#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Проверка стенда целиком (шаг 15 Ф3: «всё поднимается одной командой», NFT-3.2, NFT-3.3, NFT-6.0).

    python3 tools/stand-checks/stand_checks.py up [профили]    после make up: состав, готовность, порты, сети, секреты, память
    python3 tools/stand-checks/stand_checks.py down            остановка и сброс: make down оставляет тома, make reset убирает всё

Профили по умолчанию: все профили R1 (набор full). Отладочный файл учитывается, если задана переменная DEBUG (как в Makefile).
Только стандартная библиотека Python.
"""
import glob
import json
import os
import re
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
SECRETS = os.environ.get('DGM_SECRETS_DIR', os.path.join(ROOT, 'secrets'))
FULL = 'infra,stubs,auth,gateway,purchase,platform,storage'
FULL_OBS = FULL + ',obs'
ONE_SHOT = {'kafka-init', 'storage-init'}
NO_HEALTH = {'loki', 'tempo'}       # образы без оболочки: проверка готовности Docker невозможна, готовность проверяет make obs-check
VOLUMES = ['dgm_pgdata', 'dgm_kafkadata', 'dgm_objectdata', 'dgm_promdata', 'dgm_grafanadata', 'dgm_lokidata', 'dgm_tempodata']

PASSED, FAILED = 0, []


def ok(name):
    global PASSED
    PASSED += 1
    print('  ok    %s' % name)


def bad(name, details=''):
    FAILED.append(name)
    print('  ОШИБКА %s' % name)
    if details:
        print('        | %s' % str(details)[:500].replace('\n', ' '))


def expect(name, cond, details=''):
    ok(name) if cond else bad(name, details)
    return bool(cond)


def run(args, env=None, check=False):
    e = dict(os.environ)
    e.update(env or {})
    return subprocess.run(args, capture_output=True, text=True, env=e, cwd=ROOT)


def compose_files():
    files = ['-f', 'compose.yaml']
    if os.environ.get('DEBUG'):
        files += ['-f', 'compose.debug.yaml']
    return ['docker', 'compose'] + files


def parse_ps(text):
    text = text.strip()
    if not text:
        return []
    if text.startswith('['):
        return json.loads(text)
    return [json.loads(line) for line in text.splitlines() if line.strip()]


def containers():
    r = run(compose_files() + ['--profile', '*', 'ps', '-a', '--format', 'json'])
    return {c['Service']: c for c in parse_ps(r.stdout)}


def inspect(cid):
    r = run(['docker', 'inspect', cid])
    return json.loads(r.stdout)[0] if r.returncode == 0 and r.stdout.strip() else {}


def to_mib(text):
    m = re.match(r'([0-9.]+)\s*([KMG]i?B)', text.strip())
    if not m:
        return 0.0
    return float(m.group(1)) * {'KiB': 1 / 1024, 'KB': 1 / 1024, 'MiB': 1, 'MB': 1, 'GiB': 1024, 'GB': 1024}[m.group(2)]


def debug_ports():
    path = os.path.join(ROOT, 'compose.debug.yaml')
    if not os.environ.get('DEBUG') or not os.path.exists(path):
        return set()
    return set(re.findall(r'"127\.0\.0\.1:(\d+):\d+"', open(path, encoding='utf-8').read()))


# --------------------------------------------------------------------------------------------------- up
def up(profiles):
    print('== Состав и готовность (профили: %s)' % profiles)
    r = run(compose_files() + ['config', '--services'], {'COMPOSE_PROFILES': profiles})
    wanted = sorted(s for s in r.stdout.split() if s)
    have = containers()
    expect('Compose знает контейнеры набора: %d' % len(wanted), r.returncode == 0 and wanted, r.stderr)
    missing = [s for s in wanted if s not in have]
    expect('все контейнеры набора созданы', not missing, 'нет: %s' % missing)
    if profiles == FULL:
        expect('набор full: 16 контейнеров (14 постоянных и 2 разовых задания)', len(wanted) == 16, '%d: %s' % (len(wanted), wanted))
    if profiles == FULL_OBS:
        expect('набор full-obs: 22 контейнера (20 постоянных и 2 разовых задания)', len(wanted) == 22, '%d: %s' % (len(wanted), wanted))

    not_ready = []
    for s in wanted:
        c = have.get(s)
        if not c:
            continue
        if s in ONE_SHOT:
            good = c.get('State') == 'exited' and c.get('ExitCode') == 0
        else:
            good = c.get('State') == 'running' and c.get('Health') in ('healthy', '')
        if not good:
            not_ready.append('%s: %s/%s/%s' % (s, c.get('State'), c.get('Health'), c.get('ExitCode')))
    expect('каждый постоянный контейнер healthy, разовые задания завершились с кодом 0', not not_ready, not_ready)
    no_check = [s for s in wanted if s not in ONE_SHOT and s not in NO_HEALTH and have.get(s, {}).get('Health') == '']
    expect('у каждого постоянного контейнера есть проверка готовности (кроме образов без оболочки: %s)' % ', '.join(sorted(NO_HEALTH)),
           not no_check, 'без проверки: %s' % no_check)

    restarts, oom = [], []
    for s in wanted:
        c = have.get(s)
        info = inspect(c['ID']) if c else {}
        if (info.get('RestartCount') or 0) > 0:
            restarts.append(s)
        if (info.get('State') or {}).get('OOMKilled'):
            oom.append(s)
    expect('перезапусков нет', not restarts, restarts)
    expect('ни один контейнер не убит по памяти (OOMKilled)', not oom, oom)

    print('== Порты и сети (NFT-3.3)')
    published = {}
    for s in wanted:
        c = have.get(s)
        if not c or c.get('State') != 'running':
            continue
        ports = (inspect(c['ID']).get('NetworkSettings') or {}).get('Ports') or {}
        for inner, binds in ports.items():
            for b in binds or []:
                published.setdefault(s, set()).add('%s:%s' % (b['HostIp'], b['HostPort']))
    debug = debug_ports()
    extra = []
    for s, ports in published.items():
        for p in ports:
            host, _, port = p.rpartition(':')
            if s == 'api-gateway' and port == '8443' and host in ('0.0.0.0', '::', ''):
                continue
            if host == '127.0.0.1' and port in debug:
                continue
            extra.append('%s %s' % (s, p))
    expect('на хост опубликован только порт шлюза 8443%s' % (' и отладочные 127.0.0.1' if debug else ''), not extra, extra)

    on_edge = []
    for s in wanted:
        c = have.get(s)
        nets = (inspect(c['ID']).get('NetworkSettings') or {}).get('Networks') or {} if c else {}
        if 'dgm_edge' in nets:
            on_edge.append(s)
    expect('в сети edge только api-gateway', on_edge == ['api-gateway'] if 'api-gateway' in wanted else not on_edge, on_edge)
    data_net = inspect_network('dgm_data')
    expect('сеть data закрыта для выхода наружу (internal)', data_net.get('Internal') is True, data_net.get('Internal'))
    data_members = sorted({v['Name'].replace('dgm-', '').rsplit('-', 1)[0] for v in (data_net.get('Containers') or {}).values()})
    outsiders = [m for m in data_members if m in ('web-app', 'external-stubs')]
    expect('в сети data нет веб-интерфейса и заглушек: %s' % ', '.join(data_members), not outsiders, outsiders)

    obs_net = inspect_network('dgm_obs')       # сеть создаётся, когда запущен хотя бы один сервис Java или контейнер стека наблюдения
    if obs_net:
        expect('сеть obs закрыта для выхода наружу (internal)', obs_net.get('Internal') is True, obs_net.get('Internal'))
        obs_members = sorted({v['Name'].replace('dgm-', '').rsplit('-', 1)[0] for v in (obs_net.get('Containers') or {}).values()})
        outsiders = [m for m in obs_members if m in ('web-app', 'keycloak', 'external-stubs', 'postgres', 'redis', 'kafka', 'object-storage')]
        expect('в сети obs только сервисы Java и стек наблюдения: %s' % ', '.join(obs_members), not outsiders, outsiders)

    print('== Секреты (NFT-3.2)')
    values = secret_values()
    expect('найдены значения секретов для проверки: %d' % len(values), len(values) >= 20, len(values))
    leaked = []
    dump = {}
    for s in wanted:
        c = have.get(s)
        if not c:
            continue
        dump[s] = json.dumps(inspect(c['ID']), ensure_ascii=False)
    for name, v in values.items():
        for s, text in dump.items():
            if v in text:
                leaked.append('%s в настройках %s' % (name, s))
    expect('значения секретов не встречаются в окружении и настройках контейнеров', not leaked, leaked[:5])
    logs = run(compose_files() + ['--profile', '*', 'logs', '--no-color']).stdout
    leaked = [name for name, v in values.items() if v in logs]
    expect('значения секретов не встречаются в журналах контейнеров', not leaked, leaked[:5])

    print('== Память')
    stats = run(['docker', 'stats', '--no-stream', '--format', '{{json .}}']).stdout
    total, over = 0.0, []
    for line in stats.splitlines():
        if not line.strip():
            continue
        o = json.loads(line)
        used, _, limit = o['MemUsage'].partition('/')
        used_mib, limit_mib = to_mib(used), to_mib(limit)
        total += used_mib
        pct = used_mib / limit_mib * 100 if limit_mib else 0
        print('  %-28s %7.1f МиБ из %5.0f (%4.1f%%)' % (o['Name'], used_mib, limit_mib, pct))
        if pct > 95:
            over.append('%s %.0f%%' % (o['Name'], pct))
    print('  Всего %.0f МиБ' % total)
    expect('ни один контейнер не занял больше 95%% лимита после запуска', not over, over)
    if os.environ.get('GITHUB_ACTIONS'):
        print('::notice title=память набора %s::всего %.0f МиБ по docker stats' % (profiles.replace(',', '+'), total))


def inspect_network(name):
    r = run(['docker', 'network', 'inspect', name])
    try:
        return json.loads(r.stdout)[0]
    except (ValueError, IndexError):
        return {}


def secret_values():
    """Значения секретов-паролей и ключей: файлы без расширений сертификатов, не короче 16 знаков (короткие дали бы случайные совпадения)."""
    out = {}
    for path in sorted(glob.glob(os.path.join(SECRETS, '*'))):
        name = os.path.basename(path)
        if name.endswith(('.crt', '.key', '.srl', '.json', '.pem', '.csr')) or name.startswith('tls_') or not os.path.isfile(path):
            continue
        try:
            value = open(path, encoding='utf-8').read().strip()
        except (OSError, UnicodeDecodeError):
            continue
        if len(value) >= 16 and '\n' not in value:
            out[name] = value
    return out


# --------------------------------------------------------------------------------------------------- down
def down():
    print('== Остановка (make down): контейнеры и сети удаляются, тома и секреты остаются')
    before = [v for v in VOLUMES if run(['docker', 'volume', 'inspect', v]).returncode == 0]
    r = run(['make', '--no-print-directory', 'down'])
    expect('make down завершился без ошибок', r.returncode == 0, r.stderr[-300:])
    left = run(['docker', 'ps', '-a', '-q', '--filter', 'label=com.docker.compose.project=dgm']).stdout.split()
    expect('контейнеров проекта не осталось', not left, left)
    nets = run(['docker', 'network', 'ls', '-q', '--filter', 'name=^dgm_']).stdout.split()
    expect('сетей проекта не осталось', not nets, nets)
    kept = [v for v in VOLUMES if run(['docker', 'volume', 'inspect', v]).returncode == 0]
    expect('тома с данными сохранены (%s)' % ', '.join(kept), kept == before and bool(before), '%s -> %s' % (before, kept))
    expect('секреты и сертификаты на месте', os.path.exists(os.path.join(SECRETS, 'tls_ca.crt')) and len(secret_values()) >= 20, SECRETS)

    print('== Сброс (make reset): тома тоже удаляются')
    r = run(['make', '--no-print-directory', 'reset'])
    expect('make reset завершился без ошибок', r.returncode == 0, r.stderr[-300:])
    vols = run(['docker', 'volume', 'ls', '-q', '--filter', 'name=^dgm_']).stdout.split()
    expect('томов проекта не осталось', not vols, vols)


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else 'up'
    if mode == 'up':
        up(sys.argv[2] if len(sys.argv) > 2 else FULL)
    elif mode == 'down':
        down()
    else:
        print(__doc__)
        return 2
    print()
    print('Проверок успешно: %d, с ошибками: %d' % (PASSED, len(FAILED)))
    if os.environ.get('GITHUB_ACTIONS'):
        print('::notice title=stand_checks %s::Проверок успешно: %d, с ошибками: %d%s' % (
            mode, PASSED, len(FAILED), ('; не прошли: ' + ' | '.join(FAILED)) if FAILED else ''))
    for f in FAILED:
        print('  - %s' % f)
    return 1 if FAILED else 0


if __name__ == '__main__':
    sys.exit(main())
