#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Ожидание готовности стенда после `docker compose up -d`.

    python3 tools/stand-checks/wait.py --profiles infra,stubs [--timeout 300]

Контейнер готов, если он healthy (есть проверка готовности) или running (проверки нет) либо это разовое задание,
завершившееся с кодом 0 (kafka-init, storage-init). Если контейнер завершился с ошибкой, стал unhealthy или время вышло, скрипт печатает
состояние и последние строки журнала проблемных контейнеров и возвращает код 1. Только стандартная библиотека Python.
"""
import argparse
import json
import os
import subprocess
import sys
import time

ONE_SHOT = {'kafka-init', 'storage-init'}


def run(args, env=None):
    e = dict(os.environ)
    if env:
        e.update(env)
    return subprocess.run(args, capture_output=True, text=True, env=e)


def compose_files():
    files = ['-f', 'compose.yaml']
    if os.environ.get('DEBUG'):
        files += ['-f', 'compose.debug.yaml']
    return files


def expected_services(profiles):
    r = run(['docker', 'compose'] + compose_files() + ['config', '--services'], {'COMPOSE_PROFILES': profiles})
    if r.returncode != 0:
        print('ОШИБКА: docker compose config:', r.stderr.strip())
        sys.exit(1)
    return sorted(s for s in r.stdout.split() if s)


def parse_ps(text):
    text = text.strip()
    if not text:
        return []
    if text.startswith('['):
        return json.loads(text)
    return [json.loads(line) for line in text.splitlines() if line.strip()]


def state(profiles):
    r = run(['docker', 'compose'] + compose_files() + ['--profile', '*', 'ps', '-a', '--format', 'json'])
    out = {}
    for c in parse_ps(r.stdout):
        out[c['Service']] = c
    return out


def verdict(name, c):
    """Возвращает 'ok', 'wait' или текст ошибки."""
    if c is None:
        return 'wait'
    st, health, code = c.get('State'), c.get('Health') or '', c.get('ExitCode', 0)
    if name in ONE_SHOT:
        if st == 'exited':
            return 'ok' if code == 0 else 'завершился с кодом %s' % code
        return 'wait'
    if st in ('exited', 'dead'):
        return 'остановлен (код %s)' % code
    if st == 'restarting':
        return 'перезапускается'
    if health == 'unhealthy':
        return 'unhealthy'
    if health == 'healthy' or (st == 'running' and health == ''):
        return 'ok'
    return 'wait'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--profiles', required=True)
    ap.add_argument('--timeout', type=int, default=int(os.environ.get('WAIT_TIMEOUT', '300')))
    a = ap.parse_args()
    want = expected_services(a.profiles)
    print('Ожидание контейнеров: %s' % ', '.join(want))
    start = time.time()
    last = None
    while True:
        st = state(a.profiles)
        res = {n: verdict(n, st.get(n)) for n in want}
        bad = {n: v for n, v in res.items() if v not in ('ok', 'wait')}
        line = ', '.join('%s=%s' % (n, 'готов' if v == 'ok' else ('ждём' if v == 'wait' else v)) for n, v in res.items())
        if line != last:
            print('[%3d с] %s' % (time.time() - start, line))
            last = line
        if all(v == 'ok' for v in res.values()):
            print('Стенд готов за %d с' % (time.time() - start))
            return 0
        if bad or time.time() - start > a.timeout:
            problem = bad or {n: 'не готов за %d с' % a.timeout for n, v in res.items() if v != 'ok'}
            print('ОШИБКА: %s' % problem)
            for n in problem:
                print('===== журнал %s (последние 40 строк)' % n)
                r = run(['docker', 'compose'] + compose_files() + ['--profile', '*', 'logs', '--no-color', '--tail', '40', n])
                print(r.stdout[-6000:] or r.stderr[-2000:])
            return 1
        time.sleep(3)


if __name__ == '__main__':
    sys.exit(main())
