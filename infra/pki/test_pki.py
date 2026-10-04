#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Тесты скрипта выпуска сертификатов и секретов. Запуск: python3 -m unittest infra/pki/test_pki.py -v
Все файлы создаются во временных каталогах, репозиторий и реальные секреты не затрагиваются."""
import datetime as dt
import importlib.util
import os
import stat
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location('dgm_pki', os.path.join(HERE, 'dgm_pki.py'))
pki = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pki)


def read_text(path):
    with open(path, encoding='utf-8') as f:
        return f.read()


class PkiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.pki_dir = os.path.join(cls.tmp.name, 'pki')
        cls.sec_dir = os.path.join(cls.tmp.name, 'secrets')
        os.environ['DGM_PKI_DIR'] = cls.pki_dir
        os.environ['DGM_SECRETS_DIR'] = cls.sec_dir
        cls.inv = pki.load_inventory()
        assert pki.main(['certs']) == 0
        assert pki.main(['secrets']) == 0

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()
        os.environ.pop('DGM_PKI_DIR', None)
        os.environ.pop('DGM_SECRETS_DIR', None)

    def path(self, name):
        return os.path.join(self.sec_dir, name)

    # --- сертификаты
    def test_verify_passes_on_fresh_stand(self):
        self.assertEqual(pki.main(['verify']), 0)

    def test_every_container_has_valid_chain_and_both_purposes(self):
        for c in self.inv['containers']:
            crt = self.path('tls_%s.crt' % c['name'])
            self.assertIsNone(pki.verify_chain(crt, self.path('tls_ca.crt')), c['name'])
            info = pki.parse_cert(crt)
            self.assertEqual(info['eku'], (True, True), c['name'])

    def test_algorithm_is_ecdsa_p256_and_san_has_container_name(self):
        for c in self.inv['containers']:
            info = pki.parse_cert(self.path('tls_%s.crt' % c['name']))
            self.assertEqual((info['alg'], info['curve']), ('ecdsa', 'prime256v1'))
            self.assertIn('DNS:%s' % c['name'], info['san'])
            self.assertIn('DNS:localhost', info['san'])
            self.assertEqual(info['cn'], c['name'])

    def test_lifetime_is_90_days(self):
        info = pki.parse_cert(self.path('tls_order-service.crt'))
        self.assertEqual((info['not_after'] - info['not_before']).days, 90)

    def test_ca_key_never_reaches_container_secrets(self):
        names = os.listdir(self.sec_dir)
        self.assertNotIn('ca.key', names)
        self.assertNotIn('tls_ca.key', names)
        self.assertEqual(stat.S_IMODE(os.stat(os.path.join(self.pki_dir, 'ca.key')).st_mode), 0o400)
        self.assertNotIn('PRIVATE KEY', read_text(self.path('tls_ca.crt')))

    def test_certificate_of_foreign_ca_is_rejected(self):
        with tempfile.TemporaryDirectory() as other:
            env = dict(os.environ, DGM_PKI_DIR=os.path.join(other, 'pki'), DGM_SECRETS_DIR=os.path.join(other, 'sec'))
            subprocess.run([sys.executable, os.path.join(HERE, 'dgm_pki.py'), 'certs'], env=env, check=True, capture_output=True)
            foreign = os.path.join(other, 'sec', 'tls_order-service.crt')
            self.assertIsNotNone(pki.verify_chain(foreign, self.path('tls_ca.crt')))

    def test_verify_detects_key_that_does_not_match_certificate(self):
        a, b = self.path('tls_redis.key'), self.path('tls_kafka.key')
        saved = read_text(a)
        try:
            pki.write_file(a, read_text(b), 0o444)
            self.assertEqual(pki.main(['verify']), 1)
        finally:
            pki.write_file(a, saved, 0o444)
        self.assertEqual(pki.main(['verify']), 0)

    def test_expiring_certificate_is_renewed_and_valid_one_is_kept(self):
        name = 'web-app'
        before = read_text(self.path('tls_%s.crt' % name))
        self.assertEqual(pki.main(['certs']), 0)
        self.assertEqual(read_text(self.path('tls_%s.crt' % name)), before, 'действующий сертификат не должен перевыпускаться')
        pki.issue(name, [], 10, self.inv)       # остаётся 10 дней, меньше порога 30
        short = read_text(self.path('tls_%s.crt' % name))
        self.assertEqual(pki.main(['verify']), 1, 'меньше 14 дней до конца срока должно быть замечено')
        self.assertEqual(pki.main(['certs']), 0)
        renewed = read_text(self.path('tls_%s.crt' % name))
        self.assertNotEqual(renewed, short)
        self.assertGreater(pki.days_left(pki.parse_cert(self.path('tls_%s.crt' % name))), 85)

    # --- секреты
    def test_all_inventory_secrets_exist_unique_and_long(self):
        values = {}
        for s in self.inv['secrets']:
            v = read_text(self.path(s['name']))
            self.assertGreaterEqual(len(v), 32, s['name'])
            self.assertEqual(v, v.strip(), s['name'])
            self.assertNotIn(v, values, 'дубликат %s и %s' % (s['name'], values.get(v)))
            values[v] = s['name']

    def test_secret_files_have_expected_mode_and_directory_is_private(self):
        self.assertEqual(stat.S_IMODE(os.stat(self.sec_dir).st_mode), 0o700)
        self.assertEqual(stat.S_IMODE(os.stat(self.path('inventory_kek')).st_mode), 0o444)

    def test_rerun_keeps_secrets_and_force_replaces_them(self):
        before = read_text(self.path('db_app_orders'))
        self.assertEqual(pki.main(['secrets']), 0)
        self.assertEqual(read_text(self.path('db_app_orders')), before)
        self.assertEqual(pki.main(['secrets', '--force']), 0)
        self.assertNotEqual(read_text(self.path('db_app_orders')), before)

    def test_every_reader_is_a_known_container_or_postgres_like_service(self):
        known = {c['name'] for c in self.inv['containers']} | {j['name'] for j in self.inv.get('jobs', [])}
        for s in self.inv['secrets']:
            for r in s['readers']:
                self.assertIn(r, known, '%s читает неизвестный контейнер %s' % (s['name'], r))

    def test_secret_values_never_stored_in_git_ignored_paths_only(self):
        for p in ('secrets/db_app_orders', 'secrets/tls_order-service.key', '.pki/ca.key'):
            r = subprocess.run(['git', 'check-ignore', '-q', p], cwd=os.path.join(HERE, '..', '..'))
            self.assertEqual(r.returncode, 0, '%s должен быть в .gitignore' % p)


if __name__ == '__main__':
    unittest.main()
