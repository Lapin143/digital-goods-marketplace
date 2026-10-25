#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Ориентир NFT-1.2 (TC-062): готовит продавца, товар с ключами и покупателей, запускает order.k6.js и сверяет базы (шаг 23 Ф4, решение Р-144).

    make up SET=full DEBUG=1 && make keycloak-users && make load-order

Набор full с рабочими сроками и отладочным файлом (нужны moderator-1 и порты отладки); ускоренные сроки (fast-up) не нужны: заказы держат резерв 15 минут, и 300 неоплаченных заказов за время замера не истекают. Что делает скрипт:
  1. заводит продавца, одобряет его заявку и публикует один товар с KEYS ключами (по умолчанию 400: 300 заказов плана и запас);
  2. заводит BUYERS покупателей (по умолчанию 120) с признаком phone_verified и профилем на платформе; работа идёт в WORKERS потоках, у каждого
     свой клиент Keycloak (у клиента общее хранилище cookie, поток на клиента);
  3. входит каждым покупателем заново, чтобы токены были свежими (токен доступа действует 5 минут), и пишет их в build/load/order-fixture.json (права 0600);
  4. переводит платёжную заглушку в режим hold (платёж ждёт оплаты, заказы остаются awaiting_payment) и запускает k6: RATE заказов в секунду в течение
     DURATION, порог p95 меньше 1000 мс (NFT-1.2);
  5. после замера сверяет базы: число заказов товара равно числу ответов 201, ключей столько же, сколько загружено, ни один заказ не держит
     больше одного ключа и ни один ключ не принадлежит двум заказам (INV-01, INV-05).
