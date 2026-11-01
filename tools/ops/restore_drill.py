#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Учебное восстановление базы платформы: дамп, восстановление в чистую базу, сверка (шаг 22 Ф4; TC-817, Р-174; NFT-4.1, ST-09).

    tools/ops/restore_drill.py run                  снять дамп platform_db, развернуть его в platform_db_restore, сверить, убрать копию
    tools/ops/restore_drill.py run --keep           то же, но восстановленную базу оставить для осмотра (удалить: restore_drill.py drop)
    tools/ops/restore_drill.py drop                 удалить platform_db_restore

Что делает. `pg_dump -Fc` базы `platform_db` (пользователь postgres контейнера стенда) пишет дамп в `build/restore-drill/platform_db.dump`,
`pg_restore` разворачивает его в новую базу `platform_db_restore` в том же сервере PostgreSQL. Затем сверяется то, что обязано совпасть:

  журнал аудита   по каждой месячной секции число записей и контрольная сумма (md5 по всем строкам в порядке времени и номера), только записи
                  старше десяти секунд на момент начала: платформа пишет в журнал и во время копирования, а запись, ещё не зафиксированную к снимку,
                  дамп не содержит
  схема           число таблиц, секций, индексов, ограничений и триггеров в каждой схеме
  неизменяемость  на восстановленной базе UPDATE и DELETE строки журнала и TRUNCATE журнала и его секции отклонены триггерами (ST-09, INV-42)
  время           дамп, восстановление и всё вместе, в секундах: ориентир, а не зачёт RPO 15 минут и RTO 1 час (NFT-4.1 засчитывается на сервере в Ф6)

Чего не подтверждает. Копия снимается с той же машины и того же сервера PostgreSQL, пустой диск и отдельный сервер не проверены; роли и их пароли в
дамп базы не входят (они кластерные, их создаёт `make db-roles`); восстановление базы целиком по расписанию с хранением копии вне сервера (Ф6).
Значения ключей товаров скрипт не касается: это `inventory_db`, и мастер-ключи лежат вне базы (ADR-009).

Нужен только Docker и Python 3; стенд поднят с отладочным файлом (`--debug`, как `make ... DEBUG=1`) или без него.
"""
import argparse
import hashlib
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))

SOURCE = 'platform_db'
COPY = 'platform_db_restore'
DUMP = os.path.join(REPO, 'build', 'restore-drill', 'platform_db.dump')
AGE_SECONDS = 10
DENIED = 'журнал аудита только добавляется'

USER_SCHEMAS = "n.nspname !~ '^pg_' and n.nspname <> 'information_schema'"

SCHEMA_PARITY = """
select n.nspname, 'таблиц', count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace
  where %(s)s and c.relkind in ('r', 'p') group by 1
union all
select n.nspname, 'секций', count(*) from pg_inherits i join pg_class c on c.oid = i.inhrelid join pg_namespace n on n.oid = c.relnamespace
  where %(s)s group by 1
union all
select n.nspname, 'индексов', count(*) from pg_index x join pg_class c on c.oid = x.indexrelid join pg_namespace n on n.oid = c.relnamespace
  where %(s)s group by 1
union all
select n.nspname, 'ограничений', count(*) from pg_constraint k join pg_namespace n on n.oid = k.connamespace
  where %(s)s group by 1
union all
select n.nspname, 'триггеров', count(*) from pg_trigger t join pg_class c on c.oid = t.tgrelid join pg_namespace n on n.oid = c.relnamespace
  where %(s)s and not t.tgisinternal group by 1
