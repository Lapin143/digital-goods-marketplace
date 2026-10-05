#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Проверки Keycloak на поднятом стенде: realm dgm ведёт себя так, как записано в roles-permissions.md (NFT-3.0, NFT-3.1, ST-13 в части Ф3).

    python3 tools/stand-checks/keycloak_checks.py

Перед запуском: стенд с профилем auth поднят, пользователи созданы (python3 infra/keycloak/provision_test_users.py).
Адреса и администратор: переменные окружения, см. kc_client.from_env. Проверки идут настоящим входом по коду авторизации с PKCE:
страница входа, пароль, второй фактор, обмен кода на токены. Что проверяется:
  токены: iss, aud=dgm-api, sub, срок 5 минут, область и роль по роли пользователя, phone_verified, amr, отсутствие e-mail в access-токене;
  второй фактор: продавец и сотрудники без TOTP токен не получают (кабинет не открывается), с TOTP получают amr из pwd и otp;
  разделение ролей: покупатель у клиента сотрудников и продавец у клиента покупателей не получают ни области, ни роли;
  сессии: refresh-токен одноразовый (повторное использование отзывает сессию), сроки простоя 30 и 15 минут;
  перебор: пять неудач закрывают вход, Admin API это показывает;
  расширение SMS подключено, вход по SMS пока закрыт отказом;
  настройки realm в работающем Keycloak: Argon2, защита от перебора, TLS обязателен;
  служебный клиент platform-service: токен по секрету, Admin API только в пределах выданных прав;
  VK ID через заглушку: полный вход нового пользователя, amr = vk;
  письма Keycloak доходят до SMTP заглушки.
