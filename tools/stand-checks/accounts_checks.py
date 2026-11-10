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
  TC-110  VK ID: отказ провайдера не создаёт запись; ответ без e-mail ведёт гостя на форму запроса адреса (US-1.2, сценарий 4);
  TC-140  вход через VK ID без e-mail до конца (шаг 16 Ф5, К16): адрес, неверный и верный код из письма почтовой заглушки, токен покупателя,
          запись создана с подтверждённым адресом и привязкой к VK ID, повторный вход адрес не спрашивает;
  TC-142  занятый адрес: код приходит так же, страница та же, время ответа то же, после верного кода нейтральное «Не удалось завершить вход»,
          ни привязки, ни второй записи;
  TC-143  счётчик писем: четвёртый запрос кода удержан (страница кода с сообщением «повторите через N мин.»), регистрация на тот же адрес
          письма тоже не добавляет (счётчик общий для регистрации, сброса пароля и входа через VK ID);
  TC-124  номер, подтверждённый одной записью, другой не достаётся: «Номер использовать нельзя», SMS не уходит;
  TC-111, TC-112, TC-115 – TC-117, TC-120  жизнь учётной записи: список администратора, смена роли (Keycloak получает роль и обязательную
          настройку TOTP), токен без otp не открывает кабинет, токен с otp открывает, деактивация закрывает вход и сессии, повтор ничего
          не меняет, анонимизация удаляет запись в Keycloak, платформа хранит обезличенную строку, адрес снова свободен;
  ST-02   три внутренние операции платформы (issueOtpCode, verifyOtpCode, receiveSmsStatus) не достижимы ни одной ролью через шлюз.

