#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Модульные тесты kek_rotate.py (шаг 22 Ф4, TC-414): разбор `.env`, оценка состояния ротации, порядок команд и защита от преждевременного завершения.

    python3 -m unittest tools/ops/test_kek_rotate.py

Docker и база не нужны: `run`, `db_counts` и ожидание сервиса подменены, каталог секретов и `.env` временные.
"""
import base64
import io
import os
import re
import stat
import sys
import tempfile
import unittest
from contextlib import redirect_stdout, redirect_stderr
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import kek_rotate as K  # noqa: E402

REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
OLD_KEY = 'b2xkLWtleS1vbGQta2V5LW9sZC1rZXktb2xkLWtleQ=='
NEW_KEY = 'bmV3LWtleS1uZXcta2V5LW5ldy1rZXktbmV3LWtleQ=='


class EnvText(unittest.TestCase):
    def test_get_default_and_last_line_wins(self):
        self.assertEqual(K.env_get('', 'DGM_KEK_VERSION', '1'), '1')
        self.assertEqual(K.env_get('A=1\nDGM_KEK_VERSION=2\nDGM_KEK_VERSION=3\n', 'DGM_KEK_VERSION'), '3')
        self.assertEqual(K.env_get('DGM_KEK_VERSION="4"', 'DGM_KEK_VERSION'), '4')

    def test_set_replaces_in_place_and_keeps_the_rest(self):
        text = '# заметка\nDGM_RESTART=no\nDGM_KEK_VERSION=1\nIMAGE_TAG=dev\n'
        self.assertEqual(K.env_set(text, 'DGM_KEK_VERSION', 2), '# заметка\nDGM_RESTART=no\nDGM_KEK_VERSION=2\nIMAGE_TAG=dev\n')

    def test_set_appends_and_collapses_duplicates(self):
        self.assertEqual(K.env_set('A=1', 'DGM_KEK_ROTATE', 'true'), 'A=1\nDGM_KEK_ROTATE=true\n')
        self.assertEqual(K.env_set('X=1\nX=2\nB=3\n', 'X', 9), 'X=9\nB=3\n')
        self.assertEqual(K.env_set('', 'X', 1), 'X=1\n')

    def test_similar_names_are_not_touched(self):
        text = 'DGM_KEK_VERSION_OLD=7\n'
        self.assertEqual(K.env_get(text, 'DGM_KEK_VERSION'), None)
        self.assertEqual(K.env_set(text, 'DGM_KEK_VERSION', 2), 'DGM_KEK_VERSION_OLD=7\nDGM_KEK_VERSION=2\n')


class Counts(unittest.TestCase):
    def test_parse(self):
        self.assertEqual(K.parse_counts('1|3\n2|1\n'), {1: 3, 2: 1})
        self.assertEqual(K.parse_counts('\n  \n'), {})

    def test_parse_rejects_garbage(self):
        with self.assertRaises(ValueError):
            K.parse_counts('ERROR: нет доступа\n')


class Classify(unittest.TestCase):
    def test_idle(self):
        self.assertEqual(K.classify(1, {1: 4}, False)[0], K.IDLE)
        self.assertEqual(K.classify(1, {}, False)[0], K.IDLE)

    def test_prepared_while_old_deks_remain(self):
        self.assertEqual(K.classify(1, {1: 2, 2: 1}, True)[0], K.PREPARED)
        self.assertEqual(K.classify(1, {1: 3}, True)[0], K.PREPARED)

    def test_rewrapped_only_when_nothing_is_left_on_the_old_version(self):
        self.assertEqual(K.classify(1, {2: 4}, True)[0], K.REWRAPPED)
        self.assertEqual(K.classify(1, {}, True)[0], K.REWRAPPED)

    def test_broken_when_deks_are_on_unknown_version(self):
        state, why = K.classify(1, {1: 1, 3: 1}, True)
        self.assertEqual(state, K.BROKEN)
        self.assertIn('3', why)
        self.assertEqual(K.classify(2, {1: 1}, False)[0], K.BROKEN)

    def test_broken_when_next_secret_was_removed_too_early(self):
        state, why = K.classify(1, {2: 1}, False)
        self.assertEqual(state, K.BROKEN)
        self.assertIn(K.KEK_NEXT, why)

    def test_text_never_contains_key_material(self):
        for args in ((1, {1: 2}, False), (1, {1: 1, 2: 1}, True), (1, {2: 2}, True), (1, {5: 1}, True)):
            self.assertNotRegex(K.classify(*args)[1], r'[A-Za-z0-9+/]{40,}')


class NewKey(unittest.TestCase):
    def test_format_is_the_same_as_make_secrets(self):
        value = K.new_key()
        self.assertEqual(len(base64.b64decode(value, validate=True)), 32)
        self.assertNotEqual(value, K.new_key())
        self.assertNotIn('\n', value)


class Wiring(unittest.TestCase):
    def test_compose_command_lines(self):
        self.assertEqual(K.compose(['ps']), ['docker', 'compose', '-f', 'compose.yaml', '--profile', '*', 'ps'])
        self.assertEqual(K.compose(['up'], rotation=True, debug=True),
                         ['docker', 'compose', '-f', 'compose.yaml', '-f', 'compose.debug.yaml', '-f', 'compose.kek-rotation.yaml', '--profile', '*', 'up'])

    def test_override_adds_only_the_next_secret_to_inventory_service(self):
        with open(os.path.join(REPO, K.OVERRIDE), encoding='utf-8') as f:
            text = f.read()
        self.assertIn('inventory_kek_next:', text)
        self.assertIn('inventory-service:', text)
        self.assertNotIn('inventory_kek:', text)
        self.assertEqual(len(re.findall(r'^  [a-z-]+-service:', text, re.M)), 1)

    def test_compose_passes_version_and_flag_to_the_service(self):
        with open(os.path.join(REPO, 'compose.yaml'), encoding='utf-8') as f:
            text = f.read()
        self.assertIn('DGM_KEK_VERSION: ${DGM_KEK_VERSION:-1}', text)
        self.assertIn('DGM_KEK_ROTATE: ${DGM_KEK_ROTATE:-false}', text)

    def test_log_line_matches_what_the_service_writes(self):
        line = 'Ротация мастер-ключа: версия 1 -> 2, перезавёрнуто ключей данных 3'
        self.assertEqual(K.LOG_DONE.findall(line), [('1', '2', '3')])
        java = os.path.join(REPO, 'services', 'inventory-service', 'src', 'main', 'java', 'dgm', 'inventory', 'inventory', 'keys', 'service', 'KekStartup.java')
        with open(java, encoding='utf-8') as f:
            self.assertIn('Ротация мастер-ключа: версия {} -> {}, перезавёрнуто ключей данных {}', f.read())


class Flow(unittest.TestCase):
    """start и finish на временных каталогах: что они пишут в секреты и `.env`, когда останавливаются."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.secrets = os.path.join(self.tmp.name, 'secrets')
        os.makedirs(self.secrets)
        with open(os.path.join(self.secrets, K.KEK), 'w', encoding='utf-8') as f:
            f.write(OLD_KEY)
        self.env = os.path.join(self.tmp.name, '.env')
        self.counts = {1: 3}
        self.calls = []
        patches = [
            mock.patch.dict(os.environ, {'DGM_SECRETS_DIR': self.secrets, 'DGM_SECRET_MODE': '0444'}),
            mock.patch.object(K, 'env_path', lambda: self.env),
            mock.patch.object(K, 'db_counts', lambda debug: dict(self.counts)),
            mock.patch.object(K, 'restart_service', lambda debug, rotation: self.calls.append(('restart', rotation))),
            mock.patch.object(K, 'run', self.fake_run),
            mock.patch.object(K, 'wait', lambda what, ready, seconds: ready()),
            mock.patch.object(K, 'service_healthy', lambda debug, rotation: True),
        ]
        for p in patches:
            p.start()
            self.addCleanup(p.stop)

    def fake_run(self, cmd, timeout=180, check=True):
        self.calls.append(('run', cmd[:5]))
        result = mock.Mock(returncode=0, stderr='')
        result.stdout = 'Ротация мастер-ключа: версия 1 -> 2, перезавёрнуто ключей данных 3\n'
        return result

    def call(self, *argv):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = K.main(list(argv))
        return code, out.getvalue(), err.getvalue()

    def secret(self, name):
        with open(os.path.join(self.secrets, name), encoding='utf-8') as f:
            return f.read()

    def test_status_idle(self):
        code, out, _ = self.call('status')
        self.assertEqual(code, 0)
        self.assertIn('версия 1: 3', out)
        self.assertIn('start --yes', out)

    def test_start_without_yes_changes_nothing(self):
        code, out, _ = self.call('start')
        self.assertEqual(code, 0)
        self.assertIn('Ничего не сделано', out)
        self.assertFalse(os.path.exists(os.path.join(self.secrets, K.KEK_NEXT)))
        self.assertFalse(os.path.exists(self.env))
        self.assertEqual(self.calls, [])

    def test_start_creates_next_key_and_env_and_restarts_with_rotation(self):
        def rewrap_after_restart(debug, rotation):
            self.calls.append(('restart', rotation))
            self.counts = {2: 3}
        with mock.patch.object(K, 'restart_service', rewrap_after_restart):
            code, out, _ = self.call('start', '--yes')
        self.assertEqual(code, 0, out)
        self.assertEqual(len(base64.b64decode(self.secret(K.KEK_NEXT), validate=True)), 32)
        self.assertEqual(stat.S_IMODE(os.stat(os.path.join(self.secrets, K.KEK_NEXT)).st_mode), 0o444)
        self.assertEqual(self.secret(K.KEK), OLD_KEY, 'текущий мастер-ключ при start не меняется')
        with open(self.env, encoding='utf-8') as f:
            env = f.read()
        self.assertEqual(K.env_get(env, 'DGM_KEK_VERSION'), '1')
        self.assertEqual(K.env_get(env, 'DGM_KEK_ROTATE'), 'true')
        self.assertIn(('restart', True), self.calls)
        self.assertIn('перезавёрнуто ключей данных 3', out)
        self.assertNotIn(self.secret(K.KEK_NEXT), out)
        self.assertNotIn(OLD_KEY, out)

    def test_second_start_keeps_the_prepared_key(self):
        with open(os.path.join(self.secrets, K.KEK_NEXT), 'w', encoding='utf-8') as f:
            f.write(NEW_KEY)
        self.counts = {1: 1, 2: 2}

        def rewrap_after_restart(debug, rotation):
            self.counts = {2: 3}
        with mock.patch.object(K, 'restart_service', rewrap_after_restart):
            code, _, _ = self.call('start', '--yes')
        self.assertEqual(code, 0)
        self.assertEqual(self.secret(K.KEK_NEXT), NEW_KEY)

    def test_finish_refuses_while_old_deks_remain(self):
        with open(os.path.join(self.secrets, K.KEK_NEXT), 'w', encoding='utf-8') as f:
            f.write(NEW_KEY)
        self.counts = {1: 1, 2: 2}
        code, _, err = self.call('finish', '--yes')
        self.assertEqual(code, 2)
        self.assertIn('finish остановлен', err)
        self.assertEqual(self.secret(K.KEK), OLD_KEY)
        self.assertTrue(os.path.exists(os.path.join(self.secrets, K.KEK_NEXT)))
        self.assertFalse(os.path.exists(os.path.join(self.secrets, 'archive')))

    def test_finish_refuses_without_rotation(self):
        code, _, err = self.call('finish', '--yes')
        self.assertEqual(code, 2)
        self.assertIn('finish остановлен', err)

    def test_finish_without_yes_changes_nothing(self):
        with open(os.path.join(self.secrets, K.KEK_NEXT), 'w', encoding='utf-8') as f:
            f.write(NEW_KEY)
        self.counts = {2: 3}
        code, out, _ = self.call('finish')
        self.assertEqual(code, 0)
        self.assertIn('Ничего не сделано', out)
        self.assertEqual(self.secret(K.KEK), OLD_KEY)
        self.assertEqual(self.calls, [])

    def test_finish_swaps_keys_archives_the_old_one_and_bumps_version(self):
        with open(os.path.join(self.secrets, K.KEK_NEXT), 'w', encoding='utf-8') as f:
            f.write(NEW_KEY)
        with open(self.env, 'w', encoding='utf-8') as f:
            f.write('DGM_RESTART=no\nDGM_KEK_VERSION=1\nDGM_KEK_ROTATE=true\n')
        self.counts = {2: 3}
        code, out, err = self.call('finish', '--yes')
        self.assertEqual(code, 0, out + err)
        self.assertEqual(self.secret(K.KEK), NEW_KEY)
        self.assertFalse(os.path.exists(os.path.join(self.secrets, K.KEK_NEXT)))
        archived = os.path.join(self.secrets, 'archive', 'inventory_kek.v1')
        with open(archived, encoding='utf-8') as f:
            self.assertEqual(f.read(), OLD_KEY)
        self.assertEqual(stat.S_IMODE(os.stat(archived).st_mode), 0o400)
        self.assertEqual(stat.S_IMODE(os.stat(os.path.join(self.secrets, K.KEK)).st_mode), 0o444)
        with open(self.env, encoding='utf-8') as f:
            env = f.read()
        self.assertEqual(env, 'DGM_RESTART=no\nDGM_KEK_VERSION=2\nDGM_KEK_ROTATE=false\n')
        self.assertIn(('restart', False), self.calls)
        for key in (OLD_KEY, NEW_KEY):
            self.assertNotIn(key, out + err)

    def test_finish_does_not_overwrite_an_existing_archive_copy(self):
        os.makedirs(os.path.join(self.secrets, 'archive'))
        with open(os.path.join(self.secrets, 'archive', 'inventory_kek.v1'), 'w', encoding='utf-8') as f:
            f.write('прошлая копия')
        with open(os.path.join(self.secrets, K.KEK_NEXT), 'w', encoding='utf-8') as f:
            f.write(NEW_KEY)
        self.counts = {2: 3}
        with self.assertRaises(SystemExit):
            self.call('finish', '--yes')
        self.assertEqual(self.secret(K.KEK), OLD_KEY)
        self.assertTrue(os.path.exists(os.path.join(self.secrets, K.KEK_NEXT)))


if __name__ == '__main__':
    unittest.main()
