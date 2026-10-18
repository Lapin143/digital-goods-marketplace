#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Пул ключей сквозь стенд и канарейка ST-01 (шаг 15 Ф4; US-4.2, US-4.3, US-4.5, NFT-3.2, ADR-009, T-24, T-25).

Модуль не запускается отдельно: его вызывает seller_approval_checks.py (make seller-approval-check) с продавцом, прошедшим заявку и
вход со вторым фактором, и товаром, опубликованным модератором. Так проверка не заводит второго продавца и не повторяет долгий вход.

Что проверяется:

  пул        загрузка списком и файлом CSV через шлюз: отчёт с номерами строк и причинами (дубль в загрузке, пустая строка, дубль в пуле,
             слишком длинное значение), ошибочные запросы (не JSON, не строка, не тот тип, без токена), остаток, карточка витрины
             («в наличии», не больше 10 штук) приходит из события stock.changed через Kafka;
  канарейка  значения вида CANARY-<случайное> после этого ищутся там, где их быть не должно:
               ответы API продавца, модератора и витрины, в том числе на ошибочные запросы;
               журналы всех контейнеров стенда (docker compose logs);
               тема Kafka inventory.events целиком (три раздела, чтение без группы потребителей, смещения не сохраняются);
               дамп базы inventory_db (pg_dump с отдельными INSERT, байты в шестнадцатеричной записи).
             Значение ищется как есть, в записи hex и в Base64 (обычном и для адресов). У каждого поиска есть контрольный образец,
             который обязан найтись: идентификатор запроса в журналах, идентификатор товара и тип события в теме, идентификатор товара
             и четыре строки ключей в дампе. Без контроля пустой результат ничего не доказывает.

Допустимое место значения одно: зашифрованная колонка inventory.key.ciphertext и (в срезе 2) ответ getKeyValues. Шифртекст в дампе есть, открытого значения нет.
Тот же поиск по ответам, журналу сервиса на уровне TRACE, Outbox и каждой колонке базы без стенда делает KeyCanaryIT. Только стандартная библиотека Python.
"""
import base64
import json
import os
import subprocess
import sys
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import accounts_checks as A  # noqa: E402
import gateway_checks as G  # noqa: E402
import sellers_checks as SC  # noqa: E402

expect = G.expect
PREFIX = 'CANARY-'
SELLER_PRODUCTS = '/api/v1/seller/products'
STAFF_PRODUCTS = '/api/v1/staff/products'
STOREFRONT = '/api/v1/products'

# Клиент Kafka в контейнере образа: принципал catalog-service (читает inventory.events по ACL), разделы читаются напрямую без группы,
# поэтому группа catalog-service и её смещения не затрагиваются; смещения не сохраняются.
KAFKA_TOPIC = r"""
D=/tmp/c; mkdir -p $D
cat /s/tls_catalog-service.key /s/tls_catalog-service.crt > $D/ks.pem
printf 'security.protocol=SSL\nssl.truststore.type=PEM\nssl.truststore.location=/s/tls_ca.crt\nssl.keystore.type=PEM\nssl.keystore.location=%s\ndefault.api.timeout.ms=15000\nrequest.timeout.ms=10000\n' $D/ks.pem > $D/c.properties
export KAFKA_HEAP_OPTS=-Xmx128m LOG_DIR=/tmp/l
for p in 0 1 2; do
  /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9093 --consumer.config $D/c.properties --topic inventory.events \
    --partition $p --offset earliest --timeout-ms 8000 --consumer-property enable.auto.commit=false \
    --property print.key=true --property print.headers=true --property print.partition=true
