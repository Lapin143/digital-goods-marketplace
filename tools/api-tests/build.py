# -*- coding: utf-8 -*-
"""Сборка коллекции Postman для Newman из контрактов OpenAPI, матрицы прав и сценариев.

    python3 tools/api-tests/build.py --check              собрать в памяти и проверить сценарии (входит в tools/docs-checks/run_all.sh)
    python3 tools/api-tests/build.py --out файл.json      записать коллекцию (её читает Newman, см. run.py)
    python3 tools/api-tests/build.py --no-matrix ...      без развёртки матрицы прав ST-02

Из чего состоит коллекция:
  1. папка «ST-02 матрица прав»: случаи operation × роль из st02_matrix.py для операций со статусом done в реестрах сервисов;
  2. по папке на каждый сценарий scenarios/*.json: цепочка шагов, в которой ответ одного шага может питать следующий.

Формат сценария (файл scenarios/<имя>.json):
  {
    "id": "E2E-01",
    "title": "Покупатель находит товар на витрине",
    "steps": [
      {"name": "Список витрины", "op": "listStorefrontProducts", "as": "guest", "query": {"limit": "5"}, "expect": 200,
       "save": {"productId": "items.0.id"}, "assert": [{"path": "items", "notEmpty": true}]},
      {"name": "Карточка", "op": "getStorefrontProduct", "as": "buyer", "path": {"productId": "{{productId}}"}, "expect": [200, 304]}
    ]
  }
  op      идентификатор операции из OpenAPI; операция должна иметь статус done в реестре своего сервиса
  as      роль: guest (без токена), buyer, seller, moderator, support-operator, admin; по умолчанию guest
  path    значения параметров пути (все параметры обязательны); query, headers, body как есть (в строках работают {{переменные}})
  expect  код ответа или список кодов; каждый код должен быть описан в OpenAPI операции
  save    переменная -> путь в JSON ответа через точку (items.0.id); переменная доступна следующим шагам и сценариям
  assert  проверки тела: {"path": "status", "equals": "published"}, {"path": "items", "notEmpty": true}, {"path": "id", "exists": true};
          проверка заголовка ответа: {"header": "Content-Type", "contains": "application/problem+json"} (регистр имени не важен);
          строки нет в теле ответа: {"bodyNotContains": "NEWMAN-CANARY-1"} (значение, которое шаг сам отправил)
  multipart  вместо body: форма с одним файлом, {"field": "file", "filename": "a.pdf", "contentType": "application/pdf", "content": "текст файла"};
          содержимое только текстовое (Newman отправляет строку), двоичные файлы проверяют Java-тесты и стендовые проверки
  set     переменная -> значение до запроса, например {"orderKey": "{{$guid}}"}: ключ, который шаг и его повтор отправляют одинаковым
          ("headers": {"Idempotency-Key": "{{orderKey}}"}); переменная доступна этому шагу и следующим
  sign    подпись вебхука (шаг 4 среза 2, ADR-015): для операций с x-webhook-secret обязательна (или "unsigned": true для проверки отказа без подписи).
          Перед запросом скрипт ставит заголовки X-Webhook-Timestamp (сейчас, RFC 3339 с миллисекундами) и X-Webhook-Signature (hex HMAC-SHA-256
          от «метка.тело»). Секрет берётся из переменной окружения Newman secret_<имя секрета> (её заполняет run.py из secrets/<имя>), в коллекцию он
          не попадает. Необязательно: {"timestampOffsetSeconds": -600} (устаревшее уведомление), {"badSignature": true} (неверная подпись).
          В подписанном теле нельзя использовать динамические переменные ({{$guid}}), иначе подписанное и отправленное тело разойдутся
Для операций с ключом идемпотентности заголовок Idempotency-Key добавляется сам (новый UUID на каждый запрос), если шаг не задал его явно.
"""
import argparse
import glob
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import oas  # noqa: E402
import st02_matrix  # noqa: E402

SCENARIOS = os.path.join(HERE, 'scenarios')
VARIABLE = re.compile(r'\{\{([^}]+)\}\}')
SENTINEL_ID = '00000000-0000-4000-8000-000000000000'
COLLECTION_SCHEMA = 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json'


def js(value):
    return json.dumps(value, ensure_ascii=False)


BOUNDARY = '----dgmnewman'


def multipart_body(part):
    """Тело формы с одним файлом; границу выбирает сборщик, части в тексте нет."""
    return ('--%s\r\nContent-Disposition: form-data; name="%s"; filename="%s"\r\nContent-Type: %s\r\n\r\n%s\r\n--%s--\r\n'
            % (BOUNDARY, part.get('field', 'file'), part.get('filename', 'file.bin'), part.get('contentType', 'application/octet-stream'),
               part.get('content', ''), BOUNDARY))


