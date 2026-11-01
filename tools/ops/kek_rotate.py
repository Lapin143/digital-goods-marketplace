#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Ротация мастер-ключа ключей товаров (KEK): три команды оператора (шаг 22 Ф4; ADR-009, Р-174; TC-414).

    tools/ops/kek_rotate.py status            где сейчас ротация: версия, секреты, сколько ключей данных (DEK) на какой версии
    tools/ops/kek_rotate.py start             показать, что будет сделано
    tools/ops/kek_rotate.py start  --yes      создать inventory_kek_next, перезапустить inventory-service с ротацией, дождаться перешифровки DEK
    tools/ops/kek_rotate.py finish            показать, что будет сделано
    tools/ops/kek_rotate.py finish --yes      сделать следующий мастер-ключ текущим, старый убрать в secrets/archive, перезапустить сервис

Что ротируется. Значения ключей товаров шифруются ключами данных (DEK), DEK хранятся в `inventory.data_key` зашифрованными мастер-ключом
(`inventory_kek`, ADR-009). Плановая смена мастер-ключа не трогает значения: сервис достаёт каждый DEK старым ключом и заворачивает новым,
в базе меняется несколько строк. Выдача ключей во время ротации не останавливается: сервис знает оба мастер-ключа, новые DEK
заворачивает новым.

Порядок и защита:
  1. `start` не делает ничего, если DEK лежат на версиях, которых скрипт не ждёт (`DGM_KEK_VERSION` не совпадает с базой или `inventory_kek`
     заменён без ротации), и не меняет текущий мастер-ключ. Повторный `start` безопасен: готовый `inventory_kek_next` не пересоздаётся.
  2. `finish` не делает ничего, пока в базе остаётся хоть один DEK на старой версии: иначе сервис после замены ключа не открыл бы его.
     Старый мастер-ключ не удаляется, а переносится в `secrets/archive/inventory_kek.v<N>`: он нужен, чтобы поднять резервную копию базы,
     снятую до ротации. Эту копию оператор хранит отдельно от резервных копий базы (ADR-009) столько же, сколько живут такие копии.
  3. Значения ключей скрипт не читает и не печатает, мастер-ключи тоже: в выводе только номера версий и числа.

