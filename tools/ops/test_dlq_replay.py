#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Модульные тесты dlq_replay.py (шаг 16 Ф4, TC-611): разбор вывода консоли Kafka, выбор записи, строка возврата, защита от слепого возврата.

    python3 -m unittest tools/ops/test_dlq_replay.py

Kafka и Docker не нужны: консоль подменяет `FakeKafka`.
"""
import json
import os
import subprocess
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dlq_replay as R  # noqa: E402

EVENT_A = '0199e018-0000-7000-8000-000000e00018'
EVENT_B = '0199e019-0000-7000-8000-000000e00019'
ORDER = '0199c2a4-5e10-7a3b-9c1d-2f4e6a8b0c1d'
SECRET_VALUE = 'buyer-secret@mail.example'


def envelope(event_id, event_type, **data):
    return json.dumps({'specversion': '1.0', 'id': event_id, 'source': 'delivery-service', 'type': event_type, 'time': '2026-10-03T12:47:48.100Z',
                       'subject': ORDER, 'datacontenttype': 'application/json', 'schemaversion': 1, 'aggregatetype': 'Delivery',
                       'traceparent': '00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba90218-01', 'correlationid': 'c-1', 'data': data},
                      ensure_ascii=False, separators=(',', ':'))


def record(partition, offset, headers, key, value, stamp='1790000000000'):
    """Одна запись так, как её печатает консольный потребитель с нашими разделителями."""
    shown = R.HEADER.join('%s:%s' % h for h in headers) if headers else 'NO_HEADERS'
    return R.FIELD.join(['CreateTime:%s' % stamp, 'Partition:%s' % partition, 'Offset:%s' % offset, shown, key, value]) + R.RECORD


def dead(event_id=EVENT_A, consumer='support-event-handler', attempts='4', error='dgm.kit.events.RetryableEventException',
         message='Модели чтения заказа ещё нет, событие delivery.failed повторится (F3-21)', event_type='delivery.failed', partition=0, offset=5, **data):
    headers = [('ce_type', event_type), ('traceparent', '00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba90218-01'),
               ('dlq-original-topic', 'delivery.events'), ('dlq-original-partition', '2'), ('dlq-original-offset', '41'),
               ('dlq-consumer', consumer), ('dlq-error-class', error), ('dlq-error-message', message), ('dlq-attempts', attempts),
               ('dlq-failed-at', '2026-10-03T12:48:19Z')]
    return record(partition, offset, headers, ORDER, envelope(event_id, event_type, orderId=ORDER, reason='attempts_exhausted', address=SECRET_VALUE, **data))


class FakeKafka:
    def __init__(self, output):
        self.output = output
        self.written = []

    def read(self, topic):
        self.read_topic = topic
        return R.parse_records(self.output)

    def write(self, topic, line):
        self.written.append((topic, line))


def args_of(*argv):
    return R.parser().parse_args(list(argv))


class Collect:
    def __init__(self):
        self.lines = []

    def __call__(self, text):
        self.lines.append(text)

    def text(self):
        return '\n'.join(self.lines)


class ParseTest(unittest.TestCase):
    def test_records_are_split_by_separators_not_by_lines_commas_or_colons(self):
        message = 'Ошибка: строка 1\nстрока 2, с запятой и двоеточием: x'
        out = dead(message=message) + dead(event_id=EVENT_B, partition=1, offset=9, consumer='notification-handler')
        parsed = R.parse_records(out)
        self.assertEqual(2, len(parsed))
        self.assertEqual(message, parsed[0].header('dlq-error-message'))
        self.assertEqual('support-event-handler', parsed[0].header('dlq-consumer'))
        self.assertEqual(EVENT_A, parsed[0].event_id)
        self.assertEqual('delivery.failed', parsed[0].event_type)
        self.assertEqual(('1', '9'), (parsed[1].partition, parsed[1].offset))
        self.assertEqual('00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba90218-01', parsed[0].header('traceparent'))

    def test_record_without_headers_and_with_broken_body_is_kept_and_has_no_event_id(self):
        parsed = R.parse_records(record(0, 3, [], 'null', '{"hello":"world"') + 'шум без разделителей\n')
        self.assertEqual(1, len(parsed))
        self.assertIsNone(parsed[0].envelope)
        self.assertIsNone(parsed[0].event_id)
        self.assertEqual([], parsed[0].headers)

    def test_topic_name_gets_the_dlq_suffix_once(self):
        self.assertEqual('delivery.events.dlq', R.dlq_topic('delivery.events'))
        self.assertEqual('delivery.events.dlq', R.dlq_topic('delivery.events.dlq'))


class ShowTest(unittest.TestCase):
    def test_list_and_show_never_print_values_of_the_event(self):
        kafka = FakeKafka(dead())
        for command in ('list', 'show'):
            out = Collect()
            argv = [command, 'delivery.events'] + (['--event-id', EVENT_A] if command == 'show' else [])
            self.assertEqual(0, R.run(args_of(*argv), kafka, out))
            text = out.text()
            self.assertNotIn(SECRET_VALUE, text, command)
            self.assertNotIn('attempts_exhausted', text, command)
            self.assertIn(EVENT_A, text)
            self.assertIn('support-event-handler', text)
            self.assertIn('RetryableEventException', text)
        self.assertEqual('delivery.events.dlq', kafka.read_topic)

    def test_show_names_the_data_fields_only(self):
        out = Collect()
        R.run(args_of('show', 'delivery.events', '--event-id', EVENT_A), FakeKafka(dead()), out)
        self.assertIn('поля: address, orderId, reason', out.text())
        self.assertIn('значения не показываются', out.text())

    def test_body_that_is_not_json_is_reported_without_printing_it(self):
        out = Collect()
        R.run(args_of('show', 'delivery.events', '--partition', '0', '--offset', '3'), FakeKafka(record(0, 3, [('ce_type', 'x')], 'k', 'секрет {')), out)
        self.assertIn('не разобрано как JSON', out.text())
        self.assertNotIn('секрет', out.text())

    def test_list_is_sorted_by_failure_time_then_place(self):
        late = dead(event_id=EVENT_B, offset=1).replace('2026-10-03T12:48:19Z', '2026-10-04T08:00:00Z')
        out = Collect()
        R.run(args_of('list', 'delivery.events'), FakeKafka(late + dead()), out)
        self.assertIn(EVENT_A, out.lines[1])
        self.assertIn(EVENT_B, out.lines[2])


class SelectTest(unittest.TestCase):
    def test_by_event_id_and_by_place(self):
        parsed = R.parse_records(dead() + dead(event_id=EVENT_B, partition=1, offset=7))
        self.assertEqual([EVENT_B], [m.event_id for m in R.select(parsed, event_id=EVENT_B)])
        self.assertEqual([EVENT_B], [m.event_id for m in R.select(parsed, partition='1', offset='7')])
        self.assertEqual([], R.select(parsed, event_id='0199e0ff-0000-7000-8000-000000e000ff'))

    def test_arguments_are_validated(self):
        self.assertIsNotNone(R.validate(args_of('show', 'delivery.events')))
        self.assertIsNotNone(R.validate(args_of('show', 'delivery.events', '--event-id', 'не-uuid')))
        self.assertIsNotNone(R.validate(args_of('replay', 'delivery.events', '--partition', '0')))
        self.assertIsNone(R.validate(args_of('replay', 'delivery.events', '--partition', '0', '--offset', '5')))
        self.assertIsNone(R.validate(args_of('replay', 'delivery.events', '--event-id', EVENT_A)))
        self.assertIsNone(R.validate(args_of('list', 'delivery.events')))


class ReplayTest(unittest.TestCase):
    def test_without_yes_nothing_is_sent(self):
        kafka = FakeKafka(dead())
        out = Collect()
        self.assertEqual(0, R.run(args_of('replay', 'delivery.events', '--event-id', EVENT_A), kafka, out))
        self.assertEqual([], kafka.written)
        self.assertIn('Ничего не отправлено', out.text())
        self.assertIn('Устранена ли она?', out.text())

    def test_event_goes_back_to_the_original_topic_with_key_value_and_headers_but_without_dlq_headers(self):
        kafka = FakeKafka(dead())
        out = Collect()
        self.assertEqual(0, R.run(args_of('replay', 'delivery.events.dlq', '--event-id', EVENT_A, '--yes'), kafka, out))
        (topic, line), = kafka.written
        self.assertEqual('delivery.events', topic)
        self.assertTrue(line.endswith('\n') and line.count('\n') == 1)
        headers, rest = line.split(R.RECORD)
        key, value = rest.rstrip('\n').split(R.FIELD, 1)
        self.assertEqual(ORDER, key)
        self.assertEqual(R.parse_records(dead())[0].value, value, 'значение без изменений')
        sent = dict(h.split(':', 1) for h in headers.split(R.HEADER))
        self.assertEqual('delivery.failed', sent['ce_type'])
        self.assertTrue(sent['traceparent'].startswith('00-4bf9'))
        self.assertEqual('delivery.events.dlq p0@5', sent['dlq-replayed-from'])
        self.assertEqual({'ce_type', 'traceparent', 'dlq-replayed-from'}, set(sent), 'остальные dlq-* заголовки не возвращаются')

    def test_the_same_event_left_by_two_handlers_is_returned_once(self):
        both = dead() + dead(consumer='notification-handler', offset=6)
        kafka = FakeKafka(both)
        out = Collect()
        self.assertEqual(0, R.run(args_of('replay', 'delivery.events', '--event-id', EVENT_A, '--yes'), kafka, out))
        self.assertEqual(1, len(kafka.written))
        self.assertIn('повтор доставит его всем', out.text())

    def test_two_different_records_under_one_place_selector_are_not_guessed(self):
        out = Collect()
        kafka = FakeKafka(record(0, 5, [('ce_type', 'a')], 'k', '{}') + record(0, 5, [('ce_type', 'b')], 'k', '{}'))
        self.assertEqual(2, R.run(args_of('replay', 'delivery.events', '--partition', '0', '--offset', '5', '--yes'), kafka, out))
        self.assertEqual([], kafka.written)

    def test_unknown_record_gives_a_hint_and_sends_nothing(self):
        kafka = FakeKafka(dead())
        out = Collect()
        self.assertEqual(2, R.run(args_of('replay', 'delivery.events', '--event-id', EVENT_B, '--yes'), kafka, out))
        self.assertEqual([], kafka.written)
        self.assertIn('Подходящей записи нет', out.text())

    def test_multiline_value_is_refused_instead_of_being_broken_by_the_console_producer(self):
        message = R.parse_records(record(0, 1, [('dlq-original-topic', 'delivery.events')], 'k', 'a\nb'))[0]
        with self.assertRaises(ValueError):
            R.producer_line(message, 'delivery.events.dlq')
        kafka = FakeKafka(record(0, 1, [('dlq-original-topic', 'delivery.events')], 'k', 'a\nb'))
        out = Collect()
        self.assertEqual(3, R.run(args_of('replay', 'delivery.events', '--partition', '0', '--offset', '1', '--yes'), kafka, out))
        self.assertEqual([], kafka.written)

    def test_record_without_original_topic_header_goes_back_to_the_topic_name_without_the_suffix(self):
        kafka = FakeKafka(record(0, 1, [('ce_type', 'delivery.failed')], 'k', envelope(EVENT_A, 'delivery.failed', orderId=ORDER)))
        self.assertEqual(0, R.run(args_of('replay', 'delivery.events', '--event-id', EVENT_A, '--yes'), kafka, Collect()))
        self.assertEqual('delivery.events', kafka.written[0][0])

    def test_null_key_is_sent_as_an_empty_key(self):
        message = R.parse_records(record(0, 1, [('ce_type', 'x')], 'null', '{}'))[0]
        self.assertIn(R.RECORD + R.FIELD + '{}', R.producer_line(message, 'delivery.events.dlq'))


class ScriptTest(unittest.TestCase):
    def test_read_script_reads_partitions_directly_without_a_consumer_group_and_does_not_commit(self):
        script = R.READ % {'principal': 'kafka-init', 'bootstrap': 'kafka:9093', 'topic': 'delivery.events.dlq'}
        self.assertIn('--partition $p --offset earliest', script)
        self.assertIn('enable.auto.commit=false', script)
        self.assertNotIn('--group', script)
        self.assertNotIn('--from-beginning', script)
        self.assertIn('/s/tls_kafka-init.key', script)
        self.assertIn('--topic delivery.events.dlq', script)

    def test_write_script_parses_headers_and_key(self):
        script = R.WRITE % {'principal': 'kafka-init', 'bootstrap': 'kafka:9093', 'topic': 'delivery.events'}
        for needle in ('parse.headers=true', 'parse.key=true', "headers.delimiter=$'\\x1e'", "key.separator=$'\\x1f'", "headers.separator=$'\\x1d'",
                       '--topic delivery.events'):
            self.assertIn(needle, script)

    def test_both_scripts_are_valid_bash(self):
        for template, topic in ((R.READ, 'delivery.events.dlq'), (R.WRITE, 'delivery.events')):
            script = template % {'principal': 'kafka-init', 'bootstrap': 'kafka:9093', 'topic': topic}
            result = subprocess.run(['bash', '-n', '-c', script], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertNotIn('%(', script)

    def test_kafka_image_is_taken_from_compose(self):
        self.assertTrue(R.kafka_image().startswith('apache/kafka:'))


if __name__ == '__main__':
    unittest.main()
