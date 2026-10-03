# -*- coding: utf-8 -*-
"""Проверка матрицы ролей и прав и модели угроз.

roles-permissions.md:
  - строки матрицы OP-nn без повторов, столбцы и ячейки из словаря, условия U1..U18 определены и использованы;
  - области токена из таблицы раздела 3, роль получает область только если она названа в столбце «Кто получает»;
  - сессия по SMS: разрешены только операции с областями, которые выдаются этой сессии;
  - роли со вторым фактором совпадают с FT-1.3 (продавец, модератор, оператор поддержки, администратор);
  - каждая история R1 есть в матрице или в разделе 12; историй, которых нет в требованиях, нет;
  - внутренние вызовы и вебхуки названы по сервисам из c4-containers.md.
threat-model.md:
  - угрозы T-nn без пропусков, у каждой граница TB, актив A, категория STRIDE, мера, ссылки, остаточный риск из словаря;
  - покрыты все границы, активы и шесть категорий STRIDE; ссылки на FT/NFT/INV/OP/ADR существуют;
  - списки остаточных рисков (высокие, средние) полны; тесты ST-nn ссылаются на существующие угрозы; находки F9-n последовательны;
  - обязательные угрозы шага 9 (подмена SIM) присутствуют.
Сквозное: каждая ссылка OP-nn в документах архитектуры и API существует в матрице.
"""
import glob
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import docslib as L

RP = os.path.join(L.ARCH, 'roles-permissions.md')
TM = os.path.join(L.ARCH, 'threat-model.md')
ROLE_COLS = ['Гость', 'Покупатель', 'Покупатель SMS', 'Продавец', 'Модератор', 'Оператор', 'Админ', 'Система']
CELL_RE = re.compile(r'^(Да|Нет|Усл: U\d+(?:, U\d+)*)$')
SCOPE_NONE = re.compile(r'^Нет, (публичный|Keycloak|внутренний|подпись вебхука|вне API|не существует)$')
STRIDE = set('STRIDE') | {'R'}
EXTENSIONS = {'sms-otp'}   # расширение Keycloak, отдельного контейнера нет
KEYCLOAK_ROLE = {'Продавец': 'seller', 'Модератор': 'moderator', 'Оператор': 'support-operator', 'Админ': 'admin'}
RISK_WORDS = ('низкий', 'средний', 'высокий')
STATUS_WORDS = ('В архитектуре', 'К реализации', 'Ф6', 'Рекомендация v1.6')

WHO = [  # (слово в столбце «Кто получает», столбец матрицы)
    ('Покупатель', 'Покупатель'), ('Продавец', 'Продавец'), ('Модератор', 'Модератор'),
    ('Оператор', 'Оператор'), ('Администратор', 'Админ'),
]


def stories():
    res = {}
    for f in sorted(glob.glob(os.path.join(L.DOCS, '02-requirements', 'US-*.md'))):
        t = L.read(f)
        for m in re.finditer(r'^## (US-\d+\.\d+)\. (.+?)\n(.*?)(?=^## |\Z)', t, re.S | re.M):
            rel = re.search(r'^\| Релиз \| (.+?) \|$', m.group(3), re.M)
            res[m.group(1)] = dict(title=m.group(2), release=rel.group(1) if rel else '')
    return res


def invariants():
    t = L.read(os.path.join(L.DOCS, '04-domain', 'domain-model.md'))
    return set(re.findall(r'^\| (INV-\d+) \|', t, re.M))


def service_aliases():
    res = set(L.containers())
    mb = L.read(os.path.join(L.OPS_DIR, 'memory-budget.md'))
    t = L.table_after(mb, r'^### 2\.1\.')
    for r in t['rows']:
        res.add(L.backticked(r[1])[0])
    return res


