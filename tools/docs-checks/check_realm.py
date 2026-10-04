# -*- coding: utf-8 -*-
"""Проверка realm Keycloak (infra/keycloak/realm/dgm-realm.json) по документу roles-permissions.md.

Документ задаёт правила, JSON реализует их, скрипт сверяет:
  - роли (раздел 2) и роль по умолчанию buyer; у пользователя одна роль, лишних ролей нет;
  - области токена (раздел 3): каждая область есть как область клиента, выдаётся только своей роли (scopeMappings), клиенты получают
    свои наборы: dgm-web области покупателя, dgm-staff области продавца и сотрудников, dgm-sms области сессии по SMS (раздел 5);
  - сроки жизни (раздел 2.1): access-токен, простой и максимум сессии по группам, refresh с ротацией, сессия по SMS без refresh;
  - защита от перебора: число неудач и время блокировки (раздел 2.1);
  - второй фактор (NFT-3.0): в потоке входа у каждой из четырёх ролей есть условный подпоток с обязательной формой OTP, у остальных ролей его нет,
    нет условия «OTP, если настроен» (оно позволило бы войти без второго фактора), вход через VK ID и по SMS для этих ролей закрыт;
  - утверждения токена: audience dgm-api, phone_verified, amr из набора раздела 2.1 с положительным maxAge;
  - пароли (NFT-3.1): Argon2, минимальная длина; TLS обязателен, подтверждение почты включено;
  - клиенты: код авторизации с PKCE, без неявного потока и выдачи токена по паролю, служебный клиент с урезанными правами;
  - в файле нет секретов и учётных записей людей: только подстановки ${...} и служебная запись клиента;
  - целостность потоков: все подпотоки и настройки существуют, идентификаторы не повторяются;
  - файл равен результату infra/keycloak/gen_realm.py, провайдер sms-otp в исходниках расширения совпадает с потоком входа.
"""
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import docslib as L

REALM_FILE = os.environ.get('DGM_REALM_FILE') or os.path.join(L.REPO, 'infra', 'keycloak', 'realm', 'dgm-realm.json')
RP = os.path.join(L.ARCH, 'roles-permissions.md')
SPI = os.path.join(L.REPO, 'infra', 'keycloak', 'sms-otp')
WHO_ROLE = [('Оператор поддержки', 'support-operator'), ('Покупатель', 'buyer'), ('Продавец', 'seller'),
            ('Модератор', 'moderator'), ('Администратор', 'admin')]
AMR_WORDS = {'pwd', 'vk', 'sms', 'otp'}
SECRET_KEYS = re.compile(r'(secret|password|privateKey|credential)', re.I)
# поля, чьё имя похоже на секрет, но значение — настройка: политика паролей, алиас потока, флаг требуемого действия
NOT_SECRET_KEYS = {'passwordPolicy', 'resetCredentialsFlow', 'require.password.update.after.registration'}


def seconds(text):
    """«5 минут», «30 минут», «10 часов», «1 час» в секунды; None, если срока нет."""
    m = re.search(r'(\d+)\s*(минут|час)', text)
    if not m:
        return None
    return int(m.group(1)) * (60 if m.group(2) == 'минут' else 3600)


