# -*- coding: utf-8 -*-
"""Проверка документов Ф1: ID требований, ссылки, Mermaid, стиль.
Запуск: python3 validate_docs.py <файл или папка> [...]
"""
import os, re, subprocess, sys, tempfile, glob, time

REPO = os.environ.get('REPO') or os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
REQ = os.path.join(REPO, 'docs/02-requirements/requirements_v1.5.md')
HERE = os.path.dirname(os.path.abspath(__file__))
PCFG = os.environ.get('MMDC_PUPPETEER_CONFIG') or os.path.join(HERE, 'puppeteer-config.json')

req_text = open(REQ, encoding='utf-8').read()
FT = set(re.findall(r'\|\s*(FT-\d+\.\d+)\s*\|', req_text))
NFT = set(re.findall(r'\|\s*(NFT-\d+\.\d+)\s*\|', req_text))
BG = set(re.findall(r'\|\s*(BG-\d+)\s*\|', req_text))

# Известные исключения. Требования v1.4 и v1.5 выпущены раньше правила о тире и правке не подлежат.
# В гайде Ф1 блок Mermaid приведён как пример разметки, а не как диаграмма проекта.
DASH_OK = {'requirements_v1.4.md', 'requirements_v1.5.md'}
MERMAID_SKIP = {'00-phase1-guide.md'}

problems = []


def err(f, msg):
    problems.append('%s: %s' % (os.path.relpath(f, REPO), msg))


def check_file(f, mermaid=True):
    text = open(f, encoding='utf-8').read()
    # стиль
    if '—' in text and os.path.basename(f) not in DASH_OK:
        err(f, 'длинное тире «—» (в проекте не используется)')
    if '\r' in text:
        err(f, 'символы CR (нужны окончания строк LF)')
    if re.search(r'[ \t]+$', text, re.M):
        err(f, 'пробелы в конце строк')
    # пустые заготовки
    for bad in ('TODO', 'TBD', 'нужно подтвердить', 'Нужно подтвердить', '???'):
        if bad in text:
            err(f, 'остался маркер «%s»' % bad)
    # ID требований
    body = re.sub(r'`[^`\n]*`', '', text)
    for m in set(re.findall(r'\bNFT-\d+\.\d+\b', body)):
        if m not in NFT:
            err(f, 'нет такого требования ' + m)
    for m in set(re.findall(r'(?<!N)\bFT-\d+\.\d+\b', body)):
        if m not in FT:
            err(f, 'нет такого требования ' + m)
    for m in set(re.findall(r'\bBG-\d+\b', body)):
        if m not in BG:
            err(f, 'нет такой цели ' + m)
    # ссылки
    d = os.path.dirname(f)
    for m in re.finditer(r'\]\(([^)#\s]+)(#[^)]*)?\)', text):
        t = m.group(1)
        if t.startswith(('http', 'mailto')):
            continue
        if not os.path.exists(os.path.normpath(os.path.join(d, t))):
            err(f, 'битая ссылка ' + t)
    # таблицы: одинаковое число колонок
    lines = text.split('\n')
    i = 0
    in_code = False
    while i < len(lines):
        ln = lines[i]
        if ln.strip().startswith('```'):
            in_code = not in_code
        if not in_code and ln.startswith('|') and i + 1 < len(lines) and re.match(r'^\|[\s:|-]+\|$', lines[i + 1]):
            def ncols(s):
                s = s.strip()
                s = s.replace('\\|', '')
                return len(s.strip('|').split('|'))
            n = ncols(ln)
            j = i
            while j < len(lines) and lines[j].startswith('|'):
                if ncols(lines[j]) != n:
                    err(f, 'таблица: строка %d, колонок %d вместо %d' % (j + 1, ncols(lines[j]), n))
                j += 1
            if i > 0 and lines[i - 1].strip() != '':
                err(f, 'таблица без пустой строки перед ней, строка %d' % (i + 1))
            i = j
            continue
        i += 1
    # Mermaid
    if mermaid and '```mermaid' in text and os.path.basename(f) not in MERMAID_SKIP:
        with tempfile.TemporaryDirectory() as tmp:
            out = os.path.join(tmp, 'o.md')
            cmd = ['mmdc'] + (['-p', PCFG] if os.path.exists(PCFG) else []) + ['-i', f, '-o', out, '-e', 'svg']
            # Сбой запуска браузера (раннер перегружен) не ошибка документа: до трёх попыток. Ошибка разбора диаграммы повторов не требует
            for attempt in range(3):
                r = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
                failed = r.returncode != 0 or 'rror' in r.stderr
                launcher = re.search(r'BrowserLauncher|Failed to launch|WS endpoint|Target closed|Protocol error|TargetCloseError', r.stderr)
                if not (failed and launcher):
                    break
                time.sleep(3)
            if r.returncode != 0 or 'rror' in r.stderr:
                err(f, 'Mermaid: ' + (r.stderr or r.stdout).strip()[-600:])
            else:
                n_blocks = text.count('```mermaid')
                svgs = glob.glob(os.path.join(tmp, '*.svg'))
                if len(svgs) != n_blocks:
                    err(f, 'Mermaid: блоков %d, отрисовано %d' % (n_blocks, len(svgs)))


def main(paths):
    files = []
    for p in paths:
        if os.path.isdir(p):
            files += sorted(glob.glob(os.path.join(p, '**/*.md'), recursive=True))
        else:
            files.append(p)
    for f in files:
        check_file(f)
    print('проверено файлов:', len(files))
    for p in problems:
        print('  -', p)
    print('проблем:', len(problems))
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
