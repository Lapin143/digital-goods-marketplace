#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Очередь недоставленных сообщений: просмотр и повторная обработка (шаг 16 Ф4; ADR-006, ADR-011; TC-611, TC-012).

    tools/ops/dlq_replay.py list   delivery.events
    tools/ops/dlq_replay.py show   delivery.events --event-id 0199e018-0000-7000-8000-000000e00018
    tools/ops/dlq_replay.py replay delivery.events --event-id 0199e018-0000-7000-8000-000000e00018            (показать, что будет сделано)
    tools/ops/dlq_replay.py replay delivery.events --event-id 0199e018-0000-7000-8000-000000e00018 --yes      (вернуть в тему)

Тема называется исходной (`delivery.events`) или `.dlq`-темой (`delivery.events.dlq`): скрипт сам добавляет `.dlq`.

Что делает потребитель сервиса с событием, которое не удалось обработать (service-kit, `EventProcessor`): три повтора с паузами 1, 5 и 25 секунд, затем
запись уходит в `<тема>.dlq` с заголовками `dlq-*` (исходная тема, раздел и смещение, обработчик, класс и текст ошибки, число попыток, время). Событие,
которое не разобрать (нет конверта, неверная схема), уходит сразу, без повторов. Скрипт читает такую тему и возвращает выбранное событие в
исходную тему тем же ключом и теми же заголовками (`ce_type`, `traceparent`), кроме `dlq-*`: потребитель видит обычное событие. Двойное применение
исключено: обработчик, уже применивший событие, получит дубль и ничего не сделает (`processed_event`, ADR-006), обработчик, который отказал,
выполнит его заново. Возвращать события нужно после того, как причина устранена (runbook, раздел «Событие в .dlq»): иначе оно вернётся в
очередь недоставленных через 31 секунду повторов.

Значения событий (`data`) скрипт не показывает: в них бывают адреса и суммы. `show` печатает заголовки, поля конверта и имена полей `data`.

Подключение: контейнер образа Kafka из compose.yaml в сети стенда с сертификатом оператора (по умолчанию `kafka-init`, суперпользователь Kafka;
для сервера заведите отдельного принципала с правом чтения `*.dlq` и записи в темы событий). Нужен только Docker и Python 3.
"""
import argparse
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))

# Разделители вывода консоли Kafka: управляющие символы не бывают ни в JSON, ни в заголовках, поэтому разбор однозначен
# (текст ошибки в заголовке может содержать переводы строк, запятые и двоеточия).
FIELD = '\x1f'      # между временем, разделом, смещением, заголовками, ключом и значением (key.separator)
HEADER = '\x1d'     # между заголовками (headers.separator)
RECORD = '\x1e'     # между записями (line.separator)
PREFIX_DLQ = 'dlq-'
UUID = re.compile(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')

PROLOGUE = r"""
D=/tmp/c; mkdir -p $D
cat /s/tls_%(principal)s.key /s/tls_%(principal)s.crt > $D/ks.pem
printf 'security.protocol=SSL\nssl.truststore.type=PEM\nssl.truststore.location=/s/tls_ca.crt\nssl.keystore.type=PEM\nssl.keystore.location=%%s\ndefault.api.timeout.ms=15000\nrequest.timeout.ms=10000\n' $D/ks.pem > $D/c.properties
export KAFKA_HEAP_OPTS=-Xmx128m LOG_DIR=/tmp/l
B=%(bootstrap)s
K=/opt/kafka/bin
"""

# Разделы читаются напрямую, без группы потребителя: смещения групп сервисов и самого скрипта не затрагиваются.
READ = PROLOGUE + r"""
N=$($K/kafka-topics.sh --bootstrap-server $B --command-config $D/c.properties --describe --topic %(topic)s 2>$D/err | sed -n 's/.*PartitionCount: *\([0-9][0-9]*\).*/\1/p' | head -n 1)
if [ -z "$N" ]; then echo "тема %(topic)s не найдена или нет прав: $(tail -n 1 $D/err | cut -c1-200)" >&2; exit 3; fi
for p in $(seq 0 $((N-1))); do
  $K/kafka-console-consumer.sh --bootstrap-server $B --consumer.config $D/c.properties --topic %(topic)s --partition $p --offset earliest \
    --timeout-ms 6000 --consumer-property enable.auto.commit=false \
    --property print.timestamp=true --property print.partition=true --property print.offset=true --property print.headers=true --property print.key=true \
    --property key.separator=$'\x1f' --property headers.separator=$'\x1d' --property line.separator=$'\x1e' 2>>$D/err || true
