# -*- coding: utf-8 -*-
"""Проверка контракта событий AsyncAPI и каталога событий (шаг 10 Ф2).

Запуск:
    python3 check_asyncapi.py                  проверить всё
    python3 check_asyncapi.py --write-catalog  перезаписать сгенерированную часть events/README.md

Требуется PyYAML и jsonschema. Корень репозитория берётся из переменной REPO
или определяется командой git.
"""
import copy
import json
import os
import re
import subprocess
import sys

import yaml
from jsonschema import Draft7Validator, FormatChecker

try:
    REPO = os.environ.get('REPO') or subprocess.check_output(
        ['git', 'rev-parse', '--show-toplevel'], cwd=os.path.dirname(os.path.abspath(__file__)),
        text=True).strip()
except Exception:  # pragma: no cover
    REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))

DOCS = os.path.join(REPO, 'docs')
AX = os.path.join(DOCS, '06-api/asyncapi/asyncapi.yaml')
EVDIR = os.path.join(DOCS, '06-api/events')
README = os.path.join(EVDIR, 'README.md')
ADR3 = os.path.join(DOCS, '05-architecture/adr/ADR-003-kafka-events.md')
CONV = os.path.join(DOCS, '05-architecture/conventions.md')
C4 = os.path.join(DOCS, '05-architecture/c4-components.md')
BC = os.path.join(DOCS, '04-domain/bounded-contexts.md')

SERVICES = ['catalog-service', 'inventory-service', 'order-service', 'payment-service',
            'delivery-service', 'platform-service', 'finance-service']
AGG_OWNER = {
    'SellerProfile': 'catalog-service', 'ApiKey': 'catalog-service', 'Product': 'catalog-service',
    'ProductStock': 'inventory-service', 'Key': 'inventory-service', 'Reservation': 'inventory-service',
    'Order': 'order-service', 'Payment': 'payment-service', 'Delivery': 'delivery-service',
    'User': 'platform-service', 'Ticket': 'platform-service', 'Notification': 'platform-service',
    'Parameter': 'platform-service', 'AuditRecord': 'все сервисы',
    'Dispute': 'finance-service', 'SellerBalance': 'finance-service', 'Withdrawal': 'finance-service',
}
CTX_SERVICE = {'4.1': 'platform-service', '4.2': 'catalog-service', '4.3': 'catalog-service',
               '4.4': 'inventory-service', '4.5': 'order-service', '4.6': 'payment-service',
               '4.7': 'delivery-service', '4.8': 'platform-service', '4.9': 'finance-service',
               '4.10': 'finance-service', '4.11': 'platform-service', '4.12': 'platform-service'}
RU2TECH = {
    'пользователь зарегистрирован': 'user.registered', 'роль назначена': 'user.role-assigned',
    'e-mail изменён': 'user.email-changed', 'телефон подтверждён': 'user.phone-confirmed',
    'телефон изменён': 'user.phone-changed', 'пользователь деактивирован': 'user.deactivated',
    'пользователь анонимизирован': 'user.anonymized',
    'профиль одобрен': 'seller.approved', 'профиль отклонён': 'seller.rejected',
    'профиль возвращён на доработку': 'seller.returned', 'продавец заблокирован': 'seller.blocked',
    'блокировка снята': 'seller.unblocked', 'api-ключ выпущен': 'api-key.issued', 'api-ключ отозван': 'api-key.revoked',
    'товар создан': 'product.created', 'товар изменён': 'product.updated', 'товар опубликован': 'product.published',
    'товар отклонён': 'product.rejected', 'товар заблокирован': 'product.blocked',
    'резерв истёк': 'reservation.expired', 'резерв снят': 'reservation.released', 'остаток изменился': 'stock.changed',
    'ключ аннулирован': 'key.voided', 'ключ заменён': 'key.replaced', 'замена ключа не удалась': 'key.replacement-failed',
    'заказ создан': 'order.created', 'заказ оплачен': 'order.paid', 'заказ отменён': 'order.cancelled',
    'заказ выдан': 'order.issued', 'заказ возвращён': 'order.refunded', 'адрес доставки обновлён': 'order.address-updated',
    ('4.5', 'вернуть деньги'): 'order.refund-requested',
    'платёж подтверждён': 'payment.confirmed', 'платёж отклонён': 'payment.rejected', 'платёж возвращён': 'payment.refunded',
    'возврат ждёт администратора': 'payment.refund-escalated',
    'письмо принято': 'delivery.accepted', 'выдача доставлена': 'delivery.delivered', 'выдача не удалась': 'delivery.failed',
    '30 минут без доставки': 'delivery.overdue', 'продавец не ответил': 'delivery.seller-timeout',
    'обращение создано': 'ticket.created', 'обращение решено': 'ticket.resolved',
    'спор открыт': 'dispute.opened', 'спор решён': 'dispute.resolved', 'спор возвращён модератору': 'dispute.returned',
    ('4.9', 'вернуть деньги'): 'dispute.refund-requested', ('4.9', 'заменить ключ'): 'dispute.key-replacement-requested',
    'деньги доступны': 'balance.funds-available', 'заявка на вывод создана': 'withdrawal.created',
    'заявка выплачена': 'withdrawal.paid', 'заявка отклонена': 'withdrawal.rejected',
    'письмо не доставлено': 'notification.failed', 'параметры изменены': 'config.changed',
}
PII_NAMES = re.compile(r'(e-?mail|phone|password|passwd|token|secret|keyvalue|cardnumber|firstname|lastname|fullname|^name$)', re.I)

