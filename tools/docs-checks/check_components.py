# -*- coding: utf-8 -*-
"""Проверка диаграмм компонентов C4 (уровень 3): c4-components.md и c4-components-<сервис>.md.

  1. Таблицы компонентов: алиасы в kebab-case, не повторяются внутри сервиса; суффикс согласован со слоем Spring
     (-controller: @RestController, -repository: Spring Data JDBC, -domain: доменный @Service, -client: @Component,
     -relay, -job и т. п.: @Scheduled).
  2. Диаграммы: границей служит контейнер сервиса, узлы это компоненты сервиса, компоненты каркаса, контейнеры, внешние
     системы или базы; не больше 15 элементов; каждый компонент из таблицы показан хотя бы на одной диаграмме (у сервисов
     с диаграммами); связи диаграмм равны строкам таблиц связей.
  3. Правила слоёв по связям: контроллер не вызывает клиент и репозиторий, доменный слой не делает сетевых вызовов,
     в базу пишет только репозиторий (и публикатор Outbox), секреты читает ограниченный круг компонентов.
  4. Ссылки «раздел N, связь M» ведут на существующие строки c4-containers.md.
  5. Таблицы «Компонент → инварианты»: компоненты и инварианты существуют, все инварианты доменной модели покрыты
     (сводка в c4-components.md, раздел 6, не противоречит таблицам сервисов).
  6. Раздел 5 c4-components.md: компоненты связей и событий существуют, события названы так же, как примеры AsyncAPI.
"""
import glob
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import docslib as L

SERVICE_FILES = {
    'catalog-service': 'c4-components-catalog-service.md',
    'inventory-service': 'c4-components-inventory-service.md',
    'order-service': 'c4-components-order-service.md',
    'payment-service': 'c4-components-payment-service.md',
    'delivery-service': 'c4-components-delivery-service.md',
    'platform-service': 'c4-components-platform-service.md',
}
EXTERNALS = {'payment-gateway', 'email-provider', 'sms-provider', 'vk-id'}
MAX_ELEMENTS = 15
SUFFIX_LAYER = [  # (суффикс, подстрока в столбце «Spring-слой»)
    ('-controller', '@RestController'),
    ('-repository', 'Spring Data JDBC'),
    ('-domain', 'доменного слоя'),
    ('-client', '@Component'),
    ('-relay', '@Scheduled'),
    ('-job', '@Scheduled'),
    ('-timer', '@Scheduled'),
    ('-worker', '@Scheduled'),
    ('-poller', '@Scheduled'),
    ('-reconciler', '@Scheduled'),
    ('-dispatcher', '@Scheduled'),
    ('-watchdog', '@Scheduled'),
]
# в базы, кроме репозиториев, пишут только эти компоненты (c4-components.md, раздел 5.2)
DB_ACCESS = {'outbox-relay', 'history-reader'}
SECRET_READERS = {'key-crypto', 'signature-verifier', 'gateway-client', 'email-client', 'email-sender', 'sms-client',
                  'otp-service', 'audit-service', 'provider-webhook-controller', 'webhook-controller'}
KEBAB = re.compile(r'^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$')


def edge_pairs(block):
    """Связи диаграммы: список (откуда, куда) в порядке записи."""
    pairs = []
    for raw in block.split('\n'):
        m = re.match(r'^\s*([A-Za-z][\w-]*)\s*(?:-->|-\.->|==>|---->|-\.-\.->)\s*\|"[^|]*"\|\s*([A-Za-z][\w-]*)\s*$', raw)
        if m:
            pairs.append((m.group(1), m.group(2)))
    return pairs


def expand(cell):
    return L.backticked(cell)


def is_neighbor(name, containers):
    """Сосед на уровне контейнеров: контейнер, внешняя система, база, тема Kafka."""
    return (name in containers or name in EXTERNALS or '.' in name
            or re.match(r'^[a-z]+(?:_db|-db)$', name) is not None)


