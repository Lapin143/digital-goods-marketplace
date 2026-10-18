#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Модульные тесты клиента управления заглушками (сеть не нужна): режим возвращается к умолчаниям даже при исключении, команды идут по нужным путям.

    python3 -m unittest tools/stand-checks/test_stubs_admin.py
"""
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import stubs_admin as S  # noqa: E402


class FakeResponse:
    def __init__(self, status, body):
        self.status = status
        self.text = body if isinstance(body, str) else json.dumps(body)

    def json(self):
        return json.loads(self.text)


class FakeNet:
    def __init__(self, replies=None):
        self.calls = []
        self.replies = replies or {}

    def request(self, method, url, headers=None, body=None, **_):
        self.calls.append((method, url.replace(S.ADMIN_URL, ''), json.loads(body) if body else None))
        key = (method, url.replace(S.ADMIN_URL, '').split('?')[0])
        reply = self.replies.get(key, {})
        if callable(reply):
            reply = reply()
        return reply if isinstance(reply, FakeResponse) else FakeResponse(200, reply)


class StubsAdminTest(unittest.TestCase):
    def setUp(self):
        self.net = FakeNet()
        self.admin = S.StubsAdmin(self.net, poll_seconds=0.01)

    def test_mode_is_restored_after_block(self):
        with self.admin.payment_mode(scenario='hold', delayMs=0):
            pass
        self.assertEqual(self.net.calls, [('PUT', '/admin/modes/payment', {'scenario': 'hold', 'delayMs': 0}), ('PUT', '/admin/modes/payment', {})])

    def test_mode_is_restored_after_exception(self):
        with self.assertRaises(ValueError):
            with self.admin.email_mode(behavior='temp_error'):
                raise ValueError('сценарий упал')
        self.assertEqual(self.net.calls[-1], ('PUT', '/admin/modes/email', {}))

    def test_unknown_system_is_rejected_before_request(self):
        with self.assertRaises(ValueError):
            self.admin.set_mode('bank', scenario='x')
        self.assertEqual(self.net.calls, [])

    def test_payment_commands_paths(self):
        self.admin.confirm_payment('pay 1', repeat=2)
        self.admin.complete_refund('p2')
        self.admin.resend_webhook('p3', 'payment.paid', badSignature=True)
        self.assertEqual(self.net.calls, [
            ('POST', '/admin/payments/pay%201/confirm', {'repeat': 2}),
            ('POST', '/admin/payments/p2/refund-complete', {}),
            ('POST', '/admin/payments/p3/webhook', {'badSignature': True, 'type': 'payment.paid'}),
        ])

    def test_error_status_raises_with_context(self):
        net = FakeNet({('POST', '/admin/reset'): FakeResponse(500, 'сбой')})
        with self.assertRaises(S.StubsError) as ctx:
            S.StubsAdmin(net).reset()
        self.assertIn('код 500', str(ctx.exception))

    def test_wait_email_returns_when_enough(self):
        answers = iter([{'items': []}, {'items': [{'to': 'a'}]}])
        net = FakeNet({('GET', '/admin/emails'): lambda: next(answers)})
        found = S.StubsAdmin(net, poll_seconds=0.01).wait_email(to='a', wait=5)
        self.assertEqual(found, [{'to': 'a'}])
        self.assertEqual(net.calls[0][1], '/admin/emails?to=a')

    def test_wait_email_times_out_with_explanation(self):
        net = FakeNet({('GET', '/admin/emails'): {'items': []}})
        with self.assertRaises(S.StubsError) as ctx:
            S.StubsAdmin(net, poll_seconds=0.01).wait_email(count=2, wait=0.05, to='b')
        self.assertIn('найдено 0 из 2', str(ctx.exception))

    def test_find_payment_by_predicate(self):
        net = FakeNet({('GET', '/admin/payments'): {'items': [{'paymentId': 'a', 'orderId': 'o1'}, {'paymentId': 'b', 'orderId': 'o2'}]}})
        found = S.StubsAdmin(net, poll_seconds=0.01).find_payment(lambda p: p['orderId'] == 'o2', wait=1)
        self.assertEqual(found['paymentId'], 'b')


if __name__ == '__main__':
    unittest.main()