problems = []


def err(msg):
    problems.append(msg)


def camel(s):
    return ''.join(p.capitalize() for p in re.split(r'[.\-]', s))


# ----------------------------------------------------------------- разбор
def load():
    with open(AX, encoding='utf-8') as f:
        return yaml.safe_load(f)


def resolve(doc, ref):
    if not ref.startswith('#/'):
        raise KeyError(ref)
    cur = doc
    for part in ref[2:].split('/'):
        part = part.replace('~1', '/').replace('~0', '~')
        cur = cur[part]
    return cur


def walk(node, path=''):
    if isinstance(node, dict):
        yield path, node
        for k, v in node.items():
            yield from walk(v, path + '/' + str(k))
    elif isinstance(node, list):
        for i, v in enumerate(node):
            yield from walk(v, path + '/' + str(i))


def table_rows(text, start_pat, end_pat=None):
    """Строки таблицы (ячейки) в разделе, начинающемся с заголовка start_pat."""
    m = re.search(start_pat, text, re.M)
    if not m:
        return []
    body = text[m.end():]
    if end_pat:
        e = re.search(end_pat, body, re.M)
        if e:
            body = body[:e.start()]
    rows = []
    for ln in body.split('\n'):
        if ln.startswith('|') and not re.match(r'^\|\s*-', ln):
            rows.append([c.strip() for c in ln.strip().strip('|').split('|')])
    return rows


def tokens(cell):
    return re.findall(r'`([^`]+)`', cell)


# ---------------------------------------------------------------- проверки
def check_structure(doc):
    if doc.get('asyncapi') != '3.0.0':
        err('asyncapi: нужна версия 3.0.0')
    for k in ('info', 'channels', 'operations', 'components', 'servers'):
        if k not in doc:
            err('нет раздела ' + k)
    info = doc.get('info', {})
    for k in ('title', 'version', 'description'):
        if not info.get(k):
            err('info.%s пуст' % k)
    # ссылки
    for path, node in walk(doc):
        if '$ref' in node:
            r = node['$ref']
            if not isinstance(r, str) or not r.startswith('#/'):
                err('внешняя или неверная ссылка %s в %s' % (r, path))
                continue
            try:
                resolve(doc, r)
            except Exception:
                err('ссылка не разрешается: %s (в %s)' % (r, path))


def parse_adr_topics():
    text = open(ADR3, encoding='utf-8').read()
    rows = table_rows(text, r'^\*\*Темы\.\*\*', r'^Тема R2')
    res = {}
    for r in rows[1:]:
        if len(r) < 4:
            continue
        t = re.match(r'`?([^`\s]+)`?', r[0]).group(1)
        if t.startswith('<'):
            continue
        ret = r[3]
        if ret.startswith('Сжатие'):
            ret = 'compact'
        elif ret.startswith('30'):
            ret = '30d'
        elif ret.startswith('7'):
            ret = '7d'
        res[t] = (r[1].strip('`'), int(r[2]), ret)
    return res


def parse_conv_topics():
    text = open(CONV, encoding='utf-8').read()
    rows = table_rows(text, r'^### 11\.3\. Темы и ключи', r'^Правила:')
    res = {}
    for r in rows[1:]:
        if len(r) < 5:
            continue
        t = re.match(r'`?([^`\s]+)`?', r[0]).group(1)
        if t.startswith('<'):
            continue
        ret = r[4]
        ret = 'compact' if ret.startswith('Сжатие') else '30d' if ret.startswith('30') else '7d'
        res[t] = (r[1].strip('`'), r[2].replace('`', ''), int(r[3]), ret)
    return res


