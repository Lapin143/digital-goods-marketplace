#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Модульные тесты restore_drill.py (шаг 22 Ф4, TC-817): разбор выборок, сверка, защита от пустой сверки, проверка неизменяемости восстановленной базы.

    python3 -m unittest tools/ops/test_restore_drill.py

Docker и PostgreSQL не нужны: команды контейнера подменяет `FakeStand`.
"""
import io
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import restore_drill as R  # noqa: E402

AUDIT = [['audit_admin.audit_log_2026_10', '12', 'a' * 32], ['audit_admin.audit_log_2026_11', '3', 'b' * 32]]
SCHEMA = [['audit_admin', 'индексов', '6'], ['audit_admin', 'триггеров', '5'], ['support', 'таблиц', '4']]
COUNTS = [['audit_admin.platform_parameter', '9'], ['support.ticket', '7']]


def text(rows):
    return ''.join('|'.join(r) + '\n' for r in rows)


class FakeStand:
    """Подмена docker compose: по тексту запроса отвечает заготовленными выборками, дамп пишет в переданный файл."""

    def __init__(self, copy_audit=None, copy_schema=None, copy_counts=None, denied=True, dump_code=0, restore_code=0):
        self.audit = {R.SOURCE: AUDIT, R.COPY: AUDIT if copy_audit is None else copy_audit}
        self.schema = {R.SOURCE: SCHEMA, R.COPY: SCHEMA if copy_schema is None else copy_schema}
        self.counts = {R.SOURCE: COUNTS, R.COPY: COUNTS if copy_counts is None else copy_counts}
        self.denied, self.dump_code, self.restore_code = denied, dump_code, restore_code
        self.calls = []

    def __call__(self, cmd, stdin=None, stdout=None, timeout=600):
        self.calls.append(cmd)
        result = mock.Mock(returncode=0, stderr='', stdout='')
        if 'pg_dump' in cmd:
            stdout.write(b'PGDMP-test-dump')
            result.returncode, result.stderr = self.dump_code, 'pg_dump: ошибка'.encode() if self.dump_code else b''
            return result
        if 'pg_restore' in cmd:
            result.returncode, result.stderr = self.restore_code, 'pg_restore: ошибка' if self.restore_code else ''
            return result
        database, sql = cmd[cmd.index('-d') + 1], cmd[cmd.index('-c') + 1]
        if 'clock_timestamp' in sql:
            result.stdout = '2026-11-01 20:00:00.123456+00\n'
        elif 'from audit_admin.audit_log t' in sql:
            result.stdout = text(self.audit[database])
        elif 'pg_inherits' in sql:
            result.stdout = text(self.schema[database])
        elif 'query_to_xml' in sql:
            result.stdout = text(self.counts[database])
        elif sql.lstrip().startswith(('update', 'delete', 'truncate', 'do ')):
            if self.denied:
                result.returncode, result.stderr = 3, 'ERROR: журнал аудита только добавляется (INV-42): TRUNCATE запрещён'
            else:
                result.returncode, result.stderr = 0, ''
        return result


class Pure(unittest.TestCase):
    def test_parse_rows_skips_blank_lines(self):
        self.assertEqual(R.parse_rows('a|1\n\n  \nb|2\n'), [['a', '1'], ['b', '2']])

    def test_diff_rows_equal(self):
        self.assertEqual(R.diff_rows(AUDIT, [list(r) for r in AUDIT], 'x'), [])

    def test_diff_rows_names_the_row_that_differs_on_each_side(self):
        changed = [AUDIT[0][:1] + ['13'] + AUDIT[0][2:], AUDIT[1]]
        problems = R.diff_rows(AUDIT, changed, 'журнал')
        self.assertEqual(len(problems), 2)
        self.assertIn('в исходной базе есть audit_admin.audit_log_2026_10|12|', problems[0])
        self.assertIn('в восстановленной базе есть audit_admin.audit_log_2026_10|13|', problems[1])

    def test_diff_rows_missing_section(self):
        self.assertEqual(len(R.diff_rows(AUDIT, AUDIT[:1], 'журнал')), 1)

    def test_denied_only_by_the_immutability_trigger(self):
        trigger = mock.Mock(returncode=3, stderr='ERROR: журнал аудита только добавляется (INV-42): UPDATE запрещён', stdout='')
        no_table = mock.Mock(returncode=3, stderr='ERROR: relation "audit_admin.audit_log" does not exist', stdout='')
        allowed = mock.Mock(returncode=0, stderr='', stdout='UPDATE 1')
        self.assertTrue(R.denied(trigger))
        self.assertFalse(R.denied(no_table), 'отсутствие таблицы не доказывает неизменяемость')
        self.assertFalse(R.denied(allowed))

    def test_fingerprint(self):
        with tempfile.NamedTemporaryFile() as f:
            f.write(b'abc')
            f.flush()
            size, digest = R.fingerprint(f.name)
        self.assertEqual((size, digest), (3, 'ba7816bf8f01'))

    def test_compose_command_lines(self):
        self.assertEqual(R.Drill().compose(['ps']), ['docker', 'compose', '-f', 'compose.yaml', '--profile', '*', 'ps'])
        self.assertEqual(R.Drill(debug=True).compose(['ps']), ['docker', 'compose', '-f', 'compose.yaml', '-f', 'compose.debug.yaml', '--profile', '*', 'ps'])

    def test_queries_have_no_unfilled_placeholders(self):
        for sql in (R.SCHEMA_PARITY, R.ROW_COUNTS, R.AUDIT_SECTIONS % '2026-11-01 20:00:00+00'):
            self.assertNotIn('%(', sql)
            self.assertNotIn('%%', sql)


class Report(unittest.TestCase):
    def run_report(self, stand, keep=False):
        with tempfile.TemporaryDirectory() as d, mock.patch.object(R, 'DUMP', os.path.join(d, 'sub', 'platform_db.dump')), \
                mock.patch.object(R, 'REPO', d):
            out = io.StringIO()
            with redirect_stdout(out):
                code = R.report(R.Drill(run=stand), keep)
        return code, out.getvalue()

    def test_clean_restore_passes_and_drops_the_copy(self):
        stand = FakeStand()
        code, out = self.run_report(stand)
        self.assertEqual(code, 0, out)
        self.assertIn('записей 15', out)
        self.assertIn('база восстановлена, журнал и схема совпали', out)
        self.assertEqual(out.count('отклонён триггером'), 4)
        drops = [c for c in stand.calls if any('drop database' in str(a) for a in c)]
        self.assertEqual(len(drops), 2, 'копию удаляют перед восстановлением и после сверки')

    def test_keep_leaves_the_copy(self):
        stand = FakeStand()
        self.run_report(stand, keep=True)
        drops = [c for c in stand.calls if any('drop database' in str(a) for a in c)]
        self.assertEqual(len(drops), 1)

    def test_changed_audit_section_fails(self):
        code, out = self.run_report(FakeStand(copy_audit=[AUDIT[0][:1] + ['11'] + AUDIT[0][2:], AUDIT[1]]))
        self.assertEqual(code, 1)
        self.assertIn('РАСХОЖДЕНИЕ журнал аудита', out)

    def test_missing_trigger_fails(self):
        code, out = self.run_report(FakeStand(copy_schema=[['audit_admin', 'индексов', '6'], ['audit_admin', 'триггеров', '4'], ['support', 'таблиц', '4']]))
        self.assertEqual(code, 1)
        self.assertIn('РАСХОЖДЕНИЕ схема', out)

    def test_writable_audit_log_on_the_copy_fails(self):
        code, out = self.run_report(FakeStand(denied=False))
        self.assertEqual(code, 1)
        self.assertIn('НЕ ОТКЛОНЁН', out)
        self.assertIn('ST-09', out)

    def test_empty_audit_log_is_not_a_pass(self):
        stand = FakeStand()
        stand.audit = {R.SOURCE: [], R.COPY: []}
        code, out = self.run_report(stand)
        self.assertEqual(code, 1)
        self.assertIn('нет записей', out)

    def test_row_counts_that_moved_during_the_copy_are_reported_not_failed(self):
        code, out = self.run_report(FakeStand(copy_counts=[['audit_admin.platform_parameter', '9'], ['support.ticket', '6']]))
        self.assertEqual(code, 0, out)
        self.assertIn('строки таблиц разошлись', out)

    def test_failed_restore_stops_with_a_reason(self):
        with self.assertRaises(SystemExit) as raised:
            self.run_report(FakeStand(restore_code=1))
        self.assertIn('pg_restore', str(raised.exception))

    def test_failed_dump_stops_with_a_reason(self):
        with self.assertRaises(SystemExit) as raised:
            self.run_report(FakeStand(dump_code=1))
        self.assertIn('pg_dump', str(raised.exception))


if __name__ == '__main__':
    unittest.main()