order by 1, 2
""" % {'s': USER_SCHEMAS}

AUDIT_SECTIONS = """
select t.tableoid::regclass::text, count(*), coalesce(md5(string_agg(t::text, E'\\n' order by t.occurred_at, t.id)), '')
from audit_admin.audit_log t
where t.recorded_at <= '%s'::timestamptz
group by 1 order by 1
"""

ROW_COUNTS = """
select n.nspname || '.' || c.relname,
       (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %%I.%%I', n.nspname, c.relname), false, true, '')))[1]::text::bigint
from pg_class c join pg_namespace n on n.oid = c.relnamespace
where %(s)s and c.relkind = 'r' and not c.relispartition
order by 1
""" % {'s': USER_SCHEMAS}

DENIALS = [
    ('UPDATE строки журнала', "update audit_admin.audit_log set action = action where (id, occurred_at) = (select id, occurred_at from audit_admin.audit_log limit 1)"),
    ('DELETE строки журнала', "delete from audit_admin.audit_log where (id, occurred_at) = (select id, occurred_at from audit_admin.audit_log limit 1)"),
    ('TRUNCATE журнала', 'truncate audit_admin.audit_log'),
    ('TRUNCATE секции журнала', "do $$ begin execute format('truncate %s', (select tableoid::regclass::text from audit_admin.audit_log limit 1)); end $$"),
]


# ---------------------------------------------------------------------------------------------------------------------------
# Чистые функции (их проверяют модульные тесты без Docker)
# ---------------------------------------------------------------------------------------------------------------------------
def parse_rows(output):
    """Вывод `psql -tA -F '|'` -> список списков строк. Пустые строки пропускаются."""
    return [line.split('|') for line in output.splitlines() if line.strip()]


def diff_rows(source, copy, what):
    """Расхождения двух выборок (списков строк) по ключу из всех столбцов, кроме последних: пустой список, если выборки равны."""
    a, b = [tuple(r) for r in source], [tuple(r) for r in copy]
    problems = []
    for row in a:
        if row not in b:
            problems.append('%s: в исходной базе есть %s, в восстановленной нет такой же' % (what, '|'.join(row)))
    for row in b:
        if row not in a:
            problems.append('%s: в восстановленной базе есть %s, в исходной нет такой же' % (what, '|'.join(row)))
    return problems


def fingerprint(path):
    """Отпечаток дампа для отчёта: размер и первые 12 знаков SHA-256."""
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return os.path.getsize(path), h.hexdigest()[:12]


def denied(result):
    """Отклонила ли база операцию именно триггером неизменяемости (а не, например, отсутствием прав или таблицы)."""
    return result.returncode != 0 and DENIED in (result.stderr + result.stdout)


# ---------------------------------------------------------------------------------------------------------------------------
# Работа с PostgreSQL стенда
# ---------------------------------------------------------------------------------------------------------------------------
class Drill:
    def __init__(self, debug=False, run=None):
        self.debug = debug
        self.run = run or self._run

    def compose(self, args):
        base = ['docker', 'compose', '-f', 'compose.yaml'] + (['-f', 'compose.debug.yaml'] if self.debug else [])
        return base + ['--profile', '*'] + args

    @staticmethod
    def _run(cmd, stdin=None, stdout=None, timeout=600):
        return subprocess.run(cmd, cwd=REPO, stdin=stdin, stdout=stdout or subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=stdout is None, timeout=timeout)

    def psql(self, database, sql, check=True, timeout=120):
        cmd = self.compose(['exec', '-T', 'postgres', 'psql', '-U', 'postgres', '-d', database, '-tA', '-F', '|', '-v', 'ON_ERROR_STOP=1', '-c', sql])
        r = self.run(cmd, timeout=timeout)
        if check and r.returncode != 0:
            sys.exit('psql (%s): %s' % (database, (r.stderr or r.stdout).strip()[-400:]))
        return r

    def rows(self, database, sql):
        return parse_rows(self.psql(database, sql).stdout)

    def drop_copy(self):
        self.psql('postgres', 'drop database if exists %s with (force)' % COPY)

    def dump(self):
        os.makedirs(os.path.dirname(DUMP), exist_ok=True)
        started = time.time()
        with open(DUMP, 'wb') as out:
            r = self.run(self.compose(['exec', '-T', 'postgres', 'pg_dump', '-U', 'postgres', '-Fc', '-d', SOURCE]), stdout=out, timeout=900)
        if r.returncode != 0:
            sys.exit('pg_dump: %s' % r.stderr.decode('utf-8', 'replace').strip()[-400:])
        return time.time() - started

    def restore(self):
        self.drop_copy()
        self.psql('postgres', 'create database %s' % COPY)
        started = time.time()
        with open(DUMP, 'rb') as dump:
            r = self.run(self.compose(['exec', '-T', 'postgres', 'pg_restore', '-U', 'postgres', '--exit-on-error', '-d', COPY]), stdin=dump, timeout=900)
        if r.returncode != 0:
            sys.exit('pg_restore: %s' % r.stderr.strip()[-400:])
        return time.time() - started

    def cutoff(self):
        """Граница сверки журнала: на десять секунд раньше начала копирования (запись, начатая позже, могла не попасть в снимок дампа)."""
        return self.rows('postgres', "select (clock_timestamp() - interval '%d seconds')::text" % AGE_SECONDS)[0][0]

    def snapshot(self, database, cutoff):
        return {
            'audit': self.rows(database, AUDIT_SECTIONS % cutoff),
            'schema': self.rows(database, SCHEMA_PARITY),
            'counts': self.rows(database, ROW_COUNTS),
        }

    def immutability(self, database):
        """[(название, отклонено ли триггером)] для UPDATE, DELETE и TRUNCATE журнала и секции."""
        return [(name, denied(self.psql(database, sql, check=False))) for name, sql in DENIALS]


def report(drill, keep):
    """Полный ход учебного восстановления: печатает факты, возвращает 0 при полном совпадении и 1 при расхождениях."""
    started = time.time()
    cutoff = drill.cutoff()
    print('учебное восстановление %s -> %s (граница сверки журнала: записи до %s)' % (SOURCE, COPY, cutoff))
    t_dump = drill.dump()
    size, digest = fingerprint(DUMP)
    print('дамп: %.1f с, %d байт (sha256 %s…), %s' % (t_dump, size, digest, os.path.relpath(DUMP, REPO)))
    t_restore = drill.restore()
    print('восстановление: %.1f с' % t_restore)
    problems = []
    try:
        src, cpy = drill.snapshot(SOURCE, cutoff), drill.snapshot(COPY, cutoff)
        audit = sum(int(r[1]) for r in src['audit'])
        print('журнал аудита: секций с записями %d, записей %d' % (len(src['audit']), audit))
        if not src['audit']:
            problems.append('в журнале аудита нет записей: сверять нечего, выполните проверки стенда (make audit-check)')
        problems += diff_rows(src['audit'], cpy['audit'], 'журнал аудита (секция, число, сумма)')
        problems += diff_rows(src['schema'], cpy['schema'], 'схема (схема, объект, число)')
        later = diff_rows(src['counts'], cpy['counts'], 'строки таблицы')
        if later:
            print('строки таблиц разошлись (платформа писала в базу во время копирования, не ошибка): %d' % len(later))
        for name, refused in drill.immutability(COPY):
            print('  %s на восстановленной базе: %s' % (name, 'отклонён триггером' if refused else 'НЕ ОТКЛОНЁН'))
            if not refused:
                problems.append('%s на восстановленной базе не отклонён триггером неизменяемости (ST-09)' % name)
    finally:
        if not keep:
            drill.drop_copy()
    total = time.time() - started
    print('итого %.1f с (ориентир, не зачёт RTO 1 час: NFT-4.1 засчитывается на сервере в Ф6)' % total)
    for p in problems:
        print('РАСХОЖДЕНИЕ %s' % p)
    print('результат: %s' % ('база восстановлена, журнал и схема совпали' if not problems else 'расхождений %d' % len(problems)))
    return 1 if problems else 0


def main(argv=None):
    p = argparse.ArgumentParser(description='Учебное восстановление базы платформы')
    sub = p.add_subparsers(dest='cmd', required=True)
    r = sub.add_parser('run')
    r.add_argument('--keep', action='store_true', help='оставить восстановленную базу %s' % COPY)
    sub.add_parser('drop')
    for s in sub.choices.values():
        s.add_argument('--debug', action='store_true', help='использовать compose.debug.yaml, как make DEBUG=1')
    args = p.parse_args(argv)
    drill = Drill(debug=args.debug)
    if args.cmd == 'drop':
        drill.drop_copy()
        print('база %s удалена' % COPY)
        return 0
    return report(drill, args.keep)


if __name__ == '__main__':
    sys.exit(main())
