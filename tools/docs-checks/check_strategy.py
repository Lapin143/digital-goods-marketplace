# -*- coding: utf-8 -*-
"""Стратегия тестирования (docs/08-testing/test-strategy.md) против источников, из которых она построена.

  1. Структура: 17 разделов подряд, поля шапки, таблицы уровней, инструментов, нефункциональных требований.
  2. Уровни и инструменты: ссылки L1 – L8 только на определённые уровни, все названные инструменты описаны в разделе 3,
     числа в таблице уровней (операции OpenAPI, события AsyncAPI, сценарии, тесты ST, проверки L6 и L8) равны подсчёту.
  3. 29 NFT: каждое назначено уровню, инструменту, окружению и тестам, фазы равны сводке методики NFR (раздел 5),
     срезы в разделе 3 методики совпадают с её сводкой, TC-номера не повторяются и лежат в своих областях.
  4. 44 инварианта: тип IT-1 – IT-10, релиз равен c4-components.md, IT-1 стоит ровно у инвариантов
     со способом «ограничение в базе» (07-data), все инварианты со своим тестом в db/test_db.py имеют IT-1,
     инварианты R1 типов IT-3 и IT-4 названы в таблице конкуренции.
  5. Конкуренция (раздел 7) и сквозные сценарии (раздел 8): номера TC, покрытие диаграмм SEQ и исключений BPMN-01, 02, 04,
     режимы заглушек ссылаются на существующие TC и исключения.
  6. Тесты безопасности ST-01 – ST-14 равны модели угроз, первый запуск согласован с критериями выхода.
  7. Нумерация: области TC не пересекаются, все номера TC в документах лежат в областях, файлы областей названы по шаблону,
     у каждой области US существует история.
  8. Критерии выхода: NFT равны сводке методики, FT равны срезам плана и вместе дают все 42 FT релиза R1,
     ST равны разделу 9, сквозные сценарии срезов дают все 16.
  9. Постоянные: числа, повторённые в разных разделах и документах (повторы, карантин, покрытие, ускоренные сроки, память).
"""
import glob
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import docslib as L

TEST = os.path.join(L.DOCS, '08-testing')
STRATEGY = os.path.join(TEST, 'test-strategy.md')
ENVS = {'Локально', 'CI', 'Сервер'}
# названия инструментов, которые, если встретились в столбцах «Инструмент», обязаны быть описаны в разделе 3
KNOWN_TOOLS = ['JUnit', 'AssertJ', 'Mockito', 'ArchUnit', 'Testcontainers', 'Awaitility', 'Spectral', 'Postman', 'Newman',
               'k6', 'Prometheus', 'OpenTelemetry', 'ESLint', 'Docker Compose', 'openssl s_client', 'external-stubs',
               'psql', 'Playwright']
# метки способа без инструмента: чек-лист и расчёт
NO_TOOL_MARKS = ('Чек-лист', 'Расчёт', 'Чек-листы')
FT_RX = re.compile(r'(?<!N)FT-(\d+)\.(\d+)(?:\s*[–-]\s*FT-(\d+)\.(\d+))?')


def ft_set(text):
    res = set()
    for m in FT_RX.finditer(text):
        a, b, c, d = m.groups()
        if c is None:
            res.add('FT-%s.%s' % (a, b))
        else:
            for k in range(int(b), int(d) + 1):
                res.add('FT-%s.%d' % (a, k))
    return res


def nft_set(text):
    return set(re.findall(r'NFT-\d+\.\d+', text))


def inv_num(i):
    return int(i.split('-')[1])


