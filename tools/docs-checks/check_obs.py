# -*- coding: utf-8 -*-
"""Сверка конфигурации стека наблюдения (infra/obs) с compose.yaml и документами. Docker не нужен.

    python3 tools/docs-checks/check_obs.py

Что проверяется:
  Prometheus     цели services равны сервисам Java в сети obs (порт 8444, схема https, сертификат tls_prometheus из секретов контейнера),
                 цели obs равны контейнерам стека, хранение 15 суток, правила подключены из смонтированного каталога
  правила        у каждого оповещения severity, summary и description; каждое оповещение покрыто модульным тестом promtool
  Alertmanager   SMTP идёт на external-stubs:1025, все приёмники маршрутов объявлены, есть быстрый маршрут для проверочных оповещений
  Loki, Tempo    хранение 7 и 3 суток, порты и адреса согласованы с Alloy и Grafana
  Alloy          сокет Docker только для чтения, фильтр проекта dgm, Loki и Tempo по именам контейнеров, серверный сертификат OTLP
  Grafana        источники по адресам контейнеров, один источник по умолчанию, панели: JSON читается, номера и uid уникальны, источники
                 существуют; пароль и ключ только из файлов секретов, анонимный вход выключен
  память         GOMEMLIMIT процессов Go составляет от 70 до 90% лимита контейнера
Нужен PyYAML. DGM_COMPOSE_FILE и DGM_OBS_DIR подменяют проверяемые файлы (нужно самопроверке контроля).
"""
import glob
import json
import os
import re
import sys

import yaml

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import docslib as L

COMPOSE = os.environ.get('DGM_COMPOSE_FILE') or os.path.join(L.REPO, 'compose.yaml')
OBS_DIR = os.environ.get('DGM_OBS_DIR') or os.path.join(L.REPO, 'infra', 'obs')
DEP = os.path.join(L.ARCH, 'c4-deployment.md')

JAVA_SERVICES = ['api-gateway', 'catalog-service', 'inventory-service', 'order-service', 'payment-service', 'delivery-service',
                 'platform-service']
OBS_PORTS = {'prometheus': 9090, 'alertmanager': 9093, 'grafana': 3000, 'loki': 3100, 'tempo': 3200, 'alloy': 12345}
DATASOURCES = {'prometheus': ('Prometheus', 'prometheus', 9090), 'loki': ('Loki', 'loki', 3100), 'tempo': ('Tempo', 'tempo', 3200)}
EXPECTED_DASHBOARDS = {'dgm-services', 'dgm-gateway'}
EXPECTED_ALERTS = {'ServiceDown', 'ObsComponentDown', 'HighServerErrorRate', 'GatewayRateLimiterFailOpen', 'BackupTooOld'}


def load(path):
    with open(path, encoding='utf-8') as f:
        return yaml.safe_load(f)


def env_of(service):
    env = service.get('environment') or {}
    if isinstance(env, list):
        env = dict(e.split('=', 1) if '=' in e else (e, '') for e in env)
    return {k: str(v) for k, v in env.items()}


def mem_mib(value):
    m = re.fullmatch(r'(\d+)(m|MiB)', str(value))
    return int(m.group(1)) if m else None


