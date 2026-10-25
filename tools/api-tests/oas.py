# -*- coding: utf-8 -*-
"""Операции из контрактов OpenAPI для сборщика API-тестов: по идентификатору операции находится сервис, метод, путь, параметры и коды ответов.

Читает docs/06-api/openapi/*-service.yaml (только PyYAML) и реестры операций сервисов (services/<сервис>/src/test/resources/dgm/operations.txt):
в сценарии и в матрицу прав попадают только операции со статусом done, то есть те, у которых есть обработчик (находка 5 плана Ф4).
"""
import glob
import os
import re

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.environ.get('REPO') or os.path.dirname(os.path.dirname(HERE))
OPENAPI = os.path.join(REPO, 'docs', '06-api', 'openapi')
METHODS = ('get', 'put', 'post', 'patch', 'delete')
PATH_PARAM = re.compile(r'\{([^}]+)\}')


class Operation:
    def __init__(self, service, method, path, spec, idempotency_header):
        self.service = service
        self.method = method.upper()
        self.path = path
        self.id = spec['operationId']
        self.responses = set(str(code) for code in spec.get('responses', {}))
        self.path_params = PATH_PARAM.findall(path)
        refs = [p.get('$ref', '') for p in spec.get('parameters', []) if isinstance(p, dict)]
        self.idempotent = any(r.endswith('/IdempotencyKey') for r in refs)
        self.idempotency_header = idempotency_header
        body = spec.get('requestBody') or {}
        self.body_types = list((body.get('content') or {}).keys())
        self.matrix_ids = [spec['x-matrix-id']] + list(spec.get('x-matrix-also') or [])
        self.roles = list(spec.get('x-allowed-roles') or [])
        self.public = path.startswith('/api/v1/') and 'guest' in self.roles
        # Вызовы между сервисами и вебхуки с подписью через шлюз без токена пользователя не проверяются
        self.reachable = path.startswith('/api/v1/') and 'x-webhook-secret' not in spec and 'x-internal' not in spec
        # Вебхук с подписью: секрет источника (имя файла в secrets/). В матрицу прав он не входит (роль «система»), а в сценарии входит с подписью
        self.webhook_secret = spec.get('x-webhook-secret')
        self.scenario_reachable = path.startswith('/api/v1/') and 'x-internal' not in spec

    @property
    def json_body(self):
        return 'application/json' in self.body_types


def load():
    """Все операции контрактов: идентификатор -> Operation."""
    components = yaml.safe_load(open(os.path.join(OPENAPI, 'components.yaml'), encoding='utf-8'))
    header = components['components']['parameters']['IdempotencyKey']['name']
    ops = {}
    for file in sorted(glob.glob(os.path.join(OPENAPI, '*-service.yaml'))):
        service = os.path.basename(file)[:-len('.yaml')]
        doc = yaml.safe_load(open(file, encoding='utf-8'))
        for path, item in doc['paths'].items():
            for method in METHODS:
                if method in item:
                    op = Operation(service, method, path, item[method], header)
                    ops[op.id] = op
    return ops


def ledger(service):
    """Статусы операций сервиса из его реестра: идентификатор -> done, slice1, slice2, slice3 или later. Пустой словарь, если реестра нет."""
    path = os.path.join(REPO, 'services', service, 'src', 'test', 'resources', 'dgm', 'operations.txt')
    result = {}
    if not os.path.exists(path):
        return result
    with open(path, encoding='utf-8') as f:
        for line in f:
            parts = line.split('#', 1)[0].split()
            if len(parts) == 2:
                result[parts[0]] = parts[1]
    return result


def is_done(ops, op_id, ledgers=None):
    op = ops[op_id]
    table = (ledgers or {}).get(op.service)
    if table is None:
        table = ledger(op.service)
        if ledgers is not None:
            ledgers[op.service] = table
    return table.get(op_id) == 'done'
