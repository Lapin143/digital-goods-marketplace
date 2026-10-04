#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Тестовые пользователи Keycloak: создаются скриптом из случайных паролей, в репозитории их нет.

    python3 infra/keycloak/provision_test_users.py [--out secrets/test_users.json]

Пересоздаёт одиннадцать учётных записей realm dgm (покупатели, продавцы и сотрудники, у части настроен TOTP), пароли и секреты TOTP
пишет в файл (по умолчанию secrets/test_users.json, каталог secrets/ не входит в Git, права 0600). Запуск повторяем: существующие
тестовые записи удаляются и создаются заново. Адреса Keycloak и администратор задаются переменными окружения (см. tools/stand-checks/kc_client.py).
Продавец и сотрудники получают только свою роль: роль по умолчанию buyer у них снимается (одна роль на пользователя).
"""
import argparse
import json
import os
import secrets
import string
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'tools', 'stand-checks'))
import kc_client as K

DOMAIN = 'test.dgm.local'
# имя, роль, телефон подтверждён, второй фактор настроен
USERS = [
    ('buyer-1', 'buyer', True, False),
    ('buyer-2', 'buyer', False, False),
    ('buyer-3', 'buyer', False, False),          # жертва проверки блокировки после пяти неудач
    ('seller-1', 'seller', True, True),
    ('seller-2', 'seller', True, False),         # без второго фактора: кабинет не должен открыться
    ('moderator-1', 'moderator', False, True),
    ('moderator-2', 'moderator', False, False),
    ('support-1', 'support-operator', False, True),
    ('support-2', 'support-operator', False, False),
    ('admin-1', 'admin', False, True),
    ('admin-2', 'admin', False, False),
]


def make_secret(n=20):
    return ''.join(secrets.choice(string.ascii_letters + string.digits) for _ in range(n))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', default=os.path.join(os.environ.get('DGM_SECRETS_DIR', 'secrets'), 'test_users.json'))
    args = ap.parse_args()
    kc, admin = K.from_env()
    out = []
    default_role = admin.realm('/roles/default-roles-%s' % kc.realm).json()
    for name, role, phone, otp in USERS:
        email = '%s@%s' % (name, DOMAIN)
        found = admin.realm('/users?username=%s&exact=true' % email).json()
        for u in found:
            r = admin.realm('/users/%s' % u['id'], 'DELETE')
            assert r.status == 204, 'удаление %s: %s' % (email, r.status)
        password = secrets.token_urlsafe(24)
        otp_secret = make_secret() if otp else None
        creds = [{'type': 'password', 'value': password, 'temporary': False}]
        if otp:
            creds.append({'type': 'otp', 'secretData': json.dumps({'value': otp_secret}),
                          'credentialData': json.dumps({'subType': 'totp', 'digits': 6, 'counter': 0, 'period': 30, 'algorithm': 'HmacSHA1'})})
        body = {'username': email, 'email': email, 'emailVerified': True, 'enabled': True, 'credentials': creds}
        if phone:
            body['attributes'] = {'phone_verified': ['true']}
        r = admin.realm('/users', 'POST', body)
        assert r.status == 201, 'создание %s: %s %s' % (email, r.status, r.text[:300])
        uid = r.headers['location'].rsplit('/', 1)[1]
        if role != 'buyer':
            rep = admin.realm('/roles/%s' % role).json()
            assert admin.realm('/users/%s/role-mappings/realm' % uid, 'DELETE', [default_role]).status == 204
            assert admin.realm('/users/%s/role-mappings/realm' % uid, 'POST', [rep]).status == 204
        out.append({'name': name, 'email': email, 'id': uid, 'role': role, 'password': password, 'otp_secret': otp_secret, 'phone_verified': phone})
        print('  %-12s %-17s %s' % (name, role, 'пароль + TOTP' if otp else 'пароль'))
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    fd = os.open(args.out, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'w', encoding='utf-8') as f:
        json.dump({'users': out}, f, ensure_ascii=False, indent=2)
    print('Создано пользователей: %d, данные входа в %s (права 0600)' % (len(out), args.out))
    return 0


if __name__ == '__main__':
    sys.exit(main())
