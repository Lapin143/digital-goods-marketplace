#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Проверка контейнеров сервисов Java на поднятом стенде (make up SET=dev-purchase, затем make up SET=dev-platform).

    python3 tools/stand-checks/check_services.py [сервис ...]

Для каждого сервиса: контейнер работает и здоров, не перезапускался и не убит из-за памяти, порты 8443 и 8444 приняли соединение,
журнал это строки JSON с полем message, миграции базы применены. Печатает расход памяти относительно лимита (вход для замеров шага 18).
Только стандартная библиотека Python.
"""
import json
import subprocess
import sys

SERVICES = ['catalog-service', 'inventory-service', 'order-service', 'payment-service', 'delivery-service', 'platform-service']
COMPOSE = ['docker', 'compose', '-f', 'compose.yaml', '--profile', '*']


def run(args):
    return subprocess.run(args, capture_output=True, text=True)


def container_id(service):
    r = run(COMPOSE + ['ps', '-a', '-q', service])
    return r.stdout.strip().splitlines()[0] if r.stdout.strip() else ''


def check(service):
    problems = []
    cid = container_id(service)
    if not cid:
        return ['контейнера нет (набор с этим сервисом не поднят)'], {}
    state = json.loads(run(['docker', 'inspect', '-f', '{{json .State}}', cid]).stdout)
    restarts = int(run(['docker', 'inspect', '-f', '{{.RestartCount}}', cid]).stdout.strip() or 0)
    health = (state.get('Health') or {}).get('Status')
    if state.get('Status') != 'running':
        problems.append('состояние %s, код выхода %s' % (state.get('Status'), state.get('ExitCode')))
    if health != 'healthy':
        problems.append('проверка готовности: %s' % health)
    if state.get('OOMKilled'):
        problems.append('контейнер убит из-за нехватки памяти (OOMKilled)')
    if restarts:
        problems.append('перезапусков: %d' % restarts)

    for port in (8443, 8444):
        r = run(['docker', 'exec', cid, 'bash', '-c', 'exec 3<>/dev/tcp/127.0.0.1/%d' % port])
        if r.returncode != 0:
            problems.append('порт %d не принимает соединения' % port)

    logs = run(['docker', 'logs', cid])
    lines = [l for l in (logs.stdout + logs.stderr).splitlines() if l.strip()]
    parsed, other = [], []
    for l in lines:
        try:
            o = json.loads(l)
            parsed.append(o) if isinstance(o, dict) else other.append(l)
        except ValueError:
            other.append(l)
    if len(parsed) < 5:
        problems.append('в журнале %d строк JSON, ожидалось не меньше 5' % len(parsed))
    if len(other) > 3:
        problems.append('в журнале %d строк не в формате JSON (допустимы строки JVM до запуска приложения): %s' % (len(other), other[:2]))
    if any('message' not in o for o in parsed):
        problems.append('есть строки JSON без поля message')
    if not any('Миграции базы' in str(o.get('message', '')) for o in parsed):
        problems.append('в журнале нет записи о применённых миграциях')
    errors = [o for o in parsed if str(o.get('log.level', o.get('level', ''))).upper() == 'ERROR']
    if errors:
        problems.append('в журнале %d записей уровня ERROR, первая: %s' % (len(errors), str(errors[0].get('message', ''))[:200]))
    stats = run(['docker', 'stats', '--no-stream', '--format', '{{.MemUsage}}', cid]).stdout.strip()
    return problems, {'memory': stats, 'restarts': restarts}


def main():
    names = sys.argv[1:] or SERVICES
    bad = 0
    for s in names:
        problems, info = check(s)
        if problems:
            bad += 1
            print('ОШ %-18s %s' % (s, '; '.join(problems)))
        else:
            print('ок %-18s здоров, память %s, журнал JSON, порты 8443 и 8444 открыты' % (s, info['memory']))
    if bad:
        print('Проверка сервисов: ошибок в %d из %d' % (bad, len(names)))
        return 1
    print('Проверка сервисов: все %d в порядке' % len(names))
    return 0


if __name__ == '__main__':
    sys.exit(main())
