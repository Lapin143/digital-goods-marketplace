# -*- coding: utf-8 -*-
"""Проверка декомпозиции на сервисы (decomposition.md) против документов Ф1 и c4-containers.md.

  1. Карта «контекст → сервис»: 12 строк, контексты те же, что в доменной модели и в bounded-contexts.md, каждый ровно один раз.
  2. Владельцы сущностей (10.1): те же 16 сущностей, что в domain-model.md, контекст-владелец совпадает, сервис и модуль взяты из карты.
  3. Описания сервисов (раздел 4): одинаковый набор полей, модули и база совпадают с картой, сущности принадлежат сервису.
  4. Раздел 5 вместе с сервисами даёт ровно контейнеры c4-containers.md (кроме R2-сервиса finance-service).
  5. Базы данных: у каждого сервиса R1 своя база, общих нет, схемы и владельцы совпадают с таблицей c4-containers.md.
  6. Таблица «Владелец данных» в c4-containers.md не противоречит владельцам сущностей.
  7. События, которые сервис «публикует» в decomposition.md, есть у него же в таблице событий c4-containers.md.
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import docslib as L

DEC = os.path.join(L.ARCH, 'decomposition.md')
CONT = os.path.join(L.ARCH, 'c4-containers.md')
DM = os.path.join(L.DOCS, '04-domain', 'domain-model.md')
BC = os.path.join(L.DOCS, '04-domain', 'bounded-contexts.md')
SERVICE_FIELDS = ['Назначение', 'Модули', 'Владеет сущностями', 'База данных', 'Принимает вызовы', 'Вызывает',
                  'Публикует', 'Принимает события', 'Внешние системы и хранилища', 'Нагрузка', 'Безопасность', 'Релиз']
R1_SERVICES = ['catalog-service', 'inventory-service', 'order-service', 'payment-service', 'delivery-service', 'platform-service']


SERVICE_RECORDS = {'Уведомление', 'Одноразовый код'}   # служебные записи модуля notification (domain-model.md, раздел 5)


def strip_paren(s):
    prev = None
    while prev != s:
        prev = s
        s = re.sub(r'\([^()]*\)', '', s)
    return re.sub(r'\s+', ' ', s).strip()


def norm(s):
    return re.sub(r'\s+', ' ', s.replace('«', '').replace('»', '')).strip().lower()


def main():
    rep = L.Report('decomposition')
    dec = L.read(DEC)
    cont = L.read(CONT)
    dm = L.read(DM)
    bc = L.read(BC)

    # ---------------------------------------------------------------- 1. карта
    t = L.table_after(dec, r'^### 3\.1\. Карта')
    ctx_map = {}
    for r in t['rows']:
        ctx_map[r[1]] = dict(n=int(r[0]), services=L.backticked(r[2]), module=L.backticked(r[3])[0],
                             db=L.backticked(r[4]), dbcell=r[4], release=r[5])
    nums = [v['n'] for v in ctx_map.values()]
    if nums != list(range(1, 13)):
        rep.err('decomposition 3.1', 'нумерация карты не 1..12: %s' % nums)
    if len(ctx_map) != 12:
        rep.err('decomposition 3.1', 'в карте %d контекстов вместо 12' % len(ctx_map))
    et = L.table_after(dm, r'^## 2\. Сводная таблица сущностей')
    dm_entities = {}
    for r in et['rows']:
        dm_entities[r[1]] = dict(owner=r[2], release=r[5])
    dm_contexts = {v['owner'] for v in dm_entities.values()} | {'Уведомления'}
    if set(ctx_map) != dm_contexts:
        rep.err('decomposition 3.1', 'контексты карты и доменной модели расходятся: %s' % sorted(set(ctx_map) ^ dm_contexts))
    bc_heads = set(re.findall(r'^### 4\.\d+\. (.+)$', bc, re.M))
    if set(ctx_map) != bc_heads:
        rep.err('decomposition 3.1', 'контексты карты и bounded-contexts.md расходятся: %s' % sorted(set(ctx_map) ^ bc_heads))
    module_of = {}
    service_modules = {}
    service_dbs = {}
    for ctx, v in ctx_map.items():
        sch = re.search(r'схема `(\w+)`', v['dbcell'])
        if sch and sch.group(1) != v['module']:
            rep.err('decomposition 3.1', '«%s»: схема %s не совпадает с модулем %s' % (ctx, sch.group(1), v['module']))
        main_service = v['services'][-1] if ctx == 'Идентификация' else v['services'][0]
        module_of[ctx] = (v['services'], v['module'])
        service_modules.setdefault(main_service, []).append(v['module'])
        service_dbs.setdefault(main_service, set()).add(v['db'][0])

    # ---------------------------------------------------------------- 2. владельцы сущностей
    ot = L.table_after(dec, r'^### 10\.1\. Владельцы сущностей')
    own = {}
    for r in ot['rows']:
        own[r[0]] = dict(owner=r[1], cell=r[2], tokens=L.backticked(r[2]))
    for name, e in dm_entities.items():
        key = next((k for k in own if strip_paren(k) == strip_paren(name)), None)
        if key is None:
            rep.err('decomposition 10.1', 'сущности «%s» из доменной модели нет в таблице владельцев' % name)
            continue
        if own[key]['owner'] != e['owner']:
            rep.err('decomposition 10.1', '«%s»: владелец %s, в доменной модели %s' % (name, own[key]['owner'], e['owner']))
        services, module = module_of.get(e['owner'], ([], None))
        toks = own[key]['tokens']
        for s in services:
            if s not in toks:
                rep.err('decomposition 10.1', '«%s»: в ячейке нет сервиса %s из карты' % (name, s))
        if module and module not in toks:
            rep.err('decomposition 10.1', '«%s»: в ячейке нет модуля %s из карты' % (name, module))
    if len(own) != len(dm_entities):
        rep.err('decomposition 10.1', 'сущностей %d, в доменной модели %d' % (len(own), len(dm_entities)))
    if '16 сущност' not in dec:
        rep.err('decomposition 10', 'в таблице проверок не названо «16 сущностей»')
    if len(dm_entities) != 16:
        rep.err('domain-model 2', 'сущностей %d вместо 16' % len(dm_entities))

    # ---------------------------------------------------------------- 3. описание сервисов
    svc_sections = {}
    for m in re.finditer(r'^### 4\.(\d+)\. `([\w-]+)`\n(.*?)(?=^### |^## )', dec, re.S | re.M):
        svc_sections[m.group(2)] = L.tables(m.group(3))[0]
    if sorted(svc_sections) != sorted(R1_SERVICES):
        rep.err('decomposition 4', 'сервисы раздела 4 %s, ожидались %s' % (sorted(svc_sections), sorted(R1_SERVICES)))
    owned_by = {}
    for name, v in own.items():
        for tok in v['tokens']:
            if tok.endswith('-service'):
                owned_by.setdefault(tok, set()).add(name)
    for alias, tb in svc_sections.items():
        fields = {r[0]: r[1] for r in tb['rows']}
        if list(fields) != SERVICE_FIELDS:
            rep.err('decomposition 4 %s' % alias, 'поля %s, ожидались %s' % (list(fields), SERVICE_FIELDS))
            continue
        mods = L.backticked(fields['Модули'])
        if sorted(mods) != sorted(service_modules.get(alias, [])):
            rep.err('decomposition 4 %s' % alias, 'модули %s, в карте %s' % (mods, service_modules.get(alias)))
        dbs = L.backticked(fields['База данных'])
        if dbs and dbs[0] not in service_dbs.get(alias, set()):
            rep.err('decomposition 4 %s' % alias, 'база %s, в карте %s' % (dbs[0], service_dbs.get(alias)))
        # владеет сущностями: названия из этого поля принадлежат сервису в таблице 10.1
        entities = [e.strip() for e in re.split(r'[,.]', re.sub(r'R2:', '', strip_paren(fields['Владеет сущностями'])))
                    if e.strip()]
        expanded = []
        for e in entities:
            expanded += [x.strip() for x in e.split(' и ')] if e.split(' и ')[0] in SERVICE_RECORDS else [e]
        for e in expanded:
            if e in SERVICE_RECORDS:
                continue
            k = next((k for k in own if strip_paren(k) == e), None)
            if k is None:
                rep.err('decomposition 4 %s' % alias, 'сущность «%s» не найдена в 10.1' % e)
            elif alias not in own[k]['tokens']:
                rep.err('decomposition 4 %s' % alias, 'сущностью «%s» по 10.1 владеет другой сервис' % e)
    # ---------------------------------------------------------------- 4. остальные контейнеры
    ot5 = L.table_after(dec, r'^## 5\. Остальные контейнеры')
    other = [L.backticked(r[1])[0] for r in ot5['rows']]
    aliases = L.containers()
    if sorted(other + R1_SERVICES) != sorted(aliases):
        rep.err('decomposition 5', 'контейнеры раздела 5 и сервисы %s не равны контейнерам c4-containers.md: %s'
                % (len(other) + len(R1_SERVICES), sorted(set(other + R1_SERVICES) ^ set(aliases))))
    # ---------------------------------------------------------------- 5. базы данных
    dbt = L.table_after(cont, r'^## 6\. Базы данных')
    dbs = {}
    for r in dbt['rows']:
        dbs[L.backticked(r[0])[0]] = dict(owner=L.backticked(r[1])[0], schemas=L.backticked(r[2]))
    seen_db = {}
    for s, dset in service_dbs.items():
        for d in dset:
            if d in seen_db:
                rep.err('decomposition', 'база %s у двух сервисов: %s и %s' % (d, seen_db[d], s))
            seen_db[d] = s
            if d not in dbs and s in R1_SERVICES:
                rep.err('c4-containers 6', 'база %s из карты не описана' % d)
            elif d in dbs and dbs[d]['owner'] != s:
                rep.err('c4-containers 6', 'база %s: владелец %s, в карте %s' % (d, dbs[d]['owner'], s))
    # схемы платформенной и каталожной баз
    for d, info in dbs.items():
        mods = [v['module'] for v in ctx_map.values() if v['db'] and v['db'][0] == d]
        if len(mods) > 1 and sorted(info['schemas']) != sorted(mods):
            rep.err('c4-containers 6', 'схемы %s: %s, модули карты %s' % (d, sorted(info['schemas']), sorted(mods)))
    r1_dbs = [d for d in dbs if d != 'keycloak_db']
    if len(r1_dbs) != 6 or '6 баз R1' not in dec:
        rep.err('decomposition 10', 'баз R1 %d, ожидалось 6 и запись «6 баз R1» в таблице проверок' % len(r1_dbs))
    cr = L.table_after(cont, r'^## 1\. Контейнеры релиза R1')
    pg = [r for r in cr['rows'] if L.backticked(r[1])[0] == 'postgres'][0]
    for d in dbs:
        if d not in L.backticked(pg[4]):
            rep.err('c4-containers 1', 'в ответственности `postgres` не названа база %s' % d)
    # ---------------------------------------------------------------- 6. «Владелец данных»
    for r in cr['rows']:
        alias = L.backticked(r[1])[0]
        if alias not in R1_SERVICES:
            continue
        owner_cell = r[5]
        want = {strip_paren(e) for e in dm_entities if dm_entities[e]['release'].startswith('R1')
                and any(alias in own[k]['tokens'] for k in own if strip_paren(k) == strip_paren(e))}
        # «Параметры платформы», «Журнал аудита» и т.п. допускаются как в таблице, так и без неё
        for e in want:
            if e not in owner_cell:
                rep.err('c4-containers 1 %s' % alias, 'в «Владелец данных» нет сущности «%s» из decomposition.md 10.1' % e)
    # ---------------------------------------------------------------- 7. события
    ev = L.table_after(cont, r'^## 8\. Какие события кто публикует и читает')
    pub = {}
    for r in ev['rows']:
        for s in L.backticked(r[1]):
            pub.setdefault(s, set()).add(norm(r[0]))
    compared = 0
    for alias, tb in svc_sections.items():
        fields = {r[0]: r[1] for r in tb['rows']}
        text = norm(fields.get('Публикует', ''))
        for name in sorted(pub.get(alias, [])):
            compared += 1
            first = name.split(',')[0]
            if first not in text and first.replace('ё', 'е') not in text.replace('ё', 'е'):
                rep.err('decomposition 4 %s' % alias, 'событие «%s» из c4-containers.md не названо в поле «Публикует»' % name)

    rep.fact('Декомпозиция: %d контекстов, %d сущностей, %d сервисов R1, %d баз, %d контейнеров, событий сверено %d'
             % (len(ctx_map), len(own), len(svc_sections), len(dbs), len(aliases), compared))
    return rep.finish()


if __name__ == '__main__':
    sys.exit(main())
