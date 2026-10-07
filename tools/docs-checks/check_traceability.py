# -*- coding: utf-8 -*-
"""Матрица трассировки (docs/08-testing/traceability.md) против требований, историй и контрактов.

  1. Состав: 92 строки, по одной на каждое FT и NFT, разделы по релизам равны релизам требований.
  2. Заполнение: у R1 в первых пяти колонках нет пустых клеток (прочерк допустим только с причиной в скобках),
     колонки «Тест-кейс» и «Код» соответствуют версии матрицы (v2: пусты, v3: у R1 заполнен тест-кейс).
  3. Колонка «API / событие»: каждая операция есть в OpenAPI и связана со строкой через `x-stories` и колонку историй,
     каждое событие есть в каталоге событий, формат клетки, повторов в клетке нет.
  4. Полнота: каждая операция OpenAPI и каждое событие AsyncAPI встречаются в матрице хотя бы один раз.
  5. Прочерки: требования без операций равны объединению классов раздела 4, числа в тексте равны подсчёту.
  6. Числа в тексте: строки, операции, события, ширина связей у сквозных требований.
"""
import glob
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import docslib as L

PATH = os.path.join(L.DOCS, '08-testing', 'traceability.md')
OPS_RX = re.compile(r'Операции: (`[^`]+`(?:, `[^`]+`)*)\.')
EVT_RX = re.compile(r'События: (`[^`]+`(?:, `[^`]+`)*)\.')


def load_ops():
    ops = {}
    for f in sorted(glob.glob(os.path.join(L.DOCS, '06-api', 'openapi', '*-service.yaml'))):
        lines = L.read(f).split('\n')
        cur = None
        for i, ln in enumerate(lines):
            m = re.match(r'\s+operationId: (\w+)', ln)
            if m:
                cur = m.group(1)
                ops[cur] = []
            if cur and re.match(r'\s+x-stories:', ln):
                j = i + 1
                while j < len(lines) and re.match(r'\s+- US-', lines[j]):
                    ops[cur].append(lines[j].split('- ')[1].strip())
                    j += 1
    return ops


def load_events():
    text = L.read(os.path.join(L.DOCS, '06-api', 'events', 'README.md'))
    return set(re.findall(r'^\| \[`([^`]+)`\]', text, re.M))


def ids_in(cell):
    return re.findall(r'N?FT-\d+\.\d+', cell)


