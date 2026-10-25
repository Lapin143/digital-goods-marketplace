#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Параметры платформы сквозь стенд (шаг 8 среза 3 Ф4, К9; US-8.3, US-8.4, FT-9.0, FT-11.1, FT-11.2, INV-07, ADR-017; TC-816).

    make parameters-check

Нужен набор full с отладочным файлом и ускоренными сроками (make fast-up DEBUG=1, затем make keycloak-users). Правит параметры admin-1 настоящим
входом с TOTP, заказы оформляют одноразовые покупатели, товар берётся у orders_checks.py (secrets/purchase_fixture.json), документ заявки открывает
moderator-1. Слушатель параметров в сервисе проверяется по его поведению и по строке журнала «Параметр <ключ> = <значение> (было …, версия …)».
Что проверяется:

  лимит       orders.max-unpaid = 1: первый неоплаченный заказ нового покупателя оформляется, второй получает 409 unpaid-orders-limit;
              значение возвращено к 3: второй заказ проходит. В журнале аудита две записи parameter.changed (3 → 1 и 1 → 3) от admin-1
  комиссия    commission.default-rate-bp = 300: новый заказ с комиссией 3 % (4497 копеек с 1499 ₽), прежний заказ остаётся с 2 % (2998 копеек);
              order-service перезапускается посреди сценария, читает тему platform.config с начала и снова применяет 300; значение возвращено к 200
  ссылка      seller.document-link-ttl-seconds = 60: срок подписанной ссылки на документ в карточке заявки около 60 секунд вместо 300
  возврат     в конце все параметры, которых коснулся сценарий, снова равны значению по умолчанию (проверка красная, если возврат не выполнен)

