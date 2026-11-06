# -*- coding: utf-8 -*-
"""Модульные тесты помощников проверки API-ключей и шлюза (seller_api_checks.py, шаг 7 Ф5).

    python3 -m unittest tools/stand-checks/test_seller_api_checks.py

Сеть и стенд не нужны. Главное: расчёт секрета подписи в проверке даёт те же значения, что Java (SellerSecretsTest, известные ответы
для мастер-секрета тестов), иначе проверка «секрет равен HMAC мастера» сравнивала бы с чужим расчётом. Остальное: формы значения ключа и
секрета, чтение мастер-секрета без перевода строки, образцы поиска, различие отказов, разбор Retry-After, и что ответы, где секрет показан
по замыслу, в набор поиска не попадают.
"""
import base64
import os
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gateway_checks as G  # noqa: E402
import seller_api_checks as M  # noqa: E402

MASTER = b'master-for-test-vectors-only'          # SellerSecretsTest.MASTER
SELLER_A = '5b7f0e1a-3c4d-4e5f-8a6b-7c8d9e0f1a2b'
SELLER_B = '9d8c7b6a-5f4e-4d3c-b2a1-0f9e8d7c6b5a'
A_V1 = '472be46cea9be46b54ed35049fe32e8bc331fe4560ed380ad16b13739f7324e6'
A_V2 = '4893651dbeea4d5742f1d9e5e1451cd8e749ede1fc7b7e66769f91b63c547498'
B_V1 = '4ae337e88a5d8f9e6ae114008d824d045e53968f69c5a09e195c3c6c8a9d1321'


def resp(status, body=b'{}', headers=()):
    return G.Resp(status, list(headers), body)


class DerivationTest(unittest.TestCase):
    def test_the_secret_is_what_the_java_service_derives(self):
        self.assertEqual(M.derived(MASTER, SELLER_A, 1), A_V1)
        self.assertEqual(M.derived(MASTER, SELLER_A, 2), A_V2)
        self.assertEqual(M.derived(MASTER, SELLER_B, 1), B_V1)

    def test_the_version_and_the_seller_change_the_secret(self):
        self.assertNotEqual(M.derived(MASTER, SELLER_A, 1), M.derived(MASTER, SELLER_A, 2))
        self.assertNotEqual(M.derived(MASTER, SELLER_A, 1), M.derived(MASTER, SELLER_B, 1))
        self.assertRegex(M.derived(MASTER, SELLER_A, 1), M.SECRET_RE)


class ShapesTest(unittest.TestCase):
    def test_the_key_has_the_prefix_and_forty_url_safe_characters(self):
        good = 'dgm_' + base64.urlsafe_b64encode(os.urandom(30)).decode().rstrip('=')
        self.assertRegex(good, M.KEY_RE)
        self.assertIsNone(M.KEY_RE.match(good[:-1]))
        self.assertIsNone(M.KEY_RE.match(good + 'a'))
        self.assertIsNone(M.KEY_RE.match(good[4:]))
        self.assertIsNone(M.KEY_RE.match('Bearer ' + good))

    def test_the_secret_is_sixty_four_lowercase_hex_characters(self):
        self.assertRegex('a' * 64, M.SECRET_RE)
        self.assertIsNone(M.SECRET_RE.match('A' * 64))
        self.assertIsNone(M.SECRET_RE.match('a' * 63))

    def test_fresh_keys_are_distinct_and_marked(self):
        keys = M.fresh_keys(50)
        self.assertEqual(len(set(keys)), 50)
        self.assertTrue(all(k.startswith('SAPI-') for k in keys))


class MasterSecretTest(unittest.TestCase):
    def read_with(self, content):
        with tempfile.TemporaryDirectory() as directory:
            if content is not None:
                with open(os.path.join(directory, 'seller_webhook_master'), 'wb') as f:
                    f.write(content)
            with mock.patch.object(G, 'SECRETS', directory):
                return M.master_secret()

    def test_the_trailing_line_break_is_not_part_of_the_master(self):
        self.assertEqual(self.read_with(MASTER + b'\n'), MASTER)
        self.assertEqual(self.read_with(MASTER + b'\r\n'), MASTER)
        self.assertEqual(self.read_with(MASTER), MASTER)

    def test_a_missing_file_gives_none_instead_of_an_exception(self):
        self.assertIsNone(self.read_with(None))


