# -*- coding: utf-8 -*-
"""Проверка физической модели данных против документов (шаг 12 Ф2).

Требует PostgreSQL 16 с базами, созданными из docs/07-data/ddl/*.sql (ключ --apply создаёт их заново).

Группы проверок:
  A. Атрибуты логической модели и столбцы: карта docs/07-data/attribute-map.yaml, сверка в обе стороны.
  B. Соглашения conventions.md: имена, префиксы ограничений, типы, словарь статусов, переходы статусов против SM-диаграмм,
     нет внешних ключей между схемами.
  C. Комментарии на каждой таблице, столбце и индексе (из них строятся описания схем).
  D. Инварианты INV-01 … INV-44: каждый назван в docs/07-data/README.md, имена ограничений, индексов и триггеров существуют.

Запуск: python3 check_datamodel.py [--apply]     Переменные: REPO, PGHOST, PGPORT, PGUSER
"""
import os
import re
import subprocess
import sys

import yaml

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pgmini import connect  # noqa: E402

REPO = os.environ.get('REPO') or os.path.abspath(os.path.join(os.path.dirname(os.path.realpath(__file__)), '..', '..', '..'))
HOST, PORT = os.environ.get('PGHOST', '/tmp'), int(os.environ.get('PGPORT', '5433'))
USER = os.environ.get('PGUSER', 'postgres')
DBS = ['catalog_db', 'inventory_db', 'order_db', 'payment_db', 'delivery_db', 'platform_db']
SERVICE_DB = {'catalog-service': 'catalog_db', 'inventory-service': 'inventory_db', 'order-service': 'order_db',
              'payment-service': 'payment_db', 'delivery-service': 'delivery_db', 'platform-service': 'platform_db'}
SYSTEM_SCHEMAS = ('pg_catalog', 'information_schema', 'pg_toast')

problems = []
stats = {}


def err(msg):
    problems.append(msg)


def db_conn(db):
    return connect(host=HOST, port=PORT, user=USER, dbname=db)


def apply_ddl():
    for svc, db in SERVICE_DB.items():
        base = ['psql', '-h', HOST, '-p', str(PORT), '-U', USER, '-v', 'ON_ERROR_STOP=1', '-q']
        subprocess.run(base + ['-d', 'postgres', '-c', 'drop database if exists %s' % db, '-c', 'create database %s' % db], check=True, capture_output=True)
        r = subprocess.run(base + ['-d', db, '-f', os.path.join(REPO, 'docs/07-data/ddl', svc + '.sql')], capture_output=True, text=True)
        if r.returncode != 0:
            err('DDL %s не выполнился: %s' % (svc, r.stderr.strip()[:300]))


