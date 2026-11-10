#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Webhook продавцу сквозь стенд (шаг 16 Ф5, К16; US-11.4, FT-10.3, BG-02, ADR-028, Р-193, Р-196; TC-018, TC-970 – TC-974).

    make webhook-check

Нужен набор full, поднятый с ускоренными сроками и отладочным файлом: make fast-up DEBUG=1, затем make keycloak-users. В профиле fast-time паузы повторов
webhook 1, 2, 3, 4 и 5 секунд вместо 30 секунд, 2, 10, 30 минут и 2 часов (application-fast-time.yml платформы), отправитель идёт раз в секунду, окно ответа
продавца 20 секунд. Проверка меняет только режим «системы продавца» в заглушке (external-stubs) и возвращает его к умолчанию при выходе из любого исхода.

Продавца, подключение адреса заглушки, товар с выдачей по API и остаток заводит та же подготовка, что у выдачи по запросу (seller_delivery_checks.prepare). Платформа
шлёт webhook на тот же адрес подключения, что и запрос выдачи; запрос несёт X-Request-Id, webhook X-Event-Id и X-Event-Type, по ним заглушка их различает. Заказ на
такой товар даёт четыре события для продавца: order.paid после оплаты, order.issued после ответа продавца (отмена и возврат только если заказ отменён). Три сценария:

  выдача и подпись   order.paid и order.issued: каждое принято заглушкой один раз с верной подписью (заглушка не отвергла ни одного вызова), X-Event-Id и X-Event-Type равны
                     eventId и type в теле, тело ровно из семи полей и равно телу в очереди платформы (notification.webhook_delivery), строка очереди sent с одной попыткой;
                     ни e-mail, ни идентификатора покупателя нет ни в теле, ни в строке очереди (TC-970, TC-974);
  повторы            заглушка отказывает первым двум попыткам (503), третья принимается: три попытки с одним eventId, паузы 1 и 2 секунды (с допуском), строка sent с тремя
                     попытками, в списке продавца sent и attempts 3 (TC-971);
  постоянный сбой    заглушка отказывает всегда (500): шесть попыток с паузами 1, 2, 3, 4 и 5 секунд, статус failed, ничего не принято; запись видна в listWebhookDeliveries
                     (только свои, новые выше, фильтр по статусу, курсор, без тела и подписи), чужой продавец её не видит (TC-972).

Канарейка (TC-973, TC-974, ST-01): секрет подписи и адрес подключения не находятся в журнале platform-service, e-mail и идентификаторы покупателей не находятся в телах
принятых webhook и в строках очереди; у журнала есть контроль, который обязан найтись. Отдельной строкой печатается пик памяти platform-service за время сценариев
(docker stats раз в 3 секунды, порог красного 90 %, Р-196).