def check_channels(doc):
    chans = doc['channels']
    adr = parse_adr_topics()
    conv = parse_conv_topics()
    addr = {c['address']: (n, c) for n, c in chans.items()}
    for t, (pub, parts, ret) in adr.items():
        if t not in addr:
            err('тема %s из ADR-003 отсутствует в контракте' % t)
            continue
        c = addr[t][1]
        k = c['bindings']['kafka']
        if k['partitions'] != parts:
            err('тема %s: партиций %s, в ADR-003 %s' % (t, k['partitions'], parts))
        tc = k['topicConfiguration']
        if ret == 'compact':
            if tc.get('cleanup.policy') != ['compact']:
                err('тема %s: нужно сжатие по ключу' % t)
        else:
            want = 604800000 if ret == '7d' else 2592000000
            if tc.get('retention.ms') != want:
                err('тема %s: хранение %s, в ADR-003 %s' % (t, tc.get('retention.ms'), ret))
    for t, (pub, key, parts, ret) in conv.items():
        if t not in addr:
            err('тема %s из conventions.md отсутствует в контракте' % t)
            continue
        c = addr[t][1]
        if c['bindings']['kafka']['partitions'] != parts:
            err('тема %s: партиций не совпадает с conventions.md' % t)
        if c['x-key-rule'].replace('`', '') != key:
            err('тема %s: правило ключа расходится с conventions.md' % t)
        owner = c['x-owner-service']
        if (pub == 'Все сервисы' and owner != 'все сервисы') or (pub != 'Все сервисы' and owner != pub):
            err('тема %s: издатель %s, в conventions.md %s' % (t, owner, pub))
    extra = [t for t in addr if not t.endswith('.dlq') and t not in conv]
    if extra:
        err('в контракте есть темы, которых нет в conventions.md: %s' % extra)
    # DLQ
    consumed = set()
    for name, op in doc['operations'].items():
        if op['action'] == 'receive' and op['x-consumer']['handler'] != 'config-listener':
            ch = resolve(doc, op['channel']['$ref'])
            consumed.add(ch['address'])
    for t in consumed:
        if t + '.dlq' not in addr:
            err('нет темы %s.dlq' % t)
        else:
            c = addr[t + '.dlq'][1]
            if c['bindings']['kafka']['topicConfiguration'].get('retention.ms') != 1209600000:
                err('тема %s.dlq: хранение должно быть 14 суток' % t)
    for t in addr:
        if t.endswith('.dlq') and t[:-4] not in consumed:
            err('тема %s не нужна: у %s нет потребителей с повторами' % (t, t[:-4]))


def collect(doc):
    """Сообщения событий: имя -> (ключ компонента, сообщение, канал)."""
    comps = doc['components']['messages']
    msg2chan = {}
    for cn, ch in doc['channels'].items():
        for mk, m in ch['messages'].items():
            key = m['$ref'].split('/')[-1]
            if key == 'DeadLetter':
                continue
            if key in msg2chan:
                err('сообщение %s подключено к двум каналам' % key)
            msg2chan[key] = (cn, ch, mk)
    events = {}
    for key, m in comps.items():
        if key == 'DeadLetter':
            continue
        events[m['name']] = (key, m, msg2chan.get(key))
        if key not in msg2chan:
            err('сообщение %s не подключено ни к одному каналу' % key)
    return events


def check_messages(doc, events):
    schemas = doc['components']['schemas']
    for t, (key, m, ch) in events.items():
        if not re.match(r'^[a-z]+(-[a-z]+)*\.[a-z]+(-[a-z]+)*$', t):
            err('%s: неверное имя события' % t)
        if camel(t) != key:
            err('%s: ключ компонента должен быть %s' % (t, camel(t)))
        for k in ('title', 'summary', 'description', 'payload', 'examples', 'x-release', 'x-status',
                  'x-owner-service', 'x-aggregate', 'x-publisher-components', 'x-subject-field', 'x-schema-version'):
            if k not in m or m[k] in (None, '', []):
                err('%s: нет поля %s' % (t, k))
        if m.get('x-release') not in ('R1', 'R2'):
            err('%s: x-release должен быть R1 или R2' % t)
        if m.get('x-status') != ('draft' if m.get('x-release') == 'R2' else 'stable'):
            err('%s: статус %s не соответствует релизу' % (t, m.get('x-status')))
        if m.get('x-schema-version') != 1:
            err('%s: схема версии %s, ожидалась 1 (двойная публикация описывается отдельным сообщением)' % (t, m.get('x-schema-version')))
        env = schemas.get(key + 'Envelope')
        data = schemas.get(key + 'Data')
        if not env or not data:
            err('%s: нет схем %sEnvelope или %sData' % (t, key, key))
            continue
        if m['payload'].get('$ref') != '#/components/schemas/%sEnvelope' % key:
            err('%s: payload должен ссылаться на %sEnvelope' % (t, key))
        # издатель и владелец
        agg = m['x-aggregate']
        owner = AGG_OWNER.get(agg)
        if owner is None:
            err('%s: неизвестный агрегат %s' % (t, agg))
        elif m['x-owner-service'] != owner:
            err('%s: издатель %s не владеет агрегатом %s (владелец %s)' % (t, m['x-owner-service'], agg, owner))
        if ch:
            cowner = ch[1]['x-owner-service']
            if cowner != 'все сервисы' and cowner != m['x-owner-service']:
                err('%s: тема %s принадлежит %s, а издатель %s' % (t, ch[1]['address'], cowner, m['x-owner-service']))
        # константы конверта
        consts = env['allOf'][1]['properties']
        if consts['type'].get('const') != t:
            err('%s: const типа в конверте не совпадает' % t)
        if consts['aggregatetype'].get('const') != agg:
            err('%s: const агрегата в конверте не совпадает' % t)
        src = consts['source']
        if owner == 'все сервисы':
            if src.get('enum') != SERVICES:
                err('%s: source должен допускать все сервисы' % t)
        elif src.get('const') != owner:
            err('%s: source в конверте %s, владелец %s' % (t, src, owner))
        # ключ упорядочения
        sf = m['x-subject-field']
        if sf not in data['properties']:
            err('%s: поле ключа %s отсутствует в данных' % (t, sf))
        elif sf not in data['required']:
            err('%s: поле ключа %s необязательно' % (t, sf))
        # схема данных
        check_data_schema(t, m, data)


