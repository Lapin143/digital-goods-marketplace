# -*- coding: utf-8 -*-
"""Модульные тесты помощников проверок журнала аудита и параметров (audit_checks.py, parameters_checks.py).

    python3 -m unittest tools/stand-checks/test_audit_checks.py

Сеть и стенд не нужны. Проверяются: разбор токена, формат времени для фильтров журнала, поиск изменения поля, записи значения в hex и Base64
для канарейки, расчёт комиссии по FT-9.0 и подсчёт строк «Параметр … =» в журнале сервиса (по ним проверка ждёт слушатель).
"""
import base64
import json
import os
import sys
import unittest
from datetime import datetime, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import audit_checks as AU  # noqa: E402
import canary_checks as C  # noqa: E402
import parameters_checks as PC  # noqa: E402


def token(claims):
    body = base64.urlsafe_b64encode(json.dumps(claims).encode()).rstrip(b'=').decode()
    return 'e30.%s.sig' % body


class JournalHelpersTest(unittest.TestCase):
    def test_claims_are_read_from_the_payload(self):
        self.assertEqual(AU.claims_of(token({'sub': 'abc', 'scope': 'staff.audit'}))['sub'], 'abc')

    def test_stamp_has_milliseconds_and_zulu(self):
        moment = datetime(2026, 10, 25, 12, 30, 5, 123456, tzinfo=timezone.utc)
        self.assertEqual(AU.stamp_of(moment), '2026-10-25T12:30:05.123Z')
        self.assertEqual(AU.instant('2026-10-25T12:30:05.123Z'), datetime(2026, 10, 25, 12, 30, 5, 123000, tzinfo=timezone.utc))

    def test_change_of_finds_the_field_or_none(self):
        record = {'changes': [{'field': 'status', 'before': 'on_review', 'after': 'rejected'}, {'field': 'decisionReason', 'after': 'x'}]}
        self.assertEqual(AU.change_of(record, 'status')['after'], 'rejected')
        self.assertIsNone(AU.change_of(record, 'phone'))
        self.assertIsNone(AU.change_of({}, 'status'))

    def test_items_of_ignores_errors(self):
        class Fake:
            status = 403
            text = '{"items": [1]}'

            def json(self):
                return json.loads(self.text)
        self.assertEqual(AU.items_of(Fake()), [])
        Fake.status = 200
        self.assertEqual(AU.items_of(Fake()), [1])


class CanaryVariantsTest(unittest.TestCase):
    def test_value_comes_with_hex_and_both_base64_forms(self):
        found = AU.variants('+79123456789')
        self.assertEqual(found[0], '+79123456789')
        self.assertEqual(found[1], '2b3739313233343536373839')
        self.assertIn(base64.b64encode(b'+79123456789').decode(), found)
        self.assertEqual(len(found), 4)

    def test_leak_search_finds_every_form_and_stays_quiet_without_them(self):
        value = 'buyer@example.test'
        wanted = AU.variants(value)
        for form in wanted:
            self.assertTrue(C.leaks('{"x": "%s"}' % form, wanted), form)
        self.assertEqual(C.leaks('{"x": "%s"}' % ('0' * 64), wanted), [])

    def test_hash_pattern(self):
        self.assertTrue(AU.HASH.match('a' * 64))
        self.assertFalse(AU.HASH.match('A' * 64))
        self.assertFalse(AU.HASH.match('a' * 63))
        self.assertFalse(AU.HASH.match('buyer@example.test'))


class ParametersHelpersTest(unittest.TestCase):
    def test_commission_rounds_half_up_to_the_kopeck(self):
        self.assertEqual(PC.expected_commission(149900, 200), 2998)
        self.assertEqual(PC.expected_commission(149900, 300), 4497)
        self.assertEqual(PC.expected_commission(100000, 200), 2000)
        self.assertEqual(PC.expected_commission(25, 200), 1)    # 0,5 копейки округляется вверх
        self.assertEqual(PC.expected_commission(24, 200), 0)

    def test_applied_counts_only_the_matching_key_and_value(self):
        lines = ['Параметр orders.max-unpaid = 1 (было 3, версия 4)', 'Параметр orders.max-unpaid = 3 (было 1, версия 5)',
                 'Параметр orders.max-unpaid = 1 (было 3, версия 6)', 'Параметр commission.default-rate-bp = 1 (было 200, версия 2)',
                 'Значение orders.max-unpaid = 1 (версия 2) вне границ 1 – 20, не применено']
        original = PC.log_messages
        PC.log_messages = lambda service: lines
        try:
            self.assertEqual(PC.applied('order-service', 'orders.max-unpaid', 1), 2)
            self.assertEqual(PC.applied('order-service', 'orders.max-unpaid', 3), 1)
            self.assertEqual(PC.applied('order-service', 'orders.max-unpaid', 10), 0)
            self.assertEqual(PC.applied('order-service', 'commission.default-rate-bp', 1), 1)
        finally:
            PC.log_messages = original


if __name__ == '__main__':
    unittest.main()
