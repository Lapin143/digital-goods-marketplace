# -*- coding: utf-8 -*-
"""Проверка бюджетов времени и памяти и диаграмм развёртывания.

Время (docs/05-architecture/time-budgets.md):
  - суммы разбивки NFT-2.0 (раздел 3.1 и 3.2) и числа в тексте под таблицами;
  - арифметика проверок раздела 4 пересчитывается и сверяется с записанным результатом;
  - ускоренные сроки раздела 5 сохраняют соотношения (сессия короче резерва, контроль длиннее повторов);
  - значения повторов, названные в ADR, SEQ и компонентах, совпадают с таблицей.
Память (docs/09-operations/memory-budget.md, c4-deployment.md):
  - все контейнеры из c4-containers.md есть в таблице лимитов, лимит Java-контейнера равен Xmx плюс 160 МБ там, где так заявлено;
  - суммы групп и наборов пересчитываются, пороги статусов и запасы совпадают с таблицами;
  - диаграмма профилей: все контейнеры R1, подписанные суммы групп равны таблице памяти, не больше 15 элементов на диаграмме;
  - состав профилей и наборов в c4-deployment.md равен memory-budget.md.
"""
import glob
import math
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import docslib as L

TB = os.path.join(L.ARCH, 'time-budgets.md')
MB = os.path.join(L.OPS_DIR, 'memory-budget.md')
DEP = os.path.join(L.ARCH, 'c4-deployment.md')

LAPTOP = 3072
LAPTOP_FIT = 2560
CI_RUNNER = 16384
SERVER = 8192
SYSTEM_RESERVE = 800
MAX_DIAGRAM_ELEMENTS = 15
JAVA_OVERHEAD = 160
NO_PROCESS = {'secret-store'}   # хранилище секретов Docker/Kubernetes: файлы, а не процесс


def num(s):
    m = re.search(r'-?\d+(?:[.,]\d+)?', s.replace(' ', ' '))
    if not m:
        raise ValueError('нет числа в «%s»' % s)
    return float(m.group(0).replace(',', '.'))


def fmt(x):
    """7,5 / 13,5 / 34,0 как в документе."""
    return ('%.1f' % x).replace('.', ',')


