#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Удаляет realm dgm, чтобы Keycloak при следующем запуске применил файл realm заново (make keycloak-reimport).

Keycloak применяет файл realm только если realm с таким именем ещё нет. Правка файла в репозитории работающий realm не меняет:
сначала realm удаляют, затем перезапускают контейнер. Вместе с realm удаляются пользователи, сессии и клиентские секреты в базе;
тестовых пользователей создаёт заново make keycloak-users. Адреса и администратор задаются переменными окружения (kc_client.from_env).
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'tools', 'stand-checks'))
import kc_client as K


def main():
    _, admin = K.from_env()
    r = admin.realm('', 'DELETE')
    if r.status == 204:
        print('realm dgm удалён, при следующем запуске Keycloak применит файл realm заново')
        return 0
    if r.status == 404:
        print('realm dgm уже нет, при следующем запуске Keycloak применит файл realm')
        return 0
    print('удаление realm: %s %s' % (r.status, r.text[:300]))
    return 1


if __name__ == '__main__':
    sys.exit(main())
