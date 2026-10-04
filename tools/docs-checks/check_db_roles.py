# -*- coding: utf-8 -*-
"""Сверка баз и ролей PostgreSQL стенда (infra/postgres/roles.json) с моделью данных, секретами и бюджетом соединений.

    python3 tools/docs-checks/check_db_roles.py

Что проверяется:
  - базы: по одной на сервис (из tools/docs-checks/db/build.py) и keycloak_db; владелец базы это её роль-мигратор (у Keycloak своя роль);
  - рабочие роли app_*: ровно те, что создают и которым выдают права миграции (разделы «Роли и права» в частях DDL), и каждая
    привязана к базе того сервиса, чья это часть; нет роли без прав и прав без роли;
  - секреты: у роли `<имя>` секрет `db_<имя>` (migrator_x -> db_migrator_x, app_x -> db_app_x, keycloak -> db_keycloak), он есть в
    infra/pki/inventory.json, читают его сервис базы и postgres (создаёт роль), у Keycloak keycloak и postgres; секретов db_* без роли нет;
  - соединения: сумма пределов рабочих ролей сервиса не больше пула 8, у Keycloak не больше 10 (memory-budget.md, раздел 6),
    рабочие роли и Keycloak вместе укладываются в бюджет 65 из max_connections (за вычетом 2 на резервное копирование и 5 на администратора),
    вместе с миграторами и зарезервированными соединениями суперпользователя не выше max_connections из postgresql.conf;
  - файлы: init/10-databases-and-roles.sql существует и читает roles.json по пути, который смонтирован в compose.yaml; каталог init
    смонтирован в /docker-entrypoint-initdb.d только для чтения; у postgres в compose.yaml есть все секреты ролей.
"""
import json
import os
import re
import sys

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, 'db'))
import docslib as L  # noqa: E402
import build as ddl_build  # noqa: E402

ROLES = os.path.join(L.REPO, 'infra', 'postgres', 'roles.json')
INIT = os.path.join(L.REPO, 'infra', 'postgres', 'init', '10-databases-and-roles.sql')
PGCONF = os.path.join(L.REPO, 'infra', 'postgres', 'postgresql.conf')
INVENTORY = os.path.join(L.REPO, 'infra', 'pki', 'inventory.json')
COMPOSE = os.environ.get('DGM_COMPOSE_FILE') or os.path.join(L.REPO, 'compose.yaml')
MB = os.path.join(L.OPS_DIR, 'memory-budget.md')

KEYCLOAK = ('keycloak_db', 'keycloak', 'db_keycloak')
CONFIG_PATH = '/etc/dgm/postgres/roles.json'
INIT_MOUNT = '/docker-entrypoint-initdb.d'
SERVICE_OF_DB = {db: svc for svc, db, _part, _m in ddl_build.SERVICES}


def ddl_roles(part):
    """Роли app_*, упомянутые в части DDL: создаваемые и получающие права (grant ... to ...)."""
    text = L.read(os.path.join(ddl_build.PARTS, part + '.sql'))
    m = re.search(r'^-- (?:Роли и права|Роль и права).*', text, re.M)
    access = text[m.start():] if m else ''
    created = set(re.findall(r"'(app_[a-z_]+)'", access))
    granted = set()
    for stmt in re.findall(r'^grant .*? to ([^;]+);', access, re.M | re.S):
        granted |= set(re.findall(r'\bapp_[a-z_]+\b', stmt))
    return created, granted