class SearchSamplesTest(unittest.TestCase):
    def test_samples_cover_the_value_its_hex_and_both_base64_forms_but_not_a_shared_prefix(self):
        value = 'dgm_' + 'a-_' * 13 + 'b'
        samples = M.needles([value])
        self.assertIn(value, samples)
        self.assertIn(value.encode().hex(), samples)
        self.assertIn(base64.b64encode(value.encode()).decode(), samples)
        self.assertIn(base64.urlsafe_b64encode(value.encode()).decode().rstrip('='), samples)
        self.assertFalse(any(s in ('CANARY-', 'dgm_') for s in samples), 'общий префикс нашёл бы чужие значения и сам префикс ключа')


class ResponseHelpersTest(unittest.TestCase):
    def test_refusals_are_told_apart_by_status_code_title_and_detail_only(self):
        one = resp(401, b'{"status":401,"code":"unauthenticated","title":"t","detail":"d","traceId":"1"}')
        two = resp(401, b'{"status":401,"code":"unauthenticated","title":"t","detail":"d","traceId":"2"}')
        three = resp(401, b'{"status":401,"code":"unauthenticated","title":"t","detail":"other"}')
        self.assertEqual(M.problem(one), M.problem(two))
        self.assertNotEqual(M.problem(one), M.problem(three))

    def test_a_body_that_is_not_json_is_still_comparable(self):
        self.assertEqual(M.problem(resp(502, b'<html>')), (502, None, None, None))

    def test_retry_after_is_read_as_seconds_or_none(self):
        self.assertEqual(M.retry_after(resp(429, headers=[('Retry-After', '7')])), 7)
        self.assertIsNone(M.retry_after(resp(429)))
        self.assertIsNone(M.retry_after(resp(429, headers=[('Retry-After', 'soon')])))

    def test_only_responses_without_a_deliberately_shown_secret_go_to_the_search_set(self):
        M.SEEN.clear()
        with mock.patch.object(G, 'call', return_value=resp(201, b'{"value":"dgm_x"}')):
            M.send('POST', M.KEYS, {}, {'name': 'a'}, legit=True)
            self.assertEqual(M.SEEN, [])
            M.send('GET', M.KEYS, {})
            self.assertEqual(len(M.SEEN), 1)
        M.SEEN.clear()

    def test_the_stand_calls_carry_the_idempotency_key_on_post_only_and_the_key_header(self):
        seen = {}

        def fake(method, path, headers=None, body=None, **kw):
            seen[method] = (path, dict(headers or {}), body)
            return resp(200)
        with mock.patch.object(G, 'call', side_effect=fake):
            M.by_key('dgm_k', 'POST', '/p', {'keys': ['a']})
            M.by_key('dgm_k', 'GET', '/g')
            M.by_key('dgm_k', 'POST', '/f', b'a\r\n', **{'Content-Type': 'text/csv'})
        M.SEEN.clear()
        self.assertEqual(seen['GET'][1], {'X-Api-Key': 'dgm_k'})
        self.assertIn('Idempotency-Key', seen['POST'][1])
        self.assertEqual(seen['POST'][1]['X-Api-Key'], 'dgm_k')
        self.assertEqual(seen['POST'][2], b'a\r\n', 'тело файла уходит как есть')
        self.assertEqual(seen['POST'][1]['Content-Type'], 'text/csv', 'тип файла не заменяется на JSON')

    def test_limits_of_the_checks_are_the_real_ones_not_the_stand_ones(self):
        self.assertEqual((M.UPLOAD_BUDGET, M.KEY_BUDGET, M.CACHE_SECONDS), (6, 600, 30))


if __name__ == '__main__':
    unittest.main()
