#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Сводка покрытия кода по отчётам JaCoCo (XML) для CI.

    python3 tools/ci/jacoco_summary.py [корень репозитория] [заголовок]

Читает <модуль>/build/reports/jacoco/test/jacocoTestReport.xml (его строит задача jacocoTestReport после модульных тестов),
печатает покрытие строк и ветвей по модулям и в целом, пишет сводку в аннотацию уровня notice (она читается по API через
tools/ci/ci_status.py) и в сводку запуска.

Доменный и сервисный слой (шаг 20 Ф4, условие 5): отдельная строка по пакетам, имя которых оканчивается на `domain` или `service`, у сервисов
из `services/`. Покрытие строк слоя в каждом сервисе не должно быть ниже LAYER_MIN (80 %): замер шага 20 дал 85 – 100 %, поэтому порог блокирует (ENFORCE).
Классы, которые нельзя покрыть модульными тестами, можно перечислить в EXCLUDED с причиной, и в счёт слоя они не войдут; классы слоя ниже
порога печатаются списком для информации (в основном переходники транзакций и адаптеры, которые покрывают интеграционные тесты). Слой ниже порога даёт код выхода 1; если отчётов нет совсем, печатается предупреждение (сборка могла упасть раньше, это видно по другим заданиям).

«Строгие» пакеты (Р-148 среза 2, критерий 5 раздела 14 для денег и ключей): отдельная строка и порог STRICT_MIN (90 %) для пакетов из STRICT_PACKAGES, где живёт логика резерва, заказа, платежа и выдачи. Пока их коды пишутся (шаги 5 – 14 среза 2), порог только предупреждает
(STRICT_ENFORCE = False, аннотация `warning`); с шага 22 STRICT_ENFORCE равен True, и пакет ниже порога даёт код выхода 1. Общий порог слоя в 80 % остаётся. Пакета ещё нет в отчёте: строка «пакета ещё нет», это не ошибка.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

LAYER_MIN = 80.0
ENFORCE = True
LAYER_PACKAGES = ('domain', 'service')
STRICT_MIN = 90.0
STRICT_ENFORCE = False   # шаг 22 среза 2 переключает на True
# Модуль -> окончание имени пакета (с косой чертой) «строгого» пакета этого сервиса
STRICT_PACKAGES = (
    ('services/inventory-service', 'reservation/service'),
    ('services/order-service', 'orders/domain'),
    ('services/payment-service', 'payments/domain'),
    ('services/delivery-service', 'delivery/domain'),
)
# Класс (имя из отчёта JaCoCo, с косой чертой) -> причина, по которой его покрывают интеграционные тесты, а не модульные
EXCLUDED = {
    # Тонкая обёртка над транзакцией и Outbox модуля: ни ветвления, ни правил. Её проходят все интеграционные тесты сервиса (PaymentSessionIT,
    # PaymentWebhookIT), а модульные тесты работают с подменой PaymentTransaction в FakePayments, поэтому в отчёте модульных тестов она на нуле
    'dgm/payment/payments/service/ModulePaymentTransaction': 'покрыта интеграционными тестами payment-service',
    'dgm/payment/payments/service/ModulePaymentTransaction$1': 'покрыта интеграционными тестами payment-service',
}


def counter(root, kind):
    """(пропущено, покрыто) для типа счётчика на уровне всего отчёта."""
    for c in root.findall('counter'):
        if c.get('type') == kind:
            return int(c.get('missed', 0)), int(c.get('covered', 0))
    return 0, 0


def percent(missed, covered):
    total = missed + covered
    return '   - ' if total == 0 else '%5.1f' % (100.0 * covered / total)


def layer_of(report, module):
    """Строки слоя domain+service сервиса: ((пропущено, покрыто) по строкам, то же по ветвям, [(класс, процент строк)] ниже порога)."""
    if not module.startswith('services' + os.sep) and not module.startswith('services/'):
        return None
    line, branch, weak = [0, 0], [0, 0], []
    for package in report.findall('package'):
        if package.get('name', '').split('/')[-1] not in LAYER_PACKAGES:
            continue
        for cls in package.findall('class'):
            name = cls.get('name')
            if name in EXCLUDED:
                continue
            cl, cb = counter(cls, 'LINE'), counter(cls, 'BRANCH')
            line[0] += cl[0]
            line[1] += cl[1]
            branch[0] += cb[0]
            branch[1] += cb[1]
            if cl[0] + cl[1] and 100.0 * cl[1] / (cl[0] + cl[1]) < LAYER_MIN:
                weak.append((name, 100.0 * cl[1] / (cl[0] + cl[1]), cl[0] + cl[1]))
    return tuple(line), tuple(branch), weak


