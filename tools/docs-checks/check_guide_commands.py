#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Проверка руководства разработчика и runbook (шаг 19 Ф3): команды и факты из текста существуют в репозитории.

Руководство, которое не выполняется, стареет за неделю. Скрипт не запускает команды (стенд в этом задании не поднимается), а сверяет текст
с тем, на что он ссылается:

  1. Каждая цель `make <цель>` из блоков кода и из строк в обратных кавычках есть в Makefile.
  2. Значение `SET=...` одно из ALL_SETS Makefile; имена параметров `ИМЯ=значение` рядом с make встречаются в Makefile.
  3. Пути к файлам и каталогам репозитория в обратных кавычках существуют (кроме секретов, .pki, абсолютных путей и шаблонов).
  4. Переменные `DGM_...` читаются кодом или конфигурацией (сервисы, каркас, compose.yaml, Makefile, проверки).
  5. Порты таблицы «Адреса и порты» совпадают с портами compose.debug.yaml, ни одного лишнего и ни одного пропущенного.
  6. Охват: в руководстве упомянуты ключевые цели (первый запуск, проверки, сборка, сервис из IDE), в runbook цели для разбора сбоев; каждая цель
     Makefile либо упомянута в руководстве или runbook, либо входит в список целей, описанных в README своего каталога (EXEMPT, с причиной).
  7. Суммы наборов в таблице раздела 4 руководства (лимиты и замер) совпадают с memory-budget.md.
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import docslib as L  # noqa: E402

REPO = L.REPO
GUIDE = 'docs/09-operations/developer-guide.md'
RUNBOOK = 'docs/09-operations/runbook.md'
BUDGET = 'docs/09-operations/memory-budget.md'

# Цели, без которых руководство не отвечает на свой вопрос
GUIDE_REQUIRED = ['certs', 'secrets', 'up', 'down', 'reset', 'ps', 'logs', 'stand-check', 'stand-down-check', 'smoke', 'smoke-sabotage', 'demo', 'token',
                  'otp', 'keycloak-users', 'pki-status', 'pki-verify', 'obs-check', 'load', 'memory-start', 'memory-stop', 'images', 'build', 'test',
                  'kit-test', 'db-migrate', 'ide-check', 'gateway-check', 'stateless-check', 'services-check', 'keycloak-check', 'web-check',
                  'db-check', 'stubs-check', 'storage-check']
RUNBOOK_REQUIRED = ['ps', 'logs', 'stand-check', 'certs', 'pki-status', 'pki-verify', 'down', 'up', 'reset', 'keycloak-reimport', 'keycloak-users', 'otp',
                    'demo', 'token', 'db-roles', 'realm']
# Цели Makefile, которых нет в руководстве: их описывает README каталога, где они живут
EXEMPT = {
    'help': 'вывод списка команд',
    'docker-images': 'часть make images',
    'compose-config': 'вывод итоговой конфигурации, описан в tools/stand-checks/README.md',
    'kafka-topics': 'генерация из AsyncAPI, описана в infra/kafka/README.md',
    'db-migrations': 'генерация миграций, описана в tools/docs-checks/README.md',
    'storage-init': 'повтор инициализации хранилища, описан в infra/object-storage/README.md',
    'obs-validate': 'проверка конфигурации наблюдаемости, описана в infra/obs/README.md',
    'contract-check': 'описана в libs/service-kit/README.md',
    'stubs-test': 'описана в tools/external-stubs/README.md',
    'keycloak-test': 'описана в infra/keycloak/README.md',
    'pki-test': 'описана в infra/pki/README.md',
    'clean': 'удаление результатов сборки',
    'jars': 'часть make images',
}
# Файлы без пути, которых в репозитории нет по замыслу: создаются на стенде или запланированы разделом README
MISSING_OK = {'test_users.json': 'создаётся make keycloak-users, в Git не входит',
              'deploy.md': 'запланирован README раздела 09 (Ф6)', 'monitoring.md': 'запланирован README раздела 09 (Ф6)'}
SKIP_PREFIXES = ('secrets/', '.pki/', '/', '~', '\\\\', 'C:', 'http', 'mnt/')
PATH_EXT = ('.md', '.py', '.sh', '.yaml', '.yml', '.json', '.kts', '.toml', '.properties', '.java', '.js', '.mjs')
TOP_DIRS = ('infra/', 'tools/', 'docs/', 'services/', 'libs/', 'docker/', '.github/', 'frontend/', 'gradle/', 'build-logic/')

errors = []


def read(path):
    return open(os.path.join(REPO, path), encoding='utf-8').read()