Только стандартная библиотека Python.
"""
import json
import os
import re
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import accounts_checks as A  # noqa: E402
import canary_checks as C  # noqa: E402
import gateway_checks as G  # noqa: E402
import kc_client as K  # noqa: E402
import orders_checks as O  # noqa: E402
import seller_api_checks as SAPI  # noqa: E402
import seller_delivery_checks as SD  # noqa: E402
import stubs_admin as SB  # noqa: E402

expect = G.expect
LADDER = [1, 2, 3, 4, 5]            # dgm.notification.webhook.retry-delays в профиле fast-time платформы, секунды
BELOW = 0.3                         # пауза между попытками не короче ступени минус эта доля секунды (разные часы не участвуют: время берёт заглушка)
ABOVE = 4.0                         # и не длиннее ступени плюс просмотр очереди раз в секунду и запас на нагрузку CI
LISTING = '/api/v1/seller/webhook-deliveries'
BODY_KEYS = {'eventId', 'type', 'occurredAt', 'orderNumber', 'productId', 'quantity', 'amount'}
LISTING_KEYS = {'deliveryId', 'eventId', 'type', 'orderNumber', 'status', 'attempts', 'lastResponseCode', 'lastAttemptAt', 'nextAttemptAt', 'createdAt'}
FORBIDDEN_IN_LISTING = ('body', 'payload', 'signature', 'secret', 'url', 'endpoint', 'email', 'buyer')
WATCHED = ('platform-service',)
SECRET_RE = re.compile(r'^[0-9a-f]{64}$')


# --------------------------------------------------------------------------------------------------- разбор (без стенда, есть модульные тесты)
def gaps(attempts):
    """Паузы между попытками по порядку, секунды: по меткам времени заглушки (`at`)."""
    stamps = [SD.P.instant(a['at']).timestamp() for a in attempts]
    return [later - earlier for earlier, later in zip(stamps, stamps[1:])]


def pause_problems(attempts, ladder=LADDER):
    """Паузы, не попавшие в ступень: [(номер паузы, длина, ступень)]. Пауза после n-й неудачи равна n-й ступени (Р-193); допуск BELOW вниз и ABOVE вверх."""
    found = []
    for index, gap in enumerate(gaps(attempts)):
        step = ladder[index] if index < len(ladder) else None
        if step is None or gap < step - BELOW or gap > step + ABOVE:
            found.append((index + 1, round(gap, 2), step))
    return found


def body_problems(body, number, product_id, quantity, event_type):
    """Чем тело webhook отличается от обещанного: ровно семь полей, номер, товар, количество, тип, сумма с валютой, время. Пусто, если всё верно."""
    problems = []
    if not isinstance(body, dict):
        return ['тело не объект']
    if set(body) != BODY_KEYS:
        problems.append('поля %s, ожидались %s' % (sorted(body), sorted(BODY_KEYS)))
    if body.get('orderNumber') != number:
        problems.append('orderNumber %r, ожидался %r' % (body.get('orderNumber'), number))
    if body.get('productId') != product_id:
        problems.append('productId не тот')
    if body.get('quantity') != quantity:
        problems.append('quantity %r, ожидалось %r' % (body.get('quantity'), quantity))
    if body.get('type') != event_type:
        problems.append('type %r, ожидался %r' % (body.get('type'), event_type))
    amount = body.get('amount')
    if not (isinstance(amount, dict) and isinstance(amount.get('amount'), int) and amount['amount'] > 0 and amount.get('currency') == 'RUB'):
        problems.append('amount %r: ожидалась сумма в минорных единицах и валюта RUB' % (amount,))
    try:
        SD.P.instant(body.get('occurredAt'))
    except (TypeError, ValueError, AttributeError):
        problems.append('occurredAt не время')
    return problems


def listing_problems(items):
    """Нарушения вида списка доставок: лишние поля (тело, подпись, адрес), порядок «новые выше»."""
    problems = []
    for item in items:
        extra = sorted(set(item) - LISTING_KEYS)
        hidden = [k for k in item if any(part in k.lower() for part in FORBIDDEN_IN_LISTING)]
        if extra or hidden:
            problems.append('лишние поля %s' % (extra or hidden))
            break
    created = [i.get('createdAt') for i in items if i.get('createdAt')]
    parsed = [SD.P.instant(c).timestamp() for c in created]
    if parsed != sorted(parsed, reverse=True):
        problems.append('порядок не «новые выше»')
    return problems


# --------------------------------------------------------------------------------------------------- состояние платформы и заглушки
def rows_of(number):
    """Строки очереди webhook по номеру заказа: список словарей; None, если база не ответила."""
    rows = O.sql('platform_db', "select event_id, event_type, status, attempts, coalesce(last_response_code::text, ''), coalesce(last_error_code, ''), "
                                "next_attempt_at is not null, payload::text from notification.webhook_delivery where order_number = %d order by created_at, id" % number)
    if rows is None:
        return None
    return [{'eventId': r[0], 'type': r[1], 'status': r[2], 'attempts': int(r[3]), 'code': r[4], 'error': r[5], 'queued': r[6] == 't', 'payload': r[7]} for r in rows]


def wait_rows(number, types, statuses, wait):
    """Ждёт, пока в очереди есть строки заказа по всем `types` и у каждой статус из `statuses`. Возвращает (строки по типам, последнее чтение)."""
    box = {}

    def ready():
        box['rows'] = rows_of(number) or []
        by_type = {r['type']: r for r in box['rows']}
        box['by_type'] = by_type
        return all(t in by_type and by_type[t]['status'] in statuses for t in types)
    ok = A.eventually(ready, wait=wait, step=1.0)
    return bool(ok), box.get('by_type', {}), box.get('rows', [])


def stub_event(fx, event_id):
    """Что заглушка знает о событии: (принятый webhook или None, попытки по порядку)."""
    found = fx['stubs'].seller_webhooks(fx['slug'], event_id=event_id)
    items = found.get('items') or []
    return (items[0] if items else None), sorted(found.get('attempts') or [], key=lambda a: a.get('attempt') or 0)


def listing(fx, key='a', **query):
    suffix = '?' + '&'.join('%s=%s' % (k, v) for k, v in query.items()) if query else ''
    r = SAPI.by_token(fx[key], 'GET', LISTING + suffix, legit=True)
    body = r.json() if r.status == 200 else {}
    return r, body.get('items') or [], (body.get('page') or {}).get('nextCursor')


def place(fx, tag, quantity):
    """Покупатель и заказ на товар по API (как у выдачи по запросу); номер заказа запоминается для канарейки."""
    buyer, order = SD.place(fx, tag, quantity)
    if order:
        fx.setdefault('numbers', []).append(order['orderNumber'])
    return buyer, order


def reset_mode(fx):
    """Режим заглушки продавца к умолчанию, подключение остаётся (следующий сценарий шлёт на тот же адрес). Полный возврат с отключением делает SD.restore в конце."""
    fx['stubs'].restore_mode('seller')
    SD.seller_mode_is_default(fx['stubs'], 'После сценария')


def buyer_traces(buyers, text):
    """Следы покупателей в тексте: адрес и идентификатор."""
    return [v[:6] + '…' for buyer in buyers for v in (buyer.email, buyer.id) if v and v in text]


# --------------------------------------------------------------------------------------------------- сценарии
def delivered_and_signed(fx):
    print('== Webhook продавцу: order.paid и order.issued, подпись, заголовки, тело (US-11.4, FT-10.3; TC-018, TC-970, TC-974)')
    buyer, order = place(fx, 'wh-ok', 2)
    if not order:
        return
    number = order['orderNumber']
    ok, rows, _ = wait_rows(number, ('order.paid', 'order.issued'), ('sent',), wait=90)
    if not expect('TC-970. Платформа отправила продавцу order.paid и order.issued: обе строки очереди в статусе sent', ok, str({t: (r['status'], r['attempts']) for t, r in rows.items()})):
        return
    seen = []
    for event_type in ('order.paid', 'order.issued'):
        row = rows[event_type]
        item, attempts = stub_event(fx, row['eventId'])
        label = 'TC-970. %s' % event_type
        expect('%s: заглушка приняла событие один раз с первой попытки, без повторов и дублей' % label,
               item is not None and len(attempts) == 1 and attempts[0].get('outcome') == 'accepted' and item.get('duplicates') == 0, str((item and item.get('attempts'), attempts))[:240])
        if item is None:
            continue
        seen.append((event_type, item))
        expect('%s: заголовки X-Event-Id и X-Event-Type равны eventId и type в теле, eventId из очереди платформы' % label,
               item.get('eventIdHeader') == item['body'].get('eventId') == row['eventId'] and item.get('eventTypeHeader') == item['body'].get('type') == event_type,
               '%s | %s | %s' % (item.get('eventIdHeader'), item['body'].get('eventId'), row['eventId']))
        problems = body_problems(item['body'], number, fx['pid'], 2, event_type)
        expect('%s: тело ровно из семи полей, номер, товар, количество, тип, сумма с валютой и время верны, лишних полей нет' % label, not problems and not item.get('extraFields'),
               '%s; лишние: %s' % (problems, item.get('extraFields')))
        try:
            same = json.loads(row['payload']) == item['body']
        except ValueError:
            same = False
        expect('%s: тело в заглушке равно телу в очереди платформы (тело собрано один раз и дальше не менялось)' % label, same, row['payload'][:160])
        expect('%s: строка очереди sent, попытка одна, ответ 200, следующей попытки нет' % label,
               row['status'] == 'sent' and row['attempts'] == 1 and row['code'] == '200' and not row['queued'], str((row['status'], row['attempts'], row['code'], row['queued'])))
        text = json.dumps(item['body'], ensure_ascii=False) + row['payload']
        expect('TC-974. %s: ни e-mail, ни идентификатора покупателя нет ни в теле, ни в строке очереди' % event_type, not buyer_traces([buyer], text), str(buyer_traces([buyer], text)))
    if len(seen) == 2:
        paid, issued = seen[0][1]['body'], seen[1][1]['body']
        expect('TC-970. order.paid раньше order.issued, события разные', SD.seconds_between(paid['occurredAt'], issued['occurredAt']) > 0 and paid['eventId'] != issued['eventId'],
               '%s | %s' % (paid['occurredAt'], issued['occurredAt']))
    rejected = [r for r in fx['stubs'].seller_rejected(fx['slug']) if r.get('kind') == 'webhook']
    expect('TC-970. Заглушка не отвергла ни одного webhook: подпись, метка времени и заголовки верны', not rejected, str(rejected)[:240])


def retried(fx):
    print('== Webhook продавцу: повторы, два отказа и успех, один eventId, паузы 1 и 2 секунды (FT-10.3, Р-193; TC-018, TC-971)')
    # Ответ продавца по запросу выдачи задерживаем до 8 секунд: order.issued появится, когда три попытки order.paid уже сделаны, и счётчик отказов достанется ему
    fx['stubs'].set_mode('seller', request={'answerDelayMs': 8000}, webhook={'failFirst': 2, 'failStatus': 503})
    buyer, order = place(fx, 'wh-retry', 1)
    if not order:
        return
    number = order['orderNumber']
    ok, rows, _ = wait_rows(number, ('order.paid',), ('sent',), wait=60)
    if not expect('TC-971. order.paid после двух отказов доставлен: строка очереди sent', ok, str({t: (r['status'], r['attempts']) for t, r in rows.items()})):
        return
    row = rows['order.paid']
    item, attempts = stub_event(fx, row['eventId'])
    expect('TC-971. Три попытки: отказ 503, отказ 503, принято; номера 1, 2, 3', [(a.get('attempt'), a.get('outcome')) for a in attempts] == [(1, 'failed:503'), (2, 'failed:503'), (3, 'accepted')],
           str([(a.get('attempt'), a.get('outcome')) for a in attempts]))
    expect('TC-971. X-Event-Id и eventId один во всех попытках', len({a.get('eventId') for a in attempts}) == 1 and all(a.get('eventIdHeader') == row['eventId'] for a in attempts),
           str([(a.get('eventId'), a.get('eventIdHeader')) for a in attempts])[:240])
    late = pause_problems(attempts)
    expect('TC-971. Паузы между попытками 1 и 2 секунды (допуск вниз %.1f, вверх %.0f с): ступени лестницы повторов идут по порядку, не короче и не раньше срока' % (BELOW, ABOVE), not late and len(attempts) == 3,
           'вне допуска: %s; пауз %s' % (late, [round(g, 2) for g in gaps(attempts)]))
    try:
        same = item is not None and json.loads(row['payload']) == item['body']
    except ValueError:
        same = False
    expect('TC-971. Принятое тело равно телу в очереди: повтор отправляет то же тело, что и первая попытка', same, row['payload'][:160])
    expect('TC-971. Строка очереди: sent, три попытки, ответ 200, следующей нет', row['status'] == 'sent' and row['attempts'] == 3 and row['code'] == '200' and not row['queued'],
           str((row['status'], row['attempts'], row['code'], row['queued'])))
    _, items, _ = listing(fx, limit=100)
    mine = next((i for i in items if i.get('eventId') == row['eventId']), None)
    expect('TC-971. listWebhookDeliveries: событие в списке продавца, статус sent, попыток 3, код 200', mine is not None and mine.get('status') == 'sent' and mine.get('attempts') == 3
           and mine.get('lastResponseCode') == 200 and mine.get('nextAttemptAt') is None, str(mine)[:240])
    ok_issued, issued, _ = wait_rows(number, ('order.issued',), ('sent',), wait=60)
    expect('TC-971. order.issued этого заказа доставлен следом без отказов (счётчик отказов исчерпан order.paid)',
           ok_issued and issued['order.issued']['attempts'] == 1, str({t: (r['status'], r['attempts']) for t, r in issued.items()}))
    reset_mode(fx)


def failed_for_good(fx):
    print('== Webhook продавцу: постоянный сбой, шесть попыток, failed, список доставок (FT-10.3, US-11.4, Р-193; TC-018, TC-972)')
    fx['stubs'].set_mode('seller', webhook={'failFirst': -1, 'failStatus': 500})
    buyer, order = place(fx, 'wh-fail', 1)
    if not order:
        return
    number = order['orderNumber']
    ok, rows, _ = wait_rows(number, ('order.paid', 'order.issued'), ('failed',), wait=120)
    if not expect('TC-972. Оба события заказа после шести отказов получили статус failed (15 секунд пауз плюс очередь)', ok, str({t: (r['status'], r['attempts']) for t, r in rows.items()})):
        reset_mode(fx)
        return
    for event_type in ('order.paid', 'order.issued'):
        row = rows[event_type]
        item, attempts = stub_event(fx, row['eventId'])
        label = 'TC-972. %s' % event_type
        expect('%s: шесть попыток, все отказ 500, ничего не принято' % label, item is None and [a.get('outcome') for a in attempts] == ['failed:500'] * 6, str([a.get('outcome') for a in attempts]))
        late = pause_problems(attempts)
        expect('%s: паузы 1, 2, 3, 4 и 5 секунд (лестница повторов), после шестой неудачи попыток больше нет' % label, not late and len(attempts) == 6,
               'вне допуска: %s; пауз %s' % (late, [round(g, 2) for g in gaps(attempts)]))
        expect('%s: строка очереди failed, шесть попыток, код ответа 500, причина seller_http_500, следующей попытки нет' % label,
               row['status'] == 'failed' and row['attempts'] == 6 and row['code'] == '500' and row['error'] == 'seller_http_500' and not row['queued'],
               str((row['status'], row['attempts'], row['code'], row['error'], row['queued'])))
    r, items, _ = listing(fx, status='failed', limit=100)
    mine = {i.get('type'): i for i in items if i.get('orderNumber') == number}
    expect('TC-972. listWebhookDeliveries?status=failed: оба события заказа в списке, failed, попыток 6, код 500, следующей нет',
           r.status == 200 and set(mine) == {'order.paid', 'order.issued'} and all(i.get('status') == 'failed' and i.get('attempts') == 6 and i.get('lastResponseCode') == 500
                                                                                  and i.get('nextAttemptAt') is None for i in mine.values()), SAPI.describe(r)[:240] + ' | ' + str(mine)[:200])
    problems = listing_problems(items)
    expect('TC-972. В списке нет тела, подписи, секрета и адреса, порядок «новые выше»', not problems and bool(items), str(problems))
    _, sent_only, _ = listing(fx, status='sent', limit=100)
    expect('TC-972. Фильтр status=sent не показывает failed', not [i for i in sent_only if i.get('orderNumber') == number], str(sent_only)[:200])
    _, mixed, _ = listing(fx, status='queued,failed', limit=100)
    expect('TC-972. Фильтр status=queued,failed показывает оба события', {i.get('type') for i in mixed if i.get('orderNumber') == number} == {'order.paid', 'order.issued'}, str(mixed)[:200])
    first, first_items, cursor = listing(fx, limit=1)
    second, second_items, _ = listing(fx, limit=1, cursor=cursor) if cursor else (None, [], None)
    expect('TC-972. Курсор: страница из одного элемента, по курсору другой элемент', len(first_items) == 1 and bool(cursor) and len(second_items) == 1
           and first_items[0].get('deliveryId') != second_items[0].get('deliveryId'), '%s | %s' % (SAPI.describe(first)[:120], SAPI.describe(second)[:120] if second else 'курсора нет'))
    for label, query, parameter in (('status=lost', {'status': 'lost'}, 'status'), ('cursor=AAAA', {'cursor': 'AAAA'}, 'cursor'),
                                    ('sellerId чужой или свой', {'sellerId': fx['a'].id}, 'sellerId')):
        bad, _, _ = listing(fx, **query)
        errors = (bad.json() or {}).get('errors') if bad.status == 422 else None
        expect('TC-972. Параметр %s: 422, параметр назван в errors, список не отдаётся' % label,
               bad.status == 422 and bool(errors) and errors[0].get('parameter') == parameter, SAPI.describe(bad)[:240])
    anon = SAPI.send('GET', LISTING, {}, None, True)
    expect('TC-972. Список доставок без токена: 401', anon.status == 401, SAPI.describe(anon)[:200])
    _, foreign, _ = listing(fx, key='b', limit=100)
    expect('TC-972. Чужой продавец записей этого продавца не видит', not [i for i in foreign if i.get('orderNumber') == number], str(foreign)[:200])
    reset_mode(fx)
    buyer2, order2 = place(fx, 'wh-after', 1)
    if order2:
        ok2, rows2, _ = wait_rows(order2['orderNumber'], ('order.paid',), ('sent',), wait=60)
        expect('TC-972. Исчерпанные попытки очередь не держат: после возврата режима новый заказ доставляется (order.paid sent)', ok2, str({t: (r['status'], r['attempts']) for t, r in rows2.items()}))


def canary(fx):
    print('== Канарейка: секрет подписи, адрес подключения и данные покупателей не попадают в журнал и тела (TC-973, TC-974, ST-01)')
    out = C.sh(G.COMPOSE + ['logs', '--no-color', 'platform-service'], timeout=300)
    text = out.stdout + out.stderr
    expect('Контроль: журнал platform-service (%d КБ) настоящий, в нём есть строки об отказах продавца по уведомлениям' % (len(text) // 1024), 'ответила 500 на уведомление' in text, '')
    wanted = [fx['secret'], fx['slug'], 'external-stubs:8443/seller']
    found = C.leaks(text, wanted)
    expect('TC-973. В журнале platform-service нет секрета подписи и адреса подключения', not found, str(found[:2]))
    bodies = json.dumps(fx['stubs'].seller_webhooks(fx['slug']), ensure_ascii=False)
    expect('Контроль: заглушка хранит принятые webhook (не пусто)', len(bodies) > 400, str(len(bodies)))
    found = buyer_traces(fx['buyers'], bodies)
    expect('TC-974. Ни e-mail, ни идентификаторов покупателей нет в принятых заглушкой webhook', not found, str(found))
    stored = []
    for number in fx.get('numbers', []):
        stored += rows_of(number) or []
    blob = ' '.join(r['payload'] for r in stored)
    expect('Контроль: в очереди платформы есть строки по заказам проверки (%d)' % len(stored), len(stored) >= 6, str(len(stored)))
    found = buyer_traces(fx['buyers'], blob)
    expect('TC-974. Ни e-mail, ни идентификаторов покупателей нет в телах очереди платформы', not found, str(found))


# --------------------------------------------------------------------------------------------------- запуск
def run_scenarios(fx):
    watch = SD.MemoryWatch(watched=WATCHED, title='под сценариями webhook')
    watch.start()
    try:
        for fn in (delivered_and_signed, retried, failed_for_good):
            O.guarded(fn.__name__, fn, fx)
        O.guarded('canary', canary, fx)
    finally:
        watch.finish()
        SD.restore(fx)


def main():
    kc, admin = K.from_env(dict(os.environ))
    people = G.users()
    if 'moderator-1' not in people:
        print('Нет тестового пользователя moderator-1 (make keycloak-users)', file=sys.stderr)
        return 2
    stubs = SB.StubsAdmin.from_env()
    fx = O.guarded('prepare', SD.prepare, kc, admin, people, int(time.time()), stubs)
    if fx:
        run_scenarios(fx)
    else:
        G.bad('Продавцы, подключение, товар по API и остаток не подготовлены, сценарии webhook не запускались')
    return O.summary('webhook_checks')


if __name__ == '__main__':
    sys.exit(main())
