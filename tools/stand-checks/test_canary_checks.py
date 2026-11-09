# -*- coding: utf-8 -*-
"""Модульные тесты поиска значений канарейки (canary_checks.find_at и leaks).

    python3 -m unittest tools/stand-checks/test_canary_checks.py

Сеть и стенд не нужны. Главное: шестизначный код подтверждения не находится внутри метки времени журнала. В запуске CI 42276547602 код
«870893» нашёлся в `"@timestamp":"2026-11-06T19:17:05.987089387Z"` шлюза и покрасил проверку смены e-mail без всякой утечки (F5-14).
То же с идентификатором трассы: в ночном запуске 42572696614 код «331211» нашёлся в `"traceId":"dc15b331211f0fd2109552b87a0c3314"` (F5-46).
Обратная сторона так же важна: код, который в журнал действительно попал, обязан находиться.
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import canary_checks as C  # noqa: E402

LINE = '{"@timestamp":"2026-11-06T19:17:05.987089387Z","@version":"1","message":"POST /api/v1/phone-confirmations"}'
TRACE = '{"level":"INFO","level_value":20000,"traceId":"dc15b331211f0fd2109552b87a0c3314","spanId":"d796a28673f8a08a"}'


class DigitCodeTest(unittest.TestCase):
    def test_a_code_inside_a_timestamp_is_not_a_leak(self):
        self.assertIn('870893', LINE)
        self.assertEqual(C.find_at(LINE, '870893'), -1)
        self.assertEqual(C.leaks(LINE, ['870893']), [])

    def test_a_code_inside_a_hex_trace_id_is_not_a_leak(self):
        self.assertIn('331211', TRACE)
        self.assertEqual(C.find_at(TRACE, '331211'), -1)
        self.assertEqual(C.leaks(TRACE, ['331211']), [])
        self.assertEqual(C.leaks(TRACE, ['109552']), [])   # цифры внутри идентификатора после цифры слева

    def test_a_code_with_a_letter_on_either_side_is_not_found(self):
        for text in ('b870893', '870893f', 'x870893y', '05.870893Z'):
            self.assertEqual(C.find_at(text, '870893'), -1, text)

    def test_a_code_next_to_a_trace_id_in_the_same_line_is_still_a_leak(self):
        self.assertEqual(len(C.leaks('%s code=331211' % TRACE, ['331211'])), 1)

    def test_a_code_written_as_a_separate_number_is_a_leak(self):
        for text in ('code=870893', 'код 870893.', '"code":"870893"', '870893', 'a\n870893\nb', '(870893)', 'sms:870893,'):
            self.assertGreaterEqual(C.find_at(text, '870893'), 0, text)
        self.assertEqual(len(C.leaks('{"code":"870893","at":"%s"}' % LINE, ['870893'])), 1)

    def test_a_code_with_a_digit_on_either_side_is_not_found(self):
        for text in ('1870893', '8708932', '18708932'):
            self.assertEqual(C.find_at(text, '870893'), -1, text)

    def test_the_position_is_the_first_separate_occurrence(self):
        text = 'x 9870893 y 870893 z'
        self.assertEqual(C.find_at(text, '870893'), text.index(' 870893 ') + 1)


class OtherValuesTest(unittest.TestCase):
    def test_non_digit_values_are_still_found_as_substrings(self):
        self.assertEqual(C.find_at('abc CANARY-xyz def', 'CANARY-xyz'), 4)
        self.assertEqual(C.find_at('prefixCANARY-xyzsuffix', 'CANARY-xyz'), 6)
        self.assertEqual(C.find_at('buyer@example.test', 'buyer@example.test'), 0)
        self.assertEqual(C.find_at('abc', 'zzz'), -1)

    def test_a_value_with_digits_and_letters_is_a_plain_substring(self):
        self.assertEqual(C.find_at('key a1b2c3d4 end', 'a1b2c3d4'), 4)
        self.assertEqual(C.find_at('key xa1b2c3d4x end', 'a1b2c3d4'), 5)

    def test_a_value_with_regex_characters_is_a_plain_substring(self):
        self.assertEqual(C.find_at('a+b', '+'), 1)   # не из цифр: обычная подстрока

    def test_the_context_in_the_message_does_not_contain_the_value_twice(self):
        found = C.leaks('lead code=870893 tail', ['870893'])
        self.assertEqual(len(found), 1)
        self.assertTrue(found[0].startswith('870893… в «'))


if __name__ == '__main__':
    unittest.main()
