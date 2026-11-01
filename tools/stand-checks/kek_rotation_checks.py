#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Ротация мастер-ключа сквозь стенд (шаг 22 Ф4; TC-414, ADR-009, Р-174, runbook «Ротация мастер-ключа»).

Запуск: `make kek-rotation-check` (набор full с `make fast-up DEBUG=1`, после `make purchase-check`: в пуле есть ключи и есть хотя бы один ключ данных, DEK).
Проверка ведёт настоящий `tools/ops/kek_rotate.py` тем путём, которым пойдёт оператор, и дважды покупает ключ целиком (`purchase_checks.py`):

  до          ротации нет: все DEK на текущей версии N, секрета inventory_kek_next нет; запомнены номера и обёртки DEK, шифртексты значений, отпечаток
              inventory_kek и контейнер сервиса
  план        `start` без `--yes` ничего не меняет: ни секретов, ни .env, ни контейнера
  start       сервис пересоздан с двумя мастер-ключами и сообщил «версия N -> N+1, перезавёрнуто ключей данных K»; K равно числу DEK; все DEK на версии N+1;
              обёртка изменилась у каждого DEK (nonce новый), номера DEK, их статусы и шифртексты значений ключей прежние (значения не перешифровывались);
              inventory_kek не менялся
  покупка 1   заказ на ключи проходит до письма при двух мастер-ключах: DEK, который перезавернули, открывается новым ключом
  finish      DEK не осталось на версии N; inventory_kek теперь прежний следующий ключ, прежний лежит в secrets/archive/inventory_kek.vN с правами 0400,
              inventory_kek_next убран, в .env версия N+1 и rotate=false, сервис пересоздан с одним мастер-ключом и здоров
  канарейка   ни значение прежнего, ни значение нового мастер-ключа (и их Base64) не найдены в журналах контейнеров; контроль: строка о ротации в журнале на месте
  покупка 2   заказ проходит до письма, когда сервис знает только новый мастер-ключ: прежний после завершения не нужен
  после       `status`: ротации нет, все DEK на версии N+1

