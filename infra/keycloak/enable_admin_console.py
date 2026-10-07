#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Консоль администратора Keycloak в браузере на отладочном порту (make keycloak-console).

    python3 infra/keycloak/enable_admin_console.py [--url https://localhost:18445/auth] [--off]

Зачем. У Keycloak один публичный адрес (KC_HOSTNAME, шлюз на 8443), по нему он строит адреса входа во всех realm, в том числе в master.
Консоль на отладочном порту 18445 берёт адреса входа у realm master и получает адрес шлюза, где консоль закрыта намеренно
(ADR-021, шлюз отвечает 404). Браузер показывает «Timeout when waiting for 3rd party check iframe message» (находка F3-23).
Скрипт задаёт realm master собственный «Frontend URL», адрес отладочного порта. Realm dgm не меняется: токены для шлюза
по-прежнему выдаются с издателем на 8443.

Запуск повторяем. Настройка хранится в базе Keycloak и пропадает вместе с томом (make reset). Ключ --off возвращает прежнее состояние.
Только для отладочного стенда (DEBUG=1): на сервере консоль наружу не публикуется (runbook.md, раздел 2.1).
Администратор и центр сертификации берутся так же, как в tools/stand-checks/kc_client.py (secrets/keycloak_admin, secrets/tls_ca.crt).
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'tools', 'stand-checks'))
import kc_client as K


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--url', default='https://localhost:18445/auth', help='адрес консоли на отладочном порту')
    ap.add_argument('--off', action='store_true', help='убрать настройку')
    args = ap.parse_args()

    env = dict(os.environ)
    env['KC_PUBLIC'] = args.url
    for k in ('KC_TARGET', 'STUBS_TARGET', 'STUBS_ADMIN_TARGET'):
        env.pop(k, None)          # ходим прямо на отладочный порт, без подмены адреса шлюза
    _, admin = K.from_env(env)

    r = admin.call('GET', '/realms/master')
    if r.status != 200:
        sys.exit('realm master: %s %s (стенд поднят с DEBUG=1 и профилем auth?)' % (r.status, r.text[:200]))
    realm = r.json()
    attrs = realm.setdefault('attributes', {})
    current = attrs.get('frontendUrl')
    wanted = None if args.off else args.url
    if current == wanted:
        print('realm master: Frontend URL уже %s' % (wanted or 'не задан'))
        return
    if wanted:
        attrs['frontendUrl'] = wanted
    else:
        attrs.pop('frontendUrl', None)
    # Admin API заменяет атрибуты realm целиком, поэтому отправляется полное описание, прочитанное выше
    r = admin.call('PUT', '/realms/master', json_body=realm)
    if r.status != 204:
        sys.exit('обновление realm master: %s %s' % (r.status, r.text[:200]))
    print('realm master: Frontend URL %s -> %s' % (current or 'не задан', wanted or 'не задан'))
    if wanted:
        print('консоль: %s/admin/ (пользователь dgm-admin, пароль в secrets/keycloak_admin)' % wanted)


if __name__ == '__main__':
    main()
