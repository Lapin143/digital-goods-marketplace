#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Проверка документа docs/08-testing/nft-phase3.md (шаг 17 Ф3).

  1. Каждая ячейка «Проверка» вида `файл::фрагмент`: файл существует, фрагмент в нём встречается (проверка не может исчезнуть незаметно).
  2. Каждое требование NFT-x.y, названное в документе, есть в nfr-methodology.md, каждое ST-nn есть в test-strategy.md.
  3. Для каждого требования из строки «Ф3» таблицы этапов test-strategy.md в документе есть строка (NFT-1.3, NFT-3.0, NFT-3.1, NFT-3.3, NFT-3.5, ST-03, ST-14).
  4. Задание CI из столбца «Где в CI» существует в ci.yml.
"""
import os
import re
import sys

import yaml

REPO = os.environ.get('REPO') or os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
DOC = os.path.join(REPO, 'docs', '08-testing', 'nft-phase3.md')
REQUIRED = ['NFT-1.3', 'NFT-3.0', 'NFT-3.1', 'NFT-3.3', 'NFT-3.5', 'ST-03', 'ST-14']
errors = []


def read(path):
    return open(os.path.join(REPO, path), encoding='utf-8').read()


def main():
    if not os.path.exists(DOC):
        print('ПРОВЕРКА НЕ ПРОЙДЕНА:\n  - нет файла %s' % DOC)
        return 1
    text = read('docs/08-testing/nft-phase3.md').split('\n## 3.')[0]   # разделы 1 и 2: таблицы требований и тестов
    methodology = read('docs/08-testing/nfr-methodology.md')
    strategy = read('docs/08-testing/test-strategy.md')
    jobs = set(yaml.safe_load(read('.github/workflows/ci.yml'))['jobs'])
    seen = set()
    for n, line in enumerate(text.splitlines(), 1):
        if not line.startswith('| NFT-') and not line.startswith('| ST-'):
            continue
        cells = [c.strip() for c in line.strip().strip('|').split('|')]
        req = cells[0]
        seen.add(req)
        if req.startswith('NFT-') and req + '.' not in methodology:
            errors.append('строка %d: требования %s нет в nfr-methodology.md' % (n, req))
        if req.startswith('ST-') and req not in strategy:
            errors.append('строка %d: теста %s нет в test-strategy.md' % (n, req))
        if len(cells) >= 5:
            for ref in re.findall(r'`([^`]+::[^`]*)`', cells[2]):
                path, _, fragment = ref.partition('::')
                if not os.path.exists(os.path.join(REPO, path)):
                    errors.append('строка %d: файла %s нет' % (n, path))
                elif fragment and fragment not in read(path):
                    errors.append('строка %d: в %s нет фрагмента «%s»' % (n, path, fragment))
            for job in re.findall(r'`(\w+)`', cells[4]):
                if job not in jobs:
                    errors.append('строка %d: задания CI %s нет в ci.yml' % (n, job))
    for req in REQUIRED:
        if req not in seen:
            errors.append('для %s в документе нет строки' % req)
    m = re.search(r'\| Ф3 \| [^|]*\| ([^|]+)\| ([^|]+)\|', strategy)
    if m:
        for req in re.findall(r'(?:NFT|ST)-\d+(?:\.\d+)?', m.group(1) + m.group(2)):
            if req not in seen:
                errors.append('%s значится в test-strategy.md для Ф3, а в nft-phase3.md строки нет' % req)
    if errors:
        print('ПРОВЕРКА НЕ ПРОЙДЕНА:')
        for e in errors:
            print('  - %s' % e)
        print('проблем: %d' % len(errors))
        return 1
    print('ок: требований Ф3 в документе %d, проверки на месте, задания CI существуют' % len(seen))
    return 0


if __name__ == '__main__':
    sys.exit(main())
