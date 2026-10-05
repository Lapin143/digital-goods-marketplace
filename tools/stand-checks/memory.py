#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Замеры памяти контейнеров стенда: пик за время проверок против лимита (шаг 18 Ф3, memory-budget.md, раздел 5).

    python3 tools/stand-checks/memory.py start ФАЙЛ            запустить замеры в фоне (раз в 3 секунды docker stats, пока не вызван stop)
    python3 tools/stand-checks/memory.py stop ФАЙЛ [ключи]     остановить замеры и напечатать итог
    python3 tools/stand-checks/memory.py report ФАЙЛ [ключи]   итог по уже записанному файлу

    ключи итога:  --rule 85      доля лимита, выше которой пик считается нарушением правила (по умолчанию 85)
                  --label ТЕКСТ  подпись набора в аннотации CI
                  --strict       код выхода 1, если у какого-либо контейнера пик выше правила
                  --md ФАЙЛ      таблица в формате Markdown для документа замеров

Что измеряется. docker stats показывает рабочий набор контейнера: использование памяти cgroup минус неактивный кэш файлов. Это то, что ядро не может
отдать при нехватке: превышение лимита рабочим набором ведёт к OOM (контейнер убивается, своп отключён). Пик берётся как наибольшее значение из замеров за всё
время между start и stop, то есть за проверки стенда, дымовой тест и короткую нагрузку, а не за один момент после них. Для справки печатается
memory.peak из cgroup (включает кэш файлов, ядро его вытесняет при нехватке памяти, поэтому правилом он не является). У масштабированных сервисов
(два экземпляра) берётся больший из экземпляров. Только стандартная библиотека Python, нужен docker.
"""
import json
import os
import re
import signal
import subprocess
import sys
import time

INTERVAL = 3.0
UNITS = {'b': 1, 'kib': 1024, 'mib': 1024 ** 2, 'gib': 1024 ** 3, 'kb': 1000, 'mb': 1000 ** 2, 'gb': 1000 ** 3}
NO_LIMIT_MIB = 64 * 1024        # лимит больше этого значения это лимит хоста, то есть лимит не задан


def mib(text):
    m = re.match(r'^\s*([0-9.]+)\s*([A-Za-z]+)\s*$', text)
    if not m or m.group(2).lower() not in UNITS:
        return None
    return float(m.group(1)) * UNITS[m.group(2).lower()] / 1024 ** 2


def service_of(container):
    """dgm-order-service-2 -> order-service."""
    return re.sub(r'-\d+$', '', re.sub(r'^/?dgm-', '', container))


def snapshot():
    r = subprocess.run(['docker', 'stats', '--no-stream', '--format', '{{json .}}'], capture_output=True, text=True, timeout=60)
    out = {}
    for line in r.stdout.splitlines():
        try:
            row = json.loads(line)
            used, _, limit = row['MemUsage'].partition('/')
        except (ValueError, KeyError):
            continue
        u, l = mib(used), mib(limit)
        if u is not None and l is not None and l < NO_LIMIT_MIB:
            out[row['Name']] = [round(u, 1), round(l, 1)]
    return out


def sample(path):
    stop = []
    signal.signal(signal.SIGTERM, lambda *_: stop.append(1))
    signal.signal(signal.SIGINT, lambda *_: stop.append(1))
    with open(path, 'a', encoding='utf-8') as f:
        while not stop:
            started = time.time()
            try:
                row = {'t': round(started, 1), 'm': snapshot()}
                f.write(json.dumps(row) + '\n')
                f.flush()
            except (subprocess.SubprocessError, OSError) as e:
                with open(path + '.err', 'a', encoding='utf-8') as err:
                    err.write('%s: %s\n' % (type(e).__name__, str(e)[:200]))
            time.sleep(max(0.2, INTERVAL - (time.time() - started)))
    return 0


def start(path):
    open(path, 'w').close()
    p = subprocess.Popen([sys.executable, os.path.abspath(__file__), 'sample', path], start_new_session=True,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    with open(path + '.pid', 'w') as f:
        f.write(str(p.pid))
    for _ in range(100):
        time.sleep(0.2)
        if os.path.getsize(path) > 0 or p.poll() is not None:
            break
    state = 'работает' if p.poll() is None else 'завершился с кодом %s' % p.returncode
    first = open(path, encoding='utf-8').readline()
    count = len(json.loads(first)['m']) if first.strip() else 0
    print('замеры памяти запущены (pid %d, %s), файл %s, в первом замере контейнеров: %d' % (p.pid, state, path, count))
    return 0


def stop(path):
    try:
        pid = int(open(path + '.pid').read())
        os.kill(pid, signal.SIGTERM)
        for _ in range(100):
            time.sleep(0.1)
            try:
                os.kill(pid, 0)
            except OSError:
                break
    except (OSError, ValueError):
        print('замеры не были запущены или уже остановлены')
    return 0


def cgroup_peaks():
    """memory.peak из cgroup v2 по контейнерам, работающим сейчас. Пусто, если читать нечего (другая ОС, нет прав)."""
    peaks = {}
    try:
        r = subprocess.run(['docker', 'ps', '--no-trunc', '--format', '{{.ID}} {{.Names}}'], capture_output=True, text=True)
    except OSError:
        return peaks
    for line in r.stdout.splitlines():
        cid, _, name = line.partition(' ')
        for base in ('/sys/fs/cgroup/system.slice/docker-%s.scope', '/sys/fs/cgroup/docker/%s'):
            try:
                value = int(open((base % cid) + '/memory.peak').read())
            except (OSError, ValueError):
                continue
            peaks[name] = round(value / 1024 ** 2, 1)
            break
    return peaks


def aggregate(path):
    """{сервис: {'peak', 'limit', 'at', 'n', 'avg'}} по файлу замеров; at секунды от первого замера."""
    rows = []
    for line in open(path, encoding='utf-8'):
        try:
            rows.append(json.loads(line))
        except ValueError:
            continue
    result, first = {}, rows[0]['t'] if rows else 0
    for row in rows:
        for name, (used, limit) in row['m'].items():
            s = result.setdefault(service_of(name), {'peak': 0.0, 'limit': limit, 'at': 0, 'n': 0, 'sum': 0.0})
            s['n'] += 1
            s['sum'] += used
            s['limit'] = limit
            if used > s['peak']:
                s['peak'], s['at'] = used, row['t'] - first
    for s in result.values():
        s['avg'] = s['sum'] / s['n']
    return result, len(rows), (rows[-1]['t'] - first if rows else 0)


def report(path, rule, label, strict, md):
    if not os.path.exists(path):
        return failed('нет файла замеров %s' % path)
    data, count, span = aggregate(path)
    if not data:
        errors = open(path + '.err', encoding='utf-8').read()[-300:] if os.path.exists(path + '.err') else ''
        return failed('в файле %s замеров с данными нет (строк %d). Ошибки сборщика: %s' % (path, count, errors or 'нет'))
    cg = cgroup_peaks()
    cg_by_service = {}
    for name, value in cg.items():
        cg_by_service[service_of(name)] = max(value, cg_by_service.get(service_of(name), 0))
    lines, over = [], []
    print('Замеров: %d за %d с, правило: пик не выше %d %% лимита' % (count, span, rule))
    print('%-20s %8s %8s %8s %8s %10s  %s' % ('контейнер', 'лимит', 'пик', 'средн.', '% лим.', 'cgroup', 'вывод'))
    table = []
    for name, s in sorted(data.items(), key=lambda kv: -kv[1]['peak'] / kv[1]['limit']):
        pct = 100.0 * s['peak'] / s['limit']
        verdict = 'выше правила' if pct > rule else 'ок'
        if pct > rule:
            over.append((name, s, pct))
        peak_cg = cg_by_service.get(name)
        print('%-20s %8.0f %8.1f %8.1f %7.1f%% %10s  %s' % (name, s['limit'], s['peak'], s['avg'], pct, '%.0f' % peak_cg if peak_cg else 'н/д', verdict))
        lines.append('%s: пик %.0f из %.0f МБ (%.0f%%)%s' % (name, s['peak'], s['limit'], pct, ', выше %d%%' % rule if pct > rule else ''))
        table.append((name, s, pct, peak_cg))
    if md:
        with open(md, 'w', encoding='utf-8') as f:
            f.write('| Контейнер | Лимит, МБ | Пик, МБ | В среднем, МБ | Пик, % лимита | memory.peak cgroup, МБ |\n| --- | --- | --- | --- | --- | --- |\n')
            for name, s, pct, peak_cg in table:
                f.write('| `%s` | %.0f | %.0f | %.0f | %.0f | %s |\n' % (name, s['limit'], s['peak'], s['avg'], pct, '%.0f' % peak_cg if peak_cg else 'н/д'))
    if os.environ.get('GITHUB_ACTIONS'):
        msg = '%0A'.join(lines)
        print('::notice title=память%s; пик за %d с (%d замеров)::%s' % (' ' + label.replace(',', ';').replace(':', ' ') if label else '', span, count, msg))
        if over:
            print('::warning title=память выше правила %d%%::%s' % (rule, '%0A'.join('%s: пик %.0f из %.0f МБ, нужен лимит не меньше %d МБ' % (
                n, s['peak'], s['limit'], needed(s['peak'], rule)) for n, s, _ in over)))
    for name, s, pct in over:
        print('  выше правила: %s, пик %.0f МБ из %.0f, лимит по правилу не меньше %d МБ' % (name, s['peak'], s['limit'], needed(s['peak'], rule)))
    return 1 if (strict and over) else 0


def failed(text):
    print(text)
    if os.environ.get('GITHUB_ACTIONS'):
        print('::warning title=memory.py::%s' % text.replace('%', '%25').replace('\n', '%0A'))
    return 1


def needed(peak, rule):
    """Лимит, при котором пик занимает не больше rule процентов: пик, делённый на долю, округлённый вверх до 32 МБ."""
    import math
    return int(math.ceil(peak / (rule / 100.0) / 32.0) * 32)


def main(argv):
    if len(argv) < 3 or argv[1] not in ('start', 'stop', 'sample', 'report'):
        print(__doc__)
        return 64
    cmd, path = argv[1], argv[2]
    opts = argv[3:]

    def opt(name, default=None):
        return opts[opts.index(name) + 1] if name in opts else default

    if cmd == 'sample':
        return sample(path)
    if cmd == 'start':
        return start(path)
    if cmd == 'stop':
        stop(path)
    try:
        return report(path, int(opt('--rule', 85)), opt('--label', ''), '--strict' in opts, opt('--md'))
    except Exception as e:  # noqa: BLE001 итог не должен ронять задание CI молча
        return failed('итог замеров не получен: %s: %s' % (type(e).__name__, e))


if __name__ == '__main__':
    sys.exit(main(sys.argv))