def check_data_schema(t, m, data):
    if data.get('type') != 'object' or data.get('additionalProperties') is not False:
        err('%s: схема данных должна быть закрытым объектом' % t)
    if not data.get('required'):
        err('%s: нет обязательных полей' % t)
    props = data.get('properties', {})
    for r in data.get('required', []):
        if r not in props:
            err('%s: обязательное поле %s не описано' % (t, r))
    pii_event = bool(m.get('x-contains-pii'))
    has_addr = False
    for name, p in props.items():
        if not p.get('description'):
            err('%s.%s: нет описания' % (t, name))
        if not re.match(r'^[a-z][A-Za-z0-9]*$', name):
            err('%s.%s: имя поля не в camelCase' % (t, name))
        ref = p.get('$ref', '')
        if ref.endswith('/DeliveryAddress'):
            has_addr = True
        elif PII_NAMES.search(name):
            err('%s.%s: имя поля похоже на персональные данные или секрет' % (t, name))
        if name.endswith('Id') and name not in ('objectId',):
            if p.get('format') != 'uuid':
                err('%s.%s: идентификатор должен быть uuid' % (t, name))
        if name in ('amount', 'total', 'commission', 'unitPrice') and not ref.endswith('/Money'):
            err('%s.%s: сумма должна быть Money' % (t, name))
        if name.endswith('At') and not ref.endswith('/Timestamp'):
            err('%s.%s: момент времени должен быть Timestamp' % (t, name))
        if name.endswith('Seconds') and p.get('type') != 'integer':
            err('%s.%s: длительность это целое число секунд' % (t, name))
        if 'enum' in p:
            for v in p['enum']:
                if not re.match(r'^[a-z][a-z0-9_-]*$', v):
                    err('%s.%s: значение перечисления %s не в snake_case' % (t, name, v))
        if p.get('type') == 'array' and 'items' not in p:
            err('%s.%s: у массива нет items' % (t, name))
    if has_addr and not pii_event:
        err('%s: содержит адрес, но не помечено x-contains-pii' % t)
    if pii_event and not has_addr:
        err('%s: помечено x-contains-pii, но адреса нет' % t)
    if has_addr and t not in ('order.paid', 'order.address-updated'):
        err('%s: персональные данные в событии не разрешены (conventions.md, раздел 11.7)' % t)