def strict_of(report, module):
    """Строки «строгих» пакетов сервиса: [(окончание пакета, (пропущено, покрыто) по строкам или None, если пакета в отчёте нет)]."""
    found = []
    for mod, suffix in STRICT_PACKAGES:
        if mod != module.replace(os.sep, '/'):
            continue
        line = None
        for package in report.findall('package'):
            name = package.get('name', '')
            if name == suffix or name.endswith('/' + suffix):
                line = line or [0, 0]
                for cls in package.findall('class'):
                    if cls.get('name') in EXCLUDED:
                        continue
                    cl = counter(cls, 'LINE')
                    line[0] += cl[0]
                    line[1] += cl[1]
        found.append((suffix, tuple(line) if line is not None else None))
    return found


def main():
    root_dir = sys.argv[1] if len(sys.argv) > 1 else '.'
    title = sys.argv[2] if len(sys.argv) > 2 else 'Покрытие кода (JaCoCo)'
    pattern = os.path.join(root_dir, '**', 'build', 'reports', 'jacoco', 'test', 'jacocoTestReport.xml')
    rows = []
    layers = []
    below = []
    strict_below = []
    sums = {'LINE': [0, 0], 'BRANCH': [0, 0], 'METHOD': [0, 0]}
    for f in sorted(glob.glob(pattern, recursive=True)):
        module = os.path.relpath(f, root_dir).split(os.sep + 'build' + os.sep)[0]
        report = ET.parse(f).getroot()
        line, branch, method = counter(report, 'LINE'), counter(report, 'BRANCH'), counter(report, 'METHOD')
        for kind, value in (('LINE', line), ('BRANCH', branch), ('METHOD', method)):
            sums[kind][0] += value[0]
            sums[kind][1] += value[1]
        layer = layer_of(report, module)
        if layer:
            layers.append('%-34s слой domain+service: строк %s%%  ветвей %s%%  (строк %d из %d)'
                          % (module, percent(*layer[0]), percent(*layer[1]), layer[0][1], layer[0][0] + layer[0][1]))
            total = layer[0][0] + layer[0][1]
            if total and 100.0 * layer[0][1] / total < LAYER_MIN:
                below.append(module)
            layers += ['    ниже %d %%: %s (%.1f %%, строк %d)' % (LAYER_MIN, n.replace('/', '.'), p, t) for n, p, t in sorted(layer[2], key=lambda x: x[1])]
        for suffix, counts in strict_of(report, module):
            if counts is None or counts[0] + counts[1] == 0:
                layers.append('    строгий пакет …/%s: пакета ещё нет в отчёте' % suffix)
                continue
            share = 100.0 * counts[1] / (counts[0] + counts[1])
            layers.append('    строгий пакет …/%s: строк %s%%  (строк %d из %d, порог %d %%%s)'
                          % (suffix, percent(*counts), counts[1], counts[0] + counts[1], STRICT_MIN, '' if STRICT_ENFORCE else ', пока предупреждение'))
            if share < STRICT_MIN:
                strict_below.append('%s …/%s %.1f %%' % (module, suffix, share))
        rows.append('%-34s строк %s%%  ветвей %s%%  методов %s%%  (строк %d из %d)'
                    % (module, percent(*line), percent(*branch), percent(*method), line[1], line[0] + line[1]))
    if not rows:
        print('::warning title=%s::отчётов JaCoCo не найдено (jacocoTestReport не выполнялась)' % title)
        return 0
    rows.append('ИТОГО: строк %s%%, ветвей %s%%, методов %s%%'
                % (percent(*sums['LINE']), percent(*sums['BRANCH']), percent(*sums['METHOD'])))
    if below:
        layers.append('Слой domain+service ниже %d %% строк: %s%s' % (LAYER_MIN, ', '.join(below), '' if ENFORCE else ' (порог пока не блокирует)'))
    if strict_below:
        layers.append('Строгие пакеты ниже %d %% строк: %s%s' % (STRICT_MIN, '; '.join(strict_below), '' if STRICT_ENFORCE else ' (порог пока не блокирует)'))
    print('\n'.join(rows + layers))
    if strict_below and not STRICT_ENFORCE:
        print('::warning title=%s, строгие пакеты::%s' % (title, '; '.join(strict_below)))
    print('::notice title=%s::%s' % (title, '%0A'.join(rows)))
    if layers:
        print('::notice title=%s, слой domain+service::%s' % (title, '%0A'.join(layers)))
    rows += layers
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a', encoding='utf-8') as fh:
            fh.write('### %s\n\n```\n' % title + '\n'.join(rows) + '\n```\n')
    return 1 if ((ENFORCE and below) or (STRICT_ENFORCE and strict_below)) else 0


if __name__ == '__main__':
    sys.exit(main())
