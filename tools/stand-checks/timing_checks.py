#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Время от оплаты до письма и обращения, карточных данных на платформе нет (шаг 23 Ф4, Р-144; NFT-2.0, NFT-2.1, NFT-2.4, NFT-5.1; TC-064, TC-065, TC-066, TC-067).

    make timing-check

Запускается последним среди проверок покупки на стенде (набор full с make fast-up DEBUG=1, make keycloak-users): читает метки времени заказов, выдач
и обращений, которые оставили orders-check, payments-check, purchase-check, delivery-check и concurrency-check. Своих заказов не создаёт.

  TC-064  NFT-2.0   от подтверждения оплаты (orders.orders.paid_at) до приёма письма провайдером (delivery.delivery.sent_at): 95-й процентиль не больше 60 с.
                    В выборку входят выдачи, ушедшие с первой попытки (заглушка почты без отказов, как в методике); число выдач в выборке печатается
  TC-065  NFT-2.1   доля выдач, у которых от оплаты до подтверждения доставки (delivered_at) прошло не больше 30 минут, не меньше 95 %. На стенде
                    выборка это выдачи с подтверждением, зачёт по методике (серия 1 000 заказов, задержки заглушки 20 и 40 минут) снимается на сервере
  TC-066  NFT-2.4   обращения системы появляются не позже срока контроля (TIMING_CONTROL_SECONDS, на стенде 180 с) плюс запас 90 с для обращения
                    delivery_overdue и не позже 30 минут для остальных причин; оплаченных заказов без выдачи и без обращения старше TIMING_HANDLING_SECONDS
                    (300 с на стенде, 3600 с на сервере) нет
  TC-067  NFT-5.1   у платформы нет места для данных карты: ни один столбец баз, ни одно поле контрактов не называется как номер, срок или код карты;
                    в дампах баз и журналах сервисов нет чисел, похожих на номер карты (13 – 19 цифр, префикс платёжной системы, контрольная цифра Луна).
                    Платёжная заглушка не принимает карту (оплата кнопкой на её странице), поэтому проверка показывает отсутствие мест для данных карты
                    и следов номеров, а не обработку настоящей карты

