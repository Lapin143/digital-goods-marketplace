#!/usr/bin/env python3
"""Забирает package-lock.json, который задание web выдало в аннотациях (tools/ci/lock_emit.sh).

    python3 tools/ci/lock_fetch.py ci/sandbox [--sha КОММИТ] [-o frontend/web-app/package-lock.json]

Зачем: в рабочей среде разработки реестр npm закрыт, lock-файл создаёт только CI. Скрипт читает аннотации без входа в аккаунт
(как tools/ci/ci_status.py), склеивает части, распаковывает gzip и проверяет, что получился JSON с полем packages.
"""
import argparse
import base64
import gzip
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ci_status as C   # noqa: E402

TITLE = re.compile(r'package-lock\.json часть (\d+) из (\d+)')


def collect(repo, branch, sha):
    parts, total = {}, 0
    for run in C.runs_for(repo, branch, sha):
        jobs = C.get('%s/repos/%s/actions/runs/%s/jobs?per_page=100' % (C.API, repo, run['id']))['jobs']
        for job in jobs:
            if job['name'] != 'web':
                continue
            for note in C.get('%s/repos/%s/check-runs/%s/annotations?per_page=50' % (C.API, repo, job['id'])):
                m = TITLE.match(note.get('title') or '')
                if m:
                    parts[int(m.group(1))] = (note.get('message') or '').strip()
                    total = int(m.group(2))
    return parts, total


def decode(parts, total):
    if total == 0 or sorted(parts) != list(range(1, total + 1)):
        raise ValueError('получены части %s из %d' % (sorted(parts), total))
    packed = base64.b64decode(''.join(parts[i] for i in range(1, total + 1)))
    text = gzip.decompress(packed).decode('utf-8')
    if not isinstance(json.loads(text).get('packages'), dict):
        raise ValueError('в файле нет поля packages')
    return text


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('branch')
    ap.add_argument('--sha')
    ap.add_argument('--repo')
    ap.add_argument('-o', '--out', default=os.path.join('frontend', 'web-app', 'package-lock.json'))
    args = ap.parse_args()
    repo = args.repo or C.default_repo()
    parts, total = collect(repo, args.branch, args.sha)
    try:
        text = decode(parts, total)
    except ValueError as e:
        print('Не удалось собрать package-lock.json: %s' % e)
        return 1
    with open(args.out, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)
    print('Записан %s: %d байт, пакетов %d' % (args.out, len(text.encode('utf-8')), len(json.loads(text)['packages'])))
    return 0


if __name__ == '__main__':
    sys.exit(main())
