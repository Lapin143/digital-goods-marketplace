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
  расширение подключено (провайдеры sms-otp, dgm-cookie, dgm-auth-level, dgm-register, dgm-reset-email), страницы входа по SMS работают,
    без платформы вход по SMS честно отвечает «временно недоступен» (ответы с платформой проверяет sms_login_checks.py);
  второй фактор целиком: вход без TOTP → настройка TOTP на странице → токен с amr из pwd и otp;
  регистрация и сброс пароля нейтральны (US-01, ST-06): страница ответа для свободного и занятого e-mail одна, письма уходят, время ответа
    близко; занятому адресу пароль не меняется, учётная запись не создаётся; ссылка из письма подтверждает адрес и продолжает вход;
  утверждение seller_id попадает в токен продавца и не появляется у покупателя;
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



# --------------------------------------------------------------------------------------------------- общие помощники
def median(values):
    ordered = sorted(values)
    return ordered[len(ordered) // 2]


def mails_to(net, address, at_least=0, wait=1.0):
    """Письма, которые SMTP-заглушка приняла для адреса. at_least > 0: ждёт, пока их станет не меньше (до wait секунд)."""
    found, deadline = [], time.time() + wait
    while True:
        m = net.request('GET', 'https://external-stubs:8444/admin/emails?to=%s' % urllib.parse.quote(address), headers={'Accept': 'application/json'})
        if m.status == 200:
            body = m.json()
            found = body if isinstance(body, list) else body.get('items') or body.get('emails') or []
        if len(found) >= at_least and (at_least > 0 or time.time() >= deadline):
            return found
        if time.time() >= deadline:
            return found
        time.sleep(0.4)


def action_link(mail):
    m = re.search(r'https://localhost:8443/auth/realms/dgm/login-actions/action-token\?\S+', mail.get('text', ''))
    return m.group(0) if m else None


def strong_password(tag):
    return 'Zq9-%s-correct-horse-battery' % tag


def finish_at_callback(kc, response, limit=8):
    """Идёт по переходам Keycloak после ссылки из письма, пока не придёт на redirect_uri клиента. Возвращает последний ответ и адрес."""
    for _ in range(limit):
        loc = response.location
        if response.status in (301, 302, 303, 307) and loc:
            loc = urllib.parse.urljoin(kc.public + '/', loc)
            if loc.startswith(kc.redirect_base + '/callback'):
                return response, loc
            response = kc.net.request('GET', loc)
        else:
            break
    return response, None


def follow_redirects(kc, response, limit=8):
    """Идёт по переходам Keycloak до страницы (ответ без перехода). Страница с кодом на redirect_uri клиента тоже конец пути."""
    for _ in range(limit):
        loc = response.location
        if response.status not in (301, 302, 303, 307) or not loc:
            break
        loc = urllib.parse.urljoin(kc.public + '/', loc)
        if loc.startswith(kc.redirect_base + '/callback'):
            break
        response = kc.net.request('GET', loc)
    return response


# --------------------------------------------------------------------------------------------------- вход по SMS без платформы
def sms_checks_without_platform(kc):
    d = kc.start('dgm-sms')
    expect('вход по SMS: клиент dgm-sms открывает страницу номера', d.kind == 'sms_phone', d.kind)
    d.post(phone='123')
    expect('вход по SMS: неверный номер отклонён на странице с понятным текстом, платформа не вызывалась',
           d.kind == 'sms_phone' and 'российский номер' in d.message, '%s %s' % (d.kind, d.message))
    d.post(phone='+79990001122')
    expect('вход по SMS: платформа недоступна, ответ «временно недоступен», а не ошибка сервера', d.kind == 'sms_phone' and 'временно недоступен' in d.message,
           '%s %s %s' % (d.resp.status, d.kind, d.message))
    r = kc.start('dgm-web')
    r2 = kc.start('dgm-sms', clear_cookies=False)
    expect('вход по SMS: у dgm-sms нет входа по SSO-cookie, номер спрашивают всегда', r.kind == 'login' and r2.kind == 'sms_phone', '%s %s' % (r.kind, r2.kind))


# --------------------------------------------------------------------------------------------------- регистрация и сброс пароля нейтральны
def registration_checks(kc, admin, users):
    net = kc.net
    print('== Регистрация: ответ одинаков для свободного и занятого e-mail (US-1.1, ST-06; TC-101, TC-102)')

    def register(email, password, confirm=None):
        d = kc.start('dgm-web').follow('login-actions/registration')
        t0 = time.time()
        d.post(email=email, password=password, **{'password-confirm': password if confirm is None else confirm})
        return d, time.time() - t0

    # Кнопка «Регистрация» интерфейса (шаг 17 Ф4) отправляет на Keycloak с prompt=create; без него пришлось бы вести через страницу входа
    d_create = kc.start('dgm-web', extra={'prompt': 'create'})
    expect('регистрация: запрос с prompt=create открывает форму регистрации, а не вход (кнопка «Регистрация» интерфейса)', d_create.kind == 'register', d_create.kind)

    stamp = int(time.time())
    free = 'new-%d@example.test' % stamp
    taken = users['buyer-2']['email']
    pw = strong_password('a')
    before_taken = len(mails_to(net, taken))
    d_new, t_new = register(free, pw)
    d_taken, t_taken = register(taken, strong_password('other'))
    expect('регистрация: свободный адрес, страница «проверьте почту»', d_new.resp.status == 200 and 'Проверьте почту' in d_new.page, '%s %s' % (d_new.kind, d_new.message))
    expect('регистрация: занятый адрес получает ту же страницу, ни слова об «адрес уже существует»',
           d_taken.resp.status == 200 and 'Проверьте почту' in d_taken.page and 'уже существует' not in d_taken.page, '%s %s' % (d_taken.kind, d_taken.message))
    same = K.normalize_page(d_new.page) == K.normalize_page(d_taken.page)
    expect('регистрация: страница ответа для свободного и занятого адреса совпадает до символа (кроме кодов сессии)', same,
           'длины %d и %d' % (len(d_new.page), len(d_taken.page)))
    d_upper, _ = register(taken.upper(), strong_password('upper'))
    expect('регистрация: занятый адрес, записанный заглавными буквами, получает ту же страницу', K.normalize_page(d_upper.page) == K.normalize_page(d_new.page), d_upper.message)

    new_mail = mails_to(net, free, at_least=1, wait=10)
    expect('регистрация: свободный адрес получил одно письмо «Подтверждение E-mail» со ссылкой',
           len(new_mail) == 1 and new_mail[0].get('subject') == 'Подтверждение E-mail' and action_link(new_mail[0]), str([m.get('subject') for m in new_mail]))
    owner_mail = mails_to(net, taken, at_least=before_taken + 2, wait=10)
    fresh = owner_mail[before_taken:]
    expect('регистрация: владелец занятого адреса получил письма о двух попытках (обычной и заглавными), без ссылок подтверждения',
           len(fresh) == 2 and all(m.get('subject') == 'Попытка регистрации на ваш e-mail' and not action_link(m) for m in fresh), str([m.get('subject') for m in fresh]))
    expect('регистрация: в письме владельцу есть путь «войти» и нет нового пароля', fresh and 'войдите' in fresh[0].get('text', '').lower()
           and strong_password('other') not in fresh[0].get('text', ''), fresh[0].get('text', '')[:200] if fresh else '')

    created = admin.realm('/users?email=%s&exact=true' % free).json()
    if expect('регистрация: учётная запись для свободного адреса создана', len(created) == 1, str(created)[:200]):
        u = created[0]
        roles = {r['name'] for r in admin.realm('/users/%s/role-mappings/realm/composite' % u['id']).json()}
        expect('регистрация: новая запись включена, адрес не подтверждён, роль buyer, имя пользователя равно адресу',
               u['enabled'] and not u['emailVerified'] and 'buyer' in roles and u['username'] == free, str((u['enabled'], u['emailVerified'], roles, u['username'])))
        creds = admin.realm('/users/%s/credentials' % u['id']).json()
        expect('регистрация: пароль новой записи хранится как argon2', [json.loads(c['credentialData']).get('algorithm') for c in creds if c['type'] == 'password'] == ['argon2'], str(creds)[:200])
    same_owner = admin.realm('/users?email=%s&exact=true' % taken).json()
    expect('регистрация: на занятый адрес запись не создана и не изменена (одна запись, прежний идентификатор)',
           len(same_owner) == 1 and same_owner[0]['id'] == users['buyer-2']['id'], str(same_owner)[:200])
    r = kc.login('dgm-web', taken, users['buyer-2']['password'])
    expect('регистрация: прежний пароль владельца занятого адреса продолжает работать, новый не принят', r.ok, describe(r))
    r = kc.login('dgm-web', taken, strong_password('other'))
    expect('регистрация: пароль из попытки регистрации на занятый адрес не принимается', not r.ok, describe(r))
    admin.clear_attacks(users['buyer-2']['id'])

    # время ответа: по три попытки каждой ветки, сравниваются медианы
    t_free, t_busy = [t_new], [t_taken]
    for i in range(2):
        t_free.append(register('new-%d-%d@example.test' % (stamp, i), strong_password('t%d' % i))[1])
        t_busy.append(register(taken, strong_password('t%d' % i))[1])
    gap = abs(median(t_free) - median(t_busy))
    expect('регистрация: время ответа для свободного и занятого адреса различается меньше чем на 0,4 с (медианы %.2f и %.2f с)' % (median(t_free), median(t_busy)),
           gap < 0.4 and min(t_free + t_busy) >= 1.1, 'свободный %s, занятый %s' % (['%.2f' % t for t in t_free], ['%.2f' % t for t in t_busy]))

    # ошибки формы одинаковы для любого адреса
    weak_free, _ = register('weak-%d@example.test' % stamp, 'short')
    weak_taken, _ = register(taken, 'short')
    expect('регистрация: слабый пароль отклонён политикой одинаково для свободного и занятого адреса',
           weak_free.kind == 'register' and weak_free.message and weak_free.message == weak_taken.message, '%r и %r' % (weak_free.message, weak_taken.message))
    mismatch, _ = register(free + '.x', pw, confirm=pw + 'x')
    bad_email, _ = register('not-an-email', pw)
    expect('регистрация: разные пароли и неверный e-mail остаются на форме с сообщением', mismatch.kind == 'register' and mismatch.message and bad_email.kind == 'register' and bad_email.message,
           '%r, %r' % (mismatch.message, bad_email.message))
    expect('регистрация: после ошибки поле e-mail заполнено, пароли пустые', 'value="not-an-email"' in bad_email.page and 'value="%s"' % pw not in bad_email.page)

    # ссылка из письма: подтверждает адрес и продолжает вход в том же браузере (PKCE сохраняется)
    d_link, _ = register('link-%d@example.test' % stamp, strong_password('link'))
    mail = mails_to(net, 'link-%d@example.test' % stamp, at_least=1, wait=10)
    link = action_link(mail[0]) if mail else None
    if expect('регистрация: ссылка подтверждения есть в письме', link, str(mail)[:200]):
        resp, loc = finish_at_callback(kc, net.request('GET', link))
        expect('регистрация: ссылка из письма в том же браузере подтверждает адрес и возвращает покупателя в приложение с кодом', loc is not None and 'code=' in loc,
               '%s %s' % (resp.status, resp.location))
        verified = admin.realm('/users?email=link-%d@example.test&exact=true' % stamp).json()[0]
        expect('регистрация: после ссылки адрес подтверждён', verified['emailVerified'] is True, str(verified.get('emailVerified')))
        r = kc.login('dgm-web', verified['email'], strong_password('link'))
        a = r.access() if r.ok else {}
        expect('регистрация: вход по e-mail и паролю после регистрации: роль buyer, области покупателя, amr = pwd',
               r.ok and (a.get('realm_access') or {}).get('roles') == ['buyer'] and scopes_of(a) == BUYER and a.get('amr') == ['pwd'], describe(r))
    return stamp


def reset_checks(kc, admin, users, stamp):
    net = kc.net
    print('== Сброс пароля: ответ одинаков для известного и неизвестного адреса (US-1.4, ST-06; TC-106, TC-107)')

    def request(username):
        d = kc.start('dgm-web').follow('login-actions/reset-credentials')
        t0 = time.time()
        d.post(username=username)
        return d, time.time() - t0

    known = users['buyer-3']['email']
    unknown = 'nobody-%d@example.test' % stamp
    before = len(mails_to(kc.net, known))
    d_known, t_known = request(known)
    d_unknown, t_unknown = request(unknown)
    expect('сброс пароля: известный и неизвестный адрес получают одну и ту же страницу', d_known.message and K.normalize_page(d_known.page) == K.normalize_page(d_unknown.page),
           '%r и %r' % (d_known.message, d_unknown.message))
    got = mails_to(net, known, at_least=before + 1, wait=10)
    expect('сброс пароля: известный адрес получил письмо со ссылкой', len(got) == before + 1 and action_link(got[-1]), str([m.get('subject') for m in got]))
    expect('сброс пароля: неизвестному адресу письмо не ушло', mails_to(net, unknown, wait=1.5) == [])
    t_k, t_u = [t_known], [t_unknown]
    for _ in range(2):
        t_k.append(request(known)[1])
        t_u.append(request(unknown)[1])
    gap = abs(median(t_k) - median(t_u))
    expect('сброс пароля: время ответа различается меньше чем на 0,4 с (медианы %.2f и %.2f с)' % (median(t_k), median(t_u)), gap < 0.4 and min(t_k + t_u) >= 0.7,
           'известный %s, неизвестный %s' % (['%.2f' % t for t in t_k], ['%.2f' % t for t in t_u]))
    admin.clear_attacks(users['buyer-3']['id'])

    # ссылка из письма: новый пароль, вход со старым невозможен
    d = kc.start('dgm-web').follow('login-actions/reset-credentials')
    count = len(mails_to(net, users['buyer-1']['email']))
    d.post(username=users['buyer-1']['email'])
    mail = mails_to(net, users['buyer-1']['email'], at_least=count + 1, wait=10)
    link = action_link(mail[-1]) if len(mail) > count else None
    if expect('сброс пароля: письмо покупателю со ссылкой', link, str([m.get('subject') for m in mail])):
        page = follow_redirects(kc, net.request('GET', link))
        forms = K.forms_of(page.text)
        fresh = strong_password('reset%d' % stamp)
        if expect('сброс пароля: ссылка открывает форму нового пароля', forms and 'password-new' in forms[0]['inputs'], '%s %s' % (page.status, page.text[:200])):
            data = dict(forms[0]['inputs'], **{'password-new': fresh, 'password-confirm': fresh})
            resp, loc = finish_at_callback(kc, net.request('POST', urllib.parse.urljoin(kc.public + '/', forms[0]['action']), form=data))
            expect('сброс пароля: после нового пароля покупатель возвращается в приложение', loc is not None and 'code=' in loc, '%s %s' % (resp.status, K.page_message(resp.text)))
            expect('сброс пароля: со старым паролем вход закрыт', not kc.login('dgm-web', users['buyer-1']['email'], users['buyer-1']['password']).ok)
            r = kc.login('dgm-web', users['buyer-1']['email'], fresh)
            expect('сброс пароля: с новым паролем вход открыт', r.ok, describe(r))
            users['buyer-1']['password'] = fresh


# --------------------------------------------------------------------------------------------------- второй фактор целиком и seller_id
def totp_cycle_checks(kc, admin, users):
    print('== Второй фактор целиком: вход без TOTP, настройка на странице, токен с otp (US-1.5, NFT-3.0, F3-4; TC-118, TC-119)')
    u = users['moderator-2']
    d = kc.start('dgm-staff')
    d.post(username=u['email'], password=u['password'])
    if not expect('второй фактор: сотрудник без TOTP после пароля попадает на настройку TOTP', d.kind == 'totp_setup', d.kind):
        return
    d.post(totp='000000', userLabel='неверный')
    expect('второй фактор: неверный код при настройке не принимается, остаёмся на настройке', d.kind == 'totp_setup' and d.message, '%s %s' % (d.kind, d.message))
    secret = d.setup_totp('проверка стенда')
    r = d.tokens()
    a = r.access() if r.ok else {}
    expect('второй фактор: после настройки выдан токен, роль moderator, область staff.moderation',
           r.ok and (a.get('realm_access') or {}).get('roles') == ['moderator'] and scopes_of(a) == {'staff.moderation'}, describe(r))
    # F4-2: настройка TOTP не записывается как пройденный шаг, поэтому первый токен несёт только pwd и шлюз его не примет как токен со вторым фактором
    expect('второй фактор: сразу после настройки в amr нет otp (находка F4-2), шлюз такой токен отклонит', r.ok and a.get('amr') == ['pwd'], str(a.get('amr')))
    again = kc.start('dgm-staff', extra={'prompt': 'login'}, clear_cookies=False)
    if expect('второй фактор: повторный вход с prompt=login спрашивает пароль даже при живой сессии', again.kind == 'login', again.kind):
        again.post(username=u['email'], password=u['password'])
        expect('второй фактор: повторный вход после настройки просит код TOTP', again.kind == 'otp', again.kind)
        again.post(otp=kc.totp.next_code(secret))
        r = again.tokens()
        expect('второй фактор: после повторного входа amr = otp и pwd', r.ok and sorted(r.access().get('amr') or []) == ['otp', 'pwd'], describe(r))
    creds = admin.realm('/users/%s/credentials' % u['id']).json()
    expect('второй фактор: в Keycloak появилась учётные данные otp с пользовательским названием', any(c['type'] == 'otp' and c.get('userLabel') == 'проверка стенда' for c in creds), str(creds)[:300])
    r = kc.login('dgm-staff', u['email'], u['password'], secret)
    expect('второй фактор: следующий вход требует код TOTP и даёт amr с otp', r.ok and sorted(r.access().get('amr') or []) == ['otp', 'pwd'], describe(r))
    r = kc.login('dgm-staff', u['email'], u['password'])
    expect('второй фактор: пароль без кода TOTP токена не даёт', r.kind == 'otp_required', describe(r))
    u['otp_secret'] = secret
    admin.clear_attacks(u['id'])


def seller_id_checks(kc, admin, users):
    print('== Утверждение seller_id в токене (ADR-010, п. 5; TC-218)')
    seller, buyer = users['seller-1'], users['buyer-1']
    sid = '0199e0a0-5e11-7c00-8a00-000000000042'
    rep = admin.realm('/users/%s' % seller['id']).json()
    rep.setdefault('attributes', {})['seller_id'] = [sid]
    r = admin.realm('/users/%s' % seller['id'], 'PUT', rep)
    if not expect('seller_id: администратор выставил атрибут продавцу через Admin API', r.status == 204, '%s %s' % (r.status, r.text[:200])):
        return
    try:
        res = kc.login('dgm-staff', seller['email'], seller['password'], seller['otp_secret'])
        expect('seller_id: в access-токене продавца утверждение seller_id равно атрибуту', res.ok and res.access().get('seller_id') == sid, describe(res) + ' ' + str(res.access().get('seller_id') if res.ok else ''))
        b = kc.login('dgm-web', buyer['email'], buyer['password'])
        expect('seller_id: у покупателя утверждения нет', b.ok and 'seller_id' not in b.access(), describe(b))
        expect('seller_id: в id-токене продавца его нет (токен для интерфейса, не для сервисов)', res.ok and 'seller_id' not in res.id_claims(), str(res.id_claims() if res.ok else ''))
    finally:
        rep['attributes'].pop('seller_id', None)
        admin.realm('/users/%s' % seller['id'], 'PUT', rep)
    res = kc.login('dgm-staff', seller['email'], seller['password'], seller['otp_secret'])
    expect('seller_id: после снятия атрибута утверждение пропало', res.ok and 'seller_id' not in res.access(), describe(res))
    # пользователь не может выставить его себе сам
    attempt = kc.net.request('PUT', '%s/realms/%s/account' % (kc.public, kc.realm), json_body={'attributes': {'seller_id': [sid]}},
                             headers={'Authorization': 'Bearer ' + res.tokens['access_token']}) if res.ok else None
    expect('seller_id: сам пользователь атрибут выставить не может (нужен запрос к account API, клиент dgm-staff его не получает)', attempt is None or attempt.status in (401, 403, 404), str(attempt.status if attempt else ''))


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
        # Р-151: e-mail в access-токене только у покупателя из dgm-web (адрес для заказа и выдачи ключей), имени нет ни у кого
        expect('%s: в access-токене нет имени и логина' % name, not ({'name', 'preferred_username', 'given_name'} & set(a)), str(sorted(a)))
        if client == 'dgm-web':
            expect('%s: в access-токене покупателя есть e-mail, равный адресу записи' % name, a.get('email') == u['email'], str(a.get('email')))
        else:
            expect('%s: в access-токене клиента %s e-mail нет' % (name, client), 'email' not in a, str(sorted(a)))
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

    # --- второй фактор обязателен (NFT-3.0; US-1.5; TC-118, TC-119)
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

    # --- перебор паролей: пять неудач закрывают вход (US-1.3, сценарий 3; NFT-3.5; TC-105)
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
    expect('провайдеры расширения подключены: dgm-cookie, dgm-auth-level, dgm-register, dgm-reset-email',
           {'dgm-cookie', 'dgm-auth-level', 'dgm-register', 'dgm-reset-email'} <= set(providers), str(providers)[:300])
    # Встроенные потоки Keycloak (browser, registration form и другие) остаются в области как неиспользуемые, смотрим только то, что подключено
    realm_rep = admin.realm('').json()
    start = {realm_rep[k] for k in ('browserFlow', 'registrationFlow', 'resetCredentialsFlow', 'directGrantFlow', 'firstBrokerLoginFlow')}
    start |= {'sms-login', 'dgm-vk-post-login'}
    in_use, seen, queue = set(), set(), sorted(start)
    while queue:
        alias = queue.pop()
        if alias in seen:
            continue
        seen.add(alias)
        for e in admin.realm('/authentication/flows/%s/executions' % urllib.parse.quote(alias)).json():
            if e.get('authenticationFlow'):
                queue.append(e['displayName'])
            elif e.get('providerId'):
                in_use.add(e['providerId'])
    expect('в потоках нет стандартных шагов auth-cookie, reset-credential-email и registration-user-creation (вместо них шаги расширения)',
           not ({'auth-cookie', 'reset-credential-email', 'registration-user-creation'} & in_use), str(sorted(in_use)))
    sms_checks_without_platform(kc)
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

    # --- второй фактор целиком, seller_id, регистрация и сброс пароля
    totp_cycle_checks(kc, admin, users)
    seller_id_checks(kc, admin, users)
    stamp = registration_checks(kc, admin, users)
    reset_checks(kc, admin, users, stamp)

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
