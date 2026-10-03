# -*- coding: utf-8 -*-
"""Линтер контрактов REST (OpenAPI 3.1), шаг 11 Ф2.

Читает docs/06-api/openapi/*.yaml, conventions.md, roles-permissions.md, истории требований
и README контрактов. Проверки описаны в docs/06-api/openapi/README.md, раздел 8.

Запуск:
    python3 check_openapi.py                  проверить всё
    python3 check_openapi.py --write-catalog  перезаписать каталог операций в README контрактов

Требуется PyYAML и jsonschema. Код выхода 0 означает «проблем: 0».
"""
import glob
import json
import os
import re
import sys

import yaml
from jsonschema import Draft202012Validator, FormatChecker

REPO = os.environ.get('REPO') or os.path.abspath(
    os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
DOCS = os.path.join(REPO, 'docs')
OA = os.path.join(DOCS, '06-api/openapi')
CONV = os.path.join(DOCS, '05-architecture/conventions.md')
ROLES = os.path.join(DOCS, '05-architecture/roles-permissions.md')
RQ = os.path.join(DOCS, '02-requirements')
README = os.path.join(OA, 'README.md')

SERVICE_ORDER = ['catalog-service', 'inventory-service', 'order-service', 'payment-service',
                 'delivery-service', 'platform-service']
METHODS = ['get', 'put', 'post', 'patch', 'delete']
STATUS_CONDITIONS = {'U4', 'U5', 'U6', 'U9', 'U10', 'U14', 'U15', 'U16'}
ROLE_ALIAS = {'Гость': 'guest', 'Покупатель': 'buyer', 'Продавец': 'seller', 'Модератор': 'moderator',
              'Оператор': 'support-operator', 'Админ': 'admin', 'Система': 'system'}
# Владение путями: префикс пути -> сервис (README контрактов, раздел 4)
OWNERS = [
    ('/api/v1/seller-applications', 'catalog-service'),
    ('/api/v1/staff/seller-applications', 'catalog-service'),
    ('/api/v1/seller/products/{productId}/key-batches', 'inventory-service'),
    ('/api/v1/seller/products/{productId}/key-files', 'inventory-service'),
    ('/api/v1/seller/products/{productId}/stock', 'inventory-service'),
    ('/api/v1/seller/products', 'catalog-service'),
    ('/api/v1/staff/products', 'catalog-service'),
    ('/api/v1/products', 'catalog-service'),
    ('/internal/v1/products', 'catalog-service'),
    ('/internal/v1/keys', 'inventory-service'),
    ('/internal/v1/reservations', 'inventory-service'),
    ('/api/v1/orders', 'order-service'),
    ('/internal/v1/orders', 'order-service'),
    ('/internal/v1/payment-sessions', 'payment-service'),
    ('/api/v1/staff/manual-refunds', 'payment-service'),
    ('/api/v1/webhooks/payment-gateway', 'payment-service'),
    ('/api/v1/webhooks/email-provider-keys', 'delivery-service'),
    ('/api/v1/phone-confirmations', 'platform-service'),
    ('/api/v1/staff/users', 'platform-service'),
    ('/api/v1/support-tickets', 'platform-service'),
    ('/api/v1/staff/support-tickets', 'platform-service'),
    ('/api/v1/staff/parameters', 'platform-service'),
    ('/api/v1/staff/audit-records', 'platform-service'),
    ('/internal/v1/otp-codes', 'platform-service'),
    ('/internal/v1/otp-verifications', 'platform-service'),
    ('/api/v1/webhooks/email-provider', 'platform-service'),
    ('/api/v1/webhooks/sms-provider', 'platform-service'),
]
# Вызывающие внутренние операции (roles-permissions.md, раздел 8.1)
CALLERS = [
    ('/internal/v1/products', ['order-service']),
    ('/internal/v1/reservations', ['order-service']),
    ('/internal/v1/payment-sessions', ['order-service']),
    ('/internal/v1/keys', ['delivery-service']),
    ('/internal/v1/orders', ['platform-service']),
    ('/internal/v1/otp-codes', ['keycloak']),
    ('/internal/v1/otp-verifications', ['keycloak']),
]

problems = []


def err(msg):
    problems.append(msg)


# ------------------------------------------------------------------ загрузка
DOC = {}
for f in sorted(glob.glob(os.path.join(OA, '*.yaml'))):
    DOC[os.path.basename(f)] = yaml.safe_load(open(f, encoding='utf-8'))
COMP = 'components.yaml'
if COMP not in DOC:
    print('нет components.yaml')
    sys.exit(1)
SVC_FILES = [s + '.yaml' for s in SERVICE_ORDER]
for s in SVC_FILES:
    if s not in DOC:
        err('нет файла %s' % s)
extra_files = [f for f in DOC if f != COMP and f not in SVC_FILES]
for f in extra_files:
    err('лишний файл контракта %s' % f)


def split_ref(ref, cur):
    f, _, ptr = ref.partition('#')
    return (f or cur), ptr


def lookup(f, ptr):
    node = DOC[f]
    for seg in [s for s in ptr.split('/') if s]:
        seg = seg.replace('~1', '/').replace('~0', '~')
        node = node[seg]
    return node


def collect_refs(node, out):
    if isinstance(node, dict):
        for k, v in node.items():
            if k == '$ref' and isinstance(v, str):
                out.append(v)
            else:
                collect_refs(v, out)
    elif isinstance(node, list):
        for v in node:
            collect_refs(v, out)


def deref(node, cur, stack=()):
    """Полностью раскрывает $ref. Рекурсивная ссылка заменяется пустой схемой."""
    if isinstance(node, dict):
        if isinstance(node.get('$ref'), str):
            f, ptr = split_ref(node['$ref'], cur)
            key = (f, ptr)
            if key in stack:
                return {}
            try:
                target = lookup(f, ptr)
            except (KeyError, TypeError, IndexError):
                return {}
            res = deref(target, f, stack + (key,))
            extra = {k: v for k, v in node.items() if k != '$ref'}
            if extra and isinstance(res, dict):
                res = dict(res)
                res.update(deref(extra, cur, stack))
            return res
        return {k: deref(v, cur, stack) for k, v in node.items()}
    if isinstance(node, list):
        return [deref(v, cur, stack) for v in node]
    return node


# ------------------------------------------------------------------ источники
def table_rows(text, start_re, end_re=None):
    m = re.search(start_re, text, re.M)
    if not m:
        return []
    rest = text[m.end():]
    lines = []
    started = False
    for ln in rest.split('\n'):
        if ln.startswith('|'):
            started = True
            cells = [c.strip() for c in re.split(r'(?<!\\)\|', ln.strip())[1:-1]]
            lines.append(cells)
        elif started:
            break
    return [r for r in lines if not re.match(r'^-+$', r[0])]


CONV_TEXT = open(CONV, encoding='utf-8').read()
REGISTRY = {}
for r in table_rows(CONV_TEXT, r'^### 9\.1\. Реестр типов проблем')[1:]:
    REGISTRY[r[0].strip('`')] = (int(r[1]), r[2])

STATUS_DICT = {}
for r in table_rows(CONV_TEXT, r'^### 7\.2\. Словарь статусов')[1:]:
    STATUS_DICT[r[0]] = set(re.findall(r'→ `([a-z_]+)`', r[2]))
ALL_STATUSES = set().union(*STATUS_DICT.values())
REASONS = {}
for r in table_rows(CONV_TEXT, r'^### 7\.3\. Типы и причины')[1:]:
    m = re.search(r'\(`(\w+)`\)', r[0])
    if m:
        REASONS[m.group(1)] = set(re.findall(r'`([a-z_]+)`', r[1]))

ROLES_TEXT = open(ROLES, encoding='utf-8').read()
MATRIX = {}
for r in table_rows(ROLES_TEXT, r'^### 4\.2\. Матрица')[1:]:
    if not re.match(r'OP-\d+$', r[0]):
        continue
    cols = r[5:13]
    names = ['Гость', 'Покупатель', 'Покупатель SMS', 'Продавец', 'Модератор', 'Оператор', 'Админ', 'Система']
    cell = dict(zip(names, cols))
    roles, conds = set(), set()
    for n, v in cell.items():
        if n == 'Покупатель SMS':
            continue
        if v != 'Нет':
            roles.add(ROLE_ALIAS[n])
    for v in cols:
        conds |= set(re.findall(r'\bU\d+\b', v))
    MATRIX[r[0]] = dict(
        stories=re.findall(r'US-\d+\.\d+', r[2]), area=r[3].strip('`'), service=r[4].strip('`'),
        roles=roles, conds=conds, sms=(cell['Покупатель SMS'] != 'Нет'))

# истории R1
STORIES = {}
for f in sorted(glob.glob(os.path.join(RQ, 'US-[0-9][0-9]-*.md'))):
    t = open(f, encoding='utf-8').read()
    parts = re.split(r'^## (US-\d+\.\d+)\. ', t, flags=re.M)
    for i in range(1, len(parts), 2):
        body = parts[i + 1]
        fields = dict(re.findall(r'^\| (Релиз|Требования) \| (.+?) \|$', body, re.M))
        STORIES[parts[i]] = dict(rel=fields.get('Релиз', ''),
                                 ft=bool(re.search(r'(?<!N)\bFT-\d', fields.get('Требования', ''))))

README_TEXT = open(README, encoding='utf-8').read()
NONREST = []
m = re.search(r'<!-- nonrest:begin -->(.*?)<!-- nonrest:end -->', README_TEXT, re.S)
if m:
    for ln in m.group(1).split('\n'):
        mm = re.match(r'\| (US-\d+\.\d+) \| (OP-\d+) \| (.+) \|$', ln)
        if mm:
            NONREST.append((mm.group(1), mm.group(2)))
else:
    err('README: нет блока nonrest')

# ------------------------------------------------------------------ операции
OPS = []
for svc in SERVICE_ORDER:
    fn = svc + '.yaml'
    if fn not in DOC:
        continue
    d = DOC[fn]
    for path, item in (d.get('paths') or {}).items():
        for meth in METHODS:
            if meth in item:
                OPS.append(dict(file=fn, svc=svc, method=meth, path=path, op=item[meth],
                                common=item.get('parameters', [])))


def opname(o):
    return '%s %s %s' % (o['svc'], o['method'].upper(), o['path'])


def owner_of(path):
    best = None
    for prefix, svc in OWNERS:
        if path == prefix or path.startswith(prefix + '/'):
            if best is None or len(prefix) > len(best[0]):
                best = (prefix, svc)
    return best[1] if best else None


def op_params(o):
    res = []
    for p in list(o['common']) + list(o['op'].get('parameters', [])):
        res.append(p)
    return res


def param_dict(p, cur):
    return deref(p, cur) if isinstance(p, dict) else {}


def resp_for(o, code):
    r = o['op']['responses'].get(code)
    return r


def resp_headers(r, cur):
    return {k: v for k, v in (deref(r, cur).get('headers') or {}).items()}


def refs_of(node):
    out = []
    collect_refs(node, out)
    return out


# ------------------------------------------------------------------ 1. структура
def check_structure():
    seen_ids = {}
    seen_pairs = set()
    for fn, d in DOC.items():
        if not str(d.get('openapi', '')).startswith('3.1'):
            err('%s: нужен OpenAPI 3.1' % fn)
        for k in ('title', 'version', 'description'):
            if not (d.get('info') or {}).get(k):
                err('%s: нет info.%s' % (fn, k))
        if fn != COMP and not d.get('servers'):
            err('%s: нет servers' % fn)
    for o in OPS:
        op, p, n = o['op'], o['path'], opname(o)
        if (o['method'], p) in seen_pairs:
            err('%s: повтор метода и пути' % n)
        seen_pairs.add((o['method'], p))
        if not re.match(r'^/(api|internal)/v1/[a-z]', p) or p.endswith('/'):
            err('%s: путь должен быть /api/v1/... или /internal/v1/... без завершающего «/»' % n)
        for seg in [s for s in p.split('/') if s]:
            if seg.startswith('{'):
                if seg != '{key}' and not re.match(r'^\{[a-z][a-zA-Z0-9]*Id\}$', seg):
                    err('%s: параметр пути %s должен быть camelCase и оканчиваться на Id' % (n, seg))
            elif not re.match(r'^[a-z][a-z0-9-]*$', seg):
                err('%s: сегмент пути «%s» не в kebab-case' % (n, seg))
        oid = op.get('operationId')
        if not oid or not re.match(r'^[a-z][a-zA-Z0-9]*$', oid):
            err('%s: operationId отсутствует или не camelCase' % n)
        elif oid in seen_ids:
            err('%s: operationId %s повторяет %s' % (n, oid, seen_ids[oid]))
        else:
            seen_ids[oid] = n
        for k in ('summary', 'description'):
            if not str(op.get(k, '')).strip():
                err('%s: нет поля %s' % (n, k))
        declared = {t['name'] for t in DOC[o['file']].get('tags', [])}
        if not op.get('tags'):
            err('%s: нет тегов' % n)
        for t in op.get('tags', []):
            if t not in declared:
                err('%s: тег «%s» не объявлен в файле' % (n, t))
        # параметры пути
        params = [param_dict(x, o['file']) for x in op_params(o)]
        in_path = {x.get('name') for x in params if x.get('in') == 'path'}
        want = set(re.findall(r'\{(\w+)\}', p))
        if in_path != want:
            err('%s: параметры пути %s, в адресе %s' % (n, sorted(in_path), sorted(want)))
        for x in params:
            if x.get('in') == 'path' and x.get('required') is not True:
                err('%s: параметр пути %s не обязателен' % (n, x.get('name')))
            if x.get('in') in ('query', 'path') and not re.match(r'^[a-z][a-zA-Z0-9]*$', x.get('name', '')):
                err('%s: параметр %s не camelCase' % (n, x.get('name')))
            if not x.get('description'):
                err('%s: у параметра %s нет описания' % (n, x.get('name')))
            if 'schema' not in x:
                err('%s: у параметра %s нет схемы' % (n, x.get('name')))
        # успешные ответы
        codes = list(op['responses'])
        ok = [c for c in codes if c.startswith('2')]
        if len(ok) != 1:
            err('%s: успешных ответов %d, нужен один' % (n, len(ok)))
        for c in codes:
            if c not in ('200', '201', '202', '204', '304', '400', '401', '403', '404', '409', '412', '413',
                         '415', '422', '428', '429', '500', '503', '504'):
                err('%s: код %s не предусмотрен conventions.md, раздел 8.4' % (n, c))
        if '201' in ok and o['method'] != 'post':
            err('%s: 201 только у POST' % n)
        if not op.get('security') and not op.get('x-public') and o['svc'] and not p.startswith('/api/v1/products'):
            # публичные пути перечислены в README, остальные обязаны иметь security
            err('%s: нет security' % n)


# ------------------------------------------------------------------ 2. ссылки
def check_refs():
    used = set()
    for fn, d in DOC.items():
        refs = []
        collect_refs(d, refs)
        for r in refs:
            f, ptr = split_ref(r, fn)
            if f not in DOC:
                err('%s: ссылка на несуществующий файл %s' % (fn, r))
                continue
            try:
                lookup(f, ptr)
            except (KeyError, TypeError):
                err('%s: ссылка %s не разрешается' % (fn, r))
                continue
            used.add((f, ptr))
        # схемы безопасности используются по имени
        for sec in refs_security(d):
            used.add((COMP, '/components/securitySchemes/' + sec))
    for fn, d in DOC.items():
        for kind, items in (d.get('components') or {}).items():
            for name in items:
                if (fn, '/components/%s/%s' % (kind, name)) not in used:
                    err('%s: компонент %s/%s не используется' % (fn, kind, name))


def refs_security(d):
    names = set()

    def walk(node):
        if isinstance(node, dict):
            for k, v in node.items():
                if k == 'security' and isinstance(v, list):
                    for item in v:
                        names.update(item.keys())
                else:
                    walk(v)
        elif isinstance(node, list):
            for v in node:
                walk(v)
    walk(d)
    return names


# ------------------------------------------------------------------ 3. ошибки
def component_problem_codes():
    """Для каждого общего ответа об ошибке: статус и коды из примеров."""
    res = {}
    for name, r in (DOC[COMP]['components'].get('responses') or {}).items():
        m = re.match(r'^E(\d{3})', name)
        if not m:
            err('components.yaml: ответ %s: имя должно начинаться с E<код>' % name)
            continue
        status = int(m.group(1))
        content = (r.get('content') or {}).get('application/problem+json')
        if not content:
            err('components.yaml: ответ %s без application/problem+json' % name)
            continue
        exs = list((content.get('examples') or {}).values())
        if content.get('example') is not None:
            exs.append({'value': content['example']})
        codes = set()
        for e in exs:
            v = e.get('value', {})
            code = v.get('code')
            codes.add(code)
            if code not in REGISTRY:
                err('components.yaml: %s: тип «%s» нет в реестре 9.1' % (name, code))
                continue
            st, title = REGISTRY[code]
            if v.get('status') != status or st != status:
                err('components.yaml: %s: статус примера %s, реестра %s, ответа %s' % (name, v.get('status'), st, status))
            if v.get('title') != title:
                err('components.yaml: %s: название «%s» не совпадает с реестром «%s»' % (name, v.get('title'), title))
            if v.get('type') != 'https://api.marketplace.example/problems/' + code:
                err('components.yaml: %s: type примера не соответствует коду %s' % (name, code))
            if not re.match(r'^[0-9a-f-]{36}$', str(v.get('correlationId', ''))):
                err('components.yaml: %s: correlationId примера не UUID' % name)
        if not codes:
            err('components.yaml: ответ %s без примеров' % name)
        hdr = r.get('headers') or {}
        if 'X-Correlation-Id' not in hdr:
            err('components.yaml: ответ %s без X-Correlation-Id' % name)
        if status == 401 and not codes <= {'signature-invalid', 'webhook-expired'} and 'WWW-Authenticate' not in hdr:
            err('components.yaml: ответ %s без WWW-Authenticate' % name)
        if status in (429, 503) and 'Retry-After' not in hdr:
            err('components.yaml: ответ %s без Retry-After' % name)
        if codes == {'request-in-progress'} and 'Retry-After' not in hdr:
            err('components.yaml: ответ %s без Retry-After' % name)
        res[name] = (status, codes)
    return res


def is_list(o):
    refs = refs_of(o['op']) + sum([refs_of(x) for x in o['common']], [])
    return o['method'] == 'get' and any(r.endswith('/parameters/Cursor') for r in refs)


def check_errors():
    comp_codes = component_problem_codes()
    used_codes = set()
    for o in OPS:
        n = opname(o)
        op = o['op']
        codes = set(op['responses'])
        sec = op.get('security') or []
        sec_names = {k for s in sec for k in s}
        public = not sec
        conds = set(op.get('x-conditions', []))
        params = [param_dict(x, o['file']) for x in op_params(o)]
        has_body = 'requestBody' in op
        has_query = any(x.get('in') == 'query' for x in params)
        internal = o['path'].startswith('/internal/')
        webhook = 'webhookSignature' in sec_names
        need = {'500'}
        if webhook:
            # вебхук: подпись вместо токена, тело разбирается, лимиты шлюза (README, раздел 2)
            need |= {'400', '401', '413', '415', '429'}
        else:
            if sec:
                need.add('401' if 'mutualTls' not in sec_names else '403')
                need.add('403')
            if not internal:
                need.add('429')
        if re.search(r'\{', o['path']) or ('U1' in conds and not is_list(o)):
            need.add('404')
        if o['method'] != 'get' and conds & STATUS_CONDITIONS:
            need.add('409')
        if o['method'] == 'put':
            need |= {'412', '428'}
        if has_body and not webhook:
            need |= {'415', '422'}
            if 'multipart/form-data' in (op['requestBody'].get('content') or {}):
                need.add('413')
        if has_query:
            need.add('422')
        if o['method'] == 'post' and not webhook and not internal:
            need.add('400')
        for c in sorted(need - codes):
            err('%s: нет обязательного ответа %s' % (n, c))
        for c, r in op['responses'].items():
            if c.startswith('2') or c == '304':
                continue
            ref = r.get('$ref', '') if isinstance(r, dict) else ''
            name = ref.rsplit('/', 1)[-1]
            if not ref.startswith('components.yaml#/components/responses/E'):
                err('%s: ответ %s должен быть общим ответом об ошибке из components.yaml' % (n, c))
                continue
            if name not in comp_codes:
                continue
            st, cs = comp_codes[name]
            if str(st) != c:
                err('%s: ответ %s ссылается на %s (статус %s)' % (n, c, name, st))
            used_codes |= cs
    for code in sorted(set(REGISTRY) - used_codes):
        err('реестр 9.1: тип %s не возвращается ни одной операцией' % code)
    return len(REGISTRY)


# ------------------------------------------------------------------ 4. безопасность
def expected_callers(path):
    best = None
    for prefix, cs in CALLERS:
        if path == prefix or path.startswith(prefix + '/'):
            if best is None or len(prefix) > len(best[0]):
                best = (prefix, cs)
    return best[1] if best else None


def check_security():
    for o in OPS:
        n, op = opname(o), o['op']
        mid = op.get('x-matrix-id')
        also = op.get('x-matrix-also', [])
        rows = [mid] + also
        if not mid or mid not in MATRIX:
            err('%s: x-matrix-id «%s» нет в матрице' % (n, mid))
            continue
        bad = [r for r in rows if r not in MATRIX]
        if bad:
            err('%s: x-matrix-also %s нет в матрице' % (n, bad))
            continue
        main = MATRIX[mid]
        internal = o['path'].startswith('/internal/')
        webhook = '/webhooks/' in o['path']
        area = main['area']
        own_row = main['service'] == o['svc']
        # Операция исполняет строку матрицы целиком (строгая проверка) или одну из сторон строки:
        # внутренний вызов или вебхук, которым пользователь или процесс другой строки получает результат.
        strict = (not internal and not webhook) or \
            (internal and own_row and area.startswith('Нет, внутренний')) or \
            (webhook and own_row and area.startswith('Нет, подпись вебхука'))
        sec = op.get('security') or []
        if internal and sec != [{'mutualTls': []}]:
            err('%s: внутренняя операция должна иметь security: mutualTls' % n)
        if webhook and sec != [{'webhookSignature': []}]:
            err('%s: вебхук должен иметь security: webhookSignature' % n)
        if not internal and not webhook:
            if main['service'] != o['svc']:
                err('%s: строка %s принадлежит сервису %s' % (n, mid, main['service']))
            if area.startswith('Нет, публичный'):
                if sec:
                    err('%s: публичная операция с security' % n)
            elif area.startswith('Нет'):
                err('%s: строка %s (%s) не бывает операцией REST' % (n, mid, area))
            elif sec != [{'bearerJwt': [area]}]:
                err('%s: security %s, в матрице область %s' % (n, sec, area))
            for r in rows[1:]:
                if MATRIX[r]['area'] != area:
                    err('%s: области строк %s и %s различаются' % (n, mid, r))
        if strict:
            roles = set().union(*[MATRIX[r]['roles'] for r in rows])
            conds = set().union(*[MATRIX[r]['conds'] for r in rows])
            sms = 'allowed' if any(MATRIX[r]['sms'] for r in rows) else 'denied'
        else:
            roles, conds, sms = {'system'}, None, None
        if set(op.get('x-allowed-roles', [])) != roles:
            err('%s: роли %s, в матрице %s' % (n, sorted(op.get('x-allowed-roles', [])), sorted(roles)))
        if conds is not None and set(op.get('x-conditions', [])) != conds:
            err('%s: условия %s, в матрице %s' % (n, sorted(op.get('x-conditions', [])), sorted(conds)))
        if sms is not None and not internal and not webhook and op.get('x-sms-session') != sms:
            err('%s: x-sms-session %s, в матрице %s' % (n, op.get('x-sms-session'), sms))
        stories = set().union(*[set(MATRIX[r]['stories']) for r in rows])
        xs = set(op.get('x-stories', []))
        if not xs:
            err('%s: нет x-stories' % n)
        if not xs <= stories:
            err('%s: истории %s не входят в строки %s матрицы' % (n, sorted(xs - stories), rows))
        text = op.get('description', '')
        for r in rows:
            if r not in text:
                err('%s: в описании не названа строка матрицы %s' % (n, r))
        for s in xs:
            if s not in text:
                err('%s: в описании не названа история %s' % (n, s))
        if internal:
            if op.get('x-internal') is not True:
                err('%s: нет x-internal: true' % n)
            want = expected_callers(o['path'])
            if want is None:
                err('%s: неизвестный вызывающий внутренней операции' % n)
            elif sorted(op.get('x-allowed-callers', [])) != sorted(want):
                err('%s: вызывающие %s, в разделе 8.1 %s' % (n, op.get('x-allowed-callers'), want))
        else:
            if op.get('x-internal') or op.get('x-allowed-callers'):
                err('%s: публичная операция с x-internal или x-allowed-callers' % n)
        if o['method'] == 'post' and op.get('x-internal-nolocation') is not None and not internal:
            err('%s: x-internal-nolocation только у внутренних операций' % n)
        own = owner_of(o['path'])
        if own != o['svc']:
            err('%s: путь принадлежит сервису %s' % (n, own))
    # аудит: только чтение
    for o in OPS:
        if o['path'].startswith('/api/v1/staff/audit-records') and o['method'] != 'get':
            err('%s: журнал аудита изменять нельзя (INV-42)' % opname(o))
    # значения ключей не отдаются публично
    for o in OPS:
        if o['path'].startswith('/internal/'):
            continue
        for ref in refs_of(o['op']):
            if re.search(r'KeyValue', ref):
                err('%s: публичная операция ссылается на %s (значения ключей наружу не отдаются)' % (opname(o), ref))


# ------------------------------------------------------------------ 5. идемпотентность и версии
def check_headers():
    for o in OPS:
        n, op = opname(o), o['op']
        refs = refs_of(op) + sum([refs_of(x) for x in o['common']], [])
        has = lambda s: any(r.endswith(s) for r in refs)
        webhook = 'webhooks' in o['path']
        if o['method'] == 'post' and not webhook:
            if not any(r.endswith('/parameters/IdempotencyKey') for r in refs):
                err('%s: POST без Idempotency-Key' % n)
        if webhook and any(r.endswith('/parameters/IdempotencyKey') for r in refs):
            err('%s: вебхук с Idempotency-Key (раздел 8.3 conventions.md)' % n)
        if o['method'] == 'put' and not any(r.endswith('/parameters/IfMatch') for r in refs):
            err('%s: PUT без If-Match' % n)
        for c, r in op['responses'].items():
            rr = deref(r, o['file'])
            hdr = rr.get('headers') or {}
            if c.startswith('2') or c == '304':
                if 'X-Correlation-Id' not in hdr:
                    err('%s: ответ %s без X-Correlation-Id' % (n, c))
                public = not op.get('security')
                if not public and not webhook:
                    if 'Cache-Control' not in hdr:
                        err('%s: ответ %s без Cache-Control: no-store' % (n, c))
                if public and c in ('200', '304'):
                    if 'ETag' not in hdr:
                        err('%s: публичный ответ %s без ETag' % (n, c))
                if c == '201' and not op.get('x-internal-nolocation') and 'Location' not in hdr:
                    err('%s: 201 без Location' % n)
                if o['method'] == 'post' and not webhook and c.startswith('2') and 'Idempotency-Replayed' not in hdr:
                    err('%s: ответ %s без Idempotency-Replayed' % (n, c))
                if o['method'] == 'put' and c == '200' and 'ETag' not in hdr:
                    err('%s: PUT 200 без ETag' % n)
        # страницы
        ok = [c for c in op['responses'] if c.startswith('2')]
        if ok and o['method'] == 'get':
            body = deref(op['responses'][ok[0]], o['file']).get('content', {}).get('application/json', {}).get('schema', {})
            props = (body or {}).get('properties', {})
            if 'items' in props and 'page' in props:
                if not (any(r.endswith('/parameters/Limit') for r in refs) and any(r.endswith('/parameters/Cursor') for r in refs)):
                    err('%s: список без limit и cursor' % n)
        # условный GET витрины
        if o['path'].startswith('/api/v1/products') and o['method'] == 'get':
            if '304' not in op['responses']:
                err('%s: витрина без 304' % n)


# ------------------------------------------------------------------ 6. примеры
class Counter:
    n = 0       # примеров проверено схемой (каждое использование)
    total = 0   # то же, что в шапке README: примеры операций с учётом ссылок на ошибки и общие ответы


def fmt_checker():
    fc = FormatChecker()

    @fc.checks('date-time')
    def _dt(v):
        return not isinstance(v, str) or bool(re.match(r'^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d{1,3})?Z$', v))

    @fc.checks('uri')
    def _uri(v):
        return not isinstance(v, str) or bool(re.match(r'^https?://[^\s]+$', v))

    @fc.checks('email')
    def _email(v):
        return not isinstance(v, str) or bool(re.match(r'^[^@\s]+@[^@\s]+\.[^@\s]+$', v))
    return fc


FC = fmt_checker()


def validate_example(schema, example, where):
    try:
        v = Draft202012Validator(schema, format_checker=FC)
        errs = sorted(v.iter_errors(example), key=lambda e: list(e.path))
    except Exception as e:  # схема сама неверна
        err('%s: схема не разбирается: %s' % (where, e))
        return
    Counter.n += 1
    for e in errs[:3]:
        err('%s: пример не проходит схему: %s (%s)' % (where, e.message[:160], '/'.join(str(p) for p in e.path)))


def media_examples(mt):
    out = []
    if 'example' in mt:
        out.append(mt['example'])
    for e in (mt.get('examples') or {}).values():
        if 'value' in e:
            out.append(e['value'])
    return out


def check_examples():
    # операции
    Counter.total = 0
    for o in OPS:
        n, op, fn = opname(o), o['op'], o['file']
        rb = op.get('requestBody')
        if rb:
            for ct, mt in (rb.get('content') or {}).items():
                schema = deref(mt.get('schema', {}), fn)
                exs = media_examples(mt)
                if ct == 'application/json' and not exs:
                    err('%s: у тела запроса нет примера' % n)
                Counter.total += len(exs)
                for e in exs:
                    validate_example(schema, e, '%s, запрос' % n)
        for c, r in op['responses'].items():
            if isinstance(r, dict) and '$ref' in r:
                Counter.total += sum(len(media_examples(mt)) for mt in (deref(r, fn).get('content') or {}).values())
                continue
            rr = deref(r, fn)
            for ct, mt in (rr.get('content') or {}).items():
                schema = deref(mt.get('schema', {}), fn)
                exs = media_examples(mt)
                Counter.total += len(exs)
                if not exs and c.startswith('2') and ct == 'application/json':
                    err('%s: у ответа %s нет примера' % (n, c))
                for e in exs:
                    validate_example(schema, e, '%s, ответ %s' % (n, c))
        for p in op_params(o):
            pp = param_dict(p, fn)
            if 'example' in pp:
                Counter.total += 1
                validate_example(pp.get('schema', {}), pp['example'], '%s, параметр %s' % (n, pp.get('name')))
    # общие компоненты
    comp = DOC[COMP]['components']
    for name, r in (comp.get('responses') or {}).items():
        for ct, mt in ((r.get('content') or {}).items()):
            schema = deref(mt.get('schema', {}), COMP)
            for e in media_examples(mt):
                Counter.total += 1
                validate_example(schema, e, 'components.yaml, ответ %s' % name)
    for fn in [COMP] + SVC_FILES:
        if fn not in DOC:
            continue
        for name, sc in ((DOC[fn].get('components') or {}).get('schemas') or {}).items():
            exs = list(sc.get('examples', [])) + ([sc['example']] if 'example' in sc else [])
            schema = deref(sc, fn)
            for e in exs:
                validate_example(schema, e, '%s, схема %s' % (fn, name))


# ------------------------------------------------------------------ 7. соглашения
def walk_schemas():
    for fn in [COMP] + SVC_FILES:
        if fn not in DOC:
            continue
        for name, sc in ((DOC[fn].get('components') or {}).get('schemas') or {}).items():
            yield fn, name, sc
    for o in OPS:
        op = o['op']
        rb = (op.get('requestBody') or {}).get('content') or {}
        for ct, mt in rb.items():
            yield o['file'], opname(o) + ' (запрос)', mt.get('schema', {})
        for c, r in op['responses'].items():
            if isinstance(r, dict) and '$ref' not in r:
                for ct, mt in (r.get('content') or {}).items():
                    yield o['file'], opname(o) + ' (ответ %s)' % c, mt.get('schema', {})


ID_EXEMPT = {('AuditRecord', 'objectId')}
# Внешние идентификаторы шлюза и провайдеров: непрозрачные строки до 128 символов, не UUID платформы
EXTERNAL_IDS = {'eventId', 'paymentId', 'refundId', 'externalRefundId', 'messageId'}
# Схемы, где status это этап процесса или подтверждение приёма, а не статус сущности из словаря 7.2
STATUS_EXEMPT = {'EmailChangeState', 'ResendAccepted', 'EmailStatusNotification', 'MailProviderNotification',
                 'SmsProviderNotification'}
OPEN_ID = {'nextCursor', 'cursor'}


def check_conventions():
    def walk(node, where, schema_name, fn):
        if isinstance(node, list):
            for v in node:
                walk(v, where, schema_name, fn)
            return
        if not isinstance(node, dict):
            return
        props = node.get('properties')
        if isinstance(props, dict):
            for k, v in props.items():
                if not re.match(r'^[a-z][a-zA-Z0-9]*$', k):
                    err('%s, %s: свойство «%s» не camelCase' % (fn, where, k))
                if k.endswith('Id') and (schema_name, k) not in ID_EXEMPT and isinstance(v, dict):
                    nullable = [x for x in (v.get('oneOf') or v.get('anyOf') or []) if isinstance(x, dict)]
                    ok = v.get('$ref', '').endswith(('/schemas/Id', '/schemas/CorrelationId')) or v.get('format') == 'uuid' or \
                        any(x.get('$ref', '').endswith('/schemas/Id') for x in nullable) or \
                        (k in EXTERNAL_IDS and v.get('type') == 'string' and 0 < v.get('maxLength', 0) <= 128) or \
                        (v.get('type') == 'array' and (v.get('items', {}).get('$ref', '').endswith('/schemas/Id') or v.get('items', {}).get('format') == 'uuid'))
                    if not ok:
                        err('%s, %s: %s должно ссылаться на схему Id (UUID)' % (fn, where, k))
                if k.endswith('At') and isinstance(v, dict):
                    ok = v.get('$ref', '').endswith('/schemas/Timestamp') or v.get('format') == 'date-time' or \
                        any(isinstance(x, dict) and (x.get('$ref', '').endswith('/schemas/Timestamp') or x.get('format') == 'date-time')
                            for x in (v.get('oneOf') or v.get('anyOf') or []))
                    if not ok:
                        err('%s, %s: %s должно быть моментом времени (Timestamp)' % (fn, where, k))
                if k == 'total' and isinstance(v, dict) and not v.get('$ref', '').endswith('/schemas/Money') and \
                        not any(isinstance(x, dict) and x.get('$ref', '').endswith('/schemas/Money') for x in (v.get('oneOf') or [])):
                    err('%s, %s: имя total зарезервировано под Money (F11-9)' % (fn, where))
                if isinstance(v, dict):
                    en = v.get('enum')
                    if en:
                        for x in en:
                            if k != 'currency' and isinstance(x, str) and x != x.lower():
                                err('%s, %s: значение перечисления «%s» не в нижнем регистре' % (fn, where, x))
                        if (k == 'status' or k.endswith('Status')) and schema_name not in STATUS_EXEMPT:
                            bad = set(x for x in en if isinstance(x, str)) - ALL_STATUSES
                            if bad:
                                err('%s, %s: статусы %s нет в словаре 7.2 conventions.md' % (fn, where, sorted(bad)))
                        if k in REASONS:
                            bad = set(x for x in en if isinstance(x, str)) - REASONS[k]
                            if bad:
                                err('%s, %s: значения %s поля %s нет в разделе 7.3 conventions.md' % (fn, where, sorted(bad), k))
        for k, v in node.items():
            if k in ('example', 'examples', 'default', 'const'):
                continue
            walk(v, where, schema_name, fn)

    for fn, name, sc in walk_schemas():
        walk(sc, name, name, fn)


# ------------------------------------------------------------------ 8. покрытие
def check_coverage():
    op_stories = {}
    row_ops = {}
    for o in OPS:
        op = o['op']
        for s in op.get('x-stories', []):
            op_stories.setdefault(s, []).append(op['operationId'])
        for r in [op.get('x-matrix-id')] + op.get('x-matrix-also', []):
            row_ops.setdefault(r, []).append(op['operationId'])
    nonrest_stories = {s for s, r in NONREST}
    for s, d in STORIES.items():
        if 'R1' not in d['rel']:
            continue
        if s in op_stories or s in nonrest_stories:
            continue
        if not d['ft']:
            continue  # истории только про качество (NFT) операций не имеют
        err('история R1 %s не покрыта ни операцией, ни таблицей «без REST»' % s)
    for s in op_stories:
        if s not in STORIES:
            err('в x-stories названа несуществующая история %s' % s)
    # пары «история и строка» без REST не должны иметь операции
    for s, r in NONREST:
        if r not in MATRIX:
            err('таблица без REST: строки %s нет в матрице' % r)
            continue
        if s not in MATRIX[r]['stories']:
            err('таблица без REST: история %s не входит в строку %s матрицы' % (s, r))
        for o in OPS:
            op = o['op']
            if r in ([op.get('x-matrix-id')] + op.get('x-matrix-also', [])) and s in op.get('x-stories', []):
                err('таблица без REST: пара %s и %s уже исполняется операцией %s' % (s, r, op['operationId']))
    # строки матрицы
    nonrest_rows = {r for s, r in NONREST}
    for r, d in MATRIX.items():
        if r in row_ops:
            continue
        if r in nonrest_rows:
            continue
        if r == 'OP-87':
            continue
        if d['area'].startswith('Нет, Keycloak') or d['area'].startswith('Нет, вне API'):
            continue
        err('строка матрицы %s не исполняется ни операцией, ни таблицей «без REST»' % r)
    # у каждой строки истории покрыты операциями или таблицей
    for r, ops in row_ops.items():
        covered = set()
        for o in OPS:
            op = o['op']
            if r in ([op.get('x-matrix-id')] + op.get('x-matrix-also', [])):
                covered |= set(op.get('x-stories', []))
        for s in MATRIX[r]['stories']:
            if s not in covered and (s, r) not in NONREST:
                err('строка %s: история %s не исполняется ни одной операцией этой строки' % (r, s))


# ------------------------------------------------------------------ 9. README
def esc(s):
    return s


def access(op):
    sec = op.get('security') or []
    if not sec:
        return 'публично'
    k = list(sec[0].keys())[0]
    if k == 'bearerJwt':
        return ', '.join('`%s`' % s for s in sec[0][k])
    if k == 'mutualTls':
        return 'mTLS: ' + ', '.join('`%s`' % c for c in op.get('x-allowed-callers', []))
    return 'подпись вебхука'


def build_catalog():
    L = ['### Операции по сервисам', '']
    for svc in SERVICE_ORDER:
        ops = [o for o in OPS if o['svc'] == svc]
        L.append('#### `%s`, операций %d' % (svc, len(ops)))
        L.append('')
        L.append('| Метод | Путь | `operationId` | Матрица | Доступ | Роли | Условия | Истории |')
        L.append('| --- | --- | --- | --- | --- | --- | --- | --- |')
        for o in ops:
            op = o['op']
            mat = ' + '.join([op['x-matrix-id']] + op.get('x-matrix-also', []))
            roles = ', '.join('`%s`' % r for r in op.get('x-allowed-roles', []))
            conds = ', '.join(op.get('x-conditions', [])) or '-'
            L.append('| %s | `%s` | `%s` | %s | %s | %s | %s | %s |' % (
                o['method'].upper(), o['path'], op['operationId'], mat, access(op), roles, conds,
                ', '.join(op.get('x-stories', []))))
        L.append('')
    L.append('### Покрытие историй R1 операциями REST')
    L.append('')
    L.append('| История | Операции |')
    L.append('| --- | --- |')
    cov = {}
    for o in OPS:
        for s in o['op'].get('x-stories', []):
            cov.setdefault(s, []).append(o['op']['operationId'])
    for s in sorted(cov, key=lambda x: [int(p) for p in x[3:].split('.')]):
        if 'R1' in STORIES.get(s, {}).get('rel', ''):
            L.append('| %s | %s |' % (s, ', '.join('`%s`' % x for x in cov[s])))
    return '\n'.join(L) + '\n'


def check_readme(write):
    global README_TEXT
    m = re.search(r'(<!-- catalog:begin -->\n)(.*?)(<!-- catalog:end -->)', README_TEXT, re.S)
    if not m:
        err('README: нет блока catalog')
        return
    new = build_catalog()
    if m.group(2) != new:
        if write:
            README_TEXT = README_TEXT[:m.start(2)] + new + README_TEXT[m.end(2):]
            open(README, 'w', encoding='utf-8').write(README_TEXT)
            print('каталог операций в README перезаписан')
        else:
            err('README: каталог операций устарел (python3 check_openapi.py --write-catalog)')
    # число операций в шапке и в таблице файлов
    total = len(OPS)
    mm = re.search(r'\| Состояние \| .*?(\d+) операций.*?(\d+) пример', README_TEXT)
    if not mm:
        err('README: в шапке нет числа операций и примеров')
    else:
        if int(mm.group(1)) != total:
            err('README: в шапке %s операций, в контрактах %d' % (mm.group(1), total))
        if int(mm.group(2)) != Counter.total:
            err('README: в шапке %s примеров, фактически %d' % (mm.group(2), Counter.total))
    for svc in SERVICE_ORDER:
        mm = re.search(r'\| \[%s\.yaml\]\(%s\.yaml\) \| `%s` \| .*? \| (\d+) \|' % (svc, svc, svc), README_TEXT)
        cnt = len([o for o in OPS if o['svc'] == svc])
        if not mm:
            err('README: в таблице файлов нет %s' % svc)
        elif int(mm.group(1)) != cnt:
            err('README: у %s в таблице %s операций, в контракте %d' % (svc, mm.group(1), cnt))
    mm = re.search(r'таблице (\d+) пар', README_TEXT)
    if mm and int(mm.group(1)) != len(NONREST):
        err('README: «в таблице %s пар», фактически %d' % (mm.group(1), len(NONREST)))


def main():
    write = '--write-catalog' in sys.argv
    check_structure()
    check_refs()
    check_examples()
    nreg = check_errors()
    check_security()
    check_headers()
    check_conventions()
    check_coverage()
    check_readme(write)
    print('файлов контрактов: %d, операций: %d, примеров: %d (проверок по схемам: %d), типов проблем в реестре: %d, пар «без REST»: %d' % (
        len(SVC_FILES), len(OPS), Counter.total, Counter.n, nreg, len(NONREST)))
    for p in problems:
        print('  -', p)
    print('проблем:', len(problems))
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