def code_segments(text):
    """Блоки кода и фрагменты в обратных кавычках: [(номер строки, текст)]."""
    out = []
    in_code = False
    for n, line in enumerate(text.splitlines(), 1):
        if line.strip().startswith('```'):
            in_code = not in_code
            continue
        if in_code:
            out.append((n, line))
        else:
            for m in re.finditer(r'`([^`\n]+)`', line):
                out.append((n, m.group(1)))
    return out


def makefile_info():
    text = read('Makefile')
    targets = set(re.findall(r'^([a-zA-Z0-9_-][a-zA-Z0-9_.-]*):(?!=)', text, re.M))
    sets = re.search(r'^ALL_SETS\s*:=\s*(.+)$', text, re.M).group(1).split()
    return text, targets, sets


def haystack():
    """Текст кода и конфигурации, где читаются переменные окружения."""
    parts = []
    for base in ('services', 'libs', 'tools', 'infra', 'docker', 'build-logic'):
        for root, dirs, files in os.walk(os.path.join(REPO, base)):
            dirs[:] = [d for d in dirs if d not in ('build', '__pycache__', 'node_modules', '.gradle')]
            for f in files:
                if f in ('selftest.sh', 'check_guide_commands.py'):   # здесь имена стоят в примерах ошибок, это не чтение переменной кодом
                    continue
                if f.endswith(('.java', '.yml', '.yaml', '.properties', '.py', '.sh', '.kts', '.mjs', '.js', '.json')):
                    try:
                        parts.append(open(os.path.join(root, f), encoding='utf-8').read())
                    except (OSError, UnicodeDecodeError):
                        pass
    for f in ('Makefile', 'compose.yaml', 'compose.debug.yaml'):
        parts.append(read(f))
    return '\n'.join(parts)


def tree_basenames():
    names = set()
    for root, dirs, files in os.walk(REPO):
        dirs[:] = [d for d in dirs if d not in ('.git', 'build', 'node_modules', '__pycache__', '.gradle', 'secrets', '.pki')]
        names.update(files)
    return names


def check_make(doc, text, make_text, targets, sets, mentioned):
    for n, seg in code_segments(text):
        if 'apt install' in seg:
            continue
        for m in re.finditer(r'(?<![\w./-])make\s+((?:-\w+\s+)*)([a-z][a-z0-9-]*)((?:\s+[A-Za-z_]+=(?:"[^"]*"|\'[^\']*\'|\S+))*)', seg):
            target, args = m.group(2), m.group(3)
            if target not in targets:
                errors.append('%s, строка %d: цели make %s нет в Makefile' % (doc, n, target))
                continue
            mentioned.add(target)
            # make certs secrets: следующие слова-цели тоже вызываются
            for word in re.split(r'\s+', seg[m.end(2):].strip()):
                if word in targets:
                    mentioned.add(word)
                else:
                    break
            for a in re.finditer(r'([A-Za-z_]+)=("[^"]*"|\'[^\']*\'|\S+)', args):
                name, value = a.group(1), a.group(2).strip('"\'')
                if not re.search(r'\b%s\b' % re.escape(name), make_text):
                    errors.append('%s, строка %d: параметр %s=... не встречается в Makefile' % (doc, n, name))
                if name == 'SET' and not value.startswith('<') and value not in sets:
                    errors.append('%s, строка %d: набор SET=%s не входит в ALL_SETS (%s)' % (doc, n, value, ' '.join(sets)))
        for a in re.finditer(r'\bSET=([a-z][a-z-]*)', seg):
            if a.group(1) not in sets:
                errors.append('%s, строка %d: набор SET=%s не входит в ALL_SETS' % (doc, n, a.group(1)))


def check_paths(doc, text, names):
    for n, seg in code_segments(text):
        if re.search(r'[<>*$ ]|://|\\', seg) or seg.startswith(SKIP_PREFIXES):
            continue
        token = seg.strip().rstrip('.,;:')
        if token.startswith('./'):
            token = token[2:]
        if not (token.startswith(TOP_DIRS) or (token.endswith(PATH_EXT) and '/' not in token) or (token.endswith(PATH_EXT) and token.startswith(TOP_DIRS))):
            continue
        if token.startswith(TOP_DIRS) or '/' in token:
            ok = os.path.exists(os.path.join(REPO, token))
        else:
            ok = os.path.exists(os.path.join(REPO, token)) or token in names
        if not ok and token not in MISSING_OK:
            errors.append('%s, строка %d: пути %s нет в репозитории' % (doc, n, token))


