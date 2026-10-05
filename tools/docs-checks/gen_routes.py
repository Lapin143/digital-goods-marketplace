#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Правила маршрутов сервисов из контрактов OpenAPI.

    python3 tools/docs-checks/gen_routes.py            записать services/<сервис>/src/main/resources/dgm/routes.json
    python3 tools/docs-checks/gen_routes.py --check    проверить, что файлы соответствуют OpenAPI (код выхода 1, если нет)

Источник правды один: docs/06-api/openapi/<сервис>.yaml. Фильтры каркаса (JwtFilter, CallerFilter) читают этот файл и не знают ни
одной области, роли или имени вызывающего сервиса из кода сервиса: контракт и защита не расходятся (roles-permissions.md, раздел 10).

Правило операции:
  security bearerJwt          scopes = области из security, roles = x-allowed-roles (токен нужен)
  security mutualTls          callers = x-allowed-callers (вход по клиентскому сертификату сервиса, токена нет)
  security webhookSignature   ни областей, ни ролей, ни вызывающих: подпись проверяет обработчик вебхука, а не фильтр токена
  security []                 публичный маршрут
  x-sms-session: denied       smsSession = "denied" (сессия по SMS не допускается, FT-1.7)
Что не описано в OpenAPI, закрыто: фильтр отвечает 404 (deny-by-default). Шлюз берёт маршруты из ADR-021, а не отсюда.
Нужен PyYAML (tools/docs-checks/requirements.txt).
"""
import json
import os
import sys

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.environ.get('REPO') or os.path.abspath(os.path.join(HERE, '..', '..'))
OPENAPI = os.path.join(REPO, 'docs', '06-api', 'openapi')
SERVICES = ['catalog-service', 'inventory-service', 'order-service', 'payment-service', 'delivery-service', 'platform-service']
METHODS = ('get', 'post', 'put', 'patch', 'delete')
COMMENT = ('СОЗДАН СКРИПТОМ tools/docs-checks/gen_routes.py ИЗ docs/06-api/openapi/%s.yaml. РУКАМИ НЕ ПРАВИТЬ. '
           'Чего нет в этом файле, фильтр каркаса закрывает ответом 404.')


class SpecError(Exception):
    pass


def out_path(service):
    return os.path.join(REPO, 'services', service, 'src', 'main', 'resources', 'dgm', 'routes.json')


def rule(service, path, method, op):
    where = '%s %s %s' % (service, method.upper(), path)
    for key in ('operationId',):
        if not op.get(key):
            raise SpecError('%s: нет %s' % (where, key))
    security = op.get('security')
    if security is None:
        raise SpecError('%s: не задано security (публичный маршрут пишется как security: [])' % where)
    scopes, roles, callers = [], [], []
    kinds = set()
    for alternative in security:
        for scheme, values in alternative.items():
            kinds.add(scheme)
            if scheme == 'bearerJwt':
                scopes += list(values or [])
            elif scheme not in ('mutualTls', 'webhookSignature'):
                raise SpecError('%s: неизвестная схема защиты %s' % (where, scheme))
    if len(kinds) > 1:
        raise SpecError('%s: несколько схем защиты (%s), фильтр ждёт одну' % (where, ', '.join(sorted(kinds))))
    if 'bearerJwt' in kinds:
        roles = list(op.get('x-allowed-roles') or [])
        if not scopes or not roles:
            raise SpecError('%s: bearerJwt без областей или без x-allowed-roles' % where)
    if 'mutualTls' in kinds:
        callers = list(op.get('x-allowed-callers') or [])
        if not callers:
            raise SpecError('%s: mutualTls без x-allowed-callers' % where)
    item = {
        'method': method.upper(),
        'path': path,
        'operationId': op['operationId'],
        'scopes': sorted(set(scopes)),
        'roles': roles,
        'callers': callers,
    }
    if op.get('x-sms-session') == 'denied':
        item['smsSession'] = 'denied'
    return item


def build(service):
    with open(os.path.join(OPENAPI, service + '.yaml'), encoding='utf-8') as f:
        spec = yaml.safe_load(f)
    routes = []
    for path in sorted(spec['paths']):
        for method in METHODS:
            op = spec['paths'][path].get(method)
            if op is not None:
                routes.append(rule(service, path, method, op))
    ids = [r['operationId'] for r in routes]
    if len(ids) != len(set(ids)):
        raise SpecError('%s: operationId повторяются' % service)
    doc = {'_comment': COMMENT % service, 'service': service, 'routes': routes}
    return json.dumps(doc, ensure_ascii=False, indent=2) + '\n'


def main(argv):
    check = '--check' in argv
    bad = 0
    for service in SERVICES:
        try:
            text = build(service)
        except SpecError as e:
            print('ОШИБКА: %s' % e)
            bad += 1
            continue
        path = out_path(service)
        count = text.count('"operationId"')
        if check:
            have = open(path, encoding='utf-8').read() if os.path.exists(path) else None
            if have != text:
                print('ОШИБКА: %s не равен результату gen_routes.py (маршрутов в OpenAPI: %d), запустите python3 tools/docs-checks/gen_routes.py'
                      % (os.path.relpath(path, REPO), count))
                bad += 1
            else:
                print('ок: %s, маршрутов %d' % (os.path.relpath(path, REPO), count))
        else:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, 'w', encoding='utf-8') as f:
                f.write(text)
            print('записан %s, маршрутов %d' % (os.path.relpath(path, REPO), count))
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
