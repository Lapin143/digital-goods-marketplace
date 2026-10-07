#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Модульные тесты infra/keycloak/enable_admin_console.py: Keycloak подменяется заглушкой, сеть не нужна (make keycloak-test)."""
import contextlib
import importlib.util
import io
import os
import sys
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import kc_client as K

SPEC = importlib.util.spec_from_file_location('enable_admin_console', os.path.join(HERE, '..', '..', 'infra', 'keycloak', 'enable_admin_console.py'))
M = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(M)

URL = 'https://localhost:18445/auth'


class FakeAdmin:
    def __init__(self, realm, get_status=200, put_status=204):
        self.realm, self.get_status, self.put_status, self.calls = realm, get_status, put_status, []

    def call(self, method, path, json_body=None):
        self.calls.append((method, path, json_body))
        if method == 'GET':
            return K.Response(self.get_status, {}, __import__('json').dumps(self.realm).encode())
        return K.Response(self.put_status, {}, b'')


def run(admin, *argv):
    with mock.patch.object(K, 'from_env', return_value=(None, admin)) as fe, mock.patch.object(sys, 'argv', ['enable_admin_console.py', *argv]):
        with contextlib.redirect_stdout(io.StringIO()):
            M.main()
        return fe.call_args[0][0]


class EnableAdminConsole(unittest.TestCase):
    def test_sets_frontend_url_and_keeps_other_attributes(self):
        admin = FakeAdmin({'realm': 'master', 'attributes': {'actionTokenGeneratedByAdminLifespan': '43200'}})
        env = run(admin)
        put = [c for c in admin.calls if c[0] == 'PUT'][0]
        self.assertEqual(put[1], '/realms/master')
        self.assertEqual(put[2]['attributes'], {'actionTokenGeneratedByAdminLifespan': '43200', 'frontendUrl': URL})
        self.assertEqual(env['KC_PUBLIC'], URL)
        self.assertNotIn('KC_TARGET', env)

    def test_repeat_does_nothing(self):
        admin = FakeAdmin({'realm': 'master', 'attributes': {'frontendUrl': URL}})
        run(admin)
        self.assertEqual([c[0] for c in admin.calls], ['GET'])

    def test_off_removes_only_frontend_url(self):
        admin = FakeAdmin({'realm': 'master', 'attributes': {'frontendUrl': URL, 'x': '1'}})
        run(admin, '--off')
        put = [c for c in admin.calls if c[0] == 'PUT'][0]
        self.assertEqual(put[2]['attributes'], {'x': '1'})

    def test_off_when_not_set_does_nothing(self):
        admin = FakeAdmin({'realm': 'master'})
        run(admin, '--off')
        self.assertEqual([c[0] for c in admin.calls], ['GET'])

    def test_unavailable_keycloak_is_reported(self):
        with self.assertRaises(SystemExit) as e:
            run(FakeAdmin({}, get_status=502))
        self.assertIn('realm master: 502', str(e.exception))

    def test_rejected_update_is_reported(self):
        with self.assertRaises(SystemExit) as e:
            run(FakeAdmin({'realm': 'master'}, put_status=403))
        self.assertIn('403', str(e.exception))


if __name__ == '__main__':
    unittest.main()
