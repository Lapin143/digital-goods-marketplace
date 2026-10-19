#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Заказ и оплата сквозь стенд (шаг 12 Ф4, К5; US-5.1 – US-5.5, FT-5.0 – FT-5.2, FT-6.0 – FT-6.3; TC-001 до выдачи, TC-002, TC-003, TC-004, TC-005).

    make orders-check

Нужен набор full, поднятый с ускоренными сроками и отладочным файлом: make fast-up DEBUG=1 (резерв 30 секунд, платёжная сессия 24, профиль
fast-time), затем make keycloak-users. Покупатели, продавец и товары одноразовые: проверка заводит их сама (регистрация на странице Keycloak,
заявка продавца, решение модератора moderator-1, загрузка ключей) и кладёт номера товаров в secrets/purchase_fixture.json, чтобы
payments_checks.py (make payments-check) не заводил продавца второй раз, пока ключи не израсходованы.

Что идёт на стенде. Покупатель создаёт заказ через шлюз платформы (order-service, inventory-service, payment-service, платёжная заглушка),
платит на странице заглушки (форма POST без префикса, как в браузере) и видит заказ оплаченным. Платёжная заглушка (external-stubs, порт
управления 8444) играет шлюз: режим платежа меняется через stubs_admin.StubsAdmin и на выходе из каждого сценария возвращается к умолчанию.

  оформление  TC-001 до оплаты: 201, цена, сумма, адрес из токена, сроки сессии и резерва (INV-07), ключи в резерве, один платёж у шлюза;
              повтор с тем же ключом даёт тот же заказ и ту же сессию (ADR-006), другой запрос под тем же ключом 422, без ключа 400;
              чужой заказ 404; оплата на странице заглушки; заказ paid, платёж confirmed, резерв used
  нехватка    TC-002 (E2): заказано больше, чем ключей: 409 insufficient-stock с числом свободных, заказ отменён out_of_stock, ключи не тронуты
  телефон     TC-003, вторая часть (E1): без подтверждённого телефона 403 phone-not-confirmed и заказа нет; после подтверждения заказ создаётся
              и оплачивается без участия человека (заглушка сама присылает вебхук)
  сессия      TC-004 (E12): шлюз не открывает сессию: 503 payment-session-unavailable, заказ отменён payment_session_failed, резерв снят
  отказ       TC-005 (E3, E4): шлюз отклонил платёж: заказ отменён payment_declined, резерв снят сразу; сессия истекла без оплаты: заказ
              отменён reservation_expired по сроку резерва, ключи свободны