Только стандартная библиотека Python.
"""
import math
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import canary_checks as C  # noqa: E402
import gateway_checks as G  # noqa: E402
import orders_checks as O  # noqa: E402
import purchase_checks as P  # noqa: E402

expect = G.expect
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
SEND_LIMIT = 60.0            # NFT-2.0
DELIVER_LIMIT = 1800.0       # NFT-2.1, 30 минут
DELIVER_SHARE = 0.95
CONTROL = float(os.environ.get('TIMING_CONTROL_SECONDS', '180'))
HANDLING = float(os.environ.get('TIMING_HANDLING_SECONDS', '300'))
MIN_SAMPLE = 10              # меньше выдач в выборке: предыдущие проверки не прошли или не запускались, замер ничего не доказывает
SEED_PREFIX = '0199e0a0-0000-'   # начало идентификаторов заказов тестовых данных (V1000__testdata_orders.sql)
SCAN_DATABASES = ['catalog_db', 'inventory_db', 'order_db', 'payment_db', 'delivery_db', 'platform_db']
SERVICES = ['api-gateway', 'catalog-service', 'inventory-service', 'order-service', 'payment-service', 'delivery-service', 'platform-service', 'external-stubs']

CARD_NAME = re.compile(r'(?i)(?<![a-z])(card[_ -]?(number|no|holder|owner)|cardnumber|cardholder|pan|cvv|cvc|cvv2|card[_ -]?expir\w*|expiry[_ -]?(date|month|year))(?![a-z])')
CARD_RUN = re.compile(r'(?<![\w.-])\d(?:[ -]?\d){12,18}(?![\w-])')
CARD_PREFIX = re.compile(r'^(?:4|5[1-5]|2[2-7]|3[47]|6[0-9])')


def percentile(values, share):
    """Процентиль по ближайшему рангу: наименьшее значение, не меньше которого доля share выборки (пустая выборка: None)."""
    ordered = sorted(values)
    if not ordered:
        return None
    return ordered[max(0, math.ceil(share * len(ordered)) - 1)]


def luhn(digits):
    total = 0
    for index, char in enumerate(reversed(digits)):
        digit = int(char)
        if index % 2:
            digit *= 2
            if digit > 9:
                digit -= 9
        total += digit
    return total % 10 == 0


def card_like(text):
    """Числа, похожие на номер карты: 13 – 19 цифр (допустимы пробелы и дефисы), префикс платёжной системы, контрольная цифра Луна."""
    found = []
    for match in CARD_RUN.finditer(text):
        digits = re.sub(r'\D', '', match.group(0))
        if 13 <= len(digits) <= 19 and CARD_PREFIX.match(digits) and luhn(digits):
            found.append(match.group(0))
    return found


def rows(database, query):
    result = O.sql(database, query)
    return result if result is not None else []


def number(text):
    return float(text) if text not in ('', None) else None


def send_and_delivery_times():
    """[(секунд до приёма письма, секунд до подтверждения доставки или None, число попыток)] по первичным выдачам, у которых есть и оплата."""
    paid = {r[0]: number(r[1]) for r in rows('order_db', "select id, extract(epoch from paid_at) from orders.orders where paid_at is not null")}
    out = []
    for r in rows('delivery_db', "select order_id, extract(epoch from sent_at), extract(epoch from delivered_at), attempt_no from delivery.delivery "
                                 "where kind = 'primary' and sent_at is not null"):
        at = paid.get(r[0])
        if at is not None:
            sent, delivered = number(r[1]), number(r[2])
            out.append((sent - at, (delivered - at) if delivered is not None else None, int(r[3])))
    return out


def send_time():
    print('== От оплаты до приёма письма провайдером: 95-й процентиль не больше %d с (NFT-2.0; TC-064)' % SEND_LIMIT)
    times = send_and_delivery_times()
    first = [t[0] for t in times if t[2] == 1]
    expect('TC-064. В выборке выдач с первой попытки не меньше %d (всего выдач с приёмом письма %d): предыдущие проверки покупки отработали' % (MIN_SAMPLE, len(times)),
           len(first) >= MIN_SAMPLE, str(len(first)))
    if not first:
        return
    p50, p95, top = percentile(first, 0.5), percentile(first, 0.95), max(first)
    print('   выборка %d, медиана %.1f с, p95 %.1f с, максимум %.1f с, минимум %.1f с' % (len(first), p50, p95, top, min(first)))
    expect('TC-064. Письмо не принято раньше оплаты: метки времени согласованы (минимум %.1f с, допуск часов 1 с)' % min(first), min(first) >= -1.0, str(min(first)))
    expect('TC-064. 95-й процентиль времени от оплаты до приёма письма %.1f с, не больше %d с' % (p95, SEND_LIMIT), p95 <= SEND_LIMIT, '%.1f' % p95)


def delivery_time():
    print('== Доля выдач с подтверждением доставки за 30 минут от оплаты не меньше 95 %% (NFT-2.1; TC-065)')
    delivered = [t[1] for t in send_and_delivery_times() if t[1] is not None]
    expect('TC-065. В выборке выдач с подтверждением доставки не меньше %d (%d)' % (MIN_SAMPLE, len(delivered)), len(delivered) >= MIN_SAMPLE, str(len(delivered)))
    if not delivered:
        return
    inside = sum(1 for d in delivered if d <= DELIVER_LIMIT)
    share = inside / len(delivered)
    print('   выборка %d, уложились %d, медиана %.1f с, p95 %.1f с, максимум %.1f с' % (len(delivered), inside, percentile(delivered, 0.5), percentile(delivered, 0.95), max(delivered)))
    expect('TC-065. Доля уложившихся в 30 минут %.1f %%, не меньше %d %%' % (share * 100, DELIVER_SHARE * 100), share >= DELIVER_SHARE, '%d из %d' % (inside, len(delivered)))


def support_handover():
    print('== Заказ без выдачи попадает в поддержку в срок, неразобранных оплаченных заказов нет (NFT-2.4; TC-066)')
    paid = {r[0]: number(r[1]) for r in rows('order_db', "select id, extract(epoch from paid_at) from orders.orders where paid_at is not null")}
    tickets = rows('platform_db', "select order_id, reason, extract(epoch from created_at) from support.ticket where source = 'system'")
    late, delays = [], {}
    for oid, reason, made in tickets:
        at = paid.get(oid)
        if at is None:
            continue
        delay = number(made) - at
        delays.setdefault(reason, []).append(delay)
        limit = CONTROL + 90.0 if reason == 'delivery_overdue' else 1800.0
        if delay > limit:
            late.append('%s %.0f с при пределе %.0f' % (reason, delay, limit))
    for reason, found in sorted(delays.items()):
        print('   обращений системы «%s»: %d, от оплаты до обращения %.0f – %.0f с' % (reason, len(found), min(found), max(found)))
    expect('TC-066. Есть обращения системы (%d): сценарии недоставки среза выполнены' % len(tickets), len(tickets) >= 1, str(len(tickets)))
    expect('TC-066. Обращение системы по заказу появляется не позже срока контроля %.0f с плюс 90 с (delivery_overdue) и не позже 30 минут (остальные причины)' % CONTROL,
           not late, '; '.join(late[:3]))
    handled = {r[0] for r in tickets}
    refunded = {r[0] for r in rows('order_db', "select id from orders.orders where status in ('refunded', 'cancelled', 'issued')")}
    # Заказы тестовых данных (id с фиксированным началом SEED_PREFIX) записаны в базу миграцией, а не оплатой через стенд: события оплаты не было,
    # выдача и контроль доставки о них не знают, их возраст («оплачен» 3 октября) ничего не говорит о работе системы.
    waiting = rows('order_db', "select id, extract(epoch from now() - paid_at) from orders.orders where status = 'paid' and paid_at is not null and id::text not like '%s%%'" % SEED_PREFIX)
    stuck = [(oid, number(age)) for oid, age in waiting if oid not in handled and oid not in refunded and number(age) > HANDLING]
    oldest = max([number(age) for _, age in waiting] or [0.0])
    expect('TC-066. Оплаченных заказов без выдачи и без обращения старше %.0f с нет (оплаченных, ждущих выдачи, сейчас %d, самый старый %.0f с)' % (HANDLING, len(waiting), oldest),
           not stuck, str(stuck[:3]))


def contract_fields():
    """Имена полей контрактов, похожие на данные карты: [(файл, имя)]."""
    found = []
    for folder in ('docs/06-api/openapi', 'docs/06-api/asyncapi'):
        base = os.path.join(REPO, folder)
        for name in sorted(os.listdir(base)) if os.path.isdir(base) else []:
            if not name.endswith(('.yaml', '.yml', '.json')):
                continue
            with open(os.path.join(base, name), encoding='utf-8') as f:
                for line in f:
                    key = re.match(r'^\s*"?([A-Za-z_][\w -]*)"?\s*:', line)
                    if key and CARD_NAME.search(key.group(1)):
                        found.append((name, key.group(1)))
    return found


def card_data():
    print('== Данных карты на платформе нет: ни столбцов, ни полей контрактов, ни чисел в базах и журналах (NFT-5.1; TC-067)')
    control = card_like('оплата 4111 1111 1111 1111, заказ 1761389569123, ключ DGM-ORD-4a3f')
    expect('TC-067. Контроль поиска: номер вида 4111 1111 1111 1111 находится, время в миллисекундах и номер ключа нет (%s)' % control, control == ['4111 1111 1111 1111'], str(control))
    columns = []
    for database in SCAN_DATABASES:
        for r in rows(database, "select table_schema || '.' || table_name || '.' || column_name from information_schema.columns "
                                "where table_schema not in ('pg_catalog', 'information_schema')"):
            if CARD_NAME.search(r[0].split('.')[-1]):
                columns.append('%s %s' % (database, r[0]))
    expect('TC-067. Ни один столбец баз сервисов не называется как номер, срок или код карты', not columns, '; '.join(columns[:3]))
    fields = contract_fields()
    expect('TC-067. Ни одно поле контрактов OpenAPI и AsyncAPI не называется как номер, срок или код карты', not fields, str(fields[:3]))
    logs = C.compose_logs()
    marks = [m for m in re.findall(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', logs)[:1]]
    expect('Контроль: журналы контейнеров (%d КБ) настоящие, в них идентификаторы запросов' % (len(logs) // 1024), len(logs) > 20000 and bool(marks), str(len(logs)))
    found = card_like(logs)
    expect('TC-067. В журналах контейнеров стенда чисел, похожих на номер карты, нет', not found, str(found[:3]))
    for database in SCAN_DATABASES:
        text, code, error = P.dump(database)
        expect('Дамп %s снят (%d КБ)' % (database, len(text) // 1024), code == 0 and len(text) > 2000, error)
        found = card_like(text)
        expect('TC-067. В %s чисел, похожих на номер карты, нет' % database, not found, str(found[:3]))


def run_scenarios():
    for fn in (send_time, delivery_time, support_handover, card_data):
        O.guarded(fn.__name__, fn)


def main():
    if not O.sql('order_db', 'select 1'):
        print('База заказов не отвечает: нужен стенд full (make fast-up DEBUG=1) и проверки покупки перед этой', file=sys.stderr)
        return 2
    run_scenarios()
    return O.summary('timing_checks')


if __name__ == '__main__':
    sys.exit(main())
