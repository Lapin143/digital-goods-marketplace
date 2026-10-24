#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Покупка от каталога до письма с ключом сквозь стенд (шаг 17 Ф4, К6; US-5.3, US-6.1 – US-6.3, FT-6.0, FT-7.0, FT-7.1, NFT-2.0, NFT-3.2, INV-18, INV-19, INV-20; TC-001 целиком).

    make purchase-check

Нужен тот же стенд, что для orders_checks.py: набор full с ускоренными сроками и отладочным файлом (make fast-up DEBUG=1, make keycloak-users).
Продавца и товары берёт у orders_checks.py (secrets/purchase_fixture.json), если ключи целы, иначе заводит заново. Платёжная и почтовая заглушки
(stubs_admin.StubsAdmin) играют шлюз и почтовый сервис и возвращаются в исходный режим на выходе из сценария.

  покупка     TC-001, шаги 5 и 6: заказ на три ключа оплачен на странице заглушки, выдача идёт сама. В заглушке почты одно письмо от выдачи на адрес
              покупателя с тремя разными ключами (INV-19), статус «доставлено» пришёл вебхуком, выдача delivered с одной попыткой, заказ issued
              со временем выдачи, в Outbox выдачи одно delivery.accepted и одно delivery.delivered, повтор статуса провайдера ничего не меняет.
              Покупатель получил от платформы письма «Заказ создан и ждёт оплаты» (с номером и ссылкой на заказ) и «Заказ оплачен» (без ключа):
              ключ уходит одним письмом и только от выдачи. Остаток ключей: на три свободных меньше, на три выданных больше
  другой      второй покупатель берёт один ключ: его ключ не совпадает ни с одним ключом первого заказа (один ключ достаётся одному заказу)
  сроки       по времени событий payment.confirmed, order.paid, delivery.accepted, order.issued видно, где проходит время между оплатой и письмом;
              от оплаты до выдачи не больше минуты. Идентификатор запроса оформления виден в журналах шлюза, order-service, inventory-service
              и payment-service; события payment.confirmed и order.paid, delivery.accepted и order.issued идут в одной трассе (потребитель продолжает
              трассу издателя); трасса рвётся на очереди выдачи: диспетчер начинает новую (Р-155), связь по номеру заказа в ключе записей Kafka
  канарейка   значения ключей (DGM-ORD-…) не найдены в журналах контейнеров, в темах payment, order и delivery.events и в дампах пяти баз;
              адресов покупателей нет в журналах delivery-service и platform-service, а в темах Kafka адрес лежит только в order.paid, единственном
              событии с адресом доставки (INV-20). У каждого поиска есть контроль, который обязан найтись