def check_operations(doc, events):
    ops = doc['operations']
    sends = {}
    recv = {}
    seen = set()
    handlers = {}
    for name, op in ops.items():
        if op['action'] not in ('send', 'receive'):
            err('%s: неверное действие' % name)
            continue
        ch = resolve(doc, op['channel']['$ref'])
        if len(op['messages']) != 1:
            err('%s: должно быть ровно одно сообщение' % name)
            continue
        mref = op['messages'][0]['$ref']
        # '#/channels/<канал>/messages/<ключ>'
        parts = mref.split('/')
        if parts[2] != op['channel']['$ref'].split('/')[-1]:
            err('%s: сообщение из другого канала' % name)
        mkey = resolve(doc, mref)['$ref'].split('/')[-1]
        msg = doc['components']['messages'][mkey]
        t = msg['name']
        if op['action'] == 'send':
            if t in sends:
                err('%s: у события два издателя (%s и %s)' % (t, sends[t], name))
            sends[t] = name
            xp = op.get('x-publisher', {})
            if xp.get('outbox') is not True:
                err('%s: публикация должна идти через Outbox' % name)
            if xp.get('service') != msg['x-owner-service']:
                err('%s: издатель операции не совпадает с владельцем' % name)
            if xp.get('components') != msg['x-publisher-components']:
                err('%s: компоненты издателя расходятся с сообщением' % name)
            if name != 'publish' + mkey:
                err('%s: имя операции должно быть publish%s' % (name, mkey))
        else:
            xc = op.get('x-consumer')
            if not xc:
                err('%s: нет x-consumer' % name)
                continue
            for k in ('service', 'handler', 'effect', 'release', 'idempotency', 'errorHandling'):
                if not xc.get(k):
                    err('%s: нет x-consumer.%s' % (name, k))
            if xc.get('service') not in SERVICES:
                err('%s: неизвестный сервис %s' % (name, xc.get('service')))
            pair = (t, xc['service'], xc['handler'])
            if pair in seen:
                err('%s: повтор потребителя' % name)
            seen.add(pair)
            recv.setdefault(t, []).append(xc)
            handlers.setdefault(xc['service'], set()).add(xc['handler'])
            idem = xc.get('idempotency', {})
            if not idem.get('key') or not idem.get('businessGuard'):
                err('%s: не указан ключ идемпотентности или защита от дубля' % name)
            eh = xc.get('errorHandling', {})
            if xc['handler'] == 'config-listener':
                if idem.get('key') != '(key, version)':
                    err('%s: слушатель параметров дедуплицирует по (key, version)' % name)
                if t != 'config.changed':
                    err('%s: слушатель параметров читает только config.changed' % name)
            else:
                if idem.get('level2') != 'processed_event':
                    err('%s: нужна таблица processed_event' % name)
                if idem.get('key') != "(consumer = '%s', event_id)" % xc['handler']:
                    err('%s: ключ идемпотентности должен быть (consumer = \'%s\', event_id)' % (name, xc['handler']))
                if eh.get('deadLetterTopic') != ch['address'] + '.dlq':
                    err('%s: DLQ должна быть %s.dlq' % (name, ch['address']))
                if 'блокирующие' not in eh.get('retries', '') or '1, 5, 25' not in eh.get('retries', ''):
                    err('%s: повторы должны быть 1, 5, 25 секунд' % name)
            if xc['release'] not in ('R1', 'R2'):
                err('%s: неверный релиз потребителя' % name)
            if xc['release'] == 'R1' and msg['x-release'] == 'R2':
                err('%s: потребитель R1 у события R2' % name)
            exp = 'receive%sBy%s%s' % (mkey, ''.join(p.capitalize() for p in xc['service'].split('-')),
                                       ''.join(p.capitalize() for p in xc['handler'].split('-')))
            if name != exp:
                err('%s: имя операции должно быть %s' % (name, exp))
            # издатель не читает собственные события, кроме platform-service и catalog-service (модули)
            if xc['service'] == msg['x-owner-service'] and xc['service'] not in ('platform-service', 'catalog-service', 'finance-service'):
                err('%s: сервис читает собственное событие через Kafka' % name)
    for t, (key, m, ch) in events.items():
        if t not in sends:
            err('%s: нет операции публикации' % t)
        has_reason = bool(m.get('x-no-consumer-reason'))
        if t not in recv and not has_reason:
            err('%s: нет потребителей и не объяснено, почему (x-no-consumer-reason)' % t)
        if t in recv and has_reason:
            err('%s: есть потребители, но указана причина их отсутствия' % t)
    return recv


def check_examples(doc, events):
    schemas_root = doc
    ids = set()
    for t, (key, m, ch) in events.items():
        env = m['examples'][0]['payload']
        # проверка по схеме
        schema = {'$ref': '#/components/schemas/%sEnvelope' % key, 'components': doc['components']}
        v = Draft7Validator(schema, format_checker=FormatChecker())
        errs = sorted(v.iter_errors(env), key=lambda e: list(e.path))
        for e in errs[:3]:
            err('%s: пример не проходит схему: %s (%s)' % (t, e.message[:140], '/'.join(str(x) for x in e.path)))
        if env.get('type') != t:
            err('%s: тип в примере не совпадает' % t)
        if env.get('subject') != env.get('data', {}).get(m['x-subject-field']):
            err('%s: subject примера не равен полю %s' % (t, m['x-subject-field']))
        if env['id'] in ids:
            err('%s: повтор идентификатора события в примерах' % t)
        ids.add(env['id'])
        hdr = m['examples'][0].get('headers', {})
        if hdr.get('ce_type') != t or hdr.get('traceparent') != env.get('traceparent'):
            err('%s: заголовки примера расходятся с конвертом' % t)
        # файл примера
        p = os.path.join(EVDIR, 'examples', t + '.json')
        if not os.path.exists(p):
            err('%s: нет файла примера' % t)
        else:
            with open(p, encoding='utf-8') as f:
                fe = json.load(f)
            if fe != env:
                err('%s: файл примера расходится с контрактом' % t)
    files = [f for f in os.listdir(os.path.join(EVDIR, 'examples')) if f.endswith('.json')] if os.path.isdir(os.path.join(EVDIR, 'examples')) else []
    for f in files:
        if f[:-5] not in events:
            err('лишний файл примера %s' % f)


