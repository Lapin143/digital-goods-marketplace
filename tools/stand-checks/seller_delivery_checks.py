#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Выдача по запросу к системе продавца сквозь стенд (шаг 13 Ф5, К15; US-6.7, US-11.2, US-11.3, FT-4.3, ADR-028, Р-175, Р-184, Р-189 – Р-191;
TC-017, TC-961 – TC-967).

    make seller-delivery-check

Нужен набор full, поднятый с ускоренными сроками и отладочным файлом: make fast-up DEBUG=1, затем make keycloak-users. В профиле fast-time окно
ответа продавца 20 секунд (delivery.seller-response-seconds), диспетчер идёт раз в секунду. Окно задаёт профиль сервиса, а не проверка: стенд
перезапускать не нужно и менять нечего. Проверка меняет только режим «системы продавца» в заглушке (external-stubs) и возвращает умолчания на
выходе из любого исхода, в том числе после падения посреди сценария.

Продавцов заводит сама проверка (seller_api_checks.onboard: два, одобренные, со вторым фактором). Продавец «а» выпускает API-ключ, подключает адрес
заглушки (она входит в стендовый список dgm.seller-endpoint.allow), создаёт товар с выдачей по API, модератор его публикует, продавец ставит
остаток по ключу (setApiStock). Продавец «б» нужен как чужой: его ключ и его список заказов. Платёжная заглушка работает в режиме по умолчанию
и сама присылает подтверждение оплаты. Четыре исхода покупки такого товара:

  ответ        заглушка принимает подписанный запрос (в нём нет e-mail и идентификатора покупателя) и через 3 секунды отвечает вызовом
               respondSellerDelivery по API-ключу: 200 answered, заказ issued, одно письмо покупателю с теми же ключами, что прислал продавец, остаток
               уменьшился на заказанное, выдача delivered, запрос answered, одно delivery.accepted и delivery.delivered; продавец видит заказ без
               данных покупателя, чужой продавец его не видит, чужой ключ и неизвестный запрос получают один и тот же 404 (TC-961, TC-963, TC-964, TC-967);
  дубликат     заглушка присылает тот же ответ дважды: оба 200 answered, письмо одно, остаток уменьшился один раз (TC-967);
  ошибка       заглушка присылает отказ: ответ 200 failed, заказ cancelled с причиной seller_api_timeout и далее refunded быстрее окна, платёж refunded,
               остаток возвращён, письма с ключом нет (TC-962, TC-966);
  молчание     заглушка принимает запрос и молчит: до конца окна заказ paid, после него cancelled с причиной seller_api_timeout, платёж refunded, заказ
               refunded, остаток вернулся, письма нет, одно delivery.seller-timeout и ни одного delivery.failed; поздний ответ получает 409 и ничего не
               меняет (TC-962, TC-965, TC-967).

Канарейка (TC-964, ST-01): значения ключей продавца (их присылала заглушка) и API-ключей, секрет подписи не находятся в журналах контейнеров,
темах payment, order, delivery и inventory и дампах баз (в inventory_db лежит только шифртекст); e-mail покупателей не находится в телах запросов
к заглушке продавца. У каждого поиска есть контроль, который обязан найтись. Отдельной строкой печатается пик памяти delivery-service,
inventory-service и order-service за время сценариев (docker stats раз в 3 секунды, лимиты контейнеров).

