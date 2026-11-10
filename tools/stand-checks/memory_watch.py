# -*- coding: utf-8 -*-
"""Пик памяти сервисов за время сценариев проверки стенда (шаг 13 Ф5, К15; шаг 16 Ф5, К16, Р-196).

    watch = MemoryWatch(('platform-service',), 'под сценариями webhook')
    watch.start()
    try:
        ...сценарии...
    finally:
        watch.finish()

Наблюдатель берёт docker stats раз в 3 секунды, как замеры задания (memory.py), и по окончании печатает одну строку «Пик памяти ...»
(в CI ещё и аннотацию), а красная проверка выставляется, если пик какого-либо сервиса выше 90 % лимита или замеров нет совсем (тихо
зелёным проверка не остаётся). Порог тот же, что у `--fail 90` в замерах заданий CI. Правило Р-196: пик 90 % и выше, значит лимит
platform-service поднимается до 384 МБ с `Xmx` 224. Модуль отдельный, чтобы им пользовались проверки без взаимных импортов.
Только стандартная библиотека Python.
"""
import os
import sys
import threading

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import gateway_checks as G  # noqa: E402
import memory as M  # noqa: E402

MEMORY_FAIL = 90                   # процент лимита, как --fail в замерах памяти задания e2e


def percent(used, limit):
    return 100.0 * used / limit


class MemoryWatch(threading.Thread):
    """Пик памяти перечисленных сервисов за время сценариев: docker stats раз в 3 секунды."""

    def __init__(self, watched, title):
        super().__init__(daemon=True)
        self.watched, self.title = tuple(watched), title
        self.peaks, self.errors, self.stopped = {}, 0, threading.Event()

    def run(self):
        while not self.stopped.is_set():
            try:
                for name, (used, limit) in M.snapshot().items():
                    service = M.service_of(name)
                    if service in self.watched and used >= self.peaks.get(service, (0, 0))[0]:
                        self.peaks[service] = (used, limit)
            except Exception:   # noqa: BLE001 - сбой замера не должен ронять сценарии
                self.errors += 1
            self.stopped.wait(M.INTERVAL)

    def finish(self):
        self.stopped.set()
        if self.is_alive():
            self.join(timeout=20)
        line = '; '.join('%s пик %.0f из %.0f МБ (%.0f%%)' % (s, self.peaks[s][0], self.peaks[s][1], percent(*self.peaks[s])) for s in self.watched if s in self.peaks)
        print('Пик памяти %s: %s' % (self.title, line or 'замеров нет'))
        if os.environ.get('GITHUB_ACTIONS') and line:
            print('::notice title=память %s::%s' % (self.title, line.replace(';', '%0A')))
        missing = [s for s in self.watched if s not in self.peaks]
        over = ['%s %.0f%%' % (s, percent(*v)) for s, v in self.peaks.items() if percent(*v) > MEMORY_FAIL]
        names = ', '.join(self.watched[:-1]) + ' и ' + self.watched[-1] if len(self.watched) > 1 else self.watched[0]
        G.expect('Пик памяти %s %s измерен и не выше %d%% лимита' % (names, self.title.replace('под сценариями выдачи по запросу', 'под сценариями'), MEMORY_FAIL),
                 not missing and not over, 'нет замеров: %s; выше порога: %s; сбоев замера %d' % (missing, over, self.errors))
