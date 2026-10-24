#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Сверка «документы = код» для интерфейсов (шаг 20 Ф4, условие выхода 3 среза 1; расширена на срез 2 шагом 4 руководства среза 2).

Контракты и каталог событий описывают, что система делает; исходный код сервисов это то, что она делает. Скрипт читает Java-исходники как текст
(компилятор не нужен, поэтому проверка идёт в задании `docs` на каждый push) и сверяет их с документами:

  1. Операции. Для каждой операции из `routes.json` со статусом `done` в реестре `operations.txt` в контроллерах сервиса есть метод с тем же
     HTTP-методом и путём; у контроллера нет метода, которого нет в OpenAPI; у операции со статусом не `done` нет обработчика (иначе реестр устарел).
  2. События. Каждое событие срезов 1 и 2 из каталога событий записывается в код сервиса-издателя (строка с типом события есть в исходниках издателя),
     а у каждого его потребителя из каталога есть обработчик `EventHandler` с тем же именем, темой и типом события в `types()`.
  3. Обратная сторона. Каждый обработчик кода описан в каталоге как потребитель, каждое событие каталога, которое код издаёт, записано в каталоге.
  4. Темы Kafka. Тема из кода (издание, обработчик) есть в `infra/kafka/topics.sh`, у сервиса есть право WRITE на тему издания и READ на тему обработчика.

Что код сервиса «делает» определяется по тексту: аннотации `@GetMapping` и родственные (путь из строки, константы класса и их сложения),
метод `name()`, `topic()` и `types()` обработчиков. Если выражение не удалось вычислить, это ошибка проверки: сверка не должна молча пропускать код.

    python3 tools/docs-checks/check_code_docs.py
