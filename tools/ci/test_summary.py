#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Сводка результатов тестов Gradle (отчёты JUnit XML) для CI.

    python3 tools/ci/test_summary.py [корень репозитория] [заголовок]

Печатает число тестов по модулям и общую сумму, пишет её в аннотацию уровня notice (она читается по API
через tools/ci/ci_status.py) и в сводку запуска. Падает, если тестов нет совсем: так «зелёная» сборка
не может скрыть пропущенные тесты.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else '.'
    title = sys.argv[2] if len(sys.argv) > 2 else 'Модульные тесты'
    per = {}
    for f in glob.glob(os.path.join(root, '**', 'build', 'test-results', '*', 'TEST-*.xml'), recursive=True):
        module = os.path.relpath(f, root).split(os.sep + 'build' + os.sep)[0]
        t = ET.parse(f).getroot()
        d = per.setdefault(module, dict(tests=0, failures=0, errors=0, skipped=0, classes=set()))
        d['tests'] += int(t.get('tests', 0))
        d['failures'] += int(t.get('failures', 0))
        d['errors'] += int(t.get('errors', 0))
        d['skipped'] += int(t.get('skipped', 0))
        d['classes'].add(t.get('name'))
    total = sum(d['tests'] for d in per.values())
    bad = sum(d['failures'] + d['errors'] for d in per.values())
    skipped = sum(d['skipped'] for d in per.values())
    lines = ['%-34s тестов %3d, классов %2d, ошибок %d, пропущено %d' % (m, d['tests'], len(d['classes']), d['failures'] + d['errors'], d['skipped'])
             for m, d in sorted(per.items())]
    lines.append('ИТОГО: модулей %d, тестов %d, ошибок %d, пропущено %d' % (len(per), total, bad, skipped))
    print('\n'.join(lines))
    msg = '%0A'.join(lines)
    print('::notice title=%s::%s' % (title, msg))
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a', encoding='utf-8') as fh:
            fh.write('### %s\n\n```\n' % title + '\n'.join(lines) + '\n```\n')
    if total == 0:
        print('::error title=Тесты::не найдено ни одного отчёта о тестах')
        return 1
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
