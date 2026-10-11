#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Сводка покрытия кода по отчётам JaCoCo (XML) для CI.

    python3 tools/ci/jacoco_summary.py [корень репозитория] [заголовок]

Читает <модуль>/build/reports/jacoco/test/jacocoTestReport.xml (его строит задача jacocoTestReport после модульных тестов),
печатает покрытие строк и ветвей по модулям и в целом, пишет сводку в аннотацию уровня notice (она читается по API через
tools/ci/ci_status.py) и в сводку запуска. Порога нет: шаг 4 Ф4 только показывает числа, порог выбирается по замерам в шаге 20.
Код выхода всегда 0; если отчётов нет совсем, печатается предупреждение (сборка могла упасть раньше, это видно по другим заданиям).
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET


def counter(root, kind):
    """(пропущено, покрыто) для типа счётчика на уровне всего отчёта."""
    for c in root.findall('counter'):
        if c.get('type') == kind:
            return int(c.get('missed', 0)), int(c.get('covered', 0))
    return 0, 0


def percent(missed, covered):
    total = missed + covered
    return '   - ' if total == 0 else '%5.1f' % (100.0 * covered / total)


def main():
    root_dir = sys.argv[1] if len(sys.argv) > 1 else '.'
    title = sys.argv[2] if len(sys.argv) > 2 else 'Покрытие кода (JaCoCo)'
    pattern = os.path.join(root_dir, '**', 'build', 'reports', 'jacoco', 'test', 'jacocoTestReport.xml')
    rows = []
    sums = {'LINE': [0, 0], 'BRANCH': [0, 0], 'METHOD': [0, 0]}
    for f in sorted(glob.glob(pattern, recursive=True)):
        module = os.path.relpath(f, root_dir).split(os.sep + 'build' + os.sep)[0]
        report = ET.parse(f).getroot()
        line, branch, method = counter(report, 'LINE'), counter(report, 'BRANCH'), counter(report, 'METHOD')
        for kind, value in (('LINE', line), ('BRANCH', branch), ('METHOD', method)):
            sums[kind][0] += value[0]
            sums[kind][1] += value[1]
        rows.append('%-34s строк %s%%  ветвей %s%%  методов %s%%  (строк %d из %d)'
                    % (module, percent(*line), percent(*branch), percent(*method), line[1], line[0] + line[1]))
    if not rows:
        print('::warning title=%s::отчётов JaCoCo не найдено (jacocoTestReport не выполнялась)' % title)
        return 0
    rows.append('ИТОГО: строк %s%%, ветвей %s%%, методов %s%% (порога нет, замер в шаге 20 Ф4)'
                % (percent(*sums['LINE']), percent(*sums['BRANCH']), percent(*sums['METHOD'])))
    print('\n'.join(rows))
    print('::notice title=%s::%s' % (title, '%0A'.join(rows)))
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a', encoding='utf-8') as fh:
            fh.write('### %s\n\n```\n' % title + '\n'.join(rows) + '\n```\n')
    return 0


if __name__ == '__main__':
    sys.exit(main())