# ------------------------------------------------------------------------------------------------ интроспекция
class Catalog:
    def __init__(self, db):
        c = db_conn(db)
        self.db = db
        self.columns = {}   # (schema, table) -> [(name, type, notnull, default, comment)]
        for r in c.query("""
            select n.nspname, cl.relname, a.attname, format_type(a.atttypid, a.atttypmod), a.attnotnull,
                   pg_get_expr(d.adbin, d.adrelid), col_description(cl.oid, a.attnum)
            from pg_attribute a
            join pg_class cl on cl.oid = a.attrelid and cl.relkind in ('r', 'p') and not cl.relispartition
            join pg_namespace n on n.oid = cl.relnamespace and n.nspname not in ('pg_catalog', 'information_schema', 'pg_toast')
            left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
            where a.attnum > 0 and not a.attisdropped
            order by n.nspname, cl.relname, a.attnum"""):
            self.columns.setdefault((r[0], r[1]), []).append((r[2], r[3], r[4] == 't', r[5], r[6]))
        self.table_comments = {(r[0], r[1]): r[2] for r in c.query("""
            select n.nspname, cl.relname, obj_description(cl.oid, 'pg_class')
            from pg_class cl join pg_namespace n on n.oid = cl.relnamespace
            where cl.relkind in ('r', 'p') and not cl.relispartition and n.nspname not in ('pg_catalog', 'information_schema', 'pg_toast')""")}
        self.constraints = []   # (schema, table, name, type, def, confschema)
        for r in c.query("""
            select n.nspname, cl.relname, co.conname, co.contype, pg_get_constraintdef(co.oid), fn.nspname
            from pg_constraint co
            join pg_class cl on cl.oid = co.conrelid and not cl.relispartition
            join pg_namespace n on n.oid = cl.relnamespace and n.nspname not in ('pg_catalog', 'information_schema')
            left join pg_class fc on fc.oid = co.confrelid
            left join pg_namespace fn on fn.oid = fc.relnamespace
            where co.contype in ('p', 'f', 'u', 'c') and co.conparentid = 0"""):
            self.constraints.append(r)
        self.indexes = []       # (schema, table, name, def, comment, unique, primary)
        for r in c.query("""
            select n.nspname, t.relname, i.relname, pg_get_indexdef(i.oid), obj_description(i.oid, 'pg_class'), x.indisunique, x.indisprimary
            from pg_index x
            join pg_class i on i.oid = x.indexrelid and not i.relispartition
            join pg_class t on t.oid = x.indrelid and not t.relispartition
            join pg_namespace n on n.oid = t.relnamespace and n.nspname not in ('pg_catalog', 'information_schema', 'pg_toast')"""):
            self.indexes.append(r)
        self.triggers = []      # (schema, table, name, def)
        for r in c.query("""
            select n.nspname, cl.relname, tg.tgname, pg_get_triggerdef(tg.oid)
            from pg_trigger tg join pg_class cl on cl.oid = tg.tgrelid and not cl.relispartition
            join pg_namespace n on n.oid = cl.relnamespace and n.nspname not in ('pg_catalog', 'information_schema')
            where not tg.tgisinternal"""):
            self.triggers.append(r)
        self.names = ({x[2] for x in self.constraints} | {x[2] for x in self.indexes} | {x[2] for x in self.triggers})
        c.close()

    def table(self, schema_table):
        s, t = schema_table.split('.')
        return self.columns.get((s, t))


# ------------------------------------------------------------------------------------------------ A. атрибуты
def parse_er(path):
    text = open(path, encoding='utf-8').read()
    entities = {}
    for block in re.findall(r'```mermaid\n(erDiagram.*?)```', text, re.S):
        cur = None
        for line in block.split('\n'):
            m = re.match(r'^\s{4}([A-Z_]+)\["[^"]*"\]\s*\{\s*$', line)
            if m:
                cur = m.group(1)
                entities.setdefault(cur, set())
                continue
            if cur and re.match(r'^\s{4}\}\s*$', line):
                cur = None
                continue
            if cur:
                m = re.match(r'^\s{8}\S+\s+(\S+)', line)
                if m:
                    entities[cur].add(m.group(1))
    return entities


