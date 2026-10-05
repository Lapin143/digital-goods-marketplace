#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Проверка стека наблюдения на поднятом стенде (шаг 16 Ф3: NFT-6.0, NFT-6.1).

    make up SET=full-obs DEBUG=1 && make obs-check
    python3 tools/stand-checks/obs_checks.py --light      набор stubs и obs без сервисов Java (быстрая проверка самого стека, цели сервисов не ждём)

Что проверяется (всё через отладочные порты 127.0.0.1 из compose.debug.yaml):
  контейнеры   шесть контейнеров obs работают; у четырёх есть проверка готовности Docker, у Loki и Tempo (образы без оболочки) готовность
               проверяется здесь по /ready
  метрики      все цели Prometheus (семь сервисов по взаимному TLS и шесть компонентов стека) в состоянии UP, правила оповещений загружены без
               ошибок, у Alertmanager есть связь с Prometheus, хранение 15 суток
  панели       Grafana отвечает, вход только по паролю из секрета, три источника данных исправны, две панели подключены из файлов
  журналы      строка журнала веб-интерфейса с заданным идентификатором запроса находится в Loki, у потоков есть метки service и level,
               журналы сервиса Java тоже доходят (NFT-6.0)
  трассы       синтетическая трасса по OTLP/HTTP с клиентским сертификатом принимается Alloy и находится в Tempo по идентификатору;
               без клиентского сертификата и по открытому HTTP Alloy не принимает (NFT-3.3). Сервисы пока не отправляют трассы (Ф4)
  оповещения   проверочное оповещение Alertmanager приходит письмом в заглушку e-mail (маршрут «оповещение → письмо»)

