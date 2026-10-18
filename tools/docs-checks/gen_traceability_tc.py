#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Заполняет колонки «Тест-кейс» и «Код» матрицы трассировки (шаг 20 Ф4, версия 3).

    python3 tools/docs-checks/gen_traceability_tc.py          записать клетки в docs/08-testing/traceability.md
    python3 tools/docs-checks/gen_traceability_tc.py --check  только сравнить (код возврата 1, если клетки отличаются)

Клетки собираются из тест-кейсов (`tcindex.py`), а те связаны с кодом тегами, которые проверяет `check_testcases.py`. Поэтому колонки
не пишутся вручную: после нового тега или тест-кейса достаточно запустить скрипт. `check_traceability.py` сравнивает клетки с тем же расчётом.
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import docslib as L
import tcindex as T

PATH = os.path.join(L.DOCS, '08-testing', 'traceability.md')
SECTIONS = ('2.1', '2.2', '3.1', '3.2')


def cells_of(line):
    return [c.strip() for c in line.strip()[1:-1].split('|')]


def render(cells):
    return '|' + ''.join((' %s |' % c) if c else ' |' for c in cells)


def expected_row(rid, release1, by_req, table, tests, reserve, fields):
    tcs = by_req.get(rid, [])
    return T.expected_cell(rid, tcs, table, release1, tests, reserve), T.expected_code(tcs, fields)


def rewrite(text):
    by_req = T.by_requirement()
    table = T.slices()
    tests = T.strategy_tests()
    reserve = T.reserved_numbers()
    fields = T.test_cases()
    out = []
    section = None
    changed = 0
    for line in text.split('\n'):
        m = re.match(r'^### (\d\.\d)\.', line)
        if m:
            section = m.group(1)
        elif line.startswith('## '):
            section = None
        if section in SECTIONS and line.startswith('| ') and not line.startswith('| BG |') and not line.startswith('| ---'):
            cells = cells_of(line)
            m = re.match(r'(N?FT-\d+\.\d+) ', cells[1]) if len(cells) == 7 else None
            if m:
                tc, code = expected_row(m.group(1), section in ('2.1', '2.2'), by_req, table, tests, reserve, fields)
                new = render(cells[:5] + [tc, code])
                changed += new != line
                line = new
        out.append(line)
    return '\n'.join(out), changed


def main():
    text = L.read(PATH)
    new, changed = rewrite(text)
    if '--check' in sys.argv:
        print('клеток «Тест-кейс» и «Код» отличается строк: %d' % changed)
        return 1 if changed else 0
    if changed:
        with open(PATH, 'w', encoding='utf-8', newline='\n') as f:
            f.write(new)
    print('записано строк: %d' % changed)
    return 0


if __name__ == '__main__':
    sys.exit(main())
