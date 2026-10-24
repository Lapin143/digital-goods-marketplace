#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Очередь недоставленных сообщений сквозь стенд (шаг 16 Ф4; TC-012, ADR-006, ADR-011, runbook «Событие в .dlq»).

Запуск: `make dlq-check` (набор full, DEBUG=1; нужны Docker и Python, Keycloak и пользователи не нужны). Проверка работает с темой
`delivery.events` и платформенным сервисом: события пишет суперпользователь стенда kafka-init (сервис-издатель от его имени не нужен),
очередь читает и возвращает настоящий `tools/ops/dlq_replay.py`, то есть проверяется тот путь, по которому пойдёт оператор.

Что проверяется:

  ядовитое   запись, которую не разобрать (не конверт события), уходит в `delivery.events.dlq` сразу: одна попытка, класс ошибки
             InvalidEventException, значение записи не изменено, обработчик назван именем сервиса;
  временное  `delivery.failed` раньше модели заказа в поддержке (F3-21): потребитель повторяет 1, 5 и 25 секунд, затем запись уходит
             в `.dlq` с четырьмя попытками, RetryableEventException, обработчиком support-event-handler, заголовками исходной записи;
             обращения нет, письмо администратору по тому же событию ушло с первого раза (его обработчик отказа не получил);
  поток      после отказа потребитель не стоит: событие `order.issued` создаёт модель заказа;
  скрипт     `dlq_replay.py list` и `show` показывают запись (обработчик, класс ошибки, идентификатор), значения `data` не показывают;
             `replay` без `--yes` ничего не отправляет;
  возврат    `replay --yes` после того, как заказ появился: ровно одно обращение системы, второго письма администратору нет;
             второй возврат той же записи ничего не меняет (дубль по `processed_event`), в `.dlq` второй записи нет.

