#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Короткая нагрузка на сервисы для замеров памяти (шаг 18 Ф3).

    make up SET=full DEBUG=1 && make keycloak-users && make load            60 секунд, 16 соединений
    LOAD_SECONDS=30 LOAD_CONNECTIONS=8 make load

Нагрузка нужна, чтобы замер памяти (memory.py) видел не пустой стенд, а сервисы за работой: пулы соединений заполнены, кучи JVM прошли несколько сборок
мусора. Это не нагрузочный тест NFT-1.0 (покупка от каталога до выдачи, k6, Ф6). Клиент работает внутри контейнера Node в сети app, как соседний сервис
с сертификатом шлюза, напрямую к экземплярам (в обход шлюза: его лимиты частоты отвечают 429 на такой поток, а память шлюза измеряют проверки шлюза).

Запросы: витрина catalog-service (без токена) и история заказов order-service (токен покупателя, чтение из базы по владельцу). Соединения долгоживущие,
запросы идут один за другим по каждому соединению без пауз. Критерии: ответы только 200, ошибок соединения нет. Время ответа печатается для сведения (медиана,
95-й процентиль, максимум), порога на нём нет: раннер CI разделяет процессор с двадцатью контейнерами.
"""
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import gateway_checks as G  # noqa: E402
import kc_client as K  # noqa: E402

SUMMARY = []
SECONDS = int(os.environ.get('LOAD_SECONDS', '60'))
CONNECTIONS = int(os.environ.get('LOAD_CONNECTIONS', '16'))

LOAD_JS = r"""
const https = require('https'), fs = require('fs');
const [token, seconds, connections, ...targets] = process.argv.slice(1);
const ca = fs.readFileSync('/s/tls_ca.crt'), cert = fs.readFileSync('/s/tls_api-gateway.crt'), key = fs.readFileSync('/s/tls_api-gateway.key');
const stats = {};
for (const t of targets) stats[t.split('|')[0] + t.split('|')[2]] = {n: 0, statuses: {}, errors: {}, ms: []};
const agents = {};
const end = Date.now() + Number(seconds) * 1000;
function one(target) {
  const [host, service, path, bearer] = target.split('|');
  const s = stats[host + path];
  const agent = agents[host] || (agents[host] = new https.Agent({keepAlive: true, maxSockets: Number(connections)}));
  const headers = {Accept: 'application/json'};
  if (bearer === '1') headers.Authorization = 'Bearer ' + token;
  const t0 = process.hrtime.bigint();
  return new Promise(done => {
    const r = https.get({host, port: 8443, path, servername: service, ca, cert, key, headers, agent, timeout: 10000}, res => {
      res.on('data', () => {});
      res.on('end', () => { s.n++; s.statuses[res.statusCode] = (s.statuses[res.statusCode] || 0) + 1; s.ms.push(Number(process.hrtime.bigint() - t0) / 1e6); done(); });
    });
    r.on('error', e => { const k = e.code || e.message; s.errors[k] = (s.errors[k] || 0) + 1; done(); });
    r.on('timeout', () => r.destroy(new Error('timeout')));
  });
}
async function worker(target) { while (Date.now() < end) await one(target); }
(async () => {
  const jobs = [];
  for (let i = 0; i < Number(connections); i++) for (const t of targets) jobs.push(worker(t));
  await Promise.all(jobs);
  const out = {};
  for (const [k, s] of Object.entries(stats)) {
    s.ms.sort((a, b) => a - b);
    const q = p => s.ms.length ? s.ms[Math.min(s.ms.length - 1, Math.floor(s.ms.length * p))] : null;
    out[k] = {n: s.n, statuses: s.statuses, errors: s.errors, p50: q(0.5), p95: q(0.95), max: s.ms.length ? s.ms[s.ms.length - 1] : null};
  }
  console.log(JSON.stringify(out));
})();
"""


def node_image():
    mk = open(os.path.join(HERE, '..', '..', 'Makefile'), encoding='utf-8').read()
    return re.search(r'^NODE_IMAGE\s*\?=\s*(\S+)', mk, re.M).group(1)


def main():
    kc, _ = K.from_env(dict(os.environ))
    people = G.users()
    token = G.login(kc, people['buyer-1'])
    if not G.expect('токен покупателя получен', token, 'вход не удался'):
        return finish()
    print('== Нагрузка: %d с, %d соединений на каждый из двух запросов' % (SECONDS, CONNECTIONS))
    targets = ['catalog-service|catalog-service|/api/v1/products|0', 'order-service|order-service|/api/v1/orders|1']
    cmd = ['docker', 'run', '--rm', '--network', 'dgm_app', '-v', '%s:/s:ro' % os.path.abspath(G.SECRETS), node_image(), 'node', '-e', LOAD_JS,
           token, str(SECONDS), str(CONNECTIONS)] + targets
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=SECONDS + 180)
    try:
        result = json.loads(r.stdout.strip().splitlines()[-1])
    except (ValueError, IndexError):
        G.expect('нагрузка выполнена', False, (r.stdout + r.stderr)[-300:])
        return finish()
    for key, s in sorted(result.items()):
        ok_count = s['statuses'].get('200', 0)
        rps = s['n'] / float(SECONDS)
        timing = 'медиана %.0f мс, p95 %.0f мс, максимум %.0f мс' % (s['p50'], s['p95'], s['max']) if s['n'] else 'ответов нет'
        SUMMARY.append('%s: %d ответов, %.0f в секунду, %s' % (key, s['n'], rps, timing))
        G.expect('%s: %d ответов (%.0f в секунду), %s' % (key, s['n'], rps, timing),
                 s['n'] > 0 and ok_count == s['n'] and not s['errors'], 'статусы %s, ошибки %s' % (s['statuses'], s['errors']))
    return finish()


def finish():
    print()
    print('Проверок успешно: %d, с ошибками: %d' % (G.PASSED, len(G.FAILED)))
    if os.environ.get('GITHUB_ACTIONS'):
        print('::notice title=load::Проверок успешно: %d, с ошибками: %d%%0A%s' % (G.PASSED, len(G.FAILED), '%0A'.join(SUMMARY)))
    for f in G.FAILED:
        print('  - %s' % f)
    return 1 if G.FAILED else 0


if __name__ == '__main__':
    sys.exit(main())