"""
import glob
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import docslib as L
import check_ledger as LG

# События, которые выпуск R1 реализует к концу среза 1 (руководство Ф4, «Что входит в срез 1»); остальные события R1 реализуют срезы 2 и 3.
SLICE1_EVENTS = (
    'seller.approved', 'seller.rejected', 'seller.returned',
    'product.created', 'product.updated', 'product.published', 'product.rejected', 'product.blocked',
    'stock.changed',
    'user.role-assigned', 'user.phone-confirmed', 'user.sms-login', 'user.deactivated', 'user.anonymized',
    'audit.recorded',
)

# События среза 2 «Заказ, оплата, выдача» (руководство среза 2, Р-132): 18 событий, их издаёт владелец темы. `stock.changed` относится к срезу 1 и
# здесь не повторяется. Потребителей этих событий в каталоге ровно 20.
SLICE2_EVENTS = (
    'order.created', 'order.paid', 'order.cancelled', 'order.issued', 'order.refunded', 'order.refund-requested',
    'reservation.expired', 'reservation.released',
    'payment.confirmed', 'payment.rejected', 'payment.refunded', 'payment.refund-escalated',
    'delivery.accepted', 'delivery.delivered', 'delivery.failed', 'delivery.overdue',
    'ticket.created', 'ticket.resolved',
)

# События среза 2, которые код ещё не издаёт: событие -> шаг руководства среза 2, где издание появится. Пока событие здесь, проверка не ищет его строку в
# коде издателя; издание появилось, запись надо убрать (проверка скажет). К шагу 22 словарь пуст; на шаге 15 ушли последние записи.
UNPUBLISHED_SLICE2 = {
}

# Потребители событий, которых код получит позже: событие -> {(сервис, обработчик)}. Пока обработчика нет, пара стоит здесь; появился обработчик,
# запись надо убрать (проверка скажет). Так каталог (он описывает R1 целиком) и код среза расходятся только там, где это записано.
# Пары среза 2 ушли на шагах 5, 9, 11, 13 и 15 руководства среза 2 (order-service на шаге 11, delivery-service на шаге 13, platform-service на шаге 15);
# остаются только пары среза 3.
LATER_CONSUMERS = {
    'product.published': {('platform-service', 'notification-handler')},  # письмо продавцу о решении по товару, FT-11.4, срез 3
    'product.rejected': {('platform-service', 'notification-handler')},
    'product.blocked': {('platform-service', 'notification-handler')},
    'user.deactivated': {('platform-service', 'notification-handler')},  # письмо о деактивации, FT-11.0, срез 3
    'audit.recorded': {('platform-service', 'audit-consumer')},  # потребитель журнала аудита, срез 3
}

EVENTS_README = os.path.join(L.DOCS, '06-api', 'events', 'README.md')
TOPICS_SH = os.path.join(L.REPO, 'infra', 'kafka', 'topics.sh')
MAPPING = re.compile(r'@(Get|Post|Put|Patch|Delete)Mapping\s*(?:\(([^)]*)\))?')
CLASS_MAPPING = re.compile(r'@RequestMapping\s*\(([^)]*)\)\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:public\s+)?(?:final\s+)?class\s')
CONSTANT = re.compile(r'\bstatic\s+final\s+String\s+(\w+)\s*=\s*([^;]+);')
STRING = re.compile(r'"((?:[^"\\]|\\.)*)"')
EVENT_NAME = re.compile(r'"([a-z]+(?:-[a-z]+)*\.[a-z]+(?:-[a-z]+)*)"')


def service_files(service):
    return sorted(glob.glob(os.path.join(L.REPO, 'services', service, 'src', 'main', 'java', '**', '*.java'), recursive=True))


def evaluate(expr, consts):
    """«BASE + "/x"» -> строка; None, если в выражении что-то кроме строк, констант класса и сложения."""
    parts = [p.strip() for p in expr.strip().split('+')]
    out = ''
    for p in parts:
        m = STRING.fullmatch(p)
        if m:
            out += m.group(1)
        elif p in consts and consts[p] is not None:
            out += consts[p]
        else:
            return None
    return out


def constants(text):
    """Константы-строки класса; выражения, ссылающиеся на другие константы, разворачиваются в несколько проходов."""
    raw = {name: expr for name, expr in CONSTANT.findall(text)}
    consts = {name: None for name in raw}
    for _ in range(4):
        for name, expr in raw.items():
            if consts[name] is None:
                consts[name] = evaluate(expr, consts)
    return consts


def first_argument(arguments):
    """Первый позиционный аргумент аннотации (путь); `value = …` и `path = …` тоже путь, `produces = …` и подобные пути не задают."""
    if arguments is None or not arguments.strip():
        return ''
    depth, start, parts = 0, 0, []
    for i, ch in enumerate(arguments):
        if ch in '([{':
            depth += 1
        elif ch in ')]}':
            depth -= 1
        elif ch == ',' and depth == 0:
            parts.append(arguments[start:i])
            start = i + 1
    parts.append(arguments[start:])
    first = parts[0].strip()
    m = re.match(r'(value|path)\s*=\s*(.*)', first, re.S)
    if m:
        return m.group(2).strip()
    if re.match(r'\w+\s*=', first):
        return ''
    return first


def normalize(path):
    return re.sub(r'\{[^}/]+\}', '{}', path)


def handlers_in_controllers(service, rep):
    """Множество (METHOD, путь) из контроллеров сервиса; пути приведены к виду без имён переменных."""
    found = {}
    for f in service_files(service):
        text = L.read(f)
        if '@RestController' not in text and '@Controller' not in text:
            continue
        where = L.rel(f)
        consts = constants(text)
        prefix = ''
        cm = CLASS_MAPPING.search(text)
        if cm:
            prefix = evaluate(first_argument(cm.group(1)), consts)
            if prefix is None:
                rep.err(where, 'не удалось вычислить путь класса «%s»' % first_argument(cm.group(1)))
                prefix = ''
        for m in MAPPING.finditer(text):
            expr = first_argument(m.group(2))
            suffix = evaluate(expr, consts) if expr else ''
            if suffix is None:
                rep.err(where, 'не удалось вычислить путь метода «%s»' % expr)
                continue
            found.setdefault((m.group(1).upper(), normalize(prefix + suffix)), where)
    return found


def check_operations(rep):
    totals = {'done': 0, 'handlers': 0}
    for service in LG.SERVICES:
        where = '%s: операции' % service
        with open(LG.routes_path(service), encoding='utf-8') as f:
            routes = json.load(f)['routes']
        if not os.path.exists(LG.ledger_path(service)):
            continue
        ledger = LG.read_ledger(LG.ledger_path(service), L.Report('reg'), where)
        code = handlers_in_controllers(service, rep)
        documented = {}
        for r in routes:
            documented[(r['method'].upper(), normalize(r['path']))] = r['operationId']
        for key, op in sorted(documented.items(), key=lambda kv: kv[1]):
            status = ledger.get(op)
            if status == 'done':
                totals['done'] += 1
                if key not in code:
                    rep.err(where, 'операция %s (%s %s) в реестре `done`, а обработчика в контроллерах нет' % (op, key[0], key[1]))
            elif key in code:
                rep.err(where, 'операция %s (%s %s) имеет обработчик (%s), а в реестре %s: переведите её в `done`' % (op, key[0], key[1], code[key], status))
        for key, f in sorted(code.items()):
            totals['handlers'] += 1
            if key not in documented:
                rep.err(where, 'метод %s %s (%s) есть в коде и нет в OpenAPI' % (key[0], key[1], f))
    return totals


def parse_catalog():
    """Из каталога событий: событие -> {'topic', 'owner', 'consumers': [(сервис, обработчик)]}, только события R1."""
    text = L.read(EVENTS_README)
    owner_of = {}
    topics = L.find_table(text, 'Тема', 'Издатель')
    for row in (topics['rows'] if topics else []):
        owner_of[row[0].strip('`')] = row[1].strip('`')
    r1 = text.split('### События R2')[0]
    events = {}
    topic = None
    for line in r1.split('\n'):
        m = re.match(r'#### `([^`]+)`', line)
        if m:
            topic = m.group(1)
            continue
        m = re.match(r'\| \[`([^`]+)`\]\([^)]*\) \| (.*)$', line)
        if not m or not topic:
            continue
        cells = [c.strip() for c in m.group(2).rstrip('|').split(' | ')]
        consumers = []
        cell = cells[-1]
        if not cell.startswith('нет'):
            for part in cell.split(';'):
                names = re.findall(r'`([^`]+)`', part)
                if names and 'R2' not in part:
                    consumers += [(names[0], h) for h in names[1:]]
        events[m.group(1)] = {'topic': topic, 'owner': owner_of.get(topic, ''), 'consumers': consumers}
    return events


def event_handlers(service, rep):
    """Обработчики EventHandler сервиса, сгруппированные по имени (у имени может быть несколько классов, по одному на тему):
    имя -> {'pairs': {(тема, тип события)}, 'file': путь}."""
    found = {}
    for f in service_files(service):
        text = L.read(f)
        if not re.search(r'\bimplements\b[^{]*\b(?:EventHandler|ExternalStepsHandler)\b', text):
            continue
        where = L.rel(f)
        consts = constants(text)
        values = {}
        for key, rx in (('name', r'String\s+name\s*\(\s*\)\s*\{\s*return\s+([^;]+);'),
                        ('topic', r'String\s+topic\s*\(\s*\)\s*\{\s*return\s+([^;]+);'),
                        ('types', r'Set<String>\s+types\s*\(\s*\)\s*\{\s*return\s+([^;]+);')):
            m = re.search(rx, text)
            if not m:
                rep.err(where, 'в обработчике нет метода %s()' % key)
                values[key] = None
                continue
            expr = m.group(1).strip()
            if key == 'types':
                inner = re.fullmatch(r'Set\.of\((.*)\)', expr, re.S)
                items = [evaluate(x, consts) for x in inner.group(1).split(',')] if inner else [None]
                if None in items:
                    rep.err(where, 'не удалось вычислить types(): «%s»' % expr)
                values[key] = {x for x in items if x}
            else:
                values[key] = evaluate(expr, consts)
                if values[key] is None:
                    rep.err(where, 'не удалось вычислить %s(): «%s»' % (key, expr))
        if not values['name'] or not values['topic']:
            continue
        entry = found.setdefault(values['name'], {'pairs': set(), 'files': []})
        entry['pairs'] |= {(values['topic'], t) for t in values['types'] or set()}
        entry['files'].append(where)
    return found


def published_events(service, known):
    """События каталога, строка с типом которых есть в исходниках сервиса вне обработчиков EventHandler."""
    found = {}
    for f in service_files(service):
        text = L.read(f)
        if re.search(r'\bimplements\b[^{]*\b(?:EventHandler|ExternalStepsHandler)\b', text):
            continue
        for name in EVENT_NAME.findall(text):
            if name in known:
                found.setdefault(name, L.rel(f))
    return found


def acl(rep):
    """Права из topics.sh: (сервис, 'Read'|'Write') -> множество тем."""
    rights = {}
    topics = set()
    for line in L.read(TOPICS_SH).split('\n'):
        m = re.match(r'create_topic (\S+) ', line)
        if m:
            topics.add(m.group(1))
        m = re.match(r'allow (\S+) (.*)$', line)
        if not m or '--topic' not in line:
            continue
        ops = set(re.findall(r'--operation (\w+)', m.group(2)))
        for t in re.findall(r'--topic (\S+)', m.group(2)):
            for op in ops:
                rights.setdefault((m.group(1), op), set()).add(t)
    return topics, rights


def check_events(rep):
    catalog = parse_catalog()
    where = 'каталог событий'
    for e in SLICE1_EVENTS:
        if e not in catalog:
            rep.err(where, 'событие %s среза 1 не найдено в каталоге R1' % e)
    for e in SLICE2_EVENTS:
        if e not in catalog:
            rep.err(where, 'событие %s среза 2 не найдено в каталоге R1' % e)
    for e in sorted(set(UNPUBLISHED_SLICE2) - set(SLICE2_EVENTS)):
        rep.err(where, 'событие %s записано в UNPUBLISHED_SLICE2, а в SLICE2_EVENTS его нет' % e)
    topics, rights = acl(rep)
    handlers = {s: event_handlers(s, rep) for s in LG.SERVICES}
    published = {s: published_events(s, set(catalog)) for s in LG.SERVICES}
    stats = {'events': 0, 'consumers': 0, 'later': 0, 'unpublished': 0}
    for e in SLICE1_EVENTS + SLICE2_EVENTS:
        info = catalog.get(e)
        if not info:
            continue
        stats['events'] += 1
        owners = [info['owner']] if info['owner'] in LG.SERVICES else list(LG.SERVICES)
        is_published = any(e in published[s] for s in owners)
        if e in UNPUBLISHED_SLICE2:
            stats['unpublished'] += 1
            if is_published:
                rep.err(where, 'событие %s уже издаётся в коде: уберите его из UNPUBLISHED_SLICE2' % e)
        elif not is_published:
            rep.err(where, 'событие %s: в коде издателя (%s) нет строки с типом события, издание не реализовано' % (e, ', '.join(owners)))
        later = LATER_CONSUMERS.get(e, set())
        for pair in sorted(later - set(info['consumers'])):
            rep.err(where, 'событие %s: пара %s: %s записана в LATER_CONSUMERS, а в каталоге такого потребителя нет' % ((e,) + pair))
        for service, name in info['consumers']:
            entry = handlers.get(service, {}).get(name)
            implemented = bool(entry) and (info['topic'], e) in entry['pairs']
            if (service, name) in later:
                stats['later'] += 1
                if implemented:
                    rep.err(entry['files'][0], 'обработчик %s читает %s: уберите пару из LATER_CONSUMERS, потребитель реализован' % (name, e))
                continue
            stats['consumers'] += 1
            if not entry:
                rep.err(where, 'событие %s: потребитель %s: %s описан в каталоге, а обработчика с таким именем в коде нет' % (e, service, name))
            elif not implemented:
                topics_read = sorted({t for t, x in entry['pairs'] if x == e})
                why = ('читает его из темы %s, в каталоге тема %s' % (', '.join(topics_read), info['topic'])) if topics_read else 'не читает это событие'
                rep.err(entry['files'][0], 'обработчик %s %s, а каталог называет его потребителем %s' % (name, why, e))
    # обратная сторона: обработчики кода описаны в каталоге как потребители
    for service, by_name in handlers.items():
        for name, entry in sorted(by_name.items()):
            for topic, e in sorted(entry['pairs']):
                info = catalog.get(e)
                if not info:
                    rep.err(entry['files'][0], 'обработчик %s читает %s, а события нет в каталоге R1' % (name, e))
                elif (service, name) not in info['consumers']:
                    rep.err(entry['files'][0], 'обработчик %s читает %s, а в каталоге потребитель этого события не назван' % (name, e))
    # темы и права: издание только в темы, где сервис издатель, чтение только тем, на которые есть READ
    for service in LG.SERVICES:
        for name, entry in handlers[service].items():
            for topic, e in sorted(entry['pairs']):
                if topic not in topics:
                    rep.err(entry['files'][0], 'тема %s обработчика %s не создаётся в topics.sh' % (topic, name))
                elif topic not in rights.get((service, 'Read'), set()):
                    rep.err(entry['files'][0], 'у сервиса %s нет права READ на тему %s обработчика %s в topics.sh' % (service, topic, name))
        for e, f in sorted(published[service].items()):
            info = catalog.get(e)
            if info['owner'] != service and info['owner'] in LG.SERVICES:
                continue  # строка с типом события в чужом сервисе это чтение (служба обработчика), не издание
            if info['topic'] not in topics:
                rep.err(f, 'тема %s события %s не создаётся в topics.sh' % (info['topic'], e))
            elif info['topic'] not in rights.get((service, 'Write'), set()):
                rep.err(f, 'у сервиса %s, издающего %s, нет права WRITE на тему %s в topics.sh' % (service, e, info['topic']))
    return stats


def main():
    rep = L.Report('Документы = код')
    ops = check_operations(rep)
    ev = check_events(rep)
    rep.fact('операций со статусом done: %d, методов контроллеров: %d; событий среза 1 и 2: %d, из них издаётся в коде: %d; потребителей реализовано: %d, придут позже: %d'
             % (ops['done'], ops['handlers'], ev['events'], ev['events'] - ev['unpublished'], ev['consumers'], ev['later']))
    return rep.finish()


if __name__ == '__main__':
    sys.exit(main())