"""
import json
import os
import re
import sys
import time
import urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import kc_client as K

PASSED, FAILED = 0, []


def ok(name):
    global PASSED
    PASSED += 1
    print('  ok    %s' % name)


def bad(name, details=''):
    FAILED.append(name)
    print('  ОШИБКА %s' % name)
    if details:
        for line in str(details).splitlines()[-6:]:
            print('        | %s' % line[:300])


def expect(name, cond, details=''):
    (ok if cond else (lambda n: bad(n, details)))(name)
    return bool(cond)


# Ожидаемое по roles-permissions.md, разделы 2.1 и 3 (вторая запись правил: realm генерирует gen_realm.py, документ сверяет check_realm.py)
BUYER = {'orders.create', 'orders.read', 'support.write', 'account.manage', 'seller.apply'}
EXPECT = {
    'buyer': ('dgm-web', BUYER, 1800),
    'seller': ('dgm-staff', {'seller.catalog'}, 900),
    'moderator': ('dgm-staff', {'staff.moderation'}, 900),
    'support-operator': ('dgm-staff', {'staff.support'}, 900),
    'admin': ('dgm-staff', {'staff.admin', 'staff.audit'}, 900),
}
STAFF_ROLES = ['seller', 'moderator', 'support-operator', 'admin']
UUID = re.compile(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')


def scopes_of(claims):
    return set(claims.get('scope', '').split()) - {'openid'}


def describe(r):
    return '%s %s' % (r.kind, K.page_message(r.page) if r.page and r.kind != 'tokens' else '')


def main():
    kc, admin = K.from_env()
    path = os.path.join(os.environ.get('DGM_SECRETS_DIR', 'secrets'), 'test_users.json')
    users = {u['name']: u for u in json.load(open(path, encoding='utf-8'))['users']}
    net = kc.net

    # --- обнаружение и ключи
    r = net.request('GET', kc.issuer + '/.well-known/openid-configuration')
    conf = r.json() if r.status == 200 else {}
    expect('discovery: издатель равен внешнему адресу %s' % kc.issuer, conf.get('issuer') == kc.issuer, r.text[:200])
    expect('discovery: PKCE S256 поддерживается', 'S256' in conf.get('code_challenge_methods_supported', []))
    expect('discovery: подпись токена не none', 'none' not in conf.get('id_token_signing_alg_values_supported', ['none']))
    jwks = net.request('GET', conf.get('jwks_uri', kc.issuer + '/protocol/openid-connect/certs'), headers={'Accept': 'application/json'}).json()
    sig = [k for k in jwks.get('keys', []) if k.get('use') == 'sig']
    expect('ключи подписи: RS256, размер не меньше 2048 бит', sig and all(k.get('alg') == 'RS256' and len(k.get('n', '')) >= 340 for k in sig), str(jwks)[:200])

    # --- токены по ролям
    tokens = {}
    for name in ('buyer-1', 'buyer-2', 'seller-1', 'moderator-1', 'support-1', 'admin-1'):
        u = users[name]
        client, want_scopes, idle = EXPECT[u['role']]
        r = kc.login(client, u['email'], u['password'], u['otp_secret'])
        if not expect('%s (%s): вход выдал токены' % (name, u['role']), r.ok, describe(r)):
            continue
        tokens[name] = r
        a = r.access()
        head = K.decode_jwt(r.tokens['access_token'])[0]
        expect('%s: iss равен внешнему адресу, azp равен клиенту %s' % (name, client), a.get('iss') == kc.issuer and a.get('azp') == client, str((a.get('iss'), a.get('azp'))))
        expect('%s: aud содержит dgm-api' % name, 'dgm-api' in (a['aud'] if isinstance(a.get('aud'), list) else [a.get('aud')]), str(a.get('aud')))
        expect('%s: sub это UUID, подпись RS256 и есть kid' % name, bool(UUID.match(a.get('sub', ''))) and head.get('alg') == 'RS256' and head.get('kid'), str(head))
        expect('%s: срок access-токена 5 минут' % name, a['exp'] - a['iat'] == 300, str(a['exp'] - a['iat']))
        expect('%s: области равны %s' % (name, sorted(want_scopes)), scopes_of(a) == want_scopes, a.get('scope'))
        expect('%s: одна роль %s' % (name, u['role']), (a.get('realm_access') or {}).get('roles') == [u['role']], str(a.get('realm_access')))
        expect('%s: в access-токене нет e-mail и имени' % name, not ({'email', 'name', 'preferred_username', 'given_name'} & set(a)), str(sorted(a)))
        expect('%s: refresh-токен есть, простой %d минут' % (name, idle // 60), idle - 3 <= (r.tokens.get('refresh_expires_in') or 0) <= idle, str(r.tokens.get('refresh_expires_in')))   # Keycloak считает остаток срока на момент ответа, на границе секунды он на единицу меньше
        amr = a.get('amr')
        if u['otp_secret']:
            expect('%s: amr содержит pwd и otp' % name, sorted(amr or []) == ['otp', 'pwd'], str(amr))
        else:
            expect('%s: amr равен pwd' % name, amr == ['pwd'], str(amr))
    if 'buyer-1' in tokens and 'buyer-2' in tokens:
        expect('phone_verified: true у buyer-1', tokens['buyer-1'].access().get('phone_verified') is True)
        expect('phone_verified: у buyer-2 нет или false', tokens['buyer-2'].access().get('phone_verified') in (None, False))
        expect('id-токен buyer-1 содержит e-mail для интерфейса', tokens['buyer-1'].id_claims().get('email') == users['buyer-1']['email'])

    # --- второй фактор обязателен (NFT-3.0)
    for name in ('seller-2', 'moderator-2', 'support-2', 'admin-2'):
        u = users[name]
        r = kc.login('dgm-staff', u['email'], u['password'])
        expect('%s: без настроенного TOTP токена нет, Keycloak требует настроить второй фактор' % name, r.kind == 'totp_setup' and not r.tokens, describe(r))
    u = users['seller-1']
    r = kc.login('dgm-staff', u['email'], u['password'])
    expect('seller-1: пароль без кода TOTP токена не даёт (запрашивается код)', r.kind == 'otp_required', describe(r))
    r = kc.login('dgm-staff', u['email'], u['password'], otp_secret='A' * 20, otp_tries=1)
    expect('seller-1: неверный код TOTP токена не даёт', r.kind == 'otp_rejected', describe(r))
    admin.clear_attacks(u['id'])        # быстрые неудачи подряд (меньше секунды) закрывают вход на минуту, для следующих проверок блокировку снимаем

    # --- разделение ролей
    r = kc.login('dgm-staff', users['buyer-1']['email'], users['buyer-1']['password'])
    if expect('покупатель входит в клиент сотрудников без кода', r.ok, describe(r)):
        a = r.access()
        expect('покупатель у dgm-staff: нет ни области, ни роли', not scopes_of(a) and not (a.get('realm_access') or {}).get('roles'), '%s %s' % (a.get('scope'), a.get('realm_access')))
    u = users['seller-1']
    r = kc.login('dgm-web', u['email'], u['password'], u['otp_secret'])
    if expect('продавец входит в клиент покупателей (со вторым фактором)', r.ok, describe(r)):
        a = r.access()
        expect('продавец у dgm-web: нет областей покупателя и роли buyer', not scopes_of(a) and not (a.get('realm_access') or {}).get('roles'), '%s %s' % (a.get('scope'), a.get('realm_access')))

    # --- refresh-токен одноразовый
    if 'buyer-1' in tokens:
        old = tokens['buyer-1'].tokens['refresh_token']
        r1 = kc.refresh('dgm-web', old)
        expect('refresh: первое использование выдаёт новую пару токенов', r1.status == 200 and r1.json().get('refresh_token') not in (None, old), r1.text[:200])
        r2 = kc.refresh('dgm-web', old)
        expect('refresh: повторное использование того же токена отклонено', r2.status == 400 and r2.json().get('error') == 'invalid_grant', r2.text[:200])
        r3 = kc.refresh('dgm-web', r1.json().get('refresh_token', 'x')) if r1.status == 200 else r2
        expect('refresh: после повторного использования сессия отозвана, новый токен тоже не работает', r3.status == 400, r3.text[:200])

    # --- запрещённые способы получения токена
    r = net.request('POST', kc.issuer + '/protocol/openid-connect/token', form={
        'grant_type': 'password', 'client_id': 'dgm-web', 'username': users['buyer-1']['email'], 'password': users['buyer-1']['password']})
    expect('пароль в обмен на токен (direct grant) для dgm-web выключен', r.status in (400, 401) and 'access_token' not in r.text, r.text[:200])
    q = {'client_id': 'dgm-web', 'redirect_uri': kc.redirect_base + '/callback', 'response_type': 'code', 'scope': 'openid', 'state': 'x'}
    r = net.request('GET', '%s/protocol/openid-connect/auth?%s' % (kc.issuer, urllib.parse.urlencode(q)))
    err = (r.location or '') + r.text
    expect('код авторизации без PKCE отклонён', 'error=invalid_request' in err or 'code_challenge' in err, err[:200])
    q.update({'response_type': 'token', 'code_challenge': 'x' * 43, 'code_challenge_method': 'S256'})
    r = net.request('GET', '%s/protocol/openid-connect/auth?%s' % (kc.issuer, urllib.parse.urlencode(q)))
    err = (r.location or '') + r.text
    expect('неявный поток (response_type=token) отклонён', 'error=' in err or 'unsupported' in err.lower() or 'kc-error-message' in err, err[:200])
    q.update({'response_type': 'code', 'redirect_uri': 'https://evil.example/cb'})
    r = net.request('GET', '%s/protocol/openid-connect/auth?%s' % (kc.issuer, urllib.parse.urlencode(q)))
    expect('чужой redirect_uri отклонён (страница ошибки, переход не выполняется)', r.status == 400 and 'evil.example' not in (r.location or ''), '%s %s' % (r.status, r.location))

    # --- перебор паролей: пять неудач закрывают вход
    v = users['buyer-3']
    attempts = [kc.login('dgm-web', v['email'], 'неверный-пароль-%d' % i) for i in range(5)]
    expect('перебор: пять неверных паролей не дают токенов', all(a.kind == 'login_error' for a in attempts), ','.join(a.kind for a in attempts))
    r = kc.login('dgm-web', v['email'], v['password'])
    expect('перебор: после пяти неудач верный пароль не принимается', not r.ok, describe(r))
    st = admin.realm('/attack-detection/brute-force/users/%s' % v['id']).json()
    expect('перебор: Admin API показывает блокировку (disabled=true)', st.get('disabled') is True, str(st))
    admin.realm('/attack-detection/brute-force/users/%s' % v['id'], 'DELETE')
    r = kc.login('dgm-web', v['email'], v['password'])
    expect('перебор: после снятия блокировки вход возможен', r.ok, describe(r))

    # --- расширение SMS и потоки
    providers = [p.get('id') for p in admin.realm('/authentication/authenticator-providers').json()]
    expect('расширение SMS: провайдер sms-otp подключён', 'sms-otp' in providers, str(providers)[:300])
    flows = {f['alias'] for f in admin.realm('/authentication/flows').json()}
    expect('потоки входа: dgm-browser, sms-login, dgm-vk-post-login есть', {'dgm-browser', 'sms-login', 'dgm-vk-post-login'} <= flows, str(sorted(flows)))
    r = kc.login('dgm-sms', users['buyer-1']['email'], users['buyer-1']['password'])
    expect('вход по SMS пока закрыт отказом (логика кодов в Ф4), токена нет', not r.ok, describe(r))
    client = next(c for c in admin.realm('/clients?clientId=dgm-sms').json())
    at = client['attributes']
    expect('dgm-sms: токен 15 минут, сессия не более часа, refresh-токена нет',
           at.get('access.token.lifespan') == '900' and at.get('client.session.max.lifespan') == '3600' and at.get('use.refresh.tokens') == 'false', str(at))

    # --- настройки работающего realm
    rl = admin.realm('').json()
    expect('realm: TLS обязателен, защита от перебора 5 попыток и 15 минут',
           rl.get('sslRequired') == 'all' and rl.get('bruteForceProtected') and rl.get('failureFactor') == 5 and rl.get('maxFailureWaitSeconds') == 900,
           str({k: rl.get(k) for k in ('sslRequired', 'bruteForceProtected', 'failureFactor', 'maxFailureWaitSeconds')}))
    expect('realm: политика паролей с Argon2', 'hashAlgorithm(argon2)' in rl.get('passwordPolicy', ''), rl.get('passwordPolicy'))
    expect('realm: refresh-токен одноразовый (revokeRefreshToken, maxReuse 0)', rl.get('revokeRefreshToken') is True and rl.get('refreshTokenMaxReuse') == 0)
    algos = set()
    for u in users.values():
        creds = admin.realm('/users/%s/credentials' % u['id']).json()
        pw = [c for c in creds if c['type'] == 'password']
        algos |= {json.loads(c['credentialData']).get('algorithm') for c in pw}
        if any('value' in c for c in creds):
            bad('пароль %s отдаётся открытым значением' % u['name'])
    expect('NFT-3.1: пароли всех %d тестовых пользователей хранятся как argon2' % len(users), algos == {'argon2'}, str(algos))

    # --- служебный клиент platform-service: токен по секрету и права Admin API
    secret_file = os.path.join(os.environ.get('DGM_SECRETS_DIR', 'secrets'), 'keycloak_client_platform')
    if os.path.exists(secret_file):
        secret = open(secret_file, encoding='utf-8').read().strip()
        r = net.request('POST', kc.issuer + '/protocol/openid-connect/token', form={
            'grant_type': 'client_credentials', 'client_id': 'platform-service', 'client_secret': secret})
        if expect('platform-service: токен по client_credentials с секретом из файла', r.status == 200, r.text[:200]):
            h = {'Authorization': 'Bearer ' + r.json()['access_token'], 'Accept': 'application/json'}
            r1 = net.request('GET', '%s/admin/realms/%s/users?username=%s&exact=true' % (kc.public, kc.realm, users['buyer-2']['email']), headers=h)
            expect('platform-service: поиск пользователей через Admin API разрешён', r1.status == 200 and len(r1.json()) == 1, '%s %s' % (r1.status, r1.text[:150]))
            r2 = net.request('PUT', '%s/admin/realms/%s' % (kc.public, kc.realm), json_body={'displayName': 'x'}, headers=h)
            expect('platform-service: менять настройки realm нельзя (403)', r2.status == 403, '%s %s' % (r2.status, r2.text[:150]))
            r3 = net.request('GET', '%s/admin/realms/master' % kc.public, headers=h)
            expect('platform-service: realm master недоступен (403 или 401)', r3.status in (401, 403), str(r3.status))
        r = net.request('POST', kc.issuer + '/protocol/openid-connect/token', form={
            'grant_type': 'client_credentials', 'client_id': 'platform-service', 'client_secret': 'неверный'})
        expect('platform-service: с неверным секретом токен не выдаётся', r.status in (400, 401), str(r.status))

    # --- VK ID через заглушку: новый пользователь входит, amr равен vk, сотрудникам вход через VK закрыт отдельным потоком (проверяет check_realm)
    vk_email = 'vk-user@vk.test'
    for u in admin.realm('/users?email=%s&exact=true' % vk_email).json():
        admin.realm('/users/%s' % u['id'], 'DELETE')
    resp, loc, verifier, redirect_uri = kc.authorize('dgm-web', extra={'kc_idp_hint': 'vkid'})
    first = (loc or resp.location or resp.text)[:200]
    kc.net.cookies.clear()
    r = kc.login_idp('dgm-web', 'vkid')
    if expect('VK ID: вход нового пользователя через заглушку завершён токенами', r.ok, describe(r) + ' ' + str(r.page)[:200]):
        a = r.access()
        expect('VK ID: amr равен vk, роль buyer', a.get('amr') == ['vk'] and (a.get('realm_access') or {}).get('roles') == ['buyer'], '%s %s' % (a.get('amr'), a.get('realm_access')))
        expect('VK ID: области покупателя выданы', scopes_of(a) == BUYER, a.get('scope'))
    else:
        print('        | первый переход: %s' % first)

    # --- письма Keycloak доходят до SMTP заглушки
    u = users['buyer-2']
    r = admin.realm('/users/%s/execute-actions-email?client_id=dgm-web&redirect_uri=%s' % (u['id'], urllib.parse.quote(kc.redirect_base + '/callback', safe='')),
                    'PUT', ['UPDATE_PASSWORD'])
    expect('письмо о смене пароля: Keycloak принял запрос (204)', r.status == 204, '%s %s' % (r.status, r.text[:200]))
    found = []
    for _ in range(10):
        m = net.request('GET', 'https://external-stubs:8444/admin/emails?to=%s' % urllib.parse.quote(u['email']), headers={'Accept': 'application/json'})
        if m.status == 200:
            body = m.json()
            found = body if isinstance(body, list) else body.get('items') or body.get('emails') or []
        if found:
            break
        time.sleep(1)
    expect('письмо о смене пароля дошло до SMTP заглушки', len(found) >= 1, 'заглушка вернула %s' % str(found)[:200])

    print()
    print('Проверок успешно: %d, с ошибками: %d' % (PASSED, len(FAILED)))
    if os.environ.get('GITHUB_ACTIONS'):
        print('::notice title=keycloak_checks::Проверок успешно: %d, с ошибками: %d' % (PASSED, len(FAILED)))
    if FAILED:
        for f in FAILED:
            print('  - %s' % f)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
