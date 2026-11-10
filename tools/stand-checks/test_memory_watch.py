# -*- coding: utf-8 -*-
"""Наблюдатель за пиком памяти без стенда (шаг 16 Ф5, К16; Р-196): итоговая строка, порог 90 %, пустые замеры не дают тихого зелёного."""
import os
import sys
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import gateway_checks as G  # noqa: E402
import memory_watch as MW  # noqa: E402


class WatchTest(unittest.TestCase):
    def finish(self, watched, peaks, errors=0, annotation=False):
        watch = MW.MemoryWatch(watched, 'под сценариями webhook')
        watch.peaks, watch.errors = peaks, errors
        before, out = (G.PASSED, len(G.FAILED)), []
        # Пустое значение, а не отсутствие: на раннере CI переменная уже задана, и patch.dict с пустым словарём её не убирает
        env = {'GITHUB_ACTIONS': 'true' if annotation else ''}
        with mock.patch.dict(os.environ, env), mock.patch('builtins.print', side_effect=lambda *a, **k: out.append(' '.join(str(x) for x in a))):
            watch.finish()
        return G.PASSED - before[0], len(G.FAILED) - before[1], out

    def tearDown(self):
        del G.FAILED[:]

    def test_platform_under_the_threshold_passes_and_prints_its_own_line(self):
        passed, failed, out = self.finish(('platform-service',), {'platform-service': (300.0, 352.0)})
        self.assertEqual((passed, failed), (1, 0))
        self.assertTrue(any(line.startswith('Пик памяти под сценариями webhook: platform-service пик 300 из 352 МБ (85%)') for line in out), out)

    def test_exactly_ninety_percent_is_not_red_but_the_rule_of_r196_applies_and_is_printed(self):
        passed, failed, out = self.finish(('platform-service',), {'platform-service': (316.8, 352.0)})
        self.assertEqual((passed, failed), (1, 0))
        self.assertIn('(90%)', ' '.join(out))

    def test_above_ninety_percent_is_red(self):
        passed, failed, _ = self.finish(('platform-service',), {'platform-service': (320.0, 352.0)})
        self.assertEqual((passed, failed), (0, 1))

    def test_no_measurements_is_red_not_silently_green(self):
        passed, failed, out = self.finish(('platform-service',), {}, errors=3)
        self.assertEqual((passed, failed), (0, 1))
        self.assertIn('замеров нет', ' '.join(out))

    def test_every_watched_service_must_be_measured(self):
        self.assertEqual(self.finish(('platform-service', 'api-gateway'), {'platform-service': (100.0, 352.0)})[:2], (0, 1))

    def test_annotation_is_printed_in_ci_only(self):
        self.assertTrue(any(line.startswith('::notice title=память') for line in self.finish(('platform-service',), {'platform-service': (100.0, 352.0)}, annotation=True)[2]))
        self.assertFalse(any(line.startswith('::notice') for line in self.finish(('platform-service',), {'platform-service': (100.0, 352.0)})[2]))


if __name__ == '__main__':
    unittest.main()
