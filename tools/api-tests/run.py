#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Запуск API-тестов Newman на поднятом стенде (ST-02 и сквозные сценарии шага 4 Ф4).

    make e2e                       коллекция из build.py, токены тестовых пользователей, Newman, сводка
    python3 tools/api-tests/run.py [--no-matrix] [--base https://localhost:8443] [--ca secrets/tls_ca.crt]
    python3 tools/api-tests/run.py --sabotage     проверка проверки: ожидание отказа 403 заменяется на 200, Newman обязан покраснеть (код 0, если покраснел)

Порядок: collection (build.py) -> если запросов нет, итог «проверять нечего» и код 0, стенд не нужен -> токены ролей по настоящему входу
(tools/stand-checks/demo.py, пользователи make keycloak-users) -> newman run -> разбор отчёта -> сводка в консоль, в аннотации GitHub и в
сводку запуска. Токен живёт 5 минут (infra/keycloak/gen_realm.py, ACCESS_TTL), поэтому токены берутся прямо перед запуском Newman.
Newman ставится отдельно (npm install -g newman@<версия из versions.md>) и в репозиторий не входит.
Нужен набор full с отладочным портом Keycloak: make up SET=full DEBUG=1 и make keycloak-users.
"""
import argparse
import json
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(REPO, 'tools', 'stand-checks'))
import build  # noqa: E402
import st02_matrix  # noqa: E402

OUT = os.path.join(REPO, 'build', 'api-tests')


def write_summary(lines):
    text = '\n'.join(lines)
    print(text)
    path = os.environ.get('GITHUB_STEP_SUMMARY')
    if path:
        with open(path, 'a', encoding='utf-8') as f:
            f.write(text + '\n')


def notice(kind, title, message):
    if os.environ.get('GITHUB_ACTIONS'):
        message = message.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
        print('::%s title=%s::%s' % (kind, title.replace(':', '%3A').replace(',', '%2C'), message))


def tokens_for(roles):
    import demo  # noqa: E402  (tools/stand-checks/demo.py: вход по коду авторизации с PKCE)
    values = {}
    for role in roles:
        user = st02_matrix.ROLE_USERS.get(role)
        if user is None:
            continue
        token = demo.token_for(user)
        if not token:
            return None, 'не удалось войти тестовым пользователем %s (роль %s)' % (user, role)
        values['token_' + role] = token
    return values, None


def sabotage(collection):
    """Заменяет ожидание отказа 403 на 200 в первом случае матрицы. True, если такой случай нашёлся."""
    for folder in collection['item']:
        for entry in folder['item']:
            for event in entry.get('event', []):
                lines = event['script']['exec']
                for i, line in enumerate(lines):
                    if 'equal(403)' in line:
                        lines[i] = line.replace('equal(403)', 'equal(200)')
                        print('испорчено ожидание: %s' % entry['name'])
                        return True
    return False


def parse_report(path):
    with open(path, encoding='utf-8') as f:
        run = json.load(f)['run']
    failures = []
    for failure in run.get('failures', []):
        error = failure.get('error') or {}
        source = (failure.get('source') or {}).get('name', '?')
        failures.append('%s: %s' % (error.get('test') or source, (error.get('message') or error.get('name') or '').replace('\n', ' ')[:300]))
    return run.get('stats', {}), failures, run.get('timings', {})


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--no-matrix', action='store_true')
    parser.add_argument('--sabotage', action='store_true')
    parser.add_argument('--base', default='https://localhost:8443')
    parser.add_argument('--ca', default=os.path.join(REPO, 'secrets', 'tls_ca.crt'))
    args = parser.parse_args(argv)

    os.chdir(REPO)  # пути вида secrets/test_users.json в tools/stand-checks относительные
    out = os.path.join(OUT, 'sabotage') if args.sabotage else OUT
    os.makedirs(out, exist_ok=True)
    collection, summary, problems = build.build(with_matrix=not args.no_matrix)
    if problems:
        for p in problems:
            print('  -', p)
        notice('error', 'API-тесты', 'сценарии не собираются: ' + '; '.join(problems[:5]))
        return 1
    head = '### API-тесты (Newman)\n\nсценариев: %d, случаев матрицы ST-02: %d, запросов: %d' % (summary['scenarios'], summary['matrix_cases'], summary['requests'])
    if summary['requests'] == 0:
        write_summary([head, '', 'Проверять нечего: нет сценариев и нет готовых операций в реестрах сервисов. Задание зелёное по построению, матрица и сценарии появятся вместе с обработчиками.'])
        return 0

    newman = shutil.which('newman')
    if not newman:
        print('Нет команды newman: npm install -g newman@<версия из docs/09-operations/versions.md>')
        return 1
    if args.sabotage:
        if not sabotage(collection):
            print('Нет ни одного случая отказа (403) в матрице: испортить нечего. Проверка проверки невозможна, пока нет готовой операции с запретом для роли')
            return 1
    collection_file = os.path.join(out, 'collection.json')
    with open(collection_file, 'w', encoding='utf-8') as f:
        json.dump(collection, f, ensure_ascii=False, indent=1)

    values, error = tokens_for(summary['roles'])
    if error:
        print(error)
        notice('error', 'API-тесты', error + ' (набор full с DEBUG=1 и make keycloak-users выполнены?)')
        return 1
    values['baseUrl'] = args.base
    env_file = os.path.join(out, 'environment.json')
    report = os.path.join(out, 'report.json')
    junit = os.path.join(out, 'junit.xml')
    fd = os.open(env_file, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'w', encoding='utf-8') as f:
        json.dump({'name': 'dgm-stand', 'values': [{'key': k, 'value': v, 'enabled': True} for k, v in values.items()]}, f)
    command = [newman, 'run', collection_file, '--environment', env_file, '--ssl-extra-ca-certs', args.ca, '--color', 'off',
               '--timeout-request', '20000', '--delay-request', '30',
               '--reporters', 'cli,json,junit', '--reporter-json-export', report, '--reporter-junit-export', junit]
    try:
        rc = subprocess.run(command).returncode
    finally:
        if os.path.exists(env_file):
            os.remove(env_file)  # в файле токены: после запуска он не нужен

    if not os.path.exists(report):
        notice('error', 'API-тесты', 'Newman не записал отчёт, код выхода %d' % rc)
        return rc or 1
    stats, failures, timings = parse_report(report)
    if args.sabotage:
        # Испорченное ожидание обязано дать упавшую проверку: иначе API-тесты ничего не проверяют
        if failures:
            print('проверка проверки ок: Newman покраснел от испорченного ожидания (упавших проверок %d)' % len(failures))
            return 0
        print('ПРОВЕРКА ПРОВЕРКИ НЕ ПРОЙДЕНА: ожидание испорчено, а Newman остался зелёным')
        notice('error', 'API-тесты', 'ожидание испорчено, а Newman остался зелёным: проверки ничего не проверяют')
        return 1
    requests, assertions = stats.get('requests', {}), stats.get('assertions', {})
    lines = [head, '',
             '| | всего | упало |', '| --- | --- | --- |',
             '| запросы | %d | %d |' % (requests.get('total', 0), requests.get('failed', 0)),
             '| проверки | %d | %d |' % (assertions.get('total', 0), assertions.get('failed', 0))]
    if failures:
        lines += ['', 'Упавшие проверки:'] + ['- ' + f for f in failures[:30]]
        notice('error', 'API-тесты', '\n'.join(failures[:12]))
    else:
        notice('notice', 'API-тесты', 'запросов %d, проверок %d, упавших нет' % (requests.get('total', 0), assertions.get('total', 0)))
    write_summary(lines)
    return 1 if failures or rc else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