У каждого ожидания есть срок, поэтому зависший потребитель даёт красный результат, а не бесконечное ожидание.
"""
import json
import os
import subprocess
import sys
import time
import uuid
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(REPO, 'tools', 'ops'))
import dlq_replay as D  # noqa: E402
import gateway_checks as G  # noqa: E402

expect = G.expect
TOPIC = 'delivery.events'
DLQ = 'delivery.events.dlq'
HANDLER = 'support-event-handler'
POISON_VALUE = '{"hello":"dlq-check-%s"}'
# Повторы потребителя занимают 1 + 5 + 25 секунд; запас на очередь потока (он один на все темы сервиса) и на медленный стенд
RETRY_SECONDS = 31
WAIT_DLQ = 240
WAIT_DB = 90


def sh(args, **kw):
    return subprocess.run(args, capture_output=True, text=True, errors='replace', cwd=REPO, **kw)


def sql(database, query):
    """Строки результата (psql в контейнере postgres, столбцы через «|») или None, если psql отказал."""
    r = sh(G.COMPOSE + ['exec', '-T', 'postgres', 'psql', '-U', 'postgres', '-d', database, '-tA', '-F', '|', '-c', query], timeout=60)
    if r.returncode != 0:
        return None
    return [line.split('|') for line in r.stdout.strip().splitlines() if line.strip()]


def scalar(database, query, default=None):
    rows = sql(database, query)
    return rows[0][0] if rows else default


def count(database, query):
    value = scalar(database, query)
    return int(value) if value is not None else -1


def until(timeout, fn, step=3):
    """Значение функции, как только оно станет истинным, либо последнее значение по истечении срока."""
    deadline = time.time() + timeout
    value = fn()
    while not value and time.time() < deadline:
        time.sleep(step)
        value = fn()
    return value


def now():
    moment = datetime.now(timezone.utc)
    return moment.strftime('%Y-%m-%dT%H:%M:%S.') + '%03dZ' % (moment.microsecond // 1000)


def envelope(event_id, source, event_type, subject, aggregate, data):
    """Конверт события в том виде, в каком его собирает издатель Outbox (docs/06-api/events/examples)."""
    return json.dumps({
        'specversion': '1.0', 'id': event_id, 'source': source, 'type': event_type, 'time': now(), 'subject': subject,
        'datacontenttype': 'application/json', 'schemaversion': 1, 'aggregatetype': aggregate,
        'traceparent': '00-%s-%s-01' % (uuid.uuid4().hex, uuid.uuid4().hex[:16]), 'correlationid': str(uuid.uuid4()), 'data': data,
    }, ensure_ascii=False, separators=(',', ':'))


class Stand:
    """Kafka стенда от имени kafka-init: запись событий и чтение очередей тем же кодом, что у dlq_replay.py."""

    def __init__(self):
        self.kafka = D.Kafka(os.path.join(REPO, 'secrets'), os.environ.get('DGM_DATA_NETWORK', 'dgm_data'), 'kafka-init', 'kafka:9093', D.kafka_image())

    def publish(self, topic, key, value, event_type):
        """Запись с заголовком ce_type, как у издателя Outbox; консольный производитель принимает значение одной строкой."""
        self.kafka.write(topic, 'ce_type:%s' % event_type + D.RECORD + key + D.FIELD + value + '\n')

    def dead(self):
        return self.kafka.read(DLQ)


def delivery_event(event_type, order_id, event_id, **extra):
    data = {'deliveryId': str(uuid.uuid4()), 'orderId': order_id, 'deliveryType': 'primary'}
    data.update(extra)
    return envelope(event_id, 'delivery-service', event_type, order_id, 'Delivery', data)


def issued_event(order_id, number):
    data = {'orderId': order_id, 'orderNumber': number, 'buyerId': str(uuid.uuid4()), 'sellerId': str(uuid.uuid4()), 'productId': str(uuid.uuid4()),
            'total': {'amount': 149900, 'currency': 'RUB'}, 'commission': {'amount': 2998, 'currency': 'RUB'}, 'deliveryId': str(uuid.uuid4()),
            'issuedAt': now()}
    return envelope(str(uuid.uuid4()), 'order-service', 'order.issued', order_id, 'Order', data)


def records_for(stand, key):
    return [m for m in stand.dead() if m.key == key]


def ops(*args):
    """Запуск настоящего скрипта оператора: код возврата и объединённый вывод."""
    r = sh([sys.executable, os.path.join(REPO, 'tools', 'ops', 'dlq_replay.py')] + list(args), timeout=300)
    return r.returncode, r.stdout + r.stderr


# --------------------------------------------------------------------------------------------------- сценарии
def poison_goes_to_dlq_at_once(stand):
    key = 'poison-' + uuid.uuid4().hex
    value = POISON_VALUE % uuid.uuid4().hex
    started = time.time()
    stand.publish(TOPIC, key, value, 'delivery.failed')
    found = until(60, lambda: records_for(stand, key), step=2)
    expect('ядовитая запись попала в %s' % DLQ, bool(found), 'за 60 секунд записи с ключом %s нет' % key)
    if not found:
        return
    expect('без повторов: одна попытка у каждого потребителя', all(m.header('dlq-attempts') == '1' for m in found),
           str([m.header('dlq-attempts') for m in found]))
    expect('причина: запись не разобрана (InvalidEventException)', all((m.header('dlq-error-class') or '').endswith('InvalidEventException') for m in found),
           str([m.header('dlq-error-class') for m in found]))
    expect('значение записи не изменено', all(m.value == value for m in found), str([m.value[:60] for m in found]))
    consumers = {m.header('dlq-consumer') for m in found}
    expect('потребитель назван: platform-service среди них', 'platform-service' in consumers, str(consumers))
    expect('запись ушла сразу, без паузы повторов', time.time() - started < RETRY_SECONDS + 60, '%.0f с' % (time.time() - started))
    expect('исходная тема, раздел и смещение записаны', all(m.header('dlq-original-topic') == TOPIC and m.header('dlq-original-partition') is not None
                                                           and (m.header('dlq-original-offset') or '').isdigit() for m in found), str(found[0].headers))


def failed_before_order_then_replay(stand):
    order_id = str(uuid.uuid4())
    number = 7000000 + int(uuid.uuid4().int % 2000000)
    failed_id = str(uuid.uuid4())
    letters_sql = "select count(*) from notification.notification where template = 'delivery-failed' and params ->> 'orderId' = '%s'" % order_id
    started = time.time()
    stand.publish(TOPIC, order_id, delivery_event('delivery.failed', order_id, failed_id, reason='attempts_exhausted', attempts=6), 'delivery.failed')

    time.sleep(RETRY_SECONDS + 2)
    found = until(WAIT_DLQ, lambda: [m for m in records_for(stand, order_id) if m.event_id == failed_id], step=2)
    took = time.time() - started
    expect('событие раньше модели заказа ушло в %s после повторов' % DLQ, bool(found), 'за %d секунд записи события %s нет' % (WAIT_DLQ, failed_id))
    if not found:
        return
    message = found[0]
    expect('причина: временный отказ после повторов (RetryableEventException), четыре попытки',
           (message.header('dlq-error-class') or '').endswith('RetryableEventException') and message.header('dlq-attempts') == '4',
           '%s, попыток %s' % (message.header('dlq-error-class'), message.header('dlq-attempts')))
    expect('отказал обработчик поддержки', message.header('dlq-consumer') == HANDLER, str(message.header('dlq-consumer')))
    expect('повторы заняли не меньше 31 секунды (паузы 1, 5 и 25)', took >= RETRY_SECONDS, '%.0f с' % took)
    expect('заголовки исходной записи сохранены (ce_type)', message.header('ce_type') == 'delivery.failed', str(message.headers))
    expect('время отказа записано (dlq-failed-at, UTC)', (message.header('dlq-failed-at') or '').endswith('Z'), str(message.header('dlq-failed-at')))
    expect('текст ошибки есть и не длиннее 300 знаков', 0 < len(message.header('dlq-error-message') or '') <= 300, str(len(message.header('dlq-error-message') or '')))
    expect('модели заказа в поддержке нет', count('platform_db', "select count(*) from support.order_view where order_id = '%s'" % order_id) == 0)
    expect('обращения нет', count('platform_db', "select count(*) from support.ticket where order_id = '%s'" % order_id) == 0)
    letters = count('platform_db', letters_sql)
    expect('письмо администратору по тому же событию ушло с первого раза', letters >= 1, str(letters))

    # Скрипт оператора: список и показ без значений
    code, out = ops('list', TOPIC)
    expect('dlq_replay list показывает запись события', code == 0 and failed_id in out and HANDLER in out and 'RetryableEventException' in out, out[-400:])
    code, out = ops('show', TOPIC, '--event-id', failed_id)
    expect('dlq_replay show показывает причину и имена полей', code == 0 and 'RetryableEventException' in out and 'orderId' in out, out[-400:])
    expect('dlq_replay show не показывает значений data', 'attempts_exhausted' not in out, out[-400:])
    code, out = ops('replay', TOPIC, '--event-id', failed_id)
    expect('replay без --yes ничего не отправляет', code == 0 and 'Ничего не отправлено' in out, out[-300:])

    # Следующие события потока не заблокированы: причина снимается событием order.issued, оно создаёт модель заказа
    stand.publish('order.events', order_id, issued_event(order_id, number), 'order.issued')
    expect('после отказа потребитель работает: order.issued создал модель заказа',
           bool(until(WAIT_DB, lambda: count('platform_db', "select count(*) from support.order_view where order_id = '%s'" % order_id) == 1)))
    expect('обращения всё ещё нет: событие ждёт возврата', count('platform_db', "select count(*) from support.ticket where order_id = '%s'" % order_id) == 0)

    code, out = ops('replay', TOPIC, '--event-id', failed_id, '--yes')
    expect('replay --yes вернул событие в %s' % TOPIC, code == 0 and 'возвращено' in out, out[-300:])
    expect('после возврата создано одно обращение системы',
           bool(until(WAIT_DB, lambda: count('platform_db', "select count(*) from support.ticket where order_id = '%s'" % order_id) == 1)))
    expect('обращение: источник system, причина attempts_exhausted',
           scalar('platform_db', "select source || '/' || reason from support.ticket where order_id = '%s'" % order_id) == 'system/attempts_exhausted')
    expect('событие отмечено разобранным обработчиком поддержки',
           count('platform_db', "select count(*) from public.processed_event where consumer = '%s' and event_id = '%s'" % (HANDLER, failed_id)) == 1)
    expect('второго письма администратору нет: дубль отсеян', count('platform_db', letters_sql) == letters, '%s -> %s' % (letters, count('platform_db', letters_sql)))

    # Второй возврат той же записи: события одного заказа идут в один раздел по порядку, метка после него доказывает, что дубль разобран
    code, out = ops('replay', TOPIC, '--event-id', failed_id, '--yes')
    expect('второй replay --yes выполнен', code == 0, out[-300:])
    marker = str(uuid.uuid4())
    stand.publish(TOPIC, order_id, delivery_event('delivery.overdue', order_id, marker, paidAt=now(), thresholdSeconds=1800), 'delivery.overdue')
    expect('метка после дубля разобрана',
           bool(until(WAIT_DB, lambda: count('platform_db', "select count(*) from public.processed_event where consumer = '%s' and event_id = '%s'" % (HANDLER, marker)) == 1)))
    expect('обращение по-прежнему одно', count('platform_db', "select count(*) from support.ticket where order_id = '%s'" % order_id) == 1)
    expect('письмо администратору по-прежнему одно на получателя', count('platform_db', letters_sql) == letters)
    expect('дубль не вернулся в %s: запись события одна' % DLQ, len([m for m in records_for(stand, order_id) if m.event_id == failed_id]) == 1)


def summary():
    print('\nПроверок успешно: %d, с ошибками: %d' % (G.PASSED, len(G.FAILED)))
    for failed in G.FAILED:
        print('  - %s' % failed)
    if os.environ.get('GITHUB_ACTIONS'):
        print('::notice title=dlq_checks::Проверок успешно: %d, с ошибками: %d%s' % (G.PASSED, len(G.FAILED), ('; не прошли: ' + '|'.join(G.FAILED)) if G.FAILED else ''))
    return 1 if G.FAILED else 0


def main():
    stand = Stand()
    for scenario in (poison_goes_to_dlq_at_once, failed_before_order_then_replay):
        try:
            scenario(stand)
        except Exception as e:  # noqa: BLE001 - сценарий не должен прерывать остальные, причина попадает в итог
            G.bad(scenario.__name__, '%s: %s' % (type(e).__name__, e))
    return summary()


if __name__ == '__main__':
    sys.exit(main())
