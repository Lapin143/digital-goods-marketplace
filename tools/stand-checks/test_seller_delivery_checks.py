# -*- coding: utf-8 -*-
"""Модульные тесты помощников сквозной проверки выдачи по запросу (seller_delivery_checks.py, шаг 13 Ф5, К15).

    python3 -m unittest tools/stand-checks/test_seller_delivery_checks.py

Сеть и стенд не нужны. Сам сценарий идёт только на стенде (его гоняет CI), поэтому здесь проверено то, что можно проверить без него: разбор письма
с ключами продавца, граница между «поля покупателя в заказе продавца» и обычными полями, расчёт времени между оплатой и сроком, чтение остатка
и запроса продавцу из ответов стенда, пик памяти трёх сервисов и то, что возврат заглушки в исходный режим при падении не прячет итог проверки.
"""
import json
import os
import sys
import unittest
from types import SimpleNamespace
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gateway_checks as G  # noqa: E402
import seller_delivery_checks as M  # noqa: E402

BUYER = SimpleNamespace(email='buyer-1@example.test', id='6f1d0a52-1111-4222-8333-944455556666')
LETTER = '''Здравствуйте!
Ваши ключи (2 шт.):

1. SELLR_boot01_000007-1
2. SELLR_boot01_000007-2

Инструкция: 1. Откройте клиент. 2. Введите ключ.
'''


def response(status, body):
    return G.Resp(status, [], json.dumps(body).encode())


class LetterTest(unittest.TestCase):
    def test_the_keys_are_read_from_numbered_lines_with_the_seller_prefix(self):
        self.assertEqual(M.KEY_LINE.findall(LETTER), ['SELLR_boot01_000007-1', 'SELLR_boot01_000007-2'])

    def test_numbered_steps_of_the_instruction_are_not_keys(self):
        self.assertEqual(M.KEY_LINE.findall('1. Откройте клиент\n2. Введите ключ\n'), [])

    def test_a_trailing_space_does_not_hide_the_key_and_a_foreign_prefix_is_not_taken(self):
        self.assertEqual(M.KEY_LINE.findall('1. SELLR_a_1-1 \n2. DGM-ORD-0000\n'), ['SELLR_a_1-1'])


class BuyerDataTest(unittest.TestCase):
    ORDER = {'orderId': 'o1', 'orderNumber': 1001, 'productId': 'p1', 'quantity': 2, 'status': 'issued', 'cancelReason': None}

    def test_an_order_without_buyer_fields_passes(self):
        self.assertTrue(M.no_buyer_data(BUYER, response(200, {'items': [self.ORDER]}), self.ORDER))

    def test_a_buyer_field_fails_even_when_its_value_is_empty(self):
        for name in ('buyerId', 'buyerEmail', 'deliveryAddress', 'userId', 'phone'):
            with self.subTest(name=name):
                order = dict(self.ORDER, **{name: None})
                self.assertFalse(M.no_buyer_data(BUYER, response(200, {'items': [order]}), order))

    def test_the_address_or_the_identifier_of_the_buyer_in_the_text_fails(self):
        self.assertFalse(M.no_buyer_data(BUYER, response(200, {'items': [self.ORDER], 'note': BUYER.email}), self.ORDER))
        self.assertFalse(M.no_buyer_data(BUYER, response(200, {'items': [self.ORDER], 'note': BUYER.id}), self.ORDER))


class TimeTest(unittest.TestCase):
    def test_seconds_between_two_instants_with_nanoseconds(self):
        self.assertAlmostEqual(M.seconds_between('2026-11-09T10:00:00.123456789Z', '2026-11-09T10:00:20.623456789Z'), 20.5, places=3)

    def test_the_second_may_be_earlier(self):
        self.assertLess(M.seconds_between('2026-11-09T10:00:20Z', '2026-11-09T10:00:00Z'), 0)