Только стандартная библиотека Python.
"""
import concurrent.futures
import json
import os
import re
import sys
import time
import uuid
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(REPO, 'tools', 'ops'))
import accounts_checks as A  # noqa: E402
import canary_checks as C  # noqa: E402
import dlq_replay as D  # noqa: E402
import gateway_checks as G  # noqa: E402
import kc_client as K  # noqa: E402
import orders_checks as O  # noqa: E402
import sellers_checks as SC  # noqa: E402
import stubs_admin as SB  # noqa: E402

expect = G.expect
QUANTITY = 3
KEY_PREFIX = 'DGM-ORD-'
KEY_LINE = re.compile(r'^\d+\. (DGM-ORD-[0-9a-f-]{36})[ \t]*$', re.M)
KEY_SUBJECT = 'Ваши ключи: '
CREATED_SUBJECT = 'Заказ создан и ждёт оплаты'
PAID_SUBJECT = 'Заказ оплачен'
HTTP_CHAIN = ['api-gateway', 'order-service', 'inventory-service', 'payment-service']
DATABASES = ['order_db', 'payment_db', 'delivery_db', 'platform_db', 'inventory_db']
EVENT_TOPICS = ['payment.events', 'order.events', 'delivery.events']
ISSUE_SECONDS = 60


# --------------------------------------------------------------------------------------------------- базы
def new_buyer(kc, admin, stamp, tag):
    """Покупатель, которого платформа уже знает. Профиль на платформе создаётся при первом запросе к ней (AccountProvisioningFilter, ADR-025): настоящий
    покупатель подтверждает телефон через платформу ещё до заказа. Здесь признак телефона выставлен в Keycloak напрямую, поэтому первый запрос
    делается явно: иначе письмо «Заказ создан» уходит человеку, которого платформа ещё не знает, и отбрасывается как письмо несуществующему получателю, а сверка создаёт профиль
    только через минуту."""
    buyer = O.new_buyer(kc, admin, stamp, tag)
    if not buyer:
        return None
    headers = SC.auth(buyer.token(), **{'Content-Type': 'application/json', 'Idempotency-Key': str(uuid.uuid4())})
    body = json.dumps({'orderId': str(uuid.uuid4()), 'reason': 'key_not_received'}).encode()
    first = G.call('POST', '/api/v1/support-tickets', headers=headers, body=body)    # заказа нет: 404 после создания профиля
    rows = O.sql('platform_db', "select count(*) from identity.user_account where lower(email) = lower('%s')" % buyer.email)
    known = bool(rows) and int(rows[0][0]) == 1
    if not expect('%s: первый запрос к платформе создал профиль покупателя (заказа нет, ответ %s)' % (tag, first.status), known, '%s %s' % (O.describe(first), rows)):
        return None
    return buyer



def one_int(database, query):
    rows = O.sql(database, query)
    return int(rows[0][0]) if rows else None


def deliveries_of(order_id):
    """Выдачи заказа в delivery_db по порядку создания: список словарей, None если база не ответила."""
    rows = O.sql('delivery_db', "select id, kind, status, attempt_no, coalesce(fail_reason, ''), coalesce(last_error_code, ''), address, quantity "
                                "from delivery.delivery where order_id = '%s' order by created_at, id" % order_id)
    if rows is None:
        return None
    return [{'id': r[0], 'kind': r[1], 'status': r[2], 'attempt_no': int(r[3]), 'fail_reason': r[4], 'error_code': r[5], 'address': r[6], 'quantity': int(r[7])}
            for r in rows]


def primary_of(order_id):
    found = deliveries_of(order_id) or []
    return found[0] if found else None


def attempts_of(delivery_id):
    """Попытки выдачи: [(номер, исход, код ошибки)]."""
    rows = O.sql('delivery_db', "select attempt_no, outcome, coalesce(error_code, '') from delivery.delivery_attempt where delivery_id = '%s' order by attempt_no" % delivery_id)
    return [(int(r[0]), r[1], r[2]) for r in rows] if rows is not None else None


def provider_statuses(delivery_id):
    rows = O.sql('delivery_db', "select provider_status from delivery.provider_status_event where delivery_id = '%s' order by provider_status" % delivery_id)
    return [r[0] for r in rows] if rows is not None else None


def watch_of(order_id):
    """Контроль доставки: (статус, срок в секундах Unix) или None."""
    rows = O.sql('delivery_db', "select status, extract(epoch from deadline_at) from delivery.delivery_watch where order_id = '%s'" % order_id)
    return (rows[0][0], float(rows[0][1])) if rows else None


def outbox_count(database, order_id, event_type):
    return one_int(database, "select count(*) from public.outbox where aggregate_id = '%s' and event_type = '%s'" % (order_id, event_type))


def tickets_of(order_id):
    """Обращения по заказу: [(источник, причина, статус, кем решено)]."""
    rows = O.sql('platform_db', "select source, reason, status, coalesce(resolved_by, '') from support.ticket where order_id = '%s' order by created_at, id" % order_id)
    return [tuple(r) for r in rows] if rows is not None else None


def support_delivery_status(order_id):
    rows = O.sql('platform_db', "select coalesce(delivery_status, '') from support.order_view where order_id = '%s'" % order_id)
    return rows[0][0] if rows else None


# --------------------------------------------------------------------------------------------------- письма
def body_of(message):
    return '%s\n%s' % (message.get('text') or '', message.get('html') or '')


def wait_letters(stubs, count=1, wait=60.0, **query):
    """Письма заглушки по условию: ждёт, пока их станет не меньше count, и возвращает всё найденное (при нехватке за wait секунд тоже)."""
    try:
        return stubs.wait_email(count=count, wait=wait, **query)
    except SB.StubsError:
        return stubs.emails(**query)


def key_letter(label, stubs, address, quantity, wait=60.0):
    """Письмо с ключами от выдачи: одно, на адрес покупателя, ровно quantity разных ключей (INV-19). Возвращает (письмо, ключи) или (None, [])."""
    found = wait_letters(stubs, wait=wait, to=address, sender='delivery')
    letter = found[0] if found else None
    if not expect('%s. Заглушка почты приняла от выдачи одно письмо с ключами на адрес покупателя, тема «%s…»' % (label, KEY_SUBJECT.strip()),
                  len(found) == 1 and letter['subject'].startswith(KEY_SUBJECT) and letter['to'] == [address], str([(m['subject'], m['to']) for m in found])[:300]):
        return None, []
    text = body_of(letter)
    keys = KEY_LINE.findall(text)
    expect('%s. В письме %d разных ключей, как в заказе (INV-19), число штук названо в тексте' % (label, quantity),
           len(keys) == quantity and len(set(keys)) == quantity and '(%d шт.)' % quantity in text, '%d ключей, %d разных' % (len(keys), len(set(keys))))
    return letter, keys


def instant(text):
    """Момент из ответа API или конверта события: доли секунды длиннее шести цифр (Instant.toString) обрезаются."""
    fixed = re.sub(r'(\.\d{6})\d+', r'\1', text.replace('Z', '+00:00'))
    return datetime.fromisoformat(fixed)


def email_is_default(stubs, label):
    mode = stubs.modes().get('email') or {}
    ok = mode.get('behavior') == 'accept' and mode.get('sender') == 'any' and mode.get('status') == 'delivered' and mode.get('dedupe') is True
    expect('%s: почтовая заглушка вернулась в исходный режим' % label, ok, str(mode)[:240])


# --------------------------------------------------------------------------------------------------- покупка
def pay_and_wait_issue(buyer, body, wait=90.0):
    """Оплата на странице заглушки и ожидание статуса issued: (заказ после выдачи или {}, секунды ожидания)."""
    started = time.time()
    page, done = O.pay_on_page(body['paymentSession']['url'])
    expect('Оплата на странице заглушки принята: 303 на платформу', page.status == 200 and done.status == 303, '%s %s' % (page.status, done.status))
    ok, order = O.wait_order(buyer, body['orderId'], 'issued', wait=wait)
    return (order if ok else {}), time.time() - started


def purchase_with_key(fx):
    kc, admin, stubs = fx['kc'], fx['admin'], fx['stubs']
    print('== Покупка до письма с ключом: оплата, выдача, письма платформы, остаток (US-5.3, US-6.1 – US-6.3, FT-7.0, FT-7.1, INV-18, INV-19; TC-001 шаги 5 и 6)')
    stubs.reset()
    buyer = new_buyer(kc, admin, fx['stamp'], 'k1')
    if not buyer:
        return
    pid = fx['plenty']
    A.eventually(lambda: not (O.keys_of(pid) or {}).get('reserved'), wait=60, step=2.0)   # резервы прошлых проверок успевают истечь
    before = O.keys_of(pid) or {}
    correlation = str(uuid.uuid4())
    with stubs.payment_mode(scenario='hold'):
        created = O.create_order(buyer, pid, QUANTITY, correlation=correlation)
        body = created.json() if created.status == 201 else {}
        if not expect('TC-001. Заказ на %d ключа оформлен: 201, awaiting_payment' % QUANTITY, created.status == 201 and body.get('status') == 'awaiting_payment'
                      and body.get('quantity') == QUANTITY, O.describe(created)):
            return
        oid, number = body['orderId'], body['orderNumber']
        waiting = wait_letters(stubs, to=buyer.email, subject=CREATED_SUBJECT)
        text = body_of(waiting[0]) if waiting else ''
        expect('TC-001. Письмо «%s»: одно, с номером заказа №%d и ссылкой на страницу заказа' % (CREATED_SUBJECT, number),
               len(waiting) == 1 and '№%d' % number in text and '/account/orders/%s' % oid in text, str([m['subject'] for m in waiting]))
        order, seconds = pay_and_wait_issue(buyer, body)
    if not expect('TC-001. Заказ стал issued за %.1f с после нажатия «Оплатить»: выдача идёт сама, без ручного шага' % seconds, order and seconds <= ISSUE_SECONDS, str(order)[:300]):
        return
    fx['correlations'].append(correlation)
    fx['emails'].append(buyer.email)
    expect('TC-001. В заказе есть время оплаты и время выдачи, выдача не раньше оплаты', bool(order.get('paidAt') and order.get('issuedAt'))
           and instant(order['paidAt']) <= instant(order['issuedAt']), 'paidAt=%s issuedAt=%s' % (order.get('paidAt'), order.get('issuedAt')))
    expect('TC-001. Заказ в истории покупателя со статусом issued, платёжной сессии у него нет: 409', any(i.get('orderId') == oid for i in O.list_orders(buyer, 'issued'))
           and G.call('GET', '%s/%s/payment-session' % (O.ORDERS, oid), headers=G.bearer(buyer.token())).status == 409)

    letter, keys = key_letter('TC-001', stubs, buyer.email, QUANTITY)
    fx['keys'] = list(keys)
    delivered = A.eventually(lambda: (primary_of(oid) or {}).get('status') == 'delivered', wait=30)
    row = primary_of(oid) or {}
    expect('TC-001. Выдача первичная, одна, на адрес покупателя из заказа, три ключа, одна попытка; статус «доставлено» пришёл вебхуком провайдера',
           delivered and row.get('kind') == 'primary' and len(deliveries_of(oid) or []) == 1 and row.get('address') == buyer.email and row.get('quantity') == QUANTITY
           and row.get('attempt_no') == 1 and row.get('status') == 'delivered' and letter and stubs.emails(to=buyer.email, sender='delivery')[0].get('status') == 'delivered',
           str(row))
    expect('TC-001. Попытка записана одна и принята провайдером, а ошибки в ней нет', attempts_of(row.get('id')) == [(1, 'accepted', '')], str(attempts_of(row.get('id'))))
    expect('TC-001. Провайдер сообщил статус «delivered» (запись в provider_status_event)', 'delivered' in (provider_statuses(row.get('id')) or []), str(provider_statuses(row.get('id'))))
    expect('TC-001. В Outbox выдачи одно delivery.accepted и одно delivery.delivered, провалов и просрочки нет',
           [outbox_count('delivery_db', oid, t) for t in ('delivery.accepted', 'delivery.delivered', 'delivery.failed', 'delivery.overdue')] == [1, 1, 0, 0],
           str([outbox_count('delivery_db', oid, t) for t in ('delivery.accepted', 'delivery.delivered', 'delivery.failed', 'delivery.overdue')]))
    expect('TC-001. Контроль доставки закрыт: ключ дошёл до срока контроля', (watch_of(oid) or ('', 0))[0] == 'closed', str(watch_of(oid)))
    expect('TC-001. В Outbox заказа одно order.paid и одно order.issued', O.outbox_count(oid, 'order.paid') == 1 and outbox_count('order_db', oid, 'order.issued') == 1,
           '%s %s' % (O.outbox_count(oid, 'order.paid'), outbox_count('order_db', oid, 'order.issued')))

    # Повтор статуса от провайдера (ADR-011): повторное «delivered» принято и ничего не меняет
    message_id = (letter or {}).get('messageId', '')
    if message_id:
        stubs.email_status(message_id, 'delivered')
        stubs.idle()
    expect('TC-001. Повтор статуса «delivered» от провайдера ничего не меняет: одно delivery.delivered, одна запись статуса, выдача delivered',
           outbox_count('delivery_db', oid, 'delivery.delivered') == 1 and (provider_statuses(row.get('id')) or []).count('delivered') == 1
           and (primary_of(oid) or {}).get('status') == 'delivered', '%s %s' % (outbox_count('delivery_db', oid, 'delivery.delivered'), provider_statuses(row.get('id'))))

    # Письма платформы: «оплачен» без ключа, «создан» уже проверен до оплаты
    paid_letters = wait_letters(stubs, to=buyer.email, subject=PAID_SUBJECT)
    paid_text = body_of(paid_letters[0]) if paid_letters else ''
    expect('TC-001. Письмо «%s»: одно, с номером заказа №%d, ключа в нём намеренно нет, ключ идёт отдельным письмом' % (PAID_SUBJECT, number),
           len(paid_letters) == 1 and '№%d' % number in paid_text and KEY_PREFIX not in paid_text and 'отдельным письмом' in paid_text, str([m['subject'] for m in paid_letters]))
    everything = stubs.emails(to=buyer.email)
    with_key = [m['subject'] for m in everything if KEY_PREFIX in body_of(m)]
    expect('TC-001. Ключи есть только в письме выдачи: у покупателя %d письма, ключ виден в одном, от выдачи (INV-20)' % len(everything),
           with_key == [letter['subject']] if letter else False, str(with_key))
    after = O.keys_of(pid) or {}
    expect('TC-001. Остаток ключей: свободных на %d меньше, выданных на %d больше, зарезервированных столько же' % (QUANTITY, QUANTITY),
           after.get('free', 0) == before.get('free', 0) - QUANTITY and after.get('issued', 0) == before.get('issued', 0) + QUANTITY
           and after.get('reserved', 0) == before.get('reserved', 0), '%s -> %s' % (before, after))
    O.modes_are_default(stubs, 'TC-001')
    email_is_default(stubs, 'TC-001')
    fx['orders'].append(oid)


def another_buyer_other_key(fx):
    kc, admin, stubs = fx['kc'], fx['admin'], fx['stubs']
    print('== Второй покупатель: его ключ не совпадает с ключами первого заказа (INV-04, INV-18; TC-001)')
    if not fx.get('keys'):
        G.bad('Контроль: ключи первого заказа неизвестны, сравнивать не с чем (сценарий покупки не дошёл до письма)')
        return
    buyer = new_buyer(kc, admin, fx['stamp'], 'k2')
    if not buyer:
        return
    with stubs.payment_mode(scenario='hold'):
        created = O.create_order(buyer, fx['plenty'], 1)
        body = created.json() if created.status == 201 else {}
        if not expect('TC-001. Заказ второго покупателя на один ключ оформлен: 201', created.status == 201 and body.get('status') == 'awaiting_payment', O.describe(created)):
            return
        order, seconds = pay_and_wait_issue(buyer, body)
    expect('TC-001. Заказ второго покупателя стал issued за %.1f с' % seconds, bool(order) and seconds <= ISSUE_SECONDS, str(order)[:200])
    letter, keys = key_letter('TC-001', stubs, buyer.email, 1)
    expect('TC-001. Ключ второго заказа не совпадает ни с одним ключом первого (один ключ достаётся одному заказу)', bool(keys) and not set(keys) & set(fx['keys']), str(len(keys)))
    fx['emails'].append(buyer.email)
    fx['orders'].append(body['orderId'])
    O.modes_are_default(stubs, 'TC-001')


# --------------------------------------------------------------------------------------------------- события и трасса
def read_topics(names):
    """Записи тем Kafka от имени kafka-init, темы читаются параллельно: {тема: [DeadMessage]}."""
    kafka = D.Kafka(os.path.join(REPO, 'secrets'), os.environ.get('DGM_DATA_NETWORK', 'dgm_data'), 'kafka-init', 'kafka:9093', D.kafka_image())
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(names)) as pool:
        return dict(zip(names, pool.map(kafka.read, names)))


def events_of(topics, order_id):
    """События заказа: {тип: конверт}; заказ определяется по data.orderId (ключ записи у платёжных событий платёж)."""
    found = {}
    for messages in topics.values():
        for m in messages:
            envelope = m.envelope or {}
            if (envelope.get('data') or {}).get('orderId') == order_id:
                found.setdefault(envelope.get('type'), envelope)
    return found


def trace_of(envelope):
    return (envelope.get('traceparent') or '').split('-')[1:3]    # [идентификатор трассы, идентификатор отрезка]


def services_seen(correlation, services, attempts=15):
    """Контейнеры из списка, в журналах которых виден идентификатор: {имя}."""
    seen = set()
    for _ in range(attempts):
        out = C.sh(G.COMPOSE + ['logs', '--no-color', '--since', '15m'] + list(services), timeout=120)
        lines = [l for l in (out.stdout + out.stderr).splitlines() if correlation in l and '|' in l]
        seen = {l.split('|', 1)[0].strip().rsplit('-', 1)[0] for l in lines}
        if set(services) <= seen:
            break
        time.sleep(2)
    return seen


def timeline_and_trace(fx):
    print('== Время и трасса заказа: где проходит время между оплатой и письмом, как идёт идентификатор запроса и трасса событий (NFT-2.0)')
    if not fx['orders'] or not fx['correlations']:
        G.bad('Контроль: заказов для разбора времени и трассы нет, сценарий покупки не дошёл до выдачи')
        return
    oid, correlation = fx['orders'][0], fx['correlations'][0]
    seen = services_seen(correlation, HTTP_CHAIN)
    expect('Идентификатор запроса оформления виден в журналах %s (найден в %s)' % (', '.join(HTTP_CHAIN), ', '.join(sorted(seen)) or 'нигде'), set(HTTP_CHAIN) <= seen, str(sorted(seen)))
    topics = read_topics(EVENT_TOPICS)
    fx['topics'] = topics
    events = events_of(topics, oid)
    chain = ['payment.confirmed', 'order.paid', 'delivery.accepted', 'order.issued']
    missing = [t for t in chain if t not in events]
    if not expect('В темах Kafka есть события заказа: %s' % ', '.join(chain), not missing, 'нет: %s; есть: %s' % (missing, sorted(events))):
        return
    stamps = [instant(events[t]['time']) for t in chain]
    steps = ['%s → %s: %d мс' % (chain[i], chain[i + 1], (stamps[i + 1] - stamps[i]).total_seconds() * 1000) for i in range(len(chain) - 1)]
    print('   Время между событиями заказа: ' + '; '.join(steps))
    expect('Время событий идёт по порядку: payment.confirmed не позже order.paid, дальше delivery.accepted и order.issued',
           all(stamps[i] <= stamps[i + 1] for i in range(len(stamps) - 1)), str([events[t]['time'] for t in chain]))
    total = (stamps[-1] - stamps[0]).total_seconds()
    expect('От подтверждения платежа до выданного заказа %.1f с, не больше %d с' % (total, ISSUE_SECONDS), total <= ISSUE_SECONDS, str(total))
    traces = {t: trace_of(events[t]) for t in chain}
    valid = all(len(v) == 2 and re.fullmatch(r'[0-9a-f]{32}', v[0]) and re.fullmatch(r'[0-9a-f]{16}', v[1]) for v in traces.values())
    expect('У четырёх событий есть traceparent и correlationid', valid and all(events[t].get('correlationid') for t in chain), str(traces))
    expect('payment.confirmed и order.paid в одной трассе и с одним correlationid, отрезки разные: потребитель продолжает трассу издателя',
           valid and traces['payment.confirmed'][0] == traces['order.paid'][0] and traces['payment.confirmed'][1] != traces['order.paid'][1]
           and events['payment.confirmed'].get('correlationid') == events['order.paid'].get('correlationid'), '%s %s' % (traces['payment.confirmed'], traces['order.paid']))
    expect('delivery.accepted и order.issued в одной трассе и с одним correlationid: событие выдачи продолжается в order-service',
           valid and traces['delivery.accepted'][0] == traces['order.issued'][0] and traces['delivery.accepted'][1] != traces['order.issued'][1]
           and events['delivery.accepted'].get('correlationid') == events['order.issued'].get('correlationid'), '%s %s' % (traces['delivery.accepted'], traces['order.issued']))
    expect('Все события заказа лежат в Kafka под ключом заказа: по номеру заказа восстанавливается весь путь, даже где трасса начинается заново',
           all(m.key == oid for topic in ('order.events', 'delivery.events') for m in topics[topic] if (m.envelope or {}).get('data', {}).get('orderId') == oid), oid)


# --------------------------------------------------------------------------------------------------- канарейка
def dump(database):
    r = C.sh(G.COMPOSE + ['exec', '-T', 'postgres', 'pg_dump', '-U', 'postgres', '-d', database, '--column-inserts', '--no-owner'], timeout=300)
    return r.stdout, r.returncode, (r.stderr or '')[-200:]


def leak_scan(fx, label):
    """Значения ключей и адреса покупателей там, где их быть не должно (INV-20): журналы, темы Kafka, дампы баз."""
    print('== Канарейка: значений ключей и адресов покупателей нет в журналах, темах Kafka и базах (%s; INV-20, NFT-3.2)' % label)
    needles = [KEY_PREFIX, KEY_PREFIX.encode().hex()]
    logs = C.compose_logs()
    control = [o for o in fx['orders'] if o in logs]
    expect('Контроль: в журналах контейнеров (%d КБ) найден номер заказа, поиск идёт по настоящим журналам' % (len(logs) // 1024), bool(control) and len(logs) > 20000, str(len(logs)))
    found = C.leaks(logs, needles)
    expect('Значений ключей (%s…) в журналах контейнеров стенда нет' % KEY_PREFIX, not found, '; '.join(found)[:300])
    for service in ('delivery-service', 'platform-service'):
        text = C.sh(G.COMPOSE + ['logs', '--no-color', service], timeout=300)
        text = text.stdout + text.stderr
        seen = [e for e in fx['emails'] if e in text]
        expect('Адресов покупателей (%d) в журнале %s (%d КБ) нет' % (len(fx['emails']), service, len(text) // 1024), len(text) > 2000 and not seen, str(seen[:2]))
    topics = fx.get('topics')
    if topics:
        wire = '\n'.join(m.value for messages in topics.values() for m in messages)
        expect('Контроль: в темах Kafka (%d КБ) найдены события заказов' % (len(wire) // 1024), any(o in wire for o in fx['orders']), str(len(wire)))
        found = C.leaks(wire, needles)
        expect('Значений ключей в темах payment.events, order.events и delivery.events нет', not found, '; '.join(found)[:300])
        carrying = sorted({(m.envelope or {}).get('type') or '?' for messages in topics.values() for m in messages if any(e in m.value for e in fx['emails'])})
        expect('Адрес покупателя в темах payment.events, order.events и delivery.events есть только в order.paid: «Выдаче» его передаёт одно событие (найден в %s)'
               % (', '.join(carrying) or 'нигде'), set(carrying) <= {'order.paid'}, str(carrying))
    for database in DATABASES:
        text, code, error = dump(database)
        expect('Дамп %s снят (%d КБ)' % (database, len(text) // 1024), code == 0 and len(text) > 2000, error)
        found = C.leaks(text, needles)
        expect('Значений ключей в %s нет: в базе остаётся шифртекст inventory.key.ciphertext, а письмо собирается в памяти' % database, not found, '; '.join(found)[:300])


# --------------------------------------------------------------------------------------------------- запуск
def run_scenarios(fx):
    for fn in (purchase_with_key, another_buyer_other_key, timeline_and_trace):
        O.guarded(fn.__name__, fn, fx)
    O.guarded('leak_scan', leak_scan, fx, 'purchase_checks')


def main():
    kc, admin = K.from_env(dict(os.environ))
    people = G.users()
    if 'moderator-1' not in people:
        print('Нет тестового пользователя moderator-1 (make keycloak-users)', file=sys.stderr)
        return 2
    fx = O.context(kc, admin, people, int(time.time()), scarce_needed=False)
    if fx:
        fx['orders'] = []
        run_scenarios(fx)
    else:
        G.bad('Продавец и товары для заказов не заведены, сценарии покупки не запускались')
    return O.summary('purchase_checks')


if __name__ == '__main__':
    sys.exit(main())