def doc_rules(rep):
    text = L.read(RP)
    rules = {}
    # --- роли и второй фактор (раздел 2)
    t = L.find_table(text, 'Роль', 'Роль в Keycloak')
    roles, otp = [], set()
    for r in t['rows']:
        found = L.backticked(r[1])
        if found and found[0] not in roles:
            roles.append(found[0])
        if found and r[2].startswith('Обязателен'):
            otp.add(found[0])
    rules['roles'], rules['otp_roles'] = roles, otp
    # --- сроки (раздел 2.1)
    t = L.find_table(text, 'Сессия', 'Access-токен')
    life = {}
    for r in t['rows']:
        key = 'buyer' if r[0].startswith('Покупатель') else 'staff' if r[0].startswith('Продавец') else 'sms' if 'SMS' in r[0] else None
        if key:
            life[key] = dict(access=seconds(r[1]), refresh=r[2], idle=seconds(r[3]), max=seconds(r[4]))
    rules['life'] = life
    m = re.search(r'после (\d+) неудач вход закрывается на (\d+) минут', text)
    rules['brute'] = (int(m.group(1)), int(m.group(2)) * 60) if m else None
    # --- амр и аудитория (таблица полей токена)
    t = L.find_table(text, 'Поле токена', 'Значение')
    for r in t['rows']:
        if r[0].startswith('`amr`'):
            rules['amr'] = set(L.backticked(r[1]))
        if r[0].startswith('`iss`'):
            rules['aud'] = [x for x in L.backticked(r[1]) if x.startswith('dgm-')]
    # --- области (раздел 3)
    t = L.find_table(text, 'Область', 'Что открывает')
    scopes = {}
    for r in t['rows']:
        name = L.backticked(r[0])[0]
        who = r[2]
        roles_of = {role for word, role in WHO_ROLE if word in who}
        sms = 'сессия по SMS' in who and 'но не сессия по SMS' not in who
        scopes[name] = dict(roles=roles_of, sms=sms)
    rules['scopes'] = scopes
    if not (roles and life and rules['brute'] and scopes and rules.get('amr') and rules.get('aud')):
        rep.err('roles-permissions.md', 'не удалось прочитать правила: %s' % {k: bool(v) for k, v in rules.items()})
    return rules


def by_alias(items, key='alias'):
    return {i[key]: i for i in items}


def walk_flow(flows, alias, seen=None):
    """Все исполнения потока вместе с вложенными: (поток, исполнение)."""
    seen = seen or set()
    if alias in seen:
        return
    seen.add(alias)
    for e in flows[alias]['authenticationExecutions']:
        yield alias, e
        if e.get('authenticatorFlow') and e.get('flowAlias') in flows:
            yield from walk_flow(flows, e['flowAlias'], seen)


