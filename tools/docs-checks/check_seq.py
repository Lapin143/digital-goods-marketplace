# -*- coding: utf-8 -*-
"""Проверка sequence-диаграмм SEQ-01 … SEQ-06 (docs/05-architecture/sequence-*.md).

  1. Одна и та же директива оформления (%%{init ...}%%) во всех sequence-диаграммах, по одной на диаграмму.
  2. Участники диаграммы объявлены в разделе 2 файла; участники раздела 2 это контейнеры, внешние системы или люди;
     у каждого объявленного участника есть сообщения, у каждого сообщения объявлены оба конца.
  3. Нумерация шагов: у каждого сообщения и заметки номер вида N или Na, номера не повторяются, основа не убывает;
     множество номеров диаграммы равно множеству номеров таблицы «Шаги диаграммы N.M».
  4. Компоненты таблицы шагов существуют в c4-components-*.md.
  5. Переходы SM (SM-nn/Tn) и исключения BPMN (En) существуют в матрицах Ф1.
  6. Ссылки «NN.M, шаг(и) …» в таблицах покрытия ведут на существующие шаги существующих диаграмм.
  7. Покрытие: SEQ-01 покрывает E1 – E12 и T1 – T12 процесса покупки, остальные SEQ закрывают свои исключения и переходы.
  8. Таблицы «Времена и повторы»: значения, совпадающие по названию со сводной таблицей time-budgets.md, равны ей.
"""
import glob
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import docslib as L

EXTERNALS = {'payment-gateway', 'email-provider', 'sms-provider', 'vk-id'}
EXTENSIONS = {'sms-otp'}   # расширение Keycloak, отдельного компонента в c4-components нет
PEOPLE = {'buyer', 'seller', 'moderator', 'operator', 'admin', 'support-operator', 'user', 'browser'}
STEP_RE = re.compile(r'^(\d+)([a-z]?)\.\s')
# что обязано быть покрыто в таблицах покрытия каждого SEQ: {BPMN: {Ex}}, {SM: {Tn}}
EXPECT = {
    'SEQ-01': dict(E={'BPMN-01': {'E%d' % i for i in range(1, 13)}}, T={'SM-01': {'T%d' % i for i in range(1, 13)}}),
    'SEQ-02': dict(E={'BPMN-01': {'E5', 'E6', 'E7', 'E8'}}, T={'SM-01': {'T7', 'T8'}, 'SM-02': {'T2', 'T4'}}),
    'SEQ-03': dict(E={'BPMN-01': {'E9', 'E10', 'E11'}}, T={'SM-06': {'T3', 'T4', 'T5', 'T6'}, 'SM-07': {'T1', 'T4'}}),
    'SEQ-04': dict(E={'BPMN-04': {'E%d' % i for i in range(1, 8)}}, T={'SM-07': {'T1', 'T2', 'T3', 'T4'}}),
    'SEQ-05': dict(E={'BPMN-01': {'E1'}, 'BPMN-04': {'E2'}}, T={}),
    'SEQ-06': dict(E={'BPMN-02': {'E%d' % i for i in range(1, 6)}}, T={'SM-05': {'T%d' % i for i in range(1, 10)}}),
}
DEFAULT_BPMN = {'SEQ-01': 'BPMN-01', 'SEQ-02': 'BPMN-01', 'SEQ-03': 'BPMN-01', 'SEQ-04': 'BPMN-04',
                'SEQ-05': None, 'SEQ-06': 'BPMN-02'}


def numbers(text):
    """Числа с единицами измерения: «1 ч», «15 мин», «17 мин 40 с» приводятся к секундам; остальные числа как есть."""
    t = text.lower().replace(',', ' ,')
    t = re.sub(r'\b(на |дольше )час(а)?\b', r'\g<1>1 ч', t)
    t = re.sub(r'процент\w*', '%', t)
    res = set()
    for m in re.finditer(r'(\d+)\s*(минут\w*|мин\b|секунд\w*|с\b|часа?\b|ч\b|мс\b|%|суток|сут\w*|дн\w*)?', t):
        n = int(m.group(1))
        u = m.group(2) or ''
        if u.startswith('мин'):
            res.add(('сек', n * 60))
        elif u.startswith('сек') or u == 'с':
            res.add(('сек', n))
        elif u.startswith('час') or u == 'ч':
            res.add(('сек', n * 3600))
        else:
            res.add((u[:2], n))
    return res


