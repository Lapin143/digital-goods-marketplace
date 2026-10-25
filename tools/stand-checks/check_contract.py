# -*- coding: utf-8 -*-
"""Контрактная проверка сервисов (TC-070, NFT-6.2): настоящие ответы, записанные интеграционными тестами, сверяются со схемами OpenAPI.

    python3 tools/stand-checks/check_contract.py                 образцы в services/*/build/contract-samples
    python3 tools/stand-checks/check_contract.py каталог...      образцы в указанных каталогах (имя сервиса берётся из пути вида .../services/<сервис>/build/...)

Интеграционные тесты (CatalogServiceIT, OrderServiceIT, InventoryServiceIT, PlatformServiceIT) запускают приложение на стенде, ходят в него по HTTPS и через StandHttp
складывают каждый JSON-ответ в build/contract-samples: метод, путь без запроса, код и тело. Здесь по методу и пути находится
операция OpenAPI сервиса (шаблон «/api/v1/products/{productId}» совпадает с настоящим идентификатором), по коду берётся описание
ответа, по нему проверяется тело (JSON Schema 2020-12, форматы uuid и date-time включены).
Проверяется:
  - тело подходит под схему ответа операции; код описан в OpenAPI операции; у ошибок тело подходит под Problem и поле status
    равно коду ответа;
  - путь, которого нет в OpenAPI (например, неизвестный маршрут), допустим только как ошибка с телом Problem;
  - для каждого сервиса есть образцы обязательных операций: без них контракт «проверен» пустой проверкой. Требование включается само,
    когда реестр операций сервиса (src/test/resources/dgm/operations.txt) переводит операцию в done: пока обработчика нет, образцов
    тоже не ждут, а после появления обработчика проверка без образцов краснеет (находка 5 плана Ф4).
Нужны PyYAML и jsonschema.
"""
import glob
import json
import os
import re
import sys
from datetime import datetime

import yaml
from jsonschema import Draft202012Validator, FormatChecker

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.join(REPO, 'tools', 'docs-checks'))
import check_stub_contract as S  # noqa: E402  (load/resolve по файлам OpenAPI)

# Операции и коды, образцы которых обязательны: ходячий скелет (шаг 12 Ф3), товары продавца (шаг 12 Ф4, SellerProductIT), модерация товаров и карточка витрины (шаг 13 Ф4, ProductModerationIT), пул ключей (шаг 4 Ф4, образцы появятся в шаге 14), учётные записи
# сотрудников (шаг 5 Ф4) и подтверждение телефона с внутренними операциями входа по SMS (шаг 6 Ф4), смена адреса доставки заказа (шаг 11 среза 3, OrderAddressIT). Ответ 200 вебхука статуса SMS тела не имеет,
# поэтому у receiveSmsStatus и receivePaymentGatewayNotification обязательны только отказы
REQUIRED = {
    'catalog-service': [('listStorefrontProducts', 200), ('listStorefrontProducts', 422), ('getProductCard', 200), ('getProductCard', 404),
                        ('createSellerProduct', 201), ('createSellerProduct', 422), ('listSellerProducts', 200), ('listSellerProducts', 422),
                        ('getSellerProduct', 200), ('getSellerProduct', 404), ('replaceSellerProduct', 200), ('replaceSellerProduct', 412),
                        ('replaceSellerProduct', 428), ('submitSellerProduct', 200), ('submitSellerProduct', 409), ('archiveSellerProduct', 200),
                        ('restoreSellerProduct', 200),
                        ('getStorefrontProduct', 200), ('getStorefrontProduct', 404), ('listProductModerationQueue', 200),
                        ('listProductModerationQueue', 422), ('getProductForModeration', 200), ('getProductForModeration', 404),
                        ('approveProduct', 200), ('approveProduct', 409), ('rejectProduct', 200), ('rejectProduct', 422),
                        ('blockProduct', 200), ('blockProduct', 409)],
    'order-service': [('listOrders', 200), ('listOrders', 422), ('createOrder', 201), ('createOrder', 403), ('createOrder', 409),
                      ('createOrder', 422), ('createOrder', 503), ('getOrder', 200), ('getOrder', 404), ('getOrderPaymentSession', 200),
                      ('getOrderPaymentSession', 404), ('getOrderPaymentSession', 409),
                      ('updateDeliveryAddress', 200), ('updateDeliveryAddress', 404), ('updateDeliveryAddress', 409),
                      ('updateDeliveryAddress', 422)],
    'payment-service': [('openPaymentSession', 201), ('openPaymentSession', 409), ('openPaymentSession', 422),
                        ('openPaymentSession', 503),
                        ('receivePaymentGatewayNotification', 400), ('receivePaymentGatewayNotification', 401),
                        ('receivePaymentGatewayNotification', 415), ('receivePaymentGatewayNotification', 503),
                        ('listManualRefunds', 200), ('listManualRefunds', 422), ('completeManualRefund', 200), ('completeManualRefund', 404),
                        ('completeManualRefund', 409), ('completeManualRefund', 422)],
    'inventory-service': [('uploadKeyBatch', 200), ('uploadKeyBatch', 422), ('uploadKeyFile', 200), ('uploadKeyFile', 415),
                          ('getProductStock', 200), ('getProductStock', 404),
                          ('reserveKeys', 201), ('reserveKeys', 409), ('reserveKeys', 422),
                          ('confirmReservation', 200), ('confirmReservation', 404), ('confirmReservation', 409),
                          ('getKeyValues', 200), ('getKeyValues', 404), ('getKeyValues', 409)],
    'platform-service': [('listStaffUsers', 200), ('listStaffUsers', 422), ('changeUserRole', 200), ('changeUserRole', 409),
                         ('changeUserRole', 422), ('deactivateUser', 200), ('deactivateUser', 409), ('anonymizeUser', 200),
                         ('anonymizeUser', 409),
                         ('createPhoneConfirmation', 201), ('createPhoneConfirmation', 400), ('createPhoneConfirmation', 403),
                         ('createPhoneConfirmation', 409), ('createPhoneConfirmation', 422), ('createPhoneConfirmation', 429),
                         ('createPhoneConfirmation', 503),
                         ('verifyPhoneConfirmation', 200), ('verifyPhoneConfirmation', 404), ('verifyPhoneConfirmation', 422),
                         ('verifyPhoneConfirmation', 429),
                         ('issueOtpCode', 202), ('issueOtpCode', 400), ('issueOtpCode', 403), ('issueOtpCode', 422),
                         ('verifyOtpCode', 200), ('verifyOtpCode', 422),
                         ('receiveSmsStatus', 400), ('receiveSmsStatus', 401)],
}
PROBLEM = ('components.yaml', 'Problem')
RFC3339 = re.compile(r'^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$')