def main():
    rep = L.Report('obs')
    dc = load(COMPOSE)
    services = dc['services']
    for f in ('prometheus/prometheus.yml', 'prometheus/rules/dgm.yml', 'tests/rules_test.yml', 'alertmanager/alertmanager.yml', 'loki/loki.yaml',
              'tempo/tempo.yaml', 'alloy/config.alloy', 'grafana/provisioning/datasources/datasources.yaml',
              'grafana/provisioning/dashboards/dashboards.yaml'):
        if not os.path.exists(os.path.join(OBS_DIR, f)):
            rep.err('infra/obs', 'нет файла %s' % f)
    if rep.problems:
        return rep.finish()
    p = lambda *a: os.path.join(OBS_DIR, *a)   # noqa: E731

    # --- состав стека в compose.yaml
    for name in OBS_PORTS:
        if name not in services:
            rep.err('compose.yaml', 'нет контейнера %s профиля obs' % name)
    if rep.problems:
        return rep.finish()
    in_obs = sorted(n for n in JAVA_SERVICES if 'obs' in (services.get(n, {}).get('networks') or []))
    if in_obs != sorted(JAVA_SERVICES):
        rep.err('compose.yaml', 'в сети obs должны быть все сервисы Java, сейчас %s' % in_obs)

    # --- Prometheus
    prom = load(p('prometheus', 'prometheus.yml'))
    jobs = {j['job_name']: j for j in prom['scrape_configs']}
    svc_job = jobs.get('services')
    if not svc_job:
        rep.err('prometheus.yml', 'нет задания services')
    else:
        targets = sorted(t for sc in svc_job['static_configs'] for t in sc['targets'])
        want = sorted('%s:8444' % n for n in JAVA_SERVICES)
        if targets != want:
            rep.err('prometheus.yml, services', 'цели %s, а сервисы Java в сети obs дают %s' % (targets, want))
        if svc_job.get('scheme') != 'https' or svc_job.get('metrics_path') != '/actuator/prometheus':
            rep.err('prometheus.yml, services', 'метрики сервисов отдаются по https на /actuator/prometheus (порт управления, mTLS)')
        tls = svc_job.get('tls_config') or {}
        for key, name in (('ca_file', 'tls_ca.crt'), ('cert_file', 'tls_prometheus.crt'), ('key_file', 'tls_prometheus.key')):
            if tls.get(key) != '/run/secrets/' + name:
                rep.err('prometheus.yml, services', 'tls_config.%s = %s, нужен /run/secrets/%s' % (key, tls.get(key), name))
    obs_job = jobs.get('obs')
    if not obs_job:
        rep.err('prometheus.yml', 'нет задания obs')
    else:
        targets = sorted(t for sc in obs_job['static_configs'] for t in sc['targets'])
        want = sorted('%s:%d' % (n, port) for n, port in OBS_PORTS.items())
        if targets != want:
            rep.err('prometheus.yml, obs', 'цели %s, контейнеры стека дают %s' % (targets, want))
    am_targets = [t for a in prom.get('alerting', {}).get('alertmanagers', []) for sc in a['static_configs'] for t in sc['targets']]
    if am_targets != ['alertmanager:9093']:
        rep.err('prometheus.yml', 'Alertmanager по адресу %s, нужен alertmanager:9093' % am_targets)
    cp = services['prometheus']
    cmd = ' '.join(str(x) for x in cp.get('command') or [])
    if '--storage.tsdb.retention.time=15d' not in cmd:
        rep.err('compose.yaml, prometheus', 'хранение метрик должно быть 15 суток (c4-deployment.md раздел 5)')
    if '--config.file=/etc/prometheus/prometheus.yml' not in cmd:
        rep.err('compose.yaml, prometheus', 'не задан файл конфигурации /etc/prometheus/prometheus.yml')
    mounts = ' '.join(str(v) for v in cp.get('volumes') or [])
    if './infra/obs/prometheus:/etc/prometheus:ro' not in mounts:
        rep.err('compose.yaml, prometheus', 'каталог infra/obs/prometheus должен быть смонтирован в /etc/prometheus только для чтения')
    for rf in prom.get('rule_files', []):
        if not rf.startswith('/etc/prometheus/rules/'):
            rep.err('prometheus.yml', 'rule_files %s вне смонтированного каталога правил' % rf)

    # --- правила и их тесты
    rules = load(p('prometheus', 'rules', 'dgm.yml'))
    alerts = {}
    for g in rules['groups']:
        for r in g['rules']:
            if 'alert' in r:
                alerts[r['alert']] = r
    if set(alerts) != EXPECTED_ALERTS:
        rep.err('rules/dgm.yml', 'оповещения %s, ожидалось %s' % (sorted(alerts), sorted(EXPECTED_ALERTS)))
    for name, r in alerts.items():
        if (r.get('labels') or {}).get('severity') not in ('critical', 'warning'):
            rep.err('rules/dgm.yml, %s' % name, 'нужна метка severity: critical или warning')
        for ann in ('summary', 'description'):
            if not (r.get('annotations') or {}).get(ann):
                rep.err('rules/dgm.yml, %s' % name, 'нет аннотации %s' % ann)
    tests = load(p('tests', 'rules_test.yml'))
    tested = {a['alertname'] for t in tests['tests'] for a in t.get('alert_rule_test', [])}
    for name in sorted(set(alerts) - tested):
        rep.err('tests/rules_test.yml', 'оповещение %s не покрыто модульным тестом (проверка, которая ни разу не сработала, ничего не гарантирует)' % name)
    fires = {a['alertname'] for t in tests['tests'] for a in t.get('alert_rule_test', []) if a.get('exp_alerts')}
    for name in sorted(set(alerts) - fires):
        rep.err('tests/rules_test.yml', 'у оповещения %s нет случая, где оно срабатывает' % name)

    # --- Alertmanager
    am = load(p('alertmanager', 'alertmanager.yml'))
    host, _, port = am['global']['smtp_smarthost'].partition(':')
    if host not in services or host != 'external-stubs' or port != '1025':
        rep.err('alertmanager.yml', 'smtp_smarthost %s: на стенде письма идут в external-stubs:1025' % am['global']['smtp_smarthost'])
    if 'app' not in (services['alertmanager'].get('networks') or []):
        rep.err('compose.yaml, alertmanager', 'для отправки писем нужна сеть app')
    receivers = {r['name'] for r in am['receivers']}
    used = {am['route']['receiver']} | {r['receiver'] for r in am['route'].get('routes', [])}
    if not used <= receivers:
        rep.err('alertmanager.yml', 'приёмники %s не объявлены' % sorted(used - receivers))
    if not any('severity = "test"' in ' '.join(r.get('matchers', [])) and r.get('group_wait') == '1s' for r in am['route'].get('routes', [])):
        rep.err('alertmanager.yml', 'нет быстрого маршрута для проверочных оповещений (severity = "test", group_wait 1s): make obs-check ждал бы минуты')

    # --- Loki и Tempo
    loki = load(p('loki', 'loki.yaml'))
    if loki['limits_config'].get('retention_period') != '168h' or not loki['compactor'].get('retention_enabled'):
        rep.err('loki.yaml', 'хранение журналов 7 суток: retention_period 168h и compactor.retention_enabled (c4-deployment.md раздел 5)')
    if loki['server']['http_listen_port'] != 3100:
        rep.err('loki.yaml', 'порт Loki 3100')
    if loki['schema_config']['configs'][-1].get('schema') != 'v13':
        rep.err('loki.yaml', 'схема хранилища должна быть v13 (метаданные потоков)')
    if not loki['common']['path_prefix'].startswith('/loki'):
        rep.err('loki.yaml', 'данные Loki должны лежать в /loki (том lokidata)')
    if 'lokidata:/loki' not in [str(v) for v in services['loki'].get('volumes') or []]:
        rep.err('compose.yaml, loki', 'том lokidata должен быть смонтирован в /loki')
    tempo = load(p('tempo', 'tempo.yaml'))
    if tempo['compactor']['compaction'].get('block_retention') != '72h':
        rep.err('tempo.yaml', 'хранение трасс 3 суток: block_retention 72h (c4-deployment.md раздел 5)')
    if tempo['server']['http_listen_port'] != 3200 or tempo['distributor']['receivers']['otlp']['protocols']['grpc']['endpoint'] != '0.0.0.0:4317':
        rep.err('tempo.yaml', 'порт запросов 3200 и приём OTLP gRPC на 0.0.0.0:4317')
    if tempo['storage']['trace']['wal']['path'] != '/var/tempo/wal' or 'tempodata:/var/tempo' not in [str(v) for v in services['tempo'].get('volumes') or []]:
        rep.err('tempo.yaml', 'данные Tempo должны лежать в /var/tempo (том tempodata)')
    dep = L.read(DEP)
    for needle in ('15 суток', '7 суток', '3 суток'):
        if needle not in dep:
            rep.err('c4-deployment.md', 'нет срока хранения «%s»' % needle)

    # --- Alloy
    alloy = L.read(p('alloy', 'config.alloy'))
    for needle, why in (('http://loki:3100/loki/api/v1/push', 'журналы идут в Loki по имени контейнера и порту из loki.yaml'),
                        ('endpoint = "tempo:4317"', 'трассы идут в Tempo на порт приёма из tempo.yaml'),
                        ('com.docker.compose.project=dgm', 'фильтр: только контейнеры проекта dgm'),
                        ('/run/secrets/tls_alloy.crt', 'приём OTLP по TLS с сертификатом alloy'),
                        ('/run/secrets/tls_alloy.key', 'ключ сертификата alloy'),
                        ('client_ca_file = "/run/secrets/tls_ca.crt"', 'клиенты OTLP проверяются нашим центром (взаимный TLS, NFT-3.3)')):
        if needle not in alloy:
            rep.err('config.alloy', 'нет «%s»: %s' % (needle, why))
    if 'insecure = true' in alloy and alloy.count('insecure = true') != 1:
        rep.err('config.alloy', 'TLS отключён больше чем в одном месте: допустимо только соединение с Tempo внутри сети obs')
    ca = services['alloy']
    if '/var/run/docker.sock:/var/run/docker.sock:ro' not in [str(v) for v in ca.get('volumes') or []]:
        rep.err('compose.yaml, alloy', 'сокет Docker подключается только как /var/run/docker.sock:/var/run/docker.sock:ro')
    if ca.get('group_add') != ['${DOCKER_GID:-0}']:
        rep.err('compose.yaml, alloy', 'доступ к сокету по группе: group_add: ["${DOCKER_GID:-0}"], а не запуск от root')

    # --- Grafana
    graf = services['grafana']
    genv = env_of(graf)
    for key, name in (('GF_SECURITY_ADMIN_PASSWORD__FILE', 'grafana_admin'), ('GF_SECURITY_SECRET_KEY__FILE', 'grafana_secret_key')):
        if genv.get(key) != '/run/secrets/' + name:
            rep.err('compose.yaml, grafana', '%s должна указывать на /run/secrets/%s' % (key, name))
    for k in genv:
        if re.search(r'(PASSWORD|SECRET|TOKEN|KEY)', k) and not k.endswith('_FILE'):
            rep.err('compose.yaml, grafana', 'переменная %s: секрет только файлом (NFT-3.2)' % k)
    if genv.get('GF_AUTH_ANONYMOUS_ENABLED') != 'false' or genv.get('GF_USERS_ALLOW_SIGN_UP') != 'false':
        rep.err('compose.yaml, grafana', 'анонимный вход и самостоятельная регистрация выключены')
    gm = [str(v) for v in graf.get('volumes') or []]
    if './infra/obs/grafana/provisioning:/etc/grafana/provisioning:ro' not in gm or './infra/obs/grafana/dashboards:/etc/dgm/grafana/dashboards:ro' not in gm:
        rep.err('compose.yaml, grafana', 'каталоги provisioning и dashboards подключаются только для чтения')
    ds = load(p('grafana', 'provisioning', 'datasources', 'datasources.yaml'))['datasources']
    seen = {}
    for d in ds:
        seen[d['uid']] = d
        want = [v for v in DATASOURCES.values() if v[1] == d['uid']]
        if not want:
            rep.err('datasources.yaml', 'неизвестный источник %s' % d['uid'])
            continue
        name, uid, port = want[0]
        if d['name'] != name or d['url'] != 'http://%s:%d' % (uid, port):
            rep.err('datasources.yaml', '%s: адрес %s, ожидался http://%s:%d' % (uid, d['url'], uid, port))
        if d.get('editable') is not False:
            rep.err('datasources.yaml', '%s: источник правится только через репозиторий (editable: false)' % uid)
    if set(seen) != set(DATASOURCES):
        rep.err('datasources.yaml', 'источники %s, нужны %s' % (sorted(seen), sorted(DATASOURCES)))
    if sum(1 for d in ds if d.get('isDefault')) != 1:
        rep.err('datasources.yaml', 'источник по умолчанию должен быть ровно один')
    prov = load(p('grafana', 'provisioning', 'dashboards', 'dashboards.yaml'))['providers'][0]
    if prov['options']['path'] != '/etc/dgm/grafana/dashboards' or prov.get('allowUiUpdates') is not False:
        rep.err('dashboards.yaml', 'путь /etc/dgm/grafana/dashboards и allowUiUpdates: false')
    uids, ids_total = set(), 0
    for path in sorted(glob.glob(p('grafana', 'dashboards', '*.json'))):
        w = 'dashboards/%s' % os.path.basename(path)
        try:
            d = json.load(open(path, encoding='utf-8'))
        except ValueError as e:
            rep.err(w, 'не JSON: %s' % e)
            continue
        if d['uid'] in uids:
            rep.err(w, 'uid %s уже есть в другой панели' % d['uid'])
        uids.add(d['uid'])
        ids = [x['id'] for x in d['panels']]
        if len(ids) != len(set(ids)):
            rep.err(w, 'номера панелей повторяются')
        ids_total += len(ids)
        for panel in d['panels']:
            pds = (panel.get('datasource') or {}).get('uid')
            if pds not in DATASOURCES:
                rep.err(w, 'панель «%s»: источник %s не объявлен в datasources.yaml' % (panel.get('title'), pds))
            for t in panel.get('targets', []):
                if not t.get('expr'):
                    rep.err(w, 'панель «%s»: пустой запрос' % panel.get('title'))
                if (t.get('datasource') or {}).get('uid') != pds:
                    rep.err(w, 'панель «%s»: источник запроса не совпадает с источником панели' % panel.get('title'))
        if d.get('editable') is not False:
            rep.err(w, 'панель правится только через репозиторий (editable: false)')
    if uids != EXPECTED_DASHBOARDS:
        rep.err('dashboards', 'панели %s, по шагу 16 нужны %s' % (sorted(uids), sorted(EXPECTED_DASHBOARDS)))

    # --- память Go-процессов
    for name in ('prometheus', 'alertmanager', 'grafana', 'loki', 'tempo', 'alloy'):
        s = services[name]
        lim, go = mem_mib(s.get('mem_limit')), mem_mib(env_of(s).get('GOMEMLIMIT'))
        if not go:
            rep.err('compose.yaml, %s' % name, 'нет GOMEMLIMIT (MiB): без него сборщик мусора Go не знает о лимите контейнера')
        elif lim and not (0.7 * lim <= go <= 0.9 * lim):
            rep.err('compose.yaml, %s' % name, 'GOMEMLIMIT %d МиБ при лимите %d МБ: нужно от 70 до 90%%' % (go, lim))

    rep.fact('infra/obs: %d оповещений (все с модульными тестами), %d панелей в %d файлах, %d источников, цели: %d сервисов Java и %d компонентов стека'
             % (len(alerts), ids_total, len(uids), len(ds), len(JAVA_SERVICES), len(OBS_PORTS)))
    return rep.finish()


if __name__ == '__main__':
    sys.exit(main())