def check_env(doc, text, hay):
    seen = set()
    for n, seg in code_segments(text):
        for m in re.finditer(r'\bDGM_[A-Z0-9_]+\b', seg):
            name = m.group(0)
            if name in seen:
                continue
            seen.add(name)
            if name not in hay:
                errors.append('%s, строка %d: переменной %s нет ни в коде, ни в конфигурации' % (doc, n, name))


def check_ports(text):
    debug = read('compose.debug.yaml')
    expected = set(int(p) for p in re.findall(r'127\.0\.0\.1:(\d+):', debug))
    section = text.split('## 5. Адреса и порты')[1].split('\n## 6.')[0]
    docs_ports = set()
    for t in L.tables(section):
        if t['header'][:1] == ['Порт']:
            for row in t['rows']:
                for p in re.findall(r'\d+', row[0]):
                    docs_ports.add(int(p))
    for p in sorted(expected - docs_ports):
        errors.append('%s: порт %d из compose.debug.yaml не описан в таблице раздела 5' % (GUIDE, p))
    for p in sorted(docs_ports - expected):
        errors.append('%s: порт %d из таблицы раздела 5 не опубликован в compose.debug.yaml' % (GUIDE, p))
    return len(expected)


def check_sets_table(text):
    budget = read(BUDGET)
    limits = {}
    t = L.find_table(budget, 'Набор', contains='Группы')
    for row in (t['rows'] if t else []):
        limits[L.backticked(row[0])[0]] = int(row[2])
    peaks = {}
    t = L.find_table(budget, 'Набор', contains='Сумма пиков, МБ')
    for row in (t['rows'] if t else []):
        peaks[L.backticked(row[0])[0]] = int(row[2])
    section = text.split('## 4. Какие наборы запускать на ноутбуке')[1].split('\n## 5.')[0]
    table = L.find_table(section, 'Набор', contains='Лимиты, МБ')
    if not table:
        errors.append('%s: нет таблицы наборов в разделе 4' % GUIDE)
        return 0
    n = 0
    for row in table['rows']:
        name = L.backticked(row[0])[0]
        n += 1
        if name not in limits:
            errors.append('%s: набор %s не описан в memory-budget.md' % (GUIDE, name))
            continue
        if int(row[2]) != limits[name]:
            errors.append('%s: лимиты набора %s %s МБ, в memory-budget.md %d МБ' % (GUIDE, name, row[2], limits[name]))
        if name in peaks:
            m = re.match(r'\d+', row[3])
            if not m or int(m.group(0)) != peaks[name]:
                errors.append('%s: замер набора %s «%s», в memory-budget.md (раздел 3.3) %d МБ' % (GUIDE, name, row[3], peaks[name]))
    return n


def main():
    for p in (GUIDE, RUNBOOK):
        if not os.path.exists(os.path.join(REPO, p)):
            print('ПРОВЕРКА НЕ ПРОЙДЕНА:\n  - нет файла %s' % p)
            return 1
    make_text, targets, sets = makefile_info()
    hay = haystack()
    names = tree_basenames()
    mentioned = {GUIDE: set(), RUNBOOK: set()}
    for doc in (GUIDE, RUNBOOK):
        text = read(doc)
        check_make(doc, text, make_text, targets, sets, mentioned[doc])
        check_paths(doc, text, names)
        check_env(doc, text, hay)
    guide_text = read(GUIDE)
    ports = check_ports(guide_text)
    sets_rows = check_sets_table(guide_text)
    for t in GUIDE_REQUIRED:
        if t not in mentioned[GUIDE]:
            errors.append('%s: в руководстве нет команды make %s (она нужна по замыслу шага 19)' % (GUIDE, t))
    for t in RUNBOOK_REQUIRED:
        if t not in mentioned[RUNBOOK]:
            errors.append('%s: в runbook нет команды make %s' % (RUNBOOK, t))
    covered = mentioned[GUIDE] | mentioned[RUNBOOK]
    for t in sorted(targets - covered - set(EXEMPT)):
        errors.append('цель make %s не описана ни в руководстве, ни в runbook и не входит в EXEMPT скрипта (укажите, где она описана)' % t)
    for t in sorted(set(EXEMPT) - targets):
        errors.append('EXEMPT: цели make %s больше нет в Makefile' % t)
    if errors:
        print('ПРОВЕРКА НЕ ПРОЙДЕНА:')
        for e in errors:
            print('  - %s' % e)
        print('проблем: %d' % len(errors))
        return 1
    print('ок: целей make в Makefile %d, упомянуто в руководстве %d и в runbook %d, портов отладки %d, наборов в таблице %d, наборов SET %d'
          % (len(targets), len(mentioned[GUIDE]), len(mentioned[RUNBOOK]), ports, sets_rows, len(sets)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