checker = FormatChecker()


@checker.checks('date-time')
def _date_time(value):
    if not isinstance(value, str):
        return True
    if not RFC3339.match(value):
        return False
    try:
        datetime.fromisoformat(value.replace('Z', '+00:00'))
    except ValueError:
        return False
    return True


def template_regex(template):
    parts = re.split(r'(\{[^}]+\})', template)
    return re.compile('^' + ''.join('[^/]+' if p.startswith('{') else re.escape(p) for p in parts) + '$')


def find_operation(doc, method, path):
    """Лучшее совпадение: больше букв без параметров."""
    best = None
    for template, item in doc['paths'].items():
        if method.lower() not in item or not template_regex(template).match(path):
            continue
        literal = len(re.sub(r'\{[^}]+\}', '', template))
        if best is None or literal > best[0]:
            best = (literal, template, item[method.lower()])
    return best


def response_schema(op, status, file):
    responses = op.get('responses', {})
    node = responses.get(str(status), responses.get('default'))
    if node is None:
        return None, 'код %s не описан в OpenAPI операции %s' % (status, op['operationId'])
    node = S.resolve(node, file)
    content = node.get('content') or {}
    for media in ('application/json', 'application/problem+json'):
        if media in content and 'schema' in content[media]:
            return content[media]['schema'], None
    return None, 'у ответа %s операции %s нет JSON-схемы' % (status, op['operationId'])


def problem_schema():
    file, name = PROBLEM
    return S.resolve(S.load(file)['components']['schemas'][name], file)


def validate(schema, body):
    errors = sorted(Draft202012Validator(schema, format_checker=checker).iter_errors(body), key=lambda e: list(e.absolute_path))
    return ['%s: %s' % ('/'.join(str(p) for p in e.absolute_path) or '(тело)', e.message[:200]) for e in errors[:5]]


