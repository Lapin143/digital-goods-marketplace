#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Показ цели вехи M3: запрос проходит через шлюз с токеном (шаг 19 Ф3).

    make demo                 вход тестового покупателя, три запроса через шлюз, готовая команда curl
    make -s token             только токен доступа на выводе (для curl); другой пользователь: make -s token WHO=seller-1
    make otp WHO=seller-1     текущий код второго фактора (TOTP) тестового пользователя для входа в браузере: у продавца, модератора,
                              оператора поддержки и администратора с номером 1 секрет в secrets/test_users.json (у пользователей с номером 2 его нет)

Нужен поднятый набор с шлюзом и сервисами (make up SET=full DEBUG=1) и тестовые пользователи (make keycloak-users). Токен настоящий:
вход идёт по коду авторизации с PKCE через Keycloak, как у браузера. Через шлюз ходят запросы по адресу https://localhost:8443, центр
сертификации стенда: secrets/tls_ca.crt. Тот же сценарий в проверенном виде это дымовой тест (make smoke): здесь только то, что удобно показать.
Токен живёт 5 минут (infra/keycloak/gen_realm.py, ACCESS_TTL), поэтому для curl его получают заново, а не хранят.
"""
import json
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import gateway_checks as G  # noqa: E402
import kc_client as K  # noqa: E402

CA = G.CA
LINES = []


def out(text=''):
    print(text)
    LINES.append(text)


def token_for(name):
    kc, _ = K.from_env(dict(os.environ))
    people = G.users()
    if name not in people:
        print('Нет тестового пользователя %s. Есть: %s' % (name, ', '.join(sorted(people))), file=sys.stderr)
        return None
    token = G.login(kc, people[name])
    if not token:
        print('Вход пользователя %s не удался (стенд поднят с DEBUG=1, набор с профилем auth, make keycloak-users выполнен?)' % name, file=sys.stderr)
    return token


def show(response, limit=300):
    text = response.text.replace('\n', ' ')
    return '%d %s' % (response.status, text[:limit] + ('…' if len(text) > limit else ''))


def demo(name):
    out('== Вход: пользователь %s, код авторизации с PKCE через Keycloak' % name)
    token = token_for(name)
    if not token:
        return 1
    _, claims = K.decode_jwt(token)
    left = int(claims.get('exp', 0) - time.time())
    out('   токен получен: подписан Keycloak, роль и область в claims, срок ещё %d с' % left)
    out('   claims: sub=%s, aud=%s, scope=%s, azp=%s' % (claims.get('sub'), claims.get('aud'), claims.get('scope'), claims.get('azp')))
    failed = 0

    out()
    out('== Запрос 1. История заказов без токена: шлюз отвечает 401 и до сервиса не доходит')
    r = G.call('GET', '/api/v1/orders')
    out('   GET /api/v1/orders -> %s' % show(r))
    failed += r.status != 401

    out()
    out('== Запрос 2. История заказов с токеном: шлюз проверяет подпись, срок и область, дальше mTLS до order-service')
    r = G.call('GET', '/api/v1/orders', headers=G.bearer(token))
    out('   GET /api/v1/orders + Bearer -> %s' % show(r))
    out('   X-Correlation-Id: %s (тот же идентификатор в журналах шлюза и сервиса: make logs S=order-service)' % r.header('X-Correlation-Id'))
    failed += r.status != 200

    out()
    out('== Запрос 3. Публичная витрина: токен не нужен')
    r = G.call('GET', '/api/v1/products')
    out('   GET /api/v1/products -> %s' % show(r, 160))
    failed += r.status != 200

    out()
    out('== То же самое из командной строки (токен живёт недолго, команда берёт новый):')
    out('   TOKEN=$(make -s token)')
    out('   curl --cacert %s -H "Authorization: Bearer $TOKEN" https://localhost:8443/api/v1/orders' % CA)
    out()
    out('Результат: %s' % ('всё как ожидалось: без токена 401, с токеном 200, витрина 200' if not failed else 'расхождения с ожидаемым (%d), стенд не в порядке: make stand-check' % failed))
    return 1 if failed else 0


def otp(name):
    people = G.users()
    secret = (people.get(name) or {}).get('otp_secret')
    if not secret:
        print('У пользователя %s нет секрета TOTP (есть у: %s)' % (name, ', '.join(sorted(n for n, u in people.items() if u.get('otp_secret')))), file=sys.stderr)
        return 1
    step = 30
    left = step - int(time.time()) % step
    print('%s (действует ещё около %d с)' % (K.totp_code(secret.encode(), int(time.time() // step)), left))
    return 0


def main(argv):
    if argv and argv[0] == 'otp':
        return otp(argv[1] if len(argv) > 1 else 'seller-1')
    if argv and argv[0] == 'token':
        token = token_for(argv[1] if len(argv) > 1 else 'buyer-1')
        if not token:
            return 1
        print(token)
        return 0
    code = demo(argv[0] if argv else 'buyer-1')
    if os.environ.get('GITHUB_ACTIONS'):
        # Заметка задания CI: итог показа виден в интерфейсе GitHub без журналов
        keep = [l.strip() for l in LINES if l.strip().startswith(('GET', 'claims', 'токен', 'Результат', 'X-Corr'))]
        print('::notice title=demo::%s' % '%0A'.join(k.replace('%', '%25') for k in keep))
    return code


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
