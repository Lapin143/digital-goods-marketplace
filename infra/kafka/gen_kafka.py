#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Темы и права доступа Kafka из контракта AsyncAPI.

    python3 infra/kafka/gen_kafka.py            записать infra/kafka/topics.sh
    python3 infra/kafka/gen_kafka.py --check    проверить, что topics.sh соответствует AsyncAPI (код выхода 1, если нет)
    python3 infra/kafka/gen_kafka.py --print    показать темы и права в виде таблицы

Источник правды один: docs/06-api/asyncapi/asyncapi.yaml. Темы берутся из каналов (число партиций и срок хранения из
привязки Kafka), права из операций выпуска R1:
  - издатель темы (x-publisher.service) получает WRITE на тему, «все сервисы» это шесть прикладных сервисов;
  - потребитель (x-consumer.service) получает READ на тему и READ на свою группу (consumerGroup);
  - потребитель, у которого указана тема недоставленных (errorHandling.deadLetterTopic), получает WRITE на неё;
  - потребитель без группы (platform.config, чтение с начала темы) получает READ только на тему.
Операции выпуска R2 и их темы (finance.events) в стенд Ф3 не входят. Принципал Kafka это CN сертификата клиента
(ssl.principal.mapping.rules в server.properties), то есть имя сервиса. Всё, что не разрешено, запрещено
(allow.everyone.if.no.acl.found=false). Пользователи kafka и kafka-init суперпользователи (super.users).
Нужен PyYAML (tools/docs-checks/requirements.txt).
"""
import hashlib
import os
import sys

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
ASYNCAPI = os.path.join(REPO, 'docs', '06-api', 'asyncapi', 'asyncapi.yaml')
OUT = os.path.join(HERE, 'topics.sh')

SERVICES = ['catalog-service', 'inventory-service', 'order-service', 'payment-service', 'delivery-service', 'platform-service']
ALL_SERVICES_MARK = 'все сервисы'
RELEASE = 'R1'


class SpecError(Exception):
    pass


def load():
    with open(ASYNCAPI, encoding='utf-8') as f:
        return yaml.safe_load(f)


def ref_name(ref):
    return ref['$ref'].split('/')[-1]


def build(doc):
    """Возвращает (темы, права). Темы: список словарей, права: словарь сервис -> {write, read, groups}."""
    channels = doc['channels']
    topics = {}      # адрес -> параметры
    publishers = {}  # адрес -> множество сервисов
    readers = {}     # адрес -> множество сервисов
    groups = {s: set() for s in SERVICES}
    dlq_writers = {}  # адрес темы недоставленных -> множество сервисов

    def need_channel(addr):
        for ch in channels.values():
            if ch['address'] == addr:
                return ch
        raise SpecError('в AsyncAPI нет канала с адресом %s' % addr)

    def add_topic(addr):
        ch = need_channel(addr)
        b = ch['bindings']['kafka']
        cfg = b.get('topicConfiguration', {})
        cleanup = cfg.get('cleanup.policy', ['delete'])[0]
        topics[addr] = {
            'name': addr,
            'partitions': int(b['partitions']),
            'cleanup': cleanup,
            'retention_ms': cfg.get('retention.ms'),
        }
        if int(b.get('replicas', 1)) != 1:
            raise SpecError('%s: стенд с одним брокером, replicas должно быть 1' % addr)

    for op_name, op in doc['operations'].items():
        tags = {t['name'] for t in op.get('tags', [])}
        if RELEASE not in tags:
            continue
        addr = channels[ref_name(op['channel'])]['address']
        add_topic(addr)
        if op['action'] == 'send':
            who = op['x-publisher']['service']
            svcs = SERVICES if who == ALL_SERVICES_MARK else [who]
            for s in svcs:
                if s not in SERVICES:
                    raise SpecError('%s: неизвестный издатель %s' % (op_name, s))
                publishers.setdefault(addr, set()).add(s)
        else:
            c = op['x-consumer']
            s = c['service']
            if s not in SERVICES:
                raise SpecError('%s: неизвестный потребитель %s' % (op_name, s))
            readers.setdefault(addr, set()).add(s)
            if c.get('consumerGroup'):
                groups[s].add(c['consumerGroup'])
            dlq = (c.get('errorHandling') or {}).get('deadLetterTopic')
            if dlq:
                add_topic(dlq)
                dlq_writers.setdefault(dlq, set()).add(s)

    acl = {s: {'write': set(), 'read': set(), 'groups': groups[s]} for s in SERVICES}
    for addr, ss in publishers.items():
        for s in ss:
            acl[s]['write'].add(addr)
    for addr, ss in readers.items():
        for s in ss:
            acl[s]['read'].add(addr)
    for addr, ss in dlq_writers.items():
        for s in ss:
            acl[s]['write'].add(addr)

    for addr in topics:
        if addr not in publishers and addr not in dlq_writers:
            raise SpecError('у темы %s нет издателя выпуска %s' % (addr, RELEASE))
    return [topics[k] for k in sorted(topics)], acl


def render(topics, acl):
    lines = []
    for t in topics:
        ret = t['retention_ms'] if t['retention_ms'] is not None else '-'
        lines.append('create_topic %s %d %s %s' % (t['name'], t['partitions'], t['cleanup'], ret))
    body_topics = '\n'.join(lines)
    acl_lines = []
    for s in SERVICES:
        a = acl[s]
        if a['write']:
            acl_lines.append('allow %s --operation Write --operation Describe %s' % (
                s, ' '.join('--topic %s' % x for x in sorted(a['write']))))
        if a['read']:
            acl_lines.append('allow %s --operation Read --operation Describe %s' % (
                s, ' '.join('--topic %s' % x for x in sorted(a['read']))))
        if a['groups']:
            acl_lines.append('allow %s --operation Read %s' % (
                s, ' '.join('--group %s' % x for x in sorted(a['groups']))))
    body_acl = '\n'.join(acl_lines)
    spec_hash = hashlib.sha256((body_topics + '\n' + body_acl).encode('utf-8')).hexdigest()[:12]
    return '''#!/bin/bash
# СОЗДАН СКРИПТОМ infra/kafka/gen_kafka.py ИЗ docs/06-api/asyncapi/asyncapi.yaml. РУКАМИ НЕ ПРАВИТЬ.
# Темы (%d) и права доступа (%d команд) выпуска %s. Подключается сценарием infra/kafka/init.sh, который задаёт
# функции create_topic и allow. Права: WRITE издателю, READ потребителю и его группе, WRITE потребителя на тему недоставленных.
SPEC_HASH=%s

# Темы: имя, партиций, cleanup.policy, retention.ms (или - для сжатия по ключу)
%s

# Права: сервис, операции и ресурсы
%s
''' % (len(topics), len(acl_lines), RELEASE, spec_hash, body_topics, body_acl)


def table(topics, acl):
    out = ['Темы:']
    for t in topics:
        out.append('  %-26s партиций %d  %-8s хранение %s' % (t['name'], t['partitions'], t['cleanup'],
                                                         t['retention_ms'] if t['retention_ms'] is not None else '-'))
    out.append('Права:')
    for s in SERVICES:
        a = acl[s]
        out.append('  %s' % s)
        out.append('    WRITE: %s' % ', '.join(sorted(a['write'])))
        out.append('    READ:  %s' % ', '.join(sorted(a['read'])))
        out.append('    GROUP: %s' % ', '.join(sorted(a['groups'])))
    return '\n'.join(out)


def main(argv):
    topics, acl = build(load())
    text = render(topics, acl)
    if '--print' in argv:
        print(table(topics, acl))
        return 0
    if '--check' in argv:
        try:
            with open(OUT, encoding='utf-8') as f:
                have = f.read()
        except FileNotFoundError:
            have = ''
        if have != text:
            print('ОШИБКА: infra/kafka/topics.sh не соответствует AsyncAPI, выполните python3 infra/kafka/gen_kafka.py')
            return 1
        print('infra/kafka/topics.sh соответствует AsyncAPI: %d тем' % len(topics))
        return 0
    with open(OUT, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)
    print('записано %s: %d тем' % (os.path.relpath(OUT, REPO), len(topics)))
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main(sys.argv[1:]))
    except SpecError as e:
        print('ОШИБКА:', e)
        sys.exit(1)