Подтверждение телефона по SMS проверяет sms_login_checks.py (TC-003, часть 1, лимит кодов с одного адреса 10 в час). Покупателям этой проверки
признак phone_verified ставится через Admin API Keycloak, как это делает infra/keycloak/provision_test_users.py для buyer-1.
Повторные уведомления, потерянный вебхук, поздняя оплата и возвраты: payments_checks.py. Только стандартная библиотека Python.
"""
import json
import os
import sys
import time
import traceback
import uuid
from datetime import datetime, timedelta, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import accounts_checks as A  # noqa: E402
import canary_checks as C  # noqa: E402
import gateway_checks as G  # noqa: E402
import kc_client as K  # noqa: E402
import seller_approval_checks as SA  # noqa: E402
import sellers_checks as SC  # noqa: E402
import stubs_admin as SB  # noqa: E402

expect = G.expect
ORDERS = '/api/v1/orders'
CACHE = os.path.join(G.SECRETS, 'purchase_fixture.json')
PLENTY_KEYS = 30
SCARCE_KEYS = 4
PRICE = 149900


# --------------------------------------------------------------------------------------------------- базы и заглушка
def sql(database, query):
    """Строки результата запроса к базе сервиса (psql в контейнере postgres, столбцы через «|») или None, если psql отказал."""
    r = C.sh(G.COMPOSE + ['exec', '-T', 'postgres', 'psql', '-U', 'postgres', '-d', database, '-tA', '-F', '|', '-c', query], timeout=60)
    if r.returncode != 0:
        return None
    return [line.split('|') for line in r.stdout.strip().splitlines() if line.strip()]


def keys_of(product_id):
    """Ключи товара по статусам: {'free': 4, 'reserved': 2}. None: база не ответила."""
    rows = sql('inventory_db', "select status, count(*) from inventory.key where product_id = '%s' group by status" % product_id)
    return {r[0]: int(r[1]) for r in rows} if rows is not None else None


def reservations_of(order_id):
    """Резервы заказа по порядку создания: [(статус, причина снятия, количество)]."""
    rows = sql('inventory_db', "select status, coalesce(release_reason, ''), quantity from inventory.reservation where order_id = '%s' order by created_at, id" % order_id)
    return [(r[0], r[1], int(r[2])) for r in rows] if rows is not None else None


def payment_of(order_id):
    """Платёж заказа в payment_db: (статус, в очереди администратора, причина возврата) или None."""
    rows = sql('payment_db', "select status, in_admin_queue, coalesce(refund_reason, '') from payments.payment where order_id = '%s'" % order_id)
    return (rows[0][0], rows[0][1] == 't', rows[0][2]) if rows else None


def outbox_count(order_id, event_type):
    """Сколько событий заданного типа сервис заказов записал в Outbox по заказу."""
    rows = sql('order_db', "select count(*) from public.outbox where aggregate_id = '%s' and event_type = '%s'" % (order_id, event_type))
    return int(rows[0][0]) if rows else None


def stub_payments(stubs, order_id):
    return [p for p in stubs.payments() if p.get('orderId') == order_id]


def modes_are_default(stubs, label):
    mode = stubs.modes().get('payment') or {}
    ok = (mode.get('scenario') == 'success' and (mode.get('create') or {}).get('failFirst') == 0 and (mode.get('webhook') or {}).get('send') is True
          and (mode.get('webhook') or {}).get('repeat') == 1 and (mode.get('refund') or {}).get('mode') == 'instant')
    expect('%s: платёжная заглушка вернулась в исходный режим' % label, ok, str(mode)[:240])


# --------------------------------------------------------------------------------------------------- покупатели
def set_phone_verified(admin, user_id):
    """Признак подтверждённого телефона в Keycloak (так делает provision_test_users.py); SMS-путь проверяет sms_login_checks.py."""
    rep = admin.realm('/users/%s' % user_id).json()
    attributes = dict(rep.get('attributes') or {})
    attributes['phone_verified'] = ['true']
    rep['attributes'] = attributes
    return admin.realm('/users/%s' % user_id, 'PUT', rep)


class Buyer:
    """Одноразовый покупатель. Токен доступа живёт 5 минут: старше 200 секунд берётся новый."""

    def __init__(self, kc, data):
        self.kc, self.data = kc, data
        self.value, self.issued = None, 0.0

    @property
    def email(self):
        return self.data['email']

    @property
    def id(self):
        return self.data['id']

    def login(self):
        result = self.kc.login('dgm-web', self.data['email'], self.data['password'])
        if not result.ok:
            raise RuntimeError('покупатель %s не вошёл: %s' % (self.data['email'], result.kind))
        self.value, self.issued = result.tokens['access_token'], time.time()
        return result

    def token(self):
        if self.value is None or time.time() - self.issued > 200:
            self.login()
        return self.value

    def claims(self):
        """Содержимое нового токена доступа (вход заново, чтобы увидеть свежие атрибуты Keycloak)."""
        return self.login().access()


def new_buyer(kc, admin, stamp, tag, verified=True):
    data = A.new_buyer(kc, admin, stamp, tag)
    if not data:
        return None
    buyer = Buyer(kc, data)
    if verified:
        done = set_phone_verified(admin, data['id'])
        if not expect('%s: признак телефона выставлен в Keycloak: 204' % tag, done.status == 204, '%s %s' % (done.status, done.text[:160])):
            return None
        claims = buyer.claims()
        expect('%s: в токене phone_verified = true' % tag, claims.get('phone_verified') is True, str(claims.get('phone_verified')))
    return buyer


# --------------------------------------------------------------------------------------------------- запросы заказов
def create_order(buyer, product_id, quantity, key='', body=None, correlation=None):
    """POST /api/v1/orders. key: '' даёт новый ключ, None отправляет запрос без ключа."""
    headers = SC.auth(buyer.token(), **{'Content-Type': 'application/json'})
    if key is not None:
        headers['Idempotency-Key'] = key or str(uuid.uuid4())
    if correlation:
        headers['X-Correlation-Id'] = correlation
    payload = body if body is not None else {'productId': product_id, 'quantity': quantity}
    return G.call('POST', ORDERS, headers=headers, body=json.dumps(payload).encode(), timeout=40)


def get_order(buyer, order_id):
    return G.call('GET', '%s/%s' % (ORDERS, order_id), headers=G.bearer(buyer.token()))


def list_orders(buyer, status):
    response = G.call('GET', '%s?status=%s&limit=100' % (ORDERS, status), headers=G.bearer(buyer.token()))
    return response.json().get('items') or [] if response.status == 200 else []


def wait_order(buyer, order_id, status, wait=60.0):
    """(достигнут ли статус, последнее тело заказа)."""
    box = {}

    def reached():
        box['response'] = get_order(buyer, order_id)
        return box['response'].status == 200 and box['response'].json().get('status') == status

    ok = bool(A.eventually(reached, wait=wait, step=1.0))
    response = box['response']
    return ok, (response.json() if response.status == 200 else {})


def stub_path(url):
    return SC.storage_path(url)


def stub_payment_id(url):
    return url.rstrip('/').rsplit('/', 1)[1]


def pay_on_page(url, action='pay'):
    """Страница оплаты заглушки через шлюз: открыть и нажать кнопку (форма POST на тот же адрес). (страница, ответ на форму)."""
    path = stub_path(url)
    page = G.call('GET', path, headers={'Accept': 'text/html'})
    done = G.call('POST', path, headers={'Content-Type': 'application/x-www-form-urlencoded', 'Accept': 'text/html'}, body=('action=%s' % action).encode())
    return page, done


def describe(response):
    return '%s %s' % (response.status, response.text[:240])


def money(body, name):
    value = body.get(name) or {}
    return value.get('amount'), value.get('currency')


# --------------------------------------------------------------------------------------------------- продавец и товары
def keys_batch(prefix, count):
    return ['DGM-%s-%s' % (prefix, uuid.uuid4()) for _ in range(count)]


def publish_product(moderator, seller_token, title, count):
    created = SC.post_json(SA.CABINET, seller_token, SA.product_form(title, PRICE))
    if not expect('товар «%s»: завёл продавец, 201' % title, created.status == 201, describe(created)):
        return None
    pid = created.json()['productId']
    submitted = SC.post_json('%s/%s/submit' % (SA.CABINET, pid), seller_token)
    approved = SA.staff_decide(moderator, pid, 'approve')
    if not expect('товар «%s»: подан и одобрен модератором, published' % title,
                  submitted.status == 200 and approved.status == 200 and approved.json().get('status') == 'published', '%s; %s' % (describe(submitted), describe(approved))):
        return None
    box = {}

    def accepted():
        box['response'] = C.upload_list(seller_token, pid, keys_batch('ORD', count))
        return box['response'].status not in (404, 409)   # копия товара догоняет каталог по событиям
    A.eventually(accepted, wait=90)
    loaded = box['response']
    if not expect('товар «%s»: загружено %d ключей' % (title, count), loaded.status == 200 and loaded.json().get('accepted') == count, describe(loaded)):
        return None
    return pid


def build_fixture(kc, admin, people, stamp):
    print('== Продавец и два товара для заказов: заявка, одобрение, вход с TOTP, публикация, ключи')
    moderator = SA.Staff(kc, admin, people['moderator-1'])
    seller = SA.applicant(kc, admin, stamp, 'ord')
    if not seller:
        return None
    moderator.token()
    approved = SA.decide(moderator, seller['sellerId'], 'approve')
    if not expect('заявка продавца одобрена: 200', approved.status == 200, describe(approved)):
        return None
    if not expect('платформа поставила продавцу роль seller в Keycloak', A.eventually(lambda: SA.keycloak_is_seller(admin, seller['id'], seller['sellerId']), wait=60)):
        return None
    token = SA.first_login_and_cabinet(kc, seller)
    if not token:
        return None
    plenty = publish_product(moderator, token, 'Звёздный путь %d: ключ Steam' % stamp, PLENTY_KEYS)
    scarce = plenty and publish_product(moderator, token, 'Редкий ключ %d: Steam' % stamp, SCARCE_KEYS)
    return {'plenty': plenty, 'scarce': scarce, 'stamp': stamp} if plenty and scarce else None


def cache_is_usable(data):
    plenty, scarce = data.get('plenty'), data.get('scarce')
    if not plenty or not scarce:
        return False
    in_plenty, in_scarce = keys_of(plenty), keys_of(scarce)
    shown = [G.call('GET', '%s/%s' % (SA.STOREFRONT, p)).status for p in (plenty, scarce)]
    return bool(in_plenty and in_scarce and in_plenty.get('free', 0) >= 20 and in_scarce.get('free') == SCARCE_KEYS and not in_scarce.get('reserved')
                and shown == [200, 200])


def prepare(kc, admin, people, stamp):
    """Товары для заказов: прошлого прогона, если ключи целы, иначе новые с новым продавцом."""
    try:
        cached = json.load(open(CACHE, encoding='utf-8'))
    except (OSError, ValueError):
        cached = None
    if cached and cache_is_usable(cached):
        print('-- Товары прошлого прогона на месте и ключи целы: продавца заново не заводим')
        return cached
    fixture = build_fixture(kc, admin, people, stamp)
    if fixture:
        os.makedirs(os.path.dirname(CACHE) or '.', exist_ok=True)
        fd = os.open(CACHE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, 'w', encoding='utf-8') as f:
            json.dump(fixture, f)
    return fixture


def guarded(title, fn, *args):
    """Сценарий падает исключением: это ошибка проверки, остальные сценарии идут дальше."""
    try:
        return fn(*args)
    except Exception:   # noqa: BLE001 - любой сбой сценария попадает в итог, а не обрывает прогон
        G.bad('%s: сценарий прерван исключением' % title, traceback.format_exc()[-500:])
        return None


# --------------------------------------------------------------------------------------------------- сценарии
def purchase_to_payment(fx):
    kc, admin, stubs = fx['kc'], fx['admin'], fx['stubs']
    print('== Заказ от оформления до оплаты на странице заглушки (US-5.1 – US-5.3, FT-5.0 – FT-5.2, FT-6.0, INV-07; TC-001 до выдачи, TC-003 повтор заказа)')
    stubs.reset()
    buyer = new_buyer(kc, admin, fx['stamp'], 'o1')
    other = new_buyer(kc, admin, fx['stamp'], 'o1b')
    if not buyer or not other:
        return
    pid = fx['plenty']
    before = keys_of(pid) or {}
    with stubs.payment_mode(scenario='hold'):
        key, correlation = str(uuid.uuid4()), str(uuid.uuid4())
        started = time.time()
        created = create_order(buyer, pid, 2, key=key, correlation=correlation)
        elapsed = time.time() - started
        body = created.json() if created.status == 201 else {}
        if not expect('TC-001. Заказ оформлен: 201, статус awaiting_payment, номер, 2 штуки (%.1f с)' % elapsed,
                      created.status == 201 and body.get('status') == 'awaiting_payment' and isinstance(body.get('orderNumber'), int) and body.get('quantity') == 2,
                      describe(created)):
            return
        oid = body['orderId']
        expect('TC-001. Цена фиксируется в заказе: 1499,00 за штуку, 2998,00 за заказ, рубли', money(body, 'unitPrice') == (PRICE, 'RUB') and money(body, 'total') == (PRICE * 2, 'RUB'), str(body)[:240])
        expect('TC-001. Адрес доставки это e-mail из токена, не замаскирован; ответ no-store и Location на заказ',
               body.get('delivery') == {'channel': 'email', 'address': buyer.email, 'masked': False} and 'no-store' in (created.header('Cache-Control') or '')
               and (created.header('Location') or '').endswith('/' + oid), '%s %s' % (body.get('delivery'), created.header('Location')))
        made, session, reserve = SC.instant(body['createdAt']), SC.instant(body['paymentSessionExpiresAt']), SC.instant(body['reserveExpiresAt'])
        expect('TC-001. Сессия оплаты короче резерва (INV-07) и укладывается в ускоренные сроки: сессия %.0f с, резерв %.0f с'
               % ((session - made).total_seconds(), (reserve - made).total_seconds()),
               made < session < reserve and (session - made).total_seconds() <= 30 and (reserve - made).total_seconds() <= 45, '%s %s %s' % (made, session, reserve))
        link = (body.get('paymentSession') or {})
        expect('TC-001. Платёжная сессия ведёт на страницу оплаты через шлюз платформы, срок сессии совпадает',
               '/payment/pay/' in (link.get('url') or '') and link.get('expiresAt') == body.get('paymentSessionExpiresAt'), str(link))

        held = keys_of(pid) or {}
        expect('TC-001. Ключи в резерве: свободных на 2 меньше, зарезервированных на 2 больше', held.get('free', 0) == before.get('free', 0) - 2
               and held.get('reserved', 0) == before.get('reserved', 0) + 2, '%s -> %s' % (before, held))
        expect('TC-001. Резерв заказа один, активный, на 2 штуки', reservations_of(oid) == [('active', '', 2)], str(reservations_of(oid)))
        payments = stub_payments(stubs, oid)
        expect('TC-001. У шлюза один платёж на сумму заказа в рублях, пока не оплачен', len(payments) == 1 and payments[0].get('status') == 'pending'
               and (payments[0].get('amount') or {}).get('amount') == PRICE * 2 and (payments[0].get('amount') or {}).get('currency') == 'RUB', str(payments)[:300])

        again = create_order(buyer, pid, 2, key=key)
        replay = again.json() if again.status == 201 else {}
        expect('TC-003. Повтор запроса с тем же ключом: тот же заказ и та же платёжная сессия, помета Idempotency-Replayed (ADR-006)',
               again.status == 201 and again.header('Idempotency-Replayed') == 'true' and replay.get('orderId') == oid
               and (replay.get('paymentSession') or {}).get('url') == link.get('url'), describe(again))
        expect('TC-003. Повтор не взял вторую пару ключей и не завёл второй платёж', keys_of(pid) == held and len(stub_payments(stubs, oid)) == 1, '%s %s' % (keys_of(pid), len(stub_payments(stubs, oid))))
        reuse = create_order(buyer, pid, 3, key=key)
        expect('TC-003. Тот же ключ с другим запросом: 422 idempotency-key-reuse', reuse.status == 422 and reuse.code() == 'idempotency-key-reuse', describe(reuse))
        keyless = create_order(buyer, pid, 1, key=None)
        expect('TC-003. Запрос без Idempotency-Key: 400', keyless.status == 400, describe(keyless))

        card = get_order(buyer, oid)
        expect('TC-001. Карточка заказа: 200, awaiting_payment, no-store', card.status == 200 and card.json().get('status') == 'awaiting_payment' and 'no-store' in (card.header('Cache-Control') or ''), describe(card))
        sess = G.call('GET', '%s/%s/payment-session' % (ORDERS, oid), headers=G.bearer(buyer.token()))
        expect('TC-001. Платёжная сессия заказа открывается повторно без второго платежа: 200, тот же адрес',
               sess.status == 200 and sess.json().get('url') == link.get('url') and len(stub_payments(stubs, oid)) == 1, describe(sess))
        expect('TC-001. Заказ в истории покупателя со статусом awaiting_payment', any(i.get('orderId') == oid for i in list_orders(buyer, 'awaiting_payment')))
        stranger = get_order(other, oid)
        stranger_session = G.call('GET', '%s/%s/payment-session' % (ORDERS, oid), headers=G.bearer(other.token()))
        expect('TC-001. Чужой заказ и чужая платёжная сессия: 404, в чужой истории заказа нет', stranger.status == 404 and stranger_session.status == 404
               and not any(i.get('orderId') == oid for i in list_orders(other, 'awaiting_payment')), '%s %s' % (stranger.status, stranger_session.status))

        page, done = pay_on_page(link['url'])
        expect('TC-001. Страница оплаты открывается без токена через шлюз платформы: 200, сумма 2998.00 RUB, кнопка оплаты',
               page.status == 200 and '2998.00' in page.text and 'value="pay"' in page.text, '%s %s' % (page.status, page.text[:200]))
        expect('TC-001. Кнопка «Оплатить» (форма POST без префикса) возвращает покупателя на платформу: 303 с номером платежа', done.status == 303
               and 'paymentId=' in (done.header('Location') or ''), '%s %s' % (done.status, done.header('Location')))
        paid_ok, paid = wait_order(buyer, oid, 'paid', wait=30)
        expect('TC-001. Заказ стал paid, время оплаты записано, выдачи ещё нет', paid_ok and paid.get('paidAt') and not paid.get('issuedAt'), str(paid)[:300])
        expect('TC-001. Платёж confirmed, резерв использован, ключи остаются за заказом', payment_of(oid) and payment_of(oid)[0] == 'confirmed'
               and reservations_of(oid) == [('used', '', 2)], '%s %s' % (payment_of(oid), reservations_of(oid)))
        closed = G.call('GET', '%s/%s/payment-session' % (ORDERS, oid), headers=G.bearer(buyer.token()))
        expect('TC-001. У оплаченного заказа платёжной сессии больше нет: 409', closed.status == 409, describe(closed))
        expect('TC-001. Заказ в истории покупателя со статусом paid', any(i.get('orderId') == oid for i in list_orders(buyer, 'paid')))
    modes_are_default(stubs, 'TC-001')
    fx['correlations'].append(correlation)
    fx['emails'] += [buyer.email, other.email]


def insufficient_stock(fx):
    kc, admin, stubs = fx['kc'], fx['admin'], fx['stubs']
    print('== Заказ на товар, ключей которого меньше заказанного (US-5.2, FT-4.4, FT-5.2; TC-002, BPMN-01 E2)')
    stubs.reset()
    buyer = new_buyer(kc, admin, fx['stamp'], 'o2')
    if not buyer:
        return
    scarce = fx['scarce']
    before = keys_of(scarce)
    refused = create_order(buyer, scarce, SCARCE_KEYS + 1)
    expect('TC-002. Заказано %d, ключей %d: 409 insufficient-stock с числом свободных' % (SCARCE_KEYS + 1, SCARCE_KEYS),
           refused.status == 409 and refused.code() == 'insufficient-stock' and refused.json().get('available') == SCARCE_KEYS, describe(refused))
    cancelled = [i for i in list_orders(buyer, 'cancelled') if i.get('productId') == scarce]
    expect('TC-002. Заказ отменён с причиной out_of_stock и виден в истории (создан → отменён)', len(cancelled) == 1 and cancelled[0].get('cancelReason') == 'out_of_stock', str(cancelled)[:300])
    oid = cancelled[0]['orderId'] if cancelled else ''
    expect('TC-002. Резерва нет, ключи не тронуты, платежа у шлюза нет', reservations_of(oid) == [] and keys_of(scarce) == before and not stub_payments(stubs, oid),
           '%s %s %s' % (reservations_of(oid), keys_of(scarce), stub_payments(stubs, oid)))
    expect('TC-002. Ноль и одиннадцать штук отвергаются на входе: 422 и 422',
           create_order(buyer, scarce, 0).status == 422 and create_order(buyer, scarce, 11).status == 422)
    modes_are_default(stubs, 'TC-002')
    fx['emails'].append(buyer.email)


def phone_not_confirmed(fx):
    kc, admin, stubs = fx['kc'], fx['admin'], fx['stubs']
    print('== Первая покупка, вторая часть: телефон не подтверждён, затем подтверждён (US-1.6, US-5.1, FT-1.5; TC-003, BPMN-01 E1)')
    stubs.reset()
    buyer = new_buyer(kc, admin, fx['stamp'], 'o3', verified=False)
    if not buyer:
        return
    claims = buyer.claims()
    expect('TC-003. В токене нового покупателя phone_verified нет', not claims.get('phone_verified'), str(claims.get('phone_verified')))
    refused = create_order(buyer, fx['plenty'], 1)
    expect('TC-003. Заказ без подтверждённого телефона: 403 phone-not-confirmed', refused.status == 403 and refused.code() == 'phone-not-confirmed', describe(refused))
    history = G.call('GET', ORDERS, headers=G.bearer(buyer.token()))
    expect('TC-003. Заказ не создан: история пуста, ключей не взято', history.status == 200 and history.json().get('items') == [], describe(history))
    done = set_phone_verified(admin, buyer.id)
    claims = buyer.claims()
    expect('TC-003. Телефон подтверждён (SMS-путь: sms_login_checks.py), новый токен несёт phone_verified = true', done.status == 204 and claims.get('phone_verified') is True, '%s %s' % (done.status, claims.get('phone_verified')))
    pid = fx['plenty']
    created = create_order(buyer, pid, 1)
    body = created.json() if created.status == 201 else {}
    if not expect('TC-003. После подтверждения заказ создаётся: 201', created.status == 201 and body.get('status') == 'awaiting_payment', describe(created)):
        return
    oid = body['orderId']
    paid_ok, paid = wait_order(buyer, oid, 'paid', wait=40)
    expect('TC-003. Заглушка сама подтвердила платёж, заказ стал paid без участия человека', paid_ok and paid.get('paidAt'), str(paid)[:300])
    expect('TC-003. Платёж confirmed, резерв использован', payment_of(oid) and payment_of(oid)[0] == 'confirmed' and reservations_of(oid) == [('used', '', 1)], '%s %s' % (payment_of(oid), reservations_of(oid)))
    modes_are_default(stubs, 'TC-003')
    fx['emails'].append(buyer.email)


def payment_session_failed(fx):
    kc, admin, stubs = fx['kc'], fx['admin'], fx['stubs']
    print('== Шлюз не открыл платёжную сессию (US-5.2, NFT-4.3; TC-004, BPMN-01 E12)')
    stubs.reset()
    buyer = new_buyer(kc, admin, fx['stamp'], 'o4')
    if not buyer:
        return
    pid = fx['plenty']
    before = keys_of(pid) or {}
    with stubs.payment_mode(create={'failFirst': -1, 'failure': 'http_503'}):
        started = time.time()
        refused = create_order(buyer, pid, 1)
        elapsed = time.time() - started
    expect('TC-004. Шлюз отвечает 503 на создание платежа: 503 payment-session-unavailable за разумное время (%.1f с)' % elapsed,
           refused.status == 503 and refused.code() == 'payment-session-unavailable' and elapsed < 25, describe(refused))
    cancelled = [i for i in list_orders(buyer, 'cancelled') if i.get('productId') == pid]
    expect('TC-004. Заказ отменён с причиной payment_session_failed', len(cancelled) == 1 and cancelled[0].get('cancelReason') == 'payment_session_failed', str(cancelled)[:300])
    oid = cancelled[0]['orderId'] if cancelled else ''
    expect('TC-004. Резерв снят: ключи вернулись в свободные', A.eventually(lambda: keys_of(pid) == before, wait=30), '%s -> %s' % (before, keys_of(pid)))
    expect('TC-004. Резерв заказа released по причине order_cancelled, платежа у шлюза нет', A.eventually(lambda: reservations_of(oid) == [('released', 'order_cancelled', 1)], wait=20)
           and not stub_payments(stubs, oid), '%s %s' % (reservations_of(oid), stub_payments(stubs, oid)))
    modes_are_default(stubs, 'TC-004')
    fx['emails'].append(buyer.email)


def declined_and_expired(fx):
    kc, admin, stubs = fx['kc'], fx['admin'], fx['stubs']
    print('== Шлюз отклонил платёж; сессия истекла без оплаты (US-5.4, FT-6.3; TC-005, BPMN-01 E3, E4)')
    stubs.reset()
    buyer = new_buyer(kc, admin, fx['stamp'], 'o5')
    if not buyer:
        return
    pid = fx['plenty']
    before = keys_of(pid) or {}
    with stubs.payment_mode(scenario='reject', delayMs=500):
        created = create_order(buyer, pid, 1)
        body = created.json() if created.status == 201 else {}
        if not expect('TC-005. Заказ оформлен, шлюз отклонит платёж: 201', created.status == 201, describe(created)):
            return
        oid = body['orderId']
        ok, cancelled = wait_order(buyer, oid, 'cancelled', wait=40)
    expect('TC-005. Отказ шлюза: заказ отменён с причиной payment_declined', ok and cancelled.get('cancelReason') == 'payment_declined', str(cancelled)[:300])
    expect('TC-005. Резерв снят сразу, не дожидаясь срока: released по причине payment_declined, ключи свободны',
           A.eventually(lambda: reservations_of(oid) == [('released', 'payment_declined', 1)] and keys_of(pid) == before, wait=20), '%s %s' % (reservations_of(oid), keys_of(pid)))
    expect('TC-005. Платёж rejected, платёжной сессии у заказа больше нет: 409', payment_of(oid) and payment_of(oid)[0] == 'rejected'
           and G.call('GET', '%s/%s/payment-session' % (ORDERS, oid), headers=G.bearer(buyer.token())).status == 409, str(payment_of(oid)))

    with stubs.payment_mode(scenario='hold'):
        created = create_order(buyer, pid, 1)
        body = created.json() if created.status == 201 else {}
        if not expect('TC-005. Второй заказ оформлен, платить не будем: 201', created.status == 201, describe(created)):
            return
        oid = body['orderId']
        reserve_until = SC.instant(body['reserveExpiresAt'])
        ok, cancelled = wait_order(buyer, oid, 'cancelled', wait=120)
    expect('TC-005. Сессия истекла без оплаты: заказ отменён с причиной reservation_expired по сроку резерва', ok and cancelled.get('cancelReason') == 'reservation_expired', str(cancelled)[:300])
    expect('TC-005. Отмена не раньше срока резерва', datetime.now(timezone.utc) >= reserve_until - timedelta(seconds=2), str(reserve_until))
    expect('TC-005. Резерв released по причине expired, ключи свободны', A.eventually(lambda: reservations_of(oid) == [('released', 'expired', 1)] and keys_of(pid) == before, wait=30),
           '%s %s' % (reservations_of(oid), keys_of(pid)))
    expect('TC-005. Платёж остался created: шлюз ещё может подтвердить его позже (поздняя оплата, payments_checks.py)', payment_of(oid) and payment_of(oid)[0] == 'created', str(payment_of(oid)))
    modes_are_default(stubs, 'TC-005')
    fx['emails'].append(buyer.email)


def no_personal_data(fx):
    print('== Персональных данных покупателей нет в журналах сервисов покупки и в запросах к шлюзу (INV-20, NFT-5.1)')
    services = ['order-service', 'payment-service', 'inventory-service']
    logs = C.sh(G.COMPOSE + ['logs', '--no-color'] + services, timeout=300)
    text = logs.stdout + logs.stderr
    seen = any(c in text for c in fx['correlations'])
    expect('Контроль: поиск идёт по настоящим журналам (идентификатор запроса %s найден, %d КБ)' % (fx['correlations'][0][:8] if fx['correlations'] else '-', len(text) // 1024), seen, 'журналов %d байт' % len(text))
    found = [e for e in fx['emails'] if e in text]
    expect('В журналах order-service, payment-service и inventory-service адресов покупателей (%d) нет' % len(fx['emails']), not found, str(found[:2]))
    journal = json.dumps(fx['stubs'].journal(system='payment'), ensure_ascii=False)
    creates = [e for e in (fx['stubs'].journal(system='payment', kind='request').get('items') or []) if e.get('method') == 'POST' and e.get('path') == '/payment/v1/payments']
    expect('Контроль: в журнале заглушки есть запросы платформы на создание платежей (%d)' % len(creates), len(creates) >= 2, str(len(creates)))
    found = [e for e in fx['emails'] if e in journal]
    expect('В запросах платформы к платёжному шлюзу адресов покупателей нет', not found, str(found[:2]))


def run_scenarios(fx):
    for fn in (purchase_to_payment, insufficient_stock, phone_not_confirmed, payment_session_failed, declined_and_expired):
        guarded(fn.__name__, fn, fx)


def context(kc, admin, people, stamp):
    fixture = prepare(kc, admin, people, stamp)
    if not fixture:
        return None
    return dict(fixture, kc=kc, admin=admin, stubs=SB.StubsAdmin.from_env(), stamp=stamp, correlations=[], emails=[])


def summary(name):
    print('\nПроверок успешно: %d, с ошибками: %d' % (G.PASSED, len(G.FAILED)))
    for failed in G.FAILED:
        print('  - %s' % failed)
    if os.environ.get('GITHUB_ACTIONS'):
        print('::notice title=%s::Проверок успешно: %d, с ошибками: %d%s' % (name, G.PASSED, len(G.FAILED), ('; не прошли: ' + '|'.join(G.FAILED)) if G.FAILED else ''))
    return 1 if G.FAILED else 0


def main():
    kc, admin = K.from_env(dict(os.environ))
    people = G.users()
    if 'moderator-1' not in people:
        print('Нет тестового пользователя moderator-1 (make keycloak-users)', file=sys.stderr)
        return 2
    fx = context(kc, admin, people, int(time.time()))
    if fx:
        run_scenarios(fx)
        guarded('no_personal_data', no_personal_data, fx)
    else:
        G.bad('Продавец и товары для заказов не заведены, сценарии заказа не запускались')
    return summary('orders_checks')


if __name__ == '__main__':
    sys.exit(main())
