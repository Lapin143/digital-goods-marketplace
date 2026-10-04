#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Генератор realm Keycloak `dgm` (realm как код).

    python3 infra/keycloak/gen_realm.py            записать infra/keycloak/realm/dgm-realm.json
    python3 infra/keycloak/gen_realm.py --check    проверить, что файл в репозитории равен результату генерации (код выхода 1, если нет)

Зачем генератор: поток входа с условным вторым фактором повторяется для четырёх ролей, а идентификаторы потоков должны быть
постоянными (клиент `dgm-sms` ссылается на поток по идентификатору). Руками такой файл правится с ошибками. Правила realm
(роли, области, сроки жизни, второй фактор) описаны ниже данными, результат смотрится в Git как обычный JSON.
Сверка с docs/05-architecture/roles-permissions.md независима от генератора: tools/docs-checks/check_realm.py читает готовый JSON и документ.

Что подставляет Keycloak при импорте (переменные окружения контейнера, см. entrypoint.sh):
  DGM_PUBLIC_ORIGIN           внешний адрес входа, по умолчанию https://localhost:8443
  DGM_PLATFORM_CLIENT_SECRET  секрет клиента platform-service (файл секрета keycloak_client_platform)
  DGM_VKID_CLIENT_SECRET      секрет клиента VK ID (файл секрета keycloak_vkid_client)
