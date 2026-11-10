# -*- coding: utf-8 -*-
"""Модульные тесты разбора в проверке webhook продавцу (webhook_checks.py, шаг 16 Ф5).

    python3 -m unittest tools/stand-checks/test_webhook_checks.py

Сеть и стенд не нужны. Проверяются паузы между попытками (ступени 1 – 5 секунд с допуском), тело webhook (ровно семь полей, сумма, время), вид списка доставок
(без тела и подписи, «новые выше») и поиск следов покупателя; ожидание строк очереди идёт на подмене чтения базы.
"""
import os
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gateway_checks as G  # noqa: E402
import webhook_checks as W  # noqa: E402

PRODUCT = '0199e0a0-0000-7000-8000-00000000a001'


def attempt(number, seconds, outcome='accepted'):
    """Попытка заглушки: время 12:00:00 плюс seconds."""
    return {'at': '2026-11-10T12:00:%06.3fZ' % seconds, 'attempt': number, 'outcome': outcome}


def body(**extra):
    base = {'eventId': 'e1', 'type': 'order.paid', 'occurredAt': '2026-11-10T12:00:00.123Z', 'orderNumber': 1042, 'productId': PRODUCT, 'quantity': 2,
            'amount': {'amount': 299800, 'currency': 'RUB'}}
    base.update(extra)
    return base


class PausesTest(unittest.TestCase):
    def test_the_ladder_one_two_three_four_five_passes(self):
        times = [0, 1.2, 3.4, 6.5, 10.8, 16.1]
        attempts = [attempt(i + 1, t, 'failed:500') for i, t in enumerate(times)]
        self.assertEqual(W.pause_problems(attempts), [])
        self.assertEqual([round(g, 1) for g in W.gaps(attempts)], [1.2, 2.2, 3.1, 4.3, 5.3])

    def test_a_pause_shorter_than_the_step_is_a_failure(self):
        attempts = [attempt(1, 0, 'failed:503'), attempt(2, 0.4, 'failed:503'), attempt(3, 2.8)]
        self.assertEqual(W.pause_problems(attempts), [(1, 0.4, 1)])

    def test_a_pause_much_longer_than_the_step_is_a_failure(self):
        attempts = [attempt(1, 0, 'failed:503'), attempt(2, 1.1, 'failed:503'), attempt(3, 9.0)]
        self.assertEqual(W.pause_problems(attempts), [(2, 7.9, 2)])

    def test_a_seventh_attempt_has_no_step_and_is_a_failure(self):
        attempts = [attempt(i + 1, sum([1, 2, 3, 4, 5, 6][:i]) + 0.3 * i) for i in range(7)]
        self.assertEqual([p[0] for p in W.pause_problems(attempts)], [6])

    def test_one_attempt_has_no_pauses(self):
        self.assertEqual(W.gaps([attempt(1, 0)]), [])
        self.assertEqual(W.pause_problems([attempt(1, 0)]), [])


class BodyTest(unittest.TestCase):
    def check(self, b):
        return W.body_problems(b, 1042, PRODUCT, 2, 'order.paid')

    def test_a_correct_body_has_no_problems(self):
        self.assertEqual(self.check(body()), [])

    def test_an_extra_buyer_field_is_a_problem(self):
        self.assertTrue(any('поля' in p for p in self.check(body(email='buyer@example.test'))))

    def test_a_missing_field_is_a_problem(self):
        broken = body()
        del broken['quantity']
        self.assertTrue(self.check(broken))

    def test_wrong_values_are_named(self):
        text = ' '.join(self.check(body(orderNumber=1043, quantity=3, type='order.issued', productId='x')))
        for part in ('orderNumber', 'quantity', 'type', 'productId'):
            self.assertIn(part, text)

    def test_the_amount_must_be_an_integer_in_minor_units_with_rub(self):
        for amount in ({'amount': '299800', 'currency': 'RUB'}, {'amount': 0, 'currency': 'RUB'}, {'amount': 100, 'currency': 'USD'}, 'много', None):
            self.assertTrue(any('amount' in p for p in self.check(body(amount=amount))), amount)

    def test_the_time_must_parse(self):
        self.assertTrue(any('occurredAt' in p for p in self.check(body(occurredAt='вчера'))))
        self.assertTrue(any('occurredAt' in p for p in self.check(body(occurredAt=None))))

    def test_a_non_object_is_a_problem(self):
        self.assertEqual(self.check([1]), ['тело не объект'])


