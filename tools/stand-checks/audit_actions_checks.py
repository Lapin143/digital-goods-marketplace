#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Каждое действие сотрудника попадает в журнал аудита (шаг 23 среза 3 Ф4, NFT-3.6, FT-11.2, FT-11.3; TC-801).

    make audit-actions-check

Нужен набор full с отладочным файлом и тестовые пользователи Keycloak; запускается в конце задания e2e, когда остальные проверки стенда уже
сделали свои действия: решения модератора, обращения и смена e-mail у оператора, правки параметров, деактивация и анонимизация записей,
открытие журнала. Эта проверка ничего не делает сама: она читает журнал администратором (admin-1, настоящий вход с TOTP) и для каждого
действия сотрудника из таблицы ACTIONS требует, чтобы запись нашлась и была оформлена по правилам журнала: исполнитель это UUID, роль
из ожидаемых, тип и идентификатор объекта непустые, время записи есть, у записей сотрудников (кроме системных) адрес исполнителя
записан как есть, а не хешем. Если действия нет в журнале, красной становится строка с его именем: значит, оно выполняется без записи
или ни одна проверка стенда его не делает (тогда проверку надо добавить, а не убирать действие из таблицы).

Действия, которые на стенде не повторить без отдельного сценария, проверяют модульные и интеграционные тесты: возврат вручную
(`refund.marked_manual`, ManualRefundServiceTest и RefundIT), возврат заявки на доработку (`seller.returned`) и блокировка товара
(`product.blocked`, SellerModerationIT и ProductModerationIT). Только стандартная библиотека Python.
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import accounts_checks as A  # noqa: E402
import audit_checks as AU  # noqa: E402
import gateway_checks as G  # noqa: E402
import kc_client as K  # noqa: E402
import orders_checks as O  # noqa: E402
import seller_approval_checks as SA  # noqa: E402

expect = G.expect
UUID = re.compile(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
MODERATOR, OPERATOR, ADMIN, SYSTEM = 'moderator', 'support-operator', 'admin', 'system'

# действие -> (роли исполнителя, тип объекта или None, если тип проверяется только на непустоту). Имена действий взяты из кода сервисов:
# catalog-service (SellerEvents, ProductEvents), platform-service (support, auditadmin, identity).
ACTIONS = [
    ('seller.approved', {MODERATOR}, 'seller'),
    ('seller.rejected', {MODERATOR}, 'seller'),
    ('seller.document_opened', {MODERATOR}, None),
    ('product.published', {MODERATOR}, None),
    ('product.rejected', {MODERATOR}, None),
    ('support.ticket_taken', {OPERATOR}, 'ticket'),
    ('support.ticket_released', {OPERATOR}, 'ticket'),
    ('support.resend_requested', {OPERATOR}, 'ticket'),
    ('support.email_change_started', {OPERATOR}, 'ticket'),
    ('support.email_change_completed', {OPERATOR}, 'ticket'),
    ('parameter.changed', {ADMIN}, 'parameter'),
    ('audit.viewed', {ADMIN}, 'audit-log'),
    ('user.deactivated', {ADMIN, MODERATOR, OPERATOR, SYSTEM}, None),
    ('user.anonymized', {ADMIN, MODERATOR, OPERATOR, SYSTEM}, None),
    ('user.role_assigned', {ADMIN, MODERATOR, OPERATOR, SYSTEM}, None),
]
# Действия, чей адрес исполнителя сервис берёт из запроса сотрудника и пишет как есть (US-8.7). Завершение смены e-mail происходит
# по коду покупателя и адрес оператора не несёт, у user.* исполнитель может быть системой.
IP_AS_IS = {'seller.approved', 'seller.rejected', 'seller.document_opened', 'product.published', 'product.rejected', 'support.ticket_taken',
            'support.ticket_released', 'support.resend_requested', 'support.email_change_started', 'parameter.changed', 'audit.viewed'}


def newest(token, action):
    """Записи журнала по действию (новые первыми); журнал читается, пока запись не появится или не выйдет срок AU.WAIT."""
    A.eventually(lambda: bool(AU.items_of(AU.journal(token, action=action, limit=20))), wait=AU.WAIT, step=1.0)
    return AU.items_of(AU.journal(token, action=action, limit=20))


def check_action(token, action, roles, object_type):
    records = newest(token, action)
    if not expect('TC-801. %s: запись есть в журнале (найдено %d)' % (action, len(records)), bool(records), 'журнал по действию пуст'):
        return
    wrong_actor = [r for r in records if not UUID.match(str(r.get('actorId') or ''))]
    wrong_role = [r for r in records if r.get('actorRole') not in roles]
    no_object = [r for r in records if not r.get('objectType') or not r.get('objectId') or (object_type and r.get('objectType') != object_type)]
    no_time = [r for r in records if not r.get('occurredAt') or not r.get('recordId')]
    expect('TC-801. %s: исполнитель UUID, роль из %s, объект %s и идентификатор, время и номер записи есть' %
           (action, '/'.join(sorted(roles)), object_type or 'непустой'),
           not (wrong_actor or wrong_role or no_object or no_time),
           str([(r.get('actorRole'), r.get('objectType'), r.get('objectId')) for r in (wrong_actor + wrong_role + no_object + no_time)[:3]])[:300])
    if action in IP_AS_IS:
        expect('TC-801. %s: адрес исполнителя-сотрудника записан как есть, не хешем (US-8.7)' % action,
               all(bool(r.get('actorIp')) and r.get('actorIpHashed') is False for r in records),
               str([(r.get('actorRole'), r.get('actorIp'), r.get('actorIpHashed')) for r in records[:3]]))


def actions(ctx):
    print('== Каждое действие сотрудника есть в журнале аудита (NFT-3.6, FT-11.2; TC-801), действий в таблице: %d' % len(ACTIONS))
    token = ctx.administrator.token()
    for action, roles, object_type in ACTIONS:
        O.guarded('действие ' + action, check_action, token, action, roles, object_type)
    seen = {r.get('action') for r in AU.items_of(AU.journal(token, limit=100))}
    expect('Контроль: журнал читается целиком, на первой странице есть записи (%d видов действий)' % len(seen), bool(seen), '')


class Context:
    def __init__(self, kc, admin, people):
        self.kc, self.admin, self.people = kc, admin, people
        self.administrator = SA.Staff(kc, admin, people['admin-1'])


def main():
    kc, admin = K.from_env(dict(os.environ))
    people = G.users()
    if 'admin-1' not in people:
        print('Нет тестового пользователя admin-1 (make keycloak-users)', file=sys.stderr)
        return 2
    ctx = Context(kc, admin, people)
    ctx.administrator.token()
    O.guarded('actions', actions, ctx)
    return O.summary('audit_actions_checks')


if __name__ == '__main__':
    sys.exit(main())
