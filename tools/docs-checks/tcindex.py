# -*- coding: utf-8 -*-
"""Связь «требование → тест-кейсы» и «требование → срез» для матрицы трассировки (шаг 20 Ф4).

Тест-кейсы читаются из `docs/08-testing/TC-*.md` (поле «Требования»), а в этих файлах каждый номер стоит в коде тестов
(`check_testcases.py` проверяет связь в обе стороны). Поэтому колонка «Тест-кейс» матрицы, собранная здесь, идёт от тегов кода,
а не от ручной записи. Срезы читаются из таблицы «Критерии выхода» стратегии тестирования (раздел 14).
Используется `check_traceability.py` (проверка) и `gen_traceability_tc.py` (заполнение).

Клетка «Тест-кейс» строки R1 это номера тест-кейсов (поле «Требования» файлов TC-*.md), для нефункциональных требований среза без
тест-кейса номера системных тестов ST из таблицы 5 стратегии или закреплённые за будущим шагом номера из «Резерва номеров», затем
после точки с запятой срезы, где проверка продолжится. Клетка «Код» это модули реализации тех файлов, что названы в поле «Где в коде»
этих тест-кейсов.
"""
import glob
import os
import re

import docslib as L
import check_testcases as CT

STRATEGY = CT.STRATEGY
# Требования R1, которых нет в таблице срезов Ф4: эксплуатация проверяется в Ф6 (стратегия, раздел 13, файл TC-operations.md)
DEFAULT_LATER = 'Ф6'
MARKERS = (('Ф4, срез 2', 'срез 2'), ('Ф4, срез 3', 'срез 3'), ('Ф4, сервер', 'сервер'))


def test_cases():
    """Номер тест-кейса -> словарь полей. Ошибки разбора файлов здесь не сообщаются: их находит check_testcases.py."""
    rep = L.Report('tcindex')
    result = {}
    for path in sorted(glob.glob(os.path.join(CT.TESTING, 'TC-*.md'))):
        for c in CT.parse_cases(L.read(path), rep, L.rel(path)):
            result[c['id']] = c['fields']
    return result


def by_requirement():
    """Идентификатор требования -> отсортированный список тест-кейсов, у которых оно названо в поле «Требования»."""
    result = {}
    for tc, fields in test_cases().items():
        for rid in re.findall(r'\b(N?FT-\d+\.\d+)\b', fields.get('Требования', '')):
            result.setdefault(rid, set()).add(tc)
    return {k: sorted(v) for k, v in result.items()}


def expand(text):
    """«FT-1.0 – FT-1.5, FT-1.7, NFT-3.2» -> множество идентификаторов; диапазон идёт по второму числу внутри одной группы."""
    found = set()
    text = text.replace(' ', ' ')
    for m in re.finditer(r'(N?FT)-(\d+)\.(\d+)\s*[–-]\s*(?:N?FT)-(\d+)\.(\d+)', text):
        kind, g1, lo, g2, hi = m.group(1), m.group(2), int(m.group(3)), m.group(4), int(m.group(5))
        if g1 == g2:
            found |= {'%s-%s.%d' % (kind, g1, n) for n in range(lo, hi + 1)}
    stripped = re.sub(r'(N?FT)-(\d+)\.(\d+)\s*[–-]\s*(?:N?FT)-(\d+)\.(\d+)', '', text)
    found |= set(re.findall(r'\bN?FT-\d+\.\d+\b', stripped))
    return found


def slices():
    """Название среза из таблицы стратегии -> множество требований (функциональных и нефункциональных)."""
    t = L.find_table(L.read(STRATEGY), 'Срез', 'Функциональные требования')
    result = {}
    for row in (t['rows'] if t else []):
        result[row[0]] = expand(row[1]) | expand(row[2])
    return result


def later_markers(rid, table=None):
    """Метки срезов после первого, где требование ещё предстоит проверить: «срез 2», «срез 3», «сервер»."""
    table = table if table is not None else slices()
    return [short for name, short in MARKERS if rid in table.get(name, set())]


def reserved_numbers():
    """Номера, закреплённые «Резервом номеров» файлов TC-*.md: строка вида «TC-060».
    Ошибки разбора здесь не сообщаются: их находит check_testcases.py."""
    rep = L.Report('tcindex')
    found = set()
    for path in sorted(glob.glob(os.path.join(CT.TESTING, 'TC-*.md'))):
        found |= {'TC-%03d' % n for n in CT.reserved(L.read(path), rep, L.rel(path))}
    return found


def strategy_tests():
    """NFT -> текст клетки «Тесты» из таблицы 5 стратегии (какие проверки закреплены за требованием)."""
    t = L.find_table(L.read(STRATEGY), 'NFT', 'Тесты')
    return {row[0]: row[-1] for row in (t['rows'] if t else [])}


def expected_cell(rid, tcs, table=None, release1=True, tests=None, reserve=None):
    """Клетка «Тест-кейс». Пустая клетка допустима только у требований R2 и R3 без тест-кейсов (release1=False)."""
    table = table if table is not None else slices()
    if not release1:
        return ', '.join(tcs)
    tests = tests if tests is not None else strategy_tests()
    reserve = reserve if reserve is not None else reserved_numbers()
    in_slice1 = rid in table.get('Ф4, срез 1', set())
    later = later_markers(rid, table)
    head = list(tcs)
    if not tcs and in_slice1 and rid in tests:
        head = sorted(set(re.findall(r'\bST-\d+\b', tests[rid])))
        head += ['%s (резерв)' % n for n in sorted(set(re.findall(r'\bTC-\d{3}\b', tests[rid]))) if n in reserve]
    if not tcs and not head and not later:
        if rid in table.get('Ф3', set()):
            later = ['Ф3']
        elif not in_slice1:
            later = [DEFAULT_LATER]
    parts = [', '.join(head)] if head else []
    if later:
        parts.append(', '.join(later))
    return '; '.join(parts)


# Каталоги реализации; тест-кейсы, у которых в «Где в коде» только скрипты (контракты, документы), дают каталог скрипта
IMPLEMENTATION = ('services', 'libs', 'frontend', 'infra')


def module_of(path):
    parts = path.split('/')
    return '/'.join(parts[:2]) if len(parts) > 2 else path


def expected_code(tcs, fields_by_tc=None):
    """Клетка «Код»: модули реализации по файлам тест-кейсов, в обратных кавычках через запятую."""
    fields_by_tc = fields_by_tc if fields_by_tc is not None else test_cases()
    modules = set()
    for tc in tcs:
        for path in re.findall(r'`([^`]+)`', fields_by_tc.get(tc, {}).get('Где в коде', '')):
            modules.add(module_of(path))
    impl = {m for m in modules if m.split('/')[0] in IMPLEMENTATION}
    return ', '.join('`%s`' % m for m in sorted(impl or modules))
