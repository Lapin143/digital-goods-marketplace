# -*- coding: utf-8 -*-
"""Развёртка матрицы ролей и прав (ST-02) в проверки «операция × роль → ожидаемый ответ».

    python3 tools/api-tests/st02_matrix.py            таблица случаев для готовых операций
    python3 tools/api-tests/st02_matrix.py --all      то же для всех операций, в том числе ещё без обработчиков

Матрица записана в docs/05-architecture/roles-permissions.md, раздел 4.2 (колонки Гость, Покупатель, Покупатель SMS, Продавец, Модератор,
Оператор, Админ, Система). Для каждой операции из контрактов, доступной через шлюз (/api/v1), и для каждой роли получается один случай:

  allow        в ячейке «Да»: роль допущена, шлюз и сервис не отвечают 401 и 403 (ответ 200, 404, 422 и подобные говорят, что права пройдены)
  conditional  в ячейке «Усл: U…»: роль допущена при условии (профиль, статус, телефон), поэтому 403 возможен, а 401 и 5xx нет
  deny         в ячейке «Нет»: ответ 403, а у гостя (запрос без токена) 401

Если у операции несколько строк матрицы (x-matrix-also), берётся самое широкое разрешение. Случаи строятся для операций, у которых в реестре
сервиса (services/<сервис>/src/test/resources/dgm/operations.txt) статус done: матрица растёт вместе с обработчиками, а не краснеет заранее.
Колонка «Покупатель SMS» проверяется вместе с «Покупатель»: тестовый покупатель buyer-1 подтвердил телефон, поэтому отдельного случая у неё нет.
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import oas  # noqa: E402

MATRIX = os.path.join(oas.REPO, 'docs', '05-architecture', 'roles-permissions.md')
COLUMNS = ['guest', 'buyer', 'buyer-sms', 'seller', 'moderator', 'support-operator', 'admin', 'system']
# Роль -> тестовый пользователь Keycloak (infra/keycloak/provision_test_users.py); у гостя токена нет
ROLE_USERS = {'guest': None, 'buyer': 'buyer-1', 'seller': 'seller-1', 'moderator': 'moderator-1', 'support-operator': 'support-1', 'admin': 'admin-1'}
ROLES = list(ROLE_USERS)


def table_rows(text, start):
    m = re.search(start, text, re.M)
    if not m:
        return []
    rows = []
    for line in text[m.end():].split('\n'):
        if line.startswith('|'):
            rows.append([c.strip() for c in re.split(r'(?<!\\)\|', line.strip())[1:-1]])
        elif rows:
            break
    return [r for r in rows if not re.match(r'^-+$', r[0])]


def load_matrix(path=MATRIX):
    """OP-NN -> {роль: 'allow' | 'conditional' | 'deny'}."""
    text = open(path, encoding='utf-8').read()
    matrix = {}
    for row in table_rows(text, r'^### 4\.2\. Матрица')[1:]:
        if not re.match(r'OP-\d+$', row[0]):
            continue
        cells = dict(zip(COLUMNS, row[5:13]))
        matrix[row[0]] = {role: classify(cells[role]) for role in ROLES}
        if classify(cells['buyer-sms']) == 'allow' and matrix[row[0]]['buyer'] == 'deny':
            matrix[row[0]]['buyer'] = 'allow'
    return matrix


def classify(cell):
    if cell.startswith('Да'):
        return 'allow'
    if cell.startswith('Усл'):
        return 'conditional'
    return 'deny'


RANK = {'allow': 2, 'conditional': 1, 'deny': 0}


def cases(ops, matrix, done_only=True):
    """Список случаев: operation, service, method, path, role, user, expect, matrix_ids. Порядок: операции по сервисам, роли по матрице."""
    result, ledgers, problems = [], {}, []
    for op in ops.values():
        if not op.reachable or (done_only and not oas.is_done(ops, op.id, ledgers)):
            continue
        rows = [matrix[i] for i in op.matrix_ids if i in matrix]
        if len(rows) != len(op.matrix_ids):
            problems.append('%s: в матрице нет строки %s' % (op.id, ', '.join(i for i in op.matrix_ids if i not in matrix)))
            continue
        for role in ROLES:
            expect = max((row[role] for row in rows), key=RANK.get)
            result.append(dict(operation=op.id, service=op.service, method=op.method, path=op.path, role=role, user=ROLE_USERS[role],
                               expect=expect, matrix_ids=op.matrix_ids))
    return result, problems


def main(argv):
    ops = oas.load()
    cs, problems = cases(ops, load_matrix(), done_only='--all' not in argv)
    for c in cs:
        print('%-28s %-17s %-12s %s %s' % (c['operation'], c['role'], c['expect'], c['method'], c['path']))
    for p in problems:
        print('  -', p)
    operations = len(set(c['operation'] for c in cs))
    print('случаев: %d (операций %d), проблем: %d' % (len(cs), operations, len(problems)))
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