def main():
    rep = L.Report('components')
    inv_all = set()
    dm = L.read(os.path.join(L.DOCS, '04-domain', 'domain-model.md'))
    inv_all = set(re.findall(r'^\| (INV-\d+) \|', dm, re.M))
    containers = set(L.containers()) | {'finance-service', 'kafka', 'redis', 'object-storage'}
    events = {os.path.basename(p)[:-5] for p in glob.glob(os.path.join(L.DOCS, '06-api', 'events', 'examples', '*.json'))}

    # ----------------------------------------------------- реестр компонентов
    registry = {}          # сервис -> {алиас: слой}
    kit = {}
    root = L.read(os.path.join(L.ARCH, 'c4-components.md'))
    for t in L.tables(root):
        if t['header'][:2] == ['Компонент', 'Алиас']:
            for r in t['rows']:
                kit[L.backticked(r[1])[0]] = r[2]
    all_aliases = set(kit)
    for svc, fn in SERVICE_FILES.items():
        text = L.read(os.path.join(L.ARCH, fn))
        reg = {}
        for t in L.tables(text):
            if 'Алиас' in t['header'] and t['header'][0] == 'Компонент':
                ai = t['header'].index('Алиас')
                li = t['header'].index('Spring-слой') if 'Spring-слой' in t['header'] else None
                for r in t['rows']:
                    al = L.backticked(r[ai])
                    if not al:
                        rep.err(fn, 'строка без алиаса: %s' % r[0])
                        continue
                    alias = al[0]
                    if alias in reg:
                        rep.err(fn, 'алиас %s объявлен дважды' % alias)
                    reg[alias] = r[li] if li is not None else ''
        registry[svc] = reg
        all_aliases |= set(reg)
    n_components = 0
    for svc, reg in registry.items():
        for alias, layer in reg.items():
            n_components += 1
            if not KEBAB.match(alias):
                rep.err(svc, 'алиас %s не в kebab-case' % alias)
            for suf, need in SUFFIX_LAYER:
                if layer and alias.endswith(suf) and need not in layer:
                    rep.err(svc, '%s: суффикс %s требует слой «%s», в таблице «%s»' % (alias, suf, need, layer))
    # ----------------------------------------------------- диаграммы
    n_diagrams = 0
    n_edges = 0
    for svc, fn in SERVICE_FILES.items():
        text = L.read(os.path.join(L.ARCH, fn))
        blocks = L.mermaid_blocks(text)
        known = set(registry[svc]) | set(kit) | containers | EXTERNALS
        shown = set()
        link_tables = [t for t in L.tables(text) if t['header'][:3] == ['№', 'От', 'К']]
        table_pairs = set()
        for t in link_tables:
            for r in t['rows']:
                for a in expand(r[1]):
                    for b in expand(r[2]):
                        table_pairs.add((a, b))
        diagram_pairs = set()
        for i, b in enumerate(blocks, 1):
            n_diagrams += 1
            fc = L.flowchart(b)
            nodes = set(fc['nodes'])
            if svc not in fc['subgraphs']:
                rep.err(fn, 'диаграмма %d: граница не названа алиасом контейнера %s' % (i, svc))
            if len(nodes) > MAX_ELEMENTS:
                rep.err(fn, 'диаграмма %d: %d элементов, не больше %d' % (i, len(nodes), MAX_ELEMENTS))
            for n in nodes:
                if n in registry[svc] or n in kit:
                    shown.add(n)
                    continue
                if n in containers or n in EXTERNALS or re.match(r'^[a-z]+_?db$|^[a-z]+-db$', n):
                    continue
                rep.err(fn, 'диаграмма %d: узел %s не компонент сервиса, не контейнер и не внешняя система' % (i, n))
            for a, c in edge_pairs(b):
                n_edges += 1
                diagram_pairs.add((a, c))
                for end in (a, c):
                    if end not in nodes:
                        rep.err(fn, 'диаграмма %d: связь %s → %s: узел %s не объявлен' % (i, a, c, end))
                # правила слоёв
                if a.endswith('-controller') and (c.endswith('-client') or c.endswith('-repository')):
                    rep.err(fn, 'контроллер %s вызывает %s (правило 5 или 6)' % (a, c))
                if a.endswith('-domain') and (c.endswith('-client') or c in containers or c in EXTERNALS):
                    rep.err(fn, 'доменный слой %s делает сетевой вызов: %s (правило 5)' % (a, c))
                if re.match(r'.*-db$', c) and not a.endswith('-repository') and a not in DB_ACCESS:
                    rep.err(fn, '%s обращается к базе %s не через репозиторий (правило 7)' % (a, c))
                if c == 'secret-store' and a not in SECRET_READERS:
                    rep.err(fn, '%s читает секреты, не будучи в списке компонентов с доступом' % a)
                if a.endswith('-client') and c.endswith('-repository'):
                    rep.err(fn, 'клиент %s вызывает репозиторий %s' % (a, c))
        r2_text = L.section(text, r'Расширение релиза R2') or ''
        r2_aliases = set()
        for t in L.tables(r2_text):
            if 'Алиас' in t['header']:
                ai = t['header'].index('Алиас')
                for r in t['rows']:
                    r2_aliases |= set(L.backticked(r[ai])[:1])
        if blocks:
            for alias in registry[svc]:
                if alias not in shown and alias not in r2_aliases:
                    rep.err(fn, 'компонент %s не показан ни на одной диаграмме' % alias)
            # связи с репозиторием в таблицах сводятся в одну строку, поэтому сверяются только остальные связи
            d_cmp = {p for p in diagram_pairs if not p[1].endswith('-repository')}
            t_cmp = {p for p in table_pairs if not p[1].endswith('-repository')}
            if d_cmp != t_cmp:
                only_d = sorted(d_cmp - t_cmp)
                only_t = sorted(t_cmp - d_cmp)
                if only_d:
                    rep.err(fn, 'связи есть на диаграммах, но нет в таблицах: %s' % only_d)
                if only_t:
                    rep.err(fn, 'связи есть в таблицах, но нет на диаграммах: %s' % only_t)
        # ссылки на c4-containers.md
        for t in link_tables:
            for r in t['rows']:
                for m in re.finditer(r'раздел (\d+(?:\.\d+)?), связь (\d+)', r[-1]):
                    if not containers_row(m.group(1), m.group(2)):
                        rep.err(fn, 'ссылка «раздел %s, связь %s» не ведёт на строку c4-containers.md' % (m.groups()))
        # таблицы «Связи с соседями» (каталог, платформа)
        for t in L.tables(text):
            if t['header'][0].startswith('Связь контейнера'):
                for r in t['rows']:
                    for a in expand(r[2]):
                        if a not in registry[svc] and a not in kit and not is_neighbor(a, containers):
                            rep.err(fn, 'связь «%s»: компонент %s не найден' % (r[0][:40], a))
                    for m in re.finditer(r'раздел (\d+(?:\.\d+)?), связь (\d+)', r[0]):
                        if not containers_row(m.group(1), m.group(2)):
                            rep.err(fn, 'ссылка «раздел %s, связь %s» не ведёт на строку c4-containers.md' % (m.groups()))
    # ----------------------------------------------------- инварианты
    covered = set()
    for svc, fn in SERVICE_FILES.items():
        text = L.read(os.path.join(L.ARCH, fn))
        for t in L.tables(text):
            if t['header'][:2] == ['Компонент', 'Инварианты']:
                for r in t['rows']:
                    comps = L.backticked(r[0])
                    for c in comps:
                        if c not in registry[svc] and c not in kit:
                            rep.err(fn, 'в таблице инвариантов компонент %s не найден' % c)
                    for inv in re.findall(r'INV-\d+', r[1]):
                        covered.add(inv)
                        if inv not in inv_all:
                            rep.err(fn, 'инвариант %s не найден в доменной модели' % inv)
    summary = L.table_after(root, r'^## 6\. Инварианты и компоненты')
    r2_modules = {'disputes', 'balance'}
    for r in summary['rows']:
        ids = re.findall(r'INV-(\d+)', r[0])
        if not ids:
            rep.err('c4-components 6', 'строка «%s» без номера инварианта' % r[0])
            continue
        lo, hi = int(ids[0]), int(ids[-1])
        is_r2 = '(R2)' in r[0]
        for n in range(lo, hi + 1):
            inv = 'INV-%02d' % n
            covered.add(inv)
            if inv not in inv_all:
                rep.err('c4-components 6', 'инвариант %s не найден в доменной модели' % inv)
        svcs = L.backticked(r[1])
        for s_ in svcs:
            if s_ not in registry and not (is_r2 and s_ == 'finance-service'):
                rep.err('c4-components 6', '%s: сервис %s неизвестен' % (r[0], s_))
        for c in L.backticked(r[2]):
            if is_r2 and c in r2_modules:
                continue
            ok = any(c in registry.get(s_, {}) for s_ in svcs) or (not svcs and c in all_aliases) or c in kit
            if not ok:
                rep.err('c4-components 6', '%s: компонент %s не найден у сервисов %s' % (r[0], c, svcs))
    missing = sorted(inv_all - covered)
    for inv in missing:
        rep.err('c4-components 6', 'инвариант %s не закреплён за компонентами ни в сводке, ни в таблицах сервисов' % inv)
    # ----------------------------------------------------- раздел 5 обзора
    for sec, label in ((r'^### 5\.1\.', '5.1'), (r'^### 5\.2\.', '5.2')):
        t = L.table_after(root, sec)
        for r in t['rows']:
            for cell in r[1:]:
                for c in L.backticked(cell):
                    if c not in all_aliases and c not in containers and c not in EXTERNALS:
                        rep.err('c4-components %s' % label, 'строка «%s»: компонент %s не найден' % (r[0][:40], c))
            for m in re.finditer(r'раздел (\d+(?:\.\d+)?), связь (\d+)', r[0]):
                if not containers_row(m.group(1), m.group(2)):
                    rep.err('c4-components %s' % label, 'ссылка «раздел %s, связь %s» не ведёт на строку' % m.groups())
    t = L.table_after(root, r'^### 5\.3\.')
    ev_used = set()
    for r in t['rows']:
        for e in L.backticked(r[1]):
            ev_used.add(e)
            if e not in events:
                rep.err('c4-components 5.3', 'событие %s не найдено среди примеров AsyncAPI' % e)
        for cell in r[2:]:
            for c in L.backticked(cell):
                if c in events or (c == 'finance-service' and 'R2' in ' '.join(r)):
                    continue
                if c not in all_aliases and c not in registry:
                    rep.err('c4-components 5.3', 'событие %s: компонент или сервис %s не найден' % (r[1][:30], c))
    # события R1 с обработчиками должны быть в таблице
    rep.fact('Компоненты: %d компонентов в %d сервисах, %d компонентов каркаса, %d диаграмм, %d связей, '
             'инвариантов закреплено %d из %d, событий в разделе 5.3: %d'
             % (n_components, len(registry), len(kit), n_diagrams, n_edges, len(covered & inv_all), len(inv_all), len(ev_used)))
    return rep.finish()


_CONT_TABLES = {}


def containers_row(section, num):
    """Есть ли в разделе section файла c4-containers.md строка таблицы с номером num."""
    text = L.read(os.path.join(L.ARCH, 'c4-containers.md'))
    key = section
    if key not in _CONT_TABLES:
        m = re.search(r'^#{2,3} %s\.\s.*$' % re.escape(section), text, re.M)
        rows = set()
        if m:
            sub = text[m.end():]
            nxt = re.search(r'^#{2,3} \d', sub, re.M)
            sub = sub[:nxt.start()] if nxt else sub
            for t in L.tables(sub):
                if t['header'] and t['header'][0] == '№':
                    rows |= {r[0] for r in t['rows']}
        _CONT_TABLES[key] = rows
    return num in _CONT_TABLES[key]


if __name__ == '__main__':
    sys.exit(main())
