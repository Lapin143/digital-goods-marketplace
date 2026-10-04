# -*- coding: utf-8 -*-
"""Модульные тесты клиента входа Keycloak (kc_client.py): то, на чём держатся проверки стенда, должно быть верным само по себе.

    python3 -m unittest tools/stand-checks/test_kc_client.py

Сеть не нужна. Проверяются: TOTP и HOTP по векторам из RFC 4226 и RFC 6238, выбор шага без повторного кода, пара PKCE, разбор JWT,
разбор страниц Keycloak (какая это страница, текст ошибки), таблица маршрутов.
"""
import base64
import hashlib
import json
import os
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import kc_client as K

RFC_KEY = b'12345678901234567890'


class OtpTest(unittest.TestCase):
    def test_hotp_rfc4226(self):
        want = ['755224', '287082', '359152', '969429', '338314', '254676', '287922', '162583', '399871', '520489']
        self.assertEqual([K.totp_code(RFC_KEY, c) for c in range(10)], want)

    def test_totp_rfc6238_sha1(self):
        # время в секундах, ожидаемый восьмизначный код (приложение B RFC 6238, SHA-1)
        for t, code in [(59, '94287082'), (1111111109, '07081804'), (1111111111, '14050471'),
                        (1234567890, '89005924'), (2000000000, '69279037'), (20000000000, '65353130')]:
            self.assertEqual(K.totp_code(RFC_KEY, t // 30, digits=8), code, t)

    def test_wrong_secret_gives_other_code(self):
        self.assertNotEqual(K.totp_code(RFC_KEY, 1), K.totp_code(b'12345678901234567891', 1))

    def test_clock_never_repeats_a_code(self):
        clock = K.TotpClock()
        now = lambda: 1000 * 30 + 5.0
        with mock.patch.object(K.time, 'sleep') as sleep:
            first = clock.next_code('secret', now=now)
            second = clock.next_code('secret', now=now)       # тот же шаг занят: берётся следующий, ждать не нужно
            self.assertEqual(sleep.call_count, 0)
        self.assertEqual(first, K.totp_code(b'secret', 1000))
        self.assertEqual(second, K.totp_code(b'secret', 1001))

    def test_clock_waits_when_two_steps_ahead(self):
        clock = K.TotpClock()
        clock.used['secret'] = 1001
        with mock.patch.object(K.time, 'sleep') as sleep:
            code = clock.next_code('secret', now=lambda: 1000 * 30 + 5.0)
        self.assertEqual(sleep.call_count, 1)
        self.assertGreater(sleep.call_args[0][0], 20)       # до начала следующего шага (25 с) и немного сверх
        self.assertEqual(code, K.totp_code(b'secret', 1002))

    def test_clock_secrets_are_independent(self):
        clock = K.TotpClock()
        now = lambda: 90000.0
        a = clock.next_code('one', now=now)
        b = clock.next_code('two', now=now)
        self.assertEqual(a, K.totp_code(b'one', 3000))
        self.assertEqual(b, K.totp_code(b'two', 3000))


class PkceJwtTest(unittest.TestCase):
    def test_pkce_pair(self):
        v, c = K.pkce_pair()
        self.assertRegex(v, r'^[A-Za-z0-9\-._~]{43,128}$')
        self.assertEqual(c, base64.urlsafe_b64encode(hashlib.sha256(v.encode()).digest()).rstrip(b'=').decode())
        self.assertNotEqual(K.pkce_pair()[0], v)

    def test_decode_jwt(self):
        enc = lambda o: K.b64url(json.dumps(o).encode())
        head, body = {'alg': 'RS256', 'kid': 'k1'}, {'sub': 'u', 'aud': ['dgm-api'], 'amr': ['pwd', 'otp'], 'имя': 'значение'}
        token = '%s.%s.sig' % (enc(head), enc(body))
        self.assertEqual(K.decode_jwt(token), (head, body))


LOGIN = '<form id="kc-form-login" action="/a"><input name="username" value=""><input name="password" type="password"><input type="submit" name="login"></form>'
OTP = '<form id="kc-otp-login-form" action="/b"><input name="otp"><input type="hidden" name="credentialId" value="x"></form>'
SETUP = '<form id="kc-totp-settings-form" action="/c"><input type="hidden" name="totpSecret" value="S"><input name="totp"></form>'
ERROR = '<div id="kc-error-message"><p class="instruction">Вход по коду из SMS пока недоступен.</p></div>'
REGISTER = '<form action="/d"><input name="password"><input name="password-confirm"></form>'


class PagesTest(unittest.TestCase):
    def test_page_kind(self):
        self.assertEqual(K.page_kind(LOGIN), 'login')
        self.assertEqual(K.page_kind(OTP), 'otp')
        self.assertEqual(K.page_kind(SETUP), 'totp_setup')
        self.assertEqual(K.page_kind(ERROR), 'error')
        self.assertEqual(K.page_kind('<html><body>ok</body></html>'), 'other')

    def test_forms_skip_buttons_and_keep_hidden_values(self):
        f = K.forms_of(OTP)[0]
        self.assertEqual(f['action'], '/b')
        self.assertEqual(f['inputs'], {'otp': '', 'credentialId': 'x'})
        self.assertNotIn('login', K.forms_of(LOGIN)[0]['inputs'])

    def test_page_message(self):
        self.assertEqual(K.page_message(ERROR), 'Вход по коду из SMS пока недоступен.')
        self.assertEqual(K.page_message(LOGIN), '')


class RoutesTest(unittest.TestCase):
    def test_route_matches_prefix_only(self):
        r = K.Route('localhost', 8443, '/auth', 'https://127.0.0.1:18445')
        self.assertTrue(r.matches('localhost', 8443, '/auth/realms/dgm'))
        self.assertFalse(r.matches('localhost', 8443, '/vkid/authorize'))
        self.assertFalse(r.matches('localhost', 9443, '/auth'))

    def test_from_env_routes_public_address_to_debug_ports(self):
        kc, admin = K.from_env({'KC_PUBLIC': 'https://localhost:8443/auth', 'KC_TARGET': 'https://127.0.0.1:18445',
                                'STUBS_TARGET': 'https://127.0.0.1:18443', 'STUBS_ADMIN_TARGET': 'https://127.0.0.1:18444',
                                'KC_ADMIN_PASSWORD': 'x', 'KC_CA': '/нет/такого/файла'})
        net = kc.net
        self.assertEqual(net._route('https', 'localhost', 8443, '/auth/realms/dgm'), ('https', ('127.0.0.1', 18445)))
        self.assertEqual(net._route('https', 'localhost', 8443, '/vkid/authorize'), ('https', ('127.0.0.1', 18443)))
        self.assertEqual(net._route('https', 'external-stubs', 8444, '/admin/x'), ('https', ('127.0.0.1', 18444)))
        self.assertEqual(net._route('https', 'example.org', 443, '/'), ('https', ('example.org', 443)))
        self.assertEqual(kc.issuer, 'https://localhost:8443/auth/realms/dgm')


if __name__ == '__main__':
    unittest.main()