def make_request(op, role, path_values, query=None, headers=None, body=None, multipart=None):
    segments = op.path.split('/')[1:]
    segments = [path_values.get(s[1:-1], s) if s.startswith('{') else s for s in segments]
    header = {}
    if role != 'guest':
        header['Authorization'] = 'Bearer {{token_%s}}' % role
    if op.idempotent:
        header[op.idempotency_header] = '{{$guid}}'
    header.update(headers or {})
    request = {'method': op.method, 'header': [{'key': k, 'value': v} for k, v in header.items()]}
    query = query or {}
    raw = '{{baseUrl}}/' + '/'.join(segments)
    if query:
        raw += '?' + '&'.join('%s=%s' % (k, v) for k, v in query.items())
    request['url'] = {'raw': raw, 'host': ['{{baseUrl}}'], 'path': segments,
                      'query': [{'key': k, 'value': str(v)} for k, v in query.items()]}
    if body is not None:
        request['header'].append({'key': 'Content-Type', 'value': 'application/json'})
        request['body'] = {'mode': 'raw', 'raw': json.dumps(body, ensure_ascii=False), 'options': {'raw': {'language': 'json'}}}
    if multipart is not None:
        request['header'].append({'key': 'Content-Type', 'value': 'multipart/form-data; boundary=' + BOUNDARY})
        request['body'] = {'mode': 'raw', 'raw': multipart_body(multipart), 'options': {'raw': {'language': 'text'}}}
    return request


def item(name, request, script, prerequest=None):
    events = [{'listen': 'test', 'script': {'type': 'text/javascript', 'exec': script}}]
    if prerequest:
        events.insert(0, {'listen': 'prerequest', 'script': {'type': 'text/javascript', 'exec': prerequest}})
    return {'name': name, 'request': request, 'event': events}


SIGN_KEYS = {'timestampOffsetSeconds', 'badSignature'}


def sign_script(secret_name, sign):
    """Скрипт до запроса: заголовки подписи вебхука. Секрет читается из окружения Newman, а не из коллекции."""
    var = 'secret_' + secret_name
    script = ['const secret = pm.environment.get(%s);' % js(var),
              'if (!secret) { throw new Error(%s); }' % js('нет секрета %s в окружении Newman (run.py берёт его из secrets/%s)' % (var, secret_name)),
              'const stamp = new Date(Date.now() + %d * 1000).toISOString();' % int(sign.get('timestampOffsetSeconds', 0)),
              'const body = pm.variables.replaceIn((pm.request.body && pm.request.body.raw) || "");',
              'let signature = CryptoJS.HmacSHA256(stamp + "." + body, secret).toString(CryptoJS.enc.Hex);']
    if sign.get('badSignature'):
        script.append('signature = (signature[0] === "0" ? "1" : "0") + signature.slice(1);')
    script += ['pm.request.headers.upsert({key: "X-Webhook-Timestamp", value: stamp});',
               'pm.request.headers.upsert({key: "X-Webhook-Signature", value: signature});']
    return script


def matrix_item(case, op):
    ids = dict((p, SENTINEL_ID) for p in op.path_params)
    body = {} if op.json_body and op.method in ('POST', 'PUT', 'PATCH') else None
    role, expect, name = case['role'], case['expect'], '%s / %s: %s' % (case['operation'], case['role'], case['expect'])
    if expect == 'deny':
        wanted = 401 if role == 'guest' else 403
        check = 'pm.expect(code).to.equal(%d);' % wanted
        meaning = 'отказ %d' % wanted
    elif expect == 'conditional':
        check = 'pm.expect(code).to.not.equal(401); pm.expect(code).to.be.below(500);'
        meaning = 'допущена при условии, не 401 и не 5xx'
    else:
        check = 'pm.expect(code).to.not.be.oneOf([401, 403, 429]); pm.expect(code).to.be.below(500);'
        meaning = 'допущена, не 401, 403, 429 и не 5xx'
    script = ['const code = pm.response.code;',
              'pm.test(%s + " (код " + code + ")", function () { %s });' % (js('ST-02 ' + name + ': ' + meaning), check)]
    return item(name, make_request(op, role, ids, headers=None, body=body), script)


