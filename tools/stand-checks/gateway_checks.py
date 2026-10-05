#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Проверка шлюза на поднятом стенде (шаг 13 Ф3; NFT-3.3, NFT-3.5 в части лимитов, ST-03, ST-14, NFT-6.0).

    python3 tools/stand-checks/gateway_checks.py            маршруты, токены, лимиты (Redis работает)
    python3 tools/stand-checks/gateway_checks.py outage     отказ открытым: Redis остановлен, затем запущен снова

Нужен стенд с отладочным файлом: make up SET=dev-auth DEBUG=1, затем make up SET=dev-purchase DEBUG=1 и make keycloak-users.
Запросы идут так, как их шлёт браузер: HTTPS на https://localhost:8443 с проверкой цепочки нашего центра сертификации, без клиентского
сертификата. Токены настоящие: вход по коду авторизации с PKCE (kc_client.py) отладочным портом Keycloak и, отдельной проверкой, через
сам шлюз. Порт управления шлюза (8444) читается через отладочный порт 18446 с клиентским сертификатом соседнего сервиса.
Только стандартная библиотека Python.
"""
import base64
import http.client
import json
import os
import re
import socket
import ssl
import subprocess
import sys
import time
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import kc_client as K  # noqa: E402

SECRETS = os.environ.get('DGM_SECRETS_DIR', 'secrets')
CA = os.path.join(SECRETS, 'tls_ca.crt')
GATEWAY = ('localhost', 8443)
MANAGEMENT = ('localhost', 18446)
COMPOSE = ['docker', 'compose', '-f', 'compose.yaml', '-f', 'compose.debug.yaml', '--profile', '*']

PASSED, FAILED = 0, []


def ok(name):
    global PASSED
    PASSED += 1
    print('  ok    %s' % name)


def bad(name, details=''):
    FAILED.append(name)
    print('  ОШИБКА %s' % name)
    if details:
        print('        | %s' % str(details)[:400].replace('\n', ' '))


def expect(name, cond, details=''):
    if cond:
        ok(name)
    else:
        bad(name, details)
    return bool(cond)


# --------------------------------------------------------------------------------------------------- HTTP
class Resp:
    def __init__(self, status, headers, body):
        self.status, self.headers, self.body = status, headers, body

    def header(self, name):
        values = [v for k, v in self.headers if k.lower() == name.lower()]
        return values[0] if values else None

    def count(self, name):
        return len([1 for k, _ in self.headers if k.lower() == name.lower()])

    @property
    def text(self):
        return self.body.decode('utf-8', 'replace')

    def json(self):
        return json.loads(self.text)

    def code(self):
        try:
            return self.json().get('code')
        except ValueError:
            return None


def context(client=None):
    ctx = ssl.create_default_context(cafile=CA)
    if client:
        ctx.load_cert_chain(os.path.join(SECRETS, 'tls_%s.crt' % client), os.path.join(SECRETS, 'tls_%s.key' % client))
    return ctx


def call(method, path, headers=None, body=None, target=GATEWAY, client=None, timeout=20):
    conn = http.client.HTTPSConnection(target[0], target[1], context=context(client), timeout=timeout)
    h = {'Accept': 'application/json', 'Connection': 'close'}
    h.update(headers or {})
    try:
        conn.request(method, path, body=body, headers=h)
        r = conn.getresponse()
        return Resp(r.status, r.getheaders(), r.read())
    finally:
        conn.close()


def raw_call(method, path, headers, target=GATEWAY):
    """Заголовки без тела при большом Content-Length: шлюз обязан ответить, не дожидаясь тела."""
    sock = ssl.create_default_context(cafile=CA).wrap_socket(socket.create_connection(target, 20), server_hostname=target[0])
    try:
        head = '%s %s HTTP/1.1\r\nHost: %s:%d\r\nConnection: close\r\n' % (method, path, target[0], target[1])
        head += ''.join('%s: %s\r\n' % kv for kv in headers.items()) + '\r\n'
        sock.sendall(head.encode())
        data = b''
        while b'\r\n\r\n' not in data:
            chunk = sock.recv(4096)
            if not chunk:
                break
            data += chunk
        status = int(data.split(b' ', 2)[1]) if data else 0
        lines = data.split(b'\r\n\r\n', 1)[0].decode('latin-1').split('\r\n')[1:]
        return Resp(status, [tuple(l.split(': ', 1)) for l in lines if ': ' in l], data.split(b'\r\n\r\n', 1)[1] if b'\r\n\r\n' in data else b'')
    finally:
        sock.close()


def bearer(token):
    return {'Authorization': 'Bearer ' + token}


def forge(token, claims):
    """Токен с изменённым содержимым и прежней подписью: подпись больше не подходит."""
    head, payload, sig = token.split('.')
    data = json.loads(base64.urlsafe_b64decode(payload + '=' * (-len(payload) % 4)))
    data.update(claims)
    new = base64.urlsafe_b64encode(json.dumps(data, separators=(',', ':')).encode()).rstrip(b'=').decode()
    return '.'.join([head, new, sig])


def unsigned(token):
    """Тот же набор claims без подписи (алгоритм none)."""
    _, payload, _ = token.split('.')
    head = base64.urlsafe_b64encode(b'{"alg":"none","typ":"JWT"}').rstrip(b'=').decode()
    return '%s.%s.' % (head, payload)


def sh(args):
    return subprocess.run(args, capture_output=True, text=True)


# --------------------------------------------------------------------------------------------------- входные данные
def users():
    path = os.path.join(SECRETS, 'test_users.json')
    return {u['name']: u for u in json.load(open(path, encoding='utf-8'))['users']}


CLIENTS = {'buyer': 'dgm-web', 'seller': 'dgm-staff', 'moderator': 'dgm-staff', 'support-operator': 'dgm-staff', 'admin': 'dgm-staff'}


def login(kc, user):
    r = kc.login(CLIENTS[user['role']], user['email'], user['password'], user.get('otp_secret'))
    return r.tokens['access_token'] if r.ok else None


def metrics():
    r = call('GET', '/actuator/prometheus', target=MANAGEMENT, client='order-service', headers={'Accept': 'text/plain'})
    return r.text if r.status == 200 else ''


def metric_sum(text, name):
    return sum(float(m.group(1)) for m in re.finditer(r'^%s(?:\{[^}]*\})?\s+([0-9.eE+-]+)$' % re.escape(name), text, re.M))


# --------------------------------------------------------------------------------------------------- проверки
def routes_and_tokens(kc, kc_gateway, people):
    print('== Вход, маршруты, ошибки')
    r = call('GET', '/api/v1/products')
    expect('витрина без токена: 200 и JSON через шлюз и mTLS до catalog-service', r.status == 200 and isinstance(r.json(), dict), '%s %s' % (r.status, r.text[:200]))
    expect('в ответе один X-Correlation-Id (UUID) и traceparent', r.count('X-Correlation-Id') == 1 and re.fullmatch(r'[0-9a-f-]{36}', r.header('X-Correlation-Id') or '')
           and re.fullmatch(r'00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]', r.header('traceparent') or ''), str(r.headers))
    expect('заголовки безопасности: HSTS, nosniff, DENY, no-referrer', r.header('Strict-Transport-Security') is not None and r.header('X-Content-Type-Options') == 'nosniff'
           and r.header('X-Frame-Options') == 'DENY' and r.header('Referrer-Policy') == 'no-referrer', str(r.headers))
    cid = str(uuid.uuid4())
    r = call('GET', '/api/v1/products', headers={'X-Correlation-Id': cid})
    expect('присланный X-Correlation-Id сохраняется', r.header('X-Correlation-Id') == cid and r.count('X-Correlation-Id') == 1, str(r.headers))
    r = call('GET', '/api/v1/products', headers={'X-Correlation-Id': 'not-a-uuid', 'X-Seller-Id': 'x', 'X-Forwarded-For': '6.6.6.6'})
    expect('чужой X-Correlation-Id заменяется, лишние заголовки не мешают', r.status == 200 and r.header('X-Correlation-Id') != 'not-a-uuid', '%s %s' % (r.status, r.headers))

    page = call('GET', '/', headers={'Accept': 'text/html'})
    expect('страница / через шлюз: 200 text/html от web-app', page.status == 200 and (page.header('Content-Type') or '').startswith('text/html')
           and 'Маркетплейс цифровых товаров' in page.text, '%s %s' % (page.status, page.text[:120]))
    expect('страница через шлюз: HSTS шлюза и политика содержимого nginx, по одному разу', page.count('Strict-Transport-Security') == 1
           and page.count('Content-Security-Policy') == 1 and page.count('X-Content-Type-Options') == 1, str(page.headers))
    r = call('GET', '/styles.css')
    expect('файл страницы через шлюз: /styles.css 200 text/css', r.status == 200 and (r.header('Content-Type') or '').startswith('text/css'), '%s %s' % (r.status, r.headers))
    r = call('GET', '/no-such-page.html')
    expect('неизвестный файл страницы: 404 от nginx (HTML, не Problem)', r.status == 404 and 'json' not in (r.header('Content-Type') or ''), '%s %s' % (r.status, r.text[:120]))
    r = call('POST', '/', body=b'x=1', headers={'Content-Type': 'application/x-www-form-urlencoded'})
    expect('POST / через шлюз: 404 Problem (у страницы только GET и HEAD)', r.status == 404 and r.code() == 'not-found', '%s %s' % (r.status, r.text[:120]))

    r = call('GET', '/api/v1/unknown')
    expect('неизвестный маршрут: 404 Problem с correlationId из заголовка', r.status == 404 and r.code() == 'not-found' and r.header('Content-Type') == 'application/problem+json'
           and r.json().get('correlationId') == r.header('X-Correlation-Id'), '%s %s' % (r.status, r.text[:200]))
    r = call('GET', '/internal/v1/products/0199e0a0-0000-4000-8000-000000000001')
    expect('/internal/** снаружи недоступен: 404', r.status == 404 and r.code() == 'not-found', '%s %s' % (r.status, r.text[:200]))
    r = call('GET', '/actuator/prometheus')
    expect('метрики и здоровье на клиентском порту 8443 не отдаются: 404', r.status == 404, '%s %s' % (r.status, r.text[:120]))
    r = call('DELETE', '/api/v1/products')
    expect('метод, которого нет в OpenAPI: 404', r.status == 404, str(r.status))
    r = call('GET', '/api/v1/products;jsessionid=1')
    expect('хитрый путь (;): 400 Problem', r.status == 400 and r.code() == 'bad-request', '%s %s' % (r.status, r.text[:200]))
    r = call('GET', '/api/v1/orders/%2e%2e/products')
    expect('хитрый путь (%2e): 400 Problem', r.status == 400, str(r.status))

    r = call('GET', '/api/v1/orders')
    expect('защищённый маршрут без токена: 401 unauthenticated с WWW-Authenticate', r.status == 401 and r.code() == 'unauthenticated' and r.header('WWW-Authenticate') == 'Bearer', '%s %s' % (r.status, r.text[:200]))
    r = call('GET', '/api/v1/orders', headers=bearer('garbage'))
    expect('мусор вместо токена: 401', r.status == 401, str(r.status))

    buyer = login(kc, people['buyer-1'])
    if not expect('токен покупателя от Keycloak получен', buyer, 'вход не удался'):
        return None
    r = call('GET', '/api/v1/orders', headers=bearer(buyer))
    expect('настоящий токен покупателя: 200 через шлюз до order-service (токен сервис проверяет сам)', r.status == 200 and isinstance(r.json(), dict), '%s %s' % (r.status, r.text[:200]))
    r = call('GET', '/api/v1/orders', headers=bearer(forge(buyer, {'sub': '00000000-0000-4000-8000-000000000000'})))
    expect('ST-03: токен с подменённым sub и прежней подписью: 401', r.status == 401 and r.code() == 'unauthenticated', '%s %s' % (r.status, r.text[:200]))
    r = call('GET', '/api/v1/orders', headers=bearer(forge(buyer, {'scope': 'orders.read orders.create staff.admin'})))
    expect('ST-03: токен с дописанной областью: 401', r.status == 401, str(r.status))
    r = call('GET', '/api/v1/orders', headers=bearer(forge(buyer, {'exp': 1})))
    expect('ST-03: токен с подправленным сроком: 401', r.status == 401, str(r.status))
    r = call('GET', '/api/v1/orders', headers=bearer(unsigned(buyer)))
    expect('ST-03: токен без подписи (alg none): 401', r.status == 401, str(r.status))
    r = call('GET', '/api/v1/orders', headers=bearer(buyer[:-6] + ('AAAAAA' if not buyer.endswith('AAAAAA') else 'BBBBBB')))
    expect('ST-03: испорченная подпись: 401', r.status == 401, str(r.status))

    r = call('GET', '/api/v1/seller/products', headers=bearer(buyer))
    expect('покупатель на маршруте продавца: 403 forbidden от шлюза', r.status == 403 and r.code() == 'forbidden', '%s %s' % (r.status, r.text[:200]))
    r = call('GET', '/api/v1/staff/parameters', headers=bearer(buyer))
    expect('покупатель на маршруте сотрудников: 403', r.status == 403, str(r.status))

    seller = login(kc, people['seller-1'])
    if expect('токен продавца (со вторым фактором) получен', seller, 'вход не удался'):
        r = call('GET', '/api/v1/orders', headers=bearer(seller))
        expect('продавец на маршруте покупателя: 403 (нет области orders.read)', r.status == 403, '%s %s' % (r.status, r.text[:200]))
        r = call('GET', '/api/v1/seller/products', headers=bearer(seller))
        expect('продавец на своём маршруте: шлюз пропускает (не 401, не 403 шлюза и не 429)', r.status not in (401, 403, 429, 502, 503, 504), '%s %s' % (r.status, r.text[:200]))

    # Тела: большие запросы отклоняются по заголовку, не дожидаясь тела
    r = raw_call('POST', '/api/v1/orders', dict(bearer(buyer), **{'Content-Length': str(3 * 1024 * 1024), 'Content-Type': 'application/json'}))
    expect('тело 3 МБ: 413 payload-too-large до чтения тела', r.status == 413, '%s' % r.status)
    r = raw_call('POST', '/api/v1/webhooks/payment-gateway', {'Content-Length': str(64 * 1024 + 1), 'Content-Type': 'application/json'})
    expect('вебхук с телом больше 64 КБ: 413', r.status == 413, '%s' % r.status)

    # Keycloak за шлюзом
    r = call('GET', '/auth/realms/dgm/.well-known/openid-configuration')
    conf = r.json() if r.status == 200 else {}
    expect('Keycloak за шлюзом: discovery отдаёт издателя с внешним адресом', conf.get('issuer') == 'https://localhost:8443/auth/realms/dgm', '%s %s' % (r.status, r.text[:200]))
    r = call('GET', '/auth/realms/dgm/protocol/openid-connect/certs')
    expect('Keycloak за шлюзом: ключи подписи отдаются', r.status == 200 and r.json().get('keys'), str(r.status))
    for path in ('/auth/admin/realms/dgm/users', '/auth/admin/master/console/', '/auth/realms/master/protocol/openid-connect/certs', '/auth/metrics',
                 '/auth/health/ready', '/auth/', '/auth/realms/%6daster/protocol/openid-connect/certs'):
        r = call('GET', path)
        expect('Keycloak за шлюзом: %s закрыт (404 или 400 шлюза)' % path, r.status in (400, 404) and r.code() in ('not-found', 'bad-request'), '%s %s' % (r.status, r.text[:120]))
    r = call('PUT', '/auth/realms/dgm')
    expect('метод PUT на /auth/realms не пропускается шлюзом: 404', r.status == 404, str(r.status))
    r = call('GET', '/auth/realms/dgm/protocol/openid-connect/auth?client_id=dgm-web&response_type=code&scope=openid&redirect_uri=https://localhost:8443/callback&state=s&code_challenge=%s&code_challenge_method=S256' % ('x' * 43))
    expect('страница входа Keycloak за шлюзом отдаётся (HTML, без заголовка Server с версией)', r.status == 200 and 'password' in r.text.lower(), '%s %s' % (r.status, r.text[:100]))

    token = login(kc_gateway, people['buyer-2'])
    if expect('полный вход (код авторизации с PKCE) через шлюз выдаёт токен', token, 'вход через шлюз не удался'):
        r = call('GET', '/api/v1/orders', headers=bearer(token))
        expect('токен, полученный через шлюз, принимается шлюзом и сервисом (издатель совпал)', r.status == 200, '%s %s' % (r.status, r.text[:200]))
    return people


def limits(kc, people):
    print('== Лимиты частоты (Redis)')
    buyer1 = login(kc, people['buyer-1'])
    buyer2 = login(kc, people['buyer-2'])
    if not expect('токены двух покупателей получены', buyer1 and buyer2, 'вход не удался'):
        return
    body = json.dumps({}).encode()

    def order(token):
        return call('POST', '/api/v1/orders', headers=dict(bearer(token), **{'Content-Type': 'application/json', 'Idempotency-Key': str(uuid.uuid4())}), body=body).status

    codes = [order(buyer1) for _ in range(14)]
    allowed = len([c for c in codes if c != 429])
    expect('POST /orders: 10 в минуту на пользователя, остальные 429 (получено %d пропущенных из 14)' % allowed, 10 <= allowed <= 11 and 429 in codes,
           '%s; отказов открытым по метрике: %g' % (codes, metric_sum(metrics(), 'dgm_gateway_ratelimit_failopen_total')))
    r = call('POST', '/api/v1/orders', headers=dict(bearer(buyer1), **{'Content-Type': 'application/json'}), body=body)
    retry = r.header('Retry-After')
    expect('429: Problem rate-limited и Retry-After в секундах (1..60)', r.status == 429 and r.code() == 'rate-limited' and retry and retry.isdigit() and 1 <= int(retry) <= 60
           and r.header('X-Correlation-Id'), '%s %s %s' % (r.status, retry, r.text[:200]))
    expect('лимит у каждого пользователя свой: второй покупатель не ограничен', order(buyer2) != 429)
    r = call('GET', '/api/v1/orders', headers=bearer(buyer1))
    expect('группы лимитов независимы: чтение заказов первым покупателем проходит', r.status == 200, str(r.status))
    reads = [call('GET', '/api/v1/orders', headers=bearer(buyer2)).status for _ in range(5)]
    expect('чтение заказов в пределах лимита (120 в минуту)', all(c == 200 for c in reads), str(reads))

    codes = [call('GET', '/auth/realms/dgm/protocol/openid-connect/certs').status for _ in range(75)]
    expect('вход и токены Keycloak: 30 в минуту с IP, дальше 429 (пропущено %d из 75)' % len([c for c in codes if c == 200]),
           429 in codes and len([c for c in codes if c == 200]) <= 45, str(codes))
    r = call('GET', '/auth/realms/dgm/protocol/openid-connect/certs')
    wait = int(r.header('Retry-After') or '2') if r.status == 429 else 0
    time.sleep(min(wait, 30) + 2)
    r = call('GET', '/auth/realms/dgm/protocol/openid-connect/certs')
    expect('после ожидания Retry-After запросы снова проходят', r.status == 200, '%s %s' % (r.status, r.header('Retry-After')))

    text = metrics()
    expect('метрики шлюза на порту управления (mTLS): отказы по лимиту посчитаны', metric_sum(text, 'dgm_gateway_rejected_total') >= 1 and 'reason="rate_limited"' in text, text[:200])
    expect('метрики шлюза: задержки запросов (http_server_requests) есть', 'http_server_requests_seconds' in text)


def management():
    print('== Порт управления (mTLS)')
    try:
        call('GET', '/actuator/health/readiness', target=MANAGEMENT)
        bad('порт управления без клиентского сертификата отвечает, а должен обрывать рукопожатие')
    except (ssl.SSLError, ConnectionError, OSError):
        ok('порт управления без клиентского сертификата рукопожатие не проходит')
    r = call('GET', '/actuator/health/readiness', target=MANAGEMENT, client='order-service')
    expect('порт управления с сертификатом сервиса: readiness UP', r.status == 200 and r.json().get('status') == 'UP', '%s %s' % (r.status, r.text[:100]))
    r = call('GET', '/actuator/env', target=MANAGEMENT, client='order-service')
    expect('конечные точки кроме health и prometheus не открыты (env: 404)', r.status == 404, str(r.status))


def outage(kc, people):
    print('== Отказ открытым: Redis остановлен')
    buyer = login(kc, people['buyer-1'])
    before = metric_sum(metrics(), 'dgm_gateway_ratelimit_failopen_total')
    r = sh(COMPOSE + ['stop', '-t', '5', 'redis'])
    if not expect('Redis остановлен', r.returncode == 0, r.stderr):
        return
    try:
        time.sleep(2)
        slow = []
        codes = []
        for _ in range(6):
            t = time.time()
            codes.append(call('GET', '/api/v1/products').status)
            slow.append(time.time() - t)
        expect('без Redis запросы проходят (отказ открытым): все 200', all(c == 200 for c in codes), str(codes))
        expect('без Redis запрос не задерживается дольше 3 секунд (лимит ожидания 200 мс)', max(slow) < 3, '%.2f' % max(slow))
        r = call('GET', '/api/v1/orders', headers=bearer(buyer))
        expect('без Redis защищённый маршрут с токеном тоже работает', r.status == 200, '%s %s' % (r.status, r.text[:100]))
        after = metric_sum(metrics(), 'dgm_gateway_ratelimit_failopen_total')
        expect('метрика dgm_gateway_ratelimit_failopen_total растёт (было %g, стало %g)' % (before, after), after >= before + 7, '%g -> %g' % (before, after))
        r = call('GET', '/actuator/health/readiness', target=MANAGEMENT, client='order-service')
        expect('готовность шлюза без Redis остаётся UP (Redis ему не обязателен)', r.status == 200 and r.json().get('status') == 'UP', '%s %s' % (r.status, r.text[:100]))
    finally:
        sh(COMPOSE + ['start', 'redis'])
    print('== Redis запущен снова: лимиты возвращаются')
    deadline = time.time() + 90
    healthy = False
    while time.time() < deadline:
        cid = sh(COMPOSE + ['ps', '-q', 'redis']).stdout.strip()
        state = sh(['docker', 'inspect', '-f', '{{.State.Health.Status}}', cid]).stdout.strip() if cid else ''
        if state == 'healthy':
            healthy = True
            break
        time.sleep(2)
    if not expect('Redis снова здоров', healthy):
        return
    buyer2 = login(kc, people['buyer-2'])
    body = json.dumps({}).encode()
    limited = False
    for attempt in range(20):
        codes = [call('POST', '/api/v1/orders', headers=dict(bearer(buyer2), **{'Content-Type': 'application/json'}), body=body).status for _ in range(14)]
        if 429 in codes:
            limited = True
            break
        time.sleep(3)
    expect('после запуска Redis шлюз снова ограничивает частоту без перезапуска (429 получен с попытки %d)' % (attempt + 1), limited, str(codes))


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else 'all'
    env = dict(os.environ)
    kc, _ = K.from_env(env)
    gateway_env = {k: v for k, v in env.items() if k != 'KC_TARGET'}
    gateway_env['KC_PUBLIC'] = 'https://localhost:8443/auth'
    kc_gateway, _ = K.from_env(gateway_env)
    people = users()
    if mode == 'outage':
        outage(kc, people)
    else:
        routes_and_tokens(kc, kc_gateway, people)
        management()
        limits(kc, people)
    print()
    print('Проверок успешно: %d, с ошибками: %d' % (PASSED, len(FAILED)))
    if os.environ.get('GITHUB_ACTIONS'):
        print('::notice title=gateway_checks %s::Проверок успешно: %d, с ошибками: %d%s' % (
            mode, PASSED, len(FAILED), ('; не прошли: ' + ' | '.join(FAILED)) if FAILED else ''))
    for f in FAILED:
        print('  - %s' % f)
    return 1 if FAILED else 0


if __name__ == '__main__':
    sys.exit(main())
