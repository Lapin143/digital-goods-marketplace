#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Тест-кейсы TC-*.md: оформление, связь с историями и требованиями, связь с кодом (шаг 9 Ф4, решения Р-52 и Р-53).

Для каждого файла docs/08-testing/TC-*.md (кроме шаблона) проверяется:
  1. файл назван в таблице нумерации стратегии (раздел 13), номера тест-кейсов лежат в его диапазоне, идут по возрастанию без повторов; пропуск
     допустим только для номера из таблицы «Резерв номеров» (столбцы «Номера», «Сценарии», «Срез») этого же файла, и такой номер не описан;
  2. у каждого `### TC-NNN. Название` есть таблица полей (ID, User story, Требования, Уровень, Тип, Приоритет, Автоматизация, Тег в коде, Где в коде)
     и абзацы «Предусловия», «Шаги», «Ожидаемый результат»; ID и тег равны номеру в заголовке;
  3. истории US-N.M существуют в US-*.md, требования FT и NFT существуют в требованиях v1.6;
  4. уровень L1 – L8, тип, приоритет и способы автоматизации из допустимых наборов;
  5. «Где в коде»: каждый файл существует и содержит номер `TC-NNN` (@Tag у JUnit, имя проверки в скрипте стенда, заголовок шага сценария);
     вид файла совпадает с заявленным способом (`*Test.java` JUnit, `*IT.java` JUnit на стенде, `.py` Python на стенде, `.json` Newman,
     `*.test.ts` и `*.test.tsx` Vitest), лишних
     и недостающих видов нет; у «Ручной» кода нет;
  6. сводная таблица раздела 2 содержит те же номера, истории и уровни;
  7. обратная сверка: номер из области файла, найденный в коде (тесты сервисов и библиотек, скрипты стенда, сценарии Newman), описан в документе, и
     этот код указан в «Где в коде» этого тест-кейса. Так документ не расходится с кодом ни в ту, ни в другую сторону.
