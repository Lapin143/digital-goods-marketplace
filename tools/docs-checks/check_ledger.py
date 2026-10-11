#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Реестры готовности операций сервисов (находка 5 плана Ф4).

В каждом сервисе файл src/test/resources/dgm/operations.txt перечисляет все операции его OpenAPI со статусом: done (обработчик есть),
slice1 (войдёт в срез 1, обработчика ещё нет), later (срезы 2 и 3, Ф5). Что обработчик действительно есть у done и нет у остальных, проверяет
тест сервиса (RouteCoverage.ledgerProblems), а здесь проверяется то, что не требует Java:
  1. реестр содержит ровно операции из routes.json сервиса (его создаёт gen_routes.py из OpenAPI), каждую один раз, статусы допустимы;
  2. печатается ход среза: сколько операций готово и сколько ещё в работе по сервисам.
Запуск:
    python3 tools/docs-checks/check_ledger.py            проверка согласованности, ход среза в справке
    python3 tools/docs-checks/check_ledger.py --strict   то же, но красный, пока в реестре остаётся хоть одна операция slice1 (закрытие среза)
"""
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import docslib as L

SERVICES = ['catalog-service', 'inventory-service', 'order-service', 'payment-service', 'delivery-service', 'platform-service']
STATUSES = ('done', 'slice1', 'later')


def ledger_path(service):
    return os.path.join(L.REPO, 'services', service, 'src', 'test', 'resources', 'dgm', 'operations.txt')


def routes_path(service):
    return os.path.join(L.REPO, 'services', service, 'src', 'main', 'resources', 'dgm', 'routes.json')


def read_ledger(path, rep, where):
    """Операция -> статус; ошибки разбора уходят в отчёт."""
    ledger = {}
    for n, line in enumerate(L.read(path).split('\n'), 1):
        content = line.split('#', 1)[0].strip()
        if not content:
            continue
        parts = content.split()
        if len(parts) != 2:
            rep.err(where, 'строка %d «%s»: нужны имя операции и статус' % (n, content))
        elif parts[1] not in STATUSES:
            rep.err(where, 'строка %d: операция %s, неизвестный статус «%s»' % (n, parts[0], parts[1]))
        elif parts[0] in ledger:
            rep.err(where, 'строка %d: операция %s записана дважды' % (n, parts[0]))
        else:
            ledger[parts[0]] = parts[1]
    return ledger


def main(argv):
    strict = '--strict' in argv
    rep = L.Report('Реестры готовности операций')
    totals = {s: 0 for s in STATUSES}
    for service in SERVICES:
        where = '%s/operations.txt' % service
        if not os.path.exists(ledger_path(service)):
            rep.err(where, 'нет реестра операций: %s' % L.rel(ledger_path(service)))
            continue
        with open(routes_path(service), encoding='utf-8') as f:
            declared = {r['operationId'] for r in json.load(f)['routes']}
        ledger = read_ledger(ledger_path(service), rep, where)
        for op in sorted(declared - set(ledger)):
            rep.err(where, 'операция %s есть в OpenAPI и не записана в реестре' % op)
        for op in sorted(set(ledger) - declared):
            rep.err(where, 'операции %s нет в OpenAPI, её надо убрать из реестра' % op)
        counts = {s: sum(1 for v in ledger.values() if v == s) for s in STATUSES}
        for s in STATUSES:
            totals[s] += counts[s]
        rep.fact('%-18s операций %2d: готово %2d, в работе среза %2d, позже %2d' % (service, len(declared), counts['done'], counts['slice1'], counts['later']))
        if strict and counts['slice1']:
            open_ops = sorted(op for op, v in ledger.items() if v == 'slice1')
            rep.err(where, 'срез не закрыт, не готово %d: %s' % (len(open_ops), ', '.join(open_ops)))
    slice_total = totals['done'] + totals['slice1']
    rep.fact('срез 1 и сделанное раньше: %d операций, из них готово %d, осталось %d; позже срезов 2 и 3: %d'
             % (slice_total, totals['done'], totals['slice1'], totals['later']))
    return rep.finish()


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