class StandReadsTest(unittest.TestCase):
    FX = {'key_a': {'value': 'dgm_key'}, 'pid': 'p1'}

    def test_the_stock_is_read_by_the_key_and_reduced_to_three_numbers(self):
        body = {'productId': 'p1', 'available': 18, 'reserved': 0, 'issued': 2, 'version': 3, 'issuanceMethod': 'seller_api'}
        with mock.patch.object(M.SAPI, 'by_key', return_value=response(200, body)) as call:
            self.assertEqual(M.stock_of(self.FX), {'available': 18, 'reserved': 0, 'issued': 2})
        self.assertEqual(call.call_args.args[:3], ('dgm_key', 'GET', '/api/v1/seller/products/p1/stock'))

    def test_a_failed_read_gives_empty_numbers_that_never_equal_the_wanted_ones(self):
        with mock.patch.object(M.SAPI, 'by_key', return_value=response(401, {})):
            self.assertEqual(M.stock_of(self.FX), {'available': None, 'reserved': None, 'issued': None})
            self.assertFalse(M.stock_is(self.FX, 18, wait=0.1)[0])

    def test_stock_is_returns_the_value_it_saw_for_the_message(self):
        body = {'available': 19, 'reserved': 1, 'issued': 0}
        with mock.patch.object(M.SAPI, 'by_key', return_value=response(200, body)):
            ok, seen = M.stock_is(self.FX, 18, 1, 0, wait=0.1)
        self.assertFalse(ok)
        self.assertEqual(seen, {'available': 19, 'reserved': 1, 'issued': 0})

    def test_the_seller_request_row_is_read_from_delivery_db(self):
        with mock.patch.object(M.O, 'sql', return_value=[['timeout', '1', '']]) as sql:
            self.assertEqual(M.seller_request_of('order-1'), ('timeout', 1, ''))
        self.assertEqual(sql.call_args.args[0], 'delivery_db')
        self.assertIn("d.order_id = 'order-1'", sql.call_args.args[1])
        with mock.patch.object(M.O, 'sql', return_value=[]):
            self.assertIsNone(M.seller_request_of('order-1'))

    def test_the_order_list_gives_the_page_and_the_items(self):
        with mock.patch.object(M.SAPI, 'by_key', return_value=response(200, {'items': [{'orderId': 'o1'}]})) as call:
            page, items = M.seller_orders('dgm_key', limit=100)
        self.assertEqual((page.status, items), (200, [{'orderId': 'o1'}]))
        self.assertEqual(call.call_args.args[:3], ('dgm_key', 'GET', '/api/v1/seller/orders?limit=100'))
        with mock.patch.object(M.SAPI, 'by_key', return_value=response(401, {})):
            self.assertEqual(M.seller_orders('dgm_key')[1], [])


class MemoryTest(unittest.TestCase):
    def finish(self, peaks):
        watch = M.MemoryWatch()
        watch.peaks = peaks
        before = (G.PASSED, len(G.FAILED))
        with mock.patch('builtins.print'):
            watch.finish()
        return G.PASSED - before[0], len(G.FAILED) - before[1]

    def tearDown(self):
        del G.FAILED[:]

    def test_three_services_under_the_threshold_pass(self):
        passed, failed = self.finish({'delivery-service': (300.0, 512.0), 'inventory-service': (200.0, 512.0), 'order-service': (350.0, 512.0)})
        self.assertEqual((passed, failed), (1, 0))

    def test_a_peak_above_ninety_percent_fails(self):
        self.assertEqual(self.finish({'delivery-service': (480.0, 512.0), 'inventory-service': (200.0, 512.0), 'order-service': (350.0, 512.0)}), (0, 1))

    def test_a_missing_service_fails_instead_of_passing_silently(self):
        self.assertEqual(self.finish({'delivery-service': (300.0, 512.0)}), (0, 1))


class RestoreTest(unittest.TestCase):
    def tearDown(self):
        del G.FAILED[:]

    def test_a_failed_return_of_the_mode_is_reported_and_the_connection_is_still_removed(self):
        stubs = mock.Mock()
        stubs.restore_mode.side_effect = RuntimeError('заглушка недоступна')
        stubs.modes.return_value = {'seller': {'request': {'behavior': 'answer', 'answerDelayMs': 3000, 'keysDelta': 0, 'failFirst': -1, 'repeatDelayMs': 0}}}
        M.restore({'stubs': stubs, 'slug': 'sdel-1'})
        stubs.disconnect_seller.assert_called_once_with('sdel-1')
        self.assertEqual(len(G.FAILED), 1)
        self.assertIn('режим', G.FAILED[0])

    def test_the_default_mode_is_recognised_and_a_changed_one_is_not(self):
        default = {'seller': {'request': {'behavior': 'answer', 'answerDelayMs': 3000, 'keysDelta': 0, 'failFirst': -1, 'repeatDelayMs': 0}}}
        stubs = mock.Mock()
        stubs.modes.return_value = default
        before = G.PASSED
        M.seller_mode_is_default(stubs, 'проверка')
        self.assertEqual((G.PASSED - before, len(G.FAILED)), (1, 0))
        stubs.modes.return_value = {'seller': {'request': dict(default['seller']['request'], behavior='silent')}}
        M.seller_mode_is_default(stubs, 'проверка')
        self.assertEqual(len(G.FAILED), 1)


if __name__ == '__main__':
    unittest.main()
