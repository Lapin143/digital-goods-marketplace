#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Сквозные проверки учётных записей на настоящем стенде (шаг 9 Ф4, контрольная точка К1; US-1.1 – US-1.6, US-8.1, US-8.2, ST-02, ST-13).

    make accounts-check

Нужен набор full (платформа, Keycloak с расширением sms-otp, шлюз, заглушки) с отладочным файлом: make up SET=full DEBUG=1, затем
make keycloak-users. Проверки идут настоящим входом (код авторизации с PKCE, страницы Keycloak) и настоящими запросами через шлюз.
Общие тестовые пользователи (buyer-1, seller-1 и другие) не портятся: всё, что меняет состояние, делается над одноразовыми покупателями,
которых проверка регистрирует через страницу регистрации. Что проверяется:

  TC-103  вход до подтверждения почты не открыт;
  TC-104  неверный пароль и неизвестный адрес дают одну и ту же страницу;
  TC-109  вход через VK ID: первый вход создаёт покупателя, повторный входит в ту же запись (заглушка отдаёт новый профиль на каждый прогон);
  TC-110  VK ID: отказ провайдера не создаёт запись; ответ без e-mail наблюдается и печатается (US-1.2, сценарий 4);
  TC-124  номер, подтверждённый одной записью, другой не достаётся: «Номер использовать нельзя», SMS не уходит;
  TC-111, TC-112, TC-115 – TC-117, TC-120  жизнь учётной записи: список администратора, смена роли (Keycloak получает роль и обязательную
          настройку TOTP), токен без otp не открывает кабинет, токен с otp открывает, деактивация закрывает вход и сессии, повтор ничего
          не меняет, анонимизация удаляет запись в Keycloak, платформа хранит обезличенную строку, адрес снова свободен;
  ST-02   три внутренние операции платформы (issueOtpCode, verifyOtpCode, receiveSmsStatus) не достижимы ни одной ролью через шлюз.

