# -*- coding: utf-8 -*-
"""Модульные тесты расчётов проверки времени и поиска карточных данных (timing_checks.py).

    python3 -m unittest tools/stand-checks/test_timing_checks.py

Сеть и стенд не нужны. Проверяются: процентиль по ближайшему рангу, контрольная цифра Луна, поиск чисел, похожих на номер карты (находит номер
в записи с пробелами, дефисами и без них, не находит время в миллисекундах, идентификаторы, номера ключей и телефоны), имена столбцов и полей.
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import timing_checks as T  # noqa: E402


class PercentileTest(unittest.TestCase):
    def test_nearest_rank(self):
        self.assertEqual(T.percentile([1, 2, 3, 4, 5, 6, 7, 8, 9, 10], 0.5), 5)
        self.assertEqual(T.percentile([1, 2, 3, 4, 5, 6, 7, 8, 9, 10], 0.95), 10)
        self.assertEqual(T.percentile(list(range(1, 101)), 0.95), 95)

    def test_unsorted_and_single(self):
        self.assertEqual(T.percentile([9, 1, 5], 0.5), 5)
        self.assertEqual(T.percentile([7], 0.95), 7)

    def test_empty(self):
        self.assertIsNone(T.percentile([], 0.95))


class CardTest(unittest.TestCase):
    def test_luhn(self):
        for good in ('4111111111111111', '5555555555554444', '378282246310005', '2200000000000004'):
            self.assertTrue(T.luhn(good), good)
        self.assertFalse(T.luhn('4111111111111112'))

    def test_finds_formatted_numbers(self):
        self.assertEqual(T.card_like('оплата 4111 1111 1111 1111 принята'), ['4111 1111 1111 1111'])
        self.assertEqual(T.card_like('card=5555-5555-5555-4444;'), ['5555-5555-5555-4444'])
        self.assertEqual(T.card_like('{"n":"378282246310005"}'), ['378282246310005'])

    def test_ignores_what_is_not_a_card(self):
        for text in ('время 1761389569123 мс',                                            # миллисекунды: префикс 1
                     'DGM-ORD-4a3f9c1e-0000-4000-8000-000000000001',                     # ключ
                     '3f2b8a40-1111-4222-8333-444455556666',                             # UUID
                     'телефон +79991234567', 'заказ 12345', '4111 1111 1111 1112',      # короткое, контрольная цифра не сходится
                     'отметка 2026-10-25 09:52:49.123456+00'):
            self.assertEqual(T.card_like(text), [], text)

    def test_ignores_digits_inside_words(self):
        self.assertEqual(T.card_like('\\x4111111111111111abc'), [])
        self.assertEqual(T.card_like('id4111111111111111'), [])


class NamesTest(unittest.TestCase):
    def test_card_names(self):
        for name in ('card_number', 'cardNumber', 'card-holder', 'pan', 'cvv', 'CVC', 'expiry_date', 'card_expiry'):
            self.assertTrue(T.CARD_NAME.search(name), name)

    def test_other_names(self):
        for name in ('payment_id', 'panel', 'company', 'scan', 'discard', 'cancel_reason', 'span', 'paid_at', 'expires_at'):
            self.assertFalse(T.CARD_NAME.search(name), name)

    def test_contracts_have_no_card_fields(self):
        self.assertEqual(T.contract_fields(), [])


if __name__ == '__main__':
    unittest.main()