def step_item(scenario, index, step, ops, saved, problems, ledgers):
    where = '%s, шаг %d' % (scenario['id'], index + 1)
    op = ops.get(step.get('op'))
    if op is None:
        problems.append('%s: операции «%s» нет в OpenAPI' % (where, step.get('op')))
        return None
    if not oas.is_done(ops, op.id, ledgers):
        problems.append('%s: операция %s ещё не готова по реестру %s (статус не done)' % (where, op.id, op.service))
    if not op.scenario_reachable:
        problems.append('%s: операция %s недоступна через шлюз (внутренний вызов)' % (where, op.id))
    sign = step.get('sign')
    if op.webhook_secret:
        if sign is None and step.get('unsigned') is not True:
            problems.append('%s: вебхук %s требует подпись: добавьте sign (или unsigned: true для проверки отказа без подписи)' % (where, op.id))
        if sign is not None and step.get('unsigned'):
            problems.append('%s: sign и unsigned: true вместе не имеют смысла' % where)
    elif sign is not None or step.get('unsigned'):
        problems.append('%s: у операции %s нет подписи вебхука (x-webhook-secret), sign и unsigned здесь не нужны' % (where, op.id))
    if sign is not None:
        if not isinstance(sign, dict) or set(sign) - SIGN_KEYS:
            problems.append('%s: sign это объект с ключами %s' % (where, ', '.join(sorted(SIGN_KEYS))))
            sign = {}
        elif 'timestampOffsetSeconds' in sign and not isinstance(sign['timestampOffsetSeconds'], int):
            problems.append('%s: sign.timestampOffsetSeconds должно быть целым числом секунд' % where)
            sign = dict(sign, timestampOffsetSeconds=0)
        if sign.get('badSignature') is not None and not isinstance(sign['badSignature'], bool):
            problems.append('%s: sign.badSignature должно быть true или false' % where)
        if re.search(r'\{\{\$', json.dumps(step.get('body'), ensure_ascii=False)):
            problems.append('%s: в подписанном теле нет места динамическим переменным ({{$guid}}): подписанное и отправленное тело разойдутся' % where)
    pre_set = step.get('set') or {}
    if not isinstance(pre_set, dict) or any(not isinstance(k, str) or not isinstance(v, str) for k, v in pre_set.items()):
        problems.append('%s: set это объект «переменная: строка»' % where)
        pre_set = {}
    role = step.get('as', 'guest')
    if role not in st02_matrix.ROLES:
        problems.append('%s: неизвестная роль «%s» (есть: %s)' % (where, role, ', '.join(st02_matrix.ROLES)))
        return None
    path_values = dict(step.get('path') or {})
    for p in op.path_params:
        if p not in path_values:
            problems.append('%s: не задан параметр пути {%s} операции %s' % (where, p, op.id))
    for p in path_values:
        if p not in op.path_params:
            problems.append('%s: у операции %s нет параметра пути {%s}' % (where, op.id, p))
    expect = step.get('expect')
    codes = expect if isinstance(expect, list) else [expect]
    if not codes or any(not isinstance(c, int) for c in codes):
        problems.append('%s: поле expect должно быть кодом ответа или списком кодов' % where)
        return None
    for code in codes:
        if str(code) not in op.responses and 'default' not in op.responses:
            problems.append('%s: код %s не описан в OpenAPI операции %s (описаны: %s)' % (where, code, op.id, ', '.join(sorted(op.responses))))
    if step.get('multipart') is not None and (step.get('body') is not None or op.method != 'POST'):
        problems.append('%s: multipart допустим только у POST и вместо body' % where)
    prerequest = []
    for var, value in pre_set.items():
        prerequest.append('pm.collectionVariables.set(%s, pm.variables.replaceIn(%s));' % (js(var), js(value)))
        saved.add(var)
    text = json.dumps([step.get('path'), step.get('query'), step.get('headers'), step.get('body'), step.get('multipart')], ensure_ascii=False)
    for name in VARIABLE.findall(text):
        if name not in saved and not name.startswith('$') and name != 'baseUrl':
            problems.append('%s: переменная {{%s}} не сохранена предыдущими шагами (save)' % (where, name))
    script = ['const code = pm.response.code;',
              'pm.test(%s + " (код " + code + ")", function () { pm.expect(code).to.be.oneOf(%s); });'
              % (js('%s / %s: %s' % (scenario['id'], step.get('name', op.id), op.id)), js(codes))]
    if step.get('save') or any('path' in c for c in step.get('assert') or []):
        script.append('let json = null; try { json = pm.response.json(); } catch (e) { json = null; }')
        script.append('const at = (path) => path.split(".").reduce((v, k) => (v === null || v === undefined) ? undefined : v[k], json);')
    for var, path in (step.get('save') or {}).items():
        script.append('pm.collectionVariables.set(%s, at(%s));' % (js(var), js(path)))
        saved.add(var)
    for check in step.get('assert') or []:
        if 'header' in check:
            label = js('%s / %s: заголовок %s' % (scenario['id'], step.get('name', op.id), check['header']))
            if 'contains' in check:
                script.append('pm.test(%s, function () { pm.expect(pm.response.headers.get(%s) || "").to.include(%s); });' % (label, js(check['header']), js(check['contains'])))
            else:
                problems.append('%s: проверка заголовка без contains' % where)
            continue
        if 'bodyNotContains' in check:
            label = js('%s / %s: в ответе нет отправленного значения' % (scenario['id'], step.get('name', op.id)))
            script.append('pm.test(%s, function () { pm.expect(pm.response.text()).to.not.include(%s); });' % (label, js(check['bodyNotContains'])))
            continue
        label = js('%s / %s: тело %s' % (scenario['id'], step.get('name', op.id), check.get('path')))
        path = js(check.get('path', ''))
        if 'equals' in check:
            script.append('pm.test(%s, function () { pm.expect(at(%s)).to.eql(%s); });' % (label, path, js(check['equals'])))
        elif check.get('notEmpty'):
            script.append('pm.test(%s, function () { const v = at(%s); pm.expect(v).to.exist; pm.expect(v.length).to.be.above(0); });' % (label, path))
        elif check.get('exists'):
            script.append('pm.test(%s, function () { pm.expect(at(%s)).to.exist; });' % (label, path))
        else:
            problems.append('%s: проверка assert без equals, notEmpty или exists' % where)
    if sign is not None and op.webhook_secret:
        prerequest += sign_script(op.webhook_secret, sign)
    request = make_request(op, role, path_values, step.get('query'), step.get('headers'), step.get('body'), step.get('multipart'))
    return item(step.get('name', op.id), request, script, prerequest)


