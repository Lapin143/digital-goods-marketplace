#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Состояние запусков GitHub Actions для ветки или коммита, без входа в аккаунт.

    python3 tools/ci/ci_status.py [ветка] [--sha КОММИТ] [--wait] [--timeout СЕК] [--repo ВЛАДЕЛЕЦ/ИМЯ]

Что печатает: запуски рабочих процессов для коммита, задания, шаги с результатом и аннотации заданий
(сюда `tools/ci/diag.sh` кладёт причину ошибки). Журналы запусков без входа недоступны, аннотации доступны.
С ключом --wait ждёт окончания запусков (опрос раз в 20 секунд). Код выхода 0, если все запуски успешны.
Только стандартная библиотека Python.
"""
import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

API = 'https://api.github.com'


def get(url):
    req = urllib.request.Request(url, headers={'Accept': 'application/vnd.github+json', 'User-Agent': 'dgm-ci-status'})
    token = os.environ.get('GITHUB_TOKEN') or os.environ.get('GH_TOKEN')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def git(*args):
    return subprocess.run(['git'] + list(args), capture_output=True, text=True).stdout.strip()


def default_repo():
    url = git('config', '--get', 'remote.origin.url')
    url = url.removesuffix('.git')
    return '/'.join(url.rstrip('/').split('/')[-2:])


def runs_for(repo, branch, sha):
    q = '%s/repos/%s/actions/runs?per_page=30' % (API, repo)
    if branch:
        q += '&branch=' + branch.replace('/', '%2F')
    data = get(q)['workflow_runs']
    if sha:
        data = [r for r in data if r['head_sha'].startswith(sha)]
    else:
        newest = data[0]['head_sha'] if data else None
        data = [r for r in data if r['head_sha'] == newest]
    return data


def show(repo, runs, details):
    done = True
    ok = True
    for run in runs:
        st, cc = run['status'], run.get('conclusion')
        print('Запуск %s  %s  [%s%s]  %s' % (run['id'], run['name'], st, '/' + cc if cc else '', run['html_url']))
        if st != 'completed':
            done = False
        elif cc != 'success':
            ok = False
        if not details and st != 'completed':
            continue
        jobs = get('%s/repos/%s/actions/runs/%s/jobs?per_page=100' % (API, repo, run['id']))['jobs']
        for j in jobs:
            print('  Задание %-28s [%s%s]' % (j['name'], j['status'], '/' + j['conclusion'] if j.get('conclusion') else ''))
            for s in j.get('steps', []):
                mark = {'success': 'ok ', 'failure': 'ОШ ', 'skipped': '-- ', 'cancelled': 'xx '}.get(s.get('conclusion'), '.. ')
                if details or s.get('conclusion') not in ('success', 'skipped'):
                    print('    %s%s' % (mark, s['name']))
            if j.get('status') == 'completed':
                failed = j.get('conclusion') in ('failure', 'cancelled', 'timed_out')
                for a in get('%s/repos/%s/check-runs/%s/annotations?per_page=50' % (API, repo, j['id'])):
                    # Для упавших заданий печатаются все аннотации, для успешных только заметки уровня notice
                    if not failed and a.get('annotation_level') != 'notice':
                        continue
                    msg = (a.get('message') or '').strip()
                    print('    ::%s:: %s' % (a.get('annotation_level'), a.get('title') or ''))
                    for line in msg.splitlines():
                        print('       | ' + line)
    return done, ok


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('branch', nargs='?', default=None)
    ap.add_argument('--sha')
    ap.add_argument('--wait', action='store_true')
    ap.add_argument('--timeout', type=int, default=1500)
    ap.add_argument('--repo', default=None)
    ap.add_argument('--all-steps', action='store_true', help='печатать все шаги, а не только неудачные')
    a = ap.parse_args()
    repo = a.repo or default_repo()
    branch = a.branch or (None if a.sha else git('rev-parse', '--abbrev-ref', 'HEAD'))
    if not a.sha and branch:
        # По умолчанию ждём запуск именно для последнего локального коммита ветки, а не для предыдущего.
        local = git('rev-parse', '--verify', '-q', branch)
        if local:
            a.sha = local[:12]
    t0 = time.time()
    while True:
        try:
            runs = runs_for(repo, branch, a.sha)
        except urllib.error.HTTPError as e:
            print('Ошибка API GitHub: %s' % e)
            return 2
        if not runs:
            print('Запусков для %s пока нет' % (a.sha or branch))
            if not a.wait or time.time() - t0 > a.timeout:
                return 3
            time.sleep(20)
            continue
        done = all(r['status'] == 'completed' for r in runs)
        if a.wait and not done and time.time() - t0 < a.timeout:
            time.sleep(20)
            continue
        done, ok = show(repo, runs, a.all_steps)
        if not done:
            print('Запуски не завершены (тайм-аут ожидания)')
            return 4
        print('\nИТОГ: %s' % ('УСПЕХ' if ok else 'ЕСТЬ ОШИБКИ'))
        return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
