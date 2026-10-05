#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Проверка рабочих процессов GitHub Actions и Dependabot (шаг 17 Ф3, цепочка поставок).

    python3 tools/docs-checks/check_workflows.py            без сети
    python3 tools/docs-checks/check_workflows.py --online   плюс сверка хеша каждого действия с тегом из комментария (git ls-remote)

Правила:
  1. Каждое стороннее действие закреплено хешем коммита (40 знаков) с комментарием версии: «uses: owner/name@<sha> # v1.2.3». Тег можно
     перенести на другой коммит (так взломали tj-actions/changed-files), хеш нельзя. С ключом --online хеш сверяется с тегом из комментария,
     то есть комментарий не может врать.
  2. Права GITHUB_TOKEN минимальны: на верхнем уровне contents: read (wrapper.yml, который возвращает файлы коммитом, contents: write);
     в задании можно добавить только packages: write и только в задании publish.
  3. У каждого задания есть timeout-minutes: зависший раннер не должен съедать часы.
  4. У каждого actions/checkout persist-credentials: false: токен не остаётся в .git/config для следующих шагов (кроме wrapper.yml).
  5. У каждого actions/setup-java в ci.yml включён кэш Gradle (cache: gradle).
  6. Нет триггера pull_request_target (он выполняет код с секретами репозитория для запросов из форков), в ci.yml есть concurrency.
  7. Секреты берутся только из secrets.GITHUB_TOKEN.
  8. .github/dependabot.yml описывает github-actions, gradle, docker и docker-compose.