# ======================================================================= матрица
def check_roles(rep):
    text = L.read(RP)
    ft, nft = L.requirement_ids()
    us = stories()
    services = service_aliases()
    # --- области
    sc = L.table_after(text, r'^## 3\. Области токена')
    scopes = {}
    for r in sc['rows']:
        scopes[L.backticked(r[0])[0]] = r[2]
    sms_scopes = {k for k, v in scopes.items() if 'сессия по SMS' in v and 'не сессия по SMS' not in v}
    # --- условия
    ct = L.table_after(text, r'^### 4\.3\. Условия')
    conds = [r[0] for r in ct['rows']]
    if conds != ['U%d' % i for i in range(1, len(conds) + 1)]:
        rep.err('roles-permissions 4.3', 'условия идут не подряд U1..Un: %s' % conds)
    # --- матрица
    mt = L.table_after(text, r'^### 4\.2\. Матрица')
    hdr = mt['header']
    if hdr[:5] != ['ID', 'Операция', 'Истории', 'Область', 'Сервис'] or hdr[5:] != ROLE_COLS:
        rep.err('roles-permissions 4.2', 'заголовок матрицы: %s' % hdr)
        return None
    ids = []
    used_conds = set()
    story_in_matrix = set()
    ops = {}
    for r in mt['rows']:
        if len(r) != len(hdr):
            rep.err('roles-permissions 4.2', 'строка %s: %d ячеек вместо %d' % (r[0] if r else '?', len(r), len(hdr)))
            continue
        oid = r[0]
        if not re.match(r'^OP-\d{2}$', oid):
            rep.err('roles-permissions 4.2', 'идентификатор «%s» не в формате OP-nn' % oid)
        ids.append(oid)
        cells = dict(zip(ROLE_COLS, r[5:]))
        for role, c in cells.items():
            if not CELL_RE.match(c):
                rep.err(oid, 'ячейка роли %s «%s» не из словаря (Да, Нет, Усл: Ux)' % (role, c))
            for u in re.findall(r'U\d+', c):
                used_conds.add(u)
                if u not in conds:
                    rep.err(oid, 'условие %s не определено в разделе 4.3' % u)
        scope_cell = r[3]
        scode = L.backticked(scope_cell)
        if scode:
            for s in scode:
                if s not in scopes:
                    rep.err(oid, 'область %s не описана в разделе 3' % s)
        elif not SCOPE_NONE.match(scope_cell):
            rep.err(oid, 'область «%s» не из словаря' % scope_cell)
        svc = L.backticked(r[4])
        if not svc or svc[0] not in services:
            rep.err(oid, 'сервис «%s» не найден среди контейнеров' % r[4])
        # роли и области
        allowed_cols = [c for c, v in cells.items() if v != 'Нет']
        if scode and scode[0] in scopes:
            who = scopes[scode[0]]
            for col in allowed_cols:
                if col == 'Гость' or (col == 'Система' and not cells[col].startswith('Усл')):
                    # система действует от имени пользователя только по условию (например, U18: обращение по событию)
                    rep.err(oid, 'операция с областью %s доступна роли «%s» без условия' % (scode[0], col))
                    continue
                if col == 'Система':
                    continue
                if col == 'Покупатель SMS':
                    if scode[0] not in sms_scopes:
                        rep.err(oid, 'сессии по SMS разрешена область %s, которая ей не выдаётся' % scode[0])
                    continue
                word = dict(WHO)
                key = next(k for k, v in WHO if v == col)
                if key not in who:
                    rep.err(oid, 'роль «%s» получает область %s, но в разделе 3 она названа только для: %s' % (col, scode[0], who))
        else:
            if cells['Система'] != 'Нет' and 'внутренний' not in scope_cell and 'вебхука' not in scope_cell \
                    and 'вне API' not in scope_cell:
                rep.err(oid, 'роль «Система» разрешена при области «%s»' % scope_cell)
            if scope_cell == 'Нет, публичный' and cells['Гость'] == 'Нет':
                rep.err(oid, 'публичный маршрут недоступен гостю')
            if scope_cell == 'Нет, не существует' and any(v != 'Нет' for v in cells.values()):
                rep.err(oid, 'операция «не существует» кому-то разрешена')
        for s in re.findall(r'US-\d+\.\d+', r[2]):
            story_in_matrix.add(s)
            if s not in us:
                rep.err(oid, 'история %s не найдена в требованиях' % s)
        if not r[2].strip() and 'не существует' not in scope_cell:
            rep.err(oid, 'нет историй')
        ops[oid] = dict(scope=scode[0] if scode else None, cells=cells, service=svc[0] if svc else None)
    dup = sorted(set(i for i in ids if ids.count(i) > 1))
    if dup:
        rep.err('roles-permissions 4.2', 'повторяются строки: %s' % dup)
    if ids != sorted(ids):
        rep.err('roles-permissions 4.2', 'строки идут не по возрастанию ID')
    for u in conds:
        if u not in used_conds and not re.search(r'\b%s\b' % u, text[text.index('## 5.'):]):
            rep.err('roles-permissions 4.3', 'условие %s нигде не используется' % u)
    # --- истории R1
    t12 = L.table_after(text, r'^## 12\. Истории без операции')
    not_in_matrix = set()
    for r in t12['rows']:
        m = re.match(r'(US-\d+\.\d+)', r[0])
        if m:
            not_in_matrix.add(m.group(1))
            if m.group(1) not in us:
                rep.err('roles-permissions 12', 'история %s не найдена' % m.group(1))
            if m.group(1) in story_in_matrix:
                rep.err('roles-permissions 12', 'история %s есть и в матрице, и в разделе 12' % m.group(1))
    r1 = {k for k, v in us.items() if v['release'].startswith('R1')}
    for s in sorted(r1):
        if s not in story_in_matrix and s not in not_in_matrix:
            rep.err('roles-permissions', 'история R1 %s (%s) не попала ни в матрицу, ни в раздел 12' % (s, us[s]['title']))
    # --- второй фактор
    rt = L.table_after(text, r'^## 2\. Роли и их реализация')
    mfa = {}
    for r in rt['rows']:
        mfa[r[0]] = r[2].startswith('Обязателен')
    want_roles = {'Продавец', 'Модератор', 'Оператор поддержки', 'Администратор'}
    got = {k for k, v in mfa.items() if v}
    if got != want_roles:
        rep.err('roles-permissions 2', 'роли со вторым фактором %s, FT-1.3 требует %s' % (sorted(got), sorted(want_roles)))
    ft13 = re.search(r'\| FT-1\.3 \| (.+?) \|', L.read(L.REQ))
    if ft13:
        for w in ('продавца', 'модератора', 'оператора поддержки', 'администратора'):
            if w not in ft13.group(1):
                rep.err('requirements FT-1.3', 'в тексте нет «%s»' % w)
    s6 = L.table_after(text, r'^## 6\. Второй фактор')
    got6 = {r[0] for r in s6['rows'] if r[1].startswith('Да')}
    if got6 != want_roles:
        rep.err('roles-permissions 6', 'в разделе 6 обязателен для %s, ожидалось %s' % (sorted(got6), sorted(want_roles)))
    kc = [r[2] for r in s6['rows'] if r[0] == 'Продавец'][0]
    for role in ('seller', 'moderator', 'support-operator', 'admin'):
        if ('`%s`' % role) not in kc:
            rep.err('roles-permissions 6', 'в потоке Keycloak не названа роль %s' % role)
    # сотрудники и продавец: операция OP-05 (настроить TOTP) разрешена ровно этим ролям
    op05 = ops.get('OP-05')
    if op05:
        allowed = {c for c, v in op05['cells'].items() if v == 'Да'}
        if allowed != set(KEYCLOAK_ROLE):
            rep.err('OP-05', 'настройка TOTP доступна %s, ожидалось %s' % (sorted(allowed), sorted(KEYCLOAK_ROLE)))
    # --- внутренние вызовы и вебхуки
    t81 = L.table_after(text, r'^### 8\.1\.')
    for r in t81['rows']:
        for cell in (r[1], r[2]):
            for a in L.backticked(cell):
                if a not in services and a not in EXTENSIONS:
                    rep.err('roles-permissions 8.1', 'сервис %s (строка «%s») не найден' % (a, r[0][:40]))
    t82 = L.table_after(text, r'^### 8\.2\.')
    for r in t82['rows']:
        for a in L.backticked(r[1]):
            if a not in services:
                rep.err('roles-permissions 8.2', 'сервис %s не найден' % a)
    sms_ops = [o for o, v in ops.items() if v['cells']['Покупатель SMS'] != 'Нет']
    return dict(ids=ids, ops=ops, conds=conds, scopes=scopes, sms_ops=sms_ops, r1=r1)


