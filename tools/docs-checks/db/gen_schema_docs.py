#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Генератор описаний схем: docs/07-data/<сервис>-schema.md из живой базы (шаг 12 Ф2).

Описание строится из системного каталога PostgreSQL, комментариев `comment on ...` и таблицы инвариантов README,
поэтому не расходится с реальной схемой. Базы должны быть созданы из docs/07-data/ddl/*.sql (apply.sh).
Запуск: python3 gen_schema_docs.py [сервис ...]      Переменные: REPO, PGHOST, PGPORT
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pgmini import connect  # noqa: E402

REPO = os.environ.get('REPO') or os.path.abspath(os.path.join(os.path.dirname(os.path.realpath(__file__)), '..', '..', '..'))
HOST, PORT = os.environ.get('PGHOST', '/tmp'), int(os.environ.get('PGPORT', '5433'))

SERVICES = {
    'catalog-service': dict(
        db='catalog_db', title='Каталог и подключение продавцов',
        about='Сервис владеет профилями продавцов с документами заявки и каталогом товаров. Модуль `seller_onboarding` ведёт заявку продавца ([SM-05](../03-processes/SM-05-seller-profile.md)), модуль `catalog` ведёт товары ([SM-04](../03-processes/SM-04-product.md)) и копию остатка для витрины, которую обновляет событие `stock.changed` из «Остатков и ключей».',
        roles='`app_seller_onboarding` (схема `seller_onboarding`), `app_catalog` (схема `catalog`)'),
    'inventory-service': dict(
        db='inventory_db', title='Остатки и ключи',
        about='Сервис владеет резервами ([SM-08](../03-processes/SM-08-reservation.md)), зашифрованными ключами ([SM-03](../03-processes/SM-03-key.md)), версиями ключа данных шифрования и копией владельца и способа выдачи товара из событий каталога. Открытого значения ключа в базе нет нигде ([ADR-009](../05-architecture/adr/ADR-009-key-encryption-hmac.md)).',
        roles='`app_inventory` (схема `inventory`), без права `DELETE` на таблицу ключей'),
    'order-service': dict(
        db='order_db', title='Заказы',
        about='Сервис владеет заказом ([SM-01](../03-processes/SM-01-order.md)) и ведёт сагу оформления: резерв, платёжная сессия, оплата, выдача ([ADR-004](../05-architecture/adr/ADR-004-saga-purchase.md)). Снимки товара, цены, комиссии и адреса записываются при создании и не меняются.',
        roles='`app_orders` (схема `orders`)'),
    'payment-service': dict(
        db='payment_db', title='Платежи',
        about='Сервис владеет платежом ([SM-02](../03-processes/SM-02-payment.md)), журналом уведомлений платёжного шлюза, попытками возврата и несопоставленными уведомлениями ([ADR-013](../05-architecture/adr/ADR-013-late-payment.md), [ADR-015](../05-architecture/adr/ADR-015-payment-gateway-integration.md)). Данных банковских карт на платформе нет (NFT-5.1).',
        roles='`app_payments` (схема `payments`)'),
    'delivery-service': dict(
        db='delivery_db', title='Выдача',
        about='Сервис владеет выдачей ([SM-06](../03-processes/SM-06-delivery.md)): очередь отправки писем с ключами, история попыток без значений ключей, контроль доставки в течение 30 минут и дедупликация статусов провайдера ([ADR-011](../05-architecture/adr/ADR-011-guaranteed-delivery.md)).',
        roles='`app_delivery` (схема `delivery`)'),
    'platform-service': dict(
        db='platform_db', title='Платформа: идентификация, поддержка, уведомления, аудит и администрирование',
        about='Четыре модуля в одной базе, у каждого своя схема и роль: `identity` (расширение учётной записи Keycloak: телефон, роль, признаки), `support` (обращения по [SM-07](../03-processes/SM-07-support-ticket.md), смена e-mail, модель чтения заказов), `notification` (очередь писем), `audit_admin` (параметры платформы и журнал аудита, секционированный по месяцам). Правила модульности: между схемами нет внешних ключей и запросов.',
        roles='`app_identity`, `app_support`, `app_notification`, `app_audit_admin` и `app_audit_writer` (только `INSERT` и `SELECT` в журнал аудита)'),
}

TYPE_SHORT = {'timestamp with time zone': 'timestamptz', 'integer': 'int', 'boolean': 'bool', 'character(3)': 'char3', 'text[]': 'text_array',
              'double precision': 'float8'}
SERVICE_TABLES = {'outbox', 'processed_event', 'idempotency_key'}
# имена служебных объектов, одинаковые во всех базах: сами по себе не показывают, что инвариант относится к этой базе
SHARED_NAMES = {'pk_processed_event', 'pk_idempotency_key', 'uq_outbox_event_id', 'ck_outbox_published_or_failed', 'ix_outbox_unpublished'}


def esc(text):
    return (text or '').replace('|', '\\|').replace('\n', ' ').strip()


def cell_code(text):
    return '`%s`' % esc(text).replace('`', "'")


def short_type(t):
    if t in TYPE_SHORT:
        return TYPE_SHORT[t]
    t = re.sub(r'\(.*\)', '', t)
    return re.sub(r'[^A-Za-z0-9_]', '_', t)


def load(db):
    c = connect(host=HOST, port=PORT, user='postgres', dbname=db)
    d = {}
    d['tables'] = c.query("""
        select n.nspname, cl.relname, obj_description(cl.oid, 'pg_class'), cl.relkind, cl.oid
        from pg_class cl join pg_namespace n on n.oid = cl.relnamespace
        where cl.relkind in ('r', 'p') and not cl.relispartition and n.nspname not in ('pg_catalog', 'information_schema', 'pg_toast')
        order by n.nspname, cl.relname""")
    d['columns'] = {}
    for r in c.query("""
        select cl.oid, a.attname, format_type(a.atttypid, a.atttypmod), a.attnotnull, pg_get_expr(ad.adbin, ad.adrelid), col_description(cl.oid, a.attnum),
               a.attgenerated, a.attidentity
        from pg_attribute a join pg_class cl on cl.oid = a.attrelid and cl.relkind in ('r', 'p') and not cl.relispartition
        left join pg_attrdef ad on ad.adrelid = a.attrelid and ad.adnum = a.attnum
        where a.attnum > 0 and not a.attisdropped order by cl.oid, a.attnum"""):
        d['columns'].setdefault(r[0], []).append(r[1:])
    d['constraints'] = {}
    for r in c.query("""
        select co.conrelid, co.conname, co.contype, pg_get_constraintdef(co.oid),
               (select string_agg(a.attname, ',' order by k.ord) from unnest(co.conkey) with ordinality k(attnum, ord)
                join pg_attribute a on a.attrelid = co.conrelid and a.attnum = k.attnum), co.confrelid
        from pg_constraint co join pg_class cl on cl.oid = co.conrelid and not cl.relispartition
        where co.contype in ('p', 'f', 'u', 'c') and co.conparentid = 0
        order by co.conrelid, case co.contype when 'p' then 1 when 'u' then 2 when 'f' then 3 else 4 end, co.conname"""):
        d['constraints'].setdefault(r[0], []).append(r[1:])
    d['indexes'] = {}
    for r in c.query("""
        select x.indrelid, i.relname, pg_get_indexdef(i.oid), obj_description(i.oid, 'pg_class'), x.indisunique, x.indisprimary
        from pg_index x join pg_class i on i.oid = x.indexrelid and not i.relispartition
        join pg_class t on t.oid = x.indrelid and not t.relispartition
        where not exists (select 1 from pg_constraint co where co.conindid = x.indexrelid and co.contype in ('p', 'u'))
        order by x.indrelid, i.relname"""):
        d['indexes'].setdefault(r[0], []).append(r[1:])
    d['triggers'] = {}
    for r in c.query("""
        select tg.tgrelid, tg.tgname, pg_get_triggerdef(tg.oid), p.proname, obj_description(p.oid, 'pg_proc'), obj_description(tg.oid, 'pg_trigger')
        from pg_trigger tg join pg_class cl on cl.oid = tg.tgrelid and not cl.relispartition
        join pg_proc p on p.oid = tg.tgfoid
        where not tg.tgisinternal order by tg.tgrelid, tg.tgname"""):
        d['triggers'].setdefault(r[0], []).append(r[1:])
    d['grants'] = c.query("""
        select grantee, table_schema || '.' || table_name, string_agg(privilege_type, ', ' order by privilege_type)
        from information_schema.role_table_grants
        where grantee like 'app\\_%' and table_name !~ '_20[0-9]{2}_' and table_name <> 'audit_log_default'
        group by 1, 2 order by 1, 2""")
    d['functions'] = c.query("""
        select n.nspname || '.' || p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')', obj_description(p.oid, 'pg_proc')
        from pg_proc p join pg_namespace n on n.oid = p.pronamespace
        where n.nspname not in ('pg_catalog', 'information_schema') and p.prokind = 'f'
          and not exists (select 1 from pg_depend dep where dep.objid = p.oid and dep.deptype = 'e')
        order by 1""")
    d['partitions'] = [r[0] for r in c.query("""
        select ch.relname from pg_inherits i join pg_class ch on ch.oid = i.inhrelid join pg_class p on p.oid = i.inhparent
        where p.relname = 'audit_log' order by ch.relname""")]
    d['oid_name'] = {r[4]: '%s.%s' % (r[0], r[1]) for r in d['tables']}
    c.close()
    return d


def inv_rows():
    text = open(os.path.join(REPO, 'docs/07-data/README.md'), encoding='utf-8').read()
    rows = []
    for line in text.split('\n'):
        cells = [x.strip() for x in line.strip().strip('|').split('|')]
        if len(cells) == 4 and re.match(r'^INV-\d+$', cells[0]):
            rows.append((cells[0], cells[1], cells[2], cells[3]))
    return rows


def describe_trigger(name, funcdef, proname, func_comment, trg_comment):
    if trg_comment:
        return trg_comment
    if proname == 'enforce_status_transition':
        args = re.findall(r"'([^']+)'", funcdef.split('EXECUTE FUNCTION', 1)[1])
        init = [a.split(':', 1)[1] for a in args if a.startswith('initial:')]
        pairs = [a.replace('>', ' → ') for a in args if '>' in a]
        if init:
            return 'Начальный статус при вставке: %s' % ', '.join(init)
        return 'Допустимые переходы статуса: %s' % '; '.join(pairs)
    return func_comment or proname


def event_of(funcdef):
    m = re.match(r'CREATE (?:CONSTRAINT )?TRIGGER \S+ (BEFORE|AFTER|INSTEAD OF) (.+?) ON ', funcdef)
    ev = m.group(2) if m else ''
    ev = ev.replace('INSERT', 'вставка').replace('UPDATE OF status', 'изменение статуса').replace('UPDATE', 'изменение').replace('DELETE', 'удаление').replace('TRUNCATE', 'очистка').replace(' OR ', ', ')
    return ('до: ' if m and m.group(1) == 'BEFORE' else 'после: ') + ev


def table_er(d, only_schema_tables):
    lines = ['```mermaid', 'erDiagram']
    ent = {}
    for (schema, name, comment, kind, oid) in only_schema_tables:
        ent[oid] = name.upper()
    for (schema, name, comment, kind, oid) in only_schema_tables:
        pk, uk, fk = set(), set(), set()
        for (cname, ctype, cdef, cols, confrel) in d['constraints'].get(oid, []):
            cs = (cols or '').split(',')
            if ctype == 'p':
                pk |= set(cs)
            elif ctype == 'u' and len(cs) == 1:
                uk |= set(cs)
            elif ctype == 'f':
                fk |= set(cs)
        lines.append('    %s {' % ent[oid])
        for (col, typ, notnull, default, comment_c, gen, ident) in d['columns'][oid]:
            mark = ' PK' if col in pk else (' FK' if col in fk else (' UK' if col in uk else ''))
            lines.append('        %s %s%s' % (short_type(typ), col, mark))
        lines.append('    }')
    for (schema, name, comment, kind, oid) in only_schema_tables:
        for (cname, ctype, cdef, cols, confrel) in d['constraints'].get(oid, []):
            if ctype == 'f' and confrel in ent:
                lines.append('    %s ||--o{ %s : "%s"' % (ent[confrel], ent[oid], cols))
    lines.append('```')
    return lines


def make(svc):
    meta = SERVICES[svc]
    d = load(meta['db'])
    domain = [t for t in d['tables'] if t[0] != 'public']
    out = ['# %s: физическая модель данных' % svc, '',
           'Файл создан скриптом `gen_schema_docs.py` из живой базы PostgreSQL 16, созданной из [ddl/%s.sql](ddl/%s.sql), вручную не правится. Общие решения, таблица инвариантов и находки шага лежат в [README](README.md).' % (svc, svc), '',
           '| Поле | Содержание |', '| --- | --- |',
           '| Сервис | %s ([компоненты](../05-architecture/c4-components-%s.md)) |' % (meta['title'], svc),
           '| База | `%s` |' % meta['db'],
           '| Таблиц предметной области | %d, служебных (`outbox`, `processed_event`, `idempotency_key`) 3 |' % len(domain),
           '| Роли приложения | %s |' % meta['roles'],
           '| Назначение | %s |' % meta['about'], '']
    out += ['## Диаграмма связей', '',
            'Связи показаны только внутри базы. Ссылки на сущности других сервисов и модулей хранятся идентификаторами без внешних ключей ([README, раздел 6](README.md)).', '']
    out += table_er(d, domain)
    out.append('')

    # инварианты, чьи объекты лежат в этой базе
    names = set()
    for oid in d['oid_name']:
        names |= {x[0] for x in d['constraints'].get(oid, [])} | {x[0] for x in d['indexes'].get(oid, [])} | {x[0] for x in d['triggers'].get(oid, [])}
    rows = []
    for inv, text, method, how in inv_rows():
        if how.startswith('R2.'):
            continue
        found = [n for n in re.findall(r'`((?:pk|uq|fk|ck|ix|trg)_[a-z0-9_]+)`', how) if n in names]
        specific = [n for n in found if n not in SHARED_NAMES]
        if specific or (found and inv == 'INV-43'):
            rows.append((inv, text, found))
    out += ['## Инварианты этой базы', '',
            'Инварианты, для которых в этой базе есть ограничение, индекс или триггер. Способ обеспечения и пояснения лежат в таблице [README, раздел 5](README.md).', '',
            '| ID | Инвариант | Объекты базы |', '| --- | --- | --- |']
    for inv, text, found in rows:
        out.append('| %s | %s | %s |' % (inv, esc(text), ', '.join('`%s`' % n for n in dict.fromkeys(found))))
    out.append('')

    out += ['## Таблицы', '']
    for (schema, name, comment, kind, oid) in domain:
        out += ['### %s.%s' % (schema, name), '', esc(comment) + '.' if comment and not esc(comment).endswith('.') else esc(comment), '']
        if name == 'audit_log' and d['partitions']:
            out += ['Таблица секционирована по месяцам `occurred_at`. Секции: %s. Секцию на новый месяц создаёт функция `audit_admin.create_audit_log_partition(date)`.' % ', '.join('`%s`' % p for p in d['partitions']), '']
        out += ['**Столбцы**', '', '| Столбец | Тип | Обязателен | По умолчанию | Описание |', '| --- | --- | --- | --- | --- |']
        for (col, typ, notnull, default, ccomment, gen, ident) in d['columns'][oid]:
            dflt = default or ''
            if ident:
                dflt = 'автонумерация'
            if gen:
                dflt = 'вычисляется: ' + (default or '')
            out.append('| `%s` | `%s` | %s | %s | %s |' % (col, esc(typ), 'да' if notnull == 't' else 'нет', cell_code(dflt) if dflt else '', esc(ccomment)))
        out.append('')
        cons = d['constraints'].get(oid, [])
        if cons:
            kinds = {'p': 'первичный ключ', 'u': 'уникальность', 'f': 'внешний ключ', 'c': 'проверка'}
            out += ['**Ограничения**', '', '| Имя | Вид | Определение |', '| --- | --- | --- |']
            for (cname, ctype, cdef, cols, confrel) in cons:
                out.append('| `%s` | %s | %s |' % (cname, kinds[ctype], cell_code(cdef)))
            out.append('')
        idx = d['indexes'].get(oid, [])
        if idx:
            out += ['**Индексы**', '', '| Имя | Определение | Для чего |', '| --- | --- | --- |']
            for (iname, idef, icomment, uniq, prim) in idx:
                out.append('| `%s` | %s | %s |' % (iname, cell_code(idef.replace('CREATE INDEX ', '').replace('CREATE UNIQUE INDEX ', 'UNIQUE ')), esc(icomment) or 'Поддерживает ограничение'))
            out.append('')
        trg = d['triggers'].get(oid, [])
        if trg:
            seen, rows_t = set(), []
            for (tname, tdef, proname, fcomment, tcomment) in trg:
                if tname in seen:
                    continue
                seen.add(tname)
                rows_t.append('| `%s` | %s | %s |' % (tname, event_of(tdef), esc(describe_trigger(tname, tdef, proname, fcomment, tcomment))))
            out += ['**Триггеры**', '', '| Имя | Когда | Назначение |', '| --- | --- | --- |'] + rows_t + ['']
    # функции и права
    funcs = [f for f in d['functions'] if not f[0].startswith('public.enforce')] + [f for f in d['functions'] if f[0].startswith('public.enforce')]
    out += ['## Функции', '', '| Функция | Назначение |', '| --- | --- |']
    for (sig, comment) in funcs:
        out.append('| `%s` | %s |' % (sig, esc(comment)))
    out += ['', '## Права ролей', '',
            'Каждая роль работает только со своей схемой. Роли создаются как `nologin`, пароли и вход задаёт окружение развёртывания, в SQL секретов нет.', '',
            '| Роль | Таблица | Права |', '| --- | --- | --- |']
    for (grantee, table, privs) in d['grants']:
        out.append('| `%s` | `%s` | %s |' % (grantee, table, privs))
    out.append('')
    text = '\n'.join(out)
    text = re.sub(r'[ \t]+\n', '\n', text)
    return text.rstrip('\n') + '\n'


def main():
    svcs = sys.argv[1:] or list(SERVICES)
    for svc in svcs:
        text = make(svc)
        path = os.path.join(REPO, 'docs/07-data/%s-schema.md' % svc)
        with open(path, 'w', encoding='utf-8') as fh:
            fh.write(text)
        print('создан %s (%d строк)' % (os.path.basename(path), text.count('\n')))


if __name__ == '__main__':
    main()