def step_key(sid):
    m = re.match(r'(\d+)([a-z]?)', sid)
    return int(m.group(1)), m.group(2)


def parse_sequence(block):
    """Участники, сообщения и заметки с номерами шагов."""
    participants = []
    used = set()
    steps = []
    unnumbered = []
    status_notes = []
    init = [ln.strip() for ln in block.split('\n') if ln.strip().startswith('%%{init')]
    for raw in block.split('\n'):
        ln = raw.strip()
        m = re.match(r'^(?:participant|actor)\s+([\w-]+)\s+as\s+', ln)
        if m:
            participants.append(m.group(1))
            continue
        m = re.match(r'^([\w-]+)\s*(?:->>|-->>|--\)|-\)|--x|-x|->|-->)\s*([\w-]+)\s*:\s*(.*)$', ln)
        if m:
            used.add(m.group(1))
            used.add(m.group(2))
            sm = STEP_RE.match(m.group(3) + ' ')
            if sm:
                steps.append(sm.group(1) + sm.group(2))
            else:
                unnumbered.append(ln[:70])
            continue
        m = re.match(r'^Note (?:over|right of|left of)\s+([\w-]+(?:\s*,\s*[\w-]+)*)\s*:\s*(.*)$', ln)
        if m:
            for p in re.split(r'\s*,\s*', m.group(1)):
                used.add(p)
            sm = STEP_RE.match(m.group(2) + ' ')
            if sm:
                steps.append(sm.group(1) + sm.group(2))
            else:
                status_notes.append(ln[:70])   # пометка статуса без номера допускается (c4-notation.md, 8.3)
    return dict(status_notes=status_notes, participants=participants, used=used, steps=steps, unnumbered=unnumbered, init=init)


def expand_tokens(cell, order):
    """«1 – 2», «3a – 3b», «7 и 8», «2a – 9a»: номера шагов. order нужен для диапазонов вида 2a – 9a."""
    cell = re.sub(r'\([^)]*\)', '', cell)
    out = []
    for part in re.split(r',|\sи\s', cell):
        part = part.strip()
        if not part:
            continue
        m = re.match(r'^(\d+[a-z]?)\s*[–-]\s*(\d+[a-z]?)$', part)
        if m:
            a, b = m.groups()
            ka, kb = step_key(a), step_key(b)
            if a.isdigit() and b.isdigit():
                out += [str(i) for i in range(int(a), int(b) + 1)]
            elif ka[0] == kb[0] and ka[1] and kb[1]:
                out += ['%d%s' % (ka[0], chr(c)) for c in range(ord(ka[1]), ord(kb[1]) + 1)]
            elif a in order and b in order:
                out += order[order.index(a):order.index(b) + 1]
            else:
                out += [a, b]
        elif re.match(r'^\d+[a-z]?$', part):
            out.append(part)
    return out