def main():
    rep = L.Report('strategy')
    T = L.read(STRATEGY)
    where = 'test-strategy.md'
    body_nocode = re.sub(r'```.*?```', '', T, flags=re.S)

    # ------------------------------------------------ 1. структура
    heads = re.findall(r'^## (\d+)\. (.+)$', T, re.M)
    nums = [int(n) for n, _ in heads]
    if nums != list(range(1, 18)):
        rep.err(where, 'разделы второго уровня должны идти подряд с 1 по 17, найдено %s' % nums)
    head_tb = L.tables(T)[0]
    fields = [r[0] for r in head_tb['rows']]
    for f in ('Документ', 'Фаза', 'Основание', 'Потребители', 'Проверка'):
        if f not in fields:
            rep.err(where, 'в шапке нет поля «%s»' % f)

    def sec(n):
        b = L.section(T, r'^%d\. ' % n)
        if b is None:
            rep.err(where, 'нет раздела %d' % n)
            return ''
        return b

    s2, s3, s4, s5, s6, s7, s8, s9, s10, s11, s12, s13, s14, s16 = [sec(n) for n in (2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 16)]

    # ------------------------------------------------ 2. уровни и инструменты
    levels_tb = L.find_table(s2, 'Уровень')
    levels = {}
    if not levels_tb:
        rep.err(where, 'нет таблицы уровней')
    else:
        for r in levels_tb['rows']:
            m = re.match(r'(L\d)\.', r[0])
            if not m:
                rep.err(where, 'уровень «%s» не начинается с L<n>.' % r[0])
                continue
            if m.group(1) in levels:
                rep.err(where, 'уровень %s описан дважды' % m.group(1))
            if any(not c for c in r):
                rep.err(where, 'в строке уровня %s пустая ячейка' % m.group(1))
            levels[m.group(1)] = r
        if sorted(levels) != ['L%d' % i for i in range(1, 9)]:
            rep.err(where, 'уровни должны быть L1 – L8 без пропусков, найдено %s' % sorted(levels))
    for lv in set(re.findall(r'\bL(\d)\b', body_nocode)):
        if 'L' + lv not in levels:
            rep.err(where, 'ссылка на уровень L%s, которого нет в таблице' % lv)

    tools_tb = L.find_table(s3, 'Инструмент')
    tools3 = ''
    if not tools_tb:
        rep.err(where, 'нет таблицы инструментов раздела 3')
    else:
        tools3 = '\n'.join(r[0] for r in tools_tb['rows'])
        for r in tools_tb['rows']:
            if len(r) < 2 or not r[1]:
                rep.err(where, 'у инструмента «%s» нет описания' % r[0])
    tool_cells = []
    for t in L.tables(T):
        if 'Инструмент' in t['header'] and t is not tools_tb:
            ci = t['header'].index('Инструмент')
            tool_cells += [r[ci] for r in t['rows']]
    used = ' '.join(tool_cells)
    for k in KNOWN_TOOLS:
        if k in used and k not in tools3:
            rep.err(where, 'инструмент %s назван в таблицах, но не описан в разделе 3' % k)
    for k in KNOWN_TOOLS:
        if k in tools3 and k not in T.replace(tools3, '', 1)[len(tools3):] and k not in used:
            pass    # инструмент описан, но нигде не применён: допустимо, разделы 3 и 5 различаются по охвату

    # числа в таблице уровней
    ops = 0
    for f in glob.glob(os.path.join(L.DOCS, '06-api', 'openapi', '*.yaml')):
        ops += len(re.findall(r'^\s+operationId:', L.read(f), re.M))
    # число событий берётся из каталога событий, его сверяет с контрактом check_asyncapi.py
    mev = re.search(r'\| Объём \| (\d+) событий', L.read(os.path.join(L.DOCS, '06-api', 'events', 'README.md')))
    events = int(mev.group(1)) if mev else 0
    l4 = levels.get('L4', ['', '', '', '', '', '', ''])
    m = re.search(r'(\d+) операци\w+ OpenAPI, (\d+) событи\w+ AsyncAPI', ' '.join(l4))
    if not m:
        rep.err(where, 'в строке L4 нет «N операций OpenAPI, M событий AsyncAPI»')
    else:
        if int(m.group(1)) != ops:
            rep.err(where, 'L4: операций OpenAPI %s, а в контрактах %d' % (m.group(1), ops))
        if events and int(m.group(2)) != events:
            rep.err(where, 'L4: событий AsyncAPI %s, а в контракте %d' % (m.group(2), events))

    # ------------------------------------------------ 3. нефункциональные требования
    nfr_text = L.read(os.path.join(TEST, 'nfr-methodology.md'))
    _, all_nft = L.requirement_ids()
    sum5 = L.table_after(nfr_text, r'^## 5\. Сводка по фазам')
    nfr_phase = {}          # метка фазы -> NFT
    nft_phases = {}         # NFT -> метки фаз
    for r in sum5['rows']:
        ids = nft_set(r[2])
        nfr_phase[r[0]] = ids
        for i in ids:
            nft_phases.setdefault(i, set()).add(r[0])

    # срезы в разделе 3 методики совпадают со сводкой раздела 5
    n_cross = 0
    for t in L.tables(nfr_text):
        if t['header'] and t['header'][0] == 'Требование' and t['header'][-1] == 'Когда и где':
            for r in t['rows']:
                nid = re.match(r'(NFT-\d+\.\d+)', r[0]).group(1)
                mentioned = set()
                for mm in re.finditer(r'срез(?:ы|а)?\s+(\d)(?:\s+и\s+(\d))?', r[-1]):
                    mentioned |= {x for x in mm.groups() if x}
                if not mentioned:
                    continue
                n_cross += 1
                summary = {re.search(r'срез (\d)', p).group(1) for p in nft_phases.get(nid, ()) if 'срез' in p}
                if mentioned != summary:
                    rep.err('nfr-methodology.md', '%s: в разделе 3 срезы %s, в сводке раздела 5 срезы %s'
                            % (nid, sorted(mentioned), sorted(summary)))

    nft_tb = L.find_table(s5, 'NFT', 'Уровень')
    strat_phase = {}
    tc_in_nft = []
    st_universe = set(re.findall(r'^\| (ST-\d{2}) \|', L.read(os.path.join(L.ARCH, 'threat-model.md')), re.M))
    if not nft_tb:
        rep.err(where, 'нет таблицы NFT в разделе 5')
        ids5 = []
    else:
        ids5 = [r[0] for r in nft_tb['rows']]
        if len(ids5) != len(set(ids5)):
            rep.err(where, 'в таблице NFT повторяется номер')
        if set(ids5) != all_nft:
            rep.err(where, 'таблица NFT и требования расходятся: нет %s, лишние %s'
                    % (sorted(all_nft - set(ids5)), sorted(set(ids5) - all_nft)))
        h = nft_tb['header']
        for r in nft_tb['rows']:
            nid = r[0]
            row = dict(zip(h, r))
            for col in h[1:]:
                if not row.get(col):
                    rep.err(where, '%s: пустой столбец «%s»' % (nid, col))
            for lv in re.findall(r'\bL(\d)\b', row['Уровень']):
                if 'L' + lv not in levels:
                    rep.err(where, '%s: уровень L%s не определён' % (nid, lv))
            if not re.search(r'\bL\d\b', row['Уровень']):
                rep.err(where, '%s: в столбце «Уровень» нет номера уровня' % nid)
            for e in [x.strip() for x in row['Окружение'].split(',')]:
                if e not in ENVS:
                    rep.err(where, '%s: окружение «%s» не из %s' % (nid, e, sorted(ENVS)))
            tc = row['Инструмент']
            if re.search(r'`check_\w+\.py`', tc) and 'tools/docs-checks' in tools3:
                tc = 'tools/docs-checks'
                tools_ok = True
            else:
                tools_ok = False
            if not tools_ok and not any(re.search(r'(^|[ ,`])%s' % re.escape(re.sub(r'\s+\d+$', '', t.strip('` '))), tc)
                       for t in re.split(r',\s*', tools3.replace('\n', ', ')) if t.strip('` ')) \
                    and not tc.startswith(NO_TOOL_MARKS):
                rep.err(where, '%s: инструмент «%s» не описан в разделе 3' % (nid, tc))
            phases = {p.strip() for p in row['Фазы'].split(';')}
            strat_phase[nid] = phases
            if phases != nft_phases.get(nid, set()):
                rep.err(where, '%s: фазы %s, а в сводке методики %s' % (nid, sorted(phases), sorted(nft_phases.get(nid, set()))))
            for st in re.findall(r'ST-\d{2}', row['Тесты']):
                if st not in st_universe:
                    rep.err(where, '%s: теста %s нет в модели угроз' % (nid, st))
            tc_in_nft += re.findall(r'TC-\d{3}', re.sub(r'Область TC-\d{3} – TC-\d{3}', '', row['Тесты']))
            if not (re.search(r'(TC|ST)-\d', row['Тесты']) or row['Тесты'].startswith(('Чек-лист', 'Область', 'Лимит'))):
                rep.err(where, '%s: в столбце «Тесты» нет ни TC, ни ST, ни чек-листа' % nid)
        if len(tc_in_nft) != len(set(tc_in_nft)):
            dup = sorted({x for x in tc_in_nft if tc_in_nft.count(x) > 1})
            rep.err(where, 'в таблице NFT повторяются номера TC: %s' % dup)
    n_l6 = sum(1 for r in (nft_tb['rows'] if nft_tb else []) if re.search(r'\bL6\b', r[1]))
    n_l8 = sum(1 for r in (nft_tb['rows'] if nft_tb else []) if re.search(r'\bL8\b', r[1]))
    n_nft_l8_row = re.search(r'(\d+) проверок', ' '.join(levels.get('L8', [])))
    n_nft_l6_row = re.search(r'(\d+) сценариев', ' '.join(levels.get('L6', [])))
    if n_nft_l6_row and int(n_nft_l6_row.group(1)) != n_l6:
        rep.err(where, 'L6: в таблице уровней %s сценариев, в таблице NFT строк с L6 %d' % (n_nft_l6_row.group(1), n_l6))
    if n_nft_l8_row and int(n_nft_l8_row.group(1)) != n_l8:
        rep.err(where, 'L8: в таблице уровней %s проверок, в таблице NFT строк с L8 %d' % (n_nft_l8_row.group(1), n_l8))

    # ------------------------------------------------ 4. инварианты
    dm = L.read(os.path.join(L.DOCS, '04-domain', 'domain-model.md'))
    inv_all = re.findall(r'^\| (INV-\d+) \|', dm, re.M)
    types_tb = L.find_table(s6, 'Тип', 'Вид теста')
    types = {}
    if not types_tb:
        rep.err(where, 'нет таблицы типов IT')
    else:
        for r in types_tb['rows']:
            types[r[0]] = r
            for lv in re.findall(r'\bL(\d)\b', r[4]):
                if 'L' + lv not in levels:
                    rep.err(where, '%s: уровень L%s не определён' % (r[0], lv))
        if sorted(types, key=lambda x: int(x[3:])) != ['IT-%d' % i for i in range(1, 11)]:
            rep.err(where, 'типы должны быть IT-1 – IT-10, найдено %s' % sorted(types))
    inv_tb = L.find_table(s6, 'Инвариант', 'Тип')
    c4c = L.read(os.path.join(L.ARCH, 'c4-components.md'))
    c4_r2 = set()
    c4_all = set()
    sec6 = L.section(c4c, r'^6\. Инварианты и компоненты') or ''
    for r in (L.find_table(sec6, 'Инвариант') or {'rows': []})['rows']:
        mm = re.match(r'INV-(\d+)(?: – INV-(\d+))?( \(R2\))?', r[0])
        lo = int(mm.group(1))
        hi = int(mm.group(2) or lo)
        for k in range(lo, hi + 1):
            c4_all.add('INV-%02d' % k)
            if mm.group(3):
                c4_r2.add('INV-%02d' % k)
    ways = {}
    for t in L.tables(L.read(os.path.join(L.DOCS, '07-data', 'README.md'))):
        if t['header'][:3] == ['ID', 'Инвариант', 'Способ']:
            for r in t['rows']:
                ways[r[0]] = r[2]
    src_db = L.read(os.path.join(L.REPO, 'tools', 'docs-checks', 'db', 'test_db.py'))
    db_cov = set()
    n_db_tests = 0
    for mm in re.finditer(r"^@test\((.*?)\)\n(?=def )", src_db, re.M | re.S):
        n_db_tests += 1
        db_cov |= set(re.findall(r'INV-\d+', mm.group(1)))
    inv_types = {}
    inv_rel = {}
    if not inv_tb:
        rep.err(where, 'нет таблицы инвариантов')
    else:
        got = [r[0] for r in inv_tb['rows']]
        if got != inv_all:
            rep.err(where, 'таблица инвариантов не равна доменной модели: нет %s, лишние %s, порядок %s'
                    % (sorted(set(inv_all) - set(got)), sorted(set(got) - set(inv_all)), got == sorted(got)))
        for r in inv_tb['rows']:
            iid, prim, extra, rel = r[0], r[1], r[2], r[3]
            ex = [x.strip() for x in extra.split(',') if x.strip()]
            allt = [prim] + ex
            for t in allt:
                if t not in types:
                    rep.err(where, '%s: тип %s не определён' % (iid, t))
            if len(set(allt)) != len(allt):
                rep.err(where, '%s: тип повторяется' % iid)
            if ex != sorted(ex, key=lambda x: int(x[3:]) if x[3:].isdigit() else 0):
                rep.err(where, '%s: дополнительные типы идут не по порядку' % iid)
            inv_types[iid] = set(allt)
            inv_rel[iid] = rel
            exp_rel = 'R2' if iid in c4_r2 else 'R1'
            if rel != exp_rel:
                rep.err(where, '%s: релиз %s, в c4-components.md %s' % (iid, rel, exp_rel))
            db = 'ограничение в базе' in ways.get(iid, '')
            if db and 'IT-1' not in allt:
                rep.err(where, '%s: способ «ограничение в базе» (07-data), а типа IT-1 нет' % iid)
            if not db and 'IT-1' in allt:
                rep.err(where, '%s: IT-1 есть, а в 07-data способ «%s» без ограничения в базе' % (iid, ways.get(iid)))
            if iid in db_cov and 'IT-1' not in allt:
                rep.err(where, '%s: есть тест в db/test_db.py, а типа IT-1 нет' % iid)
        if c4_all != set(inv_all):
            rep.err('c4-components.md', 'инварианты раздела 6 не равны доменной модели')
    # текстовые числа о тестах базы
    d7 = L.read(os.path.join(L.DOCS, '07-data', 'README.md'))
    m39 = re.search(r'(\d+) тестов на данных', d7)
    m113 = re.search(r'выполняет (\d+) запрос', d7)
    for mm, what, doc_n in ((re.search(r'(\d+) проверок `db/test_db\.py`', T), 'проверок db/test_db.py', n_db_tests),
                            (re.search(r'(\d+) тестов `db/test_db\.py`', T), 'тестов db/test_db.py', n_db_tests)):
        if mm and int(mm.group(1)) != doc_n:
            rep.err(where, '%s: в стратегии %s, в db/test_db.py %d' % (what, mm.group(1), doc_n))
    if m39 and int(m39.group(1)) != n_db_tests:
        rep.err('docs/07-data/README.md', '%s тестов в тексте, в db/test_db.py %d' % (m39.group(1), n_db_tests))
    mm = re.search(r'(\d+) запросов проверки `EXPLAIN`', T)
    if mm and m113 and mm.group(1) != m113.group(1):
        rep.err(where, 'запросов EXPLAIN %s, в 07-data %s' % (mm.group(1), m113.group(1)))

    # ------------------------------------------------ 5. конкуренция
    conc_tb = L.find_table(s7, 'TC', 'Что проверяется')
    conc_text = ''
    conc_ids = []
    if not conc_tb:
        rep.err(where, 'нет таблицы раздела 7')
    else:
        conc_ids = [r[0] for r in conc_tb['rows']]
        if len(conc_ids) != len(set(conc_ids)):
            rep.err(where, 'в разделе 7 повторяется номер TC')
        nn = [int(x[3:]) for x in conc_ids]
        if nn != sorted(nn) or nn != list(range(nn[0], nn[0] + len(nn))):
            rep.err(where, 'номера TC раздела 7 должны идти подряд по возрастанию')
        for r in conc_tb['rows']:
            if any(not c for c in r):
                rep.err(where, '%s: пустая ячейка в таблице раздела 7' % r[0])
        conc_text = '\n'.join(' '.join(r) for r in conc_tb['rows'])
        for iid, ts in inv_types.items():
            if inv_rel.get(iid) == 'R1' and ('IT-3' in ts or 'IT-4' in ts) and iid not in conc_text:
                rep.err(where, '%s (тип %s) не назван в таблице раздела 7' % (iid, ', '.join(sorted(ts & {'IT-3', 'IT-4'}))))

    # ------------------------------------------------ 6. сквозные сценарии
    e2e_tb = L.find_table(s8, 'TC', 'Сценарий')
    seq_diagrams = set()
    for f in glob.glob(os.path.join(L.ARCH, 'sequence-*.md')):
        seq_diagrams |= set(re.findall(r'^## \d+\. Диаграмма (\d\d\.\d)\.', L.read(f), re.M))
    bpmn = L.bpmn_exceptions()
    must_cover = {'BPMN-01', 'BPMN-02', 'BPMN-04'}
    e2e_ids = []
    e2e_exc = {}
    cov_diag = set()
    cov_exc = set()
    if not e2e_tb:
        rep.err(where, 'нет таблицы сквозных сценариев')
    else:
        for r in e2e_tb['rows']:
            tid = r[0]
            e2e_ids.append(tid)
            diags = set(re.findall(r'\d\d\.\d', r[2]))
            for d in diags:
                if d not in seq_diagrams:
                    rep.err(where, '%s: диаграммы %s нет в sequence-файлах' % (tid, d))
            cov_diag |= diags
            exc = set()
            cur = None
            for mm in re.finditer(r'(BPMN-\d+)|\bE(\d+)', r[3]):
                if mm.group(1):
                    cur = mm.group(1)
                else:
                    if cur is None:
                        rep.err(where, '%s: исключение E%s без процесса' % (tid, mm.group(2)))
                        continue
                    exc.add((cur, 'E' + mm.group(2)))
            for b, e in exc:
                if e not in bpmn.get(b, set()):
                    rep.err(where, '%s: исключения %s %s нет в процессе' % (tid, b, e))
            if r[3] != 'нет' and not exc:
                rep.err(where, '%s: в столбце «Исключения» нет ни «нет», ни ссылок BPMN-NN E<n>' % tid)
            e2e_exc[tid] = exc
            cov_exc |= exc
        if len(e2e_ids) != len(set(e2e_ids)):
            rep.err(where, 'в разделе 8 повторяется номер TC')
        want_diag = seq_diagrams - {'01.0'}
        if cov_diag != want_diag:
            rep.err(where, 'сквозные сценарии и диаграммы SEQ расходятся: не покрыты %s, лишние %s'
                    % (sorted(want_diag - cov_diag), sorted(cov_diag - want_diag)))
        want_exc = {(b, e) for b in must_cover for e in bpmn.get(b, ())}
        if cov_exc != want_exc:
            rep.err(where, 'исключения BPMN-01, 02, 04 покрыты не все: нет %s, лишние %s'
                    % (sorted(want_exc - cov_exc), sorted(cov_exc - want_exc)))
        if sorted(e2e_ids) != ['TC-%03d' % i for i in range(1, len(e2e_ids) + 1)]:
            rep.err(where, 'номера TC раздела 8 должны идти с TC-001 без пропусков')
        words = {16: 'шестнадцать'}
        if len(e2e_ids) in words and not re.search(words[len(e2e_ids)], s8):
            rep.err(where, 'число сценариев %d не названо словом в тексте раздела 8' % len(e2e_ids))
        if levels.get('L5') and not re.search(r'\b%d сценари' % len(e2e_ids), ' '.join(levels['L5'])):
            rep.err(where, 'L5: число сценариев в таблице уровней не равно %d' % len(e2e_ids))

    # режимы заглушек
    stub_tb = L.find_table(s10, 'Система', 'Режим')
    n_stub = 0
    if stub_tb:
        for r in stub_tb['rows']:
            for g, ex in re.findall(r'((?:TC-\d{3}(?:, )?)+) \((E\d+(?:, E\d+)*)\)', r[3]):
                tcs = re.findall(r'TC-\d{3}', g)
                allowed = set()
                for t in tcs:
                    if t not in e2e_exc:
                        rep.err(where, 'заглушка %s: %s не сквозной сценарий раздела 8' % (r[1], t))
                    allowed |= {e for b, e in e2e_exc.get(t, set()) if b == 'BPMN-01'}
                for e in re.findall(r'E\d+', ex):
                    n_stub += 1
                    if e not in allowed:
                        rep.err(where, 'заглушка %s: %s не входит в исключения %s' % (r[1], e, ', '.join(tcs)))
        # каждое исключение BPMN-01 со внешней причиной воспроизводится режимом заглушки
        stub_exc = set(re.findall(r'\((E\d+(?:, E\d+)*)\)', ' '.join(r[3] for r in stub_tb['rows'])))
        stub_exc = {e for g in stub_exc for e in re.findall(r'E\d+', g)}
        for e in ('E3', 'E5', 'E6', 'E7', 'E8', 'E9', 'E10', 'E11', 'E12'):
            if e not in stub_exc:
                rep.err(where, 'исключение BPMN-01 %s не воспроизводится ни одним режимом заглушки' % e)
    else:
        rep.err(where, 'нет таблицы режимов заглушек')

    # ------------------------------------------------ 7. тесты безопасности
    st_tb = L.find_table(s9, 'Тест', 'Первый запуск')
    st_first = {}
    if not st_tb:
        rep.err(where, 'нет таблицы ST в разделе 9')
    else:
        got = [r[0] for r in st_tb['rows']]
        if got != sorted(st_universe):
            rep.err(where, 'ST раздела 9 не равны модели угроз: нет %s, лишние %s'
                    % (sorted(st_universe - set(got)), sorted(set(got) - st_universe)))
        for r in st_tb['rows']:
            st_first[r[0]] = r[3]
            if not r[2].startswith('L7'):
                rep.err(where, '%s: тест безопасности должен быть уровня L7' % r[0])
            if r[3] not in ('Ф3', 'Ф4, срез 1', 'Ф4, срез 2', 'Ф4, срез 3'):
                rep.err(where, '%s: первый запуск «%s» не из Ф3, Ф4 срез 1 – 3' % (r[0], r[3]))
        if levels.get('L7') and not re.search(r'\b%d тест' % len(st_tb['rows']), ' '.join(levels['L7'])):
            rep.err(where, 'L7: число тестов в таблице уровней не равно %d' % len(st_tb['rows']))

    # ------------------------------------------------ 8. нумерация
    areas_tb = L.find_table(s13, 'Область', 'Диапазон')
    areas = []
    if not areas_tb:
        rep.err(where, 'нет таблицы областей TC')
    else:
        files = set()
        for r in areas_tb['rows']:
            mm = re.match(r'TC-(\d{3}) – TC-(\d{3})$', r[1])
            if not mm:
                rep.err(where, 'диапазон «%s» не вида TC-NNN – TC-NNN' % r[1])
                continue
            lo, hi = int(mm.group(1)), int(mm.group(2))
            if lo > hi:
                rep.err(where, 'диапазон %s перевёрнут' % r[1])
            areas.append((lo, hi, r))
            fn = L.backticked(r[3])
            if not fn or not re.match(r'TC-[\w-]+\.md$', fn[0]):
                rep.err(where, 'область %s: файл «%s» не вида TC-<имя>.md' % (r[1], r[3]))
            elif fn[0] in files:
                rep.err(where, 'файл %s назван у двух областей' % fn[0])
            else:
                files.add(fn[0])
            ms = re.search(r'US-(\d+)', r[2])
            if ms:
                us_files = glob.glob(os.path.join(L.DOCS, '02-requirements', 'US-%s-*.md' % ms.group(1)))
                if not us_files:
                    rep.err(where, 'область %s: истории US-%s нет' % (r[1], ms.group(1)))
        areas.sort()
        for a, b in zip(areas, areas[1:]):
            if a[1] >= b[0]:
                rep.err(where, 'области %s и %s пересекаются' % (a[2][1], b[2][1]))
        # US-01 – US-08 лежат в сотнях 1xx – 8xx
        for lo, hi, r in areas:
            ms = re.search(r'US-(\d+)', r[2])
            if ms and int(ms.group(1)) <= 8 and lo // 100 != int(ms.group(1)):
                rep.err(where, 'область %s для US-%s должна лежать в сотне %d' % (r[1], ms.group(1), int(ms.group(1))))
        us_nums = sorted(int(re.match(r'US-(\d+)-', os.path.basename(f)).group(1))
                         for f in glob.glob(os.path.join(L.DOCS, '02-requirements', 'US-*.md')))
        area_us = sorted(int(re.search(r'US-(\d+)', r[2]).group(1)) for _, _, r in areas if re.search(r'US-(\d+)', r[2]))
        if area_us != us_nums:
            rep.err(where, 'истории с областями TC: %s, файлов историй: %s' % (area_us, us_nums))

    def in_area(n):
        return any(lo <= n <= hi for lo, hi, _ in areas)

    n_tc = 0
    for f in glob.glob(os.path.join(L.DOCS, '**', '*.md'), recursive=True):
        if os.path.basename(f) == '_template-test-case.md':
            continue
        text = re.sub(r'```.*?```', '', L.read(f), flags=re.S)
        for m in set(re.findall(r'(?<![\w-])TC-(\d{3})\b', text)):
            n_tc += 1
            if not in_area(int(m)):
                rep.err(L.rel(f), 'TC-%s не входит ни в одну область раздела 13' % m)
    # TC раздела 7 и 8 лежат в своих областях (001 – 029 и 030 – 059)
    for tid in e2e_ids:
        if not (1 <= int(tid[3:]) <= 29):
            rep.err(where, '%s вне области сквозных сценариев 001 – 029' % tid)
    for tid in conc_ids:
        if not (30 <= int(tid[3:]) <= 59):
            rep.err(where, '%s вне области конкуренции 030 – 059' % tid)
    # TC таблицы NFT: производительность 060 – 079, эксплуатация 080 – 099, администрирование 801 – 899 и т. д.
    for tid in tc_in_nft:
        n = int(tid[3:])
        if tid in e2e_ids or tid in conc_ids:
            continue
        if not (60 <= n <= 99 or 801 <= n <= 899):
            rep.err(where, 'таблица NFT: %s не определён в разделах 7 и 8 и не лежит в областях 060 – 099 и 801 – 899' % tid)

    # ------------------------------------------------ 9. критерии выхода
    exit_tb = L.find_table(s14, 'Срез', 'Функциональные требования')
    plan = L.read(os.path.join(L.DOCS, '00-project-plan.md'))
    plan_ft = {}
    for m in re.finditer(r'^\*\*Срез (\d)\.[^\n]*\n(.*?)(?=^\*\*|^###|\Z)', plan, re.M | re.S):
        plan_ft[int(m.group(1))] = ft_set(m.group(2))
    ft_all, _ = L.requirement_ids()
    r1 = set()
    for t in L.tables(L.read(L.REQ)):
        for r in t['rows']:
            if r and re.match(r'FT-\d+\.\d+$', r[0]) and 'R1' in r[-1]:
                r1.add(r[0])
    union_plan = set().union(*plan_ft.values()) if plan_ft else set()
    if sorted(plan_ft) != [1, 2, 3]:
        rep.err('00-project-plan.md', 'в плане Ф4 не найдены срезы 1 – 3')
    if union_plan != r1:
        rep.err('00-project-plan.md', 'срезы Ф4 и FT релиза R1 расходятся: в срезах нет %s, лишние %s'
                % (sorted(r1 - union_plan, key=lambda x: [int(p) for p in x[3:].split('.')]),
                   sorted(union_plan - r1)))
    for k, v in plan_ft.items():
        for i in v:
            if i not in ft_all:
                rep.err('00-project-plan.md', 'срез %d: %s нет в требованиях' % (k, i))
    slice_union_tc = []
    exit_ft = {}
    if not exit_tb:
        rep.err(where, 'нет таблицы критериев выхода')
    else:
        labels = [r[0] for r in exit_tb['rows']]
        if labels != ['Ф3', 'Ф4, срез 1', 'Ф4, срез 2', 'Ф4, срез 3', 'Ф4, сервер']:
            rep.err(where, 'строки критериев выхода: %s' % labels)
        for r in exit_tb['rows']:
            lab = r[0]
            if nft_set(r[2]) != nfr_phase.get(lab, set()):
                rep.err(where, '%s: NFT критериев %s, в сводке методики %s'
                        % (lab, sorted(nft_set(r[2])), sorted(nfr_phase.get(lab, set()))))
            ms = re.search(r'срез (\d)', lab)
            if ms:
                k = int(ms.group(1))
                got = ft_set(r[1])
                exit_ft[k] = got
                if got != plan_ft.get(k, set()):
                    rep.err(where, '%s: FT %s, в плане %s' % (lab, sorted(got ^ plan_ft.get(k, set())), 'срез ' + str(k)))
                firsts = {s for s, v in st_first.items() if v == lab}
                got_st = set(re.findall(r'ST-\d{2}', r[3]))
                if got_st != firsts:
                    rep.err(where, '%s: ST критериев %s, первый запуск в разделе 9 %s' % (lab, sorted(got_st), sorted(firsts)))
                tcs = re.findall(r'TC-\d{3}', r[4])
                slice_union_tc += tcs
                for t in tcs:
                    if t not in e2e_ids:
                        rep.err(where, '%s: %s не сквозной сценарий раздела 8' % (lab, t))
            elif lab == 'Ф3':
                firsts = {s for s, v in st_first.items() if v == 'Ф3'}
                got_st = set(re.findall(r'ST-\d{2}', r[3]))
                if got_st != firsts:
                    rep.err(where, 'Ф3: ST критериев %s, первый запуск в разделе 9 %s' % (sorted(got_st), sorted(firsts)))
            else:
                for st in re.findall(r'ST-\d{2}', r[3]):
                    if st not in st_first:
                        rep.err(where, '%s: теста %s нет в разделе 9' % (lab, st))
                if len(e2e_ids) == 16 and 'шестнадцать' not in r[4].lower():
                    rep.err(where, 'строка «сервер»: все сценарии раздела 8 должны быть названы словом')
        if sorted(slice_union_tc) != sorted(e2e_ids):
            rep.err(where, 'сквозные сценарии по срезам не дают все сценарии раздела 8 по одному разу: нет %s, повторы %s'
                    % (sorted(set(e2e_ids) - set(slice_union_tc)),
                       sorted({x for x in slice_union_tc if slice_union_tc.count(x) > 1})))
        if set().union(*exit_ft.values()) != r1:
            rep.err(where, 'FT критериев выхода срезов не равны FT релиза R1')
    # общие условия выхода идут подряд
    cond = re.findall(r'^(\d+)\. \*\*', s14, re.M)
    if [int(x) for x in cond] != list(range(1, len(cond) + 1)) or len(cond) < 8:
        rep.err(where, 'общие условия выхода должны идти подряд, найдено %s' % cond)

    # ------------------------------------------------ 10. постоянные
    def num(rx, text, what):
        m = re.search(rx, text)
        if not m:
            rep.err(where, 'не найдено: %s' % what)
            return None
        return m.group(1)

    a = num(r'например, (\d+) повторов', s7, 'число повторов в разделе 7')
    b = num(r'серию из (\d+) повторов', s14, 'число повторов в разделе 14')
    if a and b and a != b:
        rep.err(where, 'серия повторов: раздел 7 %s, раздел 14 %s' % (a, b))
    a = num(r'не больше чем на (\d+) суток', s14, 'карантин в разделе 14')
    b = num(r'до (\d+) суток', s16, 'карантин в разделе 16')
    if a and b and a != b:
        rep.err(where, 'карантин: раздел 14 %s, раздел 16 %s' % (a, b))
    a = re.findall(r'не ниже (\d+)%', s14)
    b = re.search(r'порогами (\d+)% и (\d+)%', s16)
    if not b or a[:2] != list(b.groups()):
        rep.err(where, 'покрытие: раздел 14 %s, раздел 16 %s' % (a, b.groups() if b else None))
    l1 = ' '.join(levels.get('L1', []))
    l3 = ' '.join(levels.get('L3', []))
    p3 = re.search(r'Около (\d+)%', l3)
    p3b = re.search(r'доля L3 около (\d+)%', s16)
    if p3 and p3b and p3.group(1) != p3b.group(1):
        rep.err(where, 'доля L3: таблица уровней %s%%, раздел 16 %s%%' % (p3.group(1), p3b.group(1)))
    p1 = re.search(r'Около (\d+)%', l1)
    if p1 and p3 and int(p1.group(1)) + int(p3.group(1)) > 100:
        rep.err(where, 'доли L1 и L3 в сумме больше 100%')

    # ускоренные сроки равны таблице time-budgets.md, раздел 5
    tb5 = L.table_after(L.read(os.path.join(L.ARCH, 'time-budgets.md')), r'^## 5\. Ускоренные сроки для тестов')
    fast = {r[0]: r[2] for r in tb5['rows']}

    def nums(s):
        return [int(x) for x in re.findall(r'\d+', s)]

    p11 = L.section(T, r'^11\. ')
    m = re.search(r'резерв (\d+) с, платёжная сессия (\d+) с, паузы повторов выдачи ([\d, ]+) с, контроль доставки (\d+) мин\w*, автовозврат ([\d, ]+) с', p11 or '')
    if not m:
        rep.err(where, 'в разделе 11 не найдена строка ускоренных сроков профиля fast-time')
    else:
        pairs = [('Резерв', [int(m.group(1))], 'с'), ('Платёжная сессия', [int(m.group(2))], 'с'),
                 ('Паузы повторов выдачи', nums(m.group(3)), 'с'), ('Контроль доставки', [int(m.group(4))], 'мин'),
                 ('Автовозврат', nums(m.group(5)), 'с')]
        for name, val, unit in pairs:
            ref = fast.get(name, '')
            if nums(ref) != val or (unit in ('с', 'мин') and not re.search(r'\b%s\b' % unit, ref.replace(' ', ' '))):
                rep.err(where, 'ускоренный срок «%s»: в стратегии %s %s, в time-budgets.md «%s»' % (name, val, unit, ref))

    # память окружений равна memory-budget.md
    mem = L.read(os.path.join(L.OPS_DIR, 'memory-budget.md'))
    s12_tb = L.find_table(s12, 'Окружение', 'Память')
    if s12_tb:
        for r in s12_tb['rows']:
            for n in re.findall(r'\d{4}', r[3]):
                if n not in mem:
                    rep.err(where, 'окружение %s: значение %s МБ не найдено в memory-budget.md' % (r[0], n))
    for pair in re.findall(r'`([\w-]+)` (\d{4}) МБ', s12):
        if not re.search(r'`?%s`?[^\n]*\b%s\b' % (re.escape(pair[0]), pair[1]), mem):
            rep.err(where, 'набор %s: %s МБ не совпадает с memory-budget.md' % pair)
    # объёмы нагрузки из методики NFR
    if '200 заказов' in s12 and '200 заказов' not in nfr_text:
        rep.err(where, 'раздел 12: «200 заказов» отсутствует в методике NFR')
    # шаблон тест-кейса содержит поля, названные в разделе 13
    tmpl = L.read(os.path.join(TEST, '_template-test-case.md'))
    for fld in ('User story', 'Требования', 'Уровень', 'Тип', 'Приоритет', 'Автоматизация', 'Предусловия', 'Шаги', 'Ожидаемый результат'):
        if fld not in tmpl:
            rep.err('_template-test-case.md', 'в шаблоне нет поля «%s»' % fld)

    rep.fact('Стратегия: уровней %d, NFT %d, инвариантов %d, TC конкуренции %d, сквозных %d, ST %d, областей TC %d, '
             'операций OpenAPI %d, событий AsyncAPI %d, ссылок TC в документах %d'
             % (len(levels), len(ids5), len(inv_types), len(conc_ids), len(e2e_ids), len(st_first), len(areas), ops, events, n_tc))
    rep.fact('Сверка: срезов методики %d, FT плана %d (R1: %d), тестов db/test_db.py %d, исключений заглушек %d'
             % (n_cross, len(union_plan), len(r1), n_db_tests, n_stub))
    return rep.finish()


if __name__ == '__main__':
    sys.exit(main())