def check_attributes(cats):
    er = parse_er(os.path.join(REPO, 'docs/04-domain/logical-er.md'))
    amap = yaml.safe_load(open(os.path.join(REPO, 'docs/07-data/attribute-map.yaml'), encoding='utf-8'))
    ents = amap['entities']
    for e in sorted(set(er) - set(ents)):
        err('A: сущность %s есть в логической ER, но нет в карте attribute-map.yaml' % e)
    for e in sorted(set(ents) - set(er)):
        err('A: сущность %s есть в карте, но нет в логической ER' % e)
    n_attr = n_cols = n_tables = 0
    used_tables = set()
    for e, spec in sorted(ents.items()):
        if e not in er:
            continue
        rel = spec.get('release')
        if rel != 'R1':
            if rel not in ('R2', 'R3'):
                err('A: у сущности %s не указан релиз' % e)
            if 'table' in spec:
                err('A: сущность %s не входит в R1, но у неё есть таблица' % e)
            continue
        db, table = spec['db'], spec['table']
        cat = cats[db]
        cols = cat.table(table)
        used_tables.add((db, table))
        n_tables += 1
        if cols is None:
            err('A: таблицы %s нет в базе %s (сущность %s)' % (table, db, e))
            continue
        colnames = {c[0] for c in cols}
        attrs = spec.get('attributes', {})
        if set(attrs) != er[e]:
            for a in sorted(er[e] - set(attrs)):
                err('A: атрибут %s.%s есть в логической ER, но нет в карте' % (e, a))
            for a in sorted(set(attrs) - er[e]):
                err('A: атрибут %s.%s есть в карте, но нет в логической ER' % (e, a))
        covered = set()
        for a, target in attrs.items():
            n_attr += 1
            if isinstance(target, str):
                targets = [target]
            elif isinstance(target, list):
                targets = target
            elif isinstance(target, dict) and 'release' in target:
                if target['release'] not in ('R2', 'R3'):
                    err('A: %s.%s: неизвестный релиз %s' % (e, a, target['release']))
                continue
            elif isinstance(target, dict) and 'table' in target:
                other = cat.table(target['table'])
                if other is None:
                    err('A: %s.%s: таблицы %s нет' % (e, a, target['table']))
                used_tables.add((db, target['table']))
                continue
            elif isinstance(target, dict) and 'derived' in target:
                for d in target['derived']:
                    s, t, col = d.split('.')
                    other = cat.table(s + '.' + t)
                    if other is None or col not in {c[0] for c in other}:
                        err('A: %s.%s: производное значение, столбца %s нет' % (e, a, d))
                    used_tables.add((db, s + '.' + t))
                continue
            else:
                err('A: %s.%s: непонятное значение в карте %r' % (e, a, target))
                continue
            for t in targets:
                if t not in colnames:
                    err('A: %s.%s ведёт на столбец %s.%s, которого нет' % (e, a, table, t))
                covered.add(t)
        tech = spec.get('technical', {}) or {}
        for t, reason in tech.items():
            if t not in colnames:
                err('A: технический столбец %s.%s из карты не существует' % (table, t))
            if not isinstance(reason, str) or len(reason) < 10:
                err('A: у технического столбца %s.%s нет объяснения' % (table, t))
            if t in covered:
                err('A: столбец %s.%s указан и как атрибут, и как технический' % (table, t))
        for cname in sorted(colnames - covered - set(tech)):
            err('A: столбец %s.%s есть в базе, но нет в доменной модели и не назван техническим' % (table, cname))
        n_cols += len(colnames)
    # таблицы без сущности и служебные
    declared = {(t['db'], t['table']) for t in amap.get('tables', [])}
    for t in amap.get('tables', []):
        if not t.get('reason') or len(t['reason']) < 10:
            err('A: у таблицы %s нет объяснения' % t['table'])
        if cats[t['db']].table(t['table']) is None:
            err('A: таблица %s из карты отсутствует в базе %s' % (t['table'], t['db']))
        n_tables += 1
    shared = [s['table'] for s in amap.get('shared', [])]
    for db in DBS:
        for (s, t) in cats[db].columns:
            full = s + '.' + t
            if full in shared:
                continue
            if (db, full) in used_tables or (db, full) in declared:
                continue
            err('A: таблица %s.%s в базе %s не связана ни с одной сущностью и не объяснена в карте' % (s, t, db))
        for sh in shared:
            if cats[db].table(sh) is None:
                err('A: служебной таблицы %s нет в базе %s' % (sh, db))
    stats['A'] = 'сущностей R1: %d, атрибутов: %d, столбцов основных таблиц: %d, таблиц в карте: %d' % (
        sum(1 for s in ents.values() if s.get('release') == 'R1'), n_attr, n_cols, n_tables + len(shared))


# ------------------------------------------------------------------------------------------------ B. соглашения
STATUS_ENTITY = {
    'orders.orders': 'Заказ', 'payments.payment': 'Платёж', 'inventory.key': 'Ключ', 'catalog.product': 'Товар',
    'seller_onboarding.seller_profile': 'Профиль продавца', 'delivery.delivery': 'Выдача', 'support.ticket': 'Обращение',
    'inventory.reservation': 'Резерв', 'identity.user_account': 'Пользователь',
}
SM_FILE = {'orders.orders': 'SM-01', 'payments.payment': 'SM-02', 'inventory.key': 'SM-03', 'catalog.product': 'SM-04',
           'seller_onboarding.seller_profile': 'SM-05', 'delivery.delivery': 'SM-06', 'support.ticket': 'SM-07', 'inventory.reservation': 'SM-08'}