def main():
    rep = L.Report('realm')
    realm = json.load(open(REALM_FILE, encoding='utf-8'))
    rules = doc_rules(rep)
    if rep.problems:
        return rep.finish()
    w = 'dgm-realm.json'

    # --- состав и целостность
    if realm.get('realm') != 'dgm':
        rep.err(w, 'realm %s, ожидался dgm' % realm.get('realm'))
    if realm.get('sslRequired') != 'all':
        rep.err(w, 'sslRequired %s: TLS обязателен для всех адресов (NFT-3.3)' % realm.get('sslRequired'))
    if not realm.get('verifyEmail'):
        rep.err(w, 'verifyEmail выключен: адрес покупателя не подтверждается (FT-1.1)')
    if not realm.get('resetPasswordAllowed'):
        rep.err(w, 'resetPasswordAllowed выключен: нет восстановления пароля (FT-1.4)')

    # --- роли (раздел 2)
    role_names = [r['name'] for r in realm['roles']['realm']]
    for r in rules['roles']:
        if r not in role_names:
            rep.err(w, 'нет роли %s из roles-permissions.md (раздел 2)' % r)
    for r in role_names:
        if r not in rules['roles'] and r != 'default-roles-dgm':
            rep.err(w, 'роль %s не описана в roles-permissions.md (раздел 2)' % r)
    default = next((r for r in realm['roles']['realm'] if r['name'] == 'default-roles-dgm'), None)
    if not default or (default.get('composites') or {}).get('realm') != ['buyer'] or (default.get('composites') or {}).get('client'):
        rep.err(w, 'роль по умолчанию должна включать только buyer: у новой учётной записи одна роль')
    if (realm.get('defaultRole') or {}).get('name') != 'default-roles-dgm':
        rep.err(w, 'defaultRole не default-roles-dgm')

    # --- области (раздел 3)
    cscopes = by_alias(realm['clientScopes'], 'name')
    mappings = {}
    for m in realm.get('scopeMappings', []):
        mappings.setdefault(m['clientScope'], set()).update(m['roles'])
    for name, info in sorted(rules['scopes'].items()):
        c = cscopes.get(name)
        if not c:
            rep.err(w, 'нет области клиента %s из раздела 3' % name)
            continue
        if c.get('attributes', {}).get('include.in.token.scope') != 'true':
            rep.err(w, 'область %s не входит в утверждение scope токена (include.in.token.scope)' % name)
        if mappings.get(name) != info['roles']:
            rep.err(w, 'области %s выдаются ролям %s, в документе %s' % (name, sorted(mappings.get(name, [])), sorted(info['roles'])))
    for name in mappings:
        if name not in rules['scopes']:
            rep.err(w, 'область %s с привязкой к ролям не описана в документе' % name)
    for name, c in cscopes.items():
        if c.get('attributes', {}).get('include.in.token.scope') == 'true' and name not in rules['scopes']:
            rep.err(w, 'область %s попадает в scope токена, но не описана в roles-permissions.md (раздел 3)' % name)

    # --- клиенты
    clients = by_alias(realm['clients'], 'clientId')
    all_scopes = set(rules['scopes'])
    buyer_scopes = {n for n, i in rules['scopes'].items() if i['roles'] == {'buyer'}}
    sms_scopes = {n for n, i in rules['scopes'].items() if i['sms']}
    staff_scopes = all_scopes - buyer_scopes
    want = {'dgm-web': buyer_scopes, 'dgm-staff': staff_scopes, 'dgm-sms': sms_scopes}
    for cid, scopes in want.items():
        c = clients.get(cid)
        if not c:
            rep.err(w, 'нет клиента %s' % cid)
            continue
        have = {s for s in c['defaultClientScopes'] if s in all_scopes}
        if have != scopes:
            rep.err(w, 'клиент %s: области %s, ожидалось %s' % (cid, sorted(have), sorted(scopes)))
        if c.get('optionalClientScopes'):
            rep.err(w, 'клиент %s: необязательные области %s, запрошенная извне область расширила бы права' % (cid, c['optionalClientScopes']))
        for need in ('dgm-api', 'basic', 'roles'):
            if need not in c['defaultClientScopes']:
                rep.err(w, 'клиент %s: нет области %s (аудитория, sub, роль в токене)' % (cid, need))
        if not c.get('publicClient') or not c.get('standardFlowEnabled'):
            rep.err(w, 'клиент %s должен быть публичным с кодом авторизации' % cid)
        if c.get('implicitFlowEnabled') or c.get('directAccessGrantsEnabled') or c.get('serviceAccountsEnabled'):
            rep.err(w, 'клиент %s: неявный поток, выдача токена по паролю и служебная учётная запись должны быть выключены' % cid)
        if c.get('fullScopeAllowed'):
            rep.err(w, 'клиент %s: fullScopeAllowed должен быть false, иначе в токен попадут все роли пользователя' % cid)
        if c.get('attributes', {}).get('pkce.code.challenge.method') != 'S256':
            rep.err(w, 'клиент %s: PKCE S256 не обязателен' % cid)
        if any(not u.startswith(('https://', '${DGM_PUBLIC_ORIGIN')) or u in ('*', 'https://*') for u in c['redirectUris']):
            rep.err(w, 'клиент %s: адреса возврата %s должны быть https и без подстановочных адресов целиком' % (cid, c['redirectUris']))
    svc = clients.get('platform-service')
    if not svc or not svc.get('serviceAccountsEnabled') or svc.get('publicClient') or svc.get('standardFlowEnabled'):
        rep.err(w, 'platform-service: нужен закрытый клиент только со служебной учётной записью')
    elif svc.get('secret') != '${DGM_PLATFORM_CLIENT_SECRET}':
        rep.err(w, 'platform-service: секрет клиента должен быть подстановкой ${DGM_PLATFORM_CLIENT_SECRET}, а не значением')
    sa = [u for u in realm.get('users', []) if u.get('serviceAccountClientId') == 'platform-service']
    if len(sa) != 1 or set(sa[0].get('clientRoles', {}).get('realm-management', [])) != {'manage-users', 'view-users', 'query-users'}:
        rep.err(w, 'служебная запись platform-service: права только manage-users, view-users, query-users, не realm-admin')

    # --- сроки жизни (раздел 2.1) и ротация refresh
    life = rules['life']
    if realm.get('accessTokenLifespan') != life['buyer']['access']:
        rep.err(w, 'срок access-токена realm %s с, в документе %s с' % (realm.get('accessTokenLifespan'), life['buyer']['access']))
    if realm.get('ssoSessionIdleTimeout') != life['buyer']['idle'] or realm.get('ssoSessionMaxLifespan') != life['buyer']['max']:
        rep.err(w, 'сессия покупателя (realm): простой %s, максимум %s; в документе %s и %s'
                % (realm.get('ssoSessionIdleTimeout'), realm.get('ssoSessionMaxLifespan'), life['buyer']['idle'], life['buyer']['max']))
    if not realm.get('revokeRefreshToken') or realm.get('refreshTokenMaxReuse') != 0:
        rep.err(w, 'refresh-токен должен быть одноразовым (revokeRefreshToken true, refreshTokenMaxReuse 0)')
    for cid, key in (('dgm-web', 'buyer'), ('dgm-staff', 'staff'), ('dgm-sms', 'sms')):
        a = (clients.get(cid) or {}).get('attributes', {})
        d = life[key]
        if a.get('access.token.lifespan') != str(d['access']):
            rep.err(w, 'клиент %s: срок access-токена %s с, в документе %s с' % (cid, a.get('access.token.lifespan'), d['access']))
        if d['idle'] is not None and a.get('client.session.idle.timeout') != str(d['idle']):
            rep.err(w, 'клиент %s: простой сессии %s с, в документе %s с' % (cid, a.get('client.session.idle.timeout'), d['idle']))
        if d['idle'] is None and 'client.session.idle.timeout' in a:
            rep.err(w, 'клиент %s: в документе простоя нет, а у клиента он задан' % cid)
        if a.get('client.session.max.lifespan') != str(d['max']):
            rep.err(w, 'клиент %s: максимум сессии %s с, в документе %s с' % (cid, a.get('client.session.max.lifespan'), d['max']))
        refresh_expected = 'false' if d['refresh'].startswith('Нет') else 'true'
        if a.get('use.refresh.tokens') != refresh_expected:
            rep.err(w, 'клиент %s: use.refresh.tokens %s, по документу (%s) нужно %s' % (cid, a.get('use.refresh.tokens'), d['refresh'], refresh_expected))

    # --- защита от перебора
    n, wait = rules['brute']
    if (not realm.get('bruteForceProtected') or realm.get('failureFactor') != n or realm.get('permanentLockout')
            or realm.get('waitIncrementSeconds') != wait or realm.get('maxFailureWaitSeconds') != wait):
        rep.err(w, 'защита от перебора: нужно %d неудач и блокировка на %d с (без постоянной), в realm failureFactor %s, wait %s, max %s'
                % (n, wait, realm.get('failureFactor'), realm.get('waitIncrementSeconds'), realm.get('maxFailureWaitSeconds')))

    # --- пароли (NFT-3.1)
    policy = realm.get('passwordPolicy', '')
    if 'hashAlgorithm(argon2)' not in policy:
        rep.err(w, 'политика паролей без hashAlgorithm(argon2) (NFT-3.1)')
    m = re.search(r'length\((\d+)\)', policy)
    if not m or int(m.group(1)) < 12:
        rep.err(w, 'политика паролей: минимальная длина меньше 12')

    # --- потоки: целостность
    flows = by_alias(realm['authenticationFlows'])
    ids = [f['id'] for f in realm['authenticationFlows']]
    if len(set(ids)) != len(ids):
        rep.err(w, 'идентификаторы потоков повторяются')
    configs = by_alias(realm['authenticatorConfig'])
    if len({c['id'] for c in realm['authenticatorConfig']}) != len(realm['authenticatorConfig']):
        rep.err(w, 'идентификаторы настроек исполнений повторяются')
    for f in realm['authenticationFlows']:
        for e in f['authenticationExecutions']:
            if e.get('authenticatorFlow') and e.get('flowAlias') not in flows:
                rep.err(w, 'поток %s ссылается на несуществующий подпоток %s' % (f['alias'], e.get('flowAlias')))
            if e.get('authenticatorConfig') and e['authenticatorConfig'] not in configs:
                rep.err(w, 'поток %s ссылается на несуществующую настройку %s' % (f['alias'], e['authenticatorConfig']))
            if e['requirement'] not in ('REQUIRED', 'ALTERNATIVE', 'CONDITIONAL', 'DISABLED'):
                rep.err(w, 'поток %s: недопустимое требование %s' % (f['alias'], e['requirement']))
    for key in ('browserFlow', 'registrationFlow', 'directGrantFlow', 'resetCredentialsFlow', 'clientAuthenticationFlow',
                'dockerAuthenticationFlow', 'firstBrokerLoginFlow'):
        if realm.get(key) not in flows:
            rep.err(w, '%s: потока %s нет в authenticationFlows' % (key, realm.get(key)))
    for c in realm['clients']:
        for k, fid in (c.get('authenticationFlowBindingOverrides') or {}).items():
            if fid not in ids:
                rep.err(w, 'клиент %s: переопределение потока %s ссылается на несуществующий идентификатор' % (c['clientId'], k))
    sms_flow_id = (clients.get('dgm-sms') or {}).get('authenticationFlowBindingOverrides', {}).get('browser')
    sms_flow = next((f for f in realm['authenticationFlows'] if f['id'] == sms_flow_id), None)
    if not sms_flow:
        rep.err(w, 'dgm-sms: поток входа не переопределён (нужен sms-login)')

    # --- второй фактор (NFT-3.0)
    otp_found = {}
    browser = realm.get('browserFlow')
    if browser in flows:
        for owner, e in walk_flow(flows, browser):
            if e.get('authenticator') == 'conditional-user-configured':
                rep.err(w, 'в потоке входа есть «Condition - user configured» (%s): второй фактор стал бы необязательным для тех, у кого он не настроен' % owner)
            if e.get('authenticator') == 'auth-otp-form':
                cond = None
                for ex in flows[owner]['authenticationExecutions']:
                    if ex.get('authenticator') == 'conditional-user-role':
                        cond = configs.get(ex.get('authenticatorConfig'), {}).get('config', {})
                parent = next((x for fl in flows.values() for x in fl['authenticationExecutions'] if x.get('flowAlias') == owner), None)
                otp_found[owner] = (cond, e['requirement'], parent['requirement'] if parent else None)
        pw = [e for _o, e in walk_flow(flows, browser) if e.get('authenticator') == 'auth-username-password-form']
        if len(pw) != 1 or pw[0]['requirement'] != 'REQUIRED':
            rep.err(w, 'в потоке входа должна быть одна обязательная форма пароля')
    got_roles = set()
    for owner, (cond, req, parent_req) in otp_found.items():
        role = (cond or {}).get('condUserRole')
        if not cond or (cond or {}).get('negate') != 'false' or role not in rules['otp_roles']:
            rep.err(w, 'подпоток %s: форма OTP без условия по роли из списка второго фактора' % owner)
            continue
        got_roles.add(role)
        if req != 'REQUIRED' or parent_req != 'CONDITIONAL':
            rep.err(w, 'подпоток %s: форма OTP должна быть REQUIRED внутри CONDITIONAL, сейчас %s внутри %s' % (owner, req, parent_req))
    if got_roles != rules['otp_roles']:
        rep.err(w, 'второй фактор в потоке входа у ролей %s, по документу обязателен у %s' % (sorted(got_roles), sorted(rules['otp_roles'])))
    # другие входы закрыты тем, кто обязан иметь второй фактор: оба потока отказывают всем, кроме buyer
    def denies_non_buyer(flow_alias):
        ok = False
        for owner, e in walk_flow(flows, flow_alias):
            if e.get('authenticator') == 'deny-access-authenticator':
                cond = [x for x in flows[owner]['authenticationExecutions'] if x.get('authenticator') == 'conditional-user-role']
                cfg = configs.get(cond[0].get('authenticatorConfig'), {}).get('config', {}) if cond else {}
                parent = next((x for fl in flows.values() for x in fl['authenticationExecutions'] if x.get('flowAlias') == owner), {})
                ok = cfg.get('condUserRole') == 'buyer' and cfg.get('negate') == 'true' and parent.get('requirement') == 'CONDITIONAL'
        return ok
    vk = by_alias(realm.get('identityProviders', []))
    idp = vk.get('vkid')
    if not idp:
        rep.err(w, 'нет внешнего провайдера vkid (FT-1.2)')
    else:
        post = idp.get('postBrokerLoginFlowAlias')
        if post not in flows or not denies_non_buyer(post):
            rep.err(w, 'после входа через VK ID должен идти поток, закрывающий вход всем, кроме buyer (иначе второй фактор обходится)')
        c = idp.get('config', {})
        if c.get('validateSignature') != 'true' or c.get('pkceEnabled') != 'true' or c.get('useJwksUrl') != 'true':
            rep.err(w, 'vkid: подпись токена провайдера и PKCE должны проверяться')
        if c.get('clientSecret') != '${DGM_VKID_CLIENT_SECRET}':
            rep.err(w, 'vkid: секрет клиента должен быть подстановкой ${DGM_VKID_CLIENT_SECRET}')
        if not str(c.get('issuer', '')).startswith('https://') or not str(c.get('tokenUrl', '')).startswith('https://'):
            rep.err(w, 'vkid: адреса провайдера должны быть https')
    if sms_flow and not denies_non_buyer(sms_flow['alias']):
        rep.err(w, 'поток входа по SMS должен закрывать вход всем, кроме buyer')
    if sms_flow and not any(e.get('authenticator') == 'sms-otp' and e['requirement'] == 'REQUIRED' for e in sms_flow['authenticationExecutions']):
        rep.err(w, 'поток входа по SMS без обязательного шага sms-otp')

    # --- утверждения токена
    api = cscopes.get('dgm-api', {})
    mappers = {m['protocolMapper']: m for m in api.get('protocolMappers', [])}
    aud = mappers.get('oidc-audience-mapper', {}).get('config', {})
    if aud.get('included.custom.audience') != rules['aud'][0] or aud.get('access.token.claim') != 'true':
        rep.err(w, 'область dgm-api: audience должен быть %s в access-токене' % rules['aud'][0])
    pv = [m for m in api.get('protocolMappers', []) if m['config'].get('claim.name') == 'phone_verified']
    if not pv or pv[0]['config'].get('user.attribute') != 'phone_verified' or pv[0]['config'].get('jsonType.label') != 'boolean':
        rep.err(w, 'область dgm-api: нет утверждения phone_verified (boolean из атрибута пользователя)')
    amr = mappers.get('oidc-amr-mapper', {}).get('config', {})
    if amr.get('access.token.claim') != 'true':
        rep.err(w, 'область dgm-api: нет утверждения amr в access-токене')
    refs = {}
    for c in realm['authenticatorConfig']:
        v = c['config'].get('default.reference.value')
        if v is not None:
            refs[c['alias']] = v
            age = c['config'].get('default.reference.maxAge', '0')
            if not age.isdigit() or int(age) < 3600:
                rep.err(w, 'настройка %s: у amr нет default.reference.maxAge (по умолчанию 0 и значение пропадает через секунду)' % c['alias'])
    if set(refs.values()) != rules['amr']:
        rep.err(w, 'значения amr в потоках %s, в документе %s' % (sorted(set(refs.values())), sorted(rules['amr'])))
    for c in realm['clients']:
        if c['clientId'] in want and 'dgm-api' not in c['defaultClientScopes']:
            rep.err(w, 'клиент %s без области dgm-api: токен без audience и amr' % c['clientId'])
    em = cscopes.get('email', {}).get('protocolMappers', [])
    if any(m['config'].get('access.token.claim') == 'true' for m in em):
        rep.err(w, 'e-mail попадает в access-токен: персональные данные нужны только интерфейсу (id-токен)')
    up = json.loads(realm['components']['org.keycloak.userprofile.UserProfileProvider'][0]['config']['kc.user.profile.config'][0])
    pvattr = next((a for a in up['attributes'] if a['name'] == 'phone_verified'), None)
    if not pvattr or pvattr['permissions'] != {'view': ['admin'], 'edit': ['admin']}:
        rep.err(w, 'phone_verified в профиле пользователя может менять только администратор (платформа через Admin API), не сам пользователь')
    if up.get('unmanagedAttributePolicy'):
        rep.err(w, 'неописанные атрибуты пользователя разрешены (unmanagedAttributePolicy): пользователь мог бы выдать себе phone_verified')

    # --- почта
    smtp = realm.get('smtpServer') or {}
    if smtp.get('host') != 'external-stubs' or smtp.get('port') != '1025':
        rep.err(w, 'исходящая почта: SMTP должен смотреть на заглушку external-stubs:1025 (c4-deployment.md, раздел 2.2)')

    # --- секреты и люди в файле
    raw = open(REALM_FILE, encoding='utf-8').read()
    def scan(node, path=''):
        if isinstance(node, dict):
            for k, v in node.items():
                p = '%s.%s' % (path, k)
                if k == 'kc.user.profile.config':
                    continue
                if SECRET_KEYS.search(k) and k not in NOT_SECRET_KEYS and isinstance(v, str) and v and not re.fullmatch(r'\$\{[A-Z_]+\}', v) and 'Message' not in k:
                    rep.err(w, '%s: значение выглядит как секрет, нужна подстановка ${ИМЯ}' % p)
                if k in ('credentials', 'privateKey', 'certificate'):
                    rep.err(w, '%s: учётные данные и ключи в файле realm не хранятся' % p)
                scan(v, p)
        elif isinstance(node, list):
            for i, v in enumerate(node):
                scan(v, '%s[%d]' % (path, i))
    scan(realm)
    people = [u['username'] for u in realm.get('users', []) if not u['username'].startswith('service-account-')]
    if people:
        rep.err(w, 'в файле realm есть учётные записи людей %s: тестовые пользователи создаются скриптом из случайных паролей' % people)
    for ph in set(re.findall(r'\$\{([A-Z][A-Z_]+)(?::[^}]*)?\}', raw)):
        if ph not in ('DGM_PUBLIC_ORIGIN', 'DGM_PLATFORM_CLIENT_SECRET', 'DGM_VKID_CLIENT_SECRET'):
            rep.err(w, 'подстановка ${%s}: переменную не задаёт entrypoint.sh' % ph)

    # --- расширение SMS и генератор
    factory = os.path.join(SPI, 'src', 'dgm', 'keycloak', 'smsotp', 'SmsOtpAuthenticatorFactory.java')
    services = os.path.join(SPI, 'resources', 'META-INF', 'services', 'org.keycloak.authentication.AuthenticatorFactory')
    if not os.path.exists(factory) or not os.path.exists(services):
        rep.err('infra/keycloak/sms-otp', 'нет исходников расширения или файла регистрации провайдера')
    else:
        m = re.search(r'String ID = "([^"]+)"', L.read(factory))
        if not m or m.group(1) != 'sms-otp':
            rep.err('SmsOtpAuthenticatorFactory.java', 'идентификатор провайдера %s, поток sms-login и ADR-010 используют sms-otp' % (m.group(1) if m else None))
        if L.read(services).strip() != 'dgm.keycloak.smsotp.SmsOtpAuthenticatorFactory':
            rep.err('META-INF/services', 'файл регистрации ссылается не на SmsOtpAuthenticatorFactory')
    if 'DGM_REALM_FILE' not in os.environ:
        sys.path.insert(0, os.path.join(L.REPO, 'infra', 'keycloak'))
        import gen_realm
        if gen_realm.render() != raw:
            rep.err(w, 'файл не равен результату gen_realm.py: правьте генератор и выполните python3 infra/keycloak/gen_realm.py')

    rep.fact('realm dgm: %d ролей, %d областей, %d клиентов, %d потоков входа, второй фактор у %s, amr %s, сроки по документу'
             % (len(role_names), len(rules['scopes']), len(realm['clients']), len(flows), ', '.join(sorted(got_roles)), sorted(set(refs.values()))))
    return rep.finish()


if __name__ == '__main__':
    sys.exit(main())
