#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""API-ключ продавца и шлюз сквозь стенд (шаг 7 Ф5, контрольная точка К13; US-11.1, US-11.2, ADR-027, ADR-028, Р-185 – Р-188, Р-198, ST-01).

    make seller-api-check

Нужен набор full (платформа, Keycloak, шлюз, каталог, склад, объектное хранилище, заглушки) с отладочным файлом: make up SET=full DEBUG=1, затем
make keycloak-users. Проверка идёт настоящим входом и настоящими запросами через шлюз. Продавцов заводит сама: два, одобренных модератором и
вошедших со вторым фактором (TOTP), у каждого один опубликованный товар. Продавец «а» работает по ключам, продавец «б» служит «чужим»: чужой
товар, чужой ключ, соседний лимит. Канарейка шага 15 Ф4 (canary_checks.py) тратит весь часовой запас загрузок своего продавца, поэтому
этим проверкам нужны свои продавцы, а не тот же. Что проверяется:

  выпуск     значение ключа (dgm_ и 40 знаков) показано один раз, отпечаток это первые восемь знаков после префикса, список значения не знает,
             повтор по тому же Idempotency-Key отдаёт ответ без значения (TC-950, Р-198);
  вызов      getProductStock, getSellerProduct, listSellerProducts, uploadKeyBatch по ключу и по токену отвечают одинаково; чужой товар 404 и по
             ключу, и по токену, один и тот же ответ; X-Seller-Id и X-Auth-Kind из внешнего запроса не меняют, кто вызывает; неизвестный и
             испорченный ключ получают один и тот же ответ 401; токен вместе с ключом 400; операции кабинета (выпуск, список и отзыв ключей,
             подключение системы продавца) ключом недоступны: 401 и никаких следов (TC-955, TC-958, F5-1);
  подключение  адрес заглушки на стенде сохраняется по If-Match, секрет показан один раз и равен HMAC-SHA-256 мастер-секрета (считается заново
             здесь по файлу секретов), смена секрета даёт версию 2 и новый секрет, прежний больше не тот; запрещённые адреса (http, loopback,
             внутреннее имя, служебный диапазон, порт заглушки управления, логин в адресе, фрагмент) дают один и тот же отказ 422 без адреса
             в ответе (TC-954, TC-959);
  лимиты     загрузка пулов: шесть запросов в час на профиль продавца общим счётом для токена и ключа, седьмой 429 с Retry-After, другой
             продавец и чтение не затронуты; вызовы по ключу: 600 в минуту на ключ, лишнее 429, соседний ключ не затронут, после паузы ключ снова
             работает. Значения настоящие, а не стендовые: лимиты стенда от настоящих не отличаются (TC-957);
  отзыв      отозванный ключ перестаёт действовать не позже срока памяти шлюза (30 секунд) и получает тот же ответ, что неизвестный; чужой
             и несуществующий идентификатор ключа неотличимы; соседний ключ продолжает работать (TC-951, TC-956);
  канарейка  значения ключей, секреты подписи и мастер-секрет ищутся в ответах API, журналах всех контейнеров, темах Kafka catalog.events и
             audit.events и дампе catalog_db; адрес системы продавца: в журналах и темах (в базе он лежит по замыслу). У каждого поиска
             есть контрольный образец, который обязан найтись (TC-953, TC-960, ST-01).