Лимит кодов с одного адреса (10 в час) общий с sms_login_checks.py: эта проверка тратит на него два запроса.
Пик памяти platform-service за время проверки печатается отдельной строкой (memory_watch.py, Р-196, красный выше 90 % лимита).
Только стандартная библиотека Python.
"""
import json
import os
import re
import sys
import time
import traceback
import urllib.parse
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import gateway_checks as G  # noqa: E402
import kc_client as K  # noqa: E402
import memory_watch as MW  # noqa: E402
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

        # US-1.2, сценарий 4: провайдер подтвердил вход, но не передал e-mail (шаг 16 Ф5, К16: проходится до конца)
        for title, scenario in (('VK ID без e-mail', vk_without_email), ('VK ID без e-mail, занятый адрес', vk_busy_address),
                                ('VK ID без e-mail, счётчик писем', vk_letter_limit), ('VK ID без e-mail, время ответа', vk_timing)):
            guarded(title, scenario, kc, admin, stamp)
    finally:
        stub_mode(kc.net, 'vkid', {})


# --------------------------------------------------------------------------------------------------- TC-110, TC-140 – TC-143: VK ID без e-mail
VK_LETTER = 'Код подтверждения e-mail для входа'
CODE_AFTER_VK = re.compile(r'VK ID:\s*(\d{6})(?!\d)')
TIMING_GAP = 0.4        # секунды: на столько медиана времени ответа для занятого адреса может отличаться от свободного (как у регистрации и сброса пароля)


def guarded(title, fn, *args):
    """Сценарий падает исключением: это ошибка проверки, остальные сценарии идут дальше."""
    try:
        return fn(*args)
    except Exception:   # noqa: BLE001 - любой сбой сценария попадает в итог, а не обрывает прогон
        G.bad('%s: сценарий прерван исключением' % title, traceback.format_exc()[-500:])
        return None


def vk_code_of(letter):
    """Шесть цифр кода из письма «Код подтверждения e-mail для входа»: те, что идут после «VK ID:»; если текст иной, единственное шестизначное
    число, не часть более длинного слова (идентификатор запроса или адрес не годятся, F5-46). Нет кода: None."""
    text = (letter or {}).get('text') or ''
    found = CODE_AFTER_VK.search(text)
    if found:
        return found.group(1)
    loose = set(re.findall(r'(?<![0-9A-Za-z])\d{6}(?![0-9A-Za-z])', text))
    return loose.pop() if len(loose) == 1 else None


def letters_to(net, address, subject=None, at_least=0, wait=0.0):
    """Письма, которые SMTP-заглушка приняла для адреса (по теме, если она задана), по порядку приёма. at_least > 0: ждёт, пока их станет
    не меньше, до wait секунд; иначе ждёт wait секунд (проверка, что новых писем нет)."""
    deadline = time.time() + wait
    while True:
        found = []
        m = net.request('GET', '%s/admin/emails?to=%s' % (STUBS_ADMIN, urllib.parse.quote(address)), headers={'Accept': 'application/json'})
        if m.status == 200:
            body = m.json()
            found = [x for x in (body if isinstance(body, list) else body.get('items') or body.get('emails') or [])
                     if subject is None or x.get('subject') == subject]
        if (at_least > 0 and len(found) >= at_least) or time.time() >= deadline:
            return found
        time.sleep(0.4)


def vk_letters(net, address, at_least=0, wait=0.0):
    return letters_to(net, address, VK_LETTER, at_least, wait)


def vk_silent_dialog(kc, sub):
    """Вход через VK ID, когда провайдер подтверждает вход и не передаёт e-mail (профиль с пустым адресом): страница Keycloak после возврата от провайдера."""
    profile = {'sub': sub, 'email': '', 'email_verified': False, 'name': 'Проверка VK', 'given_name': 'Проверка', 'family_name': 'VK'}
    r = stub_mode(kc.net, 'vkid', {'behavior': 'success', 'profile': profile})
    if r.status != 200:
        raise RuntimeError('заглушка VK ID не переведена в режим «успех без e-mail»: %s %s' % (r.status, r.text[:200]))
    return kc.start('dgm-web', extra={'kc_idp_hint': 'vkid'})


def submit_address(d, address):
    """Отправляет адрес на форме запроса: (секунды ответа Keycloak, страница после ответа)."""
    started = time.time()
    d.post(email=address)
    return time.time() - started, d.page


def linked_to_vk(admin, sub):
    r = admin.realm('/users?idpAlias=vkid&idpUserId=%s' % urllib.parse.quote(sub))
    return r.json() if r.status == 200 else None


def says(d, fragment):
    """Есть ли на странице фраза (без учёта регистра): ищется и в извлечённом сообщении, и во всём тексте страницы, чтобы проверка не зависела от разметки
    блока сообщений."""
    return fragment in d.message.lower() or fragment in d.page.lower()


def without_address(page, address):
    """Страница кода без адреса гостя: так сравниваются ответы для свободного и занятого адреса."""
    return K.normalize_page(page.replace(address, 'ADDRESS'))


def vk_without_email(kc, admin, stamp):
    print('== VK ID без e-mail: форма адреса, код из письма, запись создаётся подтверждённой (US-1.2, сценарий 4; TC-110, TC-140)')
    sub = 'vk_noemail_%d' % stamp
    address = 'vk-free-%d@example.test' % stamp
    d = vk_silent_dialog(kc, sub)
    if not expect('TC-110: VK ID подтвердил вход без e-mail: гость на форме запроса адреса, токенов нет (%s)' % d.kind, d.kind == 'vk_email', page_summary(d.page)):
        return
    found = linked_to_vk(admin, sub)
    expect('TC-110: пока адрес не подтверждён кодом, записи Keycloak с этим идентификатором VK нет', found == [], str(found)[:200])

    d.post(email='not an address')
    expect('TC-140: адрес неверной формы остаётся на форме с сообщением «Введите адрес электронной почты» (%r)' % d.message[:80],
           d.kind == 'vk_email' and says(d, 'введите адрес электронной почты'), '%s %s' % (d.kind, d.message[:160]))

    _, page = submit_address(d, '  VK-Free-%d@Example.Test  ' % stamp)
    if not expect('TC-140: адрес с заглавными буквами и пробелами принят, показана форма кода, в ней адрес в нижнем регистре (%s)' % d.kind,
                  d.kind == 'vk_code' and address in page, '%s %s' % (d.kind, d.message[:160])):
        return
    mails = vk_letters(kc.net, address, at_least=1, wait=15)
    code = vk_code_of(mails[0]) if mails else None
    if not expect('TC-140: на адрес пришло одно письмо «%s» с шестизначным кодом' % VK_LETTER, len(mails) == 1 and bool(code), str(mails)[:240]):
        return
    expect('TC-140: пока код не введён, записи с этим адресом в Keycloak нет', keycloak_user(admin, address) is None)
    d.post(code=S.other_code(code))
    expect('TC-140: неверный код остаётся на форме кода с сообщением «Код неверный» (%r)' % d.message[:60], d.kind == 'vk_code' and says(d, 'код неверный'),
           '%s %s' % (d.kind, d.message[:160]))
    d.post(code=code)
    result = d.tokens()
    if not expect('TC-140: верный код завершает вход: токены выданы (%s)' % result.kind, result.ok, S.describe(result)):
        return
    claims = result.access()
    user = keycloak_user(admin, address)
    expect('TC-140: роль buyer, amr = vk', (claims.get('realm_access') or {}).get('roles') == ['buyer'] and claims.get('amr') == ['vk'],
           '%s %s' % (claims.get('realm_access'), claims.get('amr')))
    expect('TC-140: запись создана с адресом из письма и подтверждённым e-mail', bool(user) and user.get('emailVerified') is True and user.get('id') == claims.get('sub'),
           str({k: (user or {}).get(k) for k in ('id', 'email', 'emailVerified')}))
    linked = linked_to_vk(admin, sub) or []
    expect('TC-140: запись привязана к VK ID (идентификатор провайдера найден)', [u.get('id') for u in linked] == [claims.get('sub')], str(linked)[:200])
    again = kc.login_idp('dgm-web', 'vkid')
    expect('TC-140: повторный вход через VK ID входит в ту же запись и адрес больше не спрашивает', again.ok and again.access().get('sub') == claims.get('sub'),
           S.describe(again))
    return page


def vk_busy_address(kc, admin, stamp):
    print('== VK ID без e-mail, адрес занят: код приходит, привязки нет, ответ неотличим (US-1.2; TC-142)')
    owner = new_buyer(kc, admin, stamp, 'vkown')
    if not owner:
        return
    free = vk_free_page(kc, stamp)      # до диалога владельца: каждый новый диалог начинается с чистых cookie браузера
    sub = 'vk_busy_%d' % stamp
    d = vk_silent_dialog(kc, sub)
    if not expect('TC-142: гость на форме запроса адреса (%s)' % d.kind, d.kind == 'vk_email', page_summary(d.page)):
        return
    _, page = submit_address(d, owner['email'])
    expect('TC-142: занятый адрес получает форму кода, как и свободный (%s)' % d.kind, d.kind == 'vk_code', '%s %s' % (d.kind, d.message[:160]))
    if free:
        expect('TC-142: страницы кода для занятого и свободного адреса совпадают до символа (кроме адреса, значений полей и кодов сессии)',
               without_address(page, owner['email']) == without_address(free[1], free[0]), 'длины %d и %d' % (len(page), len(free[1])))
    mails = vk_letters(kc.net, owner['email'], at_least=1, wait=15)
    code = vk_code_of(mails[-1]) if mails else None
    if not expect('TC-142: код пришёл и на занятый адрес (письмо получает владелец ящика)', bool(code), str(mails)[:240]):
        return
    d.post(code=code)
    expect('TC-142: верный код при занятом адресе даёт нейтральное «Не удалось завершить вход», токенов нет (%s)' % d.kind,
           d.loc is None and says(d, 'не удалось завершить вход'), '%s %s' % (d.kind, d.message[:160]))
    found = linked_to_vk(admin, sub)
    expect('TC-142: идентификатор VK ни к одной записи не привязан', found == [], str(found)[:200])
    federated = admin.realm('/users/%s/federated-identity' % owner['id']).json()
    expect('TC-142: у владельца адреса привязок к VK ID нет, чужой вход в его запись не открыт', federated == [], str(federated)[:200])
    same = admin.realm('/users?email=%s&exact=true' % urllib.parse.quote(owner['email'])).json()
    expect('TC-142: запись с этим адресом по-прежнему одна', len(same) == 1 and same[0]['id'] == owner['id'], str(len(same)))


def vk_free_page(kc, stamp):
    """Страница кода для свободного адреса (адрес, страница): образец для сравнения с ответом на занятый адрес."""
    address = 'vk-same-%d@example.test' % stamp
    d = vk_silent_dialog(kc, 'vk_same_%d' % stamp)
    if d.kind != 'vk_email':
        return None
    d.post(email=address)
    return (address, d.page) if d.kind == 'vk_code' else None


def vk_letter_limit(kc, admin, stamp):
    print('== VK ID без e-mail: счётчик писем на адрес, четвёртое письмо удержано (F4-1, US-1.2; TC-143)')
    address = 'vk-quota-%d@example.test' % stamp
    d = vk_silent_dialog(kc, 'vk_quota_%d' % stamp)
    if not expect('TC-143: гость на форме запроса адреса (%s)' % d.kind, d.kind == 'vk_email', page_summary(d.page)):
        return
    d.post(email=address)
    if not expect('TC-143: первый запрос кода: форма кода (%s)' % d.kind, d.kind == 'vk_code', '%s %s' % (d.kind, d.message[:160])):
        return
    for n in (2, 3):
        d.post(button='resend')
        expect('TC-143: запрос кода %d из 3: форма кода без сообщения об ограничении (%s)' % (n, d.kind), d.kind == 'vk_code' and not says(d, 'повторите через'),
               '%s %s' % (d.kind, d.message[:160]))
    sent = vk_letters(kc.net, address, at_least=3, wait=15)
    expect('TC-143: за три запроса ушло три письма', len(sent) == 3, str(len(sent)))
    d.post(button='resend')
    expect('TC-143: четвёртый запрос: форма кода остаётся, сообщение «повторите через N мин.» (%r)' % d.message[:90],
           d.kind == 'vk_code' and says(d, 'повторите через'), '%s %s' % (d.kind, d.message[:160]))
    expect('TC-143: четвёртое письмо не уходит и позже', len(vk_letters(kc.net, address, wait=2.0)) == 3)
    register(kc, address, strong_password('quota'))
    expect('TC-143: счётчик общий: регистрация на тот же адрес письма не добавляет (всего писем на адрес три)', len(letters_to(kc.net, address, wait=3.0)) == 3,
           str([m.get('subject') for m in letters_to(kc.net, address)]))


def vk_timing(kc, admin, stamp):
    print('== VK ID без e-mail: время ответа на форме адреса для свободного и занятого адреса (ST-06; TC-142)')
    owners = [new_buyer(kc, admin, stamp, 'vkt%d' % n) for n in range(3)]
    if not all(owners):
        return
    t_free, t_busy = [], []
    for n, owner in enumerate(owners):
        for sub, address, bucket in (('vk_tfree_%d_%d' % (stamp, n), 'vk-tfree-%d-%d@example.test' % (stamp, n), t_free),
                                     ('vk_tbusy_%d_%d' % (stamp, n), owner['email'], t_busy)):
            d = vk_silent_dialog(kc, sub)
            if d.kind != 'vk_email':
                G.bad('время ответа: гость не на форме запроса адреса (%s)' % d.kind, page_summary(d.page))
                return
            seconds, _ = submit_address(d, address)
            bucket.append(seconds)
    gap = abs(S.median(t_free) - S.median(t_busy))
    expect('TC-142: время ответа различается меньше чем на %.1f с (свободный адрес %s, занятый %s)' % (TIMING_GAP, ['%.2f' % x for x in t_free], ['%.2f' % x for x in t_busy]),
           gap < TIMING_GAP, 'разница медиан %.2f с' % gap)


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
    # шаг 16 Ф5 (К16, Р-196): пик памяти platform-service под входом, письмами и кодами отдельной строкой, красный выше 90 % лимита
    watch = MW.MemoryWatch(('platform-service',), 'под сценариями учётных записей')
    watch.start()
    try:
        buyer = login_checks(kc, admin, stamp)
        vk_checks(kc, admin, stamp)
        if buyer:
            lifecycle(kc, admin, people, buyer)
        internal_operations(kc, admin, people)
    finally:
        watch.finish()
    print('\nПроверок успешно: %d, с ошибками: %d' % (G.PASSED, len(G.FAILED)))
    for name in G.FAILED:
        print('  - %s' % name)
    if os.environ.get('GITHUB_ACTIONS'):
        print('::notice title=accounts_checks::Проверок успешно: %d, с ошибками: %d%s' % (
            G.PASSED, len(G.FAILED), ('; не прошли: ' + '|'.join(G.FAILED)) if G.FAILED else ''))
    return 1 if G.FAILED else 0


if __name__ == '__main__':
    sys.exit(main())