Лимит кодов с одного адреса (10 в час) общий с sms_login_checks.py: эта проверка тратит на него два запроса.
Только стандартная библиотека Python.
"""
import json
import os
import re
import sys
import time
import urllib.parse
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import gateway_checks as G  # noqa: E402
import kc_client as K  # noqa: E402
import sms_login_checks as S  # noqa: E402

expect = G.expect
STUBS_ADMIN = 'https://external-stubs:8444'


# --------------------------------------------------------------------------------------------------- вспомогательное
def strong_password(tag):
    return 'Zq9-%s-correct-horse-battery' % tag


def eventually(fn, wait=20.0, step=1.0):
    """Значение fn(), как только оно истинно; иначе последнее значение через wait секунд. Платформа передаёт изменения в Keycloak сразу после записи,
    но проверка не должна зависеть от того, успела ли она до первого вопроса."""
    deadline = time.time() + wait
    while True:
        value = fn()
        if value or time.time() >= deadline:
            return value
        time.sleep(step)


def stub_mode(net, system, body):
    return net.request('PUT', '%s/admin/modes/%s' % (STUBS_ADMIN, system), json_body=body)


def register(kc, email, password):
    d = kc.start('dgm-web').follow('login-actions/registration')
    d.post(email=email, password=password, **{'password-confirm': password})
    return d


def keycloak_user(admin, email):
    found = admin.realm('/users?email=%s&exact=true' % urllib.parse.quote(email)).json()
    return found[0] if found else None


def confirm_email(admin, user_id):
    """Ссылка из письма проверена в keycloak_checks.py (регистрация); здесь адрес подтверждается через Admin API, чтобы не повторять весь путь."""
    rep = admin.realm('/users/%s' % user_id).json()
    rep['emailVerified'] = True
    rep['requiredActions'] = [a for a in rep.get('requiredActions') or [] if a != 'VERIFY_EMAIL']
    return admin.realm('/users/%s' % user_id, 'PUT', rep)


def realm_roles(admin, user_id):
    r = admin.realm('/users/%s/role-mappings/realm/composite' % user_id)
    return {x['name'] for x in r.json()} if r.status == 200 else set()


def get(path, token):
    return G.call('GET', path, headers=G.bearer(token))


def post(path, token, body=None):
    return S.api('POST', path, token, body)


def new_buyer(kc, admin, stamp, tag):
    """Одноразовый покупатель: регистрация на странице, адрес подтверждён. Возвращает словарь с адресом, паролем и идентификатором в Keycloak."""
    email = 'acc-%s-%d@example.test' % (tag, stamp)
    password = strong_password(tag)
    d = register(kc, email, password)
    user = eventually(lambda: keycloak_user(admin, email), wait=10)
    if not expect('одноразовый покупатель %s зарегистрирован через страницу (%s)' % (tag, d.kind), user, d.message):
        return None
    confirm_email(admin, user['id'])
    return {'email': email, 'password': password, 'id': user['id']}


def page_summary(page):
    """Кратко о странице Keycloak: заголовок и имена полей форм (для наблюдений, где вид страницы заранее не задан)."""
    if not page:
        return '(пусто)'
    title = re.search(r'<title[^>]*>(.*?)</title>', page, re.S)
    names = sorted({n for f in K.forms_of(page) for n in f['inputs']})
    text = re.sub(r'\s+', ' ', re.sub(r'<[^>]+>', ' ', re.sub(r'(?s)<(script|style).*?</\1>', ' ', page))).strip()
    return 'заголовок %r, поля %s, текст %r' % (re.sub(r'\s+', ' ', title.group(1)).strip() if title else '', names, text[:160])


def staff_login(kc, admin, user, tries=3):
    """Вход сотрудника со вторым фактором: (токен доступа, пояснение при неудаче).

    Предыдущие проверки того же задания уже входили этими пользователями. Keycloak не принимает код TOTP второй раз (codeReusable = false), а быстрые
    неудачи подряд закрывают вход на минуту. Поэтому перед каждой попыткой блокировка снимается, а повтор ждёт следующего шага TOTP (30 секунд).
    """
    note = ''
    for attempt in range(tries):
        admin.clear_attacks(user['id'])
        r = kc.login(G.CLIENTS[user['role']], user['email'], user['password'], user.get('otp_secret'))
        if r.ok:
            return r.tokens['access_token'], ''
        note = 'попытка %d: %s' % (attempt + 1, S.describe(r))
        time.sleep(31)
    return None, note


# --------------------------------------------------------------------------------------------------- TC-103, TC-104
def login_checks(kc, admin, stamp):
    print('== Вход по e-mail и паролю: до подтверждения почты, неверный пароль, неизвестный адрес (TC-103, TC-104; US-1.1, US-1.3)')
    email = 'acc-main-%d@example.test' % stamp
    password = strong_password('main')
    d = register(kc, email, password)
    user = eventually(lambda: keycloak_user(admin, email), wait=10)
    if not expect('одноразовый покупатель main зарегистрирован через страницу (%s)' % d.kind, user, d.message):
        return None
    r = kc.login('dgm-web', email, password)
    low = r.page.lower() if r.page else ''
    expect('TC-103: до подтверждения почты токенов нет, страница говорит про e-mail (%s)' % r.kind, not r.ok and ('mail' in low or 'почт' in low),
           '%s %s' % (r.kind, K.page_message(r.page)[:200] if r.page else ''))
    confirm_email(admin, user['id'])
    ok_login = kc.login('dgm-web', email, password)
    a = ok_login.access() if ok_login.ok else {}
    expect('TC-104: после подтверждения почты вход открыт: роль buyer, amr = pwd', ok_login.ok and (a.get('realm_access') or {}).get('roles') == ['buyer']
           and a.get('amr') == ['pwd'], S.describe(ok_login))
    wrong = kc.login('dgm-web', email, 'definitely-wrong-password')
    unknown = kc.login('dgm-web', 'nobody-%d@example.test' % stamp, 'definitely-wrong-password')
    msg_wrong, msg_unknown = K.page_message(wrong.page), K.page_message(unknown.page)
    expect('TC-104: неверный пароль и неизвестный адрес: обе попытки остаются на форме входа', wrong.kind == 'login_error' and unknown.kind == 'login_error',
           '%s и %s' % (wrong.kind, unknown.kind))
    expect('TC-104: сообщение одно и то же и не называет причину (%r)' % msg_wrong, msg_wrong and msg_wrong == msg_unknown, '%r и %r' % (msg_wrong, msg_unknown))
    expect('TC-104: страницы совпадают до символа (кроме значений полей и кодов сессии)', K.normalize_page(wrong.page) == K.normalize_page(unknown.page),
           'длины %d и %d' % (len(wrong.page), len(unknown.page)))
    admin.clear_attacks(user['id'])
    return {'email': email, 'password': password, 'id': user['id']}


# --------------------------------------------------------------------------------------------------- TC-109, TC-110
def vk_checks(kc, admin, stamp):
    print('== VK ID: первый и повторный вход, отказ провайдера, ответ без e-mail (TC-109, TC-110; US-1.2)')
    email = 'vk-%d@vk.test' % stamp
    profile = {'sub': 'vk_%d' % stamp, 'email': email, 'email_verified': True, 'name': 'Проверка VK', 'given_name': 'Проверка', 'family_name': 'VK'}
    try:
        r = stub_mode(kc.net, 'vkid', {'behavior': 'success', 'profile': profile})
        if not expect('заглушка VK ID переведена в режим «успех» с новым профилем', r.status == 200, '%s %s' % (r.status, r.text[:200])):
            return
        first = kc.login_idp('dgm-web', 'vkid')
        if not expect('TC-109: первый вход через VK ID завершён токенами', first.ok, S.describe(first)):
            return
        a = first.access()
        expect('TC-109: роль buyer, amr = vk, e-mail в Keycloak подтверждён', (a.get('realm_access') or {}).get('roles') == ['buyer'] and a.get('amr') == ['vk']
               and (keycloak_user(admin, email) or {}).get('emailVerified') is True, '%s %s' % (a.get('realm_access'), a.get('amr')))
        again = kc.login_idp('dgm-web', 'vkid')
        expect('TC-109: повторный вход через VK ID входит в ту же запись (sub совпал), дубля нет',
               again.ok and again.access().get('sub') == a.get('sub') and len(admin.realm('/users?email=%s&exact=true' % urllib.parse.quote(email)).json()) == 1,
               S.describe(again))

        denied_email = 'vk-deny-%d@vk.test' % stamp
        stub_mode(kc.net, 'vkid', {'behavior': 'deny', 'profile': dict(profile, sub='vk_deny_%d' % stamp, email=denied_email)})
        denied = kc.login_idp('dgm-web', 'vkid')
        expect('TC-110: провайдер отказал: токенов нет, запись не создана (%s)' % denied.kind, not denied.ok and keycloak_user(admin, denied_email) is None,
               S.describe(denied))
        stub_mode(kc.net, 'vkid', {'behavior': 'error', 'profile': dict(profile, sub='vk_err_%d' % stamp, email='vk-err-%d@vk.test' % stamp)})
        broken = kc.login_idp('dgm-web', 'vkid')
        expect('TC-110: провайдер вернул ошибку: токенов нет, а не ошибка сервера', not broken.ok, S.describe(broken))

        # US-1.2, сценарий 4: провайдер подтвердил вход, но не передал e-mail. Поведение наблюдается и печатается, результат идёт в отчёт среза.
        silent_sub = 'vk_noemail_%d' % stamp
        stub_mode(kc.net, 'vkid', {'behavior': 'success', 'profile': dict(profile, sub=silent_sub, email='', email_verified=False)})
        silent = kc.login_idp('dgm-web', 'vkid')
        shown = page_summary(silent.page)
        print('  --    TC-110 (наблюдение, не проверка): вход через VK ID без e-mail дал «%s»; сообщение: %r; страница: %s' % (
            silent.kind, K.page_message(silent.page)[:200] if silent.page else '', shown))
        orphan = admin.realm('/users?search=%s' % urllib.parse.quote(silent_sub)).json()
        print('  --    TC-110 (наблюдение): записей Keycloak, найденных по идентификатору VK: %d' % len(orphan))
        expect('TC-110: ответ VK ID без e-mail: токенов нет и записи не появилось', not silent.ok and not orphan, '%s, записей %d' % (S.describe(silent), len(orphan)))
        if os.environ.get('GITHUB_ACTIONS'):
            print('::notice title=TC-110 вход через VK ID без e-mail::результат %s, сообщение %r, записей %d, страница: %s' % (
                silent.kind, K.page_message(silent.page)[:160] if silent.page else '', len(orphan), shown))
    finally:
        stub_mode(kc.net, 'vkid', {})


# --------------------------------------------------------------------------------------------------- жизнь учётной записи
def phone_busy_check(kc, admin, people, owner_token, phone):
    """TC-124: номер подтверждён одной записью, вторая получает нейтральный отказ и SMS не уходит."""
    other = G.login(kc, people['buyer-2'])
    if not expect('TC-124: второй покупатель (buyer-2) вошёл', other, 'вход не удался'):
        return
    before = len(S.sms_items(kc.net, phone))
    r = post('/api/v1/phone-confirmations', other, {'phone': phone})
    expect('TC-124: занятый номер: 409 phone-not-allowed «Номер использовать нельзя»', r.status == 409 and r.code() == 'phone-not-allowed'
           and 'Номер использовать нельзя' in r.text, '%s %s' % (r.status, r.text[:200]))
    time.sleep(1.0)
    expect('TC-124: SMS по занятому номеру не ушло', len(S.sms_items(kc.net, phone)) == before, '%d -> %d' % (before, len(S.sms_items(kc.net, phone))))


def lifecycle(kc, admin, people, buyer):
    print('== Жизнь учётной записи: список, смена роли, второй фактор, деактивация, анонимизация (TC-111, TC-112, TC-115 – TC-117, TC-120, TC-124)')
    token = kc.login('dgm-web', buyer['email'], buyer['password'])
    if not expect('одноразовый покупатель входит паролем', token.ok, S.describe(token)):
        return
    t = token.tokens['access_token']
    phone = S.fresh_phone()
    before = len(S.sms_items(kc.net, phone))
    created = post('/api/v1/phone-confirmations', t, {'phone': phone})
    if not expect('первый запрос к платформе создаёт запись и запрашивает код: 201', created.status == 201, '%s %s' % (created.status, created.text[:200])):
        return
    code = S.next_code(kc.net, phone, before)
    verified = post('/api/v1/phone-confirmations/%s/verify' % created.json()['confirmationId'], t, {'code': code}) if code else None
    expect('телефон одноразового покупателя подтверждён', verified is not None and verified.status == 200 and verified.json().get('phoneConfirmed') is True,
           '%s' % (verified.text[:200] if verified is not None else 'код не пришёл'))
    phone_busy_check(kc, admin, people, t, phone)

    adm, why = staff_login(kc, admin, people['admin-1'])
    if not expect('администратор admin-1 вошёл со вторым фактором', adm, why):
        return
    issued = [time.time()]

    def renew():
        """Токен доступа живёт 5 минут, а проверка с настройкой TOTP и ожиданием шага TOTP идёт дольше: старше 200 секунд токен выдаётся заново."""
        previous = renew.token
        if time.time() - issued[0] > 200:
            token, note = staff_login(kc, admin, people['admin-1'])
            expect('администратор admin-1 вошёл заново для следующих шагов', token, note)
            renew.token = token or previous
            issued[0] = time.time()
        return renew.token
    renew.token = adm
    r = get('/api/v1/staff/users?email=%s' % urllib.parse.quote(buyer['email']), adm)
    items = r.json().get('items', []) if r.status == 200 else []
    if not expect('TC-111: список администратора находит запись по адресу: покупатель, активна, телефон подтверждён',
                  len(items) == 1 and items[0].get('role') == 'buyer' and items[0].get('status') == 'active' and items[0].get('phoneConfirmed') is True
                  and items[0].get('anonymized') is False, '%s %s' % (r.status, r.text[:300])):
        return
    uid = items[0]['userId']
    expect('TC-111: идентификатор записи платформы равен идентификатору в Keycloak (sub)', uid == buyer['id'], '%s и %s' % (uid, buyer['id']))
    active_admins = get('/api/v1/staff/users?role=admin&status=active', adm)
    expect('TC-111: фильтр по роли и статусу: администраторы есть, покупателя среди них нет', active_admins.status == 200
           and active_admins.json().get('items') and all(i.get('role') == 'admin' for i in active_admins.json()['items']), '%s %s' % (active_admins.status, active_admins.text[:200]))

    changed = post('/api/v1/staff/users/%s/change-role' % uid, adm, {'role': 'admin'})
    expect('TC-112: смена роли на admin: 200, в ответе роль admin', changed.status == 200 and changed.json().get('role') == 'admin', '%s %s' % (changed.status, changed.text[:200]))

    def keycloak_has_admin_role():
        roles = realm_roles(admin, uid)
        return 'admin' in roles and 'buyer' not in roles
    expect('TC-112: в Keycloak у записи роль admin, роли buyer нет (одна роль на запись)', eventually(keycloak_has_admin_role), str(sorted(realm_roles(admin, uid))))
    rep = admin.realm('/users/%s' % uid).json()
    expect('TC-112: Keycloak потребует настроить TOTP при следующем входе (обязательное действие CONFIGURE_TOTP)', 'CONFIGURE_TOTP' in (rep.get('requiredActions') or []),
           str(rep.get('requiredActions')))

    d = kc.start('dgm-staff')
    d.post(username=buyer['email'], password=buyer['password'])
    if not expect('TC-118: новый администратор после пароля попадает на настройку TOTP (%s)' % d.kind, d.kind == 'totp_setup', d.kind + ' ' + d.message):
        return
    secret = d.setup_totp('проверка К1')
    first = d.tokens()
    fa = first.access() if first.ok else {}
    expect('TC-112: после настройки выдан токен роли admin с областью staff.admin', first.ok and (fa.get('realm_access') or {}).get('roles') == ['admin']
           and 'staff.admin' in (fa.get('scope') or '').split(), S.describe(first))
    if first.ok:
        denied = get('/api/v1/staff/users', first.tokens['access_token'])
        expect('TC-120: токен администратора без otp в amr (%s) кабинет не открывает: 403 second-factor-required' % fa.get('amr'),
               denied.status == 403 and denied.code() == 'second-factor-required', '%s %s' % (denied.status, denied.text[:200]))
    again = kc.login('dgm-staff', buyer['email'], buyer['password'], secret)
    expect('TC-120: повторный вход с кодом TOTP даёт amr = otp и pwd', again.ok and sorted(again.access().get('amr') or []) == ['otp', 'pwd'], S.describe(again))
    if again.ok:
        allowed = get('/api/v1/staff/users?email=%s' % urllib.parse.quote(buyer['email']), again.tokens['access_token'])
        expect('TC-120: токен с otp кабинет открывает: список администратора 200', allowed.status == 200 and allowed.json().get('items'), '%s %s' % (allowed.status, allowed.text[:200]))
    refresh = again.tokens.get('refresh_token') if again.ok else None

    anon_active = post('/api/v1/staff/users/%s/anonymize' % uid, renew())
    expect('TC-116: анонимизация активной записи отклонена: 409 state-conflict', anon_active.status == 409 and anon_active.code() == 'state-conflict', '%s %s' % (anon_active.status, anon_active.text[:200]))

    off = post('/api/v1/staff/users/%s/deactivate' % uid, renew())
    expect('TC-115: деактивация: 200, статус deactivated', off.status == 200 and off.json().get('status') == 'deactivated', '%s %s' % (off.status, off.text[:200]))
    expect('TC-115: в Keycloak запись отключена', eventually(lambda: admin.realm('/users/%s' % uid).json().get('enabled') is False), str(admin.realm('/users/%s' % uid).json().get('enabled')))
    closed = kc.login('dgm-staff', buyer['email'], buyer['password'], secret)
    expect('TC-115: вход деактивированной записи закрыт (%s)' % closed.kind, not closed.ok, S.describe(closed))
    if refresh:
        r = kc.refresh('dgm-staff', refresh)
        expect('TC-115: выданный до деактивации refresh-токен больше не работает', r.status in (400, 401), '%s %s' % (r.status, r.text[:150]))
    repeat = post('/api/v1/staff/users/%s/deactivate' % uid, renew())
    expect('TC-115: повторная деактивация ничего не меняет: 200 и тот же статус', repeat.status == 200 and repeat.json().get('status') == 'deactivated', '%s %s' % (repeat.status, repeat.text[:200]))
    listed = get('/api/v1/staff/users?email=%s' % urllib.parse.quote(buyer['email']), renew())
    expect('TC-115: история сохранена: запись остаётся в списке со статусом deactivated', listed.status == 200 and [i.get('status') for i in listed.json().get('items', [])] == ['deactivated'],
           '%s %s' % (listed.status, listed.text[:200]))
    role_back = post('/api/v1/staff/users/%s/change-role' % uid, renew(), {'role': 'buyer'})
    expect('TC-115: роль деактивированной записи не меняют: 409 state-conflict', role_back.status == 409 and role_back.code() == 'state-conflict', '%s %s' % (role_back.status, role_back.text[:200]))

    erased = post('/api/v1/staff/users/%s/anonymize' % uid, renew())
    expect('TC-117: анонимизация деактивированной записи: 200, anonymized = true', erased.status == 200 and erased.json().get('anonymized') is True, '%s %s' % (erased.status, erased.text[:200]))
    expect('TC-117: в ответе нет прежнего адреса', buyer['email'] not in erased.text, erased.text[:200])
    expect('TC-117: в Keycloak записи больше нет (404)', eventually(lambda: admin.realm('/users/%s' % uid).status == 404), str(admin.realm('/users/%s' % uid).status))
    page = get('/api/v1/staff/users?status=deactivated&limit=50', renew())
    mine = [i for i in (page.json().get('items', []) if page.status == 200 else []) if i.get('userId') == uid]
    expect('TC-117: платформа хранит обезличенную строку с прежним идентификатором, прежнего адреса в ней нет',
           len(mine) == 1 and mine[0].get('anonymized') is True and mine[0].get('email') != buyer['email'], str(mine)[:300])
    repeat = post('/api/v1/staff/users/%s/anonymize' % uid, renew())
    expect('TC-117: повторная анонимизация ничего не меняет: 200', repeat.status == 200 and repeat.json().get('anonymized') is True, '%s %s' % (repeat.status, repeat.text[:200]))
    d = register(kc, buyer['email'], strong_password('again'))
    reborn = eventually(lambda: keycloak_user(admin, buyer['email']), wait=10)
    expect('TC-117: адрес снова свободен: регистрация на него создаёт новую запись с другим идентификатором', reborn and reborn['id'] != uid and d.resp.status == 200,
           '%s %s' % (d.kind, d.message))


# --------------------------------------------------------------------------------------------------- ST-02 для внутренних операций
def internal_operations(kc, admin, people):
    print('== ST-02: внутренние операции платформы недостижимы через шлюз ни одной ролью (issueOtpCode, verifyOtpCode, receiveSmsStatus)')
    tokens = {'guest': None}
    notes = []
    for name in ('buyer-1', 'seller-1', 'moderator-1', 'support-1', 'admin-1'):
        tokens[people[name]['role']], why = staff_login(kc, admin, people[name]) if people[name].get('otp_secret') else (G.login(kc, people[name]), '')
        if why:
            notes.append('%s: %s' % (name, why))
    missing = [role for role, token in tokens.items() if role != 'guest' and not token]
    if not expect('токены пяти ролей получены', not missing, 'нет токенов: %s; %s' % (missing, '; '.join(notes))):
        return
    probes = [('issueOtpCode', 'POST', '/internal/v1/otp-codes'), ('verifyOtpCode', 'POST', '/internal/v1/otp-verifications'),
              ('receiveSmsStatus', 'POST', '/api/v1/webhooks/sms-provider')]
    for op, method, path in probes:
        results = {}
        for role, token in tokens.items():
            headers = {'Content-Type': 'application/json', 'Idempotency-Key': str(uuid.uuid4())}
            if token:
                headers.update(G.bearer(token))
            results[role] = G.call(method, path, headers=headers, body=b'{}').status
        expect('ST-02 %s: ни одна из шести ролей не получила успеха и ошибки сервера %s' % (op, results),
               all(code in (400, 401, 403, 404) for code in results.values()), str(results))
        if op != 'receiveSmsStatus':
            expect('ST-02 %s: маршрута в шлюзе нет, гость и роли получают отказ до платформы (401 или 404)' % op, all(code in (401, 404) for code in results.values()), str(results))
        else:
            expect('ST-02 %s: вебхук без подписи отклонён всем ролям и гостю (400 или 401)' % op, all(code in (400, 401) for code in results.values()), str(results))


def main():
    kc, admin = K.from_env(dict(os.environ))
    people = G.users()
    for name in ('buyer-1', 'buyer-2', 'seller-1', 'moderator-1', 'support-1', 'admin-1'):
        if name not in people:
            print('Нет тестового пользователя %s (make keycloak-users)' % name, file=sys.stderr)
            return 2
    stamp = int(time.time())
    buyer = login_checks(kc, admin, stamp)
    vk_checks(kc, admin, stamp)
    if buyer:
        lifecycle(kc, admin, people, buyer)
    internal_operations(kc, admin, people)
    print('\nПроверок успешно: %d, с ошибками: %d' % (G.PASSED, len(G.FAILED)))
    for name in G.FAILED:
        print('  - %s' % name)
    if os.environ.get('GITHUB_ACTIONS'):
        print('::notice title=accounts_checks::Проверок успешно: %d, с ошибками: %d%s' % (
            G.PASSED, len(G.FAILED), ('; не прошли: ' + '|'.join(G.FAILED)) if G.FAILED else ''))
    return 1 if G.FAILED else 0


if __name__ == '__main__':
    sys.exit(main())
