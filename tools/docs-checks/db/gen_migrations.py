#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Миграции Flyway из тех же частей, из которых собраны DDL (docs/07-data/ddl).

    python3 tools/docs-checks/db/gen_migrations.py            записать миграции в services/<сервис>/src/main/resources/db/migration
    python3 tools/docs-checks/db/gen_migrations.py --check    проверить, что миграции и DDL соответствуют частям (код выхода 1, если нет)
    python3 tools/docs-checks/db/gen_migrations.py --print    показать список миграций и контрольные суммы

Источник один: tools/docs-checks/db/parts/*.sql. Из него получаются и документ (docs/07-data/ddl/<сервис>.sql, его собирает build.py),
и миграции. Разбиение идёт по заголовкам разделов в частях (три строки «-- ====», название, «-- ====»):

  common/V1__common.sql          служебные таблицы и функция контроля статусов (outbox, processed_event, idempotency_key), одинаковы во всех базах
  <схема>/V<n>__<схема>.sql      по одной миграции на схему модуля (правило модульности 7: у модуля своя папка миграций)
  access/V<последняя>__access.sql   роли и права на таблицы, комментарии к функциям; идёт последней, потому что права выдаются на уже созданные таблицы

Номера версий сквозные внутри сервиса, Flyway читает все подпапки каталога db/migration (расположение по умолчанию) и применяет по номеру.
Содержимое разделов не меняется ни на символ: склейка миграций (без шапок) равна телу docs/07-data/ddl/<сервис>.sql, это проверяется
сравнением SHA-256. Контрольную сумму миграции считает сам Flyway при применении, правка применённого файла вызывает ошибку проверки.

R1 заморожен (выпуск вышел, тег r1, Р-181): файл r1-frozen.sha256 хранит контрольные суммы всех миграций R1, --check сверяет с ними файлы
и части. Изменения модели R2 идут отдельной частью сервиса parts/<часть>.r2.sql. Её разделы начинаются с заголовка «R2, <схема>: описание»
(схема это папка миграции, для прав «access») и выпускаются миграциями с номерами выше последней миграции R1 в той же папке модуля:

  <схема>/V<n>__r2_<схема>.sql     один раздел части R2 в одной миграции; «R2, access» идёт последним (права на новые таблицы)

Склейка по возрастанию номера: служебный блок, разделы R1, разделы R2. Так же собирает документ DDL build.py.
"""
import hashlib
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import build as ddl_build  # noqa: E402  (HEADER, SERVICES, PARTS)

REPO = ddl_build.REPO
PARTS = ddl_build.PARTS
SERVICES = ddl_build.SERVICES
DDL_DIR = os.path.join(REPO, 'docs', '07-data', 'ddl')
RULE = '-- ' + '=' * 76
FROZEN = os.path.join(HERE, 'r1-frozen.sha256')
R2_TITLE = re.compile(r'^R2, (\w+): (.+)$')

SHAPKA = """-- СОЗДАН СКРИПТОМ tools/docs-checks/db/gen_migrations.py ИЗ tools/docs-checks/db/parts/{parts}. РУКАМИ НЕ ПРАВИТЬ.
-- Сервис {service}, база {db}. Миграция V{version}, {what}.
-- Применённую миграцию править нельзя: Flyway сверяет контрольную сумму, правка вызовет ошибку проверки при старте.
-- Полная модель одним файлом: docs/07-data/ddl/{service}.sql

"""


class GenError(Exception):
    pass


def is_access(title):
    return re.match(r'(Роль|Роли) и права', title) is not None


def split_sections(text, label):
    """Делит текст на разделы по заголовкам из трёх строк. Возвращает список (заголовок, текст раздела без хвостовых пробелов)."""
    lines = text.rstrip('\n').split('\n')
    starts = []
    for i in range(len(lines) - 2):
        if lines[i].startswith('-- ====') and lines[i + 2].startswith('-- ====') and not lines[i + 1].startswith('-- ===='):
            starts.append(i)
    if not starts or starts[0] != 0:
        raise GenError('%s: файл должен начинаться с заголовка раздела (три строки «-- ====»)' % label)
    out = []
    for n, s in enumerate(starts):
        e = starts[n + 1] if n + 1 < len(starts) else len(lines)
        chunk = '\n'.join(lines[s:e]).rstrip('\n')
        title = lines[s + 1][3:].strip()
        out.append((title, chunk))
    # Склейка разделов через пустую строку должна давать исходный текст без потерь
    joined = '\n\n'.join(c for _, c in out)
    if joined != text.rstrip('\n'):
        raise GenError('%s: между разделами должна быть ровно одна пустая строка (иначе миграции не склеятся в исходный DDL)' % label)
    return out


def module_of(title, chunk, label):
    if is_access(title):
        return 'access'
    m = re.search(r'^create schema (\w+);', chunk, re.M)
    if not m:
        raise GenError('%s: в разделе «%s» нет create schema' % (label, title))
    return m.group(1)


def plan():
    """Возвращает список миграций: словарь с ключами service, db, module, version, path (относительно корня), text, section."""
    infra_text = open(os.path.join(PARTS, 'infra.sql'), encoding='utf-8').read()
    infra = split_sections(infra_text, 'parts/infra.sql')
    if len(infra) != 1:
        raise GenError('parts/infra.sql: ожидался один раздел, найдено %d' % len(infra))
    result = []
    for service, db, part, _modules in SERVICES:
        body = open(os.path.join(PARTS, part + '.sql'), encoding='utf-8').read()
        sections = split_sections(body, 'parts/%s.sql' % part)
        if not is_access(sections[-1][0]):
            raise GenError('parts/%s.sql: последним должен идти раздел «Роли и права»' % part)
        items = [('common', 'служебные таблицы и функция контроля статусов', infra[0][1])]
        for title, chunk in sections:
            mod = module_of(title, chunk, 'parts/%s.sql' % part)
            what = 'роли и права' if mod == 'access' else 'схема %s' % mod
            items.append((mod, what, chunk))
        seen = set()
        for n, (mod, what, chunk) in enumerate(items, start=1):
            if mod in seen:
                raise GenError('%s: модуль %s встречается дважды' % (service, mod))
            seen.add(mod)
            text = SHAPKA.format(parts='infra.sql' if mod == 'common' else '%s.sql' % part,
                                 service=service, db=db, version=n, what=what) + chunk + '\n'
            rel = os.path.join('services', service, 'src', 'main', 'resources', 'db', 'migration', mod, 'V%d__%s.sql' % (n, mod))
            result.append({'service': service, 'db': db, 'module': mod, 'version': n, 'path': rel, 'text': text, 'section': chunk, 'release': 'R1'})
        r2_text = ddl_build.read_r2(part)
        if r2_text is None:
            continue
        label = 'parts/%s.r2.sql' % part
        r2_sections = split_sections(r2_text, label)
        seen_r2 = set()
        for k, (title, chunk) in enumerate(r2_sections):
            m = R2_TITLE.match(title)
            if not m:
                raise GenError('%s: заголовок раздела «%s» должен иметь вид «R2, <схема>: описание»' % (label, title))
            mod, descr = m.group(1), m.group(2)
            if mod in seen_r2:
                raise GenError('%s: схема %s встречается дважды' % (label, mod))
            seen_r2.add(mod)
            if mod == 'access' and k != len(r2_sections) - 1:
                raise GenError('%s: раздел «R2, access» должен идти последним' % label)
            if mod != 'access' and mod not in seen:
                raise GenError('%s: схемы %s нет среди модулей сервиса %s' % (label, mod, service))
            version = len(items) + 1 + k
            what = 'R2, %s' % descr
            text = SHAPKA.format(parts='%s.r2.sql' % part, service=service, db=db, version=version, what=what) + chunk + '\n'
            rel = os.path.join('services', service, 'src', 'main', 'resources', 'db', 'migration', mod, 'V%d__r2_%s.sql' % (version, mod))
            result.append({'service': service, 'db': db, 'module': mod, 'version': version, 'path': rel, 'text': text, 'section': chunk, 'release': 'R2'})
    return result


def ddl_body(service, db, modules):
    full = open(os.path.join(DDL_DIR, service + '.sql'), encoding='utf-8').read()
    head = ddl_build.HEADER.format(service=service, db=db, modules=modules)
    if not full.startswith(head):
        return None
    return full[len(head):]


def sha(text):
    return hashlib.sha256(text.encode('utf-8')).hexdigest()


def check(items):
    problems = []
    by_service = {}
    for it in items:
        by_service.setdefault(it['service'], []).append(it)
    expected_paths = set()
    for it in items:
        expected_paths.add(it['path'])
        full = os.path.join(REPO, it['path'])
        try:
            have = open(full, encoding='utf-8').read()
        except FileNotFoundError:
            problems.append('нет файла %s, выполните python3 tools/docs-checks/db/gen_migrations.py' % it['path'])
            continue
        if have != it['text']:
            problems.append('%s не соответствует частям DDL, выполните python3 tools/docs-checks/db/gen_migrations.py' % it['path'])
    # Лишние файлы в каталогах миграций (устаревшие после смены разбиения)
    for service, _db, _part, _m in SERVICES:
        root = os.path.join(REPO, 'services', service, 'src', 'main', 'resources', 'db', 'migration')
        for d, _dirs, files in os.walk(root):
            for f in files:
                rel = os.path.relpath(os.path.join(d, f), REPO)
                if rel not in expected_paths:
                    problems.append('лишний файл миграции %s (его нет в разбиении частей DDL)' % rel)
    # R1 заморожен: каждая миграция R1 совпадает с контрольной суммой из r1-frozen.sha256, и новых миграций R1 нет
    frozen = {}
    for line in open(FROZEN, encoding='utf-8').read().split('\n'):
        if line.strip() and not line.startswith('#'):
            digest, path = line.split(None, 1)
            frozen[path.strip()] = digest
    r1_items = {it['path']: it for it in items if it['release'] == 'R1'}
    for path, digest in sorted(frozen.items()):
        it = r1_items.get(path)
        if it is None:
            problems.append('миграция R1 %s есть в r1-frozen.sha256, но частями R1 больше не порождается' % path)
        elif sha(it['text']) != digest:
            problems.append('миграция R1 %s изменилась (sha256 %s, в r1-frozen.sha256 %s): R1 заморожен, правки идут частью parts/*.r2.sql'
                            % (path, sha(it['text'])[:12], digest[:12]))
    for path in sorted(set(r1_items) - set(frozen)):
        problems.append('новая миграция R1 %s: R1 заморожен, изменения модели идут частью parts/*.r2.sql' % path)
    # Склейка миграций с диска равна телу docs/07-data/ddl/<сервис>.sql, а тот равен сборке build.py из частей
    infra = open(os.path.join(PARTS, 'infra.sql'), encoding='utf-8').read()
    for service, db, part, modules in SERVICES:
        sections = []
        for it in sorted(by_service[service], key=lambda x: x['version']):
            try:
                have = open(os.path.join(REPO, it['path']), encoding='utf-8').read()
            except FileNotFoundError:
                continue
            idx = have.find('\n\n')
            sections.append(have[idx + 2:].rstrip('\n'))
        glued = '\n\n'.join(sections) + '\n'
        body = ddl_body(service, db, modules)
        if body is None:
            problems.append('docs/07-data/ddl/%s.sql: шапка не совпадает с build.py, выполните python3 tools/docs-checks/db/build.py' % service)
        elif sha(glued) != sha(body):
            problems.append('склейка миграций %s (sha256 %s) не равна docs/07-data/ddl/%s.sql (sha256 %s)'
                            % (service, sha(glued)[:12], service, sha(body)[:12]))
        if body is not None and body != ddl_build.body_of(infra, part):
            problems.append('docs/07-data/ddl/%s.sql не соответствует частям, выполните python3 tools/docs-checks/db/build.py' % service)
    return problems


def main(argv):
    items = plan()
    if '--print' in argv:
        for it in items:
            print('%-18s V%-2d %-18s %s  %s' % (it['service'], it['version'], it['module'], sha(it['text'])[:12], it['path']))
        return 0
    if '--check' in argv:
        problems = check(items)
        if problems:
            for p in problems:
                print('ОШИБКА: ' + p)
            return 1
        print('миграции Flyway соответствуют частям DDL: %d файлов в %d сервисах, склейка равна docs/07-data/ddl' % (len(items), len(SERVICES)))
        return 0
    wanted = set()
    for it in items:
        full = os.path.join(REPO, it['path'])
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, 'w', encoding='utf-8', newline='\n') as f:
            f.write(it['text'])
        wanted.add(it['path'])
    for service, _db, _part, _m in SERVICES:
        root = os.path.join(REPO, 'services', service, 'src', 'main', 'resources', 'db', 'migration')
        for d, _dirs, files in os.walk(root):
            for f in files:
                rel = os.path.relpath(os.path.join(d, f), REPO)
                if rel not in wanted:
                    os.remove(os.path.join(REPO, rel))
                    print('удалён устаревший файл', rel)
    print('записано %d файлов миграций' % len(items))
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main(sys.argv[1:]))
    except GenError as e:
        print('ОШИБКА:', e)
        sys.exit(1)
