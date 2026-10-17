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


def item(name, request, script):
    return {'name': name, 'request': request,
            'event': [{'listen': 'test', 'script': {'type': 'text/javascript', 'exec': script}}]}


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
    if not op.reachable:
        problems.append('%s: операция %s недоступна через шлюз (внутренний вызов или вебхук с подписью)' % (where, op.id))
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
    request = make_request(op, role, path_values, step.get('query'), step.get('headers'), step.get('body'), step.get('multipart'))
    return item(step.get('name', op.id), request, script)


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