Параметры с границами и версиями, отказы (вне границ, INV-07, чужая версия) проверяют ParameterServiceTest и PlatformServiceIT; слушатель на настоящей
Kafka проверяет ConfigListenerIT. Здесь проверяется то, чего у них нет: платформа, тема, слушатель в соседнем сервисе и его перезапуск вместе.
Переменные: PARAMETERS_ONLY (имена сценариев через запятую: unpaid, commission, link), PARAMETERS_WAIT (секунд ожидания применения, 30).
Только стандартная библиотека Python.
"""
import os
import sys
import time
import json
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import accounts_checks as A  # noqa: E402
import audit_checks as AU  # noqa: E402
import canary_checks as C  # noqa: E402
import gateway_checks as G  # noqa: E402
import kc_client as K  # noqa: E402
import orders_checks as O  # noqa: E402
import seller_approval_checks as SA  # noqa: E402
import sellers_checks as SC  # noqa: E402

expect = G.expect
PARAMETERS = '/api/v1/staff/parameters'
UNPAID, COMMISSION, LINK = 'orders.max-unpaid', 'commission.default-rate-bp', 'seller.document-link-ttl-seconds'
WAIT = float(os.environ.get('PARAMETERS_WAIT', '30'))


# --------------------------------------------------------------------------------------------------- вспомогательное
class Context:
    def __init__(self, kc, admin, people, stamp, fixture):
        self.kc, self.admin, self.people, self.stamp, self.fixture = kc, admin, people, stamp, fixture
        self.administrator = SA.Staff(kc, admin, people['admin-1'])
        self.moderator = SA.Staff(kc, admin, people['moderator-1'])
        self.touched = []
        self.started = datetime.now(timezone.utc)


def listing(ctx):
    """Параметры платформы по ключам: {ключ: тело параметра}; пусто, если список не прочитан."""
    response = G.call('GET', PARAMETERS, headers=SC.auth(ctx.administrator.token()))
    try:
        return {item['key']: item for item in response.json().get('items') or []} if response.status == 200 else {}
    except ValueError:
        return {}


def put(ctx, key, value):
    """Правка параметра с If-Match, равным версии из свежего списка; ключ запоминается для возврата."""
    current = listing(ctx).get(key)
    if key not in ctx.touched:
        ctx.touched.append(key)
    if not current:
        return None
    return SC.put_json('%s/%s' % (PARAMETERS, key), ctx.administrator.token(), '"%d"' % current['version'], {'value': value})


def log_messages(service):
    """Сообщения журнала контейнера: строки JSON разбираются, остальные идут как есть (compose добавляет к строке имя сервиса и «|»)."""
    r = C.sh(G.COMPOSE + ['logs', '--no-color', service], timeout=180)
    result = []
    for line in (r.stdout + r.stderr).splitlines():
        start = line.find('{')
        if start >= 0:
            try:
                result.append(str(json.loads(line[start:]).get('message', '')))
                continue
            except ValueError:
                pass
        result.append(line)
    return result


def applied(service, key, value):
    """Сколько раз слушатель сервиса записал, что применил значение ключа (строка ConfigRegistry «Параметр <ключ> = <значение> (было …»)."""
    prefix = 'Параметр %s = %s (' % (key, value)
    return sum(1 for m in log_messages(service) if prefix in m)


def wait_applied(service, key, value, before):
    return bool(A.eventually(lambda: applied(service, key, value) > before, wait=WAIT, step=1.0))


def set_and_wait(ctx, service, key, value, label):
    """Правка и ожидание применения слушателем; (ответ платформы, применено ли значение в сервисе)."""
    before = applied(service, key, value)
    response = put(ctx, key, value)
    if response is None:
        G.bad('%s: параметр %s не найден в списке платформы' % (label, key))
        return None, False
    expect('%s: правка %s = %s принята платформой: 200' % (label, key, value), response.status == 200 and response.json().get('value') == value, AU.describe(response))
    ok = wait_applied(service, key, value, before)
    expect('%s: слушатель %s применил %s = %s за %d с (строка «Параметр … =» в журнале сервиса)' % (label, service, key, value, WAIT), ok, 'строки в журнале нет')
    return response, ok


def commission_of(order_id):
    rows = O.sql('order_db', "select commission_rate_bp, commission from orders.orders where id = '%s'" % order_id)
    return (int(rows[0][0]), int(rows[0][1])) if rows else None


def expected_commission(amount, rate_bp):
    """Комиссия в копейках по FT-9.0: сумма на ставку, математическое округление до копейки."""
    return (amount * rate_bp + 5000) // 10000


def open_order(buyer, product_id, label):
    created = O.create_order(buyer, product_id, 1)
    body = created.json() if created.status == 201 else {}
    expect('%s: заказ оформлен: 201, awaiting_payment' % label, created.status == 201 and body.get('status') == 'awaiting_payment', O.describe(created))
    return body.get('orderId')


def wait_healthy(service, seconds=240):
    cid = C.sh(G.COMPOSE + ['ps', '-q', service]).stdout.strip().splitlines()
    if not cid:
        return False

    def healthy():
        return C.sh(['docker', 'inspect', '-f', '{{.State.Health.Status}}', cid[0]]).stdout.strip() == 'healthy'
    return bool(A.eventually(healthy, wait=seconds, step=3.0))


def parameter_records(ctx, key):
    """Записи parameter.changed по ключу за время прогона, от старых к новым: [(до, после, исполнитель, роль)]."""
    token = ctx.administrator.token()
    since = AU.stamp_of(ctx.started)
    box = {}

    def two():
        box['items'] = AU.items_of(AU.journal(token, action='parameter.changed', objectType='parameter', objectId=key, occurredFrom=since, limit=100))
        return len(box['items']) >= 2
    A.eventually(two, wait=AU.WAIT, step=1.0)
    records = list(reversed(box.get('items') or []))
    return [((AU.change_of(r, 'value') or {}).get('before'), (AU.change_of(r, 'value') or {}).get('after'), r.get('actorId'), r.get('actorRole')) for r in records]


# --------------------------------------------------------------------------------------------------- сценарии
def unpaid_limit(ctx):
    print('== Лимит неоплаченных заказов меняется параметром и применяется без перезапуска (US-8.3, FT-5.1; TC-816)')
    buyer = O.new_buyer(ctx.kc, ctx.admin, ctx.stamp, 'pm1')
    if not buyer:
        return
    product = ctx.fixture['plenty']
    _, ok = set_and_wait(ctx, 'order-service', UNPAID, 1, 'TC-816')
    first = open_order(buyer, product, 'TC-816. Первый неоплаченный заказ при лимите 1')
    second = O.create_order(buyer, product, 1)
    expect('TC-816. Второй неоплаченный заказ при лимите 1: 409 unpaid-orders-limit, предел 1 в ответе',
           ok and second.status == 409 and second.code() == 'unpaid-orders-limit' and second.json().get('limit') == 1, O.describe(second))
    _, back = set_and_wait(ctx, 'order-service', UNPAID, 3, 'TC-816')
    third = O.create_order(buyer, product, 1)
    expect('TC-816. После возврата лимита 3 второй неоплаченный заказ оформляется: 201', back and third.status == 201, O.describe(third))
    expect('TC-816. Первый заказ не затронут правкой параметра: awaiting_payment, paid или cancelled по сроку резерва',
           first and O.get_order(buyer, first).json().get('status') in ('awaiting_payment', 'cancelled', 'paid', 'issued'), str(first))
    records = parameter_records(ctx, UNPAID)
    admin_sub = AU.claims_of(ctx.administrator.token())['sub']
    expect('TC-816. В журнале две записи parameter.changed по orders.max-unpaid: 3 → 1 и 1 → 3, исполнитель admin-1, роль admin (кто и когда изменил)',
           [(b, a) for b, a, _, _ in records][-2:] == [('3', '1'), ('1', '3')] and all(s == admin_sub and r == 'admin' for _, _, s, r in records[-2:]), str(records))


def commission(ctx):
    print('== Комиссия по умолчанию из параметра, фиксируется в заказе, переживает перезапуск сервиса заказов (US-8.4, FT-9.0, ADR-017; TC-816)')
    old_buyer = O.new_buyer(ctx.kc, ctx.admin, ctx.stamp, 'pm2')
    new_buyer = O.new_buyer(ctx.kc, ctx.admin, ctx.stamp, 'pm3')
    if not (old_buyer and new_buyer):
        return
    product, amount = ctx.fixture['plenty'], O.PRICE
    current = listing(ctx).get(COMMISSION) or {}
    expect('TC-816. Исходная ставка 200 базисных пунктов (2 %%) по умолчанию: значение %s, по умолчанию %s' % (current.get('value'), current.get('defaultValue')),
           current.get('value') == 200 == current.get('defaultValue'), str(current))
    older = open_order(old_buyer, product, 'TC-816. Заказ при ставке 2 %')
    old_row = commission_of(older) if older else None
    expect('TC-816. Заказ при 2 %%: ставка 200 и комиссия %d копеек зафиксированы в заказе (FT-9.0)' % expected_commission(amount, 200),
           old_row == (200, expected_commission(amount, 200)), str(old_row))

    _, ok = set_and_wait(ctx, 'order-service', COMMISSION, 300, 'TC-816')
    newer = open_order(new_buyer, product, 'TC-816. Заказ при ставке 3 %')
    new_row = commission_of(newer) if newer else None
    expect('TC-816. Новый заказ при 3 %%: ставка 300 и комиссия %d копеек (4497 с цены 1499 ₽, математическое округление)' % expected_commission(amount, 300),
           ok and new_row == (300, expected_commission(amount, 300)), str(new_row))
    expect('TC-816. Прежний заказ остался с 2 %: ставка и сумма комиссии не изменились (INV на фиксацию в заказе)', older and commission_of(older) == old_row, str(commission_of(older)) if older else '')

    before = applied('order-service', COMMISSION, 300)
    restarted = C.sh(G.COMPOSE + ['restart', 'order-service'], timeout=240)
    expect('TC-816. order-service перезапущен посреди сценария', restarted.returncode == 0, (restarted.stderr or '')[-200:])
    expect('TC-816. order-service снова здоров после перезапуска', wait_healthy('order-service'), 'не стал healthy за 240 с')
    expect('TC-816. После перезапуска слушатель прочитал тему platform.config с начала и снова применил 300 без запроса к платформе',
           wait_applied('order-service', COMMISSION, 300, before), 'строки «Параметр %s = 300» после перезапуска нет' % COMMISSION)
    restart_order = open_order(old_buyer, product, 'TC-816. Заказ после перезапуска при ставке 3 %')
    expect('TC-816. Заказ после перезапуска: ставка 300, как до него (значение не вернулось к 200 из настроек сервиса)',
           restart_order and commission_of(restart_order) == (300, expected_commission(amount, 300)), str(commission_of(restart_order)) if restart_order else '')

    _, back = set_and_wait(ctx, 'order-service', COMMISSION, 200, 'TC-816')
    last = open_order(new_buyer, product, 'TC-816. Заказ после возврата ставки 2 %')
    expect('TC-816. Заказ после возврата: ставка 200 и комиссия %d копеек' % expected_commission(amount, 200),
           back and last and commission_of(last) == (200, expected_commission(amount, 200)), str(commission_of(last)) if last else '')


def link_ttl(ctx):
    print('== Срок подписанной ссылки на документ заявки меняется параметром (US-8.3, ADR-024; TC-816)')
    applicant = SA.applicant(ctx.kc, ctx.admin, ctx.stamp, 'pml')
    if not applicant:
        return

    def seconds_left():
        card = G.call('GET', '%s/%s' % (SA.QUEUE, applicant['sellerId']), headers=SC.auth(ctx.moderator.token()))
        documents = (card.json().get('documents') or []) if card.status == 200 else []
        link = (documents[0].get('link') or {}) if documents else {}
        if not link.get('expiresAt'):
            return None
        return (AU.instant(link['expiresAt']) - datetime.now(timezone.utc)).total_seconds()
    before = seconds_left()
    expect('TC-816. Ссылка при значении по умолчанию живёт около 300 секунд (%s)' % before, before is not None and 270 <= before <= 305, str(before))
    _, ok = set_and_wait(ctx, 'catalog-service', LINK, 60, 'TC-816')
    after = seconds_left()
    expect('TC-816. После правки на 60 секунд новая ссылка живёт около 60 секунд (%s)' % after, ok and after is not None and 30 <= after <= 65, str(after))


SCENARIOS = {'unpaid': unpaid_limit, 'commission': commission, 'link': link_ttl}


# --------------------------------------------------------------------------------------------------- возврат и запуск
def restore(ctx):
    print('== Параметры возвращены к значению по умолчанию (иначе проверка красная)')
    for key in ctx.touched:
        current = listing(ctx).get(key)
        if current and current['value'] != current['defaultValue']:
            response = put(ctx, key, current['defaultValue'])
            expect('Возврат %s к значению по умолчанию %s: 200' % (key, current['defaultValue']), response is not None and response.status == 200, AU.describe(response) if response else 'нет ключа')
    left = {key: (v['value'], v['defaultValue']) for key, v in listing(ctx).items() if key in ctx.touched}
    expect('Все параметры, которых коснулся сценарий, равны значению по умолчанию (%s)' % ', '.join('%s=%s' % (k, v[0]) for k, v in left.items()),
           bool(left) and all(value == default for value, default in left.values()), str(left))
    in_orders = A.eventually(lambda: all(applied('order-service', k, listing(ctx)[k]['defaultValue']) > 0 for k in ctx.touched if k != LINK), wait=WAIT, step=1.0)
    expect('Сервис заказов применил и возврат значений (в журнале есть строки с значением по умолчанию)', in_orders, 'строк нет')


def main():
    kc, admin = K.from_env(dict(os.environ))
    people = G.users()
    for name in ('moderator-1', 'admin-1'):
        if name not in people:
            print('Нет тестового пользователя %s (make keycloak-users)' % name, file=sys.stderr)
            return 2
    stamp = int(time.time())
    fixture = O.prepare(kc, admin, people, stamp, scarce_needed=False)
    if not fixture:
        G.bad('Продавец и товары для заказов не заведены, сценарии параметров не запускались')
        return O.summary('parameters_checks')
    ctx = Context(kc, admin, people, stamp, fixture)
    ctx.administrator.token()
    ctx.moderator.token()
    names = [n for n in os.environ.get('PARAMETERS_ONLY', ','.join(SCENARIOS)).split(',') if n in SCENARIOS]
    try:
        for name in names:
            O.guarded(name, SCENARIOS[name], ctx)
    finally:
        O.guarded('restore', restore, ctx)
    return O.summary('parameters_checks')


if __name__ == '__main__':
    sys.exit(main())