class ListingTest(unittest.TestCase):
    def item(self, **extra):
        base = {'deliveryId': 'd', 'eventId': 'e', 'type': 'order.paid', 'orderNumber': 1042, 'status': 'failed', 'attempts': 6, 'lastResponseCode': 500,
                'lastAttemptAt': '2026-11-10T12:00:16.000Z', 'nextAttemptAt': None, 'createdAt': '2026-11-10T12:00:00.000Z'}
        base.update(extra)
        return base

    def test_new_first_and_known_fields_pass(self):
        items = [self.item(createdAt='2026-11-10T12:00:05.000Z'), self.item(createdAt='2026-11-10T12:00:00.000Z')]
        self.assertEqual(W.listing_problems(items), [])

    def test_the_oldest_first_is_a_problem(self):
        items = [self.item(createdAt='2026-11-10T12:00:00.000Z'), self.item(createdAt='2026-11-10T12:00:05.000Z')]
        self.assertEqual(W.listing_problems(items), ['порядок не «новые выше»'])

    def test_a_body_or_a_signature_in_the_list_is_a_problem(self):
        for field in ('payload', 'body', 'signature', 'endpointUrl'):
            self.assertTrue(any('лишние' in p for p in W.listing_problems([self.item(**{field: 'x'})])), field)

    def test_an_empty_list_has_no_problems(self):
        self.assertEqual(W.listing_problems([]), [])


class TracesTest(unittest.TestCase):
    class Buyer:
        email = 'buyer-1@example.test'
        id = '0199e0a0-0000-7000-8000-00000000b001'

    def test_an_address_and_an_id_are_found(self):
        self.assertEqual(len(W.buyer_traces([self.Buyer], '{"x":"buyer-1@example.test"}')), 1)
        self.assertEqual(len(W.buyer_traces([self.Buyer], 'id ' + self.Buyer.id)), 1)
        self.assertEqual(len(W.buyer_traces([self.Buyer], self.Buyer.email + ' ' + self.Buyer.id)), 2)

    def test_a_clean_text_has_no_traces(self):
        self.assertEqual(W.buyer_traces([self.Buyer], '{"eventId":"e1","orderNumber":1042}'), [])


class WaitRowsTest(unittest.TestCase):
    def row(self, kind, status):
        return {'eventId': kind, 'type': kind, 'status': status, 'attempts': 1, 'code': '200', 'error': '', 'queued': False, 'payload': '{}'}

    def test_returns_when_all_types_have_an_accepted_status(self):
        rows = [self.row('order.paid', 'sent'), self.row('order.issued', 'sent')]
        with mock.patch.object(W, 'rows_of', return_value=rows):
            ok, by_type, _ = W.wait_rows(1042, ('order.paid', 'order.issued'), ('sent',), wait=0)
        self.assertTrue(ok)
        self.assertEqual(set(by_type), {'order.paid', 'order.issued'})

    def test_a_missing_type_or_a_queued_status_is_not_ready(self):
        with mock.patch.object(W, 'rows_of', return_value=[self.row('order.paid', 'sent')]):
            self.assertFalse(W.wait_rows(1042, ('order.paid', 'order.issued'), ('sent',), wait=0)[0])
        with mock.patch.object(W, 'rows_of', return_value=[self.row('order.paid', 'queued')]):
            self.assertFalse(W.wait_rows(1042, ('order.paid',), ('sent',), wait=0)[0])

    def test_an_unanswered_database_is_not_ready(self):
        with mock.patch.object(W, 'rows_of', return_value=None):
            self.assertFalse(W.wait_rows(1042, ('order.paid',), ('sent',), wait=0)[0])


class ModeTest(unittest.TestCase):
    def tearDown(self):
        del G.FAILED[:]

    def test_reset_mode_keeps_the_connection(self):
        stubs = mock.Mock()
        stubs.modes.return_value = {'seller': {'request': {'behavior': 'answer', 'answerDelayMs': 3000, 'keysDelta': 0, 'failFirst': -1, 'repeatDelayMs': 0}}}
        W.reset_mode({'stubs': stubs})
        stubs.restore_mode.assert_called_once_with('seller')
        stubs.disconnect_seller.assert_not_called()


if __name__ == '__main__':
    unittest.main()