Только стандартная библиотека Python. Значения мастер-ключей проверка читает из файлов секретов только для сравнения отпечатков и поиска в журналах, не печатает.
"""
import base64
import hashlib
import os
import re
import stat
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(REPO, 'tools', 'ops'))
import gateway_checks as G  # noqa: E402
import kek_rotate as K  # noqa: E402

expect = G.expect
TOOL = os.path.join(REPO, 'tools', 'ops', 'kek_rotate.py')
PURCHASE = os.path.join(HERE, 'purchase_checks.py')


def sh(args, **kw):
    return subprocess.run(args, capture_output=True, text=True, errors='replace', cwd=REPO, **kw)


def sql(query):
    """Строки результата запроса к inventory_db (столбцы через «|») или None, если psql отказал."""
    r = sh(G.COMPOSE + ['exec', '-T', 'postgres', 'psql', '-U', 'postgres', '-d', 'inventory_db', '-tA', '-F', '|', '-c', query], timeout=60)
    if r.returncode != 0:
        return None
    return [line.split('|') for line in r.stdout.strip().splitlines() if line.strip()]


def deks():
    """{номер DEK: (версия мастер-ключа, md5 обёртки, статус)}: по md5 видно, что обёртка изменилась, не показывая её."""
    rows = sql('select id, kek_version, md5(wrapped_key), status from inventory.data_key order by id')
    return {int(r[0]): (int(r[1]), r[2], r[3]) for r in rows} if rows is not None else None


def values_digest():
    """Число ключей товаров и md5 по всем nonce и шифртекстам: если значения перешифровали, он изменится."""
    rows = sql("select count(*), coalesce(md5(string_agg(encode(nonce, 'hex') || encode(ciphertext, 'hex'), ',' order by id)), '') from inventory.key")
    return tuple(rows[0]) if rows else None


def secret_path(name):
    return os.path.join(K.secrets_dir(), name)


def sha(path):
    """Отпечаток файла секрета (None, если файла нет)."""
    if not os.path.isfile(path):
        return None
    with open(path, 'rb') as f:
        return hashlib.sha256(f.read()).hexdigest()


def read_secret(path):
    with open(path, encoding='utf-8') as f:
        return f.read()


def service_container():
    return sh(G.COMPOSE + ['ps', '-q', K.SERVICE], timeout=60).stdout.strip()


def tool(*args, timeout=420):
    # стенд для проверок поднят `make fast-up`: пересозданный сервис должен остаться с профилем fast-time, иначе сроки резерва в последующих покупках станут рабочими
    return sh([sys.executable, TOOL] + list(args) + ['--debug'], timeout=timeout, env=dict(os.environ, DGM_FAST_TIME='1'))


def purchase(label):
    """Покупка от каталога до письма с ключом целиком: те же проверки, что в `make purchase-check`."""
    r = sh([sys.executable, PURCHASE], timeout=900, env=dict(os.environ))
    tail = '\n'.join(r.stdout.strip().splitlines()[-3:])
    expect('%s: покупка до письма с ключом проходит (purchase_checks.py, код %d)' % (label, r.returncode), r.returncode == 0, tail + ' ' + r.stderr[-200:])


def log_text():
    return sh(G.COMPOSE + ['logs', '--no-color'], timeout=300).stdout


def main():
    version = K.current_version()
    before = deks()
    if not expect('до: ключи данных есть, все на версии %d (после make purchase-check в пуле есть ключи)' % version,
                  bool(before) and all(v[0] == version for v in before.values()), before):
        return summary()
    values = values_digest()
    kek_sha, container = sha(secret_path(K.KEK)), service_container()
    expect('до: секрета %s нет, %s есть, .env без включённой ротации' % (K.KEK_NEXT, K.KEK),
           not os.path.exists(secret_path(K.KEK_NEXT)) and kek_sha is not None and K.env_get(K.read_env_file(), 'DGM_KEK_ROTATE', 'false') != 'true')
    status = tool('status')
    expect('до: status: ротации нет', status.returncode == 0 and 'состояние: %s' % K.IDLE in status.stdout, status.stdout[-300:] + status.stderr[-200:])

    # --- план: без --yes ничего не меняется
    plan = tool('start')
    expect('план: start без --yes печатает план и ничего не делает', plan.returncode == 0 and 'Ничего не сделано' in plan.stdout, plan.stdout[-300:] + plan.stderr[-200:])
    expect('план: секретов, .env и контейнера не коснулись',
           not os.path.exists(secret_path(K.KEK_NEXT)) and service_container() == container and deks() == before)

    # --- start
    start = tool('start', '--yes')
    done = K.OUT_DONE.findall(start.stdout)
    expect('start: код 0 и сервис сообщил о ротации (версия %d -> %d, перезавёрнуто ключей данных %d)' % (version, version + 1, len(before)),
           start.returncode == 0 and done and done[-1] == (str(version), str(version + 1), str(len(before))), start.stdout[-400:] + start.stderr[-300:])
    middle = deks() or {}
    expect('start: все DEK на версии %d, номера и статусы прежние' % (version + 1),
           set(middle) == set(before) and all(m[0] == version + 1 and m[2] == before[i][2] for i, m in middle.items()), middle)
    expect('start: обёртка изменилась у каждого DEK (новый nonce, новый мастер-ключ)', set(middle) == set(before) and all(middle[i][1] != before[i][1] for i in middle))
    expect('start: шифртексты значений ключей не тронуты (%s ключей)' % (values[0] if values else '?'), values_digest() == values, values_digest())
    expect('start: inventory_kek прежний, %s лежит рядом, сервис пересоздан' % K.KEK_NEXT,
           sha(secret_path(K.KEK)) == kek_sha and sha(secret_path(K.KEK_NEXT)) is not None and service_container() != container)
    env_text = K.read_env_file()
    expect('start: в .env версия %d и rotate=true' % version, K.env_get(env_text, 'DGM_KEK_VERSION') == str(version) and K.env_get(env_text, 'DGM_KEK_ROTATE') == 'true')
    next_sha, old_value, new_value = sha(secret_path(K.KEK_NEXT)), None, None
    if os.path.isfile(secret_path(K.KEK)) and os.path.isfile(secret_path(K.KEK_NEXT)):
        old_value, new_value = read_secret(secret_path(K.KEK)), read_secret(secret_path(K.KEK_NEXT))
    expect('start: status: все DEK перешифрованы, можно завершать', 'состояние: %s' % K.REWRAPPED in tool('status').stdout)

    purchase('покупка 1 (два мастер-ключа)')
    # журнал контейнера, который выполнил ротацию: после finish сервис пересоздан, и его журнал исчезает вместе с контейнером
    rotating_logs = log_text()

    # --- finish
    plan = tool('finish')
    expect('план: finish без --yes ничего не делает', plan.returncode == 0 and 'Ничего не сделано' in plan.stdout and sha(secret_path(K.KEK)) == kek_sha, plan.stdout[-300:])
    finish = tool('finish', '--yes')
    expect('finish: код 0, ротация завершена', finish.returncode == 0 and 'ротация завершена' in finish.stdout, finish.stdout[-400:] + finish.stderr[-300:])
    archive = secret_path(os.path.join('archive', '%s.v%d' % (K.KEK, version)))
    expect('finish: inventory_kek стал прежним следующим, %s убран' % K.KEK_NEXT, sha(secret_path(K.KEK)) == next_sha and not os.path.exists(secret_path(K.KEK_NEXT)))
    expect('finish: прежний мастер-ключ лежит в secrets/archive/%s.v%d с правами 0400 и не изменился' % (K.KEK, version),
           sha(archive) == kek_sha and stat.S_IMODE(os.stat(archive).st_mode) == 0o400 if os.path.isfile(archive) else False)
    env_text = K.read_env_file()
    expect('finish: в .env версия %d и rotate=false' % (version + 1), K.env_get(env_text, 'DGM_KEK_VERSION') == str(version + 1) and K.env_get(env_text, 'DGM_KEK_ROTATE') == 'false')
    after = deks() or {}
    expect('finish: все DEK на версии %d, значения ключей не тронуты' % (version + 1),
           set(after) == set(before) and all(a[0] == version + 1 for a in after.values()) and values_digest() == values, after)

    # --- канарейка: мастер-ключей нет в журналах; контроль: строка о ротации на месте
    logs = rotating_logs + '\n' + log_text()
    witnesses = [v for v in (old_value, new_value) if v]
    needles = []
    for value in witnesses:
        needles += [value, value.strip('='), base64.b64decode(value).hex()]
    expect('канарейка: контроль, строка о ротации есть в журнале сервиса, который выполнил ротацию', bool(K.LOG_DONE.search(rotating_logs)))
    expect('канарейка: значения обоих мастер-ключей (как есть, без «=», в hex) не найдены в журналах контейнеров',
           len(witnesses) == 2 and not any(n in logs for n in needles), 'ключи не прочитаны' if len(witnesses) != 2 else 'значение мастер-ключа найдено в журнале')
    expect('канарейка: в журнале не напечатаны строки с обёрнутым ключом данных', not re.search(r'wrapped_key|wrappedKey', logs))

    purchase('покупка 2 (только новый мастер-ключ)')
    final = tool('status')
    expect('после: status: ротации нет, все DEK на версии %d' % (version + 1), final.returncode == 0 and 'состояние: %s' % K.IDLE in final.stdout, final.stdout[-300:])
    return summary()


def summary():
    print('\nПроверок успешно: %d, с ошибками: %d' % (G.PASSED, len(G.FAILED)))
    for failed in G.FAILED:
        print('  - %s' % failed)
    if os.environ.get('GITHUB_ACTIONS'):
        print('::notice title=kek_rotation_checks::Проверок успешно: %d, с ошибками: %d%s' % (G.PASSED, len(G.FAILED), ('; не прошли: ' + '|'.join(G.FAILED)) if G.FAILED else ''))
    return 1 if G.FAILED else 0


if __name__ == '__main__':
    sys.exit(main())