Состояние между командами лежит в `.env` в корне репозитория (его читает `docker compose`, в Git он не попадает): `DGM_KEK_VERSION`
(версия текущего `inventory_kek`, 1, если строки нет) и `DGM_KEK_ROTATE` (`true` на время ротации). Секреты читаются из `DGM_SECRETS_DIR`
или из `secrets/`. Нужен только Docker и Python 3.
"""
import argparse
import base64
import os
import re
import secrets as pysecrets
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))

KEK = 'inventory_kek'
KEK_NEXT = 'inventory_kek_next'
OVERRIDE = 'compose.kek-rotation.yaml'
SERVICE = 'inventory-service'

QUERY = 'select kek_version, count(*) from inventory.data_key group by kek_version order by kek_version'
LOG_DONE = re.compile(r'Ротация мастер-ключа: версия (\d+) -> (\d+), перезавёрнуто ключей данных (\d+)')

# Состояния ротации (classify)
IDLE = 'idle'                  # ротации нет: все DEK на текущей версии, следующего ключа нет
PREPARED = 'prepared'          # следующий ключ положен, часть DEK ещё на текущей версии
REWRAPPED = 'rewrapped'        # следующий ключ положен, все DEK на следующей версии: можно завершать
BROKEN = 'broken'              # состояние, в котором скрипт ничего не меняет, пока оператор не разберётся


# ---------------------------------------------------------------------------------------------------------------------------
# Чистые функции: разбор .env и оценка состояния. Их проверяют модульные тесты без Docker
# ---------------------------------------------------------------------------------------------------------------------------
def env_get(text, key, default=None):
    """Значение переменной из текста `.env` (последняя строка выигрывает, как у docker compose)."""
    value = default
    for line in text.splitlines():
        m = re.match(r'^\s*%s\s*=\s*(.*?)\s*$' % re.escape(key), line)
        if m:
            value = m.group(1).strip('"\'')
    return value


def env_set(text, key, value):
    """Текст `.env` с заданной переменной: строка заменяется на месте, если её нет, добавляется в конец; остальное не меняется."""
    line = '%s=%s' % (key, value)
    out, done = [], False
    for old in text.splitlines():
        if re.match(r'^\s*%s\s*=' % re.escape(key), old):
            if not done:
                out.append(line)
                done = True
            continue
        out.append(old)
    if not done:
        out.append(line)
    return '\n'.join(out) + '\n'


def parse_counts(output):
    """Вывод `psql -tA -F '|'` -> {версия: число DEK}. Лишние и пустые строки пропускаются, нечисловые значения считаются ошибкой."""
    counts = {}
    for line in output.splitlines():
        line = line.strip()
        if not line:
            continue
        version, count = line.split('|')
        counts[int(version)] = int(count)
    return counts


def classify(version, counts, next_exists):
    """Где ротация. version: версия текущего inventory_kek; counts: {версия: число DEK}; next_exists: лежит ли inventory_kek_next.

    Возвращает (состояние, пояснение для оператора).
    """
    on_current = counts.get(version, 0)
    on_next = counts.get(version + 1, 0)
    others = sum(n for v, n in counts.items() if v not in (version, version + 1))
    if others:
        versions = ', '.join(str(v) for v in sorted(counts) if v not in (version, version + 1))
        return BROKEN, ('в базе есть DEK на версиях %s, а текущая версия мастер-ключа %d: DGM_KEK_VERSION в .env не совпадает с базой '
                        'или секрет inventory_kek заменён без ротации (runbook, раздел «Ротация мастер-ключа»)' % (versions, version))
    if not next_exists:
        if on_next:
            return BROKEN, ('%d DEK уже на версии %d, а секрета %s нет: он удалён раньше времени. Верните его из копии, '
                            'иначе сервис не откроет эти ключи' % (on_next, version + 1, KEK_NEXT))
        return IDLE, 'ротации нет: все DEK (%d) на версии %d' % (on_current, version)
    if on_current:
        return PREPARED, '%s положен, на версии %d ещё %d DEK, на версии %d уже %d' % (KEK_NEXT, version, on_current, version + 1, on_next)
    return REWRAPPED, 'все DEK (%d) на версии %d, можно завершать ротацию' % (on_next, version + 1)


def new_key():
    """Новый мастер-ключ в том же виде, что делает infra/pki/dgm_pki.py: 256 бит, base64, без перевода строки."""
    return base64.b64encode(pysecrets.token_bytes(32)).decode()


# ---------------------------------------------------------------------------------------------------------------------------
# Работа с окружением
# ---------------------------------------------------------------------------------------------------------------------------
def secrets_dir():
    return os.environ.get('DGM_SECRETS_DIR') or os.path.join(REPO, 'secrets')


def secret_mode():
    """Права файлов секретов те же, что у остальных (контейнер читает под другим пользователем): 0444 по умолчанию."""
    return int(os.environ.get('DGM_SECRET_MODE', '0444'), 8)


def write_secret(path, data, mode):
    if os.path.lexists(path):
        os.unlink(path)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w', encoding='utf-8', newline='\n') as f:
        f.write(data)
    os.chmod(path, mode)


def env_path():
    return os.path.join(REPO, '.env')


def read_env_file():
    try:
        with open(env_path(), encoding='utf-8') as f:
            return f.read()
    except FileNotFoundError:
        return ''


def write_env_file(text):
    with open(env_path(), 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)


def current_version():
    raw = env_get(read_env_file(), 'DGM_KEK_VERSION', '1')
    if not re.match(r'^[1-9][0-9]*$', raw):
        sys.exit('DGM_KEK_VERSION в .env должна быть целым числом от 1, сейчас «%s»' % raw)
    return int(raw)


def compose(args, rotation=False, debug=False):
    """Командная строка docker compose: все профили включены, чтобы видеть и postgres, и inventory-service независимо от набора стенда."""
    base = ['docker', 'compose', '-f', 'compose.yaml']
    if debug:
        base += ['-f', 'compose.debug.yaml']
    if rotation:
        base += ['-f', OVERRIDE]
    return base + ['--profile', '*'] + args


def run(cmd, timeout=180, check=True):
    r = subprocess.run(cmd, cwd=REPO, stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, timeout=timeout)
    if check and r.returncode != 0:
        sys.exit('команда «%s» завершилась с кодом %d: %s' % (' '.join(cmd[:6]), r.returncode, (r.stderr or r.stdout).strip()[-400:]))
    return r


def db_counts(debug):
    r = run(compose(['exec', '-T', 'postgres', 'psql', '-U', 'postgres', '-d', 'inventory_db', '-tA', '-F', '|', '-v', 'ON_ERROR_STOP=1', '-c', QUERY],
                    debug=debug))
    return parse_counts(r.stdout)


def snapshot(debug):
    version = current_version()
    next_exists = os.path.isfile(os.path.join(secrets_dir(), KEK_NEXT))
    counts = db_counts(debug)
    state, why = classify(version, counts, next_exists)
    return version, next_exists, counts, state, why


def wait(what, ready, seconds):
    """Опрос раз в 2 секунды: ready() возвращает значение, когда условие выполнено, и None, пока нет."""
    deadline = time.time() + seconds
    while time.time() < deadline:
        value = ready()
        if value is not None:
            return value
        time.sleep(2)
    sys.exit('не дождались: %s (%d с). Журнал сервиса: docker compose logs --tail 80 %s' % (what, seconds, SERVICE))


def restart_service(debug, rotation):
    run(compose(['up', '-d', '--no-deps', '--force-recreate', SERVICE], rotation=rotation, debug=debug), timeout=300)


def service_healthy(debug, rotation):
    cid = run(compose(['ps', '-q', SERVICE], rotation=rotation, debug=debug), check=False).stdout.strip()
    if not cid:
        return None
    r = run(['docker', 'inspect', '-f', '{{.State.Health.Status}}', cid], check=False)
    return True if r.stdout.strip() == 'healthy' else None


# ---------------------------------------------------------------------------------------------------------------------------
# Команды
# ---------------------------------------------------------------------------------------------------------------------------
def print_state(version, next_exists, counts, state, why):
    sd = secrets_dir()
    print('версия текущего мастер-ключа (DGM_KEK_VERSION): %d' % version)
    print('секреты в %s: %s %s, %s %s' % (sd, KEK, 'есть' if os.path.isfile(os.path.join(sd, KEK)) else 'НЕТ',
                                          KEK_NEXT, 'есть' if next_exists else 'нет'))
    print('ключи данных (DEK) по версиям мастер-ключа: %s' % (', '.join('версия %d: %d' % (v, n) for v, n in sorted(counts.items())) or 'нет'))
    print('состояние: %s. %s' % (state, why))


def cmd_status(args):
    snap = snapshot(args.debug)
    print_state(*snap)
    state = snap[3]
    hint = {IDLE: 'Начать ротацию: tools/ops/kek_rotate.py start --yes',
            PREPARED: 'Перешифровка не закончена: tools/ops/kek_rotate.py start --yes (повтор безопасен)',
            REWRAPPED: 'Завершить: tools/ops/kek_rotate.py finish --yes',
            BROKEN: 'Скрипт ничего не меняет, пока состояние не разобрано (runbook, раздел «Ротация мастер-ключа»)'}[state]
    print(hint)
    return 0 if state != BROKEN else 2


def cmd_start(args):
    version, next_exists, counts, state, why = snapshot(args.debug)
    print_state(version, next_exists, counts, state, why)
    if state == BROKEN:
        print('start остановлен: состояние не разобрано', file=sys.stderr)
        return 2
    if state == REWRAPPED:
        print('перешифровка уже сделана, дальше: tools/ops/kek_rotate.py finish --yes')
        return 0
    print('\nБудет сделано:')
    if not next_exists:
        print('  1. создан секрет %s/%s (256 бит, права %04o)' % (secrets_dir(), KEK_NEXT, secret_mode()))
    else:
        print('  1. секрет %s уже лежит, остаётся как есть' % KEK_NEXT)
    print('  2. в .env: DGM_KEK_VERSION=%d, DGM_KEK_ROTATE=true' % version)
    print('  3. %s пересоздан с %s (мастер-ключи версий %d и %d), при старте DEK перезавёрнуты версией %d' % (SERVICE, OVERRIDE, version, version + 1, version + 1))
    print('  Выдача ключей на это время кратко прерывается (перезапуск сервиса), значения ключей не меняются.')
    if not args.yes:
        print('\nНичего не сделано. Чтобы выполнить, добавьте --yes')
        return 0
    if not next_exists:
        os.makedirs(secrets_dir(), exist_ok=True)
        write_secret(os.path.join(secrets_dir(), KEK_NEXT), new_key(), secret_mode())
        print('создан %s' % KEK_NEXT)
    text = env_set(env_set(read_env_file(), 'DGM_KEK_VERSION', version), 'DGM_KEK_ROTATE', 'true')
    write_env_file(text)
    restart_service(args.debug, rotation=True)

    def done():
        logs = run(compose(['logs', '--no-color', SERVICE], rotation=True, debug=args.debug), check=False).stdout
        found = LOG_DONE.findall(logs)
        return found[-1] if found else None
    old, new, rewrapped = wait('строки о ротации в журнале %s' % SERVICE, done, args.timeout)
    print('сервис сообщил: версия %s -> %s, перезавёрнуто ключей данных %s' % (old, new, rewrapped))
    counts = wait('DEK на версии %d' % (version + 1), lambda: (lambda c: c if not c.get(version) else None)(db_counts(args.debug)), 30)
    print('ключи данных: %s' % ', '.join('версия %d: %d' % (v, n) for v, n in sorted(counts.items())))
    wait('здоровый %s' % SERVICE, lambda: service_healthy(args.debug, True), args.timeout)
    print('сервис здоров с двумя мастер-ключами')
    print('дальше: tools/ops/kek_rotate.py finish --yes')
    return 0


def cmd_finish(args):
    version, next_exists, counts, state, why = snapshot(args.debug)
    print_state(version, next_exists, counts, state, why)
    if state != REWRAPPED:
        print('finish остановлен: завершать можно только когда все DEK уже на версии %d (состояние %s)' % (version + 1, REWRAPPED), file=sys.stderr)
        return 2
    archive = os.path.join(secrets_dir(), 'archive')
    old_copy = os.path.join(archive, '%s.v%d' % (KEK, version))
    print('\nБудет сделано:')
    print('  1. старый мастер-ключ перенесён в %s (хранить отдельно от копий базы, пока живут копии, снятые до ротации)' % old_copy)
    print('  2. %s становится %s, %s удалён' % (KEK_NEXT, KEK, KEK_NEXT))
    print('  3. в .env: DGM_KEK_VERSION=%d, DGM_KEK_ROTATE=false' % (version + 1))
    print('  4. %s пересоздан с одним мастер-ключом, проверено, что он здоров' % SERVICE)
    if not args.yes:
        print('\nНичего не сделано. Чтобы выполнить, добавьте --yes')
        return 0
    sd = secrets_dir()
    with open(os.path.join(sd, KEK), encoding='utf-8') as f:
        old_value = f.read()
    with open(os.path.join(sd, KEK_NEXT), encoding='utf-8') as f:
        next_value = f.read()
    if os.path.exists(old_copy):
        sys.exit('%s уже существует: прошлая ротация этой версии не доведена до конца, разберите вручную' % old_copy)
    os.makedirs(archive, exist_ok=True)
    os.chmod(archive, 0o700)
    write_secret(old_copy, old_value, 0o400)
    write_secret(os.path.join(sd, KEK), next_value, secret_mode())
    os.unlink(os.path.join(sd, KEK_NEXT))
    write_env_file(env_set(env_set(read_env_file(), 'DGM_KEK_VERSION', version + 1), 'DGM_KEK_ROTATE', 'false'))
    restart_service(args.debug, rotation=False)
    wait('здоровый %s' % SERVICE, lambda: service_healthy(args.debug, False), args.timeout)
    print('ротация завершена: мастер-ключ версии %d' % (version + 1))
    print_state(*snapshot(args.debug))
    return 0


def main(argv=None):
    p = argparse.ArgumentParser(description='Ротация мастер-ключа ключей товаров (ADR-009)')
    sub = p.add_subparsers(dest='cmd', required=True)
    for name, fn in (('status', cmd_status), ('start', cmd_start), ('finish', cmd_finish)):
        s = sub.add_parser(name)
        s.add_argument('--debug', action='store_true', help='использовать compose.debug.yaml, как make DEBUG=1')
        if name != 'status':
            s.add_argument('--yes', action='store_true', help='выполнить, без него только показать план')
            s.add_argument('--timeout', type=int, default=180, help='сколько секунд ждать сервис, по умолчанию 180')
        s.set_defaults(fn=fn)
    args = p.parse_args(argv)
    return args.fn(args)


if __name__ == '__main__':
    sys.exit(main())