def selftest(doc, events):
    """Проверка самой проверки: порча примера должна быть замечена."""
    t = 'order.paid'
    key, m, ch = events[t]
    schema = {'$ref': '#/components/schemas/%sEnvelope' % key, 'components': doc['components']}
    v = Draft7Validator(schema, format_checker=FormatChecker())
    base = copy.deepcopy(m['examples'][0]['payload'])
    cases = {}
    c = copy.deepcopy(base); del c['data']['orderId']; cases['нет обязательного поля'] = c
    c = copy.deepcopy(base); c['data']['extra'] = 1; cases['лишнее поле'] = c
    c = copy.deepcopy(base); c['data']['total']['amount'] = 12.5; cases['дробная сумма'] = c
    c = copy.deepcopy(base); c['data']['total']['currency'] = 'USD'; cases['чужая валюта'] = c
    c = copy.deepcopy(base); c['data']['orderId'] = 'не-uuid'; cases['неверный uuid'] = c
    c = copy.deepcopy(base); c['time'] = '2026-10-03 12:00:00'; cases['время не RFC 3339'] = c
    c = copy.deepcopy(base); c['type'] = 'order.cancelled'; cases['чужой тип'] = c
    c = copy.deepcopy(base); c['source'] = 'payment-service'; cases['чужой издатель'] = c
    c = copy.deepcopy(base); c['traceparent'] = 'abc'; cases['неверный traceparent'] = c
    c = copy.deepcopy(base); c['data']['delivery']['address'] = 'не адрес'; cases['неверный e-mail'] = c
    for name, c in cases.items():
        if not list(v.iter_errors(c)):
            err('самопроверка: порча «%s» не замечена' % name)


# ----------------------------------------------------- сверка с документами
def parse_c4_registry():
    text = open(C4, encoding='utf-8').read()
    rows = table_rows(text, r'^### 5\.3\. События', r'^Строка «Пользователь анонимизирован»')
    res = []
    for r in rows[1:]:
        if len(r) < 4:
            continue
        res.append((tokens(r[1]), tokens(r[2]), r[3]))
    return res


def check_registry(doc, events, recv):
    reg = parse_c4_registry()
    r1 = {t for t, (k, m, c) in events.items() if m['x-release'] == 'R1'}
    seen = set()
    all_handlers = set()
    for t, cs in recv.items():
        for c in cs:
            all_handlers.add(c['handler'])
    for evs, pubs, subs in reg:
        for t in evs:
            seen.add(t)
            if t not in events:
                err('c4-components.md, 5.3: события %s нет в контракте' % t)
                continue
            m = events[t][1]
            if m['x-release'] != 'R1':
                err('c4-components.md, 5.3: %s указано как R1, в контракте R2' % t)
            pcomp = [p for p in pubs if p not in all_handlers or p in m['x-publisher-components']]
            if sorted(set(pcomp)) != sorted(m['x-publisher-components']):
                err('c4-components.md, 5.3: издатель %s: %s, в контракте %s' % (t, pubs, m['x-publisher-components']))
            toks = tokens(subs)
            only = []
            if 'только' in subs:
                only = [x for x in toks if x in events]
            if only and t not in only:
                want = set()
            else:
                want = {x for x in toks if x in all_handlers}
            have = {c['handler'] for c in recv.get(t, []) if c['release'] == 'R1'}
            if want != have:
                err('c4-components.md, 5.3: потребители %s: в таблице %s, в контракте %s' % (t, sorted(want), sorted(have)))
            if t == 'config.changed':
                svcs = {c['service'] for c in recv.get(t, [])}
                if svcs != {'catalog-service', 'delivery-service', 'inventory-service', 'order-service', 'payment-service'}:
                    err('config.changed: слушатели параметров во всех сервисах R1, кроме издателя: %s' % sorted(svcs))
    for t in r1 - seen:
        err('событие R1 %s отсутствует в c4-components.md, 5.3' % t)