Секретов в файле нет, только эти подстановки (проверяет check_realm.py).
"""
import json
import os
import sys
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, 'realm', 'dgm-realm.json')

REALM = 'dgm'
NS = uuid.UUID('6f1c0a52-0d0c-4d3e-9a55-2f1d6f0b6d11')   # постоянное пространство имён: идентификаторы потоков не меняются между запусками
PUBLIC = '${DGM_PUBLIC_ORIGIN:https://localhost:8443}'
STUBS = 'https://external-stubs:8443'                       # адрес заглушек внутри сети (обратный канал: токен, ключи, профиль)
AUDIENCE = 'dgm-api'

# --- роли (roles-permissions.md, раздел 2)
ROLES = [
    ('buyer', 'Покупатель'),
    ('seller', 'Продавец, второй фактор обязателен'),
    ('moderator', 'Модератор, второй фактор обязателен'),
    ('support-operator', 'Оператор поддержки, второй фактор обязателен'),
    ('admin', 'Администратор, второй фактор обязателен'),
]
OTP_ROLES = ['seller', 'moderator', 'support-operator', 'admin']

# --- области токена и роль, которой они выдаются (roles-permissions.md, раздел 3). Область выдаётся только тому, у кого есть роль:
# Keycloak проверяет роли, привязанные к области клиента (scopeMappings), поэтому покупатель не получит staff.admin ни у какого клиента.
SCOPES = [
    ('orders.create', 'buyer', 'Оформление заказа и платёжная сессия'),
    ('orders.read', 'buyer', 'История заказов и статусы выдачи'),
    ('support.write', 'buyer', 'Создание обращения, ввод кодов смены e-mail'),
    ('account.manage', 'buyer', 'Кабинет учётной записи'),
    ('seller.apply', 'buyer', 'Заявка на статус продавца'),
    ('seller.catalog', 'seller', 'Товары, пулы ключей и остатки продавца'),
    ('staff.moderation', 'moderator', 'Очереди заявок и товаров, решения модератора'),
    ('staff.support', 'support-operator', 'Очередь обращений, повторная отправка, смена e-mail'),
    ('staff.admin', 'admin', 'Роли, учётные записи, параметры, ручные возвраты'),
    ('staff.audit', 'admin', 'Чтение журнала аудита'),
]
BUYER_SCOPES = ['orders.create', 'orders.read', 'support.write', 'account.manage', 'seller.apply']
STAFF_SCOPES = ['seller.catalog', 'staff.moderation', 'staff.support', 'staff.admin', 'staff.audit']
SMS_SCOPES = ['orders.read', 'support.write']

# --- сроки жизни (roles-permissions.md, раздел 2.1), секунды
ACCESS_TTL = 300
SESSIONS = {
    'buyer': {'idle': 30 * 60, 'max': 10 * 3600},
    'staff': {'idle': 15 * 60, 'max': 8 * 3600},
    'sms': {'access': 15 * 60, 'max': 3600},   # отдельного срока простоя нет: действует простой SSO-сессии realm (клиент не может его увеличить)
}


def uid(kind, name):
    return str(uuid.uuid5(NS, '%s/%s' % (kind, name)))


# --------------------------------------------------------------------------------------------------- потоки входа
CONFIGS = []   # authenticatorConfig, наполняется при построении потоков


def cfg(alias, config):
    CONFIGS.append({'id': uid('config', alias), 'alias': alias, 'config': config})
    return alias


def ref(alias, value, max_age):
    """Ссылка на способ входа для утверждения amr: Keycloak кладёт значение в amr, когда этот шаг потока пройден.
    max_age обязателен: значение остаётся в amr, пока с входа прошло не больше max_age секунд (при 0, это значение по умолчанию,
    только в ту же секунду, и первый же вход после запуска получает пустой amr). Берётся максимальная длина сессии, где способ применяется."""
    return cfg(alias, {'default.reference.value': value, 'default.reference.maxAge': str(max_age)})


def step(authenticator, req='REQUIRED', prio=10, config=None):
    d = {'authenticator': authenticator, 'authenticatorFlow': False, 'autheticatorFlow': False,
         'requirement': req, 'priority': prio, 'userSetupAllowed': False}
    if config:
        d['authenticatorConfig'] = config
    return d


def sub(alias, req='REQUIRED', prio=10):
    return {'authenticatorFlow': True, 'autheticatorFlow': True, 'flowAlias': alias,
            'requirement': req, 'priority': prio, 'userSetupAllowed': False}


def flow(alias, description, executions, top=False, provider='basic-flow'):
    return {'id': uid('flow', alias), 'alias': alias, 'description': description, 'providerId': provider,
            'topLevel': top, 'builtIn': False, 'authenticationExecutions': executions}


def deny_unless_buyer(alias, message):
    """Подпоток: если у пользователя нет роли buyer, доступ закрыт. Закрыто по умолчанию: новая роль без явного разрешения не пройдёт."""
    return [
        flow(alias, 'Отказ всем, кроме покупателей', [
            step('conditional-user-role', prio=10, config=cfg(alias + '-cond', {'condUserRole': 'buyer', 'negate': 'true'})),
            step('deny-access-authenticator', prio=20, config=cfg(alias + '-deny', {'denyErrorMessage': message})),
        ]),
    ]


def build_flows():
    flows = []
    # 1. Вход в браузере: пароль, затем второй фактор (TOTP) для каждой из четырёх ролей. Если TOTP у пользователя ещё не настроен,
    # Keycloak не выпускает токен и требует настроить его (обязательное действие CONFIGURE_TOTP): без второго фактора кабинет не открывается.
    forms = [step('auth-username-password-form', prio=10, config=ref('ref-pwd', 'pwd', SESSIONS['buyer']['max']))]
    for i, role in enumerate(OTP_ROLES):
        forms.append(sub('dgm-2fa-' + role, 'CONDITIONAL', 20 + 10 * i))
    flows.append(flow('dgm-browser', 'Вход: SSO-cookie, внешний провайдер или пароль плюс второй фактор по роли', [
        step('auth-cookie', 'ALTERNATIVE', 10),
        step('identity-provider-redirector', 'ALTERNATIVE', 25),
        sub('dgm-browser-forms', 'ALTERNATIVE', 30),
    ], top=True))
    flows.append(flow('dgm-browser-forms', 'Пароль и условный второй фактор', forms))
    for role in OTP_ROLES:
        flows.append(flow('dgm-2fa-' + role, 'Второй фактор обязателен для роли ' + role, [
            step('conditional-user-role', prio=10, config=cfg('cond-role-' + role, {'condUserRole': role, 'negate': 'false'})),
            step('auth-otp-form', prio=20, config=ref('ref-otp-' + role, 'otp', SESSIONS['staff']['max'])),
        ]))

    # 2. Регистрация покупателя по e-mail и паролю (FT-1.1). Капча и условия отключены: их нет в R1.
    flows.append(flow('registration', 'Регистрация', [sub('registration form', 'REQUIRED', 10)], top=True))
    flows.append(flow('registration form', 'Форма регистрации', [
        step('registration-user-creation', prio=20),
        step('registration-password-action', prio=50),
        step('registration-recaptcha-action', 'DISABLED', 60),
        step('registration-terms-and-conditions', 'DISABLED', 70),
    ], provider='form-flow'))

    # 3. Прямая выдача токена по паролю в клиентах выключена, поток нужен только потому, что realm обязан его иметь
    flows.append(flow('direct grant', 'Выдача токена по паролю (клиентам не включена)', [
        step('direct-grant-validate-username', prio=10),
        step('direct-grant-validate-password', prio=20),
        sub('Direct Grant - Conditional OTP', 'CONDITIONAL', 30),
    ], top=True))
    flows.append(flow('Direct Grant - Conditional OTP', 'OTP, если настроен', [
        step('conditional-user-configured', prio=10),
        step('direct-grant-validate-otp', prio=20),
    ]))

    # 4. Восстановление пароля по e-mail (FT-1.4); при настроенном втором факторе его код тоже спрашивают
    flows.append(flow('reset credentials', 'Сброс пароля', [
        step('reset-credentials-choose-user', prio=10),
        step('reset-credential-email', prio=20),
        step('reset-password', prio=30),
        sub('Reset - Conditional OTP', 'CONDITIONAL', 40),
    ], top=True))
    flows.append(flow('Reset - Conditional OTP', 'OTP при сбросе, если настроен', [
        step('conditional-user-configured', prio=10),
        step('reset-otp', prio=20),
    ]))

    # 5. Проверка клиентов и Docker (обязательные потоки realm)
    flows.append(flow('clients', 'Проверка подлинности клиентов', [
        step('client-secret', 'ALTERNATIVE', 10),
        step('client-jwt', 'ALTERNATIVE', 20),
        step('client-secret-jwt', 'ALTERNATIVE', 30),
        step('client-x509', 'ALTERNATIVE', 40),
    ], top=True, provider='client-flow'))
    flows.append(flow('docker auth', 'Docker (не используется)', [step('docker-http-basic-authenticator', prio=10)], top=True))

    # 6. Первый вход через VK ID: новая учётная запись или привязка к существующей с подтверждением по e-mail
    flows.append(flow('dgm-first-broker-login', 'Первый вход через внешнего провайдера', [
        step('idp-create-user-if-unique', 'ALTERNATIVE', 10,
             config=cfg('create-unique-user', {'require.password.update.after.registration': 'false'})),
        sub('dgm-handle-existing-account', 'ALTERNATIVE', 20),
    ], top=True))
    flows.append(flow('dgm-handle-existing-account', 'Учётная запись с таким e-mail уже есть', [
        step('idp-confirm-link', prio=10),
        step('idp-email-verification', prio=20),
    ]))

    # 7. После каждого входа через VK ID: сотрудникам и продавцам вход через VK закрыт (в обход второго фактора), остальным в amr записывается vk
    flows.extend(deny_unless_buyer('dgm-vk-deny-nonbuyer', 'Вход через VK ID доступен только покупателям'))
    flows.append(flow('dgm-vk-post-login', 'После входа через VK ID', [
        sub('dgm-vk-deny-nonbuyer', 'CONDITIONAL', 10),
        step('allow-access-authenticator', prio=20, config=ref('ref-vk', 'vk', SESSIONS['buyer']['max'])),
    ], top=True))

    # 8. Вход по коду из SMS (ADR-010, FT-1.7): клиент dgm-sms. Номер и код принимает расширение sms-otp, сотрудникам и продавцам вход закрыт
    flows.extend(deny_unless_buyer('dgm-sms-deny-nonbuyer', 'Вход по коду из SMS доступен только покупателям'))
    flows.append(flow('sms-login', 'Вход по коду из SMS', [
        step('sms-otp', prio=10, config=ref('ref-sms', 'sms', SESSIONS['sms']['max'])),
        sub('dgm-sms-deny-nonbuyer', 'CONDITIONAL', 20),
    ], top=True))
    return flows


# --------------------------------------------------------------------------------------------------- области клиентов
def mapper(name, kind, config):
    return {'name': name, 'protocol': 'openid-connect', 'protocolMapper': kind, 'consentRequired': False, 'config': config}


def scope(name, description, mappers=None, in_token=False):
    return {'name': name, 'description': description, 'protocol': 'openid-connect',
            'attributes': {'include.in.token.scope': 'true' if in_token else 'false', 'display.on.consent.screen': 'false'},
            'protocolMappers': mappers or []}


def build_scopes():
    access = {'access.token.claim': 'true', 'introspection.token.claim': 'true', 'id.token.claim': 'false',
              'userinfo.token.claim': 'false', 'lightweight.claim': 'false'}
    out = [
        scope('basic', 'Идентификатор пользователя sub и время входа', [
            mapper('sub', 'oidc-sub-mapper', {'access.token.claim': 'true', 'introspection.token.claim': 'true'}),
            mapper('auth_time', 'oidc-usersessionmodel-note-mapper', {
                'user.session.note': 'AUTH_TIME', 'claim.name': 'auth_time', 'jsonType.label': 'long',
                'access.token.claim': 'true', 'id.token.claim': 'true', 'introspection.token.claim': 'true'}),
        ]),
        scope('roles', 'Роль пользователя в realm_access.roles (одна роль из roles-permissions.md, раздел 2)', [
            mapper('realm roles', 'oidc-usermodel-realm-role-mapper', dict(access, **{
                'claim.name': 'realm_access.roles', 'jsonType.label': 'String', 'multivalued': 'true'})),
        ]),
        scope('email', 'E-mail для интерфейса. В access-токен не попадает: персональные данные не нужны шлюзу и сервисам', [
            mapper('email', 'oidc-usermodel-attribute-mapper', {
                'user.attribute': 'email', 'claim.name': 'email', 'jsonType.label': 'String',
                'id.token.claim': 'true', 'access.token.claim': 'false', 'userinfo.token.claim': 'true'}),
            mapper('email verified', 'oidc-usermodel-property-mapper', {
                'user.attribute': 'emailVerified', 'claim.name': 'email_verified', 'jsonType.label': 'boolean',
                'id.token.claim': 'true', 'access.token.claim': 'false', 'userinfo.token.claim': 'true'}),
        ]),
        scope(AUDIENCE, 'Адресат dgm-api, признак подтверждённого телефона и способы входа amr', [
            mapper('audience ' + AUDIENCE, 'oidc-audience-mapper', dict(access, **{'included.custom.audience': AUDIENCE})),
            mapper('phone_verified', 'oidc-usermodel-attribute-mapper', dict(access, **{
                'user.attribute': 'phone_verified', 'claim.name': 'phone_verified', 'jsonType.label': 'boolean'})),
            mapper('amr', 'oidc-amr-mapper', dict(access)),
        ]),
    ]
    for name, _role, description in SCOPES:
        out.append(scope(name, description, in_token=True))
    return out


# --------------------------------------------------------------------------------------------------- клиенты
def client(client_id, name, description, scopes, attrs, flow_overrides=None):
    redirect = [PUBLIC + '/*']
    c = {
        'clientId': client_id, 'name': name, 'description': description, 'enabled': True, 'protocol': 'openid-connect',
        'publicClient': True, 'bearerOnly': False, 'standardFlowEnabled': True, 'implicitFlowEnabled': False,
        'directAccessGrantsEnabled': False, 'serviceAccountsEnabled': False, 'fullScopeAllowed': False,
        'consentRequired': False, 'frontchannelLogout': False,
        'redirectUris': redirect, 'webOrigins': [PUBLIC], 'rootUrl': PUBLIC,
        'attributes': dict({'pkce.code.challenge.method': 'S256', 'post.logout.redirect.uris': '+',
                            'oauth2.device.authorization.grant.enabled': 'false', 'oidc.ciba.grant.enabled': 'false'}, **attrs),
        'defaultClientScopes': ['basic', 'roles'] + scopes,
        'optionalClientScopes': [],
    }
    if flow_overrides:
        c['authenticationFlowBindingOverrides'] = flow_overrides
    return c


def build_clients():
    buyer = SESSIONS['buyer']
    staff = SESSIONS['staff']
    sms = SESSIONS['sms']
    return [
        client('dgm-web', 'Веб-интерфейс: покупатель', 'Одностраничное приложение, кабинет покупателя (код авторизации с PKCE)',
               ['email', AUDIENCE] + BUYER_SCOPES,
               {'access.token.lifespan': str(ACCESS_TTL), 'client.session.idle.timeout': str(buyer['idle']),
                'client.session.max.lifespan': str(buyer['max']), 'use.refresh.tokens': 'true'}),
        client('dgm-staff', 'Веб-интерфейс: продавец и сотрудники', 'Кабинеты продавца, модератора, поддержки и администратора (код с PKCE, второй фактор по роли)',
               ['email', AUDIENCE] + STAFF_SCOPES,
               {'access.token.lifespan': str(ACCESS_TTL), 'client.session.idle.timeout': str(staff['idle']),
                'client.session.max.lifespan': str(staff['max']), 'use.refresh.tokens': 'true'}),
        client('dgm-sms', 'Веб-интерфейс: вход по коду из SMS', 'Ограниченная сессия покупателя: 15 минут, без refresh-токена (roles-permissions.md, раздел 5)',
               [AUDIENCE] + SMS_SCOPES,
               {'access.token.lifespan': str(sms['access']), 'client.session.max.lifespan': str(sms['max']), 'use.refresh.tokens': 'false'},
               flow_overrides={'browser': uid('flow', 'sms-login')}),
        {
            'clientId': 'platform-service', 'name': 'platform-service: Admin API', 'enabled': True, 'protocol': 'openid-connect',
            'description': 'Служебный клиент: платформа назначает роли и признак подтверждения телефона (ADR-010, INV-36)',
            'publicClient': False, 'bearerOnly': False, 'clientAuthenticatorType': 'client-secret', 'secret': '${DGM_PLATFORM_CLIENT_SECRET}',
            'standardFlowEnabled': False, 'implicitFlowEnabled': False, 'directAccessGrantsEnabled': False,
            'serviceAccountsEnabled': True, 'fullScopeAllowed': True, 'consentRequired': False,
            'attributes': {'access.token.lifespan': str(ACCESS_TTL)},
            'defaultClientScopes': ['basic'], 'optionalClientScopes': [],
        },
    ]


# --------------------------------------------------------------------------------------------------- realm
USER_PROFILE = {
    'attributes': [
        {'name': 'username', 'displayName': '${username}',
         'validations': {'length': {'min': 3, 'max': 255}, 'username-prohibited-characters': {}, 'up-username-not-idn-homograph': {}},
         'permissions': {'view': ['admin', 'user'], 'edit': ['admin', 'user']}, 'multivalued': False},
        {'name': 'email', 'displayName': '${email}', 'validations': {'email': {}, 'length': {'max': 255}},
         'required': {'roles': ['user']}, 'permissions': {'view': ['admin', 'user'], 'edit': ['admin', 'user']}, 'multivalued': False},
        # Признак выставляет только платформа через Admin API (INV-35), сам пользователь его не видит и не меняет
        {'name': 'phone_verified', 'displayName': 'Телефон подтверждён', 'validations': {'options': {'options': ['true', 'false']}},
         'permissions': {'view': ['admin'], 'edit': ['admin']}, 'multivalued': False},
    ],
    'groups': [],
}


def build_realm():
    CONFIGS.clear()
    flows = build_flows()
    default_role = 'default-roles-' + REALM
    realm = {
        'id': REALM, 'realm': REALM, 'displayName': 'Маркетплейс цифровых товаров', 'enabled': True,
        'sslRequired': 'all', 'loginTheme': None,
        'registrationAllowed': True, 'registrationEmailAsUsername': True, 'loginWithEmailAllowed': True,
        'duplicateEmailsAllowed': False, 'rememberMe': False, 'verifyEmail': True, 'resetPasswordAllowed': True,
        # защита от перебора: 5 неудач, вход закрыт на 15 минут (roles-permissions.md, раздел 2.1)
        'bruteForceProtected': True, 'permanentLockout': False, 'failureFactor': 5,
        'waitIncrementSeconds': 900, 'maxFailureWaitSeconds': 900, 'minimumQuickLoginWaitSeconds': 60,
        'maxDeltaTimeSeconds': 43200, 'quickLoginCheckMilliSeconds': 1000,
        # сроки жизни: покупатель по умолчанию, продавец и сотрудники и SMS сокращены у клиентов (раздел build_clients)
        'accessTokenLifespan': ACCESS_TTL, 'accessTokenLifespanForImplicitFlow': 900,
        'ssoSessionIdleTimeout': SESSIONS['buyer']['idle'], 'ssoSessionMaxLifespan': SESSIONS['buyer']['max'],
        'ssoSessionIdleTimeoutRememberMe': 0, 'ssoSessionMaxLifespanRememberMe': 0,
        'offlineSessionIdleTimeout': 2592000, 'offlineSessionMaxLifespanEnabled': False,
        'accessCodeLifespan': 60, 'accessCodeLifespanUserAction': 300, 'accessCodeLifespanLogin': 1800,
        'actionTokenGeneratedByAdminLifespan': 43200, 'actionTokenGeneratedByUserLifespan': 300,
        # refresh-токен одноразовый: повторное использование отзывает сессию (ротация с обнаружением повторного использования)
        'revokeRefreshToken': True, 'refreshTokenMaxReuse': 0,
        'defaultSignatureAlgorithm': 'RS256',
        'passwordPolicy': 'hashAlgorithm(argon2) and length(12) and notUsername and notEmail',
        'otpPolicyType': 'totp', 'otpPolicyAlgorithm': 'HmacSHA1', 'otpPolicyInitialCounter': 0, 'otpPolicyDigits': 6,
        'otpPolicyLookAheadWindow': 1, 'otpPolicyPeriod': 30, 'otpPolicyCodeReusable': False,
        'otpSupportedApplications': ['totpAppFreeOTPName', 'totpAppGoogleName', 'totpAppMicrosoftAuthenticatorName'],
        'requiredCredentials': ['password'],
        'internationalizationEnabled': True, 'supportedLocales': ['ru', 'en'], 'defaultLocale': 'ru',
        'eventsEnabled': True, 'eventsExpiration': 604800, 'eventsListeners': ['jboss-logging'],
        'adminEventsEnabled': True, 'adminEventsDetailsEnabled': False,
        'browserSecurityHeaders': {
            'contentSecurityPolicyReportOnly': '', 'xContentTypeOptions': 'nosniff', 'referrerPolicy': 'no-referrer',
            'xRobotsTag': 'none', 'xFrameOptions': 'DENY', 'contentSecurityPolicy': "frame-src 'self'; frame-ancestors 'none'; object-src 'none';",
            'strictTransportSecurity': 'max-age=31536000; includeSubDomains'},
        'smtpServer': {'host': 'external-stubs', 'port': '1025', 'from': 'no-reply@dgm.local',
                       'fromDisplayName': 'Маркетплейс цифровых товаров', 'ssl': 'false', 'starttls': 'false', 'auth': 'false'},
        # поток входа и остальные обязательные потоки
        'browserFlow': 'dgm-browser', 'registrationFlow': 'registration', 'directGrantFlow': 'direct grant',
        'resetCredentialsFlow': 'reset credentials', 'clientAuthenticationFlow': 'clients', 'dockerAuthenticationFlow': 'docker auth',
        'firstBrokerLoginFlow': 'dgm-first-broker-login',
        'authenticationFlows': flows, 'authenticatorConfig': list(CONFIGS),
        'roles': {'realm': [
            {'name': n, 'description': d, 'composite': False, 'clientRole': False} for n, d in ROLES
        ] + [
            # Роль по умолчанию: новый пользователь становится покупателем. Продавца и сотрудников назначает платформа или администратор,
            # снимая эту роль (одна роль на пользователя, roles-permissions.md, принцип 2)
            {'name': default_role, 'description': 'Роль по умолчанию: покупатель', 'composite': True,
             'composites': {'realm': ['buyer']}, 'clientRole': False},
        ]},
        'defaultRole': {'name': default_role, 'description': 'Роль по умолчанию: покупатель', 'composite': True,
                        'clientRole': False, 'containerId': REALM},
        'scopeMappings': [{'clientScope': n, 'roles': [role]} for n, role, _d in SCOPES],
        'clientScopes': build_scopes(),
        'defaultDefaultClientScopes': [],
        'defaultOptionalClientScopes': [],
        'clients': build_clients(),
        'identityProviders': [{
            'alias': 'vkid', 'displayName': 'VK ID', 'providerId': 'oidc', 'enabled': True, 'trustEmail': True, 'storeToken': False,
            'addReadTokenRoleOnCreate': False, 'authenticateByDefault': False, 'linkOnly': False, 'hideOnLogin': False,
            'firstBrokerLoginFlowAlias': 'dgm-first-broker-login', 'postBrokerLoginFlowAlias': 'dgm-vk-post-login',
            'config': {
                # окно браузера идёт на публичный адрес (шлюз передаёт /vkid на заглушку), сервер Keycloak ходит на заглушку напрямую
                'authorizationUrl': PUBLIC + '/vkid/authorize', 'tokenUrl': STUBS + '/vkid/token',
                'userInfoUrl': STUBS + '/vkid/userinfo', 'jwksUrl': STUBS + '/vkid/jwks', 'issuer': STUBS + '/vkid',
                'useJwksUrl': 'true', 'validateSignature': 'true', 'pkceEnabled': 'true', 'pkceMethod': 'S256',
                'clientId': 'dgm-keycloak', 'clientSecret': '${DGM_VKID_CLIENT_SECRET}', 'clientAuthMethod': 'client_secret_post',
                'defaultScope': 'openid email', 'syncMode': 'IMPORT', 'backchannelSupported': 'false', 'disableUserInfo': 'false'},
        }],
        'users': [{
            'username': 'service-account-platform-service', 'enabled': True, 'serviceAccountClientId': 'platform-service',
            'realmRoles': [], 'clientRoles': {'realm-management': ['manage-users', 'view-users', 'query-users']}}],
        'components': {
            'org.keycloak.userprofile.UserProfileProvider': [{
                'providerId': 'declarative-user-profile', 'subComponents': {},
                'config': {'kc.user.profile.config': [json.dumps(USER_PROFILE, ensure_ascii=False)]}}],
            'org.keycloak.keys.KeyProvider': [
                {'name': 'rsa-generated', 'providerId': 'rsa-generated', 'subComponents': {}, 'config': {'priority': ['100'], 'keySize': ['2048']}},
                {'name': 'hmac-generated-hs512', 'providerId': 'hmac-generated', 'subComponents': {}, 'config': {'priority': ['100'], 'algorithm': ['HS512']}},
                {'name': 'aes-generated', 'providerId': 'aes-generated', 'subComponents': {}, 'config': {'priority': ['100']}},
            ],
        },
    }
    realm = {k: v for k, v in realm.items() if v is not None}
    return realm


def render():
    return json.dumps(build_realm(), ensure_ascii=False, indent=2) + '\n'


def main():
    text = render()
    if '--check' in sys.argv:
        have = open(OUT, encoding='utf-8').read() if os.path.exists(OUT) else ''
        if have != text:
            print('ОШИБКА: %s не равен результату gen_realm.py, выполните: python3 infra/keycloak/gen_realm.py' % OUT)
            return 1
        print('realm совпадает с результатом генерации')
        return 0
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)
    print('записан %s (%d байт)' % (OUT, len(text.encode('utf-8'))))
    return 0


if __name__ == '__main__':
    sys.exit(main())
