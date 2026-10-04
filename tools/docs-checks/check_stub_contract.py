# -*- coding: utf-8 -*-
"""Контракт заглушки внешних систем: тела вебхуков берутся из OpenAPI проекта, а не пишутся по памяти.

    python3 tools/docs-checks/check_stub_contract.py          сверить tools/external-stubs/test/contract/webhook-schemas.json с OpenAPI
    python3 tools/docs-checks/check_stub_contract.py --write  пересобрать файл после намеренного изменения контракта

Заглушка играет внешние системы, но вебхуки она шлёт нашим сервисам, и их формат описан в наших OpenAPI. Если схема
уведомления изменилась, а заглушка нет, сервис пройдёт свои тесты и сломается на настоящем шлюзе. Поэтому:
  - из OpenAPI вынимаются схемы четырёх уведомлений (со всеми `$ref`, развёрнутыми внутрь), имена заголовков подписи и метки
    времени, пути приёма; результат лежит в репозитории рядом с тестами заглушки;
  - эта проверка падает, если файл расходится с OpenAPI (изменили контракт, пересоберите файл и перечитайте заглушку);
  - модульные тесты заглушки (`test/contract.test.mjs`) проверяют каждое отправляемое ею уведомление по этим схемам;
  - адреса вебхуков в compose.yaml (`STUBS_*_WEBHOOK_URL`) оканчиваются путями приёма из OpenAPI.
Нужен PyYAML.
"""
import json
import os
import re
import sys

import yaml

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import docslib as L

OPENAPI = os.path.join(L.DOCS, '06-api', 'openapi')
OUT = os.environ.get('DGM_STUB_CONTRACT_FILE') or os.path.join(L.REPO, 'tools', 'external-stubs', 'test', 'contract', 'webhook-schemas.json')
COMPOSE = os.environ.get('DGM_COMPOSE_FILE') or os.path.join(L.REPO, 'compose.yaml')   # переменные нужны самопроверке контроля

# имя в файле контракта: (файл OpenAPI, схема, путь приёма, операция)
WEBHOOKS = {
    'payment': ('payment-service.yaml', 'PaymentGatewayNotification', '/api/v1/webhooks/payment-gateway', 'receivePaymentGatewayNotification'),
    'deliveryEmail': ('delivery-service.yaml', 'EmailStatusNotification', '/api/v1/webhooks/email-provider-keys', 'receiveKeyEmailStatus'),
    'platformEmail': ('platform-service.yaml', 'MailProviderNotification', '/api/v1/webhooks/email-provider', 'receiveMailStatus'),
    'platformSms': ('platform-service.yaml', 'SmsProviderNotification', '/api/v1/webhooks/sms-provider', 'receiveSmsStatus'),
}
ENV_OF = {
    'payment': 'STUBS_PAYMENT_WEBHOOK_URL',
    'deliveryEmail': 'STUBS_DELIVERY_EMAIL_WEBHOOK_URL',
    'platformEmail': 'STUBS_PLATFORM_EMAIL_WEBHOOK_URL',
    'platformSms': 'STUBS_PLATFORM_SMS_WEBHOOK_URL',
}
_cache = {}


def load(name):
    if name not in _cache:
        with open(os.path.join(OPENAPI, name), encoding='utf-8') as f:
            _cache[name] = yaml.safe_load(f)
    return _cache[name]


def resolve(node, current):
    """Разворачивает $ref внутрь схемы. Ссылки вида «#/components/schemas/X» и «components.yaml#/components/schemas/X»."""
    if isinstance(node, list):
        return [resolve(x, current) for x in node]
    if not isinstance(node, dict):
        return node
    if '$ref' in node:
        file, _, pointer = node['$ref'].partition('#')
        doc = load(file) if file else load(current)
        target = doc
        for part in pointer.strip('/').split('/'):
            target = target[part]
        return resolve(target, file or current)
    return {k: resolve(v, current) for k, v in node.items()}


def strip(node):
    """Оставляет ключевые слова проверки: тип, обязательность, перечни, границы, шаблон. Описания и примеры в файл не идут."""
    keep = ('type', 'properties', 'required', 'additionalProperties', 'enum', 'minLength', 'maxLength', 'minimum', 'maximum', 'pattern', 'format')
    if isinstance(node, dict):
        out = {}
        for k, v in node.items():
            if k == 'properties':
                out[k] = {name: strip(s) for name, s in v.items()}
            elif k in keep:
                out[k] = v
        return out
    return node


def build():
    contract = {'signature': {}, 'webhooks': {}}
    components = load('components.yaml')['components']['securitySchemes']['webhookSignature']
    contract['signature'] = {'header': components['name'], 'timestampHeader': 'X-Webhook-Timestamp', 'algorithm': 'HMAC-SHA-256', 'toleranceSeconds': 300}
    for key, (file, schema, path, operation) in WEBHOOKS.items():
        doc = load(file)
        op = doc['paths'][path]['post']
        if op['operationId'] != operation:
            raise SystemExit('ОШИБКА: у %s %s операция %s, ожидалась %s' % (file, path, op['operationId'], operation))
        names = [p['name'] for p in op['parameters']]
        if 'X-Webhook-Timestamp' not in names:
            raise SystemExit('ОШИБКА: у %s %s нет заголовка X-Webhook-Timestamp' % (file, path))
        contract['webhooks'][key] = {
            'path': path,
            'operationId': operation,
            'schema': strip(resolve(doc['components']['schemas'][schema], file)),
        }
    return contract


def main():
    contract = build()
    text = json.dumps(contract, ensure_ascii=False, indent=2, sort_keys=True) + '\n'
    problems = []
    if '--write' in sys.argv:
        os.makedirs(os.path.dirname(OUT), exist_ok=True)
        with open(OUT, 'w', encoding='utf-8', newline='\n') as f:
            f.write(text)
        print('записан', L.rel(OUT))
    else:
        current = open(OUT, encoding='utf-8').read() if os.path.exists(OUT) else ''
        if current != text:
            problems.append('%s не совпадает с OpenAPI: выполните python3 tools/docs-checks/check_stub_contract.py --write и перечитайте заглушку' % L.rel(OUT))
        else:
            print('схемы вебхуков в %s совпадают с OpenAPI (%d)' % (L.rel(OUT), len(contract['webhooks'])))

    # Адреса вебхуков в Compose оканчиваются путями приёма из OpenAPI
    if os.path.exists(COMPOSE):
        with open(COMPOSE, encoding='utf-8') as f:
            services = (yaml.safe_load(f) or {}).get('services', {})
        env = (services.get('external-stubs') or {}).get('environment')
        if env is not None:
            env = env if isinstance(env, dict) else dict(e.split('=', 1) for e in env)
            for key, var in ENV_OF.items():
                url = str(env.get(var, ''))
                want = contract['webhooks'][key]['path']
                if not url:
                    problems.append('compose.yaml: у external-stubs нет переменной %s' % var)
                elif not re.match(r'^https://[a-z0-9-]+(:\d+)?%s$' % re.escape(want), url):
                    problems.append('compose.yaml: %s=%s, ожидался адрес https://<контейнер>:<порт>%s' % (var, url, want))
                else:
                    print('compose.yaml: %s оканчивается путём приёма %s' % (var, want))
    for p in problems:
        print('  -', p)
    print('проблем:', len(problems))
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