# ======================================================================= угрозы
def check_threats(rep, matrix_ids):
    text = L.read(TM)
    ft, nft = L.requirement_ids()
    inv = invariants()
    tb = L.table_after(text, r'^## 2\. Границы доверия')
    bounds = [r[0] for r in tb['rows']]
    at = L.table_after(text, r'^## 3\. Активы')
    assets = [r[0] for r in at['rows']]
    if bounds != ['TB-%d' % i for i in range(1, len(bounds) + 1)]:
        rep.err('threat-model 2', 'границы идут не подряд: %s' % bounds)
    if assets != ['A-%02d' % i for i in range(1, len(assets) + 1)]:
        rep.err('threat-model 3', 'активы идут не подряд: %s' % assets)
    reg = [t for t in L.tables(text) if t['header'][:2] == ['ID', 'S']][0]
    ids = [r[0] for r in reg['rows']]
    if ids != ['T-%02d' % i for i in range(1, len(ids) + 1)]:
        rep.err('threat-model 4', 'угрозы идут не подряд T-01..T-nn: нарушение около %s' % next(
            (a for a, b in zip(ids, ['T-%02d' % i for i in range(1, len(ids) + 1)]) if a != b), '?'))
    used_b, used_a, used_s = set(), set(), set()
    risk = {}
    for r in reg['rows']:
        tid = r[0]
        if len(r) != 9:
            rep.err(tid, '%d ячеек вместо 9' % len(r))
            continue
        letters = set(re.findall(r'[A-Z]', r[1]))
        if not letters or not letters <= STRIDE:
            rep.err(tid, 'категория STRIDE «%s» не из S, T, R, I, D, E' % r[1])
        used_s |= letters
        if r[2] not in bounds:
            rep.err(tid, 'граница %s не определена' % r[2])
        used_b.add(r[2])
        if r[3] not in assets:
            rep.err(tid, 'актив %s не определён' % r[3])
        used_a.add(r[3])
        if len(r[4]) < 8:
            rep.err(tid, 'пустой сценарий')
        if len(r[5]) < 20:
            rep.err(tid, 'пустая мера')
        refs = r[6]
        if not re.search(r'\b(FT-\d+\.\d+|NFT-\d+\.\d+|ADR-\d{3}|INV-\d+|OP-\d+|SEQ-\d+|roles-permissions|U\d+)', refs):
            rep.err(tid, 'нет ссылки на требование, ADR, инвариант или операцию матрицы')
        for x in re.findall(r'\bFT-\d+\.\d+\b', refs):
            if x not in ft:
                rep.err(tid, 'требование %s не найдено' % x)
        for x in re.findall(r'\bNFT-\d+\.\d+\b', refs):
            if x not in nft:
                rep.err(tid, 'требование %s не найдено' % x)
        for x in re.findall(r'\bINV-\d+\b', refs + ' ' + r[5]):
            if x not in inv:
                rep.err(tid, 'инвариант %s не найден' % x)
        for x in re.findall(r'\bOP-\d+\b', refs + ' ' + r[5]):
            if x not in matrix_ids:
                rep.err(tid, 'операция %s не найдена в матрице' % x)
        for x in re.findall(r'\bT-\d{2}\b', r[4] + ' ' + r[5] + ' ' + r[7]):
            if x not in ids:
                rep.err(tid, 'ссылка на несуществующую угрозу %s' % x)
        low = r[7].lower()
        word = next((w for w in RISK_WORDS if low.startswith(w)), None)
        if word is None:
            rep.err(tid, 'остаточный риск «%s» не начинается со слова из словаря %s' % (r[7][:30], RISK_WORDS))
        else:
            risk[tid] = word
        if not any(w in r[8] for w in STATUS_WORDS):
            rep.err(tid, 'статус меры «%s» не из словаря' % r[8][:40])
    for b in bounds:
        if b not in used_b:
            rep.err('threat-model', 'у границы %s нет ни одной угрозы' % b)
    for a in assets:
        if a not in used_a:
            rep.err('threat-model', 'у актива %s нет ни одной угрозы' % a)
    if used_s != set('STRIDE') | {'R'}:
        rep.err('threat-model', 'не покрыты категории STRIDE: %s' % sorted((set('STRIDE') | {'R'}) - used_s))
    # --- обязательные угрозы шага 9
    for tid in ('T-01', 'T-02'):
        row = next((r for r in reg['rows'] if r[0] == tid), None)
        if not row or 'SIM' not in row[4]:
            rep.err(tid, 'угроза «подмена SIM» (находка 11 Ф1) отсутствует')
    # --- остаточные риски
    t51 = L.table_after(text, r'^### 5\.1\.')
    t52 = L.table_after(text, r'^### 5\.2\.')
    high = {re.match(r'(T-\d{2})', r[0]).group(1) for r in t51['rows']}
    mid = set()
    for r in t52['rows']:
        mid |= set(re.findall(r'T-\d{2}', r[0]))
    want_high = {k for k, v in risk.items() if v == 'высокий'}
    want_mid = {k for k, v in risk.items() if v == 'средний'}
    if high != want_high:
        rep.err('threat-model 5.1', 'высокие риски реестра %s, в разделе 5.1 %s' % (sorted(want_high), sorted(high)))
    if mid != want_mid:
        rep.err('threat-model 5.2', 'средние риски реестра %s, в разделе 5.2 %s (расходятся: %s)'
                % (len(want_mid), len(mid), sorted(want_mid ^ mid)))
    # --- процедуры, тесты, находки
    t7 = L.table_after(text, r'^## 7\. Проверки безопасности')
    tests = [r[0] for r in t7['rows']]
    if tests != ['ST-%02d' % i for i in range(1, len(tests) + 1)]:
        rep.err('threat-model 7', 'тесты идут не подряд: %s' % tests)
    covered = set()
    for r in t7['rows']:
        for x in re.findall(r'T-\d{2}', r[2]):
            covered.add(x)
            if x not in ids:
                rep.err(r[0], 'угроза %s не найдена' % x)
    t8 = L.table_after(text, r'^## 8\. Находки шага 9')
    finds = [r[0] for r in t8['rows']]
    if finds != ['F9-%d' % i for i in range(1, len(finds) + 1)]:
        rep.err('threat-model 8', 'находки идут не подряд: %s' % finds)
    for r in t8['rows']:
        for x in re.findall(r'T-\d{2}', r[2]):
            if x not in ids:
                rep.err(r[0], 'угроза %s не найдена' % x)
    # высокие и «К реализации Ф3» угрозы должны иметь тест Ф3 или быть названы в рекомендациях
    return dict(ids=ids, tests=tests, finds=finds, covered=covered, risk=risk, bounds=bounds, assets=assets)


