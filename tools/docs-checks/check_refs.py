# -*- coding: utf-8 -*-
"""Сквозные ссылки между документами (то, чего не проверяет validate_docs.py).

  1. Якоря: ссылка вида файл.md#раздел ведёт на существующий заголовок целевого файла (правила якорей GitHub).
  2. Идентификаторы в документах и контрактах: US-n.m, UC-nn, SM-nn, SM-nn/Tn, BPMN-nn, INV-nn, SEQ-nn, T-nn, ST-nn,
     A-nn, TB-n, F9-n существуют там, где они определены.
  3. Скрипты: упоминания check_*.py, validate_docs.py, run_all.sh ведут на файлы tools/docs-checks.
  4. Имена файлов документов в обратных кавычках (name.md) существуют в репозитории.
"""
import glob
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import docslib as L

TOOLS = os.path.join(L.REPO, 'tools', 'docs-checks')
SKIP_DIRS = ('node_modules', '.git')
# документы, которые появятся на следующих шагах плана; ссылки на них пока допустимы только в виде имени в кавычках
FUTURE_MD = {
    'requirements_v1.6.md',                          # решение D-12: версия 1.6 после замечаний Ф2
    'monitoring.md', 'runbook.md', 'deploy.md',      # Ф6, раздел 09-operations
}


def slug(title):
    t = re.sub(r'`', '', title).strip().lower()
    t = re.sub(r'\[([^\]]*)\]\([^)]*\)', r'\1', t)
    t = re.sub(r'[^\w\- ]', '', t, flags=re.U)
    return t.replace(' ', '-')


def headings(path, cache={}):
    if path not in cache:
        text = L.read(path)
        text = re.sub(r'```.*?```', '', text, flags=re.S)
        seen = {}
        res = set()
        for m in re.finditer(r'^#{1,6}\s+(.+?)\s*$', text, re.M):
            s = slug(m.group(1))
            n = seen.get(s, 0)
            res.add(s if n == 0 else '%s-%d' % (s, n))
            seen[s] = n + 1
        cache[path] = res
    return cache[path]


def all_files():
    out = []
    for root, dirs, files in os.walk(L.REPO):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS and d != '__pycache__']
        for f in files:
            if f.endswith(('.md', '.yaml', '.yml')):
                out.append(os.path.join(root, f))
    return out