Параметры из окружения: BUYERS (120), KEYS (400), RATE (10), DURATION (30s), WORKERS (4), K6 (k6), LOAD_STRICT=1 (красный код выхода, если порог нарушен;
по умолчанию ориентир: предупреждение), BASE_URL (https://localhost:8443). Лимиты шлюза не меняются: заказы раздаются покупателям по кругу, трёх неоплаченных
заказов на покупателя и десяти заказов в минуту на пользователя хватает (в плане 2,5 заказа на покупателя за 30 секунд).

Раннер GitHub Actions делит процессор между k6 и двадцатью контейнерами стенда, поэтому результат это ориентир, а не зачёт: зачёт по методике
(nfr-methodology.md, раздел 3.1) снимается на сервере с трассировкой OpenTelemetry (участки «создать заказ» и «резерв»).
"""
import concurrent.futures
import contextlib
import io
import json
import os
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
sys.path.insert(0, os.path.join(HERE, '..', 'stand-checks'))
import concurrency_checks as CC  # noqa: E402
import gateway_checks as G  # noqa: E402
import kc_client as K  # noqa: E402
import orders_checks as O  # noqa: E402
import purchase_checks as P  # noqa: E402

BUYERS = int(os.environ.get('BUYERS', '120'))
KEYS = int(os.environ.get('KEYS', '400'))
RATE = int(os.environ.get('RATE', '10'))
DURATION = os.environ.get('DURATION', '30s')
WORKERS = int(os.environ.get('WORKERS', '4'))
LIMIT_MS = 1000
FIXTURE = os.path.join(REPO, 'build', 'load', 'order-fixture.json')


def seconds(text):
    return int(text[:-1]) * (60 if text.endswith('m') else 1)


def seller_product(kc, admin, people, stamp):
    """Продавец с одобренной заявкой и один опубликованный товар с KEYS ключами; идентификатор товара или None."""
    print('== Продавец и товар с %d ключами для замера' % KEYS, flush=True)
    moderator = O.SA.Staff(kc, admin, people['moderator-1'])
    seller = O.SA.applicant(kc, admin, stamp, 'load')
    if not seller:
        return None
    moderator.token()
    approved = O.SA.decide(moderator, seller['sellerId'], 'approve')
    if not G.expect('заявка продавца одобрена: 200', approved.status == 200, '%s' % approved.status):
        return None
    if not G.expect('платформа поставила продавцу роль seller', O.A.eventually(lambda: O.SA.keycloak_is_seller(admin, seller['id'], seller['sellerId']), wait=60)):
        return None
    token = O.SA.first_login_and_cabinet(kc, seller)
    if not token:
        return None
    return O.publish_product(moderator, token, 'Нагрузка заказов %d: ключ Steam' % stamp, KEYS)


def make_buyers(stamp, count):
    """Покупатели готовы к заказу: регистрация, телефон подтверждён, профиль на платформе. По клиенту Keycloak на поток."""
    def worker(indexes):
        kc, admin = K.from_env(dict(os.environ))
        return [(i, P.new_buyer(kc, admin, stamp, 'ld%d' % i)) for i in indexes]

    chunks = [list(range(w, count, WORKERS)) for w in range(WORKERS)]
    quiet = io.StringIO()   # проверки заводящих функций пишут по строке на покупателя: в журнал идут только итог и сбои
    with contextlib.redirect_stdout(quiet):
        with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as pool:
            done = [pair for part in pool.map(worker, chunks) for pair in part]
    made = [b for _, b in sorted(done, key=lambda p: p[0]) if b]
    print('Покупателей заведено %d из %d' % (len(made), count), flush=True)
    return made


def fresh_tokens(buyers):
    """Новый вход каждым покупателем: [{email, token}]. Клиент Keycloak на поток, как при заведении."""
    def worker(part):
        kc, _ = K.from_env(dict(os.environ))
        out = []
        for buyer in part:
            buyer.kc = kc
            buyer.login()
            out.append({'email': buyer.email, 'token': buyer.value})
        return out

    chunks = [buyers[w::WORKERS] for w in range(WORKERS)]
    with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as pool:
        return [item for part in pool.map(worker, chunks) for item in part]


def run_k6(env):
    k6 = os.environ.get('K6', 'k6')
    with tempfile.TemporaryDirectory() as tmp:
        export = os.path.join(tmp, 'summary.json')
        r = subprocess.run([k6, 'run', '--quiet', '--summary-export', export, os.path.join(HERE, 'order.k6.js')], env=env)
        summary = {}
        if os.path.exists(export):
            with open(export, encoding='utf-8') as f:
                summary = json.load(f).get('metrics', {})
    return r.returncode, summary


def values(metric):
    return metric.get('values', metric)


def verify(pid, created):
    """Сверка баз после замера: [(описание, выполнено, подробности)]."""
    rows = O.sql('order_db', "select status, count(*) from orders.orders where product_id = '%s' group by status" % pid)
    orders = {r[0]: int(r[1]) for r in rows} if rows is not None else None
    keys = O.keys_of(pid)
    extra, reservations = CC.duplicates(pid)
    return [
        ('заказов товара в базе заказов столько же, сколько ответов 201 (%d)' % created, orders is not None and sum(orders.values()) == created, str(orders)),
        ('ключей у товара столько же, сколько загружено (%d), ни один не потерян' % KEYS, keys is not None and sum(keys.values()) == KEYS, str(keys)),
        ('ни один заказ не держит больше одного ключа, резервов не больше заказов (INV-01, INV-05)', extra == 0 and reservations is not None and reservations <= created,
         '%s %s' % (extra, reservations)),
    ]


def main():
    kc, admin = K.from_env(dict(os.environ))
    people = G.users()
    if 'moderator-1' not in people:
        print('Нет тестового пользователя moderator-1 (make keycloak-users)', file=sys.stderr)
        return 2
    # k6 может запустить на одну итерацию больше плана (первый замер: 301 запрос при плане 300, лишний получил 409 от лимита неоплаченных заказов), поэтому запас в одну итерацию
    if RATE * seconds(DURATION) + 1 > BUYERS * 3:
        print('В плане %d заказов и одна запасная итерация, покупателей хватает на %d (три неоплаченных заказа на покупателя): увеличьте BUYERS' % (RATE * seconds(DURATION), BUYERS * 3), file=sys.stderr)
        return 2
    if RATE * seconds(DURATION) + 1 > KEYS:
        print('В плане %d заказов и одна запасная итерация, ключей %d: увеличьте KEYS' % (RATE * seconds(DURATION), KEYS), file=sys.stderr)
        return 2
    stamp = int(time.time())
    pid = seller_product(kc, admin, people, stamp)
    if not pid:
        print('ОШИБКА: продавец и товар не заведены, замер не запускался')
        return 1
    print('== Покупатели: %d, потоков %d' % (BUYERS, WORKERS), flush=True)
    started = time.time()
    buyers = make_buyers(stamp, BUYERS)
    if len(buyers) < BUYERS:
        print('ОШИБКА: заведены не все покупатели (%d из %d)' % (len(buyers), BUYERS))
        return 1
    print('Покупатели заведены за %.0f с' % (time.time() - started), flush=True)
    tokens = fresh_tokens(buyers)
    os.makedirs(os.path.dirname(FIXTURE), exist_ok=True)
    fd = os.open(FIXTURE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'w', encoding='utf-8') as f:
        json.dump({'productId': pid, 'buyers': tokens}, f)

    env = dict(os.environ, FIXTURE=FIXTURE, RATE=str(RATE), DURATION=DURATION)
    env.setdefault('SSL_CERT_FILE', os.path.join(os.environ.get('DGM_SECRETS_DIR', os.path.join(REPO, 'secrets')), 'tls_ca.crt'))
    stubs = O.SB.StubsAdmin.from_env()
    stubs.reset()
    print('== Замер NFT-1.2: %d заказов в секунду, %s, порог p95 меньше %d мс' % (RATE, DURATION, LIMIT_MS), flush=True)
    with stubs.payment_mode(scenario='hold'):
        rc, summary = run_k6(env)
    lines, missed, broken = [], [], []
    if rc not in (0, 99):
        broken.append('k6 завершился с кодом %d' % rc)
    trend = values(summary.get('create_order_duration', {}))
    created = int(values(summary.get('orders_created', {})).get('count', 0))
    refused = int(values(summary.get('orders_refused', {})).get('count', 0))
    if not trend:
        broken.append('в отчёте k6 нет метрики create_order_duration')
    else:
        p95 = trend.get('p(95)')
        lines.append('NFT-1.2 создание заказа и резерв  p95 %7.1f мс  (медиана %.1f, p99 %.1f, максимум %.1f)  порог %d мс: %s'
                     % (p95, trend.get('med', 0), trend.get('p(99)', 0), trend.get('max', 0), LIMIT_MS, 'выполнен' if p95 < LIMIT_MS else 'НЕ ВЫПОЛНЕН'))
        if p95 >= LIMIT_MS:
            missed.append('NFT-1.2 p95 %.1f мс при пороге %d' % (p95, LIMIT_MS))
    lines.append('NFT-1.2 заказов создано %d, отказов %d, запросов %d' % (created, refused, int(values(summary.get('http_reqs', {})).get('count', 0))))
    if refused or not created:
        broken.append('заказов создано %d, отказов %d: замер без отказов, иначе время ответа ничего не доказывает' % (created, refused))
    time.sleep(2)
    for title, ok, detail in verify(pid, created):
        lines.append('сверка баз: %s: %s' % (title, 'да' if ok else 'НЕТ, ' + detail))
        if not ok:
            broken.append('сверка баз: %s (%s)' % (title, detail))
    print()
    print('\n'.join(lines))
    if os.environ.get('GITHUB_ACTIONS'):
        print('::notice title=Нагрузка NFT-1.2 (ориентир на раннере)::%s' % '%0A'.join(lines))
        for m in missed:
            print('::warning title=Порог нагрузки на раннере::%s' % m)
    for b in broken:
        print('ОШИБКА: %s' % b)
    if G.FAILED:
        print('Проверки заведения: с ошибками %d' % len(G.FAILED))
        for failed in G.FAILED:
            print('  - %s' % failed)
        return 1
    if broken or (os.environ.get('LOAD_STRICT') == '1' and missed):
        return 1
    if missed:
        print('Порог нарушен, на раннере это ориентир; зачёт по методике на сервере.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
