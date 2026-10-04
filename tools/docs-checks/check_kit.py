#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Каркас сервисов (libs/service-kit) против документов: код не расходится с тем, что записано в конвенциях, DDL и ADR.

Проверяется (шаг 11 Ф3):
  1. Реестр типов проблем в коде (enum ProblemType) совпадает с таблицей 9.1 conventions.md: коды, статусы HTTP, названия.
  2. Таблицы outbox и processed_event в миграциях сервисов совпадают с DDL в docs/07-data/ddl (одинаковые во всех шести базах).
  3. Столбцы в SQL каркаса (запись и чтение Outbox, таблица обработанных событий) есть в DDL: переименование столбца в
     документе без правки кода, и наоборот, ловится здесь, а не на стенде. Запись в Outbox задаёт все обязательные столбцы.
  4. Метрики Outbox, названные в ADR-005, есть в коде публикатора.
Запуск: python3 tools/docs-checks/check_kit.py
"""
import glob
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import docslib as L

KIT = os.path.join(L.REPO, 'libs', 'service-kit', 'src', 'main', 'java', 'dgm', 'kit')
CONVENTIONS = os.path.join(L.ARCH, 'conventions.md')
ADR_005 = os.path.join(L.ARCH, 'adr', 'ADR-005-transactional-outbox.md')
DDL_DIR = os.path.join(L.DOCS, '07-data', 'ddl')

# слова SQL и функции, которые не являются столбцами
SQL_WORDS = set('''select insert into values update set delete from where and or not is null order by limit in now make_interval secs
interval cast as jsonb uuid text on conflict do nothing returning count min max coalesce desc asc
pg_try_advisory_lock pg_advisory_unlock'''.split())
TABLES = {'outbox', 'processed_event'}

ENUM_RX = re.compile(r'^\s+([A-Z][A-Z0-9_]*)\("([a-z0-9-]+)",\s*(\d{3}),\s*"((?:[^"\\]|\\.)*)"\)\s*[,;]', re.M)
STMT_RX = re.compile(r'((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)')
LIT_RX = re.compile(r'"((?:[^"\\]|\\.)*)"')


def java_files(sub):
    return sorted(glob.glob(os.path.join(KIT, sub, '*.java')))


def problem_types(rep):
    where = 'ProblemType.java / conventions.md 9.1'
    code = {}
    for m in ENUM_RX.finditer(L.read(os.path.join(KIT, 'problem', 'ProblemType.java'))):
        code[m.group(2)] = (int(m.group(3)), m.group(4))
    t = L.table_after(L.read(CONVENTIONS), r'^### 9\.1\. Реестр типов проблем')
    if not t:
        rep.err(where, 'в conventions.md не найдена таблица 9.1')
        return
    doc = {}
    for r in t['rows']:
        doc[r[0].strip('`')] = (int(r[1]), r[2])
    for k in sorted(set(doc) - set(code)):
        rep.err(where, 'тип «%s» есть в документе и нет в коде' % k)
    for k in sorted(set(code) - set(doc)):
        rep.err(where, 'тип «%s» есть в коде и нет в документе: сначала добавляется в реестр 9.1' % k)
    for k in sorted(set(doc) & set(code)):
        if doc[k][0] != code[k][0]:
            rep.err(where, '«%s»: статус в документе %d, в коде %d' % (k, doc[k][0], code[k][0]))
        if doc[k][1] != code[k][1]:
            rep.err(where, '«%s»: название в документе «%s», в коде «%s»' % (k, doc[k][1], code[k][1]))
    rep.fact('типов проблем: в документе %d, в коде %d' % (len(doc), len(code)))


def table_columns(sql, table):
    """Столбцы таблицы public.<table> из create table: имя -> (не null, есть значение по умолчанию или identity)."""
    m = re.search(r'create table (?:public\.)?%s \((.*?)\n\);' % re.escape(table), sql, re.S)
    if not m:
        return None
    cols = {}
    for line in m.group(1).split('\n'):
        line = line.strip()
        if not line or line.startswith('constraint') or line.startswith('--'):
            continue
        parts = line.rstrip(',').split()
        low = line.lower()
        cols[parts[0]] = ('not null' in low or 'primary key' in low, ' default ' in low or 'generated always' in low)
    return cols


def ddl_tables(rep):
    where = 'DDL outbox и processed_event'
    result = {}
    for table in sorted(TABLES):
        seen = {}
        for f in sorted(glob.glob(os.path.join(DDL_DIR, '*.sql'))):
            cols = table_columns(L.read(f), table)
            if cols is None:
                rep.err(where, 'в %s нет таблицы %s' % (L.rel(f), table))
            else:
                seen[os.path.basename(f)] = cols
        shapes = {tuple(sorted(c.items())) for c in seen.values()}
        if len(shapes) > 1:
            rep.err(where, 'таблица %s в разных базах разная: служебные таблицы должны быть одинаковы' % table)
        if seen:
            result[table] = next(iter(seen.values()))
        rep.fact('%s: %d столбцов, одинаковая в %d базах' % (table, len(result.get(table, {})), len(seen)))
    return result


def sql_statements(path):
    text = L.read(path)
    # без комментариев javadoc и строк: только литералы подряд через +
    out = []
    for m in STMT_RX.finditer(text):
        s = ''.join(LIT_RX.findall(m.group(1)))
        if re.match(r'\s*(select|insert|update|delete)\b', s, re.I) and any(t in s for t in TABLES):
            out.append(s)
    return out


def sql_columns(rep, cols):
    where = 'SQL каркаса и DDL'
    files = java_files('outbox') + java_files('events')
    total = 0
    for f in files:
        for s in sql_statements(f):
            total += 1
            table = 'outbox' if re.search(r'\boutbox\b', s) else 'processed_event'
            known = set(cols.get(table, {}))
            tokens = {w for w in re.findall(r'[a-z_][a-z_0-9]*', re.sub(r'::\w+', '', s.lower()))}
            unknown = sorted(tokens - SQL_WORDS - TABLES - known)
            if unknown:
                rep.err(where, '%s: в SQL «%s…» нет в DDL %s: %s' % (os.path.basename(f), s[:48], table, ', '.join(unknown)))
            m = re.match(r'\s*insert into %s \(([^)]*)\)' % table, s, re.I)
            if m:
                given = {c.strip() for c in m.group(1).split(',')}
                required = {c for c, (nn, dflt) in cols[table].items() if nn and not dflt}
                for c in sorted(required - given):
                    rep.err(where, '%s: запись в %s не задаёт обязательный столбец %s' % (os.path.basename(f), table, c))
    if total == 0:
        rep.err(where, 'в каркасе не найдено ни одного запроса к outbox и processed_event: разбор устарел')
    rep.fact('запросов к служебным таблицам проверено: %d' % total)


def metrics(rep):
    where = 'ADR-005 и OutboxRelay.java'
    adr = L.read(ADR_005)
    names = sorted(set(re.findall(r'`(outbox_[a-z_]+)`', adr)))
    code = L.read(os.path.join(KIT, 'outbox', 'OutboxRelay.java'))
    if not names:
        rep.err(where, 'в ADR-005 не найдено ни одной метрики outbox_*')
    for n in names:
        if '"%s"' % n not in code:
            rep.err(where, 'метрика %s названа в ADR-005, а в публикаторе её нет' % n)
    rep.fact('метрик Outbox в ADR-005: %d' % len(names))


def main():
    rep = L.Report('Каркас сервисов против документов')
    problem_types(rep)
    cols = ddl_tables(rep)
    if cols:
        sql_columns(rep, cols)
    metrics(rep)
    return rep.finish()


if __name__ == '__main__':
    sys.exit(main())
