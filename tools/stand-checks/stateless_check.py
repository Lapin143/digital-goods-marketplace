#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""NFT-1.3: сервисы без состояния сессии, экземпляры равнозначны (шаг 17 Ф3).

    make up SET=dev-auth DEBUG=1 && make up SET=dev-purchase DEBUG=1 && make keycloak-users && make stateless-check

Порог NFT-1.3 (nfr-methodology.md): сценарий выполняется при двух экземплярах сервиса, один перезапускается посреди серии запросов. Полный
сценарий покупки появится в Ф4 (TC-063); в Ф3 ходячий скелет это история заказов покупателя с токеном (order-service) и витрина (catalog-service).

Как проверяется. order-service и catalog-service поднимаются по два экземпляра (docker compose --scale). Контейнер в сети app два с половиной
раза в секунду опрашивает каждый экземпляр order-service напрямую, одним и тем же токеном, выданным до начала серии, с сертификатом шлюза.
Через 15 секунд второй экземпляр перезапускается. Критерии:

  - первый экземпляр отвечает 200 на всех запросах серии, в том числе пока второй перезапускается (экземпляры не зависят друг от друга);
  - второй экземпляр до перезапуска отвечает 200, во время перезапуска недоступен, после него снова 200 тем же токеном: никакого состояния,
    которое пришлось бы восстанавливать (сессия, вход, кэш пользователя), у сервиса нет;
  - тела ответов обоих экземпляров совпадают, заголовка Set-Cookie нет ни в одном ответе;
  - то же для двух экземпляров catalog-service без перезапуска: тела совпадают, куки нет.

