#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Дымовой тест стенда (шаг 17 Ф3; ST-03, ST-14, NFT-3.3 для внутренних вызовов, сквозной идентификатор, Outbox → Kafka).

    make up SET=full DEBUG=1 && make keycloak-users && make smoke

Дымовой тест отвечает на один вопрос: «стенд в целом живой, и главный путь через шлюз работает». Он короткий (около минуты), идёт по
одному разу через каждый слой и не заменяет регрессионные проверки: те проверяют много случаев на каждый слой отдельно
(make gateway-check, make kit-test и другие). Если дымовой тест красный, дальше смотреть нечего.

Что проходит запрос, от входа до записи:

  вход          настоящий токен покупателя от Keycloak по коду авторизации с PKCE, запросы идут через шлюз (https://localhost:8443)
  операции      страница /, витрина GET /api/v1/products (ETag и 304), история заказов GET /api/v1/orders с токеном: в базе order_db
                лежит служебный заказ покупателя 1, его видит только он (U1, владелец берётся из токена); без токена 401; /internal/** закрыт
  ST-03         токен без подписи (alg none), подписан HS256, подписан чужим ключом RSA с верным издателем, чужой издатель, изменённая область
                (подпись прежняя), настоящий токен с истёкшим сроком (срок клиента сокращён через Admin API на время выпуска): везде 401
  mTLS          внутренний вызов catalog-service от order-service проходит дальше фильтра вызывающего (404: такого товара нет), от другого
                сервиса с сертификатом нашего центра 403, без клиентского сертификата рукопожатие не проходит
  Outbox        служебная строка в outbox базы order_db публикуется order-service в Kafka (тема order.events), читается из Kafka по маркеру
  журналы       один X-Correlation-Id виден в журналах шлюза и сервиса, trace id совпадает с заголовком ответа; при поднятом стеке
                наблюдения тот же идентификатор находится в Loki по меткам нескольких сервисов

Проверка проверки: SMOKE_SABOTAGE=token|scope|cert намеренно портит токен, область или сертификат вызывающего в «хорошем» пути, и тест
обязан стать красным (CI запускает все три, tools/ci/smoke-sabotage.sh). Переменные: KC_TARGET (отладочный порт Keycloak для Admin API),
SMOKE_SKIP_EXPIRY=1 пропускает проверку просроченного токена (ждать около 40 секунд).
Нужны Docker и openssl. Остальное стандартная библиотека Python.
"""
import base64
import hashlib
import hmac
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import gateway_checks as G  # noqa: E402
import kc_client as K  # noqa: E402

SABOTAGE = os.environ.get('SMOKE_SABOTAGE', '')
ISSUER = 'https://localhost:8443/auth/realms/dgm'
LOKI = os.environ.get('LOKI_URL', 'http://127.0.0.1:13100')
SKEW = 30           # допуск расхождения часов шлюза и сервисов, секунд (JwtSettings.DEFAULT_SKEW)
EXPIRY_SECONDS = 5  # срок токена, который выпускается ради проверки просрочки

# Клиент Kafka в контейнере образа: принципал delivery-service (потребитель order.events по ACL), смещения не сохраняются, читается вся тема с начала
KAFKA_CONSUMER = r"""
D=/tmp/c; mkdir -p $D
cat /s/tls_delivery-service.key /s/tls_delivery-service.crt > $D/ks.pem
printf 'security.protocol=SSL\nssl.truststore.type=PEM\nssl.truststore.location=/s/tls_ca.crt\nssl.keystore.type=PEM\nssl.keystore.location=%s\ndefault.api.timeout.ms=15000\nrequest.timeout.ms=10000\n' $D/ks.pem > $D/c.properties
export KAFKA_HEAP_OPTS=-Xmx128m LOG_DIR=/tmp/l
/opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9093 --consumer.config $D/c.properties --topic order.events \
  --group delivery-service --from-beginning --timeout-ms 12000 --consumer-property enable.auto.commit=false --property print.key=true
"""


def note(text):
    print('  ----  %s' % text)


# --------------------------------------------------------------------------------------------------- токены
def b64(data):
    return base64.urlsafe_b64encode(data).rstrip(b'=').decode()


def parts(token):
    head, payload, _ = token.split('.')
    pad = lambda s: s + '=' * (-len(s) % 4)  # noqa: E731
    return json.loads(base64.urlsafe_b64decode(pad(head))), json.loads(base64.urlsafe_b64decode(pad(payload)))


def compact(header, claims):
    return '%s.%s' % (b64(json.dumps(header, separators=(',', ':')).encode()), b64(json.dumps(claims, separators=(',', ':')).encode()))


class Forger:
    """Токены, которые Keycloak не выпускал: своим ключом RSA (openssl) и HMAC."""

    def __init__(self):
        self.dir = tempfile.mkdtemp(prefix='dgm-smoke-')
        self.key = os.path.join(self.dir, 'k.pem')
        subprocess.run(['openssl', 'genrsa', '-out', self.key, '2048'], check=True, capture_output=True)

    def rs256(self, header, claims):
        signing = compact(dict(header, alg='RS256'), claims)
        sig = subprocess.run(['openssl', 'dgst', '-sha256', '-sign', self.key], input=signing.encode(), capture_output=True, check=True).stdout
        return '%s.%s' % (signing, b64(sig))

    @staticmethod
    def hs256(header, claims, secret=b'dgm-smoke-secret'):
        signing = compact(dict(header, alg='HS256'), claims)
        return '%s.%s' % (signing, b64(hmac.new(secret, signing.encode(), hashlib.sha256).digest()))


# --------------------------------------------------------------------------------------------------- окружение
def sh(args, **kw):
    return subprocess.run(args, capture_output=True, text=True, **kw)


def psql(db, sql):
    r = sh(G.COMPOSE + ['exec', '-T', 'postgres', 'psql', '-U', 'postgres', '-d', db, '-v', 'ON_ERROR_STOP=1', '-tA', '-c', sql])
    return r.returncode, (r.stdout + r.stderr).strip()


def compose_image(pattern):
    out = sh(G.COMPOSE + ['config', '--images']).stdout
    return next((l for l in out.split() if re.match(pattern, l)), None)


def node_image():
    mk = open(os.path.join(HERE, '..', '..', 'Makefile'), encoding='utf-8').read()
    m = re.search(r'^NODE_IMAGE\s*\?=\s*(\S+)', mk, re.M)
    return m.group(1) if m else None


def drun(network, image, *args, entrypoint=None):
    cmd = ['docker', 'run', '--rm', '--network', network, '-v', '%s:/s:ro' % os.path.abspath(G.SECRETS)]
    if entrypoint:
        cmd += ['--entrypoint', entrypoint, '-u', '0:0']
    return sh(cmd + [image] + list(args), timeout=120)


def req(method, path, token=None, headers=None, **kw):
    h = dict(headers or {})
    if token:
        h.update(G.bearer(token))
    return G.call(method, path, headers=h, **kw)


# --------------------------------------------------------------------------------------------------- проверки
def short_token(admin, kc_gateway, user):
    """Настоящий токен со сроком в несколько секунд: срок access-токена клиента dgm-web сокращается на время одного входа и возвращается."""
    r = admin.realm('/clients?clientId=dgm-web')
    if r.status != 200 or not r.json():
        return None, 'клиент dgm-web не найден: %s %s' % (r.status, r.text[:120])
    cid = r.json()[0]['id']
    full = admin.realm('/clients/%s' % cid).json()
    attrs = dict(full.get('attributes') or {})
    changed = dict(full, attributes=dict(attrs, **{'access.token.lifespan': str(EXPIRY_SECONDS)}))
    if admin.realm('/clients/%s' % cid, 'PUT', changed).status != 204:
        return None, 'срок токена клиента не изменён'
    try:
        token = G.login(kc_gateway, user)
    finally:
        restored = admin.realm('/clients/%s' % cid, 'PUT', dict(full, attributes=attrs))
        if restored.status != 204:
            print('::error title=smoke::срок access-токена клиента dgm-web не восстановлен, выполните make keycloak-reimport')
    return token, None


def operations(buyer, buyer2, people):
    print('== Операции через шлюз')
    r = req('GET', '/', headers={'Accept': 'text/html'})
    G.expect('страница /: 200 text/html от web-app', r.status == 200 and (r.header('Content-Type') or '').startswith('text/html'), '%s %s' % (r.status, r.text[:100]))
    r = req('GET', '/api/v1/products')
    etag = r.header('ETag')
    body = r.json() if r.status == 200 else {}
    G.expect('витрина GET /api/v1/products: 200, список items и ETag', r.status == 200 and isinstance(body.get('items'), list) and etag, '%s %s' % (r.status, r.text[:150]))
    r = req('GET', '/api/v1/products', headers={'If-None-Match': etag or '"none"'})
    G.expect('витрина с If-None-Match: 304 без тела', r.status == 304 and not r.body, '%s %d байт' % (r.status, len(r.body)))

    oid, sub = str(uuid.uuid4()), parts(buyer)[1]['sub']
    title = 'Дымовой тест %s' % oid[:8]
    rc, out = psql('order_db', "insert into orders.orders (id, buyer_id, seller_id, product_id, product_title, quantity, unit_price, amount, "
                   "commission_rate_bp, commission, delivery_address) values ('%s', '%s', '%s', '%s', '%s', 1, 10000, 10000, 1000, 1000, 'smoke@example.com')"
                   % (oid, sub, uuid.uuid4(), uuid.uuid4(), title))
    try:
        if G.expect('служебный заказ покупателя 1 записан в order_db (psql в контейнере postgres)', rc == 0, out):
            r = req('GET', '/api/v1/orders', buyer)
            items = r.json().get('items', []) if r.status == 200 else []
            G.expect('GET /api/v1/orders с токеном: 200, покупатель 1 видит свой служебный заказ (шлюз, mTLS, токен проверен, база)',
                     any(i.get('orderId') == oid for i in items), '%s %s' % (r.status, r.text[:200]))
            r2 = req('GET', '/api/v1/orders', buyer2)
            G.expect('U1: покупатель 2 этот заказ не видит, владелец берётся из токена', r2.status == 200 and not any(i.get('orderId') == oid for i in r2.json().get('items', [])),
                     '%s %s' % (r2.status, r2.text[:200]))
    finally:
        psql('order_db', "delete from orders.orders where id = '%s'" % oid)
    r = req('GET', '/api/v1/orders')
    G.expect('GET /api/v1/orders без токена: 401 unauthenticated', r.status == 401 and r.code() == 'unauthenticated', '%s %s' % (r.status, r.text[:120]))
    r = req('GET', '/internal/v1/products/%s' % uuid.uuid4(), buyer)
    G.expect('/internal/** снаружи закрыт и с токеном: 404', r.status == 404, '%s %s' % (r.status, r.text[:120]))


def forged(buyer):
    print('== ST-03: токены, которых Keycloak не выпускал')
    head, claims = parts(buyer)
    forge = Forger()
    cases = [
        ('алгоритм none, без подписи', G.unsigned(buyer)),
        ('подписан HS256', Forger.hs256({'typ': 'JWT'}, claims)),
        ('подписан чужим ключом RSA, издатель и kid настоящие', forge.rs256(head, claims)),
        ('подписан чужим ключом RSA, чужой издатель', forge.rs256(head, dict(claims, iss='https://evil.example/realms/dgm'))),
        ('изменена область, подпись прежняя', G.forge(buyer, {'scope': claims.get('scope', '') + ' staff.admin'})),
        ('изменён sub, подпись прежняя', G.forge(buyer, {'sub': str(uuid.uuid4())})),
    ]
    for name, token in cases:
        r = req('GET', '/api/v1/orders', token)
        G.expect('%s: 401' % name, r.status == 401 and r.code() == 'unauthenticated', '%s %s' % (r.status, r.text[:120]))


def expired_token(token, issued_at):
    if token is None:
        return
    wait = issued_at + EXPIRY_SECONDS + SKEW + 3 - time.time()
    if wait > 0:
        note('жду %d с: срок токена вышел, но допуск расхождения часов %d с ещё действует' % (wait, SKEW))
        time.sleep(wait)
    r = req('GET', '/api/v1/orders', token)
    G.expect('ST-03: настоящий токен Keycloak после истечения срока: 401', r.status == 401 and r.code() == 'unauthenticated', '%s %s' % (r.status, r.text[:120]))


def internal_calls():
    print('== mTLS внутренних вызовов (контейнер в сети app)')
    image = node_image()
    if not G.expect('образ Node для клиента найден в Makefile', image):
        return
    script = ("const https=require('https'),fs=require('fs');const [host,path,cert]=process.argv.slice(1);"
              "const o={host,port:8443,path,ca:fs.readFileSync('/s/tls_ca.crt'),servername:host,headers:{Accept:'application/json'}};"
              "if(cert!=='none'){o.cert=fs.readFileSync('/s/tls_'+cert+'.crt');o.key=fs.readFileSync('/s/tls_'+cert+'.key');}"
              "const r=https.get(o,res=>{let b='';res.on('data',d=>b+=d);res.on('end',()=>console.log(JSON.stringify({status:res.statusCode,body:b.slice(0,300)})));});"
              "r.on('error',e=>console.log(JSON.stringify({error:e.code||e.message})));r.setTimeout(10000,()=>r.destroy(new Error('timeout')));")
    path = '/internal/v1/products/%s' % uuid.uuid4()

    def call(cert):
        r = drun('dgm_app', image, 'node', '-e', script, 'catalog-service', path, cert)
        try:
            return json.loads(r.stdout.strip().splitlines()[-1])
        except (ValueError, IndexError):
            return {'error': 'нет ответа клиента: %s' % (r.stdout + r.stderr)[:200]}

    caller = 'prometheus' if SABOTAGE == 'cert' else 'order-service'
    a = call(caller)
    G.expect('order-service вызывает catalog-service: фильтр вызывающего пропускает (404 «товара нет», не 401 и не 403)', a.get('status') == 404, str(a))
    b = call('payment-service')
    G.expect('payment-service с сертификатом нашего центра, но не в списке маршрута: 403', b.get('status') == 403, str(b))
    c = call('none')
    G.expect('без клиентского сертификата рукопожатие не проходит', 'error' in c and 'status' not in c, str(c))


def outbox_to_kafka():
    print('== Outbox → Kafka')
    event, key, marker, corr = str(uuid.uuid4()), 'smoke-%s' % uuid.uuid4(), str(uuid.uuid4()), str(uuid.uuid4())
    trace = '00-%s-%s-01' % (uuid.uuid4().hex, uuid.uuid4().hex[:16])
    headers = json.dumps({'schemaversion': 1, 'traceparent': trace, 'correlationid': corr})
    sql = ("insert into public.outbox (event_id, aggregate_type, aggregate_id, event_type, topic, payload, headers) values "
           "('%s', 'smoke', '%s', 'smoke.marker', 'order.events', '{\"marker\": \"%s\"}'::jsonb, '%s'::jsonb)" % (event, key, marker, headers))
    rc, out = psql('order_db', sql)
    if not G.expect('служебная строка записана в outbox базы order_db', rc == 0, out):
        return
    published = False
    for _ in range(40):
        rc, out = psql('order_db', "select published_at is not null from public.outbox where event_id = '%s'" % event)
        if out.strip() == 't':
            published = True
            break
        time.sleep(0.5)
    G.expect('order-service публикует строку: published_at заполнен (публикатор опрашивает каждые 200 мс)', published, out)
    image = compose_image(r'^apache/kafka:')
    if not published or not G.expect('образ Kafka найден в compose.yaml', image):
        return
    inner = KAFKA_CONSUMER
    r = drun('dgm_data', image, '-c', inner, entrypoint='bash')
    line = next((l for l in r.stdout.splitlines() if marker in l), '')
    G.expect('маркер прочитан из темы order.events (потребитель delivery-service по ACL AsyncAPI, без сохранения смещений)', bool(line), (r.stdout + r.stderr)[-300:])
    if line:
        key_out, _, value = line.partition('\t')
        try:
            envelope = json.loads(value)
        except ValueError:
            envelope = {}
        G.expect('конверт CloudEvents: id события, ключ записи, тип, correlationid и traceparent из строки Outbox',
                 envelope.get('id') == event and key_out == key and envelope.get('type') == 'smoke.marker' and envelope.get('correlationid') == corr
                 and envelope.get('traceparent') == trace and envelope.get('data', {}).get('marker') == marker and envelope.get('source') == 'order-service', line[:300])


def correlation(buyer):
    print('== Сквозной идентификатор в журналах')
    for path, token, services in (('/api/v1/orders', buyer, {'api-gateway', 'order-service'}), ('/api/v1/products', None, {'api-gateway', 'catalog-service'})):
        corr = str(uuid.uuid4())
        r = req('GET', path, token, headers={'X-Correlation-Id': corr})
        tid = (r.header('traceparent') or '-----').split('-')[1]
        G.expect('%s: ответ вернул наш X-Correlation-Id и traceparent' % path, r.status == 200 and r.header('X-Correlation-Id') == corr and len(tid) == 32, '%s %s' % (r.status, r.headers))
        seen, lines = set(), []
        for _ in range(15):
            out = sh(G.COMPOSE + ['logs', '--no-color', '--since', '3m'] + sorted(services)).stdout
            lines = [l for l in out.splitlines() if corr in l]
            seen = {l.split('|', 1)[0].strip().rsplit('-', 1)[0] for l in lines if '|' in l}
            if services <= seen:
                break
            time.sleep(1)
        G.expect('%s: идентификатор виден в журналах контейнеров %s (найдены %s)' % (path, ', '.join(sorted(services)), ', '.join(sorted(seen)) or 'нигде'),
                 services <= seen, '\n'.join(l[:200] for l in lines[:3]))
        service_line = next((l for l in lines if l.split('|', 1)[0].strip().startswith(sorted(services)[1])), '')
        G.expect('%s: в строке сервиса trace id из ответа шлюза (трасса не рвётся на шлюзе)' % path, tid in service_line, service_line[:250])
        loki(corr, services)


def loki(corr, services):
    try:
        urllib.request.urlopen(LOKI + '/ready', timeout=3).read()
    except (urllib.error.URLError, OSError):
        note('Loki не запущен (набор без профиля obs): поиск в Loki пропущен')
        return
    found = set()
    for _ in range(40):
        now = int(time.time() * 1e9)
        query = urllib.parse.urlencode({'query': '{service=~".+"} |= "%s"' % corr, 'limit': 50, 'start': now - 600 * 10**9, 'end': now + 60 * 10**9})
        try:
            data = json.loads(urllib.request.urlopen('%s/loki/api/v1/query_range?%s' % (LOKI, query), timeout=5).read())
            found = {s['stream'].get('service') for s in data['data']['result']}
        except (urllib.error.URLError, OSError, ValueError, KeyError):
            pass
        if services <= found:
            break
        time.sleep(1)
    G.expect('Loki: один идентификатор находится в журналах сервисов %s (найдены %s)' % (', '.join(sorted(services)), ', '.join(sorted(map(str, found))) or 'нигде'), services <= found)


def main():
    env = dict(os.environ)
    kc, admin = K.from_env(env)
    gateway_env = {k: v for k, v in env.items() if k != 'KC_TARGET'}
    gateway_env['KC_PUBLIC'] = ISSUER.rsplit('/realms/', 1)[0]
    kc_gateway, _ = K.from_env(gateway_env)
    people = G.users()
    started = time.time()
    quick = bool(SABOTAGE)
    if quick:
        print('== Намеренная порча: SMOKE_SABOTAGE=%s, тест обязан стать красным' % SABOTAGE)

    print('== Вход')
    expiring, issued = None, 0
    if not quick and not env.get('SMOKE_SKIP_EXPIRY'):
        expiring, why = short_token(admin, kc_gateway, people['buyer-2'])
        issued = time.time()
        G.expect('токен со сроком %d с выпущен для проверки просрочки (срок клиента dgm-web возвращён)' % EXPIRY_SECONDS, expiring, why or 'вход не удался')
    buyer = G.login(kc_gateway, people['buyer-1'])
    if not G.expect('токен покупателя получен у Keycloak через шлюз (код авторизации с PKCE)', buyer, 'вход не удался'):
        return finish(started)
    claims = parts(buyer)[1]
    G.expect('токен: издатель, область orders.read и срок', claims.get('iss') == ISSUER and 'orders.read' in claims.get('scope', '').split() and claims['exp'] - claims['iat'] <= 900,
             json.dumps({k: claims.get(k) for k in ('iss', 'scope', 'exp', 'iat')}))
    buyer2 = G.login(kc, people['buyer-2'])
    if SABOTAGE == 'token':
        buyer = buyer[:-6] + ('AAAAAA' if not buyer.endswith('AAAAAA') else 'BBBBBB')
    elif SABOTAGE == 'scope':
        buyer = G.login(kc, people['seller-1']) or buyer

    operations(buyer, buyer2, people)
    internal_calls()
    if not quick:
        forged(buyer)
        outbox_to_kafka()
        correlation(buyer)
        expired_token(expiring, issued)
    return finish(started)


def finish(started):
    print()
    print('Проверок успешно: %d, с ошибками: %d, время %d с' % (G.PASSED, len(G.FAILED), time.time() - started))
    if os.environ.get('GITHUB_ACTIONS'):
        print('::notice title=smoke%s::Проверок успешно: %d, с ошибками: %d, время %d с%s' % (
            ' (порча %s)' % SABOTAGE if SABOTAGE else '', G.PASSED, len(G.FAILED), time.time() - started,
            ('; не прошли: ' + ' | '.join(G.FAILED)) if G.FAILED else ''))
    for f in G.FAILED:
        print('  - %s' % f)
    return 1 if G.FAILED else 0


if __name__ == '__main__':
    sys.exit(main())