def status_dictionary():
    text = open(os.path.join(REPO, 'docs/05-architecture/conventions.md'), encoding='utf-8').read()
    sec = text.split('### 7.2. Словарь статусов', 1)[1].split('### 7.3', 1)[0]
    out = {}
    for line in sec.split('\n'):
        cells = [x.strip() for x in line.strip().strip('|').split('|')]
        if len(cells) == 3 and cells[0] not in ('Сущность', '---'):
            name = re.sub(r' \(R\d\)', '', cells[0])
            out[name] = dict(re.findall(r'«([^»]+)» → `([a-z_]+)`', cells[2]))
    return out


def sm_transitions(sm_id, ru2code):
    d = os.path.join(REPO, 'docs/03-processes')
    f = [x for x in os.listdir(d) if x.startswith(sm_id + '-') and x.endswith('.md')][0]
    text = open(os.path.join(d, f), encoding='utf-8').read()
    pairs, initial = set(), set()
    for line in text.split('\n'):
        cells = [x.strip() for x in line.strip().strip('|').split('|')]
        if len(cells) >= 6 and re.match(r'^T\d+$', cells[0]):
            r2 = cells[3].startswith('R2.') or cells[3].startswith('R3.')
            frm = [x.strip() for x in cells[1].split(',')]
            to = cells[2].strip()
            if r2:
                continue
            for x in frm:
                if x == '(начало)':
                    initial.add(ru2code[to])
                elif x != to:
                    pairs.add('%s>%s' % (ru2code[x], ru2code[to]))
    return pairs, initial