def mmss(sec):
    sec = int(round(sec))
    return '%d мин %d с' % (sec // 60, sec % 60)


def clean(c):
    return c.replace('**', '').strip()


# ================================================================== время
def check_time(rep):
    text = L.read(TB)
    # --- 3.1
    s31 = L.section(text, r'^3\.1\.')
    t = L.tables(s31)[0]
    rows = [r for r in t['rows'] if clean(r[0]) != '']
    body = [r for r in t['rows'] if clean(r[0]).isdigit()]
    total = [r for r in t['rows'] if 'Итого' in ''.join(r)]
    if len(body) != 9 or len(total) != 1:
        rep.err('time-budgets 3.1', 'ожидалось 9 звеньев и строка «Итого», найдено %d и %d' % (len(body), len(total)))
        return
    p95 = [num(r[3]) for r in body]
    lim = [num(r[4]) for r in body]
    tot = total[0]
    if abs(sum(p95) - num(tot[3])) > 1e-9:
        rep.err('time-budgets 3.1', 'сумма бюджета p95 %s, в таблице %s' % (fmt(sum(p95)), clean(tot[3])))
    if abs(sum(lim) - num(tot[4])) > 1e-9:
        rep.err('time-budgets 3.1', 'сумма пределов %s, в таблице %s' % (fmt(sum(lim)), clean(tot[4])))
    for i, (a, b) in enumerate(zip(p95, lim), 1):
        if b < a:
            rep.err('time-budgets 3.1', 'звено %d: предел %s меньше бюджета p95 %s' % (i, fmt(b), fmt(a)))
    P95, LIM = sum(p95), sum(lim)
    m = re.search(r'Бюджет p95 занимает ([\d,]+) секунды? из 60, запас ([\d,]+) с\. Худший случай первой попытки занимает ([\d,]+) с, запас ([\d,]+) с', s31)
    if not m:
        rep.err('time-budgets 3.1', 'не найдено итоговое предложение под таблицей')
    else:
        exp = (P95, 60 - P95, LIM, 60 - LIM)
        for k, (g, e) in enumerate(zip(m.groups(), exp)):
            if abs(num(g) - e) > 1e-9:
                rep.err('time-budgets 3.1', 'число %d итогового предложения %s, расчёт %s' % (k + 1, g, fmt(e)))
    # --- 3.2
    s32 = L.section(text, r'^3\.2\.')
    t = L.tables(s32)[0]
    body = [r for r in t['rows'] if clean(r[0]).isdigit()]
    total = [r for r in t['rows'] if 'Итого' in ''.join(r)][0]
    secs = [num(r[2]) for r in body]
    if abs(sum(secs) - num(total[2])) > 1e-9:
        rep.err('time-budgets 3.2', 'сумма %s, в таблице %s' % (fmt(sum(secs)), clean(total[2])))
    exp_rows = [LIM, 10 * 1.2, lim[6], lim[7], lim[8]]
    for i, (g, e) in enumerate(zip(secs, exp_rows), 1):
        if abs(g - e) > 1e-9:
            rep.err('time-budgets 3.2', 'строка %d: %s, ожидалось %s (из таблицы 3.1 и паузы 10 с плюс 20%%)' % (i, fmt(g), fmt(e)))
    m = re.search(r'на (\d+)-й секунде, запас (\d+) с', s32)
    if not m:
        rep.err('time-budgets 3.2', 'не найдено предложение «на N-й секунде, запас M с»')
    else:
        if int(m.group(1)) != round(sum(secs)) or int(m.group(2)) != round(60 - sum(secs)):
            rep.err('time-budgets 3.2', 'в тексте %s-я секунда и запас %s с, расчёт %d и %d'
                    % (m.group(1), m.group(2), round(sum(secs)), round(60 - sum(secs))))
    m = re.search(r'Третья попытка начнётся не раньше чем через (\d+) с после второй неудачи', s32)
    if not m:
        rep.err('time-budgets 3.2', 'нет предложения о третьей попытке')
    elif int(m.group(1)) != int(30 * 1.2):
        rep.err('time-budgets 3.2', 'третья попытка через %s с, ожидалось 36 (пауза 30 с плюс 20%%)' % m.group(1))

    # --- раздел 4
    s4 = L.section(text, r'^4\. Проверка согласованности')
    rows4 = {int(clean(r[0])): r for r in L.tables(s4)[0]['rows'] if clean(r[0]).isdigit()}
    if sorted(rows4) != list(range(1, 17)):
        rep.err('time-budgets 4', 'ожидались проверки 1–16, найдены %s' % sorted(rows4))
    else:
        def has(n, *needles):
            cells = ' '.join(rows4[n][1:])
            for nd in needles:
                if nd not in cells:
                    rep.err('time-budgets 4', 'проверка %d: в строке нет «%s» (пересчитанное значение)' % (n, nd))
        # параметры из сводных таблиц
        reserve = 15
        session = 12
        has(1, '%d минус %d равно %d' % (reserve, session, reserve - session), 'запас %d мин' % (reserve - session))
        step = 3 * 2 + 0.3 + 0.7
        has(2, fmt(step))
        has(3, '8 с больше 7 с')
        worst_post = 2 * 0.5 + 2 * 1 + 8
        has(4, fmt(worst_post), '15 с')
        if not worst_post < 15:
            rep.err('time-budgets 4', 'проверка 4: %s не меньше 15 с' % fmt(worst_post))
        has(5, '15 с меньше 60 с')
        has(7, '60 с', '30 с')
        refund = 0 + 30 + 120 + 300 + 600
        has(8, '17,5 мин', '30 мин')
        pauses = 10 + 30 + 120 + 300 + 600
        with_jitter = pauses * 1.2
        attempts = 6 * 7.5
        total9 = with_jitter + attempts + 5
        has(9, mmss(pauses), mmss(with_jitter), '45 с', mmss(total9), 'запас %s' % mmss(1800 - total9))
        total10 = 1800 + 30 + 2
        has(10, mmss(total10), 'запас %s' % mmss(3600 - total10))
        has(11, '2 мин больше 7,5 с')
        has(13, '5 мин')
        has(14, '5 мин', '15 мин', '1 ч')
        has(15, '30 суток больше 14 суток')
        has(16, '30 мин плюс 15 мин плюс 15 мин равно 60 мин')

    # --- раздел 5
    s5 = L.section(text, r'^5\. Ускоренные сроки')
    t5 = {clean(r[0]): r for r in L.tables(s5)[0]['rows']}
    try:
        res, ses = num(t5['Резерв'][2]), num(t5['Платёжная сессия'][2])
        if not ses < res:
            rep.err('time-budgets 5', 'в тестах сессия %s с не короче резерва %s с' % (ses, res))
        if (res - ses) / res < 0.2 - 1e-9:
            rep.err('time-budgets 5', 'запас между сессией и резервом в тестах меньше 20%%: %.1f%%' % ((res - ses) / res * 100))
        if abs(ses / res - session / reserve) > 1e-9:
            rep.err('time-budgets 5', 'отношение сессии к резерву в тестах %.2f, в работе %.2f' % (ses / res, session / reserve))
        p_t = [num(x) for x in re.findall(r'(\d+) с', t5['Паузы повторов выдачи'][2])]
        if p_t != [1, 3, 12, 30, 60]:
            rep.err('time-budgets 5', 'паузы повторов в тестах %s, ожидалось 1, 3, 12, 30, 60' % p_t)
        if p_t != sorted(p_t):
            rep.err('time-budgets 5', 'паузы повторов в тестах не возрастают')
        ctrl = num(t5['Контроль доставки'][2]) * 60
        if not ctrl > sum(p_t):
            rep.err('time-budgets 5', 'контроль доставки в тестах %d с не больше суммы пауз %d с' % (ctrl, sum(p_t)))
        if ('(%d с)' % sum(p_t)) not in t5['Контроль доставки'][3]:
            rep.err('time-budgets 5', 'сумма пауз повторов в тестах %d с не названа в столбце «Что сохраняется»' % sum(p_t))
        a_t = [num(x) for x in re.findall(r'(\d+)', t5['Автовозврат'][2])]
        if a_t != [0, 3, 12, 30, 60] or sum(a_t) >= ctrl:
            rep.err('time-budgets 5', 'автовозврат в тестах %s, сумма %d, контроль %d' % (a_t, sum(a_t), ctrl))
    except KeyError as e:
        rep.err('time-budgets 5', 'нет строки %s' % e)

    # --- сводные таблицы: ключевые значения совпадают с их источниками
    must = [
        (os.path.join(L.ARCH, 'adr', 'ADR-012-reservation-redis-timer.md'), r'15 минут|15 мин', 'резерв 15 мин'),
        (os.path.join(L.ARCH, 'adr', 'ADR-013-late-payment.md'), r'12 минут|12 мин', 'платёжная сессия 12 мин'),
        (os.path.join(L.ARCH, 'adr', 'ADR-013-late-payment.md'), r'17,5 минуты', 'автовозврат 17,5 минуты'),
        (os.path.join(L.ARCH, 'adr', 'ADR-003-kafka-events.md'), r'1, 5, 25 с|1, 5 и 25 с', 'повторы потребителя 1, 5, 25 с'),
        (os.path.join(L.ARCH, 'adr', 'ADR-011-guaranteed-delivery.md'), r'7 мин 40 с(?s:.*)17 мин 40 с', 'расписание повторов выдачи нарастающим итогом (17 мин 40 с)'),
        (os.path.join(L.ARCH, 'c4-components-delivery-service.md'), r'10 с, 30 с, 2 мин, 5 мин, 10 мин', 'паузы повторов выдачи'),
        (os.path.join(L.ARCH, 'sequence-guaranteed-delivery.md'), r'10 с, 30 с, 2 мин, 5 мин, 10 мин', 'паузы повторов выдачи'),
    ]
    for path, rx, what in must:
        if not re.search(rx, L.read(path)):
            rep.err(L.rel(path), 'не найдено значение из таблицы бюджетов времени: %s' % what)
    return P95, LIM


# ================================================================== память
def parse_mem(rep):
    text = L.read(MB)
    cont = {}   # алиас -> (группа, лимит, настройка)
    t = L.table_after(text, r'^### 2\.1\.')
    for r in t['rows']:
        alias = L.backticked(r[1])[0]
        cont[alias] = dict(group=L.backticked(r[2])[0], limit=int(num(r[3])), setting=r[4])
    groups = {}
    t = L.table_after(text, r'^### 2\.2\.')
    for r in t['rows']:
        g = L.backticked(r[0])[0]
        groups[g] = dict(composition=r[1], total=int(num(r[2])))
    sets = {}
    t = L.table_after(text, r'^### 3\.1\.')
    for r in t['rows']:
        name = L.backticked(r[0])[0]
        gl = [x for x in L.backticked(r[1])]
        sets[name] = dict(groups=gl, total=int(num(r[2])), status=r[3])
    margins = {}
    t = L.table_after(text, r'^### 3\.2\.')
    for r in t['rows']:
        margins[L.backticked(r[0])[0]] = r
    return text, cont, groups, sets, margins


def check_memory(rep):
    text, cont, groups, sets, margins = parse_mem(rep)
    # --- контейнеры из c4-containers.md
    for alias in L.containers():
        if alias in NO_PROCESS:
            if ('`%s`' % alias) not in text:
                rep.err('memory-budget 2.1', 'у %s нет процесса, это должно быть сказано в документе' % alias)
            if alias in cont:
                rep.err('memory-budget 2.1', '%s не процесс, лимита памяти у него быть не должно' % alias)
        elif alias not in cont:
            rep.err('memory-budget 2.1', 'контейнер %s из c4-containers.md не имеет лимита' % alias)
    # --- сколько мегабайт в Xmx
    for alias, c in cont.items():
        m = re.search(r'`Xmx` (\d+) МБ', c['setting'])
        if m and alias not in ('keycloak', 'kafka', 'kafka-init'):
            if c['limit'] != int(m.group(1)) + JAVA_OVERHEAD and not (alias == 'platform-service' and c['limit'] == int(m.group(1)) + JAVA_OVERHEAD):
                rep.err('memory-budget 2.1', '%s: лимит %d, Xmx %s плюс %d равно %d (принцип 2)'
                        % (alias, c['limit'], m.group(1), JAVA_OVERHEAD, int(m.group(1)) + JAVA_OVERHEAD))
    # --- суммы групп
    by_group = {}
    for alias, c in cont.items():
        by_group.setdefault(c['group'], []).append(alias)
    for g, info in groups.items():
        calc = sum(cont[a]['limit'] for a in by_group.get(g, []))
        if calc != info['total']:
            rep.err('memory-budget 2.2', 'группа %s: сумма контейнеров %d, в таблице %d' % (g, calc, info['total']))
        # состав «`postgres` 512, `kafka` 448»
        comp = re.findall(r'`([\w-]+)` (\d+)', info['composition'])
        for a, v in comp:
            if a not in cont:
                rep.err('memory-budget 2.2', 'группа %s: контейнер %s не найден в таблице 2.1' % (g, a))
            elif cont[a]['limit'] != int(v):
                rep.err('memory-budget 2.2', 'группа %s: у %s записано %s, в таблице 2.1 %d' % (g, a, v, cont[a]['limit']))
        if comp and sorted(a for a, _ in comp) != sorted(by_group.get(g, [])):
            rep.err('memory-budget 2.2', 'группа %s: состав %s не равен контейнерам группы %s'
                    % (g, sorted(a for a, _ in comp), sorted(by_group.get(g, []))))
    for g in by_group:
        if g not in groups:
            rep.err('memory-budget 2.2', 'группа %s есть у контейнеров, но не в таблице сумм' % g)
    # --- наборы
    r1 = ['infra', 'stubs', 'auth', 'gateway', 'purchase', 'platform', 'storage']
    for name, s in sets.items():
        calc = sum(groups[g]['total'] for g in s['groups'])
        if calc != s['total']:
            rep.err('memory-budget 3.1', 'набор %s: сумма групп %d, в таблице %d' % (name, calc, s['total']))
        want = 'помещается' if calc <= LAPTOP_FIT else ('на пределе' if calc <= LAPTOP else 'только на сервере')
        if not s['status'].startswith(want):
            rep.err('memory-budget 3.1', 'набор %s (%d МБ): статус «%s», по порогам «%s»' % (name, calc, s['status'], want))
        if name == 'full' and sorted(s['groups']) != sorted(r1):
            rep.err('memory-budget 3.1', 'набор full должен включать все профили R1: %s' % r1)
    # --- запасы (3.2)
    env_of = {'Ноутбук': LAPTOP, 'Раннер CI': CI_RUNNER, 'Сервер 8 ГБ': SERVER}
    for name, r in margins.items():
        env = env_of.get(r[1])
        if env is None:
            rep.err('memory-budget 3.2', 'неизвестная среда «%s»' % r[1])
            continue
        if int(num(r[2])) != env:
            rep.err('memory-budget 3.2', 'набор %s: предел среды %s, ожидалось %d' % (name, r[2], env))
        if name not in sets:
            rep.err('memory-budget 3.2', 'набор %s не найден в таблице 3.1' % name)
            continue
        total = sets[name]['total']
        if int(num(r[3])) != total:
            rep.err('memory-budget 3.2', 'набор %s: сумма %s, в 3.1 %d' % (name, r[3], total))
        if int(num(r[4])) != env - total:
            rep.err('memory-budget 3.2', 'набор %s: запас %s, расчёт %d' % (name, r[4], env - total))
        if int(num(r[5])) != round((env - total) / env * 100):
            rep.err('memory-budget 3.2', 'набор %s: запас %s%%, расчёт %d%%' % (name, r[5], round((env - total) / env * 100)))
    # --- размер сервера (раздел 4)
    s4 = L.section(text, r'^4\. Размер сервера')
    t = L.tables(s4)
    rows = {r[0]: r for r in t[0]['rows']}
    srv = sets['server']['total']
    worst = srv + SYSTEM_RESERVE
    chk = [('Сумма лимитов набора `server`', srv), ('Система и Docker', SYSTEM_RESERVE), ('Итого худший случай', worst),
           ('Размер сервера', SERVER), ('Запас', SERVER - worst)]
    for k, v in chk:
        if k not in rows:
            rep.err('memory-budget 4', 'нет строки «%s»' % k)
        elif int(num(rows[k][1])) != v:
            rep.err('memory-budget 4', '«%s»: в таблице %s, расчёт %d' % (k, rows[k][1], v))
    if 'Запас' in rows and ('%d процент' % round((SERVER - worst) / SERVER * 100)) not in rows['Запас'][2]:
        rep.err('memory-budget 4', 'доля запаса в процентах не равна %d' % round((SERVER - worst) / SERVER * 100))
    full = sets['full']['total']
    v = {r[0]: r for r in t[1]['rows']}
    if not any(('%d плюс 800 равно %d' % (full, full + SYSTEM_RESERVE)) in r[1] for r in t[1]['rows']):
        rep.err('memory-budget 4', 'строка «6 ГБ»: ожидалось «%d плюс 800 равно %d»' % (full, full + SYSTEM_RESERVE))
    if not any(('запас %d МБ' % (6144 - full - SYSTEM_RESERVE)) in r[1] for r in t[1]['rows']):
        rep.err('memory-budget 4', 'строка «6 ГБ»: запас должен быть %d МБ' % (6144 - full - SYSTEM_RESERVE))
    if full + SYSTEM_RESERVE <= 4096:
        rep.err('memory-budget 4', 'строка «4 ГБ»: %d плюс система не больше 4096, вердикт «не подходит» неверен' % full)
    # --- числа, повторённые в других документах
    adr3 = L.read(os.path.join(L.ARCH, 'adr', 'ADR-003-kafka-events.md'))
    if ('%d МБ' % cont['kafka']['limit']) not in adr3:
        rep.err('ADR-003', 'лимит kafka %d МБ из memory-budget.md не назван' % cont['kafka']['limit'])
    return cont, groups, sets


def check_deployment(rep, cont, groups, sets):
    text = L.read(DEP)
    # --- состав профилей
    t = L.table_after(text, r'^## 3\. Профили Compose')
    prof = {}
    for r in t['rows']:
        prof[L.backticked(r[0])[0]] = sorted(L.backticked(r[1]))
    for g, info in groups.items():
        want = sorted(a for a, c in cont.items() if c['group'] == g)
        if g not in prof:
            rep.err('c4-deployment 3', 'профиль %s отсутствует' % g)
        elif prof[g] != want:
            rep.err('c4-deployment 3', 'профиль %s: %s, в memory-budget.md %s' % (g, prof[g], want))
    # --- наборы: «dev-min» раскрывается в профили
    t2 = tables_after_set(text)
    sets_dep = {}
    for r in t2['rows']:
        sets_dep[L.backticked(r[0])[0]] = L.backticked(r[1]) if 'все профили' not in r[1] else None

    def expand(name, seen=()):
        if name in prof:
            return [name]
        if name == 'full':
            return ['infra', 'stubs', 'auth', 'gateway', 'purchase', 'platform', 'storage']
        out = []
        for x in sets_dep[name]:
            out += expand(x)
        return out
    for name in sets:
        if name not in sets_dep:
            rep.err('c4-deployment 3', 'набор %s не описан' % name)
            continue
        got = sorted(set(expand(name)))
        if got != sorted(sets[name]['groups']):
            rep.err('c4-deployment 3', 'набор %s раскрывается в %s, в memory-budget.md %s' % (name, got, sorted(sets[name]['groups'])))
    # --- диаграммы
    blocks = L.mermaid_blocks(text)
    d2 = None
    for b in blocks:
        if 'p-gateway' in b:
            d2 = (b, L.flowchart(b))
    if d2 is None:
        rep.err('c4-deployment 2.1', 'не найдена диаграмма профилей Compose')
        return
    b, fc = d2
    in_diagram = set(fc['nodes'])
    intro = text[text.index('### 2.1.'):text.index('```mermaid', text.index('### 2.1.'))]
    for alias in L.containers():
        if alias in NO_PROCESS:
            if ('`%s`' % alias) not in intro:
                rep.err('c4-deployment 2.1', '%s не на диаграмме профилей: в тексте раздела 2.1 это должно быть объяснено' % alias)
        elif alias not in in_diagram:
            rep.err('c4-deployment 2.1', 'контейнер %s из c4-containers.md не показан на диаграмме профилей' % alias)
    for m in re.finditer(r'subgraph p-([\w-]+)\["<b>Профиль ([\w-]+), (\d+) МБ</b>"\]', b):
        sg, name, v = m.group(1), m.group(2), int(m.group(3))
        if sg != name:
            rep.err('c4-deployment 2.1', 'подграф p-%s подписан профилем %s' % (sg, name))
        if name in groups and groups[name]['total'] != v:
            rep.err('c4-deployment 2.1', 'профиль %s на диаграмме %d МБ, в memory-budget.md %d' % (name, v, groups[name]['total']))
    seen_prof = set(re.findall(r'subgraph p-([\w-]+)\[', b))
    for g in ('infra', 'stubs', 'auth', 'gateway', 'purchase', 'platform', 'storage'):
        if g not in seen_prof:
            rep.err('c4-deployment 2.1', 'на диаграмме нет профиля %s' % g)
    # --- элементов на диаграммах не больше 15 (узлы без подграфов)
    for i, blk in enumerate(blocks, 1):
        count = len(L.flowchart(blk)['nodes'])
        if count > MAX_DIAGRAM_ELEMENTS:
            rep.err('c4-deployment', 'диаграмма %d: %d элементов, не больше %d' % (i, count, MAX_DIAGRAM_ELEMENTS))


def tables_after_set(text):
    for t in L.tables(text):
        if t['header'][:2] == ['Набор', 'Профили']:
            return t
    raise ValueError('нет таблицы наборов')


def main():
    rep = L.Report('budgets')
    P95, LIM = check_time(rep) or (0, 0)
    cont, groups, sets = check_memory(rep)
    check_deployment(rep, cont, groups, sets)
    rep.fact('Бюджеты: время p95 %s с, предел %s с (из 60); память: %d контейнеров, %d групп, %d наборов; server %d МБ'
             % (fmt(P95), fmt(LIM), len(cont), len(groups), len(sets), sets['server']['total']))
    return rep.finish()


if __name__ == '__main__':
    sys.exit(main())