done
"""

WRITE = PROLOGUE + r"""
$K/kafka-console-producer.sh --bootstrap-server $B --producer.config $D/c.properties --topic %(topic)s \
  --property parse.key=true --property parse.headers=true --property key.separator=$'\x1f' --property headers.delimiter=$'\x1e' \
  --property headers.separator=$'\x1d' --property headers.key.separator=: 2>$D/err || { cat $D/err >&2; exit 4; }
"""


class DeadMessage:
    """Одна запись `.dlq`-темы: место в теме, заголовки `dlq-*` и конверт события (без разбора `data`)."""

    def __init__(self, partition, offset, timestamp, headers, key, value):
        self.partition = partition
        self.offset = offset
        self.timestamp = timestamp
        self.headers = headers          # список пар (имя, значение): заголовки Kafka бывают повторяющимися
        self.key = key
        self.value = value
        try:
            envelope = json.loads(value)
            self.envelope = envelope if isinstance(envelope, dict) else None
        except ValueError:
            self.envelope = None

    def header(self, name, default=None):
        found = [v for k, v in self.headers if k == name]
        return found[-1] if found else default

    @property
    def event_id(self):
        return self.envelope.get('id') if self.envelope else None

    @property
    def event_type(self):
        return (self.envelope.get('type') if self.envelope else None) or self.header('ce_type')

    @property
    def original_topic(self):
        return self.header('dlq-original-topic')

    def passes(self):
        """Заголовки, которые уходят обратно в исходную тему: все, кроме `dlq-*`."""
        return [(k, v) for k, v in self.headers if not k.startswith(PREFIX_DLQ)]

    def place(self):
        return 'p%s@%s' % (self.partition, self.offset)


def parse_records(text):
    """Записи вывода консольного потребителя с разделителями FIELD, HEADER и RECORD."""
    messages = []
    for raw in text.split(RECORD):
        raw = raw.strip('\r\n')
        if not raw:
            continue
        fields = raw.split(FIELD)
        if len(fields) < 6:
            continue
        stamp, partition, offset, headers, key = fields[:5]
        value = FIELD.join(fields[5:])
        pairs = []
        if headers and headers != 'NO_HEADERS':
            for item in headers.split(HEADER):
                name, _, val = item.partition(':')
                pairs.append((name, val))
        messages.append(DeadMessage(partition.split(':', 1)[-1], offset.split(':', 1)[-1], stamp.split(':', 1)[-1], pairs, key, value))
    return messages


def dlq_topic(name):
    return name if name.endswith('.dlq') else name + '.dlq'


def select(messages, event_id=None, partition=None, offset=None):
    """Записи, подходящие под выбор: по идентификатору события или по месту в теме."""
    if event_id:
        return [m for m in messages if m.event_id == event_id]
    return [m for m in messages if str(m.partition) == str(partition) and str(m.offset) == str(offset)]


def short(text, limit):
    text = ' '.join((text or '').split())
    return text if len(text) <= limit else text[:limit - 1] + '…'


def describe(message):
    """Строка списка: место, время, обработчик, тип события, попытки и причина. Значений события в ней нет."""
    error = '%s: %s' % ((message.header('dlq-error-class') or '?').rsplit('.', 1)[-1], short(message.header('dlq-error-message'), 90))
    return '%-8s %-20s %-22s %-18s %-36s попыток %-2s %s' % (
        message.place(), message.header('dlq-failed-at', '?'), message.header('dlq-consumer', '?'), message.event_type or '(нет типа)',
        message.event_id or '(нет идентификатора)', message.header('dlq-attempts', '?'), error)


def show_lines(message):
    """Подробности записи без значений: заголовки, поля конверта и только имена полей `data`."""
    lines = ['Запись %s, ключ %s, время записи в тему %s' % (message.place(), message.key, message.timestamp)]
    for name, val in message.headers:
        if name.startswith(PREFIX_DLQ):
            lines.append('  %-22s %s' % (name, short(val, 300)))
    for name, val in message.headers:
        if not name.startswith(PREFIX_DLQ):
            lines.append('  %-22s %s' % (name, short(val, 120)))
    if message.envelope is None:
        lines.append('Конверт: тело не разобрано как JSON-объект (%d символов). Значения не показываются.' % len(message.value))
        return lines
    data = message.envelope.get('data')
    for name, val in message.envelope.items():
        if name != 'data':
            lines.append('  конверт.%-18s %s' % (name, short(json.dumps(val, ensure_ascii=False), 100)))
    if isinstance(data, dict):
        lines.append('  конверт.data             поля: %s (значения не показываются)' % ', '.join(sorted(data)))
    else:
        lines.append('  конверт.data             %s (значения не показываются)' % ('нет' if data is None else 'не объект'))
    return lines


def producer_line(message, source_topic):
    """Строка консольного производителя: заголовки, ключ и значение исходной записи. Значение должно быть в одну строку."""
    if '\n' in message.value or '\r' in message.value:
        raise ValueError('значение записи занимает несколько строк, консольный производитель его не передаст; верните запись другим способом')
    if RECORD in message.value or HEADER in message.value:
        raise ValueError('в значении записи есть служебные символы')
    pairs = message.passes() + [('dlq-replayed-from', '%s %s' % (source_topic, message.place()))]
    headers = HEADER.join('%s:%s' % (k, v) for k, v in pairs)
    return headers + RECORD + (message.key if message.key != 'null' else '') + FIELD + message.value + '\n'


class Kafka:
    """Консоль Kafka в одноразовом контейнере образа брокера: сертификат оператора, сеть стенда, ничего не остаётся на диске."""

    def __init__(self, secrets, network, principal, bootstrap, image):
        self.secrets, self.network, self.principal, self.bootstrap, self.image = secrets, network, principal, bootstrap, image

    def _docker(self, script, stdin=None, timeout=120):
        cmd = ['docker', 'run', '--rm', '-i', '--network', self.network, '-v', '%s:/s:ro' % os.path.abspath(self.secrets), '--entrypoint', 'bash',
               '-u', '0:0', self.image, '-c', script]
        return subprocess.run(cmd, input=stdin if stdin is not None else '', capture_output=True, text=True, errors='replace', timeout=timeout)

    def read(self, topic):
        script = READ % {'principal': self.principal, 'bootstrap': self.bootstrap, 'topic': topic}
        result = self._docker(script)
        if result.returncode != 0:
            raise RuntimeError(result.stderr.strip() or 'не удалось прочитать тему %s' % topic)
        return parse_records(result.stdout)

    def write(self, topic, line):
        script = WRITE % {'principal': self.principal, 'bootstrap': self.bootstrap, 'topic': topic}
        result = self._docker(script, stdin=line)
        if result.returncode != 0:
            raise RuntimeError(result.stderr.strip() or 'не удалось записать в тему %s' % topic)


def kafka_image():
    """Образ Kafka из compose.yaml: тот же, что у брокера стенда."""
    with open(os.path.join(REPO, 'compose.yaml'), encoding='utf-8') as f:
        found = re.search(r'^\s+image:\s+(apache/kafka:\S+)', f.read(), re.M)
    if not found:
        raise RuntimeError('образ Kafka не найден в compose.yaml')
    return found.group(1)


def one(messages, args, out):
    """Единственная запись под выбор; если одно событие лежит в теме несколько раз (отказали два обработчика), берётся первая."""
    found = select(messages, args.event_id, args.partition, args.offset)
    if not found:
        out('Подходящей записи нет. Список: dlq_replay.py list %s' % args.topic)
        return None
    ids = {m.event_id for m in found}
    if len(found) > 1 and (len(ids) > 1 or None in ids):
        out('Выбору соответствует несколько разных записей, уточните место (--partition и --offset):')
        for m in found:
            out('  ' + describe(m))
        return None
    if len(found) > 1:
        out('Событие лежит в теме %d раза (его не обработали несколько обработчиков). Возвращается одно: повтор доставит его всем.' % len(found))
    return found[0]


def run(args, kafka, out=print):
    topic = dlq_topic(args.topic)
    messages = kafka.read(topic)
    if args.command == 'list':
        out('Тема %s: записей %d' % (topic, len(messages)))
        for m in sorted(messages, key=lambda x: (x.header('dlq-failed-at', ''), int(x.partition), int(x.offset))):
            out(describe(m))
        return 0
    message = one(messages, args, out)
    if message is None:
        return 2
    if args.command == 'show':
        for line in show_lines(message):
            out(line)
        return 0
    target = message.original_topic or topic[:-len('.dlq')]
    out('Вернуть %s (%s, событие %s) в тему %s с ключом %s.' % (message.place(), message.event_type, message.event_id, target, message.key))
    out('Причина отказа была: %s, %s попыток. Устранена ли она? Не возвращайте событие, пока причина не снята (runbook, «Событие в .dlq»).'
        % ((message.header('dlq-error-class') or '?').rsplit('.', 1)[-1], message.header('dlq-attempts', '?')))
    try:
        line = producer_line(message, topic)
    except ValueError as e:
        out('ОШИБКА: %s' % e)
        return 3
    if not args.yes:
        out('Ничего не отправлено. Чтобы вернуть событие, повторите команду с --yes.')
        return 0
    kafka.write(target, line)
    out('Событие возвращено в %s. Если оно снова не обработается, через 31 секунду оно опять окажется в %s.' % (target, topic))
    return 0


def parser():
    p = argparse.ArgumentParser(description='Очередь недоставленных сообщений: просмотр и повторная обработка (значения событий не показываются)')
    p.add_argument('--secrets', default=os.environ.get('DGM_SECRETS_DIR', os.path.join(REPO, 'secrets')), help='каталог сертификатов (secrets)')
    p.add_argument('--network', default=os.environ.get('DGM_DATA_NETWORK', 'dgm_data'), help='сеть Docker, в которой доступен Kafka')
    p.add_argument('--principal', default=os.environ.get('DGM_DLQ_PRINCIPAL', 'kafka-init'), help='имя сертификата оператора: secrets/tls_<имя>.key и .crt')
    p.add_argument('--bootstrap', default=os.environ.get('DGM_KAFKA_BOOTSTRAP', 'kafka:9093'))
    sub = p.add_subparsers(dest='command', required=True)
    for name, text in (('list', 'записи очереди'), ('show', 'одна запись без значений'), ('replay', 'вернуть запись в исходную тему')):
        s = sub.add_parser(name, help=text)
        s.add_argument('topic', help='тема событий (delivery.events) или её очередь (delivery.events.dlq)')
        if name != 'list':
            s.add_argument('--event-id', help='идентификатор события (поле id конверта)')
            s.add_argument('--partition', help='раздел очереди (вместе со смещением)')
            s.add_argument('--offset', help='смещение в разделе очереди')
        if name == 'replay':
            s.add_argument('--yes', action='store_true', help='действительно отправить; без него только показать')
    return p


def validate(args):
    if args.command == 'list':
        return None
    if args.event_id:
        if not UUID.match(args.event_id):
            return 'идентификатор события должен быть UUID в нижнем регистре'
        return None
    if args.partition is not None and args.offset is not None and args.partition.isdigit() and args.offset.isdigit():
        return None
    return 'укажите --event-id или пару --partition и --offset'


def main(argv=None):
    args = parser().parse_args(argv)
    problem = validate(args)
    if problem:
        print('ОШИБКА: ' + problem, file=sys.stderr)
        return 2
    try:
        kafka = Kafka(args.secrets, args.network, args.principal, args.bootstrap, kafka_image())
        return run(args, kafka)
    except (RuntimeError, OSError, subprocess.SubprocessError) as e:
        print('ОШИБКА: %s' % e, file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