def main():
    rep = L.Report('db roles')
    cfg = json.load(open(ROLES, encoding='utf-8'))
    inv = {s['name']: s for s in json.load(open(INVENTORY, encoding='utf-8'))['secrets']}
    roles = {r['name']: r for r in cfg['roles']}
    dbs = {d['name']: d for d in cfg['databases']}
    if len(roles) != len(cfg['roles']):
        rep.err('roles.json', 'имена ролей повторяются')

    # --- базы и владельцы
    want_dbs = set(SERVICE_OF_DB) | {KEYCLOAK[0]}
    if set(dbs) != want_dbs:
        rep.err('roles.json, databases', 'базы %s, ожидались %s (по одной на сервис и keycloak_db)' % (sorted(dbs), sorted(want_dbs)))
    for name, d in dbs.items():
        o = roles.get(d['owner'])
        if not o:
            rep.err('roles.json, %s' % name, 'владелец %s не описан среди ролей' % d['owner'])
            continue
        if o['database'] != name:
            rep.err('roles.json, %s' % name, 'владелец %s привязан к другой базе (%s)' % (d['owner'], o['database']))
        if name == KEYCLOAK[0]:
            if d['owner'] != KEYCLOAK[1]:
                rep.err('roles.json, %s' % name, 'владелец базы Keycloak должен быть %s' % KEYCLOAK[1])
        else:
            short = SERVICE_OF_DB[name]
            want_owner = 'migrator_' + short.replace('-service', '')
            if d['owner'] != want_owner:
                rep.err('roles.json, %s' % name, 'владелец %s, ожидался %s (роль миграций сервиса)' % (d['owner'], want_owner))

    # --- рабочие роли против DDL
    ddl_all = {}
    for svc, db, part, _m in ddl_build.SERVICES:
        created, granted = ddl_roles(part)
        if created != granted:
            rep.err('parts/%s.sql' % part, 'роли, которым выданы права, и роли, которые создаются, различаются: %s' % sorted(created ^ granted))
        have = {n for n, r in roles.items() if n.startswith('app_') and r['database'] == db}
        if have != created:
            rep.err('roles.json, %s' % db, 'рабочие роли %s, а в DDL (%s) %s' % (sorted(have), part + '.sql', sorted(created)))
        for r in created:
            ddl_all[r] = db
    for n, r in roles.items():
        if n.startswith('app_') and n not in ddl_all:
            rep.err('roles.json, %s' % n, 'роли нет в разделах «Роли и права» DDL')
        if not (n.startswith('app_') or n.startswith('migrator_') or n == KEYCLOAK[1]):
            rep.err('roles.json, %s' % n, 'имя роли должно начинаться с app_ или migrator_ (или быть keycloak)')

    # --- секреты
    svc_of = lambda db: None if db == KEYCLOAK[0] else SERVICE_OF_DB[db]  # noqa: E731
    used = set()
    for n, r in roles.items():
        want = 'db_' + n if n != KEYCLOAK[1] else KEYCLOAK[2]
        if r['secret'] != want:
            rep.err('roles.json, %s' % n, 'секрет %s, ожидался %s' % (r['secret'], want))
        s = inv.get(r['secret'])
        if not s:
            rep.err('roles.json, %s' % n, 'секрета %s нет в infra/pki/inventory.json' % r['secret'])
            continue
        used.add(r['secret'])
        reader = svc_of(r['database']) or 'keycloak'
        if sorted(s['readers']) != sorted([reader, 'postgres']):
            rep.err('inventory.json, %s' % r['secret'], 'читатели %s, ожидались %s' % (sorted(s['readers']), sorted([reader, 'postgres'])))
    for name in inv:
        if name.startswith('db_') and name != 'db_postgres_admin' and name not in used:
            rep.err('inventory.json, %s' % name, 'секрет базы без роли в roles.json')
    if 'db_postgres_admin' not in inv:
        rep.err('inventory.json', 'нет секрета db_postgres_admin')

    # --- соединения
    mb = L.read(MB)
    m = re.search(r'Пул соединений каждого сервиса до (\d+), шесть сервисов дают (\d+), Keycloak (\d+), резервное копирование (\d+), администратор (\d+), итого (\d+) из (\d+)', mb)
    if not m:
        rep.err('memory-budget.md', 'не найдена строка расчёта соединений в разделе 6 (решение 2)')
    else:
        pool, six, kc, backup, admin, total, maxc = map(int, m.groups())
        if pool * 6 != six or six + kc + backup + admin != total:
            rep.err('memory-budget.md', 'арифметика расчёта соединений не сходится: %d*6, %d+%d+%d+%d, итого %d' % (pool, six, kc, backup, admin, total))
        per_service = {}
        for n, r in roles.items():
            if n.startswith('app_'):
                per_service[r['database']] = per_service.get(r['database'], 0) + r['limit']
        for db, lim in per_service.items():
            if lim > pool:
                rep.err('roles.json, %s' % db, 'сумма пределов рабочих ролей %d больше пула сервиса %d' % (lim, pool))
        if roles[KEYCLOAK[1]]['limit'] > kc:
            rep.err('roles.json, keycloak', 'предел %d больше пула Keycloak %d' % (roles[KEYCLOAK[1]]['limit'], kc))
        apps = sum(per_service.values()) + roles[KEYCLOAK[1]]['limit']
        if apps + backup + admin > total:
            rep.err('roles.json', 'рабочие роли и Keycloak %d + резерв %d + администратор %d больше бюджета %d' % (apps, backup, admin, total))
        conf = L.read(PGCONF)
        mc = re.search(r'^max_connections\s*=\s*(\d+)', conf, re.M)
        if not mc or int(mc.group(1)) != maxc:
            rep.err('postgresql.conf', 'max_connections %s, в memory-budget.md %d' % (mc.group(1) if mc else 'не задан', maxc))
        sr = re.search(r'^superuser_reserved_connections\s*=\s*(\d+)', conf, re.M)
        reserved = int(sr.group(1)) if sr else 3
        migr = sum(r['limit'] for n, r in roles.items() if n.startswith('migrator_'))
        if apps + migr + backup + admin + reserved > maxc:
            rep.err('roles.json', 'пределы всех ролей %d + резерв суперпользователя %d больше max_connections %d' % (apps + migr + backup + admin, reserved, maxc))
        rep.fact('соединения: рабочие роли и Keycloak %d, миграторы %d (только при старте), бюджет %d из %d' % (apps, migr, total, maxc))

    # --- файлы и compose
    if not os.path.exists(INIT):
        rep.err('infra/postgres/init', 'нет 10-databases-and-roles.sql')
    else:
        sql = L.read(INIT)
        if CONFIG_PATH not in sql:
            rep.err('10-databases-and-roles.sql', 'не читает %s' % CONFIG_PATH)
        for needle in ('nosuperuser', 'nocreaterole', 'nocreatedb', 'connection limit', 'revoke all on database', 'pg_read_file'):
            if needle not in sql:
                rep.err('10-databases-and-roles.sql', 'нет «%s»' % needle)
        if re.search(r"password\s+'[^']+'", sql, re.I):
            rep.err('10-databases-and-roles.sql', 'в файле записан пароль, пароли берутся только из секретов')
    comp = yaml.safe_load(open(COMPOSE, encoding='utf-8'))
    pg = (comp.get('services') or {}).get('postgres')
    if not pg:
        rep.err('compose.yaml', 'нет контейнера postgres')
    else:
        vols = [str(v) for v in pg.get('volumes') or []]
        if not any(v.endswith(':%s:ro' % INIT_MOUNT) and v.startswith('./infra/postgres/init') for v in vols):
            rep.err('compose.yaml, postgres', 'каталог ./infra/postgres/init должен быть смонтирован в %s только для чтения' % INIT_MOUNT)
        if not any(v.endswith(':/etc/dgm/postgres:ro') and v.startswith('./infra/postgres:') for v in vols):
            rep.err('compose.yaml, postgres', 'каталог ./infra/postgres должен быть смонтирован в /etc/dgm/postgres (там лежит roles.json)')
        secrets = set(pg.get('secrets') or [])
        for n, r in roles.items():
            if r['secret'] not in secrets:
                rep.err('compose.yaml, postgres', 'у контейнера нет секрета %s роли %s' % (r['secret'], n))
        if 'db_postgres_admin' not in secrets:
            rep.err('compose.yaml, postgres', 'нет секрета db_postgres_admin')

    rep.fact('базы и роли: %d баз, %d ролей (%d миграторов, %d рабочих, keycloak)'
             % (len(dbs), len(roles), sum(1 for n in roles if n.startswith('migrator_')), sum(1 for n in roles if n.startswith('app_'))))
    return rep.finish()


if __name__ == '__main__':
    sys.exit(main())