done
"""


# --------------------------------------------------------------------------------------------------- поиск
def canary():
    return PREFIX + str(uuid.uuid4())


def needles(values):
    """Что искать: значения целиком, общий префикс и их записи в hex и в Base64."""
    result = list(values) + [PREFIX, PREFIX.encode().hex()]
    for value in values:
        raw = value.encode()
        result += [raw.hex(), base64.b64encode(raw).decode(), base64.urlsafe_b64encode(raw).decode().rstrip('=')]
    return result


def leaks(haystack, wanted):
    """Найденные строки с куском окружения (для сообщения); сами значения в итог не выводятся, они одноразовые."""
    found = []
    for needle in wanted:
        at = haystack.find(needle)
        if at >= 0:
            found.append('%s… в «%s»' % (needle[:24], haystack[max(0, at - 60):at + 60].replace('\n', ' ')))
    return found


def sh(args, **kw):
    return subprocess.run(args, capture_output=True, text=True, errors='replace', **kw)


def compose_logs():
    r = sh(G.COMPOSE + ['logs', '--no-color'], timeout=300)
    return r.stdout + r.stderr


def kafka_events():
    out = sh(G.COMPOSE + ['config', '--images']).stdout
    image = next((l for l in out.split() if l.startswith('apache/kafka:')), None)
    if not image:
        return None, 'образ Kafka не найден в compose.yaml'
    cmd = ['docker', 'run', '--rm', '--network', 'dgm_data', '-v', '%s:/s:ro' % os.path.abspath(G.SECRETS), '--entrypoint', 'bash', '-u', '0:0', image, '-c', KAFKA_TOPIC]
    r = sh(cmd, timeout=240)
    return r.stdout, (r.stderr or '')[-300:]


def catalog_version(token, product_id):
    """Версия товара в карточке продавца (совпадает с ETag и с полем version событий каталога)."""
    response = G.call('GET', '%s/%s' % (SELLER_PRODUCTS, product_id), headers=SC.auth(token))
    try:
        return response.json().get('version') if response.status == 200 else None
    except ValueError:
        return None


def copy_state(product_id):
    """Статус и версия источника у копии товара в inventory_db: (status, source_version) или None, пока копии нет."""
    r = sh(G.COMPOSE + ['exec', '-T', 'postgres', 'psql', '-U', 'postgres', '-d', 'inventory_db', '-tA', '-F', '|', '-c',
                        "select status, source_version from inventory.product_copy where product_id = '%s'" % product_id], timeout=60)
    parts = r.stdout.strip().split('|')
    if r.returncode != 0 or len(parts) != 2 or not parts[1].isdigit():
        return None
    return parts[0], int(parts[1])


def database_dump():
    r = sh(G.COMPOSE + ['exec', '-T', 'postgres', 'pg_dump', '-U', 'postgres', '-d', 'inventory_db', '--column-inserts', '--no-owner'], timeout=240)
    return r.stdout, (r.stderr or '')[-300:]


# --------------------------------------------------------------------------------------------------- запросы
def json_headers(token, **extra):
    headers = SC.auth(token, **{'Content-Type': 'application/json', 'Idempotency-Key': str(uuid.uuid4())})
    headers.update(extra)
    return headers


def upload_list(token, product_id, keys, raw=None, headers=None):
    body = raw if raw is not None else json.dumps({'keys': keys}, ensure_ascii=False)
    return G.call('POST', '%s/%s/key-batches' % (SELLER_PRODUCTS, product_id), headers=headers or json_headers(token), body=body.encode())


def upload_csv(token, product_id, text):
    headers = SC.auth(token, **{'Content-Type': 'text/csv', 'Idempotency-Key': str(uuid.uuid4())})
    return G.call('POST', '%s/%s/key-files' % (SELLER_PRODUCTS, product_id), headers=headers, body=text.encode())


def rejected(response):
    try:
        return [(i.get('line'), i.get('reason')) for i in response.json().get('rejected') or []]
    except ValueError:
        return None


def describe(response):
    return '%s %s' % (response.status, response.text[:200])


# --------------------------------------------------------------------------------------------------- проверки
def run(seller_token, moderator, product_id):
    print('== Пул ключей и канарейка ST-01 (шаг 15, US-4.2, US-4.3, US-4.5, NFT-3.2)')
    values = [canary() for _ in range(4)]
    too_long = PREFIX + 'x' * 1100
    wanted = needles(values) + [too_long]
    correlation = str(uuid.uuid4())
    seen = []   # все ответы API: их тоже ищем

    def keep(response):
        seen.append((response.status, str(response.headers), response.text))
        return response

    # Копия товара в inventory-service приходит из событий каталога и догоняет его не сразу: до первого события загрузка отвечает 404,
    # пока копия не вернулась из статуса «заблокирован» после повторной модерации (шаг 13) отвечает 409. Отказ ничего не сохраняет
    box = {}

    # Копия догоняет каталог по очереди событий (товар прошёл модерацию, блокировку и повторную модерацию): до последнего события она
    # может быть на промежуточной версии, в том числе «опубликован», и позже сменить статус. Поэтому сначала ждём версию и статус карточки
    state = {}

    def caught_up():
        state['catalog'] = catalog_version(seller_token, product_id)
        state['copy'] = copy_state(product_id)
        return state['catalog'] is not None and state['copy'] == ('published', state['catalog'])
    expect('TC-407. Версия и статус копии в inventory_db совпали с карточкой продавца (последнее событие каталога применено)',
           A.eventually(caught_up, wait=90), 'карточка: версия %s, копия: %s' % (state.get('catalog'), state.get('copy')))

    def attempt():
        box['response'] = keep(upload_list(seller_token, product_id, [values[0], values[1], values[2], values[0], '  '],
                                           headers=json_headers(seller_token, **{'X-Correlation-Id': correlation})))
        return box['response'].status not in (404, 409)
    expect('TC-407. Копия товара в inventory-service догнала каталог (загрузка не отвечает 404 и 409)', A.eventually(attempt, wait=60), describe(box['response']))
    first = box['response']
    expect('TC-401. Первая загрузка списком: принято три значения, повтор в загрузке и пустая строка названы по номерам',
           first.status == 200 and first.json().get('accepted') == 3 and rejected(first) == [(4, 'duplicate_in_upload'), (5, 'empty')], describe(first))
    listed = keep(upload_list(seller_token, product_id, [values[0], values[1], values[2], values[0], '  ']))
    body = listed.json() if listed.status == 200 else {}
    expect('TC-401. Повторная загрузка того же списка: все три значения уже в пуле, отчёт по строкам без значений',
           listed.status == 200 and body.get('accepted') == 0 and rejected(listed) == [(1, 'duplicate_in_pool'), (2, 'duplicate_in_pool'), (3, 'duplicate_in_pool'),
                                                                                   (4, 'duplicate_in_upload'), (5, 'empty')], describe(listed))
    csv = keep(upload_csv(seller_token, product_id, '%s\r\n%s\r\n%s\r\n' % (values[3], values[1], too_long)))
    expect('TC-402. Файл CSV (переводы строки Windows): принято одно значение, повтор и слишком длинная строка названы по номерам',
           csv.status == 200 and csv.json().get('accepted') == 1 and rejected(csv) == [(2, 'duplicate_in_pool'), (3, 'too_long')], describe(csv))

    broken = keep(upload_list(seller_token, product_id, None, raw='{"keys":["%s"' % values[0]))
    expect('TC-403. Тело не JSON: 400', broken.status == 400, describe(broken))
    wrong = keep(upload_list(seller_token, product_id, None, raw='{"keys":["%s", 7]}' % values[0]))
    expect('TC-403. В списке не строка: 422 с указателем /keys/1', wrong.status == 422 and [e.get('pointer') for e in wrong.json().get('errors') or []] == ['/keys/1'], describe(wrong))
    text = keep(G.call('POST', '%s/%s/key-batches' % (SELLER_PRODUCTS, product_id), headers=SC.auth(seller_token, **{'Content-Type': 'text/plain', 'Idempotency-Key': str(uuid.uuid4())}),
                       body=values[0].encode()))
    expect('TC-403. Не тот тип содержимого: 415', text.status == 415, describe(text))
    anonymous = keep(G.call('POST', '%s/%s/key-batches' % (SELLER_PRODUCTS, product_id),
                            headers={'Content-Type': 'application/json', 'Idempotency-Key': str(uuid.uuid4())}, body=json.dumps({'keys': [values[0]]}).encode()))
    expect('TC-408. Без токена: 401', anonymous.status == 401, describe(anonymous))

    stock = keep(G.call('GET', '%s/%s/stock' % (SELLER_PRODUCTS, product_id), headers=SC.auth(seller_token)))
    s = stock.json() if stock.status == 200 else {}
    expect('TC-406. Остаток: 4 в наличии, 4 в пуле, ничего не зарезервировано и не выдано, предел 100000, no-store',
           stock.status == 200 and (s.get('available'), s.get('reserved'), s.get('issued'), s.get('poolSize'), s.get('poolLimit')) == (4, 0, 0, 4, 100000)
           and 'no-store' in (stock.header('Cache-Control') or ''), describe(stock))

    box.clear()

    def card():
        box['response'] = keep(G.call('GET', '%s/%s' % (STOREFRONT, product_id)))
        return box['response'].status == 200 and box['response'].json().get('maxOrderQuantity') == 4
    expect('TC-406. Событие stock.changed дошло до каталога: карточка витрины «в наличии», можно заказать до 4 штук', A.eventually(card, wait=45), describe(box['response']))

    keep(G.call('GET', '%s/%s' % (SELLER_PRODUCTS, product_id), headers=SC.auth(seller_token)))
    keep(G.call('GET', SELLER_PRODUCTS + '?limit=100', headers=SC.auth(seller_token)))
    keep(G.call('GET', '%s/%s' % (STAFF_PRODUCTS, product_id), headers=SC.auth(moderator.token())))
    keep(G.call('GET', STAFF_PRODUCTS + '?limit=100', headers=SC.auth(moderator.token())))
    keep(G.call('GET', STOREFRONT + '?limit=100'))

    print('-- Поиск значений ключей: ответы API, журналы контейнеров, тема inventory.events, дамп базы')
    answers = ['%s %s %s' % row for row in seen]
    expect('TC-412. Контроль: ответов API %d, среди них отчёты загрузки' % len(answers), len(answers) >= 12 and any('duplicate_in_pool' in a for a in answers), str(len(answers)))
    found = [f for a in answers for f in leaks(a, wanted)]
    expect('TC-412. В ответах API продавца, модератора и витрины значений ключей нет', not found, str(found[:2]))

    logs = compose_logs()
    expect('TC-412. Контроль: поиск идёт по настоящим журналам (идентификатор запроса %s найден в журнале inventory-service)' % correlation[:8],
           any(correlation in line and 'inventory-service' in line for line in logs.splitlines()), 'журналов %d байт' % len(logs))
    found = leaks(logs, wanted)
    expect('TC-412. В журналах всех контейнеров значений ключей нет (%d МБ)' % (len(logs) // 1048576), not found, str(found[:2]))

    events, note = kafka_events()
    if events is None:
        expect('TC-412. Тема inventory.events прочитана', False, note)
    else:
        mine = [line for line in events.splitlines() if product_id in line]
        expect('TC-412. Контроль: в теме inventory.events есть события остатка этого товара', any('stock.changed' in line for line in mine), (events + note)[-300:])
        found = leaks(events, wanted)
        expect('TC-412. В теме inventory.events значений ключей нет (%d записей)' % len(events.splitlines()), not found, str(found[:2]))

    dump, note = database_dump()
    rows = [line for line in dump.splitlines() if line.startswith('INSERT INTO inventory.key ') and product_id in line]
    expect('TC-412. Контроль: в дампе inventory_db четыре строки ключей этого товара, шифртекст есть', len(rows) == 4 and all('ciphertext' in line for line in rows), '%d строк; %s' % (len(rows), note))
    found = leaks(dump, wanted)
    expect('TC-412. В дампе inventory_db значений ключей нет (%d КБ)' % (len(dump) // 1024), not found, str(found[:2]))