def main():
    rep = L.Report('refs')
    files = all_files()
    md_names = {os.path.basename(f) for f in files if f.endswith('.md')}
    all_names = {os.path.basename(p) for p in glob.glob(os.path.join(L.REPO, '**', '*'), recursive=True)}

    # ------------------------------------------------ универсумы идентификаторов
    us = set()
    for f in glob.glob(os.path.join(L.DOCS, '02-requirements', 'US-*.md')):
        us |= set(re.findall(r'^## (US-\d+\.\d+)\.', L.read(f), re.M))
    uc = {re.match(r'(UC-\d+)', os.path.basename(f)).group(1)
          for f in glob.glob(os.path.join(L.DOCS, '02-requirements', 'UC-0*.md'))}
    sm = L.sm_transitions()
    bpmn = L.bpmn_exceptions()
    inv = set(re.findall(r'^\| (INV-\d+) \|', L.read(os.path.join(L.DOCS, '04-domain', 'domain-model.md')), re.M))
    seq = set()
    for f in glob.glob(os.path.join(L.ARCH, 'sequence-*.md')):
        seq.add(re.search(r'^# (SEQ-\d+)\.', L.read(f), re.M).group(1))
    tm = L.read(os.path.join(L.ARCH, 'threat-model.md'))
    threats = set(re.findall(r'^\| (T-\d{2}) \|', tm, re.M))
    tests = set(re.findall(r'^\| (ST-\d{2}) \|', tm, re.M))
    assets = set(re.findall(r'^\| (A-\d{2}) \|', tm, re.M))
    bounds = set(re.findall(r'^\| (TB-\d) \|', tm, re.M))
    finds9 = set(re.findall(r'^\| (F9-\d+) \|', tm, re.M))

    n_anchor = n_ids = n_scripts = n_names = 0
    for f in files:
        text = L.read(f)
        rel = L.rel(f)
        in_docs = f.startswith(L.DOCS) or os.path.basename(f) == 'README.md'
        # ---- якоря
        if f.endswith('.md'):
            for m in re.finditer(r'\]\(([^)#\s]+\.md)#([^)\s]+)\)', text):
                n_anchor += 1
                target = os.path.normpath(os.path.join(os.path.dirname(f), m.group(1)))
                if os.path.exists(target) and m.group(2) not in headings(target):
                    rep.err(rel, 'якорь #%s не найден в %s' % (m.group(2), m.group(1)))
        if f.endswith('.md'):
            own = headings(f)
            for m in re.finditer(r'\]\(#([^)\s]+)\)', text):
                n_anchor += 1
                if m.group(1) not in own:
                    rep.err(rel, 'якорь #%s не найден в этом файле' % m.group(1))
        if not in_docs:
            continue
        body = re.sub(r'```.*?```', '', text, flags=re.S) if f.endswith('.md') else text
        # ---- идентификаторы
        checks = [
            (r'\bUS-\d+\.\d+\b', us, 'история'),
            (r'\bUC-\d{2}\b', uc, 'use case'),
            (r'\bINV-\d+\b', inv, 'инвариант'),
            (r'\bSEQ-\d{2}\b', seq, 'sequence-сценарий'),
            (r'(?<![\w-])T-\d{2}\b', threats, 'угроза'),
            (r'\bST-\d{2}\b', tests, 'тест безопасности'),
            (r'(?<![\w-])A-\d{2}\b', assets, 'актив'),
            (r'\bTB-\d\b', bounds, 'граница доверия'),
        ]
        for rx, universe, what in checks:
            for m in set(re.findall(rx, body)):
                n_ids += 1
                if m not in universe:
                    # R2 и R3 вне набора: инварианты INV-nn заданы целиком, прочее обязано существовать
                    rep.err(rel, '%s %s не найден(а)' % (what, m))
        for m in set(re.findall(r'\bSM-(\d+)(?:/(T\d+))?', body)):
            n_ids += 1
            key = 'SM-%s' % m[0]
            if key not in sm:
                rep.err(rel, 'статусной модели %s нет' % key)
            elif m[1] and m[1] not in sm[key]:
                rep.err(rel, 'перехода %s/%s нет в матрице' % (key, m[1]))
        for m in set(re.findall(r'\bBPMN-(\d+)\b', body)):
            n_ids += 1
            if 'BPMN-%s' % m not in bpmn:
                rep.err(rel, 'процесса BPMN-%s нет' % m)
        for m in set(re.findall(r'\bF9-\d+\b', body)):
            n_ids += 1
            if m not in finds9:
                rep.err(rel, 'находка %s не найдена в threat-model.md' % m)
        # ---- скрипты
        for m in set(re.findall(r'\b(check_\w+\.py|validate_docs\.py|run_all\.sh|docslib\.py)\b', body)):
            n_scripts += 1
            if not glob.glob(os.path.join(TOOLS, '**', m), recursive=True):
                rep.err(rel, 'скрипт %s не найден в tools/docs-checks' % m)
        # ---- имена файлов в обратных кавычках
        if f.endswith('.md'):
            for m in set(re.findall(r'`([\w][\w.-]*\.md)`', body)):
                n_names += 1
                if 'NN' in m:
                    continue    # шаблон имени, например ADR-NNN-short-name.md
                if m.startswith('TC-'):
                    continue    # файлы тест-кейсов по областям появляются в Ф4 (имена заданы в test-strategy.md, раздел 13)
                if m not in md_names and m not in FUTURE_MD:
                    rep.err(rel, 'файл %s упомянут, но не найден в репозитории' % m)
    rep.fact('Ссылки: якорей %d, идентификаторов %d, упоминаний скриптов %d, имён файлов %d'
             % (n_anchor, n_ids, n_scripts, n_names))
    return rep.finish()


if __name__ == '__main__':
    sys.exit(main())
