#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Ориентир NFT-1.0 и NFT-1.1: запускает сценарии k6 по порядку и печатает сводку (шаг 21 Ф4, решение Р-28).

    make up SET=dev-sellers DEBUG=1 DGM_LOAD_PUBLIC_PER_MINUTE=1000000 DGM_LOAD_PUBLIC_BURST=1000000 && make load-seed && make load-nft

Условия (docs/08-testing/nfr-methodology.md, раздел 3): 5000 опубликованных товаров, кэш Redis прогрет, 50 запросов в секунду, 2 минуты
устойчивой нагрузки (на сервере 5 минут). Параметры из окружения:
    BASE_URL  адрес шлюза (https://localhost:8443)      RATE    запросов в секунду (50)      STEADY  устойчивая фаза (2m)
    K6        исполняемый файл k6 (k6)                  LOAD_STRICT=1  красный код выхода, если порог нарушен (по умолчанию ориентир: предупреждение)
Центр сертификации стенда берётся из secrets/tls_ca.crt через SSL_CERT_FILE (k6 написан на Go и читает эту переменную).

Раннер GitHub Actions разделяет процессор с контейнерами стенда и самим k6, поэтому результат это ориентир: зачёт по методике на сервере (Р-28).
"""
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))

SCENARIOS = (
    ('storefront.k6.js', 'NFT-1.0 карточка товара', ('card_duration',), 300, ()),
    ('search.k6.js', 'NFT-1.1 поиск и фильтры', ('search_duration', 'filter_duration'), 500, (('empty_search', 'поиск'), ('empty_filter', 'фильтры'))),
)


def values(metric):
    return metric.get('values', metric)


def run_one(script, k6, env):
    with tempfile.TemporaryDirectory() as tmp:
        export = os.path.join(tmp, 'summary.json')
        r = subprocess.run([k6, 'run', '--quiet', '--summary-export', export, os.path.join(HERE, script)], env=env)
        summary = {}
        if os.path.exists(export):
            with open(export, encoding='utf-8') as f:
                summary = json.load(f).get('metrics', {})
    return r.returncode, summary


def main():
    k6 = os.environ.get('K6', 'k6')
    strict = os.environ.get('LOAD_STRICT') == '1'
    env = dict(os.environ)
    env.setdefault('SSL_CERT_FILE', os.path.join(os.environ.get('DGM_SECRETS_DIR', os.path.join(REPO, 'secrets')), 'tls_ca.crt'))
    lines, missed, broken = [], [], []
    for script, title, trends, limit, rates in SCENARIOS:
        print('== %s (%s, порог p95 < %d мс)' % (title, script, limit), flush=True)
        rc, summary = run_one(script, k6, env)
        if rc not in (0, 99):
            broken.append('%s: k6 завершился с кодом %d' % (script, rc))
            continue
        reqs = values(summary.get('http_reqs', {}))
        failed = values(summary.get('http_req_failed', {}))
        for trend in trends:
            v = values(summary.get(trend, {}))
            if not v:
                broken.append('%s: в отчёте k6 нет метрики %s' % (script, trend))
                continue
            p95 = v.get('p(95)')
            line = '%-26s %-16s p95 %7.1f мс  (медиана %.1f, p99 %.1f, максимум %.1f)  порог %d мс: %s' % (
                title, trend, p95, v.get('med', 0), v.get('p(99)', 0), v.get('max', 0), limit, 'выполнен' if p95 < limit else 'НЕ ВЫПОЛНЕН')
            lines.append(line)
            if p95 >= limit:
                missed.append('%s %s p95 %.1f мс при пороге %d' % (title, trend, p95, limit))
        lines.append('%-26s запросов %d, скорость %.1f в секунду, доля ошибок %.4f' % (title, reqs.get('count', 0), reqs.get('rate', 0), failed.get('value', failed.get('rate', 0))))
        for metric, label in rates:
            r = values(summary.get(metric, {}))
            if not r:
                broken.append('%s: в отчёте k6 нет метрики %s' % (script, metric))
                continue
            share = r.get('value', r.get('rate', 0))
            lines.append('%-26s доля пустых ответов (%s): %.4f' % (title, label, share))
            if share > 0.5:
                broken.append('%s: больше половины ответов (%s) пустые (%.2f), засев и условия запросов разошлись, результат ничего не доказывает' % (script, label, share))
        if rc == 99 and not any(title in m for m in missed):
            missed.append('%s: нарушен порог доли ошибок или проверок' % title)
    print()
    print('\n'.join(lines))
    if os.environ.get('GITHUB_ACTIONS'):
        print('::notice title=Нагрузка NFT-1.0 и NFT-1.1 (ориентир на раннере)::%s' % '%0A'.join(lines))
        for m in missed:
            print('::warning title=Порог нагрузки на раннере::%s' % m)
    for b in broken:
        print('ОШИБКА: %s' % b)
    if broken or (strict and missed):
        return 1
    if missed:
        print('Пороги нарушены (%d), на раннере это ориентир; зачёт по методике на сервере.' % len(missed))
    return 0


if __name__ == '__main__':
    sys.exit(main())