Только стандартная библиотека Python. Переменные: DGM_SECRETS_DIR (каталог секретов), OBS_WAIT (предельное ожидание, секунд, по умолчанию 120).
"""
import http.client
import json
import os
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
SECRETS = os.environ.get('DGM_SECRETS_DIR', os.path.join(ROOT, 'secrets'))
WAIT = int(os.environ.get('OBS_WAIT', '120'))

PROM = os.environ.get('OBS_PROMETHEUS', 'http://127.0.0.1:9090')
AM = os.environ.get('OBS_ALERTMANAGER', 'http://127.0.0.1:19094')
GRAFANA = os.environ.get('OBS_GRAFANA', 'http://127.0.0.1:3000')
LOKI = os.environ.get('OBS_LOKI', 'http://127.0.0.1:13100')
TEMPO = os.environ.get('OBS_TEMPO', 'http://127.0.0.1:13200')
ALLOY_OTLP = os.environ.get('OBS_ALLOY_OTLP', 'https://127.0.0.1:14318')
STUBS_ADMIN = os.environ.get('STUBS_ADMIN_TARGET', 'https://127.0.0.1:18444')
GATEWAY = os.environ.get('GATEWAY_URL', 'https://127.0.0.1:8443')

OBS = ['prometheus', 'alertmanager', 'grafana', 'loki', 'tempo', 'alloy']
NO_DOCKER_HEALTH = {'loki', 'tempo'}
RULES = {'ServiceDown', 'ObsComponentDown', 'HighServerErrorRate', 'GatewayRateLimiterFailOpen', 'BackupTooOld'}
DASHBOARDS = {'dgm-services': 'Сервисы', 'dgm-gateway': 'Шлюз'}

LIGHT = '--light' in sys.argv

PASSED, FAILED = 0, []


def ok(name):
    global PASSED
    PASSED += 1
    print('  ok    %s' % name)


def bad(name, details=''):
    FAILED.append('%s%s' % (name, (' [%s]' % str(details)[:160].replace('\n', ' ')) if details else ''))
    print('  ОШИБКА %s' % name)
    if details:
        print('        | %s' % str(details)[:600].replace('\n', ' '))


def expect(name, cond, details=''):
    ok(name) if cond else bad(name, details)
    return bool(cond)


def wait_for(fn, seconds=None, step=3):
    """Повторяет fn до истинного результата. Возвращает (результат, пояснение последней неудачи)."""
    deadline = time.time() + (WAIT if seconds is None else seconds)
    last = ''
    while True:
        try:
            r = fn()
            if isinstance(r, tuple):
                r, last = r
            if r:
                return r, ''
        except Exception as e:  # noqa: BLE001 - любая ошибка сети означает «ещё не готово»
            last = '%s: %s' % (type(e).__name__, e)
        if time.time() > deadline:
            return None, last
        time.sleep(step)


# ----------------------------------------------------------------------------------------------- HTTP
def ca_context(client=False):
    ctx = ssl.create_default_context(cafile=os.path.join(SECRETS, 'tls_ca.crt'))
    if client:
        ctx.load_cert_chain(os.path.join(SECRETS, 'tls_prometheus.crt'), os.path.join(SECRETS, 'tls_prometheus.key'))
    return ctx


def http(method, url, body=None, headers=None, auth=None, client_cert=False, timeout=15):
    """Возвращает (код, текст ответа); ошибки сети поднимаются исключением, коды 4xx и 5xx возвращаются."""
    data = None
    h = dict(headers or {})
    if body is not None:
        data = body if isinstance(body, bytes) else json.dumps(body).encode('utf-8')
        h.setdefault('Content-Type', 'application/json')
    if auth:
        import base64
        h['Authorization'] = 'Basic ' + base64.b64encode(('%s:%s' % auth).encode('utf-8')).decode('ascii')
    req = urllib.request.Request(url, data=data, headers=h, method=method)
    ctx = ca_context(client_cert) if url.startswith('https://') else None
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as r:
            return r.status, r.read().decode('utf-8', 'replace')
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace')


def jget(url, **kw):
    code, text = http('GET', url, **kw)
    return code, (json.loads(text) if text.strip().startswith(('{', '[')) else text)


def secret(name):
    with open(os.path.join(SECRETS, name), encoding='utf-8') as f:
        return f.read().strip()


def compose_files():
    return ['docker', 'compose', '-f', 'compose.yaml', '-f', 'compose.debug.yaml']


# ----------------------------------------------------------------------------------------------- проверки
def check_containers():
    print('== Контейнеры')
    r = subprocess.run(compose_files() + ['--profile', '*', 'ps', '-a', '--format', 'json'], capture_output=True, text=True, cwd=ROOT)
    text = r.stdout.strip()
    rows = json.loads(text) if text.startswith('[') else [json.loads(x) for x in text.splitlines() if x.strip()]
    by = {c['Service']: c for c in rows}
    for s in OBS:
        c = by.get(s)
        if not c:
            bad('контейнер %s создан' % s, 'нет в docker compose ps (поднят ли набор full-obs?)')
            continue
        want = '' if s in NO_DOCKER_HEALTH else 'healthy'
        expect('%s: running%s' % (s, '' if not want else ', healthy'), c.get('State') == 'running' and c.get('Health', '') == want,
               '%s/%s' % (c.get('State'), c.get('Health')))
    return by


def check_ready():
    print('== Готовность без проверки Docker (Loki, Tempo)')
    for name, url in (('Loki', LOKI + '/ready'), ('Tempo', TEMPO + '/ready')):
        res, why = wait_for(lambda u=url: http('GET', u)[0] == 200)
        expect('%s: /ready отвечает 200' % name, res, why)


def check_prometheus(services):
    print('== Prometheus: цели, правила, хранение')
    expected = len(services) + len(OBS)

    def targets_up():
        code, d = jget(PROM + '/api/v1/targets?state=active')
        t = [x for x in d['data']['activeTargets'] if not LIGHT or x['labels']['job'] == 'obs']
        down = ['%s (%s)' % (x['labels'].get('service', x['scrapeUrl']), (x.get('lastError') or x['health'])[:80]) for x in t if x['health'] != 'up']
        return (not down and len(t) >= expected), ('не UP: %s; всего целей %d' % (down, len(t)))
    res, why = wait_for(targets_up)
    expect('все цели в состоянии UP: %s%d компонентов стека' % ('' if LIGHT else '%d сервисов Java (взаимный TLS) и ' % len(services), len(OBS)), res, why)

    code, d = jget(PROM + '/api/v1/targets?state=active')
    jobs = {}
    for x in d['data']['activeTargets']:
        jobs.setdefault(x['labels']['job'], set()).add(x['labels'].get('service'))
    if not LIGHT:
        expect('цели services: %s' % ', '.join(sorted(jobs.get('services', []))), jobs.get('services') == set(services), jobs.get('services'))
    expect('цели obs: %s' % ', '.join(sorted(jobs.get('obs', []))), jobs.get('obs') == set(OBS), jobs.get('obs'))

    code, d = jget(PROM + '/api/v1/rules')
    alerts = {r['name']: r for g in d['data']['groups'] for r in g['rules'] if r['type'] == 'alerting'}
    expect('правила оповещений загружены: %s' % ', '.join(sorted(alerts)), RULES <= set(alerts), sorted(RULES - set(alerts)))
    broken = [n for n, r in alerts.items() if r.get('health') != 'ok']
    expect('правила вычисляются без ошибок', not broken, [(n, alerts[n].get('lastError')) for n in broken])

    if not LIGHT:
        code, d = jget(PROM + '/api/v1/query?query=' + urllib.parse.quote('count(jvm_memory_used_bytes{area="heap"})'))
        heap = int(float(d['data']['result'][0]['value'][1])) if d['data']['result'] else 0
        expect('метрики JVM сервисов видны в Prometheus (рядов кучи: %d)' % heap, heap >= len(services), heap)

    code, d = jget(PROM + '/api/v1/alertmanagers')
    expect('Prometheus видит Alertmanager', len(d['data']['activeAlertmanagers']) == 1, d['data'])
    code, d = jget(PROM + '/api/v1/status/flags')
    expect('хранение метрик 15 суток (c4-deployment.md раздел 5)', d['data'].get('storage.tsdb.retention.time') == '15d',
           d['data'].get('storage.tsdb.retention.time'))


def check_grafana():
    print('== Grafana: вход, источники, панели')
    res, why = wait_for(lambda: jget(GRAFANA + '/api/health')[1].get('database') == 'ok')
    expect('/api/health: база данных ok', res, why)
    code, _ = http('GET', GRAFANA + '/api/datasources')
    expect('без входа источники недоступны: %s' % code, code == 401, code)
    code, _ = http('GET', GRAFANA + '/api/datasources', auth=('admin', 'неверный-пароль'))
    expect('с неверным паролем вход отклонён: %s' % code, code == 401, code)
    auth = ('admin', secret('grafana_admin'))
    code, d = jget(GRAFANA + '/api/datasources', auth=auth)
    expect('вход администратора с паролем из секрета', code == 200, code)
    names = sorted(x['name'] for x in d) if code == 200 else []
    expect('источники данных: %s' % ', '.join(names), names == ['Loki', 'Prometheus', 'Tempo'], names)
    for uid in ('prometheus', 'loki', 'tempo'):
        def health(u=uid):
            c, body = jget(GRAFANA + '/api/datasources/uid/%s/health' % u, auth=auth)
            return c == 200 and body.get('status') == 'OK', body
        res, why = wait_for(health, 60)
        expect('источник %s отвечает (Grafana → контейнер по сети obs)' % uid, res, why)
    code, found = jget(GRAFANA + '/api/search?type=dash-db', auth=auth)
    uids = {x['uid'] for x in found} if code == 200 else set()
    expect('панели подключены из файлов: %s' % ', '.join(sorted(uids)), set(DASHBOARDS) <= uids, uids)
    for uid in DASHBOARDS:
        code, d = jget(GRAFANA + '/api/dashboards/uid/' + uid, auth=auth)
        expect('панель %s: provisioned, правка в интерфейсе запрещена' % uid, code == 200 and d['meta'].get('provisioned') is True, code)


def now_ns():
    return int(time.time() * 1e9)


def loki_lines(query, minutes=30):
    qs = urllib.parse.urlencode({'query': query, 'start': now_ns() - minutes * 60 * 10**9, 'end': now_ns() + 60 * 10**9,
                                 'limit': 20, 'direction': 'backward'})
    code, d = jget(LOKI + '/loki/api/v1/query_range?' + qs)
    if code != 200:
        return []
    return [v for stream in d['data']['result'] for v in stream['values']]


def check_loki():
    print('== Журналы: Alloy → Loki (NFT-6.0)')
    if not LIGHT:
        marker = str(uuid.uuid4())
        # Веб-интерфейс пишет строку JSON с correlation_id на каждый запрос; шлюз сохраняет идентификатор, пришедший от клиента
        code, text = http('GET', GATEWAY + '/', headers={'X-Correlation-Id': marker})
        expect('запрос через шлюз к веб-интерфейсу с идентификатором %s: %s' % (marker[:8], code), code == 200, code)
        res, why = wait_for(lambda: loki_lines('{service="web-app"} |= "%s"' % marker), 90)
        expect('строка журнала веб-интерфейса с этим идентификатором найдена в Loki', res, why or 'за 90 с строки нет')
        if res:
            line = json.loads(res[0][1]) if res[0][1].startswith('{') else {}
            expect('строка в Loki: JSON с полем correlation_id', line.get('correlation_id') == marker, res[0][1][:160])
    for svc in (('external-stubs',) if LIGHT else ('order-service', 'api-gateway')):
        res, why = wait_for(lambda s=svc: loki_lines('{service="%s"}' % s), 60)
        expect('журнал %s доходит до Loki' % svc, res, why or 'нет строк за 30 минут')
    if not LIGHT:
        res, why = wait_for(lambda: loki_lines('{service="order-service", level="INFO"}'), 30)
        expect('у потоков есть метка level (разбор JSON в Alloy)', res, why or 'нет потока с level=INFO')
    code, d = jget(LOKI + '/loki/api/v1/labels')
    expect('метки в Loki: %s' % (', '.join(d['data']) if code == 200 else code), code == 200 and {'service', 'container'} <= set(d['data']), d)


def check_tempo():
    print('== Трассы: OTLP → Alloy → Tempo')
    trace_id = uuid.uuid4().hex
    span_id = uuid.uuid4().hex[:16]
    start = now_ns()
    span = {'resourceSpans': [{
        'resource': {'attributes': [{'key': 'service.name', 'value': {'stringValue': 'dgm-obs-check'}}]},
        'scopeSpans': [{'scope': {'name': 'obs-check'}, 'spans': [{
            'traceId': trace_id, 'spanId': span_id, 'name': 'synthetic-check', 'kind': 1,
            'startTimeUnixNano': str(start), 'endTimeUnixNano': str(start + 5 * 10**6),
            'attributes': [{'key': 'correlation_id', 'value': {'stringValue': trace_id}}],
        }]}],
    }]}
    code, text = http('POST', ALLOY_OTLP + '/v1/traces', body=span, client_cert=True)
    expect('Alloy принимает трассу по OTLP/HTTP с клиентским сертификатом: %s' % code, code == 200, '%s %s' % (code, text[:120]))

    def stored():
        c, body = http('GET', TEMPO + '/api/traces/' + trace_id)
        return c == 200 and 'synthetic-check' in body, 'код %s' % c
    res, why = wait_for(stored, 90)
    expect('трасса %s найдена в Tempo по идентификатору' % trace_id[:8], res, why)

    try:
        code, _ = http('POST', ALLOY_OTLP + '/v1/traces', body=span, client_cert=False)
        refused = code in (400, 401, 403)
    except (urllib.error.URLError, ssl.SSLError, ConnectionError, OSError, http.client.HTTPException):
        refused = True     # рукопожатие TLS без клиентского сертификата не завершилось
    expect('без клиентского сертификата Alloy трассу не принимает (NFT-3.3)', refused)
    try:
        code, _ = http('POST', ALLOY_OTLP.replace('https://', 'http://') + '/v1/traces', body=span)
        plain = code >= 400
    except (urllib.error.URLError, ConnectionError, OSError, http.client.HTTPException):
        plain = True
    expect('по открытому HTTP Alloy трассу не принимает (NFT-3.3)', plain)


def check_alert():
    print('== Оповещения: Alertmanager → письмо')
    name = 'DgmObsCheck%s' % uuid.uuid4().hex[:6]
    now = time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())
    end = time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime(time.time() + 300))
    code, text = http('POST', AM + '/api/v2/alerts', body=[{
        'labels': {'alertname': name, 'severity': 'test', 'service': 'obs-check'},
        'annotations': {'summary': 'Проверочное оповещение стенда (make obs-check)'},
        'startsAt': now, 'endsAt': end}])
    expect('Alertmanager принял проверочное оповещение %s: %s' % (name, code), code == 200, '%s %s' % (code, text[:120]))

    def mail():
        c, d = jget(STUBS_ADMIN + '/admin/emails?subject=' + name)
        return c == 200 and d['items'], 'код %s' % c
    res, why = wait_for(mail, 90, step=2)
    expect('письмо с оповещением пришло в заглушку e-mail (SMTP external-stubs:1025)', res, why)
    if res:
        m = res[0]
        expect('письмо от alerts@dgm.local для admin@dgm.local, тема содержит имя оповещения',
               'alerts@dgm.local' in str(m.get('from')) and 'admin@dgm.local' in str(m.get('to')) and name in m.get('subject', ''), m)


def guard(name, fn, *args):
    """Неожиданная ошибка одной группы проверок не отменяет остальные: записывается как ошибка и печатается."""
    try:
        return fn(*args)
    except Exception as e:  # noqa: BLE001
        bad('группа «%s» прервана' % name, '%s: %s' % (type(e).__name__, e))
        return None


def main():
    by = guard('контейнеры', check_containers) or {}
    services = [] if LIGHT else sorted(s for s in by if s in ('api-gateway', 'catalog-service', 'inventory-service', 'order-service',
                                                                'payment-service', 'delivery-service', 'platform-service'))
    if (not LIGHT and len(services) != 7) or any(s not in by for s in OBS):
        print('ОШИБКА: нужен набор full-obs (нет контейнеров: %s)' % [s for s in services + OBS if s not in by])
    guard('готовность Loki и Tempo', check_ready)
    guard('Prometheus', check_prometheus, services)
    guard('Grafana', check_grafana)
    guard('Loki', check_loki)
    guard('Tempo', check_tempo)
    guard('оповещения', check_alert)
    print()
    print('Проверок успешно: %d, с ошибками: %d' % (PASSED, len(FAILED)))
    if os.environ.get('GITHUB_ACTIONS'):
        print('::notice title=obs_checks::Проверок успешно: %d, с ошибками: %d%s' % (
            PASSED, len(FAILED), ('; не прошли: ' + ' | '.join(FAILED)) if FAILED else ''))
    for f in FAILED:
        print('  - %s' % f)
    return 1 if FAILED else 0


if __name__ == '__main__':
    sys.exit(main())