def check_conventions(cats):
    dictionary = status_dictionary()
    money_cols = {'amount', 'price', 'unit_price', 'commission'}
    id_exceptions = {'gateway_payment_id', 'gateway_refund_id', 'gateway_event_id', 'dek_id', 'aggregate_id', 'object_id', 'consumer'}
    n_checked = 0
    for db in DBS:
        cat = cats[db]
        con_by_table = {}
        for (s, t, name, typ, d, fs) in cat.constraints:
            con_by_table.setdefault((s, t), []).append((name, typ, d, fs))
            prefix = {'p': 'pk_', 'f': 'fk_', 'u': 'uq_', 'c': 'ck_'}[typ]
            if not name.startswith(prefix):
                err('B: %s: ограничение %s должно начинаться с %s' % (db, name, prefix))
            if not re.match(r'^[a-z][a-z0-9_]*$', name):
                err('B: %s: имя ограничения %s не в snake_case' % (db, name))
            if typ == 'f' and fs != s:
                err('B: %s: внешний ключ %s ведёт из схемы %s в схему %s, между модулями только идентификаторы' % (db, name, s, fs))
            n_checked += 1
        for (s, t, name, d, comment, uniq, prim) in cat.indexes:
            if prim:
                continue
            pref = 'uq_' if uniq else 'ix_'
            if not name.startswith(pref) and not (uniq and name.startswith('uq_')):
                err('B: %s: индекс %s должен начинаться с %s' % (db, name, pref))
            n_checked += 1
        for (s, t), cols in cat.columns.items():
            if not re.match(r'^[a-z][a-z0-9_]*$', t):
                err('B: %s: имя таблицы %s.%s не в snake_case' % (db, s, t))
            if s != 'public' and not any(x[1] == 'p' for x in con_by_table.get((s, t), [])):
                err('B: %s: у таблицы %s.%s нет первичного ключа' % (db, s, t))
            names = [c[0] for c in cols]
            for (name, typ, notnull, default, comment) in cols:
                if not re.match(r'^[a-z][a-z0-9_]*$', name):
                    err('B: %s: имя столбца %s.%s.%s не в snake_case' % (db, s, t, name))
                if typ.startswith('timestamp') and 'with time zone' not in typ:
                    err('B: %s: столбец %s.%s.%s без часового пояса, нужен timestamptz (conventions 5.2)' % (db, s, t, name))
                if (name == 'id' or (name.endswith('_id') and name not in id_exceptions)) and typ not in ('uuid', 'integer', 'bigint'):
                    err('B: %s: столбец %s.%s.%s должен быть uuid (conventions 3.1)' % (db, s, t, name))
                if name.endswith('_id') and name not in id_exceptions and typ == 'bigint' and (s, t) != ('public', 'outbox'):
                    err('B: %s: идентификатор %s.%s.%s не должен быть bigint' % (db, s, t, name))
                if name in money_cols and typ != 'bigint':
                    err('B: %s: сумма %s.%s.%s должна быть bigint в копейках (conventions 4.1)' % (db, s, t, name))
                if name in money_cols and 'currency' not in names and (s, t) != ('payments', 'refund_attempt'):
                    err('B: %s: у таблицы %s.%s есть сумма, но нет столбца currency (conventions 4.3)' % (db, s, t))
                if name == 'currency':
                    if typ != 'character(3)':
                        err('B: %s: currency в %s.%s должен быть char(3)' % (db, s, t))
                    if not any(x[1] == 'c' and "currency" in x[2] and "'RUB'" in x[2] for x in con_by_table.get((s, t), [])):
                        err('B: %s: у %s.%s нет проверки currency = RUB (conventions 4.3)' % (db, s, t))
                if name.endswith('_bp') and typ != 'integer':
                    err('B: %s: проценты %s.%s.%s должны быть целыми базисными пунктами (conventions 4.5)' % (db, s, t, name))
            # статусы: словарь и переходы
            full = s + '.' + t
            if 'status' in names and full in STATUS_ENTITY:
                allowed = set(dictionary[STATUS_ENTITY[full]].values())
                ck = [x for x in con_by_table[(s, t)] if x[0] == 'ck_%s_status' % t]
                if not ck:
                    err('B: %s: у %s нет ограничения ck_%s_status' % (db, full, t))
                else:
                    got = set(re.findall(r"'([a-z_]+)'::text", ck[0][2]))
                    if got != allowed:
                        err('B: %s: допустимые статусы %s %s не совпадают со словарём conventions 7.2 %s' % (db, full, sorted(got), sorted(allowed)))
            if full in SM_FILE:
                ru2code = dictionary[STATUS_ENTITY[full]]
                pairs, initial = sm_transitions(SM_FILE[full], ru2code)
                trg = {x[2]: x[3] for x in cat.triggers if (x[0], x[1]) == (s, t)}
                tr = [d for n, d in trg.items() if n == 'trg_%s_status_transition' % t]
                ini = [d for n, d in trg.items() if n == 'trg_%s_status_initial' % t]
                if not tr or not ini:
                    err('B: %s: у %s нет триггеров начального статуса и переходов' % (db, full))
                else:
                    got_pairs = set(re.findall(r"'([a-z_]+>[a-z_]+)'", tr[0]))
                    got_init = set(re.findall(r"'initial:([a-z_]+)'", ini[0]))
                    if got_pairs != pairs:
                        err('B: %s: переходы %s в триггере и в %s расходятся: лишние в базе %s, не хватает в базе %s' % (
                            db, full, SM_FILE[full], sorted(got_pairs - pairs), sorted(pairs - got_pairs)))
                    if got_init != initial:
                        err('B: %s: начальный статус %s в триггере %s, в %s %s' % (db, full, sorted(got_init), SM_FILE[full], sorted(initial)))
    stats['B'] = 'проверено ограничений, индексов и столбцов по правилам: %d объектов в шести базах' % n_checked


