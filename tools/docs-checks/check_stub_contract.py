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
  - адреса вебхуков в compose.yaml (`STUBS_*_WEBHOOK_URL`) оканчиваются путями приёма из OpenAPI;
  - подпись заглушки совпадает с подписью каркаса: общий пример `WebhookSignatureTest` (секрет, метка, тело и подпись, посчитанная независимо)
    пересчитывается здесь и должен лежать в `test/signing.test.mjs` заглушки с теми же значениями; имена заголовков и окно метки времени
    сверяются с `WebhookSignature.java` (заглушка «система продавца» и получатели платформы подписывают одинаково, Ф5).
Нужен PyYAML.
"""
import hashlib
import hmac
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
KIT_SIGNATURE = os.path.join(L.REPO, 'libs', 'service-kit', 'src', 'main', 'java', 'dgm', 'kit', 'crypto', 'WebhookSignature.java')
KIT_SIGNATURE_TEST = os.path.join(L.REPO, 'libs', 'service-kit', 'src', 'test', 'java', 'dgm', 'kit', 'crypto', 'WebhookSignatureTest.java')
STUB_SIGNING = os.path.join(L.REPO, 'tools', 'external-stubs', 'src', 'signing.mjs')
STUB_SIGNING_TEST = os.path.join(L.REPO, 'tools', 'external-stubs', 'test', 'signing.test.mjs')
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


def java_string(source, name):
    """Значение константы `name` (строковый литерал Java в одну строку) из текста класса; None, если не найдена."""
    m = re.search(r'\b%s\s*=\s*(?:SecretValue\.of\()?"((?:[^"\\]|\\.)*)"' % re.escape(name), source)
    if not m:
        return None
    return re.sub(r'\\(.)', r'\1', m.group(1))


def check_signature(problems):
    """Подпись заглушки и подпись каркаса `WebhookSignature` совпадают на общем примере и по именам заголовков."""
    missing = [L.rel(p) for p in (KIT_SIGNATURE, KIT_SIGNATURE_TEST, STUB_SIGNING, STUB_SIGNING_TEST) if not os.path.exists(p)]
    if missing:
        problems.append('нет файлов для сверки подписи: %s' % ', '.join(missing))
        return
    kit = open(KIT_SIGNATURE, encoding='utf-8').read()
    test = open(KIT_SIGNATURE_TEST, encoding='utf-8').read()
    stub = open(STUB_SIGNING, encoding='utf-8').read()
    stub_test = open(STUB_SIGNING_TEST, encoding='utf-8').read()

    secret = java_string(test, 'SECRET')
    timestamp = java_string(test, 'TIMESTAMP')
    body = java_string(test, 'BODY')
    known = java_string(test, 'KNOWN')
    if None in (secret, timestamp, body, known):
        problems.append('%s: не найден общий пример (SECRET, TIMESTAMP, BODY, KNOWN)' % L.rel(KIT_SIGNATURE_TEST))
        return
    body = body.replace('\\"', '"')
    computed = hmac.new(secret.encode('utf-8'), ('%s.%s' % (timestamp, body)).encode('utf-8'), hashlib.sha256).hexdigest()
    if computed != known:
        problems.append('%s: подпись общего примера не пересчитывается (в тесте %s, получилось %s)' % (L.rel(KIT_SIGNATURE_TEST), known, computed))
    else:
        print('общий пример подписи каркаса (%s) пересчитан независимо: %s' % (L.rel(KIT_SIGNATURE_TEST), known[:16] + '…'))

    for what, value in (('подпись', known), ('метка времени', timestamp)):
        if value not in stub_test:
            problems.append('%s: нет %s общего примера каркаса (%s); добавьте проверку из WebhookSignatureTest' % (L.rel(STUB_SIGNING_TEST), what, value))
    if "'%s'" % body.replace("'", "\\'") not in stub_test:
        problems.append('%s: нет тела общего примера каркаса (%s)' % (L.rel(STUB_SIGNING_TEST), body))
    if "'%s'" % secret not in stub_test:
        problems.append('%s: нет секрета общего примера каркаса (%s)' % (L.rel(STUB_SIGNING_TEST), secret))

    for const, expected in (('SIGNATURE_HEADER', 'X-Webhook-Signature'), ('TIMESTAMP_HEADER', 'X-Webhook-Timestamp')):
        kit_value = java_string(kit, const)
        if kit_value != expected:
            problems.append('%s: %s = %r, ожидалось %r' % (L.rel(KIT_SIGNATURE), const, kit_value, expected))
        if "'%s'" % expected.lower() not in stub:
            problems.append('%s: нет заголовка %s (в нижнем регистре, как в Node.js)' % (L.rel(STUB_SIGNING), expected))
    tolerance = re.search(r'TOLERANCE\s*=\s*Duration\.ofMinutes\((\d+)\)', kit)
    stub_tolerance = re.search(r'TOLERANCE_MS\s*=\s*(\d+)\s*\*\s*60\s*\*\s*1000', stub)
    if not tolerance or not stub_tolerance or tolerance.group(1) != stub_tolerance.group(1):
        problems.append('окно метки времени заглушки (%s) и каркаса (%s) не совпадают' % (stub_tolerance and stub_tolerance.group(1), tolerance and tolerance.group(1)))
    else:
        print('имена заголовков подписи и окно метки времени (%s мин) совпадают с каркасом' % tolerance.group(1))


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
    check_signature(problems)
    for p in problems:
        print('  -', p)
    print('проблем:', len(problems))
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