def undocumented(schema, body, path=''):
    """Поля тела, которых нет в схеме: лишнее поле в ответе значит, что контракт и код разошлись (например, утекло внутреннее поле)."""
    found = []
    if not isinstance(schema, dict):
        return found
    if isinstance(body, list):
        return [x for item in body for x in undocumented(schema.get('items'), item, path + '[]')]
    if not isinstance(body, dict):
        return found
    for key in ('oneOf', 'anyOf'):
        for branch in schema.get(key, []):
            if not any(Draft202012Validator(branch, format_checker=checker).iter_errors(body)):
                return undocumented(branch, body, path)
    props = dict(schema.get('properties', {}))
    for part in schema.get('allOf', []):
        props.update(part.get('properties', {}))
    if props:
        extra = schema.get('additionalProperties', True)
        for name in body:
            if name not in props and not isinstance(extra, dict):
                found.append('%s: поля нет в схеме' % ((path + '/' + name).lstrip('/')))
    for name, value in body.items():
        if name in props:
            found.extend(undocumented(props[name], value, path + '/' + name))
    return found


def ready_operations(service):
    """Операции со статусом done в реестре сервиса. None, если реестра нет: тогда обязательными считаются все."""
    path = os.path.join(REPO, 'services', service, 'src', 'test', 'resources', 'dgm', 'operations.txt')
    if not os.path.exists(path):
        return None
    ready = set()
    with open(path, encoding='utf-8') as f:
        for line in f:
            parts = line.split('#', 1)[0].split()
            if len(parts) == 2 and parts[1] == 'done':
                ready.add(parts[0])
    return ready


def service_of(directory):
    m = re.search(r'services[/\\]([^/\\]+)[/\\]build', os.path.abspath(directory))
    return m.group(1) if m else None


def check_directory(directory, service, problems, seen):
    file = service + '.yaml'
    if not os.path.exists(os.path.join(S.OPENAPI, file)):
        problems.append('%s: нет описания OpenAPI %s' % (directory, file))
        return 0
    doc = S.load(file)
    problem = problem_schema()
    count = 0
    for sample_file in sorted(glob.glob(os.path.join(directory, '*.json'))):
        with open(sample_file, encoding='utf-8') as f:
            sample = json.load(f)
        method, path, status, body = sample['method'], sample['path'], sample['status'], sample['body']
        where = '%s %s -> %s' % (method, path, status)
        count += 1
        found = find_operation(doc, method, path)
        if found is None:
            if status < 400:
                problems.append('%s %s: путь не описан в OpenAPI %s, успешный ответ без контракта' % (service, where, file))
                continue
            schema, op_id = problem, None
        else:
            _, template, op = found
            op_id = op['operationId']
            schema, error = response_schema(op, status, file)
            if error:
                problems.append('%s %s: %s' % (service, where, error))
                continue
        label = ' (%s)' % op_id if op_id else ' (Problem)'
        for message in validate(schema, body) + undocumented(schema, body):
            problems.append('%s %s%s: %s' % (service, where, label, message))
        if status >= 400:
            if not isinstance(body, dict) or body.get('status') != status:
                problems.append('%s %s: поле status тела Problem не равно коду ответа' % (service, where))
            else:
                for message in validate(problem, body) + undocumented(problem, body):
                    problems.append('%s %s (Problem): %s' % (service, where, message))
        seen.add((service, op_id, status))
    return count


def main():
    explicit = bool(sys.argv[1:])
    directories = sys.argv[1:] or sorted(glob.glob(os.path.join(REPO, 'services', '*', 'build', 'contract-samples')))
    problems, seen, total = [], set(), 0
    services = set()
    for directory in directories:
        service = service_of(directory)
        if service is None:
            problems.append('%s: не удалось определить сервис по пути (ожидалось .../services/<сервис>/build/...)' % directory)
            continue
        services.add(service)
        n = check_directory(directory, service, problems, seen)
        print('%s: образцов проверено %d (%s)' % (service, n, os.path.relpath(directory, REPO) if directory.startswith(REPO) else directory))
        total += n
    postponed = 0
    for service, required in REQUIRED.items():
        ready = ready_operations(service)
        active = [(op_id, status) for op_id, status in required if ready is None or op_id in ready]
        postponed += len(required) - len(active)
        if not active:
            continue
        if explicit and service not in services:
            continue
        if service not in services:
            problems.append('%s: нет каталога build/contract-samples, интеграционные тесты не записали ответы' % service)
            continue
        for op_id, status in active:
            if (service, op_id, status) not in seen:
                problems.append('%s: нет образца ответа %s операции %s (интеграционный тест не вызвал её или не записал ответ)' % (service, status, op_id))
    if postponed:
        print('обязательных образцов отложено: %d (операции ещё не готовы по реестру operations.txt)' % postponed)
    problems = list(dict.fromkeys(problems))
    for p in problems:
        print('  -', p)
    print('образцов: %d, проблем: %d' % (total, len(problems)))
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
