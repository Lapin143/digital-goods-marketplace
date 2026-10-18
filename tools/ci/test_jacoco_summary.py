#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Тесты сводки покрытия: порог слоя 80 %, «строгие» пакеты 90 % (Р-148 среза 2) в режимах предупреждения и блокировки.

    python3 -m unittest tools/ci/test_jacoco_summary.py
"""
import contextlib
import io
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import jacoco_summary as J  # noqa: E402


def report(root, module, packages):
    """Отчёт JaCoCo модуля: packages это {пакет: (пропущено, покрыто)} по строкам одного класса пакета."""
    path = os.path.join(root, module, 'build', 'reports', 'jacoco', 'test')
    os.makedirs(path)
    body = ''.join('<package name="%s"><class name="%s/C"><counter type="LINE" missed="%d" covered="%d"/></class></package>' % (p, p, m, c)
                   for p, (m, c) in packages.items())
    with open(os.path.join(path, 'jacocoTestReport.xml'), 'w', encoding='utf-8') as f:
        f.write('<report name="t">%s</report>' % body)


class JacocoSummaryTest(unittest.TestCase):
    def run_main(self, module, packages, strict_enforce):
        old = (J.STRICT_ENFORCE, sys.argv)
        J.STRICT_ENFORCE = strict_enforce
        try:
            with tempfile.TemporaryDirectory() as root:
                report(root, module, packages)
                sys.argv = ['jacoco_summary.py', root]
                out = io.StringIO()
                with contextlib.redirect_stdout(out):
                    code = J.main()
                return code, out.getvalue()
        finally:
            J.STRICT_ENFORCE, sys.argv = old

    def test_strict_package_below_threshold_only_warns_before_step_22(self):
        code, out = self.run_main('services/order-service', {'dgm/order/orders/domain': (2, 8)}, False)
        self.assertEqual(code, 0)
        self.assertIn('::warning', out)
        self.assertIn('пока не блокирует', out)

    def test_strict_package_below_threshold_blocks_after_step_22(self):
        code, out = self.run_main('services/payment-service', {'dgm/payment/payments/domain': (2, 8)}, True)
        self.assertEqual(code, 1)
        self.assertIn('Строгие пакеты ниже 90 %', out)

    def test_strict_package_at_threshold_passes(self):
        code, out = self.run_main('services/delivery-service', {'dgm/delivery/delivery/domain': (1, 9)}, True)
        self.assertEqual(code, 0)
        self.assertNotIn('Строгие пакеты ниже', out)

    def test_missing_strict_package_is_not_an_error(self):
        code, out = self.run_main('services/inventory-service', {'dgm/inventory/inventory/keys/service': (0, 10)}, True)
        self.assertEqual(code, 0)
        self.assertIn('пакета ещё нет в отчёте', out)

    def test_plain_layer_threshold_still_blocks(self):
        code, _ = self.run_main('services/catalog-service', {'dgm/catalog/sellers/service': (5, 5)}, False)
        self.assertEqual(code, 1)


if __name__ == '__main__':
    unittest.main()