def load_scenarios(directory):
    result = []
    for file in sorted(glob.glob(os.path.join(directory, '*.json'))):
        with open(file, encoding='utf-8') as f:
            data = json.load(f)
        data['_file'] = os.path.basename(file)
        result.append(data)
    return result


def build(scenarios_dir=SCENARIOS, with_matrix=True, ops=None, matrix=None):
    """Возвращает (коллекция, сводка, проблемы)."""
    ops = ops or oas.load()
    problems, folders, ledgers = [], [], {}
    roles, requests = set(), 0
    cases = []
    if with_matrix:
        cases, matrix_problems = st02_matrix.cases(ops, matrix or st02_matrix.load_matrix())
        problems.extend('ST-02: ' + p for p in matrix_problems)
        if cases:
            folders.append({'name': 'ST-02 матрица прав', 'item': [matrix_item(c, ops[c['operation']]) for c in cases]})
            roles.update(c['role'] for c in cases)
            requests += len(cases)
    scenarios = load_scenarios(scenarios_dir)
    seen = set()
    saved = set()
    for scenario in scenarios:
        label = scenario['_file']
        if not scenario.get('id') or scenario['id'] in seen:
            problems.append('%s: нет идентификатора id или он повторяется' % label)
            continue
        seen.add(scenario['id'])
        if not scenario.get('steps'):
            problems.append('%s: нет шагов steps' % label)
            continue
        items = []
        for i, step in enumerate(scenario['steps']):
            built = step_item(scenario, i, step, ops, saved, problems, ledgers)
            if built:
                items.append(built)
                roles.add(step.get('as', 'guest'))
        folders.append({'name': '%s %s' % (scenario['id'], scenario.get('title', '')), 'item': items})
        requests += len(items)
    collection = {'info': {'name': 'Digital Goods Marketplace: API-тесты', 'schema': COLLECTION_SCHEMA}, 'item': folders}
    summary = {'scenarios': len(scenarios), 'matrix_cases': len(cases), 'requests': requests, 'roles': sorted(roles)}
    return collection, summary, problems


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--check', action='store_true', help='только проверить')
    parser.add_argument('--out', help='куда записать коллекцию')
    parser.add_argument('--no-matrix', action='store_true')
    parser.add_argument('--scenarios', default=SCENARIOS)
    args = parser.parse_args(argv)
    collection, summary, problems = build(args.scenarios, with_matrix=not args.no_matrix)
    for p in problems:
        print('  -', p)
    print('сценариев: %(scenarios)d, случаев матрицы ST-02: %(matrix_cases)d, запросов: %(requests)d, ролей: %(r)d' %
          dict(summary, r=len(summary['roles'])), '| проблем: %d' % len(problems))
    if args.out and not problems:
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        with open(args.out, 'w', encoding='utf-8') as f:
            json.dump(collection, f, ensure_ascii=False, indent=1)
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