def main():
    rep = L.Report('traceability')
    where = 'traceability.md'
    T = L.read(PATH)
    ops = load_ops()
    events = load_events()
    ft_all, nft_all = L.requirement_ids()

    # релизы требований
    rel = {}
    for t in L.tables(L.read(L.REQ)):
        for r in t['rows']:
            if r and re.match(r'FT-\d+\.\d+$', r[0]):
                rel[r[0]] = r[-1]
    r1 = {k for k, v in rel.items() if 'R1' in v}
    r3 = {k for k, v in rel.items() if v.strip().startswith('R3')}
    r2 = set(rel) - r1 - r3

    # ------------------------------------------------ разбор матрицы по разделам
    ver = re.search(r'версия (\d)', T)
    version = int(ver.group(1)) if ver else 0
    sections = {}
    for key, rx in (('2.1', r'^### 2\.1\.'), ('2.2', r'^### 2\.2\.'), ('3.1', r'^### 3\.1\.'), ('3.2', r'^### 3\.2\.')):
        body = L.section(T, rx.replace('^### ', r'^'))
        sections[key] = body or ''
        if not body:
            rep.err(where, 'нет раздела %s' % key)
    rows = {}
    where_row = {}
    for key, body in sections.items():
        for t in L.tables(body):
            if t['header'][:2] == ['BG', 'Требование']:
                if t['header'] != ['BG', 'Требование', 'User story', 'Процесс / UC / SM', 'API / событие', 'Тест-кейс', 'Код']:
                    rep.err(where, 'раздел %s: заголовок таблицы %s' % (key, t['header']))
                for r in t['rows']:
                    m = re.match(r'(N?FT-\d+\.\d+) ', r[1])
                    if not m or len(r) != 7:
                        rep.err(where, 'раздел %s: строка «%s» не вида «ID название» или не 7 колонок' % (key, r[1][:30]))
                        continue
                    rid = m.group(1)
                    if rid in rows:
                        rep.err(where, '%s встречается дважды' % rid)
                    rows[rid] = r
                    where_row[rid] = key
    all_ids = ft_all | nft_all
    if set(rows) != all_ids:
        rep.err(where, 'строки и требования расходятся: нет %s, лишние %s' % (sorted(all_ids - set(rows)), sorted(set(rows) - all_ids)))
    # состав разделов
    exp = {'2.1': r1, '2.2': {k for k in nft_all if k != 'NFT-3.4'},
           '3.1': r2 | {'NFT-3.4'}, '3.2': r3}
    for key, s in exp.items():
        got = {k for k, v in where_row.items() if v == key}
        if got != s:
            rep.err(where, 'раздел %s: нет %s, лишние %s' % (key, sorted(s - got), sorted(got - s)))

    # ------------------------------------------------ заполнение и формат
    used_ops = {}
    used_events = {}
    no_ops = set()
    no_both = set()
    reach = {}
    for rid, r in rows.items():
        bg, req, us, proc, api, tc, code = r
        stories = set(re.findall(r'US-\d+\.\d+', us))
        sec = where_row.get(rid)
        if sec in ('2.1', '2.2'):
            for name, c in (('BG', bg), ('Требование', req), ('User story', us), ('Процесс / UC / SM', proc), ('API / событие', api)):
                if not c:
                    rep.err(where, '%s: пустая клетка «%s» у требования R1' % (rid, name))
        elif not api:
            rep.err(where, '%s: пустая клетка «API / событие»' % rid)
        if not stories:
            rep.err(where, '%s: нет ни одной истории' % rid)
        if api.startswith('–'):
            if not re.match(r'– \([^)]+\)$', api):
                rep.err(where, '%s: прочерк без причины в скобках: «%s»' % (rid, api))
            no_ops.add(rid)
            no_both.add(rid)
            continue
        mo = OPS_RX.search(api)
        me = EVT_RX.search(api)
        ro = re.findall(r'`([^`]+)`', mo.group(1)) if mo else []
        re_ = re.findall(r'`([^`]+)`', me.group(1)) if me else []
        if not mo and not me:
            rep.err(where, '%s: клетка «API / событие» не вида «Операции: …. События: ….» или «– (причина)»: «%s»' % (rid, api[:60]))
        tokens = re.findall(r'`([^`]+)`', api)
        if len(tokens) != len(ro) + len(re_):
            rep.err(where, '%s: в клетке есть названия вне перечней «Операции» и «События»' % rid)
        if len(set(ro)) != len(ro) or len(set(re_)) != len(re_):
            rep.err(where, '%s: название повторяется в клетке' % rid)
        for o in ro:
            if o not in ops:
                rep.err(where, '%s: операции %s нет в OpenAPI' % (rid, o))
                continue
            used_ops.setdefault(o, []).append(rid)
            if not set(ops[o]) & stories:
                rep.err(where, '%s: операция %s не связана со строкой через историю (её истории %s, истории строки %s)'
                        % (rid, o, ops[o], sorted(stories)))
        for e in re_:
            if e not in events:
                rep.err(where, '%s: события %s нет в каталоге' % (rid, e))
            else:
                used_events.setdefault(e, []).append(rid)
        if ro and 'Операций нет' in api:
            rep.err(where, '%s: есть операции и одновременно «Операций нет»' % rid)
        if not ro:
            no_ops.add(rid)
            if not re.search(r'Операций нет: [^.]+\.', api):
                rep.err(where, '%s: нет операций, а причины «Операций нет: …» нет' % rid)
        # колонки следующих фаз
        if version == 2:
            if tc or code:
                rep.err(where, '%s: в версии 2 колонки «Тест-кейс» и «Код» должны быть пусты' % rid)
        elif version >= 3 and sec in ('2.1', '2.2') and not tc:
            rep.err(where, '%s: в версии %d у R1 должен быть тест-кейс' % (rid, version))
    if version < 2:
        rep.err(where, 'версия матрицы %d, ожидается не ниже 2' % version)

    # ------------------------------------------------ полнота
    for o in sorted(set(ops) - set(used_ops)):
        rep.err(where, 'операция %s не встречается в матрице' % o)
    for e in sorted(events - set(used_events)):
        rep.err(where, 'событие %s не встречается в матрице' % e)

    # ------------------------------------------------ прочерки, раздел 4
    sec4 = L.section(T, r'^4\. Прочерки') or ''
    classes = None
    for t in L.tables(sec4):
        if t['header'] == ['Класс', 'Требования', 'Почему допустимо'] and any('Контракт следующего релиза' in r[0] for r in t['rows']):
            classes = t
    if not classes:
        rep.err(where, 'в разделе 4 нет таблицы классов прочерков колонки «API / событие»')
    else:
        listed = []
        for r in classes['rows']:
            listed += ids_in(r[1])
        if len(listed) != len(set(listed)):
            rep.err(where, 'раздел 4: требование названо в двух классах: %s' % sorted({x for x in listed if listed.count(x) > 1}))
        if set(listed) != no_ops:
            rep.err(where, 'раздел 4: классы и требования без операций расходятся: нет %s, лишние %s'
                    % (sorted(no_ops - set(listed)), sorted(set(listed) - no_ops)))
        # все строки R2 и R3 входят в класс следующего релиза, и только они
        nxt = set()
        for r in classes['rows']:
            if r[0].startswith('Контракт следующего релиза'):
                nxt = set(ids_in(r[1]))
        if nxt != (r2 | r3 | {'NFT-3.4'}):
            rep.err(where, 'раздел 4: класс «следующий релиз» не равен строкам R2 и R3: нет %s, лишние %s'
                    % (sorted((r2 | r3 | {'NFT-3.4'}) - nxt), sorted(nxt - (r2 | r3 | {'NFT-3.4'}))))
    m = re.search(r'У (\d+) требований из (\d+) нет ни одной операции OpenAPI\. У (\d+) из них нет и событий, в клетке стоит прочерк с причиной\. У остальных (\d+) нет операций, но есть события', sec4)
    if not m:
        rep.err(where, 'в разделе 4 нет фразы о числе требований без операций')
    else:
        want = (len(no_ops), len(rows), len(no_both), len(no_ops) - len(no_both))
        if tuple(int(x) for x in m.groups()) != want:
            rep.err(where, 'раздел 4: числа %s, подсчёт %s' % (m.groups(), want))

    # ------------------------------------------------ числа в тексте
    m = re.search(r'В матрице (\d+) строк[аи]?: по одной на каждое из (\d+) функциональных \(FT\) и (\d+) нефункциональных \(NFT\)', T)
    if not m or tuple(int(x) for x in m.groups()) != (len(rows), len(ft_all), len(nft_all)):
        rep.err(where, 'число строк в тексте %s, подсчёт %s' % (m.groups() if m else None, (len(rows), len(ft_all), len(nft_all))))
    m = re.search(r'Все (\d+) операций OpenAPI и все (\d+) событий AsyncAPI', T)
    if not m or (int(m.group(1)), int(m.group(2))) != (len(ops), len(events)):
        rep.err(where, 'в находках: операций и событий %s, подсчёт %s' % (m.groups() if m else None, (len(ops), len(events))))
    m = re.search(r'Каждая из (\d+) операций и каждое из (\d+) событий', T)
    if not m or (int(m.group(1)), int(m.group(2))) != (len(ops), len(events)):
        rep.err(where, 'в разделе 1: операций и событий %s, подсчёт %s' % (m.groups() if m else None, (len(ops), len(events))))
    # ширина связей через истории у сквозных требований
    for rid, word in (('FT-11.2', r'для FT-11\.2 (\d+) операц'), ('NFT-5.3', r'для NFT-5\.3 (\d+) операц')):
        stories = set(re.findall(r'US-\d+\.\d+', rows[rid][2])) if rid in rows else set()
        n = sum(1 for o, st in ops.items() if set(st) & stories)
        m = re.search(word, T)
        if not m or int(m.group(1)) != n:
            rep.err(where, '%s: ширина связей в тексте %s, подсчёт %d' % (rid, m.group(1) if m else None, n))
    # события R2 привязаны (18) и в строках R2 и R3 нет событий R1 без причины: только счёт
    m = re.search(r'\((\d+) черновиков событий R2 привязаны', T)
    ev_text = L.read(os.path.join(L.DOCS, '06-api', 'events', 'README.md'))
    r2_part = ev_text.split('### События R2')[1] if '### События R2' in ev_text else ''
    r2_events = set(re.findall(r'^\| \[`([^`]+)`\]', r2_part, re.M))
    if not m or int(m.group(1)) != len(r2_events & set(used_events)):
        rep.err(where, 'в находках событий R2 %s, подсчёт %d' % (m.group(1) if m else None, len(r2_events & set(used_events))))
    nxt_rows = r2 | r3 | {'NFT-3.4'}
    with_events = len([x for x in nxt_rows if x not in no_both])
    m = re.search(r'В (\d+) из (\d+) строк R2 и R3 заполнены события', T)
    if not m or (int(m.group(1)), int(m.group(2))) != (with_events, len(nxt_rows)):
        rep.err(where, 'в находках строк R2 и R3 с событиями: %s, подсчёт %s' % (m.groups() if m else None, (with_events, len(nxt_rows))))

    rep.fact('Трассировка: версия %d, строк %d (FT %d, NFT %d), операций в матрице %d из %d, событий %d из %d, без операций %d, без операций и событий %d'
             % (version, len(rows), len(ft_all), len(nft_all), len(used_ops), len(ops), len(used_events), len(events),
                len(no_ops), len(no_both)))
    return rep.finish()


if __name__ == '__main__':
    sys.exit(main())