def check_bounded_contexts(doc, events):
    text = open(BC, encoding='utf-8').read()
    published = {}
    for m in re.finditer(r'^### (4\.\d+)\. [^\n]*\n(.*?)(?=^### |^## )', text, re.M | re.S):
        ctx, body = m.group(1), m.group(2)
        row = re.search(r'^\| Публикует \| (.*?) \|\s*$', body, re.M)
        if not row:
            continue
        for ph in re.findall(r'«([^»]+)»', re.sub(r'\([^)]*\)', '', row.group(1))):
            k = ph.strip().lower()
            tech = RU2TECH.get((ctx, k)) or RU2TECH.get(k)
            if not tech:
                err('bounded-contexts.md, %s: событие «%s» не сопоставлено с техническим именем' % (ctx, ph))
                continue
            if tech not in events:
                err('bounded-contexts.md, %s: %s нет в контракте' % (ctx, tech))
                continue
            owner = events[tech][1]['x-owner-service']
            if owner != CTX_SERVICE[ctx]:
                err('bounded-contexts.md, %s: %s публикует %s, а в контракте владелец %s' % (ctx, tech, CTX_SERVICE[ctx], owner))
            published.setdefault(tech, []).append(ctx)
    for t in events:
        if t != 'audit.recorded' and t not in published:
            err('событие %s есть в контракте, но не указано в «Публикует» в bounded-contexts.md' % t)
    for t, ctxs in published.items():
        if len(ctxs) > 1:
            err('событие %s указано как публикуемое в нескольких контекстах: %s' % (t, ctxs))


def check_service_docs(doc, events, recv):
    arch = os.path.join(DOCS, '05-architecture')
    for svc in SERVICES:
        p = os.path.join(arch, 'c4-components-%s.md' % svc)
        if not os.path.exists(p):
            continue
        text = open(p, encoding='utf-8').read()
        docev = set()
        for m in re.finditer(r'Читает:([^|\n]*)', text):
            docev.update(tokens(m.group(1)))
        r1 = set()
        allr = set()
        for t, cs in recv.items():
            for c in cs:
                if c['service'] == svc:
                    allr.add(t)
                    if c['release'] == 'R1':
                        r1.add(t)
        if not r1 <= docev:
            err('%s: события R1 %s не указаны в «Читает»' % (os.path.basename(p), sorted(r1 - docev)))
        if not docev <= allr:
            err('%s: «Читает» содержит %s, а в контракте таких потребителей нет' % (os.path.basename(p), sorted(docev - allr)))
        # публикации сервиса
        pub = set(t for t, (k, m, c) in events.items() if m['x-owner-service'] == svc and m['x-release'] == 'R1')
        pdoc = set()
        for m in re.finditer(r'\| `outbox-relay` \| `kafka` \|([^|\n]*)\|', text):
            pdoc.update(tokens(m.group(1)))
        if pdoc:
            pdoc.discard('audit.recorded')
            if svc == 'platform-service':
                continue
            if pdoc != pub:
                err('%s: публикации %s расходятся с контрактом %s' % (os.path.basename(p), sorted(pdoc), sorted(pub)))


# ----------------------------------------------------------------- каталог
def short(s, n=170):
    s = s.replace('|', '/')
    return s