Пока не проверяется (нужны шаги 8 – 11): вызов системы продавца платформой по подписи, ответ продавца по ключу respondSellerDelivery, webhook.
Лимиты и сроки памяти шлюза проверка не меняет и не трогает: чужие проверки идут с настоящими значениями.
"""
import base64
import hashlib
import hmac
import http.client
import json
import os
import re
import sys
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import accounts_checks as A  # noqa: E402
import canary_checks as C  # noqa: E402
import gateway_checks as G  # noqa: E402
import kc_client as K  # noqa: E402
import seller_approval_checks as SA  # noqa: E402
import sellers_checks as SC  # noqa: E402
import sms_login_checks as S  # noqa: E402

expect = G.expect
KEYS = '/api/v1/seller/api-keys'
INTEGRATION = '/api/v1/seller/integration'
PRODUCTS = '/api/v1/seller/products'
KEY_RE = re.compile(r'^dgm_[A-Za-z0-9_-]{40}$')
SECRET_RE = re.compile(r'^[0-9a-f]{64}$')
STUB = 'external-stubs'            # заглушка системы продавца: имя и порт в списке разрешённых (dgm.seller-endpoint.allow, compose.yaml)
CACHE_SECONDS = 30                 # срок памяти шлюза о действующем ключе (application.yml, ADR-027)
UPLOAD_BUDGET = 6                  # загрузок в час на профиль продавца (seller-upload)
KEY_BUDGET = 600                   # вызовов в минуту на ключ (seller-api)
SEEN = []                          # все ответы API, кроме тех, где секрет или значение показаны по замыслу: в них секреты ищутся


# --------------------------------------------------------------------------------------------------- вызовы
class Seller:
    """Одобренный продавец со вторым фактором. Токен доступа живёт 5 минут, поэтому старше 200 секунд он перевыпускается."""

    def __init__(self, kc, buyer, totp_secret):
        self.kc, self.buyer, self.totp_secret = kc, buyer, totp_secret
        self.id = buyer['sellerId']
        self.value, self.issued = None, 0.0
        self.product_id = None

    def token(self):
        if self.value is None or time.time() - self.issued > 200:
            r = self.kc.login('dgm-staff', self.buyer['email'], self.buyer['password'], self.totp_secret)
            expect('вход продавца со вторым фактором: токен получен', r.ok, S.describe(r))
            self.value = r.tokens['access_token'] if r.ok else self.value
            self.issued = time.time()
        return self.value


def send(method, path, headers, body=None, legit=False):
    payload = json.dumps(body, ensure_ascii=False).encode() if isinstance(body, (dict, list)) else body
    response = G.call(method, path, headers=headers, body=payload, timeout=60)
    if not legit:
        SEEN.append('%s %s %s' % (response.status, response.headers, response.text))
    return response


def by_token(seller, method, path, body=None, legit=False, **extra):
    headers = SC.auth(seller.token(), **extra)
    if body is not None:
        headers.setdefault('Content-Type', 'application/json')
    if method == 'POST':
        headers.setdefault('Idempotency-Key', str(uuid.uuid4()))
    return send(method, path, headers, body, legit)


def by_key(value, method, path, body=None, legit=False, **extra):
    headers = {'X-Api-Key': value}
    headers.update(extra)
    if body is not None:
        headers.setdefault('Content-Type', 'application/json')
    if method == 'POST':
        headers.setdefault('Idempotency-Key', str(uuid.uuid4()))
    return send(method, path, headers, body, legit)


def problem(response):
    """Что отличает один отказ от другого: статус, код, заголовок и пояснение (идентификатор запроса и время у каждого свои)."""
    try:
        body = response.json()
    except ValueError:
        return (response.status, None, None, None)
    return (response.status, body.get('code'), body.get('title'), body.get('detail'))


def describe(response):
    return '%s %s' % (response.status, response.text[:240])


def retry_after(response):
    try:
        return int(response.header('Retry-After') or '')
    except ValueError:
        return None


def fresh_keys(count, tag='SAPI'):
    return ['%s-%s' % (tag, uuid.uuid4().hex) for _ in range(count)]


def stock(product_id):
    return '%s/%s/stock' % (PRODUCTS, product_id)


# --------------------------------------------------------------------------------------------------- продавцы и товары
def first_login(kc, buyer, tag):
    """Первый вход одобренного продавца: настройка TOTP, затем вход с кодом (amr = pwd, otp). Возвращает Seller или None."""
    d = kc.start('dgm-staff')
    d.post(username=buyer['email'], password=buyer['password'])
    if not expect('%s: после пароля Keycloak требует настроить TOTP (%s)' % (tag, d.kind), d.kind == 'totp_setup', d.kind + ' ' + d.message):
        return None
    secret = d.setup_totp('проверка шага 7 Ф5')
    d.tokens()
    seller = Seller(kc, buyer, secret)
    return seller if seller.token() else None


def onboard(kc, admin, moderator, stamp):
    """Два продавца: заявки подаются, потом одобряются обе, потом ждётся Keycloak (платформа ставит роль по событию), потом вход."""
    print('== Два продавца: заявка, одобрение, роль в Keycloak, вход со вторым фактором, один опубликованный товар у каждого')
    buyers = []
    for tag in ('sapi-a', 'sapi-b'):
        buyer = SA.applicant(kc, admin, stamp, tag)
        if not buyer:
            return None
        buyers.append(buyer)
    moderator.token()
    for buyer in buyers:
        approved = SA.decide(moderator, buyer['sellerId'], 'approve')
        if not expect('заявка одобрена: 200, approved', approved.status == 200 and approved.json().get('status') == 'approved', describe(approved)):
            return None
    for buyer in buyers:
        ok = A.eventually(lambda: SA.keycloak_is_seller(admin, buyer['id'], buyer['sellerId']), wait=45)
        if not expect('платформа поставила роль seller и seller_id (%s)' % buyer['sellerId'][:8], ok):
            return None
    sellers = []
    for buyer, tag in zip(buyers, ('а', 'б')):
        seller = first_login(kc, buyer, 'продавец ' + tag)
        if not seller:
            return None
        sellers.append(seller)
    for seller, tag in zip(sellers, ('а', 'б')):
        title = 'Лунная тропа %d %s: ключ Steam' % (stamp, tag)
        created = by_token(seller, 'POST', PRODUCTS, SA.product_form(title))
        if not expect('продавец %s завёл товар: 201' % tag, created.status == 201, describe(created)):
            return None
        pid = created.json()['productId']
        submitted = by_token(seller, 'POST', '%s/%s/submit' % (PRODUCTS, pid))
        approved = SA.staff_decide(moderator, pid, 'approve')
        if not expect('товар подан и одобрен модератором: 200 и 200', submitted.status == 200 and approved.status == 200, '%s %s' % (submitted.status, approved.status)):
            return None
        seller.product_id = pid
    for seller, tag in zip(sellers, ('а', 'б')):
        # Копия товара в inventory-service приходит из событий каталога: до неё загрузка отвечает 404, и отказ тратил бы запас загрузок
        caught = A.eventually(lambda: (C.copy_state(seller.product_id) or ('',))[0] == 'published', wait=90)
        if not expect('TC-955. Копия товара продавца %s в inventory_db опубликована (загрузка не упрётся в 404)' % tag, caught, str(C.copy_state(seller.product_id))):
            return None
    return sellers


# --------------------------------------------------------------------------------------------------- выпуск и список
def issuing(seller, name='Склад'):
    """Выпускает ключ. Возвращает словарь: value, id, fingerprint, idem (ключ идемпотентности), response."""
    idem = str(uuid.uuid4())
    first = by_token(seller, 'POST', KEYS, {'name': name}, legit=True, **{'Idempotency-Key': idem})
    body = first.json() if first.status == 201 else {}
    return {'value': body.get('value'), 'id': (body.get('apiKey') or {}).get('id'), 'fingerprint': (body.get('apiKey') or {}).get('fingerprint'),
            'idem': idem, 'response': first, 'body': body}


def issue_checks(a):
    print('== Выпуск ключа: значение показано один раз, список и повтор значения не знают (TC-950, Р-185, Р-198)')
    key = issuing(a, 'Склад')
    first, body, value = key['response'], key['body'], key['value']
    expect('TC-950. Выпуск: 201, no-store, значение вида dgm_ и 40 знаков, shownOnce, статус active',
           first.status == 201 and 'no-store' in (first.header('Cache-Control') or '') and bool(value) and bool(KEY_RE.match(value or ''))
           and body.get('shownOnce') is True and (body.get('apiKey') or {}).get('status') == 'active', describe(first)[:120])
    if not value:
        return None
    expect('TC-950. Отпечаток в ответе: первые восемь знаков после префикса, в ответе значение один раз', key['fingerprint'] == value[4:12]
           and first.text.count(value) == 1, '%s %s' % (key['fingerprint'], first.text.count(value)))
    replay = by_token(a, 'POST', KEYS, {'name': 'Склад'}, **{'Idempotency-Key': key['idem']})
    replay_body = replay.json() if replay.status in (200, 201) else {}
    expect('TC-950. Повтор по тому же Idempotency-Key: тот же ключ (id), значения в ответе нет (Р-198)',
           replay.status in (200, 201) and replay.header('Idempotency-Replayed') == 'true' and 'value' not in replay_body
           and (replay_body.get('apiKey') or {}).get('id') == key['id'] and value not in replay.text, describe(replay))
    listed = by_token(a, 'GET', KEYS)
    items = listed.json().get('items') or [] if listed.status == 200 else []
    mine = [i for i in items if i.get('id') == key['id']]
    expect('TC-950. Список ключей: 200, ключ есть, поля без значения, значения и его части в ответе нет',
           listed.status == 200 and len(mine) == 1 and set(mine[0]) == {'id', 'name', 'fingerprint', 'status', 'createdAt', 'revokedAt'}
           and value not in listed.text and value[4:] not in listed.text, describe(listed))
    return key


# --------------------------------------------------------------------------------------------------- вызов по ключу
def key_calls(a, b, k1, k_b):
    print('== Вызов по ключу: свои операции, чужой товар, подмена заголовков, испорченные ключи, операции кабинета (TC-955, TC-958, F5-1)')
    pa, pb, value = a.product_id, b.product_id, k1['value']
    correlation = str(uuid.uuid4())
    own = by_key(value, 'GET', stock(pa), **{'X-Correlation-Id': correlation})
    body = own.json() if own.status == 200 else {}
    expect('TC-955. Остаток своего товара по ключу: 200, productId и нулевой остаток (ключей ещё нет)', own.status == 200 and body.get('productId') == pa
           and body.get('available') == 0, describe(own))
    by_tok = by_token(a, 'GET', stock(pa))
    expect('TC-955. Тот же запрос по токену даёт тот же остаток, поля те же', by_tok.status == 200
           and set(by_tok.json()) == set(body) and by_tok.json().get('available') == body.get('available'), describe(by_tok))
    card = by_key(value, 'GET', '%s/%s' % (PRODUCTS, pa))
    expect('TC-955. Карточка своего товара по ключу: 200', card.status == 200 and card.json().get('productId') == pa, describe(card))
    page = by_key(value, 'GET', PRODUCTS + '?limit=100')
    ids = [i.get('productId') for i in (page.json().get('items') or [])] if page.status == 200 else []
    expect('TC-955. Список товаров по ключу: только товары этого продавца (свой есть, чужого нет)', page.status == 200 and pa in ids and pb not in ids, '%s %s' % (page.status, ids))

    # чужой товар: по ключу и по токену один и тот же ответ, существование чужого товара не раскрывается
    foreign_key = by_key(value, 'GET', stock(pb))
    foreign_token = by_token(a, 'GET', stock(pb))
    unknown = by_key(value, 'GET', stock(str(uuid.uuid4())))
    control = by_token(b, 'GET', stock(pb))
    expect('TC-955. Контроль: хозяин видит остаток своего товара: 200', control.status == 200 and control.json().get('productId') == pb, describe(control))
    expect('TC-955. Чужой товар по ключу: 404, по токену: тот же ответ; несуществующий товар: тот же ответ (существование не раскрывается)',
           foreign_key.status == 404 and problem(foreign_key) == problem(foreign_token) == problem(unknown), '%s | %s | %s' % (
               problem(foreign_key), problem(foreign_token), problem(unknown)))

    # подмена заголовков личности (ST-13, TC-958)
    spoof = {'X-Seller-Id': b.id, 'X-Auth-Kind': 'token'}
    spoofed_key = by_key(value, 'GET', stock(pb), **spoof)
    spoofed_own = by_key(value, 'GET', stock(pa), **spoof)
    spoofed_token = by_token(a, 'GET', stock(pb), **spoof)
    expect('TC-958. X-Seller-Id и X-Auth-Kind из внешнего запроса по ключу ничего не меняют: чужой товар 404, свой 200',
           spoofed_key.status == 404 and spoofed_own.status == 200, '%s %s' % (spoofed_key.status, spoofed_own.status))
    expect('TC-958. То же по токену: подменённый X-Seller-Id не открывает чужой товар (404)', spoofed_token.status == 404, describe(spoofed_token))

    # ключи, которые не пройдут: неизвестный, испорченный, чужого вида. Ответ один (ADR-027, п. 3)
    unknown_value = 'dgm_' + base64.urlsafe_b64encode(os.urandom(30)).decode().rstrip('=')
    answers = {name: by_key(v, 'GET', stock(pa)) for name, v in (('неизвестный', unknown_value), ('короткий', 'dgm_short'), ('чужого вида', 'Bearer ' + value),
                                                                  ('без префикса', value[4:]))}
    expect('TC-955. Неизвестный, короткий, чужого вида и без префикса ключ: 401 и один и тот же ответ',
           all(r.status == 401 for r in answers.values()) and len({problem(r) for r in answers.values()}) == 1,
           str({n: problem(r) for n, r in answers.items()}))
    both = send('GET', stock(pa), dict(SC.auth(a.token()), **{'X-Api-Key': value}))
    expect('TC-955. Токен вместе с ключом: 400, шлюз не угадывает, кто вызывает', both.status == 400, describe(both))

    # операции кабинета ключом недоступны, и следов не остаётся
    before_keys = by_token(a, 'GET', KEYS).json().get('items') or []
    before = by_token(a, 'GET', INTEGRATION)
    attempts = [('POST', KEYS, {'name': 'Чужой замысел'}), ('GET', KEYS, None), ('POST', '%s/%s/revocation' % (KEYS, k1['id']), None),
                ('GET', INTEGRATION, None), ('PUT', INTEGRATION, {'endpointUrl': 'https://%s:8443/seller/x/request' % STUB}),
                ('POST', INTEGRATION + '/secret-rotation', None)]
    codes = []
    for method, path, payload in attempts:
        headers = {'If-Match': '"0"'} if method == 'PUT' else {}
        r = by_key(value, method, path, payload, **headers)
        codes.append((method, path.rsplit('/', 1)[-1], r.status))
    expect('F5-1. Выпуск, список, отзыв ключей и подключение системы продавца по ключу: 401 у всех шести операций', all(s == 401 for _, _, s in codes), str(codes))
    after_keys = by_token(a, 'GET', KEYS).json().get('items') or []
    after = by_token(a, 'GET', INTEGRATION)
    expect('F5-1. Следов нет: ключей столько же и они действуют, подключение не изменилось',
           len(after_keys) == len(before_keys) and all(i.get('status') == 'active' for i in after_keys) and after.status == 200
           and after.json() == before.json(), '%d и %d ключей' % (len(before_keys), len(after_keys)))
    other = by_key(k_b['value'], 'GET', stock(pb))
    expect('TC-955. Контроль: ключ продавца «б» работает на его товаре: 200', other.status == 200, describe(other))
    return correlation


# --------------------------------------------------------------------------------------------------- подключение системы продавца
def master_secret():
    try:
        with open(os.path.join(G.SECRETS, 'seller_webhook_master'), 'rb') as f:
            return f.read().rstrip(b'\r\n')
    except OSError:
        return None


def derived(master, seller_id, version):
    """Секрет подписи продавца: HMAC-SHA-256(мастер, «seller-integration|<продавец>|<версия>»), 64 знака hex (ADR-028, п. 1)."""
    return hmac.new(master, ('seller-integration|%s|%d' % (seller_id, version)).encode(), hashlib.sha256).hexdigest()


def integration_checks(a, slug):
    print('== Подключение системы продавца: адрес заглушки, секрет подписи, смена, запрещённые адреса (TC-954, TC-959; Р-187)')
    master = master_secret()
    expect('Контроль: мастер-секрет стенда прочитан (каталог секретов %s)' % G.SECRETS, master is not None and len(master) >= 16)
    result = {'secrets': [], 'address': 'https://%s:8443/seller/%s/request' % (STUB, slug), 'slug': slug}
    empty = by_token(a, 'GET', INTEGRATION)
    body = empty.json() if empty.status == 200 else {}
    expect('TC-954. До подключения: 200, адреса нет, версия 0, ETag "0"', empty.status == 200 and body.get('endpointUrl') is None and body.get('version') == 0
           and empty.header('ETag') == '"0"', describe(empty))
    no_match = by_token(a, 'PUT', INTEGRATION, {'endpointUrl': result['address']})
    expect('TC-954. Без If-Match: 428', no_match.status == 428, describe(no_match))

    saved = by_token(a, 'PUT', INTEGRATION, {'endpointUrl': result['address']}, legit=True, **{'If-Match': '"0"'})
    sbody = saved.json() if saved.status == 200 else {}
    secret = sbody.get('secret') or ''
    result['secrets'].append(secret)
    expect('TC-954. Подключение: 200, no-store, адрес сохранён, статус active, версия секрета 1, секрет 64 знака hex один раз',
           saved.status == 200 and 'no-store' in (saved.header('Cache-Control') or '') and (sbody.get('integration') or {}).get('endpointUrl') == result['address']
           and (sbody.get('integration') or {}).get('status') == 'active' and (sbody.get('integration') or {}).get('secretVersion') == 1
           and bool(SECRET_RE.match(secret)) and saved.text.count(secret) == 1, describe(saved)[:140])
    if master and secret:
        expect('TC-959. Секрет равен HMAC-SHA-256 мастер-секрета от «seller-integration|продавец|1» (считается заново по файлу секретов)',
               hmac.compare_digest(secret, derived(master, a.id, 1)), 'секрет другой')
    again = by_token(a, 'PUT', INTEGRATION, {'endpointUrl': result['address']}, **{'If-Match': saved.header('ETag') or '"1"'})
    expect('TC-954. Повторное сохранение того же состояния по новой версии: 200, секрета в ответе нет', again.status == 200
           and 'secret' not in again.json() and secret not in again.text, describe(again))
    stale = by_token(a, 'PUT', INTEGRATION, {'endpointUrl': result['address']}, **{'If-Match': '"0"'})
    expect('TC-954. Устаревшая версия: 412 version-conflict', stale.status == 412 and stale.code() == 'version-conflict', describe(stale))

    # запрещённые адреса: один отказ, адрес в ответе не повторяется; в записи остаётся прежний
    token = uuid.uuid4().hex[:12]
    refused = [('http', 'http://%s:8443/ssrf-%s' % (STUB, token)), ('порт заглушки управления', 'https://%s:8444/ssrf-%s' % (STUB, token)),
               ('loopback по имени', 'https://localhost:8443/ssrf-%s' % token), ('loopback по числу', 'https://127.0.0.1:8443/ssrf-%s' % token),
               ('служебный диапазон', 'https://169.254.169.254/ssrf-%s' % token), ('внутреннее имя сети стенда', 'https://postgres:8443/ssrf-%s' % token),
               ('логин в адресе', 'https://login:pwd@%s:8443/ssrf-%s' % (STUB, token)), ('фрагмент', 'https://%s:8443/ssrf-%s#part' % (STUB, token)),
               ('имя не находится', 'https://no-such-name.invalid/ssrf-%s' % token)]
    version = by_token(a, 'GET', INTEGRATION).header('ETag')
    details, problems = set(), []
    for name, url in refused:
        r = by_token(a, 'PUT', INTEGRATION, {'endpointUrl': url}, **{'If-Match': version})
        details.add(problem(r)[2:])
        if not (r.status == 422 and r.code() == 'endpoint-not-allowed' and token not in r.text and 'postgres' not in r.text and '169.254' not in r.text):
            problems.append('%s: %s' % (name, describe(r)))
    expect('TC-954. Запрещённые адреса (%d видов): 422 endpoint-not-allowed, текст отказа один, адрес в ответе не повторяется' % len(refused),
           not problems and len(details) == 1, str(problems[:2]) + ' текстов отказа: %d' % len(details))
    result['ssrf'] = token
    kept = by_token(a, 'GET', INTEGRATION)
    expect('TC-954. После отказов подключение прежнее: адрес и версия записи', kept.status == 200 and kept.json().get('endpointUrl') == result['address']
           and kept.header('ETag') == version, describe(kept))

    # смена секрета
    idem = str(uuid.uuid4())
    rotated = by_token(a, 'POST', INTEGRATION + '/secret-rotation', legit=True, **{'Idempotency-Key': idem})
    rbody = rotated.json() if rotated.status == 200 else {}
    new_secret = rbody.get('secret') or ''
    result['secrets'].append(new_secret)
    expect('TC-959. Смена секрета: 200, no-store, версия 2, новый секрет 64 знака hex, не равен первому, shownOnce', rotated.status == 200
           and 'no-store' in (rotated.header('Cache-Control') or '') and rbody.get('secretVersion') == 2 and bool(SECRET_RE.match(new_secret))
           and new_secret != secret and rbody.get('shownOnce') is True, describe(rotated)[:140])
    if master and new_secret:
        expect('TC-959. Новый секрет равен HMAC-SHA-256 мастер-секрета для версии 2, прежний ему не равен (наложения нет, Р-187)',
               hmac.compare_digest(new_secret, derived(master, a.id, 2)) and not hmac.compare_digest(secret, derived(master, a.id, 2)), 'секрет другой')
    replay = by_token(a, 'POST', INTEGRATION + '/secret-rotation', **{'Idempotency-Key': idem})
    replay_body = replay.json() if replay.status == 200 else {}
    expect('TC-959. Повтор смены по тому же Idempotency-Key: 200, Idempotency-Replayed, секрета в ответе нет, версия не выросла (Р-198)',
           replay.status == 200 and replay.header('Idempotency-Replayed') == 'true' and 'secret' not in replay_body and replay_body.get('secretVersion') == 2
           and new_secret not in replay.text, describe(replay))
    read = by_token(a, 'GET', INTEGRATION)
    expect('TC-959. Чтение подключения после смены: версия секрета 2, секрета в ответе нет', read.status == 200 and read.json().get('secretVersion') == 2
           and 'secret' not in read.json() and new_secret not in read.text, describe(read))
    return result


# --------------------------------------------------------------------------------------------------- лимиты
def uploads_and_limits(a, b, k1, k_ctl, k_burst):
    print('== Лимит загрузок: шесть в час на продавца общим счётом для токена и ключа (TC-957, Р-188)')
    value, pa = k1['value'], a.product_id
    statuses = []

    def upload(how):
        keys = fresh_keys(2)
        if how == 'key':
            return by_key(value, 'POST', '%s/%s/key-batches' % (PRODUCTS, pa), {'keys': keys})
        if how == 'file':
            return by_key(value, 'POST', '%s/%s/key-files' % (PRODUCTS, pa), ('\r\n'.join(keys) + '\r\n').encode(), **{'Content-Type': 'text/csv'})
        return by_token(a, 'POST', '%s/%s/key-batches' % (PRODUCTS, pa), {'keys': keys})

    first = by_key(value, 'POST', '%s/%s/key-batches' % (PRODUCTS, pa), {'keys': fresh_keys(3)})
    statuses.append(first.status)
    body = first.json() if first.status == 200 else {}
    expect('TC-955. Загрузка по ключу (первая из шести): 200, принято 3, остаток 3', first.status == 200 and body.get('accepted') == 3 and body.get('available') == 3, describe(first))
    left = by_key(value, 'GET', stock(pa))
    expect('TC-955. Остаток по ключу сразу после загрузки: 3', left.status == 200 and left.json().get('available') == 3, describe(left))
    second = upload('token')
    statuses.append(second.status)
    expect('TC-957. Загрузка по токену кабинета: 200, принято 2, остаток 5 (общий склад, один и тот же товар)', second.status == 200
           and second.json().get('accepted') == 2 and second.json().get('available') == 5, describe(second))
    limited = None
    for number in range(3, UPLOAD_BUDGET + 4):
        r = upload(('key', 'token', 'file')[number % 3])
        statuses.append(r.status)
        if r.status == 429:
            limited = r
            break
    expect('TC-957. Первые %d загрузок (ключ, токен и файл по очереди) приняты, %d-я получает 429' % (UPLOAD_BUDGET, UPLOAD_BUDGET + 1),
           limited is not None and statuses[:UPLOAD_BUDGET] == [200] * UPLOAD_BUDGET and len(statuses) == UPLOAD_BUDGET + 1, str(statuses))
    if limited is not None:
        wait = retry_after(limited)
        expect('TC-957. 429 с Retry-After в секундах (один запрос возвращается за 10 минут: 1 – 600) и без значений ключей', wait is not None and 1 <= wait <= 600, str(limited.headers))
    other = by_token(b, 'POST', '%s/%s/key-batches' % (PRODUCTS, b.product_id), {'keys': fresh_keys(1)})
    expect('TC-957. Другой продавец загружает свободно: 200 (счёт по профилю продавца, а не общий)', other.status == 200, describe(other))
    reads = (by_key(value, 'GET', stock(pa)).status, by_token(a, 'GET', stock(pa)).status)
    expect('TC-957. Чтение остатка при исчерпанном запасе загрузок работает (по ключу и по токену): 200 и 200', reads == (200, 200), str(reads))

    print('== Лимит вызовов по ключу: 600 в минуту на ключ, соседний ключ не затронут (TC-957, Р-188)')
    path = stock(pa)
    ctx = G.context()

    def one(_):
        conn = http.client.HTTPSConnection(G.GATEWAY[0], G.GATEWAY[1], context=ctx, timeout=30)
        try:
            conn.request('GET', path, headers={'X-Api-Key': k_burst['value'], 'Accept': 'application/json'})
            r = conn.getresponse()
            r.read()
            return r.status, r.getheader('Retry-After')
        except OSError as e:
            return 0, str(e)
        finally:
            conn.close()

    started = time.time()
    with ThreadPoolExecutor(16) as pool:
        results = list(pool.map(one, range(KEY_BUDGET + 250)))
    elapsed = time.time() - started
    ok = sum(1 for s, _ in results if s == 200)
    denied = [(s, ra) for s, ra in results if s == 429]
    others = sorted({s for s, _ in results if s not in (200, 429)})
    allowed_max = KEY_BUDGET + int(10 * elapsed) + 30     # запас в корзине и пополнение десять в секунду за время обстрела
    expect('TC-957. Обстрел одного ключа (%d запросов за %.1f с): принято %d (запас 600, а не стендовые 20), лишние получают 429, других ответов нет (%s)' % (
        len(results), elapsed, ok, others), 500 <= ok <= allowed_max and len(denied) >= 1 and not others, '%d принято, %d отказов, прочее %s' % (ok, len(denied), others))
    if denied:
        wait = int(denied[0][1]) if (denied[0][1] or '').isdigit() else None
        expect('TC-957. 429 по ключу несёт Retry-After 1 – 60 секунд', wait is not None and 1 <= wait <= 60, str(denied[0]))
    neighbour = by_key(k_ctl['value'], 'GET', path)
    expect('TC-957. Соседний ключ того же продавца не затронут: 200', neighbour.status == 200, describe(neighbour))
    expect('TC-957. Токен кабинета не затронут: 200', by_token(a, 'GET', path).status == 200)
    if denied and denied[0][1] and denied[0][1].isdigit():
        time.sleep(int(denied[0][1]) + 1)
    after = by_key(k_burst['value'], 'GET', path)
    expect('TC-957. После паузы по Retry-After обстрелянный ключ снова работает: 200', after.status == 200, describe(after))


# --------------------------------------------------------------------------------------------------- отзыв
def revoke_checks(a, b, k_rev, k_b):
    print('== Отзыв ключа: статус, повтор, чужой идентификатор, действие на шлюзе не позже срока памяти (TC-951, TC-956)')
    pa = a.product_id
    warmed = by_key(k_rev['value'], 'GET', stock(pa))
    expect('TC-956. Контроль: ключ до отзыва работает (и шлюз его запомнил): 200', warmed.status == 200, describe(warmed))
    revoked = by_token(a, 'POST', '%s/%s/revocation' % (KEYS, k_rev['id']))
    stamp = time.time()
    rbody = revoked.json() if revoked.status == 200 else {}
    expect('TC-951. Отзыв: 200, статус revoked, revokedAt есть, значения нет', revoked.status == 200 and rbody.get('status') == 'revoked'
           and bool(rbody.get('revokedAt')) and k_rev['value'] not in revoked.text, describe(revoked))
    repeat = by_token(a, 'POST', '%s/%s/revocation' % (KEYS, k_rev['id']))
    expect('TC-951. Повторный отзыв ничего не меняет: 200, та же дата отзыва', repeat.status == 200 and repeat.json().get('revokedAt') == rbody.get('revokedAt'), describe(repeat))
    foreign = by_token(a, 'POST', '%s/%s/revocation' % (KEYS, k_b['id']))
    missing = by_token(a, 'POST', '%s/%s/revocation' % (KEYS, uuid.uuid4()))
    expect('TC-951. Чужой и несуществующий идентификатор ключа: 404 и один и тот же ответ', foreign.status == 404 and problem(foreign) == problem(missing),
           '%s | %s' % (problem(foreign), problem(missing)))
    expect('TC-951. Контроль: чужой ключ не тронут и работает: 200', by_key(k_b['value'], 'GET', stock(b.product_id)).status == 200)
    listed = by_token(a, 'GET', KEYS + '?status=revoked')
    expect('TC-951. Список отозванных ключей: ключ есть со статусом revoked', listed.status == 200
           and any(i.get('id') == k_rev['id'] and i.get('status') == 'revoked' for i in listed.json().get('items') or []), describe(listed))
    return stamp


class Watch(threading.Thread):
    """Опрашивает отозванный ключ раз в секунду, пока шлюз его не отвергнет. Идёт рядом с долгим поиском канарейки: момент, когда ключ
    перестал действовать, виден по часам, а не по тому, когда до него дошла очередь проверки."""

    def __init__(self, seller, key, since):
        super().__init__(daemon=True)
        self.seller, self.key, self.since = seller, key, since
        self.stopped_at, self.last = None, None

    def run(self):
        deadline = self.since + CACHE_SECONDS + 15
        while time.time() < deadline:
            self.last = by_key(self.key['value'], 'GET', stock(self.seller.product_id))
            if self.last.status == 401:
                self.stopped_at = time.time()
                return
            time.sleep(1)


def revoke_effect(a, k_rev, watch, unknown_answer):
    """Итог наблюдения: отозванный ключ перестаёт проходить не позже срока памяти шлюза (ключ из памяти проходит до её конца, ADR-027, п. 3)."""
    watch.join(timeout=CACHE_SECONDS + 30)
    took = (watch.stopped_at - watch.since) if watch.stopped_at else None
    expect('TC-956. Отозванный ключ перестал действовать не позже срока памяти шлюза (%s с после отзыва, срок %d с): 401' % (
        '%.0f' % took if took is not None else 'не перестал', CACHE_SECONDS), took is not None and took <= CACHE_SECONDS + 6,
        describe(watch.last) if watch.last is not None else 'опросов нет')
    if took is not None:
        expect('TC-955. Отозванный ключ получает тот же ответ, что неизвестный (401 без подсказки)', problem(watch.last) == unknown_answer,
               '%s | %s' % (problem(watch.last), unknown_answer))
        again = [by_key(k_rev['value'], 'GET', stock(a.product_id)).status for _ in range(3)]
        expect('TC-956. Отказ держится: следующие три запроса тоже 401', again == [401, 401, 401], str(again))


# --------------------------------------------------------------------------------------------------- канарейка
def needles(values):
    """Что искать: значения целиком и их записи в hex и в Base64 (обычном и для адресов). Общего префикса, как у канарейки пула, у них нет."""
    result = list(values)
    for value in values:
        raw = value.encode()
        result += [raw.hex(), base64.b64encode(raw).decode(), base64.urlsafe_b64encode(raw).decode().rstrip('=')]
    return result


def canary_search(a, keys, integration, correlation, master):
    print('-- Поиск значений ключей, секретов подписи и мастер-секрета: ответы API, журналы контейнеров, темы Kafka, дамп catalog_db')
    values = [k['value'] for k in keys if k.get('value')]
    secrets = [s for s in integration['secrets'] if s]
    wanted = needles(values + secrets)
    if master:
        wanted += [master.decode('utf-8', 'replace')]
    address_wanted = [integration['address'], '/seller/%s/request' % integration['slug'], integration['slug'], 'ssrf-' + integration['ssrf']]

    expect('TC-953. Контроль: ответов API собрано %d, среди них отказы 401, 404, 422 и 429' % len(SEEN),
           len(SEEN) >= 50 and all(any(a_.startswith(code) for a_ in SEEN) for code in ('401', '404', '422', '429')), str(len(SEEN)))
    found = [f for answer in SEEN for f in C.leaks(answer, wanted)]
    expect('TC-953. В ответах API (кроме трёх мест, где секрет показан по замыслу) значений ключей и секретов нет', not found, str(found[:2]))

    logs = C.compose_logs()
    expect('TC-953. Контроль: поиск идёт по настоящим журналам (идентификатор запроса %s найден в журнале inventory-service)' % correlation[:8],
           any(correlation in line and 'inventory-service' in line for line in logs.splitlines()), 'журналов %d байт' % len(logs))
    found = C.leaks(logs, wanted) + C.leaks(logs, address_wanted)
    expect('TC-953. В журналах всех контейнеров нет ни значений ключей, ни секретов, ни мастер-секрета, ни адреса системы продавца (%d МБ)' % (len(logs) // 1048576),
           not found, str(found[:2]))

    catalog, note = C.kafka_events('catalog.events', 'platform-service', '0 1 2')
    audit, note_audit = C.kafka_events('audit.events', 'platform-service', '0')
    ids = [k['id'] for k in keys if k.get('id')]
    if catalog is None or audit is None:
        expect('TC-960. Темы catalog.events и audit.events прочитаны', False, '%s %s' % (note, note_audit))
    else:
        expect('TC-960. Контроль: в catalog.events событие api-key.issued каждого из выпущенных ключей',
               all(any('api-key.issued' in line and i in line for line in catalog.splitlines()) for i in ids), (catalog + note)[-300:])
        expect('TC-960. Контроль: в audit.events записи seller.api_key_issued, seller.api_key_revoked, seller.integration_set и seller.integration_secret_rotated продавца «а»',
               all(any(action in line and a.id in line for line in audit.splitlines()) for action in
                   ('seller.integration_set', 'seller.integration_secret_rotated')) and any('seller.api_key_issued' in line for line in audit.splitlines())
               and any('seller.api_key_revoked' in line for line in audit.splitlines()), (audit + note_audit)[-300:])
        sha = lambda text: hashlib.sha256(text.encode()).hexdigest()   # noqa: E731
        host, path = STUB, '/seller/%s/request' % integration['slug']
        record = [line for line in audit.splitlines() if 'seller.integration_set' in line and a.id in line]
        expect('TC-960. Запись seller.integration_set несёт SHA-256 хоста и пути вместо адреса (контроль: хеши есть, имени хоста и пути нет)',
               bool(record) and sha(host) in record[0] and sha(path) in record[0] and host not in record[0] and path not in record[0], record[0][:300] if record else 'записи нет')
        found = C.leaks(catalog, wanted) + C.leaks(catalog, address_wanted) + C.leaks(audit, wanted) + C.leaks(audit, address_wanted)
        expect('TC-960. В темах catalog.events и audit.events (%d и %d записей) значений ключей, секретов, мастер-секрета и адреса нет' % (
            len(catalog.splitlines()), len(audit.splitlines())), not found, str(found[:2]))

    dump, note = C.database_dump('catalog_db')
    inserts = [line for line in dump.splitlines() if line.startswith('INSERT INTO seller_onboarding.api_key ')]
    stored = all(any(k['id'] in line and "'\\x%s'" % hashlib.sha256(k['value'].encode()).hexdigest() in line for line in inserts) for k in keys if k.get('value'))
    expect('TC-953. Контроль: в дампе catalog_db у каждого ключа строка с SHA-256 значения (INV-41: хеш есть, значения нет); строк ключей %d' % len(inserts),
           stored and len(inserts) >= len(ids), '%d строк; %s' % (len(inserts), note))
    integration_rows = [line for line in dump.splitlines() if line.startswith('INSERT INTO seller_onboarding.seller_integration ') and a.id in line]
    expect('TC-959. Контроль: подключение продавца «а» в дампе есть, адрес лежит открыто по замыслу, версия секрета 2',
           len(integration_rows) == 1 and integration['address'] in integration_rows[0] and re.search(r"', 2, 'active', ", integration_rows[0]) is not None,
           (integration_rows or [''])[0][:240])
    idempotency = [line for line in dump.splitlines() if line.startswith('INSERT INTO public.idempotency_key ')]
    expect('TC-953. Контроль: таблица идемпотентности в дампе есть, в ней ответы выпуска ключа и смены секрета без значений (строк %d)' % len(idempotency),
           any(keys[0]['id'] in line for line in idempotency) and any('secretVersion' in line for line in idempotency), str(len(idempotency)))
    found = C.leaks(dump, wanted)
    expect('TC-953. В дампе catalog_db значений ключей, секретов подписи и мастер-секрета нет (%d КБ)' % (len(dump) // 1024), not found, str(found[:2]))


# --------------------------------------------------------------------------------------------------- запуск
def main():
    kc, admin = K.from_env(dict(os.environ))
    people = G.users()
    if 'moderator-1' not in people:
        print('Нет тестового пользователя moderator-1 (make keycloak-users)', file=sys.stderr)
        return 2
    stamp = int(time.time())
    moderator = SA.Staff(kc, admin, people['moderator-1'])
    sellers = onboard(kc, admin, moderator, stamp)
    if sellers:
        a, b = sellers
        k1 = issue_checks(a)
        k_b = issuing(b, 'Склад «б»')
        expect('Продавцу «б» выпущен ключ (чужой для всех проверок «а»)', bool(k_b.get('value')), describe(k_b['response']))
        if k1 and k_b.get('value'):
            correlation = key_calls(a, b, k1, k_b)
            integration = integration_checks(a, 'sapi-%d' % stamp)
            k_ctl, k_rev, k_burst = issuing(a, 'Контрольный'), issuing(a, 'Отзываемый'), issuing(a, 'Обстрел')
            expect('Выпущены ещё три ключа продавца «а» (контрольный, отзываемый, для обстрела): значения получены', all(k.get('value') for k in (k_ctl, k_rev, k_burst)),
                   str([describe(k['response']) for k in (k_ctl, k_rev, k_burst) if not k.get('value')]))
            if all(k.get('value') for k in (k_ctl, k_rev, k_burst)):
                uploads_and_limits(a, b, k1, k_ctl, k_burst)
                revoked_at = revoke_checks(a, b, k_rev, k_b)
                watch = Watch(a, k_rev, revoked_at)
                watch.start()
                unknown = problem(by_key('dgm_' + base64.urlsafe_b64encode(os.urandom(30)).decode().rstrip('='), 'GET', stock(a.product_id)))
                canary_search(a, [k1, k_b, k_ctl, k_rev, k_burst], integration, correlation, master_secret())
                revoke_effect(a, k_rev, watch, unknown)
    print('\nПроверок успешно: %d, с ошибками: %d' % (G.PASSED, len(G.FAILED)))
    for name in G.FAILED:
        print('  - %s' % name)
    if os.environ.get('GITHUB_ACTIONS'):
        print('::notice title=seller_api_checks::Проверок успешно: %d, с ошибками: %d%s' % (
            G.PASSED, len(G.FAILED), ('; не прошли: ' + '|'.join(G.FAILED)) if G.FAILED else ''))
    return 1 if G.FAILED else 0


if __name__ == '__main__':
    sys.exit(main())