Запуск: python3 tools/docs-checks/check_testcases.py
"""
import glob
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import docslib as L

TESTING = os.path.join(L.DOCS, '08-testing')
STRATEGY = os.path.join(TESTING, 'test-strategy.md')
FIELDS = ['ID', 'User story', 'Требования', 'Уровень', 'Тип', 'Приоритет', 'Автоматизация', 'Тег в коде', 'Где в коде']
TYPES = {'UI', 'API', 'интеграционный', 'конкуренция', 'нагрузочный', 'безопасность', 'эксплуатационный'}
PRIORITIES = {'Высокий', 'Средний', 'Низкий'}
KINDS = {'JUnit', 'JUnit на стенде', 'Vitest', 'Newman', 'Python на стенде', 'k6', 'Ручной'}
BLOCKS = ('**Предусловия.**', '**Шаги.**', '**Ожидаемый результат.**')
HEADING = re.compile(r'^### (TC-(\d{3}))\. (.+)$', re.M)
CODE_GLOBS = [
    'services/*/src/test/**/*.java', 'libs/*/src/test/**/*.java', 'tools/stand-checks/*.py', 'tools/stand-checks/*.sh',
    'tools/api-tests/*.py', 'tools/api-tests/scenarios/*.json', 'frontend/web-app/src/**/*.test.ts', 'frontend/web-app/src/**/*.test.tsx',
]
RESERVE = re.compile(r'TC-(\d{3})(?:\s*[–-]\s*TC-(\d{3}))?')


def kind_of(path):
    if path.endswith('IT.java'):
        return 'JUnit на стенде'
    if path.endswith('.java'):
        return 'JUnit'
    if re.search(r'\.test\.tsx?$', path):
        return 'Vitest'
    return {'.py': 'Python на стенде', '.json': 'Newman', '.js': 'k6'}.get(os.path.splitext(path)[1])


def ranges():
    """Файл тест-кейсов -> (первый номер, последний) по таблице нумерации стратегии."""
    t = L.find_table(L.read(STRATEGY), 'Область', 'Диапазон')
    res = {}
    for row in t['rows']:
        files = L.backticked(row[3]) if len(row) > 3 else []
        nums = re.findall(r'TC-(\d{3})', row[1])
        if files and len(nums) == 2:
            res[files[0]] = (int(nums[0]), int(nums[1]))
    return res


def story_ids():
    ids = set()
    for f in glob.glob(os.path.join(L.DOCS, '02-requirements', 'US-*.md')):
        ids |= set(re.findall(r'^## (US-\d+\.\d+)\.', L.read(f), re.M))
    return ids


def code_files():
    out = []
    for pattern in CODE_GLOBS:
        out.extend(glob.glob(os.path.join(L.REPO, pattern), recursive=True))
    return sorted(set(out))


def token(number, text):
    return re.search(r'(?<![\w-])TC-%s(?!\w)' % number, text) is not None


def parse_cases(text, rep, where):
    cases = []
    heads = list(HEADING.finditer(text))
    for k, m in enumerate(heads):
        end = heads[k + 1].start() if k + 1 < len(heads) else len(text)
        body = text[m.end():end]
        # блок после последнего тест-кейса заканчивается следующим заголовком второго уровня
        stop = re.search(r'^## ', body, re.M)
        if stop:
            body = body[:stop.start()]
        tables = L.tables(body)
        fields = {}
        if tables and tables[0]['header'][:2] == ['Поле', 'Содержание']:
            for row in tables[0]['rows']:
                if len(row) >= 2:
                    fields[row[0]] = row[1]
        else:
            rep.err(where, '%s: нет таблицы полей' % m.group(1))
        cases.append(dict(id=m.group(1), number=int(m.group(2)), title=m.group(3), body=body, fields=fields))
    return cases


def reserved(text, rep, where):
    """Номера из таблицы «Резерв номеров» файла: за ними закреплён сценарий, который появится в указанном срезе."""
    t = L.find_table(text, 'Номера', 'Сценарии')
    numbers = set()
    for row in (t['rows'] if t else []):
        found = RESERVE.findall(row[0])
        if not found:
            rep.err(where, 'резерв: в строке «%s» нет номера TC-NNN' % row[0])
        for first, last in found:
            lo, hi = int(first), int(last or first)
            if hi < lo:
                rep.err(where, 'резерв: диапазон %s задан наоборот' % row[0])
            numbers |= set(range(lo, hi + 1))
    return numbers


def check_case(c, stories, ft, nft, rep, where, code_cache):
    tc, f = c['id'], c['fields']
    at = '%s %s' % (where, tc)
    for name in FIELDS:
        if not f.get(name):
            rep.err(at, 'нет поля «%s»' % name)
    if f.get('ID') and f['ID'] != tc:
        rep.err(at, 'ID в таблице %s не равен номеру заголовка' % f['ID'])
    if f.get('Тег в коде') and f['Тег в коде'] != tc:
        rep.err(at, 'тег в коде %s не равен номеру заголовка' % f['Тег в коде'])
    for block in BLOCKS:
        if block not in c['body']:
            rep.err(at, 'нет абзаца %s' % block)
    c['stories'] = set(re.findall(r'US-\d+\.\d+', f.get('User story', '')))
    if not c['stories']:
        rep.err(at, 'не названа история')
    for s in sorted(c['stories'] - stories):
        rep.err(at, 'истории %s нет в US-*.md' % s)
    for r in re.findall(r'\b(FT-\d+\.\d+|NFT-\d+\.\d+)\b', f.get('Требования', '')):
        if r.startswith('N') and r not in nft or not r.startswith('N') and r not in ft:
            rep.err(at, 'требования %s нет в requirements_v1.6.md' % r)
    if not re.fullmatch(r'L[1-8]', f.get('Уровень', '')):
        rep.err(at, 'уровень «%s», нужен L1 – L8' % f.get('Уровень'))
    if f.get('Тип') not in TYPES:
        rep.err(at, 'тип «%s» вне набора %s' % (f.get('Тип'), sorted(TYPES)))
    if f.get('Приоритет') not in PRIORITIES:
        rep.err(at, 'приоритет «%s» вне набора %s' % (f.get('Приоритет'), sorted(PRIORITIES)))
    declared = {k.strip() for k in f.get('Автоматизация', '').split(';') if k.strip()}
    for k in sorted(declared - KINDS):
        rep.err(at, 'способ автоматизации «%s» вне набора %s' % (k, sorted(KINDS)))
    paths = L.backticked(f.get('Где в коде', ''))
    c['paths'] = set(paths)
    if declared == {'Ручной'}:
        if paths:
            rep.err(at, 'для ручного теста «Где в коде» должно быть пустым (–)')
        return
    if 'Ручной' in declared:
        rep.err(at, '«Ручной» нельзя смешивать с автоматизацией')
    if not paths:
        rep.err(at, 'не названы файлы с автоматизацией')
    kinds = set()
    for p in paths:
        full = os.path.join(L.REPO, p)
        if not os.path.isfile(full):
            rep.err(at, 'файла %s нет' % p)
            continue
        text = code_cache.setdefault(full, L.read(full))
        if not token(tc[3:], text):
            rep.err(at, 'в файле %s нет номера %s (поставьте @Tag или имя проверки с номером)' % (p, tc))
        k = kind_of(p)
        if k is None:
            rep.err(at, 'файл %s не похож на тест: неизвестный вид' % p)
        else:
            kinds.add(k)
            if k not in declared:
                rep.err(at, 'файл %s это «%s», а в «Автоматизация» этого способа нет' % (p, k))
    for k in sorted(declared - kinds - {'Ручной'}):
        rep.err(at, 'способ «%s» заявлен, а файла такого вида в «Где в коде» нет' % k)


def check_summary(text, cases, rep, where):
    t = L.find_table(text, 'TC', 'Название')
    if not t:
        rep.err(where, 'нет сводной таблицы (TC, Название, История, Уровень, Чем проверяется)')
        return
    h = t['header']
    rows = {r[0]: r for r in t['rows'] if len(r) == len(h)}
    by_id = {c['id']: c for c in cases}
    for tc in sorted(set(by_id) - set(rows)):
        rep.err(where, 'сводка: нет строки %s' % tc)
    for tc in sorted(set(rows) - set(by_id)):
        rep.err(where, 'сводка: строка %s без тест-кейса' % tc)
    hi, lv = h.index('История'), h.index('Уровень')
    for tc, row in rows.items():
        c = by_id.get(tc)
        if not c:
            continue
        if set(re.findall(r'US-\d+\.\d+', row[hi])) != c['stories']:
            rep.err(where, 'сводка %s: истории %s не равны полю User story %s' % (tc, row[hi], sorted(c['stories'])))
        if row[lv] != c['fields'].get('Уровень'):
            rep.err(where, 'сводка %s: уровень %s не равен полю %s' % (tc, row[lv], c['fields'].get('Уровень')))


def main():
    rep = L.Report('Тест-кейсы')
    ranges_by_file = ranges()
    stories = story_ids()
    ft, nft = L.requirement_ids()
    files = sorted(f for f in glob.glob(os.path.join(TESTING, 'TC-*.md')))
    if not files:
        rep.err('docs/08-testing', 'нет файлов TC-*.md')
    code_cache = {}
    documented = {}
    areas = []
    for path in files:
        name = os.path.basename(path)
        where = L.rel(path)
        if name not in ranges_by_file:
            rep.err(where, 'файла нет в таблице нумерации стратегии (раздел 13)')
            continue
        lo, hi = ranges_by_file[name]
        areas.append((lo, hi))
        text = L.read(path)
        cases = parse_cases(text, rep, where)
        held = reserved(text, rep, where)
        previous = None
        for c in cases:
            if not lo <= c['number'] <= hi:
                rep.err(where, '%s вне диапазона %d – %d' % (c['id'], lo, hi))
            if c['number'] in held:
                rep.err(where, '%s описан, но стоит в таблице «Резерв номеров»' % c['id'])
            first = lo if previous is None else previous + 1
            missing = [n for n in range(first, c['number']) if n not in held]
            if missing or (previous is not None and c['number'] <= previous):
                rep.err(where, '%s: ожидался TC-%03d (номера идут подряд, пропуск допустим только для резерва)' % (c['id'], missing[0] if missing else first))
            previous = c['number']
            if c['id'] in documented:
                rep.err(where, '%s описан дважды' % c['id'])
            check_case(c, stories, ft, nft, rep, where, code_cache)
            documented[c['id']] = c
        check_summary(text, cases, rep, where)
        rep.fact('%s: тест-кейсов %d (TC-%03d – TC-%03d), автоматизировано %d'
                 % (name, len(cases), cases[0]['number'] if cases else lo, cases[-1]['number'] if cases else lo,
                    sum(1 for c in cases if 'Ручной' not in c['fields'].get('Автоматизация', ''))))

    # обратная сверка: код ссылается только на описанные тест-кейсы своей области и только из перечисленных файлов
    seen = 0
    for full in code_files():
        text = code_cache.get(full) or L.read(full)
        for number in sorted(set(re.findall(r'(?<![\w-])TC-(\d{3})(?!\w)', text))):
            if not any(lo <= int(number) <= hi for lo, hi in areas):
                continue
            seen += 1
            tc = 'TC-' + number
            c = documented.get(tc)
            if not c:
                rep.err(L.rel(full), 'ссылается на %s, которого нет в TC-*.md' % tc)
            elif L.rel(full) not in c['paths']:
                rep.err(L.rel(full), 'ссылается на %s, но не указан в его поле «Где в коде»' % tc)
    rep.fact('ссылок из кода на тест-кейсы областей с документами: %d (файл, номер)' % seen)
    return rep.finish()


if __name__ == '__main__':
    sys.exit(main())