"""
import os
import re
import subprocess
import sys

import yaml

REPO = os.environ.get('REPO') or os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
WORKFLOWS = os.path.join(REPO, '.github', 'workflows')
USES = re.compile(r'^\s*-?\s*uses:\s*([^@\s]+)@(\S+)(?:\s+#\s*(\S+))?\s*$')
SHA = re.compile(r'^[0-9a-f]{40}$')
VERSION = re.compile(r'^v?\d+(\.\d+){0,2}([-+.\w]*)$')
WRITE_EXEMPT = {'wrapper.yml'}          # возвращает файлы коммитом в ветку
PACKAGES_JOB = 'publish'

errors = []


def err(where, text):
    errors.append('%s: %s' % (where, text))


def check_file(name, online):
    path = os.path.join(WORKFLOWS, name)
    raw = open(path, encoding='utf-8').read()
    doc = yaml.safe_load(raw)
    top = doc.get('permissions')
    expected_top = {'contents': 'write'} if name in WRITE_EXEMPT else {'contents': 'read'}
    if top != expected_top:
        err(name, 'права GITHUB_TOKEN на верхнем уровне %s, нужно %s' % (top, expected_top))
    triggers = doc.get(True) or doc.get('on') or {}
    if 'pull_request_target' in (triggers if isinstance(triggers, (dict, list)) else [triggers]):
        err(name, 'триггер pull_request_target запрещён')
    if name == 'ci.yml' and not doc.get('concurrency'):
        err(name, 'нет concurrency: повторные запуски одной ветки должны отменять друг друга')
    for ln, line in enumerate(raw.splitlines(), 1):
        if re.search(r'\bsecrets\.(?!GITHUB_TOKEN\b)\w+', line):
            err('%s:%d' % (name, ln), 'используется секрет, кроме GITHUB_TOKEN: %s' % line.strip())
        m = USES.match(line)
        if not m:
            if re.match(r'^\s*-?\s*uses:', line):
                err('%s:%d' % (name, ln), 'не разобрано: %s' % line.strip())
            continue
        action, ref, comment = m.groups()
        if action.startswith('./'):
            continue
        if not SHA.match(ref):
            err('%s:%d' % (name, ln), '%s закреплено не хешем коммита, а «%s»' % (action, ref))
            continue
        if not comment or not VERSION.match(comment):
            err('%s:%d' % (name, ln), '%s: после хеша нужен комментарий с версией (# v1.2.3), а там «%s»' % (action, comment))
            continue
        if online:
            verify_online(name, ln, action, ref, comment)
    for job_id, job in (doc.get('jobs') or {}).items():
        where = '%s, задание %s' % (name, job_id)
        if 'timeout-minutes' not in job:
            err(where, 'нет timeout-minutes')
        jp = job.get('permissions')
        if jp is not None:
            allowed = {'contents': 'read', 'packages': 'write'} if job_id == PACKAGES_JOB else {'contents': 'read'}
            if any(allowed.get(k) != v for k, v in jp.items()) or jp.get('contents') != 'read':
                err(where, 'права задания %s шире допустимых %s' % (jp, allowed))
        for i, step in enumerate(job.get('steps') or [], 1):
            uses = str(step.get('uses', ''))
            if uses.startswith('actions/checkout@') and name not in WRITE_EXEMPT and (step.get('with') or {}).get('persist-credentials') is not False:
                err(where, 'шаг %d: у actions/checkout нет persist-credentials: false' % i)
            if uses.startswith('actions/setup-java@') and name == 'ci.yml' and (step.get('with') or {}).get('cache') != 'gradle':
                err(where, 'шаг %d: у actions/setup-java нет cache: gradle' % i)


def verify_online(name, ln, action, sha, tag):
    repo = '/'.join(action.split('/')[:2])
    r = subprocess.run(['git', 'ls-remote', 'https://github.com/%s' % repo, 'refs/tags/%s' % tag, 'refs/tags/%s^{}' % tag],
                       capture_output=True, text=True, timeout=60)
    if r.returncode != 0:
        err('%s:%d' % (name, ln), 'не удалось проверить %s@%s по тегу %s: %s' % (action, sha[:8], tag, r.stderr.strip()[:120]))
        return
    refs = dict(line.split('\t')[::-1] for line in r.stdout.splitlines() if '\t' in line)
    commit = refs.get('refs/tags/%s^{}' % tag) or refs.get('refs/tags/%s' % tag)
    if commit is None:
        err('%s:%d' % (name, ln), 'у %s нет тега %s' % (repo, tag))
    elif commit != sha:
        err('%s:%d' % (name, ln), '%s: тег %s указывает на %s, в файле %s: комментарий версии не соответствует хешу' % (repo, tag, commit[:12], sha[:12]))


def check_dependabot():
    path = os.path.join(REPO, '.github', 'dependabot.yml')
    if not os.path.exists(path):
        err('dependabot.yml', 'файла нет')
        return
    doc = yaml.safe_load(open(path, encoding='utf-8'))
    got = {u.get('package-ecosystem') for u in doc.get('updates', [])}
    for eco in ('github-actions', 'gradle', 'docker', 'docker-compose'):
        if eco not in got:
            err('dependabot.yml', 'не описан пакетный менеджер %s' % eco)
    for u in doc.get('updates', []):
        if not u.get('schedule', {}).get('interval'):
            err('dependabot.yml', '%s: нет расписания' % u.get('package-ecosystem'))


def main():
    online = '--online' in sys.argv
    names = sorted(n for n in os.listdir(WORKFLOWS) if n.endswith(('.yml', '.yaml')))
    if 'ci.yml' not in names:
        err('workflows', 'нет ci.yml')
    for n in names:
        check_file(n, online)
    check_dependabot()
    if errors:
        print('ПРОВЕРКА НЕ ПРОЙДЕНА:')
        for e in errors:
            print('  - %s' % e)
        print('проблем: %d' % len(errors))
        return 1
    print('ок: рабочих процессов %d, действия закреплены по хешу%s, права и сроки заданий в норме, Dependabot описан' % (
        len(names), ' и сверены с тегами' if online else ''))
    return 0


if __name__ == '__main__':
    sys.exit(main())