def main():
    rep = L.Report('seq')
    containers = set(L.containers())
    comps = L.component_aliases()
    sm = L.sm_transitions()
    bp = L.bpmn_exceptions()
    # сводка сроков для сверки значений
    tb = L.read(os.path.join(L.ARCH, 'time-budgets.md'))
    budget = {}
    for t in L.tables(tb[:tb.index('## 3. Разбивка')]):
        if len(t['header']) >= 2 and t['header'][0] in ('Срок', 'Вызов'):
            for r in t['rows']:
                budget[r[0].strip()] = r[1].strip()
    docs = {}
    diagrams = {}          # 'NN.M' -> упорядоченные номера шагов
    inits = {}
    n_steps = 0
    n_budget = 0
    n_diagrams = 0
    for f in sorted(glob.glob(os.path.join(L.ARCH, 'sequence-*.md'))):
        fn = os.path.basename(f)
        text = L.read(f)
        sid = re.search(r'^# (SEQ-\d+)\.', text, re.M).group(1)
        docs[sid] = dict(file=fn, text=text)
        # участники
        sec2 = L.section(text, r'^2\. Участники')
        part_tb = L.tables(sec2)[0]
        declared = [a for r in part_tb['rows'] for a in L.backticked(r[0])]
        if len(set(declared)) != len(declared):
            rep.err(fn, 'раздел 2: повторяются участники')
        for a in declared:
            if a not in containers and a not in EXTERNALS and a not in PEOPLE:
                rep.err(fn, 'раздел 2: участник %s не контейнер, не внешняя система и не человек' % a)
        # диаграммы по разделам
        for m in re.finditer(r'^## \d+\. Диаграмма (\d\d\.\d)\. (.+)$', text, re.M):
            did = m.group(1)
            start = m.end()
            nxt = re.search(r'^## ', text[start:], re.M)
            body = text[start:start + nxt.start()] if nxt else text[start:]
            blocks = L.mermaid_blocks(body)
            if not blocks:
                rep.err(fn, 'диаграмма %s: нет блока mermaid' % did)
                continue
            n_diagrams += 1
            block = blocks[0]
            if block.lstrip().split('\n')[1].strip().startswith('sequenceDiagram') is False and 'sequenceDiagram' not in block:
                continue  # C4-динамика (flowchart) разбирается отдельно
            if 'sequenceDiagram' not in block:
                continue
            seq = parse_sequence(block)
            inits[(sid, did)] = seq['init']
            if len(seq['init']) != 1:
                rep.err(fn, 'диаграмма %s: директив init %d, нужна одна' % (did, len(seq['init'])))
            for p in seq['participants']:
                if p not in declared:
                    rep.err(fn, 'диаграмма %s: участник %s не объявлен в разделе 2' % (did, p))
                if p not in seq['used']:
                    rep.err(fn, 'диаграмма %s: у участника %s нет ни одного сообщения' % (did, p))
            for p in seq['used']:
                if p not in seq['participants']:
                    rep.err(fn, 'диаграмма %s: участник %s не объявлен на диаграмме' % (did, p))
            for u in seq['unnumbered']:
                rep.err(fn, 'диаграмма %s: сообщение без номера шага: %s' % (did, u))
            steps = seq['steps']
            if len(set(steps)) != len(steps):
                dup = sorted({s for s in steps if steps.count(s) > 1})
                rep.err(fn, 'диаграмма %s: повторяются номера шагов %s' % (did, dup))
            plain = [int(s_) for s_ in steps if s_.isdigit()]
            if plain != sorted(plain):
                rep.err(fn, 'диаграмма %s: основные номера шагов убывают: %s' % (did, plain))
            by_base = {}
            for s_ in steps:
                n, letter = step_key(s_)
                if letter:
                    by_base.setdefault(n, []).append(letter)
            for n, letters in by_base.items():
                if letters != [chr(ord('a') + i) for i in range(len(letters))]:
                    rep.err(fn, 'диаграмма %s: буквы шага %d идут не a, b, c подряд: %s' % (did, n, letters))
            # пропуски в основной нумерации допустимы только там, где идёт ветвь с буквенными номерами (alt, else, break)
            top = max(plain + list(by_base)) if (plain or by_base) else 0
            gaps = [n for n in range(1, top + 1) if n not in plain and n not in by_base]
            if gaps:
                rep.err(fn, 'диаграмма %s: нет шагов %s ни в основной, ни в буквенной нумерации' % (
                    did, ', '.join(map(str, gaps[:8])) + (' …' if len(gaps) > 8 else '')))
            diagrams[did] = steps
            n_steps += len(steps)
            # таблица шагов
            m2 = re.search(r'^### \d+\.\d+\. Шаги диаграммы %s\n' % re.escape(did), text, re.M)
            if not m2:
                rep.err(fn, 'диаграмма %s: нет таблицы «Шаги диаграммы»' % did)
                continue
            tt = L.tables(text[m2.end():])[0]
            if tt['header'][:2] != ['№', 'Что происходит'] or 'Компонент' not in tt['header']:
                rep.err(fn, 'диаграмма %s: заголовок таблицы шагов %s' % (did, tt['header']))
                continue
            ci = tt['header'].index('Компонент')
            si = tt['header'].index('Переход SM')
            table_steps = []
            for r in tt['rows']:
                table_steps += expand_tokens(r[0], steps)
                for c in L.backticked(r[ci]):
                    if c not in comps and c not in containers and c not in EXTERNALS and c not in PEOPLE \
                            and '.' not in c and c not in EXTENSIONS:
                        rep.err(fn, 'диаграмма %s, шаг %s: компонент %s не найден в c4-components' % (did, r[0], c))
                for mm in re.finditer(r'SM-(\d+)/T(\d+)', r[si]):
                    key = 'SM-%s' % mm.group(1)
                    if key not in sm or 'T%s' % mm.group(2) not in sm[key]:
                        rep.err(fn, 'диаграмма %s, шаг %s: перехода %s нет в матрице' % (did, r[0], mm.group(0)))
            if sorted(table_steps, key=step_key) != sorted(steps, key=step_key):
                only_d = sorted(set(steps) - set(table_steps), key=step_key)
                only_t = sorted(set(table_steps) - set(steps), key=step_key)
                if only_d:
                    rep.err(fn, 'диаграмма %s: шаги есть на диаграмме, но не в таблице: %s' % (did, only_d))
                if only_t:
                    rep.err(fn, 'диаграмма %s: шаги есть в таблице, но не на диаграмме: %s' % (did, only_t))
                if len(table_steps) != len(set(table_steps)):
                    rep.err(fn, 'диаграмма %s: в таблице шагов номера повторяются' % did)
            # времена и повторы
            m3 = re.search(r'^### \d+\.\d+\. Времена и повторы диаграммы %s\n' % re.escape(did), text, re.M)
            if m3:
                pt = L.tables(text[m3.end():])[0]
                for r in pt['rows']:
                    key = r[0].strip()
                    if key in budget:
                        n_budget += 1
                        na, nb = numbers(r[1]), numbers(budget[key])
                        if not nb <= na:
                            rep.err(fn, 'диаграмма %s: «%s» = «%s», в time-budgets.md «%s» (нет чисел %s)'
                                    % (did, key, r[1], budget[key], sorted(nb - na)))
    # --- единая директива
    uniq = {i[0] for i in inits.values() if i}
    if len(uniq) > 1:
        rep.err('sequence-*.md', 'в sequence-диаграммах %d разных директив init, нужна одна' % len(uniq))
    # --- ссылки на шаги в таблицах покрытия и обзорной таблице SEQ-01
    ref_count = 0
    for sid, d in docs.items():
        fn, text = d['file'], d['text']
        cover_tables = []
        for t in L.tables(text):
            h = t['header']
            if h[:2] in (['Что', 'Где нарисовано'], ['Исключение', 'Где нарисовано'], ['Переход SM-01', 'Где нарисован']):
                cover_tables.append((t, 1))
            elif h[:3] == ['№', 'Участники по порядку стрелок', 'Что передаётся'] or (len(h) == 4 and h[3] == 'Где подробно'):
                cover_tables.append((t, 3))
        for t, col in cover_tables:
            for r in t['rows']:
                cell = re.sub(r'\[[^\]]*\]\([^)]*\)', ' ', r[col])
                current = None
                for tok in re.finditer(r'(\d\d\.\d)|шаг(?:и|ов)?\s+([^;]*?)(?=,\s*\d\d\.\d|\s+и\s+\d\d\.\d|$)', cell):
                    if tok.group(1):
                        current = tok.group(1)
                        if current not in diagrams and current != '01.0':
                            rep.err(fn, 'покрытие: диаграммы %s нет ни в одном SEQ' % current)
                            current = None
                    elif current and tok.group(2):
                        order = diagrams.get(current, [])
                        for s in expand_tokens(tok.group(2), order):
                            ref_count += 1
                            if order and s not in order:
                                rep.err(fn, 'покрытие: шага %s нет на диаграмме %s (строка «%s»)' % (s, current, r[0][:30]))
        # исключения и переходы
        want = EXPECT[sid]
        got_e = {}
        got_t = {}
        for t in L.tables(text):
            h = t['header']
            if h[:2] not in (['Что', 'Где нарисовано'], ['Исключение', 'Где нарисовано'], ['Переход SM-01', 'Где нарисован']):
                continue
            for r in t['rows']:
                name = r[0]
                cur_bpmn = None
                mb = re.search(r'BPMN-(\d+)', name)
                cur_bpmn = ('BPMN-%s' % mb.group(1)) if mb else DEFAULT_BPMN[sid]
                if h[0] == 'Переход SM-01':
                    for x in re.findall(r'T\d+', name):
                        got_t.setdefault('SM-01', set()).add(x)
                    continue
                for x in re.findall(r'\bE\d+\b', name):
                    got_e.setdefault(cur_bpmn, set()).add(x)
                    if cur_bpmn and (cur_bpmn not in bp or x not in bp[cur_bpmn]):
                        rep.err(fn, 'исключения %s %s нет в BPMN' % (x, cur_bpmn))
                for ms in re.finditer(r'(SM-\d+)/(T\d+(?:,\s*T\d+)*)', name):
                    for x in re.findall(r'T\d+', ms.group(2)):
                        got_t.setdefault(ms.group(1), set()).add(x)
                        if ms.group(1) not in sm or x not in sm[ms.group(1)]:
                            rep.err(fn, 'перехода %s/%s нет в матрице' % (ms.group(1), x))
        for b, es in want['E'].items():
            miss = es - got_e.get(b, set())
            if miss:
                rep.err(fn, 'не покрыты исключения %s: %s' % (b, sorted(miss, key=lambda s: int(s[1:]))))
        for s_, ts in want['T'].items():
            miss = ts - got_t.get(s_, set())
            if miss:
                rep.err(fn, 'не покрыты переходы %s: %s' % (s_, sorted(miss, key=lambda s: int(s[1:]))))
        d['got_e'], d['got_t'] = got_e, got_t
    # исключения BPMN-01 целиком и переходы SM-01 целиком
    all_e = set()
    for d in docs.values():
        all_e |= d['got_e'].get('BPMN-01', set())
    miss = set(bp['BPMN-01']) - all_e
    if miss:
        rep.err('SEQ', 'исключения BPMN-01 не покрыты ни одним SEQ: %s' % sorted(miss))
    # E6–E8 действительно закрыты в SEQ-02, E9–E11 в SEQ-03
    for sid_, es in (('SEQ-02', {'E6', 'E7', 'E8'}), ('SEQ-03', {'E9', 'E10', 'E11'})):
        if es - docs[sid_]['got_e'].get('BPMN-01', set()):
            rep.err(sid_, 'SEQ-01 отсылает сюда %s, но в таблице покрытия их нет' % sorted(es))
    # --- обзорная таблица «Диаграмма | Что показывает»
    for sid, d in docs.items():
        text = d['text']
        t = [x for x in L.tables(text) if x['header'][:2] == ['Диаграмма', 'Что показывает']][0]
        ids = [r[0] for r in t['rows']]
        in_doc = re.findall(r'^## \d+\. Диаграмма (\d\d\.\d)\.', text, re.M)
        if ids != in_doc:
            rep.err(d['file'], 'обзор диаграмм %s, разделы %s' % (ids, in_doc))
        for r in t['rows']:
            for ms in re.finditer(r'(SM-\d+)/((?:T\d+(?:,\s*)?)+)', r[2]):
                for x in re.findall(r'T\d+', ms.group(2)):
                    if ms.group(1) not in sm or x not in sm[ms.group(1)]:
                        rep.err(d['file'], 'обзор: перехода %s/%s нет в матрице' % (ms.group(1), x))
    rep.fact('Sequence: %d файлов, %d диаграмм, %d шагов, ссылок на шаги проверено %d, значений сроков сверено %d'
             % (len(docs), n_diagrams, n_steps, ref_count, n_budget))
    return rep.finish()


if __name__ == '__main__':
    sys.exit(main())
