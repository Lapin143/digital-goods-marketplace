# -*- coding: utf-8 -*-
"""Общие функции проверок: разбор таблиц Markdown, блоков Mermaid, разделов и источников истины.

Подключается скриптами проверок архитектуры (check_adr, check_budgets, check_decomposition,
check_security, check_seq, check_components, check_refs).
"""
import glob
import os
import re
import sys

REPO = os.environ.get('REPO') or os.path.abspath(
    os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
DOCS = os.path.join(REPO, 'docs')
ARCH = os.path.join(DOCS, '05-architecture')
OPS_DIR = os.path.join(DOCS, '09-operations')
REQ = os.path.join(DOCS, '02-requirements', 'requirements_v1.5.md')


def read(path):
    with open(path, encoding='utf-8') as f:
        return f.read()


def rel(path):
    return os.path.relpath(path, REPO)


class Report:
    """Накопитель замечаний с печатью итога и кодом выхода."""

    def __init__(self, title):
        self.title = title
        self.problems = []
        self.facts = []

    def err(self, where, msg):
        self.problems.append('%s: %s' % (where, msg))

    def fact(self, msg):
        self.facts.append(msg)

    def finish(self):
        for f in self.facts:
            print(f)
        for p in self.problems:
            print('  -', p)
        print('проблем:', len(self.problems))
        return 1 if self.problems else 0


def split_cells(line):
    line = line.strip()
    parts = re.split(r'(?<!\\)\|', line)
    return [c.strip() for c in parts[1:-1]]


def tables(text):
    """Все таблицы вне блоков кода: список словарей {header, rows, line}. Разделитель строк не включается."""
    lines = text.split('\n')
    out = []
    in_code = False
    i = 0
    while i < len(lines):
        ln = lines[i]
        if ln.strip().startswith('```'):
            in_code = not in_code
            i += 1
            continue
        if not in_code and ln.startswith('|') and i + 1 < len(lines) and re.match(r'^\|[\s:|-]+\|\s*$', lines[i + 1]):
            header = split_cells(ln)
            rows = []
            j = i + 2
            while j < len(lines) and lines[j].startswith('|'):
                rows.append(split_cells(lines[j]))
                j += 1
            out.append(dict(header=header, rows=rows, line=i + 1))
            i = j
            continue
        i += 1
    return out


def find_table(text, first_col, contains=None):
    """Первая таблица, у которой первый столбец заголовка равен first_col (и в заголовке есть contains)."""
    for t in tables(text):
        if t['header'] and t['header'][0] == first_col and (contains is None or contains in t['header']):
            return t
    return None


def table_after(text, heading_re):
    """Первая таблица после заголовка, подходящего под регулярное выражение."""
    m = re.search(heading_re, text, re.M)
    if not m:
        return None
    sub = text[m.end():]
    ts = tables(sub)
    return ts[0] if ts else None


def sections(text):
    """Разделы второго и третьего уровней: список (заголовок, текст до следующего заголовка того же или более высокого уровня)."""
    res = []
    for m in re.finditer(r'^(#{2,3}) (.+)$', text, re.M):
        res.append((m.start(), m.end(), len(m.group(1)), m.group(2)))
    out = []
    for k, (s, e, lvl, title) in enumerate(res):
        end = len(text)
        for s2, e2, lvl2, t2 in res[k + 1:]:
            if lvl2 <= lvl:
                end = s2
                break
        out.append((title, text[e:end]))
    return out


def section(text, title_re):
    for title, body in sections(text):
        if re.search(title_re, title):
            return body
    return None


def mermaid_blocks(text):
    return re.findall(r'```mermaid\n(.*?)```', text, re.S)


def backticked(cell):
    return re.findall(r'`([^`]+)`', cell)


# ---------------------------------------------------------------- разбор flowchart Mermaid
NODE_RE = re.compile(r'^\s*([A-Za-z][\w-]*)\s*(\[\[|\[\(|\(\[|\(\(|\[|\(|\{)')
SUB_RE = re.compile(r'^\s*subgraph\s+([A-Za-z][\w-]*)')
EDGE_RE = re.compile(r'^\s*([A-Za-z][\w-]*)\s*(?:-->|-\.->|==>|--[^>\n]*-->|-\.[^>\n]*\.->|<-->|---|-\.-|--\)|--x)\s*(?:\|[^|\n]*\|\s*)?([A-Za-z][\w-]*)')


def flowchart(block):
    """Узлы, подграфы и связи flowchart. Возвращает dict(nodes, subgraphs, edges, parent)."""
    nodes, subs, edges = [], [], []
    parent = {}
    stack = []
    for raw in block.split('\n'):
        ln = raw.rstrip()
        if ln.strip().startswith('%%') or not ln.strip():
            continue
        ms = SUB_RE.match(ln)
        if ms:
            subs.append(ms.group(1))
            if stack:
                parent[ms.group(1)] = stack[-1]
            stack.append(ms.group(1))
            continue
        if re.match(r'^\s*end\s*$', ln):
            if stack:
                stack.pop()
            continue
        if re.match(r'^\s*(classDef|class|style|linkStyle|flowchart|graph|direction)\b', ln):
            continue
        me = EDGE_RE.match(ln)
        mn = NODE_RE.match(ln)
        if mn and not (me and not re.match(r'^\s*[A-Za-z][\w-]*\s*(\[\[|\[\(|\(\[|\(\(|\[|\(|\{).*(-->|-\.->)', ln)):
            nodes.append(mn.group(1))
            if stack:
                parent[mn.group(1)] = stack[-1]
        # узел может быть объявлен и на конце связи, например  a --> b["..."]
        for mm in re.finditer(r'(?:-->|-\.->|==>)\s*(?:\|[^|\n]*\|\s*)?([A-Za-z][\w-]*)\s*(\[\[|\[\(|\(\[|\(\(|\[|\(|\{)', ln):
            if mm.group(1) not in nodes:
                nodes.append(mm.group(1))
        if me:
            edges.append((me.group(1), me.group(2)))
    return dict(nodes=list(dict.fromkeys(nodes)), subgraphs=subs, edges=edges, parent=parent)


# ---------------------------------------------------------------- общие справочники
def containers():
    """Алиасы контейнеров из таблицы раздела 1 c4-containers.md."""
    t = read(os.path.join(ARCH, 'c4-containers.md'))
    tb = table_after(t, r'^## 1\. Контейнеры релиза R1')
    return [backticked(r[1])[0] for r in tb['rows']]


def sm_transitions():
    """Переходы статусных моделей: {'SM-01': {'T1', ...}}."""
    res = {}
    for f in glob.glob(os.path.join(DOCS, '03-processes', 'SM-*.md')):
        t = read(f)
        n = re.search(r'^# (SM-\d+)\.', t, re.M).group(1)
        res[n] = set(re.findall(r'^\| (T\d+) \|', t, re.M))
    return res


def bpmn_exceptions():
    res = {}
    for f in glob.glob(os.path.join(DOCS, '03-processes', 'BPMN-0*.md')):
        t = read(f)
        n = re.search(r'^# (BPMN-\d+)\.', t, re.M).group(1)
        res[n] = set(re.findall(r'^\| (E\d+) \|', t, re.M))
    return res


def requirement_ids():
    t = read(REQ)
    return (set(re.findall(r'\|\s*(FT-\d+\.\d+)\s*\|', t)), set(re.findall(r'\|\s*(NFT-\d+\.\d+)\s*\|', t)))


def component_aliases():
    """Все алиасы компонентов из c4-components.md (каркас) и c4-components-<сервис>.md."""
    res = set()
    files = [os.path.join(ARCH, 'c4-components.md')] + sorted(glob.glob(os.path.join(ARCH, 'c4-components-*.md')))
    for f in files:
        for t in tables(read(f)):
            if 'Алиас' in t['header'] and t['header'][0] == 'Компонент':
                ai = t['header'].index('Алиас')
                for r in t['rows']:
                    b = backticked(r[ai])
                    if b:
                        res.add(b[0])
    return res