def render_catalog(doc, events, recv):
    chans = doc['channels']
    out = []
    out.append('### Темы\n')
    out.append('| Тема | Издатель | Ключ записи | Партиций | Хранение | Событий | Тема недоставленного |')
    out.append('| --- | --- | --- | --- | --- | --- | --- |')
    for cn, ch in chans.items():
        if cn.endswith('Dlq'):
            continue
        k = ch['bindings']['kafka']
        tc = k['topicConfiguration']
        ret = 'сжатие по ключу' if tc['cleanup.policy'] == ['compact'] else '%d суток' % (tc['retention.ms'] // 86400000)
        n = len(ch['messages'])
        dlq = '`%s.dlq`, %s' % (ch['address'], ch.get('x-dlq-alert', '')) if (cn + 'Dlq') in chans else 'нет'
        owner = ch['x-owner-service']
        out.append('| `%s` | %s | %s | %d | %s | %d | %s |' % (ch['address'], '`%s`' % owner if owner != 'все сервисы' else 'все сервисы', ch['x-key-rule'].replace('`', ''), k['partitions'], ret, n, dlq))
    out.append('')
    for rel, title in (('R1', 'События R1'), ('R2', 'События R2 (черновик)')):
        out.append('### %s\n' % title)
        for cn, ch in chans.items():
            if cn.endswith('Dlq'):
                continue
            rows = []
            for mk, mr in ch['messages'].items():
                key = mr['$ref'].split('/')[-1]
                m = doc['components']['messages'][key]
                if m['x-release'] != rel:
                    continue
                t = m['name']
                cons = {}
                for c in recv.get(t, []):
                    cons.setdefault(c['service'], []).append(c['handler'] + (' (R2)' if c['release'] == 'R2' and rel == 'R1' else ''))
                if cons:
                    cs = '; '.join('`%s`: %s' % (s, ', '.join('`%s`' % h.replace(' (R2)', '') + (' (R2)' if '(R2)' in h else '') for h in hs)) for s, hs in cons.items())
                else:
                    cs = 'нет, ' + m['x-no-consumer-reason'][0].lower() + m['x-no-consumer-reason'][1:] if m.get('x-no-consumer-reason') else 'нет'
                pii = ' Персональные данные.' if m.get('x-contains-pii') else ''
                rows.append('| [`%s`](examples/%s.json) | `%s` | %s | %s |' % (
                    t, t, m['x-subject-field'], '`%s`' % '`, `'.join(m['x-publisher-components']), (m['summary'] + '.' + pii) + ' ' + ''))
                rows[-1] = rows[-1].rstrip()
                rows[-1] = rows[-1][:-1].rstrip() + ' | ' + short(cs) + ' |'
            if rows:
                out.append('#### `%s`\n' % ch['address'])
                out.append('| Событие | Ключ записи | Компонент издателя | Что произошло | Потребители |')
                out.append('| --- | --- | --- | --- | --- |')
                out.extend(rows)
                out.append('')
    out.append('### Потребители и защита от дублей\n')
    out.append('Каждый обработчик пишет `processed_event(consumer, event_id)` в одной транзакции с изменением данных, повторы 1, 5 и 25 секунд, затем `<тема>.dlq`. В таблице второй уровень защиты: бизнес-проверка или ограничение базы, которые остановят дубль, даже если первый уровень обойдён. Слушатели параметров работают иначе и описаны в [conventions.md](../../05-architecture/conventions.md), раздел 12.4.\n')
    by_service = {}
    for t, cs in recv.items():
        for c in cs:
            by_service.setdefault(c['service'], []).append((c['handler'], t, c))
    order = {t: i for i, t in enumerate(events)}
    for svc in SERVICES:
        items = by_service.get(svc)
        if not items:
            continue
        out.append('#### `%s`\n' % svc)
        out.append('| Обработчик | Событие | Релиз | Что делает | Защита от дубля |')
        out.append('| --- | --- | --- | --- | --- |')
        for h, t, c in sorted(items, key=lambda x: (x[0], order[x[1]])):
            out.append('| `%s` | `%s` | %s | %s | %s |' % (h, t, c['release'], short(c['effect']), short(c['idempotency']['businessGuard'])))
        out.append('')
    return '\n'.join(out).rstrip() + '\n'


BEGIN = '<!-- catalog:begin -->'
END = '<!-- catalog:end -->'


def check_catalog(doc, events, recv, write=False):
    text = open(README, encoding='utf-8').read() if os.path.exists(README) else ''
    new = render_catalog(doc, events, recv)
    if BEGIN not in text or END not in text:
        err('в events/README.md нет маркеров %s и %s' % (BEGIN, END))
        return
    head, rest = text.split(BEGIN, 1)
    cur, tail = rest.split(END, 1)
    if cur.strip('\n') != new.strip('\n'):
        if write:
            with open(README, 'w', encoding='utf-8') as f:
                f.write(head + BEGIN + '\n' + new + END + tail)
            print('каталог событий перезаписан')
        else:
            err('events/README.md: сгенерированная часть устарела, запустите с --write-catalog')
    r1 = sum(1 for t, (k, m, c) in events.items() if m['x-release'] == 'R1')
    r2 = len(events) - r1
    if ('%d событий' % len(events)) not in head or ('R1 %d' % r1) not in head or ('R2 %d' % r2) not in head:
        err('events/README.md: число событий в шапке (%d, R1 %d, R2 %d) не совпадает с контрактом' % (len(events), r1, r2))


def main():
    write = '--write-catalog' in sys.argv
    doc = load()
    check_structure(doc)
    if problems:
        return finish(doc, None)
    check_channels(doc)
    events = collect(doc)
    check_messages(doc, events)
    recv = check_operations(doc, events)
    check_examples(doc, events)
    selftest(doc, events)
    check_registry(doc, events, recv)
    check_bounded_contexts(doc, events)
    check_service_docs(doc, events, recv)
    check_catalog(doc, events, recv, write)
    return finish(doc, events)


def finish(doc, events):
    if events is not None:
        r1 = sum(1 for t, (k, m, c) in events.items() if m['x-release'] == 'R1')
        topics = len([c for n, c in doc['channels'].items() if not n.endswith('Dlq')])
        dlq = len([n for n in doc['channels'] if n.endswith('Dlq')])
        ncons = sum(1 for o in doc['operations'].values() if o['action'] == 'receive')
        print('событий %d (R1 %d, R2 %d), тем %d, тем DLQ %d, потребителей %d, операций %d' % (
            len(events), r1, len(events) - r1, topics, dlq, ncons, len(doc['operations'])))
    if problems:
        for p in problems:
            print('  - ' + p)
        print('проблем: %d' % len(problems))
        return 1
    print('проблем: 0')
    return 0


if __name__ == '__main__':
    sys.exit(main())