Только стандартная библиотека Python.
"""
import hashlib
import json
import os
import re
import sys
import threading
import time
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import accounts_checks as A  # noqa: E402
import canary_checks as C  # noqa: E402
import gateway_checks as G  # noqa: E402
import kc_client as K  # noqa: E402
import memory as M  # noqa: E402
import orders_checks as O  # noqa: E402
import purchase_checks as P  # noqa: E402
import seller_api_checks as SAPI  # noqa: E402
import seller_approval_checks as SA  # noqa: E402
import stubs_admin as SB  # noqa: E402

expect = G.expect
WINDOW = 20                        # delivery.seller-response-seconds в профиле fast-time
ANSWER_DELAY_MS = 3000             # пауза заглушки между приёмом запроса и ответом (умолчание режима answer)
START_STOCK = 20                   # остаток, который продавец задаёт товару
ANSWER_PATH = '/api/v1/seller-api/delivery-requests/%s/answer'
SELLER_ORDERS = '/api/v1/seller/orders'
KEY_LINE = re.compile(r'^\d+\. (SELLR_\S+)[ \t]*$', re.M)
DATABASES = P.DATABASES + ['catalog_db']
EVENT_TOPICS = ['payment.events', 'order.events', 'delivery.events', 'inventory.events']
WATCHED = ('delivery-service', 'inventory-service', 'order-service')
MEMORY_FAIL = 90                   # процент лимита, как --fail в замерах памяти задания e2e
BUYER_FIELDS = ('buyer', 'email', 'address', 'user', 'phone')
ERROR_CODE = re.compile(r'^[a-z0-9_.-]{1,64}$')


# --------------------------------------------------------------------------------------------------- запись о стенде
def seller_request_of(order_id):
    """Запрос продавцу по заказу в delivery_db: (статус, число отправок, код ошибки) или None."""
    rows = O.sql('delivery_db', "select r.status, r.attempts, coalesce(r.error_code, '') from delivery.seller_request r "
                                "join delivery.delivery d on d.id = r.delivery_id where d.order_id = '%s'" % order_id)
    return (rows[0][0], int(rows[0][1]), rows[0][2]) if rows else None


def stock_of(fx):
    """Остаток товара по ключу продавца «а»: {available, reserved, issued}; пусто, если ответ не 200."""
    r = SAPI.by_key(fx['key_a']['value'], 'GET', SAPI.stock(fx['pid']), legit=True)
    body = r.json() if r.status == 200 else {}
    return {name: body.get(name) for name in ('available', 'reserved', 'issued')}


def stock_is(fx, available, reserved=0, issued=0, wait=45.0):
    """Ждёт, пока остаток станет таким; возвращает последнее прочитанное значение, чтобы показать его в сообщении проверки."""
    want = {'available': available, 'reserved': reserved, 'issued': issued}
    box = {}

    def reached():
        box['now'] = stock_of(fx)
        return box['now'] == want
    A.eventually(reached, wait=wait, step=1.0)
    return box['now'] == want, box['now']


def seller_orders(key_value, **query):
    """Заказы продавца по ключу: (ответ, элементы)."""
    suffix = '?' + '&'.join('%s=%s' % (k, v) for k, v in query.items()) if query else ''
    r = SAPI.by_key(key_value, 'GET', SELLER_ORDERS + suffix, legit=True)
    items = (r.json().get('items') or []) if r.status == 200 else []
    return r, items


def my_order(fx, order_id, wait=30.0):
    """Заказ в списке продавца «а»: ждёт его появления (список читает его из своей таблицы). (ответ, заказ или None)."""
    box = {}

    def listed():
        box['response'], items = seller_orders(fx['key_a']['value'], limit=100)
        box['order'] = next((i for i in items if i.get('orderId') == order_id), None)
        return box['order'] is not None
    A.eventually(listed, wait=wait, step=1.0)
    return box['response'], box.get('order')


def no_buyer_data(buyer, response, order):
    """В заказе глазами продавца нет ни одного поля покупателя, а e-mail и идентификатора покупателя нет в тексте ответа (INV-44)."""
    return (not [k for k in order if any(part in k.lower() for part in BUYER_FIELDS)]
            and buyer.email not in response.text and buyer.id not in response.text)


def seconds_between(first, second):
    return P.instant(second).timestamp() - P.instant(first).timestamp()


# --------------------------------------------------------------------------------------------------- заглушка продавца
def seller_mode_is_default(stubs, label):
    mode = (stubs.modes().get('seller') or {}).get('request') or {}
    ok = (mode.get('behavior') == 'answer' and mode.get('answerDelayMs') == ANSWER_DELAY_MS and mode.get('keysDelta') == 0
          and mode.get('failFirst') == -1 and mode.get('repeatDelayMs') == 0)
    expect('%s: заглушка продавца вернулась в исходный режим' % label, ok, str(mode)[:240])


def restore(fx):
    """Режим заглушки продавца к умолчанию и забыть подключение. Вызывается всегда: сценарий мог упасть посреди."""
    stubs = fx['stubs']
    for name, call in (('режим', lambda: stubs.restore_mode('seller')), ('подключение', lambda: stubs.disconnect_seller(fx['slug']))):
        try:
            call()
        except Exception as e:   # noqa: BLE001 - возврат не должен прятать итог проверки
            G.bad('Возврат заглушки продавца (%s) не удался' % name, '%s: %s' % (type(e).__name__, str(e)[:200]))
    seller_mode_is_default(stubs, 'Итог')


def request_for(fx, order):
    """Запрос, который заглушка приняла по заказу; ждёт его до 30 секунд. None, если не пришёл."""
    try:
        found = fx['stubs'].wait_seller_request(fx['slug'], count=1, wait=30.0, order_number=str(order['orderNumber']))
    except SB.StubsError as e:
        G.bad('Заглушка продавца не получила запрос по заказу №%s' % order['orderNumber'], '%s; отклонено заглушкой: %s' % (str(e)[:160], rejected_note(fx)))
        return None
    return found[0]


def rejected_note(fx):
    """Почему заглушка не приняла запросы платформы (подпись, тело): до пяти последних причин, чтобы журнал CI показал разницу между «не дошёл» и «не принят»."""
    try:
        items = fx['stubs'].seller_rejected(fx['slug'])
    except Exception as e:   # noqa: BLE001 - диагностика не должна скрывать исходную ошибку
        return 'не удалось прочитать (%s)' % type(e).__name__
    return str([{k: i.get(k) for k in ('kind', 'reason', 'problems')} for i in items[-5:]]) if items else 'ничего (запрос не дошёл до заглушки)'


# --------------------------------------------------------------------------------------------------- подготовка
def connect(a, slug):
    """Подключает адрес заглушки по If-Match; возвращает (адрес, секрет подписи)."""
    address = 'https://%s:8443/seller/%s/request' % (SAPI.STUB, slug)
    version = SAPI.by_token(a, 'GET', SAPI.INTEGRATION, legit=True).header('ETag') or '"0"'
    saved = SAPI.by_token(a, 'PUT', SAPI.INTEGRATION, {'endpointUrl': address}, legit=True, **{'If-Match': version})
    body = saved.json() if saved.status == 200 else {}
    secret = body.get('secret') or ''
    expect('Подключение адреса заглушки: 200, статус active, секрет подписи 64 знака hex',
           saved.status == 200 and (body.get('integration') or {}).get('status') == 'active' and bool(SAPI.SECRET_RE.match(secret)), SAPI.describe(saved)[:200])
    return address, secret


def api_product(a, moderator, stamp):
    """Товар с выдачей по API: создан, подан, одобрен, копия в inventory_db опубликована. Возвращает идентификатор или None."""
    form = dict(SA.product_form('Лунный ключ %d: выдача по запросу' % stamp), issuanceMethod='seller_api')
    created = SAPI.by_token(a, 'POST', SAPI.PRODUCTS, form)
    if not expect('Товар с выдачей по API создан продавцом: 201', created.status == 201, SAPI.describe(created)):
        return None
    pid = created.json()['productId']
    submitted = SAPI.by_token(a, 'POST', '%s/%s/submit' % (SAPI.PRODUCTS, pid))
    approved = SA.staff_decide(moderator, pid, 'approve')
    if not expect('Товар подан и одобрен модератором: 200 и 200, published',
                  submitted.status == 200 and approved.status == 200 and approved.json().get('status') == 'published', '%s; %s' % (SAPI.describe(submitted), SAPI.describe(approved))):
        return None
    caught = A.eventually(lambda: (C.copy_state(pid) or ('',))[0] == 'published', wait=90)
    return pid if expect('Копия товара в inventory_db опубликована (остаток не упрётся в 404)', bool(caught), str(C.copy_state(pid))) else None


def set_stock(key_value, pid, quantity):
    """Остаток по API по ключу продавца: версию берёт из getProductStock (If-Match). Возвращает ответ setApiStock."""
    current = SAPI.by_key(key_value, 'GET', SAPI.stock(pid), legit=True)
    version = current.json().get('version') if current.status == 200 else None
    return SAPI.by_key(key_value, 'PUT', '%s/%s/api-stock' % (SAPI.PRODUCTS, pid), {'quantity': quantity}, legit=True, **{'If-Match': '"%s"' % version})


def prepare(kc, admin, people, stamp, stubs):
    stubs.reset()
    moderator = SA.Staff(kc, admin, people['moderator-1'])
    sellers = SAPI.onboard(kc, admin, moderator, stamp)
    if not sellers:
        return None
    a, b = sellers
    print('== Продавец «а»: API-ключ, подключение адреса заглушки, товар с выдачей по API, остаток по ключу (US-11.1, US-4.7; TC-017)')
    key_a, key_b = SAPI.issuing(a, 'Выдача по запросу'), SAPI.issuing(b, 'Чужой продавец')
    if not expect('Ключи продавцов выпущены: значения получены', bool(key_a.get('value') and key_b.get('value')), SAPI.describe(key_a['response'])):
        return None
    slug = 'sdel-%d' % stamp
    address, secret = connect(a, slug)
    if not secret:
        return None
    connected = stubs.connect_seller(slug, secret, api_key=key_a['value'])
    expect('Заглушка продавца знает продавца «а»: секрет и API-ключ переданы (в ответе их нет)', connected is not None and secret not in json.dumps(connected)
           and key_a['value'] not in json.dumps(connected), str(connected)[:200])
    pid = api_product(a, moderator, stamp)
    if not pid:
        return None
    stocked = set_stock(key_a['value'], pid, START_STOCK)
    expect('TC-017. Остаток по ключу: setApiStock 200, количество %d, версия растёт' % START_STOCK, stocked.status == 200 and stocked.json().get('quantity') == START_STOCK,
           SAPI.describe(stocked))
    fx = {'kc': kc, 'admin': admin, 'stubs': stubs, 'stamp': stamp, 'a': a, 'b': b, 'key_a': key_a, 'key_b': key_b, 'slug': slug, 'address': address, 'secret': secret,
          'pid': pid, 'emails': [], 'orders': [], 'correlations': [], 'buyers': []}
    ok, now = stock_is(fx, START_STOCK, wait=15)
    expect('Остаток товара читается по ключу: доступно %d, в резерве 0, выдано 0' % START_STOCK, ok, str(now))
    return fx


def place(fx, tag, quantity):
    """Покупатель и заказ на товар по API; оплату присылает платёжная заглушка сама. (покупатель, заказ из ответа создания) или (None, None)."""
    buyer = P.new_buyer(fx['kc'], fx['admin'], fx['stamp'], tag)
    if not buyer:
        return None, None
    fx['emails'].append(buyer.email)
    fx['buyers'].append(buyer)
    created = O.create_order(buyer, fx['pid'], quantity)
    body = created.json() if created.status == 201 else {}
    if not expect('%s: заказ на %d шт. оформлен: 201, awaiting_payment' % (tag, quantity),
                  created.status == 201 and body.get('status') == 'awaiting_payment' and body.get('quantity') == quantity, O.describe(created)):
        return None, None
    fx['orders'].append(body['orderId'])
    return buyer, body


def foreign_and_unknown(fx, request_id, quantity):
    """Чужой ключ и неизвестный запрос получают один ответ 404, без ключа 401 (TC-967). Ключи в теле значений ключей продавца не повторяют."""
    body = {'keys': ['FOREIGN_CANARY_%s-%d' % (uuid.uuid4().hex, i) for i in range(quantity)]}
    foreign = SAPI.by_key(fx['key_b']['value'], 'POST', ANSWER_PATH % request_id, body)
    unknown = SAPI.by_key(fx['key_a']['value'], 'POST', ANSWER_PATH % uuid.uuid4(), body)
    expect('TC-967. Ключ другого продавца на чужой запрос: 404, неотличимо от неизвестного запроса (статус, код, заголовок)',
           foreign.status == 404 and SAPI.problem(foreign)[:3] == SAPI.problem(unknown)[:3] and unknown.status == 404, '%s | %s' % (SAPI.describe(foreign), SAPI.describe(unknown)))
    bare = G.call('POST', ANSWER_PATH % request_id, headers={'Content-Type': 'application/json'}, body=json.dumps(body).encode())
    expect('TC-967. Ответ без ключа: 401', bare.status == 401, SAPI.describe(bare))


# --------------------------------------------------------------------------------------------------- сценарии
def answer_in_time(fx):
    stubs = fx['stubs']
    print('== Ответ в срок: запрос без данных покупателя, ключи продавца в письме, остаток, заказ в списке продавца (TC-961, TC-963, TC-964, TC-967; US-6.7, US-11.3)')
    quantity = 2
    before = stock_of(fx)
    with stubs.seller_mode(request={'behavior': 'answer', 'answerDelayMs': ANSWER_DELAY_MS}):
        buyer, order = place(fx, 'sd-answer', quantity)
        if not buyer:
            return
        oid = order['orderId']
        entry = request_for(fx, order)
        if not entry:
            return
        expect('TC-963. Запрос продавцу пришёл один раз, подпись и метка времени прошли проверку заглушки, товар и количество из заказа, лишних полей нет',
               entry['attempts'] == 1 and entry['productId'] == fx['pid'] and entry['quantity'] == quantity and not entry.get('extraFields'), str({k: entry.get(k) for k in ('attempts', 'quantity', 'extraFields')}))
        wire = json.dumps(entry.get('body'), ensure_ascii=False)
        expect('TC-963. В теле запроса нет e-mail и идентификатора покупателя и адреса доставки (INV-44)', buyer.email not in wire and buyer.id not in wire and 'address' not in wire.lower(), wire[:200])
        ok, final = O.wait_order(buyer, oid, 'issued', wait=60)
        done = stubs.wait_seller_answers(fx['slug'], entry['requestId'], count=1, wait=20.0)
    answered = (done.get('answers') or [{}])[0]
    keys = answered.get('keys') or []
    expect('TC-964. Ответ продавца принят: 200 answered, принято %d ключа, заглушка прислала их по API-ключу' % quantity,
           answered.get('status') == 200 and answered.get('result') == 'answered' and answered.get('stored') == quantity and len(keys) == quantity, str(answered)[:240])
    if not expect('TC-964. Заказ стал issued после ответа, в окне %d с от оплаты' % WINDOW, ok, str(final)[:240]):
        return
    expect('TC-964. Ответ пришёл до конца окна: respondBy не раньше момента ответа', P.instant(entry['body']['respondBy']).timestamp() >= P.instant(answered['at']).timestamp(),
           '%s %s' % (entry['body'].get('respondBy'), answered.get('at')))
    paid = final.get('paidAt')
    window = seconds_between(paid, entry['body']['respondBy']) if paid else None
    expect('TC-963. Срок ответа в запросе: время оплаты плюс окно %d с' % WINDOW, window is not None and abs(window - WINDOW) <= 2, str(window))

    letters = P.wait_letters(stubs, wait=60, to=buyer.email, sender='delivery')
    text = P.body_of(letters[0]) if letters else ''
    expect('TC-964. Письмо с ключами одно, на адрес покупателя, тема «%s…»' % P.KEY_SUBJECT.strip(),
           len(letters) == 1 and letters[0]['subject'].startswith(P.KEY_SUBJECT) and letters[0]['to'] == [buyer.email], str([(m['subject'], m['to']) for m in letters])[:240])
    in_letter = KEY_LINE.findall(text)
    expect('TC-964. В письме ровно те ключи, что прислал продавец (%d штук, число названо в тексте)' % quantity,
           sorted(in_letter) == sorted(keys) and len(set(in_letter)) == quantity and '(%d шт.)' % quantity in text, '%d в письме, %d от продавца' % (len(in_letter), len(keys)))
    with_key = [m['subject'] for m in stubs.emails(to=buyer.email) if keys and keys[0] in P.body_of(m)]
    expect('TC-964. Ключ продавца есть только в письме выдачи, в письмах платформы его нет (INV-20)', with_key == [letters[0]['subject']] if letters else False, str(with_key))
    stock_ok, stock_now = stock_is(fx, before['available'] - quantity, before['reserved'], before['issued'] + quantity)
    expect('TC-964. Остаток уменьшился на заказанное: доступно %d, выдано %d, в резерве столько же, сколько было' % (before['available'] - quantity, before['issued'] + quantity),
           stock_ok, '%s -> %s' % (before, stock_now))

    delivered = A.eventually(lambda: (P.primary_of(oid) or {}).get('status') == 'delivered', wait=40)
    row = P.primary_of(oid) or {}
    expect('TC-964. Выдача первичная, одна, на адрес покупателя, %d ключа, одна попытка, статус delivered; запрос продавцу answered с одной отправкой' % quantity,
           bool(delivered) and row.get('kind') == 'primary' and len(P.deliveries_of(oid) or []) == 1 and row.get('address') == buyer.email and row.get('quantity') == quantity
           and row.get('attempt_no') == 1 and seller_request_of(oid) == ('answered', 1, ''), '%s %s' % (row, seller_request_of(oid)))
    counts = [P.outbox_count('delivery_db', oid, t) for t in ('delivery.accepted', 'delivery.delivered', 'delivery.failed', 'delivery.seller-timeout')]
    expect('TC-964. В Outbox выдачи одно delivery.accepted и одно delivery.delivered, провалов и тайм-аутов нет', counts == [1, 1, 0, 0], str(counts))

    response, mine = my_order(fx, oid)
    expect('TC-961. Продавец «а» видит заказ в списке по ключу: статус issued, товар, количество, issuanceMethod seller_api, время оплаты и выдачи',
           bool(mine) and mine.get('status') == 'issued' and mine.get('productId') == fx['pid'] and mine.get('quantity') == quantity and mine.get('issuanceMethod') == 'seller_api'
           and bool(mine.get('paidAt') and mine.get('issuedAt')), SAPI.describe(response)[:240])
    expect('TC-961. В заказе глазами продавца нет полей покупателя, e-mail и идентификатора покупателя в ответе нет, значений ключей тоже',
           bool(mine) and no_buyer_data(buyer, response, mine) and not any(k in response.text for k in keys), str(sorted(mine or {}))[:240])
    other_response, other_items = seller_orders(fx['key_b']['value'], limit=100)
    expect('TC-961. Продавец «б» заказа не видит: 200, список без заказов «а»', other_response.status == 200 and not [i for i in other_items if i.get('orderId') == oid],
           SAPI.describe(other_response)[:240])
    foreign_and_unknown(fx, entry['requestId'], quantity)
    seller_mode_is_default(stubs, 'TC-964')


def duplicate_answer(fx):
    stubs = fx['stubs']
    print('== Дубликат: тот же ответ дважды, письмо одно, остаток уменьшился один раз (TC-967; US-11.3)')
    before = stock_of(fx)
    with stubs.seller_mode(request={'behavior': 'duplicate', 'answerDelayMs': 1000, 'repeatDelayMs': 500}):
        buyer, order = place(fx, 'sd-dup', 1)
        if not buyer:
            return
        oid = order['orderId']
        entry = request_for(fx, order)
        if not entry:
            return
        done = stubs.wait_seller_answers(fx['slug'], entry['requestId'], count=2, wait=45.0)
        ok, final = O.wait_order(buyer, oid, 'issued', wait=60)
    answers = done.get('answers') or []
    expect('TC-967. Оба ответа приняты: 200 answered, принят один ключ, ключи в них одни и те же',
           len(answers) == 2 and all(x.get('status') == 200 and x.get('result') == 'answered' and x.get('stored') == 1 for x in answers) and answers[0].get('keys') == answers[1].get('keys'),
           str(answers)[:300])
    expect('TC-967. Заказ стал issued', ok, str(final)[:240])
    stubs.idle()
    time.sleep(5)                                   # повторное письмо, если бы оно было, ушло бы в ближайшие секунды
    letters = P.wait_letters(stubs, wait=30, to=buyer.email, sender='delivery')
    expect('TC-967. Письмо с ключом одно, в нём ключ продавца', len(letters) == 1 and bool(answers) and (answers[0].get('keys') or [''])[0] in P.body_of(letters[0]), str(len(letters)))
    now_ok, now = stock_is(fx, before['available'] - 1, before['reserved'], before['issued'] + 1)
    expect('TC-967. Остаток уменьшился один раз: доступно на 1 меньше, выдано на 1 больше', now_ok, '%s -> %s' % (before, now))
    expect('TC-967. Запрос продавцу answered, delivery.accepted одно, delivery.seller-timeout нет', seller_request_of(oid) == ('answered', 1, '')
           and P.outbox_count('delivery_db', oid, 'delivery.accepted') == 1 and P.outbox_count('delivery_db', oid, 'delivery.seller-timeout') == 0,
           '%s %s' % (seller_request_of(oid), P.outbox_count('delivery_db', oid, 'delivery.accepted')))


def cancelled_by_timeout(fx, label, buyer, oid, quantity, before):
    """Общая часть исходов «ошибка» и «молчание»: заказ отменён с причиной seller_api_timeout и возвращён, платёж возвращён, остаток вернулся,
    письма с ключом нет, выдача failed, одно delivery.seller-timeout без delivery.failed (SM-01 T10, T8)."""
    stubs = fx['stubs']
    ok, final = O.wait_order(buyer, oid, 'refunded', wait=90)
    expect('%s. Заказ дошёл до refunded: отмена по T10, затем возврат платежа (T8)' % label, ok, str(final)[:240])
    expect('%s. Причина отмены осталась seller_api_timeout' % label, final.get('cancelReason') == 'seller_api_timeout', str(final.get('cancelReason')))
    payment = A.eventually(lambda: O.payment_of(oid) == ('refunded', False, 'seller_api_timeout'), wait=30)
    stub_side = O.stub_payments(stubs, oid)
    expect('%s. Платёж refunded по причине seller_api_timeout, не в очереди администратора; платёжная заглушка вернула деньги' % label,
           bool(payment) and len(stub_side) == 1 and stub_side[0].get('status') == 'refunded', '%s %s' % (O.payment_of(oid), [p.get('status') for p in stub_side]))
    back_ok, back = stock_is(fx, before['available'], before['reserved'], before['issued'])
    expect('%s. Остаток вернулся: как до заказа (доступно %d, в резерве %d, выдано %d)' % (label, before['available'], before['reserved'], before['issued']), back_ok, str(back))
    letters = stubs.emails(to=buyer.email, sender='delivery')
    expect('%s. Письма с ключом от выдачи нет' % label, not letters, str([m['subject'] for m in letters]))
    row = P.primary_of(oid) or {}
    expect('%s. Выдача failed по причине seller_unavailable' % label, row.get('status') == 'failed' and row.get('fail_reason') == 'seller_unavailable', str(row))
    counts = [P.outbox_count('delivery_db', oid, t) for t in ('delivery.seller-timeout', 'delivery.failed', 'delivery.accepted', 'delivery.delivered')]
    expect('%s. В Outbox выдачи одно delivery.seller-timeout, а delivery.failed, delivery.accepted и delivery.delivered нет' % label, counts == [1, 0, 0, 0], str(counts))
    response, mine = my_order(fx, oid)
    expect('%s. Продавец видит заказ: refunded, причина seller_api_timeout, время возврата есть, полей покупателя нет' % label,
           bool(mine) and mine.get('status') == 'refunded' and mine.get('cancelReason') == 'seller_api_timeout' and bool(mine.get('refundedAt')) and no_buyer_data(buyer, response, mine),
           SAPI.describe(response)[:240])
    return final, mine


def seller_error(fx):
    stubs = fx['stubs']
    print('== Ошибка продавца: отказ по запросу, отмена и возврат быстрее окна (TC-962, TC-966; US-6.7)')
    before = stock_of(fx)
    with stubs.seller_mode(request={'behavior': 'error', 'errorCode': 'cannot_issue', 'answerDelayMs': 1000}):
        buyer, order = place(fx, 'sd-error', 1)
        if not buyer:
            return
        oid = order['orderId']
        entry = request_for(fx, order)
        if not entry:
            return
        done = stubs.wait_seller_answers(fx['slug'], entry['requestId'], count=1, wait=30.0)
        answered = (done.get('answers') or [{}])[0]
        expect('TC-966. Отказ продавца принят: 200 failed, заглушка прислала код cannot_issue', answered.get('kind') == 'error' and answered.get('status') == 200
               and answered.get('result') == 'failed' and answered.get('errorCode') == 'cannot_issue', str(answered)[:240])
        final, mine = cancelled_by_timeout(fx, 'TC-966', buyer, order['orderId'], 1, before)
    state = seller_request_of(oid)
    expect('TC-966. Запрос продавцу failed с кодом ошибки, одна отправка', bool(state) and state[0] == 'failed' and state[1] == 1 and bool(ERROR_CODE.match(state[2])), str(state))
    paid, refunded = final.get('paidAt'), (mine or {}).get('refundedAt')
    seconds = seconds_between(paid, refunded) if paid and refunded else None
    expect('TC-962. Отмена и возврат пришли быстрее окна: от оплаты до возврата %s с, окно %d с' % ('%.1f' % seconds if seconds is not None else '?', WINDOW),
           seconds is not None and 0 <= seconds < WINDOW, str(seconds))


def seller_silence(fx):
    stubs = fx['stubs']
    print('== Молчание продавца: окно %d с, отмена по T10, возврат, остаток, поздний ответ (TC-962, TC-965, TC-967; US-6.7)' % WINDOW)
    before = stock_of(fx)
    with stubs.seller_mode(request={'behavior': 'silent'}):
        buyer, order = place(fx, 'sd-silent', 1)
        if not buyer:
            return
        oid = order['orderId']
        entry = request_for(fx, order)
        if not entry:
            return
        respond_by = P.instant(entry['body']['respondBy']).timestamp()
        ok, paid = O.wait_order(buyer, oid, 'paid', wait=30)
        early = ok and paid.get('status') in ('paid', 'issued')
        expect('TC-965. Пока окно не закрыто, заказ paid: продавец ещё может ответить, отмены нет', early and paid.get('status') == 'paid' and time.time() < respond_by, str(paid.get('status')))
        box = {}

        def cancelled():
            box['order'] = O.get_order(buyer, oid).json()
            box['at'] = time.time()
            return box['order'].get('status') in ('cancelled', 'refunded')
        found = A.eventually(cancelled, wait=WINDOW + 60, step=1.0)
        expect('TC-965. После окна заказ отменён с причиной seller_api_timeout', bool(found) and box['order'].get('cancelReason') == 'seller_api_timeout', str(box.get('order'))[:240])
        expect('TC-965. Отмена не раньше конца окна (respondBy), раньше срока ни секунды', bool(found) and box['at'] >= respond_by - 0.5, '%.1f до срока' % (respond_by - box.get('at', 0)))
        cancelled_by_timeout(fx, 'TC-965', buyer, oid, 1, before)
        state = seller_request_of(oid)
        expect('TC-965. Запрос продавцу timeout, одна отправка: заглушка приняла его с первого раза, ответа не было', state == ('timeout', 1, ''), str(state))
        late = stubs.answer_seller_request(fx['slug'], entry['requestId'])
        replies = late.get('answers') or []
        expect('TC-967. Поздний ответ после тайм-аута: 409, ключи не приняты', len(replies) == 1 and replies[0].get('status') == 409, str(replies)[:240])
        keys = [k for r in replies for k in (r.get('keys') or [])]
        after = stock_is(fx, before['available'], before['reserved'], before['issued'], wait=10)
        expect('TC-967. После позднего ответа остаток прежний, письма с ключом нет, заказ refunded',
               after[0] and not stubs.emails(to=buyer.email, sender='delivery') and O.get_order(buyer, oid).json().get('status') == 'refunded', str(after[1]))
        fx['late_keys'] = keys
    seller_mode_is_default(stubs, 'TC-965')


# --------------------------------------------------------------------------------------------------- канарейка
def canary_scan(fx):
    stubs = fx['stubs']
    print('== Канарейка: ключи продавца, API-ключи и секрет подписи не найдены в журналах, темах Kafka и базах; e-mail покупателей нет в запросах к заглушке (TC-964, ST-01)')
    records = stubs.seller_requests(fx['slug'])
    delivered = [k for r in records for a in (r.get('answers') or []) for k in (a.get('keys') or [])]
    expect('Контроль: заглушка продавца принимала запросы (%d) и присылала ключи (%d значений)' % (len(records), len(delivered)), len(records) >= 4 and len(delivered) >= 3, '%d %d' % (len(records), len(delivered)))
    wanted = SAPI.needles(sorted(set(delivered + fx.get('late_keys', []))) + [fx['key_a']['value'], fx['key_b']['value'], fx['secret']])
    journal = stubs.journal(system='seller')
    journal_text = json.dumps(journal, ensure_ascii=False)
    wire = json.dumps(records, ensure_ascii=False) + journal_text
    seen = [e for e in fx['emails'] if e in wire]
    expect('Контроль: в журнале заглушки продавца записи по всем запросам (%d записей), в запросах номера заказов' % len(journal.get('items') or []),
           bool(records) and all(r.get('requestId') in journal_text for r in records) and all(str(r.get('orderNumber')) in wire for r in records), str(len(journal_text)))
    expect('Адресов покупателей (%d) в запросах платформы к системе продавца и в журнале заглушки нет' % len(fx['emails']), not seen, str(seen[:2]))

    logs = C.compose_logs()
    control = [o for o in fx['orders'] if o in logs]
    expect('Контроль: в журналах контейнеров (%d КБ) найдены номера заказов, поиск идёт по настоящим журналам' % (len(logs) // 1024), len(control) >= 3 and len(logs) > 2000, str(len(control)))
    found = C.leaks(logs, wanted)
    expect('Ключей продавца, API-ключей и секрета подписи в журналах контейнеров нет', not found, '; '.join(found)[:300])

    topics = P.read_topics(EVENT_TOPICS)
    on_wire = '\n'.join(m.value for messages in topics.values() for m in messages)
    expect('Контроль: в темах Kafka (%d КБ) найдены события заказов' % (len(on_wire) // 1024), any(o in on_wire for o in fx['orders']), str(len(on_wire)))
    found = C.leaks(on_wire, wanted)
    expect('Ключей продавца, API-ключей и секрета подписи в темах payment, order, delivery и inventory нет', not found, '; '.join(found)[:300])
    types = {(m.envelope or {}).get('type') for messages in topics.values() for m in messages if any(o in m.value for o in fx['orders'])}
    expect('Контроль: среди событий заказов есть delivery.seller-timeout (молчание и ошибка), delivery.accepted и order.issued', {'delivery.seller-timeout', 'delivery.accepted', 'order.issued'} <= types, str(sorted(t for t in types if t)))

    held = O.keys_of(fx['pid']) or {}
    expect('Контроль: в inventory_db у товара %d ключей продавца (выданные, шифртекст), по числу выданных единиц' % sum(held.values()), sum(held.values()) == 3, str(held))
    for database in DATABASES:
        text, code, error = P.dump(database)
        expect('Дамп %s снят (%d КБ)' % (database, len(text) // 1024), code == 0 and len(text) > 2000, error)
        if database == 'catalog_db':
            hashes = ["'\\x%s'" % hashlib.sha256(fx[k]['value'].encode()).hexdigest() for k in ('key_a', 'key_b')]
            expect('Контроль: в дампе catalog_db у обоих API-ключей строка с SHA-256 значения (хеш есть, значения нет)', all(h in text for h in hashes), str(len(text)))
        found = C.leaks(text, wanted)
        expect('В %s значений ключей продавца и API-ключей нет: в inventory_db только шифртекст, в catalog_db только SHA-256 ключа' % database, not found, '; '.join(found)[:300])


# --------------------------------------------------------------------------------------------------- память
class MemoryWatch(threading.Thread):
    """Пик памяти трёх сервисов за время сценариев: docker stats раз в 3 секунды, как в замерах задания (memory.py)."""

    def __init__(self):
        super().__init__(daemon=True)
        self.peaks, self.errors, self.stopped = {}, 0, threading.Event()

    def run(self):
        while not self.stopped.is_set():
            try:
                for name, (used, limit) in M.snapshot().items():
                    service = M.service_of(name)
                    if service in WATCHED and used >= self.peaks.get(service, (0, 0))[0]:
                        self.peaks[service] = (used, limit)
            except Exception:   # noqa: BLE001 - сбой замера не должен ронять сценарии
                self.errors += 1
            self.stopped.wait(M.INTERVAL)

    def finish(self):
        self.stopped.set()
        if self.is_alive():
            self.join(timeout=20)
        line = '; '.join('%s пик %.0f из %.0f МБ (%.0f%%)' % (s, self.peaks[s][0], self.peaks[s][1], 100.0 * self.peaks[s][0] / self.peaks[s][1]) for s in WATCHED if s in self.peaks)
        print('Пик памяти под сценариями выдачи по запросу: %s' % (line or 'замеров нет'))
        if os.environ.get('GITHUB_ACTIONS') and line:
            print('::notice title=память под сценариями выдачи по запросу::%s' % line.replace(';', '%0A'))
        missing = [s for s in WATCHED if s not in self.peaks]
        over = ['%s %.0f%%' % (s, 100.0 * v[0] / v[1]) for s, v in self.peaks.items() if 100.0 * v[0] / v[1] > MEMORY_FAIL]
        expect('Пик памяти delivery-service, inventory-service и order-service под сценариями измерен и не выше %d%% лимита' % MEMORY_FAIL, not missing and not over,
               'нет замеров: %s; выше порога: %s; сбоев замера %d' % (missing, over, self.errors))


# --------------------------------------------------------------------------------------------------- запуск
def run_scenarios(fx):
    watch = MemoryWatch()
    watch.start()
    try:
        for fn in (answer_in_time, duplicate_answer, seller_error, seller_silence):
            O.guarded(fn.__name__, fn, fx)
        O.guarded('canary_scan', canary_scan, fx)
    finally:
        watch.finish()
        restore(fx)


def main():
    kc, admin = K.from_env(dict(os.environ))
    people = G.users()
    if 'moderator-1' not in people:
        print('Нет тестового пользователя moderator-1 (make keycloak-users)', file=sys.stderr)
        return 2
    stubs = SB.StubsAdmin.from_env()
    fx = O.guarded('prepare', prepare, kc, admin, people, int(time.time()), stubs)
    if fx:
        run_scenarios(fx)
    else:
        G.bad('Продавцы, подключение, товар по API и остаток не подготовлены, сценарии выдачи по запросу не запускались')
    return O.summary('seller_delivery_checks')


if __name__ == '__main__':
    sys.exit(main())