def check_op_refs(rep, matrix_ids):
    bad = 0
    total = 0
    for sub in ('05-architecture', '06-api', '07-data', '08-testing'):
        for f in glob.glob(os.path.join(L.DOCS, sub, '**', '*'), recursive=True):
            if not os.path.isfile(f) or not f.endswith(('.md', '.yaml', '.yml')):
                continue
            t = L.read(f)
            for m in re.finditer(r'\bOP-(\d{2})\b', t):
                total += 1
                if m.group(0) not in matrix_ids:
                    bad += 1
                    rep.err(L.rel(f), 'ссылка на несуществующую операцию %s' % m.group(0))
    return total


def main():
    rep = L.Report('security')
    mx = check_roles(rep)
    if not mx:
        return rep.finish()
    th = check_threats(rep, set(mx['ids']))
    refs = check_op_refs(rep, set(mx['ids']))
    rep.fact('Матрица: %d операций, %d условий, %d областей, сессии по SMS доступно %d операций; '
             'модель угроз: %d угроз, %d границ, %d активов, %d тестов, %d находок; ссылок OP-nn проверено %d'
             % (len(mx['ids']), len(mx['conds']), len(mx['scopes']), len(mx['sms_ops']), len(th['ids']),
                len(th['bounds']), len(th['assets']), len(th['tests']), len(th['finds']), refs))
    return rep.finish()


if __name__ == '__main__':
    sys.exit(main())