Статическая часть NFT-1.3 это правила ArchUnit noHttpSessions и noMutableStaticFields (ServiceArchRules), они выполняются в модульных тестах.
В конце экземпляры возвращаются к одному.
"""
import json
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import gateway_checks as G  # noqa: E402
import kc_client as K  # noqa: E402

SERIES_SECONDS = int(os.environ.get('STATELESS_SECONDS', '100'))
RESTART_AT = 15
STEP_MS = 400

SERIES_JS = r"""
const https = require('https'), fs = require('fs');
const [token, seconds, step, ...targets] = process.argv.slice(1);
const ca = fs.readFileSync('/s/tls_ca.crt'), cert = fs.readFileSync('/s/tls_api-gateway.crt'), key = fs.readFileSync('/s/tls_api-gateway.key');
const started = Date.now(), log = [];
function one(target) {
  const [host, service, path, bearer] = target.split('|');
  return new Promise(done => {
    const headers = {Accept: 'application/json'};
    if (bearer === '1') headers.Authorization = 'Bearer ' + token;
    const t = (Date.now() - started) / 1000;
    const r = https.get({host, port: 8443, path, servername: service, ca, cert, key, headers, timeout: 3000}, res => {
      let b = ''; res.on('data', d => b += d);
      res.on('end', () => { log.push({t, host, status: res.statusCode, cookie: !!res.headers['set-cookie'], body: b.length < 20000 ? b : b.slice(0, 20000)}); done(); });
    });
    r.on('error', e => { log.push({t, host, error: e.code || e.message}); done(); });
    r.on('timeout', () => r.destroy(new Error('timeout')));
  });
}
(async () => {
  while (Date.now() - started < Number(seconds) * 1000) {
    await Promise.all(targets.map(one));
    await new Promise(r => setTimeout(r, Number(step)));
  }
  console.log(JSON.stringify({started, log}));
})();
"""


def sh(args, **kw):
    return subprocess.run(args, capture_output=True, text=True, **kw)


def node_image():
    mk = open(os.path.join(HERE, '..', '..', 'Makefile'), encoding='utf-8').read()
    return re.search(r'^NODE_IMAGE\s*\?=\s*(\S+)', mk, re.M).group(1)


def scale(order, catalog):
    return sh(G.COMPOSE + ['up', '-d', '--no-deps', '--no-recreate', '--scale', 'order-service=%d' % order, '--scale', 'catalog-service=%d' % catalog,
                          'order-service', 'catalog-service'], timeout=300)


def healthy(container):
    return sh(['docker', 'inspect', '-f', '{{.State.Health.Status}}', container]).stdout.strip() == 'healthy'


def wait_healthy(containers, seconds=180):
    deadline = time.time() + seconds
    while time.time() < deadline:
        if all(healthy(c) for c in containers):
            return True
        time.sleep(3)
    return False


def main():
    kc, _ = K.from_env(dict(os.environ))
    people = G.users()
    token = G.login(kc, people['buyer-1'])
    if not G.expect('токен покупателя получен до начала серии', token, 'вход не удался'):
        return finish()
    orders = ['dgm-order-service-1', 'dgm-order-service-2']
    catalogs = ['dgm-catalog-service-1', 'dgm-catalog-service-2']
    try:
        print('== Два экземпляра order-service и catalog-service')
        r = scale(2, 2)
        G.expect('docker compose --scale: по два экземпляра', r.returncode == 0, r.stderr[-300:])
        G.expect('все четыре экземпляра здоровы', wait_healthy(orders + catalogs), 'не стали healthy за 180 с')

        print('== Серия запросов к экземплярам напрямую, второй order-service перезапускается на %d-й секунде' % RESTART_AT)
        targets = ['%s|order-service|/api/v1/orders|1' % c for c in orders] + ['%s|catalog-service|/api/v1/products|0' % c for c in catalogs]
        cmd = ['docker', 'run', '--rm', '--network', 'dgm_app', '-v', '%s:/s:ro' % os.path.abspath(G.SECRETS), node_image(), 'node', '-e', SERIES_JS,
               token, str(SERIES_SECONDS), str(STEP_MS)] + targets
        series = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        time.sleep(RESTART_AT)
        restarted = time.time()
        r = sh(['docker', 'restart', '-t', '5', orders[1]], timeout=120)
        G.expect('второй экземпляр order-service перезапущен посреди серии', r.returncode == 0, r.stderr[-200:])
        out, err = series.communicate(timeout=SERIES_SECONDS + 120)
        try:
            result = json.loads(out.strip().splitlines()[-1])
            log, restart_t = result['log'], restarted - result['started'] / 1000.0
        except (ValueError, IndexError, KeyError):
            G.expect('серия выполнена', False, (out + err)[-300:])
            return finish()
        evaluate(log, orders, catalogs, restart_t)
    finally:
        r = scale(1, 1)
        if r.returncode != 0:
            print('::warning title=stateless_check::не удалось вернуть по одному экземпляру: %s' % r.stderr[-200:])
    return finish()


def evaluate(log, orders, catalogs, restart_t):
    """restart_t: когда команда перезапуска была выдана, в секундах от начала серии (часы клиента серии и этого скрипта общие, это один раннер)."""
    print('== Результаты серии: %d запросов, перезапуск на %.1f-й секунде' % (len(log), restart_t))
    by = {h: [e for e in log if e['host'] == h] for h in orders + catalogs}
    first, second = by[orders[0]], by[orders[1]]
    bad1 = [e for e in first if e.get('status') != 200]
    G.expect('экземпляр 1 order-service: %d ответов, все 200, в том числе пока экземпляр 2 перезапускается' % len(first), first and not bad1, str(bad1[:2]))
    margin = 1.0  # контейнер перестаёт принимать соединения сразу после команды, запрос чуть раньше команды ещё успевает пройти
    before = [e for e in second if e['t'] < restart_t - margin]
    G.expect('экземпляр 2 до перезапуска: %d ответов, все 200' % len(before), before and all(e.get('status') == 200 for e in before), str([e for e in before if e.get('status') != 200][:2]))
    down = [e for e in second if restart_t - margin <= e['t'] and e.get('status') != 200]
    G.expect('экземпляр 2 во время перезапуска недоступен (%d неудачных попыток): перезапуск действительно был' % len(down), len(down) > 0)
    first_down = min((e['t'] for e in down), default=restart_t)
    recovered = [e for e in second if e.get('status') == 200 and e['t'] > first_down]
    back_at = min((e['t'] for e in recovered), default=None)
    G.expect('экземпляр 2 снова отвечает 200 тем же токеном, выданным до перезапуска (вернулся на %s-й секунде)' % ('%.0f' % back_at if back_at else '?'), back_at is not None)
    tail = [e for e in second if back_at is not None and e['t'] >= back_at]
    G.expect('после возвращения экземпляр 2 больше не ошибается (%d ответов)' % len(tail), tail and all(e.get('status') == 200 for e in tail), str([e for e in tail if e.get('status') != 200][:2]))
    bodies = {e['body'] for h in orders for e in by[h] if e.get('status') == 200}
    G.expect('ответы двух экземпляров order-service одинаковы (%d вариант тела)' % len(bodies), len(bodies) == 1, str(list(bodies)[:2]))
    cat_bodies = {e['body'] for h in catalogs for e in by[h] if e.get('status') == 200}
    G.expect('ответы двух экземпляров catalog-service одинаковы, ошибок нет', len(cat_bodies) == 1 and all(e.get('status') == 200 for h in catalogs for e in by[h]),
             str([e for h in catalogs for e in by[h] if e.get('status') != 200][:2]))
    G.expect('Set-Cookie нет ни в одном ответе (сессии нет)', not any(e.get('cookie') for e in log))


def finish():
    print()
    print('Проверок успешно: %d, с ошибками: %d' % (G.PASSED, len(G.FAILED)))
    if os.environ.get('GITHUB_ACTIONS'):
        print('::notice title=stateless_check NFT-1.3::Проверок успешно: %d, с ошибками: %d%s' % (
            G.PASSED, len(G.FAILED), ('; не прошли: ' + ' | '.join(G.FAILED)) if G.FAILED else ''))
    for f in G.FAILED:
        print('  - %s' % f)
    return 1 if G.FAILED else 0


if __name__ == '__main__':
    sys.exit(main())