# ------------------------------------------------------------------------------------------------ C. комментарии
def check_comments(cats):
    n = 0
    for db in DBS:
        cat = cats[db]
        for (s, t), cols in cat.columns.items():
            if not cat.table_comments.get((s, t)):
                err('C: %s: у таблицы %s.%s нет комментария' % (db, s, t))
            n += 1
            for (name, typ, nn, default, comment) in cols:
                n += 1
                if not comment or len(comment) < 3:
                    err('C: %s: у столбца %s.%s.%s нет комментария' % (db, s, t, name))
        for (s, t, name, d, comment, uniq, prim) in cat.indexes:
            if prim:
                continue
            n += 1
            if not comment or len(comment) < 10:
                err('C: %s: у индекса %s нет комментария с обоснованием запросом' % (db, name))
        for text in [c[1] for cols in cat.columns.values() for c in cols] and []:
            pass
        # в комментариях нет длинного тире
        for (s, t), cols in cat.columns.items():
            for c in cols:
                if c[4] and ('—' in c[4] or '–' in c[4]):
                    err('C: %s: комментарий столбца %s.%s.%s содержит тире' % (db, s, t, c[0]))
    stats['C'] = 'комментариев проверено: %d' % n


# ------------------------------------------------------------------------------------------------ D. инварианты
METHODS = ('ограничение в базе', 'транзакция', 'проверка в коде', 'событие')


def check_invariants(cats):
    path = os.path.join(REPO, 'docs/07-data/README.md')
    if not os.path.exists(path):
        err('D: нет docs/07-data/README.md')
        return
    text = open(path, encoding='utf-8').read()
    dm = open(os.path.join(REPO, 'docs/04-domain/domain-model.md'), encoding='utf-8').read()
    all_inv = sorted(set(re.findall(r'\|\s*(INV-\d+)\s*\|', dm)))
    names = set()
    for db in DBS:
        names |= cats[db].names
    rows = {}
    for line in text.split('\n'):
        cells = [x.strip() for x in line.strip().strip('|').split('|')]
        if len(cells) == 4 and re.match(r'^INV-\d+$', cells[0]):
            if cells[0] in rows:
                err('D: %s указан в README больше одного раза' % cells[0])
            rows[cells[0]] = cells
    for inv in all_inv:
        if inv not in rows:
            err('D: инвариант %s не назван в таблице способов обеспечения README' % inv)
    for inv in rows:
        if inv not in all_inv:
            err('D: %s есть в README, но нет в доменной модели' % inv)
    n_names = 0
    n_r2 = 0
    for inv, (_, _, method, how) in rows.items():
        ms = [m.strip() for m in method.split(',')]
        for m in ms:
            if m not in METHODS:
                err('D: %s: способ «%s» не из списка %s' % (inv, m, ', '.join(METHODS)))
        refs = re.findall(r'`((?:pk|uq|fk|ck|ix|trg)_[^`\s]*)`', how)
        # имена триггерных проверок вида ck_<таблица>_status_transition существуют только как ограничения-имена ошибок триггера
        for r in refs:
            n_names += 1
            if r not in names and not re.match(r'^ck_[a-z_]+_(status_transition|immutable|snapshot_immutable|issued_at_once|rebind|last_admin|append_only|session_vs_reserve)$', r):
                err('D: %s: в README назван `%s`, такого ограничения, индекса или триггера нет в базах' % (inv, r))
        is_r2 = how.startswith('R2.')
        if is_r2:
            n_r2 += 1
        if 'ограничение в базе' in ms and not refs and not is_r2:
            err('D: %s: способ «ограничение в базе» без названия ограничения, индекса или триггера' % inv)
        if len(how) < 15:
            err('D: %s: нет объяснения механизма' % inv)
    stats['D'] = 'инвариантов: %d (из них R2: %d), проверено имён объектов базы: %d' % (len(rows), n_r2, n_names)


def main():
    if '--apply' in sys.argv:
        apply_ddl()
    cats = {db: Catalog(db) for db in DBS}
    check_attributes(cats)
    check_conventions(cats)
    check_comments(cats)
    check_invariants(cats)
    for k in sorted(stats):
        print('%s: %s' % (k, stats[k]))
    for p in problems:
        print('ПРОБЛЕМА', p)
    print('проблем: %d' % len(problems))
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
