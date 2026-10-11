#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Вход по коду из SMS на настоящем стенде (шаг 7 Ф4; FT-1.5, FT-1.7, F3-3, ST-06, ST-07, ADR-010).

    make sms-login-check

Нужен набор full (платформа, Keycloak с расширением sms-otp, шлюз, заглушки) с отладочным файлом: make up SET=full DEBUG=1, затем
make keycloak-users. Проверки идут цепочкой, как у покупателя:

1. покупатель входит паролем, просит код подтверждения телефона через шлюз, вводит неверный код, затем верный из SMS-заглушки;
2. Keycloak получает признак `phone_verified`, он появляется в токене;
3. покупатель входит по SMS (клиент dgm-sms): `amr` равен `sms`, токен живёт не больше 15 минут, refresh-токена нет, области только
   `orders.read` и `support.write`, шлюз не пускает такой токен туда, где нужен полный вход;
4. F3-3: сессия по SMS не превращается в полную тихо: клиент dgm-web просит пароль, после пароля `pwd` в `amr`, и дальше SSO работает;
5. ST-06: чужой (не привязанный) номер получает ту же страницу кода, то же сообщение о неверном коде, то же время ответа, SMS не уходит.

Лимиты запросов кода (3 за 10 минут на номер, 10 за час на IP) общие для всей платформы, поэтому проверка расходует их экономно: на привязанный
номер уходит три запроса (подтверждение, вход, сравнение с чужим номером), на IP не больше шести. Повторный прогон на том же стенде
использует номер первого прогона (secrets/sms_login_phone.json): телефон покупателя подтверждается один раз.
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

BUYER = 'buyer-3'
STATE_FILE = os.path.join(G.SECRETS, 'sms_login_phone.json')
expect = G.expect


# --------------------------------------------------------------------------------------------------- вспомогательное
def fresh_phone():
    """Случайный российский мобильный номер: прогоны и параллельные проверки не сталкиваются на уникальном индексе номера."""
    return '+79%09d' % (time.time_ns() // 1000 % 10**9)


def sms_items(net, phone):
    r = net.request('GET', 'https://external-stubs:8444/admin/sms?to=%s&all=1' % urllib.parse.quote(phone), headers={'Accept': 'application/json'})
    if r.status != 200:
        return []
    body = r.json()
    return body if isinstance(body, list) else body.get('items') or []


def next_code(net, phone, seen, wait=15):
    """Код из нового SMS на номер (новее, чем seen сообщений). Ждёт: заглушка принимает сообщение чуть позже ответа платформы."""
    deadline = time.time() + wait
    while time.time() < deadline:
        items = sms_items(net, phone)
        if len(items) > seen:
            return items[-1].get('code')
        time.sleep(0.4)
    return None


def other_code(code):
    return '000000' if code != '000000' else '111111'


def api(method, path, token, body=None):
    headers = dict(G.bearer(token), **{'Content-Type': 'application/json', 'Idempotency-Key': str(uuid.uuid4())})
    return G.call(method, path, headers=headers, body=json.dumps(body).encode() if body is not None else None)


def describe(r):
    return '%s %s' % (r.kind, K.page_message(r.page) if r.page and r.kind != 'tokens' else '')


def median(values):
    return sorted(values)[len(values) // 2]


def mask_phone(page):
    """Страница кода показывает номер в маске; для сравнения страниц разных номеров маска заменяется."""
    return re.sub(r'\+7\d{3}\*\*\*\d{4}', '+7***', K.normalize_page(page))


def remembered_phone():
    try:
        return json.load(open(STATE_FILE, encoding='utf-8')).get(BUYER)
    except (OSError, ValueError):
        return None


def remember_phone(phone):
    os.makedirs(os.path.dirname(STATE_FILE) or '.', exist_ok=True)
    data = {}
    try:
        data = json.load(open(STATE_FILE, encoding='utf-8'))
    except (OSError, ValueError):
        pass
    data[BUYER] = phone
    fd = os.open(STATE_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'w', encoding='utf-8') as f:
        json.dump(data, f)


# --------------------------------------------------------------------------------------------------- проверки
def bind_phone(kc, buyer):
    """Покупатель подтверждает телефон через шлюз. Возвращает (номер, sub) либо (None, sub), если проверять дальше нечем."""
    print('== Подтверждение телефона покупателем (OP-06, FT-1.5)')
    r = kc.login('dgm-web', buyer['email'], buyer['password'])
    if not expect('покупатель %s входит паролем' % BUYER, r.ok, describe(r)):
        return None, None
    token = r.tokens['access_token']
    sub = r.access().get('sub')
    phone = fresh_phone()
    before = len(sms_items(kc.net, phone))
    created = api('POST', '/api/v1/phone-confirmations', token, {'phone': phone})
    if created.status == 409 and created.code() == 'state-conflict':
        old = remembered_phone()
        print('  --    телефон покупателя уже подтверждён предыдущим прогоном%s' % (': %s' % old[:5] + '***' if old else ''))
        return old, sub
    if not expect('запрос кода подтверждения: 201 и идентификатор запроса', created.status == 201 and created.json().get('confirmationId'),
                  '%s %s' % (created.status, created.text[:200])):
        return None, sub
    cid = created.json()['confirmationId']
    code = next_code(kc.net, phone, before)
    if not expect('SMS с кодом пришло на номер (заглушка провайдера)', code and re.fullmatch(r'\d{6}', code), str(code)):
        return None, sub
    wrong = api('POST', '/api/v1/phone-confirmations/%s/verify' % cid, token, {'code': other_code(code)})
    expect('неверный код: 422 otp-invalid и число оставшихся попыток', wrong.status == 422 and wrong.code() == 'otp-invalid'
           and isinstance(wrong.json().get('attemptsLeft'), int), '%s %s' % (wrong.status, wrong.text[:200]))
    right = api('POST', '/api/v1/phone-confirmations/%s/verify' % cid, token, {'code': code})
    if not expect('верный код: 200, телефон подтверждён', right.status == 200 and right.json().get('phoneConfirmed') is True,
                  '%s %s' % (right.status, right.text[:200])):
        return None, sub
    remember_phone(phone)
    again = api('POST', '/api/v1/phone-confirmations', token, {'phone': fresh_phone()})
    expect('второй запрос кода после подтверждения: 409 (телефон уже подтверждён)', again.status == 409, '%s %s' % (again.status, again.text[:200]))
    # Платформа передала Keycloak признак подтверждения сразу после записи; в новом токене он есть
    deadline, claims = time.time() + 20, {}
    while time.time() < deadline:
        r = kc.login('dgm-web', buyer['email'], buyer['password'])
        claims = r.access() if r.ok else {}
        if claims.get('phone_verified') is True:
            break
        time.sleep(1)
    expect('в токене появился признак phone_verified = true (платформа передала его Keycloak)', claims.get('phone_verified') is True, str(claims.get('phone_verified')))
    return phone, sub


def sms_session_checks(kc, buyer, phone, sub):
    print('== Вход по коду из SMS: ограниченная сессия (FT-1.7, F3-3)')
    seen = len(sms_items(kc.net, phone))
    r = kc.login_sms('dgm-sms', phone, lambda: next_code(kc.net, phone, seen))
    if not expect('вход по SMS: токены выданы', r.ok, describe(r)):
        return None
    t = r.tokens
    a = r.access()
    scopes = set((a.get('scope') or '').split())
    expect('вход по SMS: тот же пользователь, что при входе паролем', a.get('sub') == sub, '%s и %s' % (a.get('sub'), sub))
    expect('вход по SMS: amr равен sms, пароля в нём нет', a.get('amr') == ['sms'], str(a.get('amr')))
    expect('вход по SMS: области orders.read и support.write, полного доступа нет', {'orders.read', 'support.write'} <= scopes
           and not ({'account.manage', 'orders.create', 'seller.apply'} & scopes), str(sorted(scopes)))
    expect('вход по SMS: признак phone_verified = true', a.get('phone_verified') is True, str(a.get('phone_verified')))
    expect('вход по SMS: токен живёт не больше 15 минут', 0 < a.get('exp', 0) - a.get('iat', 0) <= 900, '%s' % (a.get('exp', 0) - a.get('iat', 0)))
    expect('вход по SMS: refresh-токена нет', 'refresh_token' not in t, str(sorted(t)))
    expect('вход по SMS: адресат dgm-api', 'dgm-api' in (a['aud'] if isinstance(a.get('aud'), list) else [a.get('aud')]), str(a.get('aud')))

    denied = api('POST', '/api/v1/phone-confirmations', t['access_token'], {'phone': fresh_phone()})
    expect('сессия по SMS не может подтверждать телефон (нужен полный вход): 403', denied.status == 403, '%s %s' % (denied.status, denied.text[:200]))
    staff = G.call('GET', '/api/v1/staff/users', headers=G.bearer(t['access_token']))
    expect('сессия по SMS не открывает кабинет сотрудников: 403', staff.status == 403, '%s %s' % (staff.status, staff.text[:200]))

    print('== Уровень входа: SMS не становится полной сессией без пароля (F3-3)')
    d = kc.start('dgm-web', clear_cookies=False)
    if expect('браузер с сессией по SMS: клиент dgm-web просит пароль, а не входит по SSO-cookie', d.kind == 'login', d.kind):
        d.post(username=buyer['email'], password=buyer['password'])
        full = d.tokens()
        fa = full.access() if full.ok else {}
        expect('после пароля токен полный: pwd в amr, пароль принят', full.ok and 'pwd' in (fa.get('amr') or []), describe(full))
        expect('после пароля сессия больше не помечена как SMS: sms в amr без pwd быть не может', not ('sms' in (fa.get('amr') or []) and 'pwd' not in (fa.get('amr') or [])),
               str(fa.get('amr')))
        silent = kc.start('dgm-web', clear_cookies=False)
        expect('после полного входа SSO снова работает: клиент dgm-web входит без пароля', silent.kind == 'redirect', silent.kind)
    sms_again = kc.start('dgm-sms', clear_cookies=False)
    expect('клиент dgm-sms всегда спрашивает номер, SSO-cookie его не заменяет', sms_again.kind == 'sms_phone', sms_again.kind)
    return a


def neutrality_checks(kc, phone):
    print('== Чужой номер неотличим от привязанного (ST-06)')
    # Диалоги идут строго по очереди: следующий kc.start стирает cookie браузера, и прежняя страница уже не работает
    seen = len(sms_items(kc.net, phone))
    strangers = [fresh_phone() for _ in range(2)]
    while strangers[0] == strangers[1]:
        strangers[1] = fresh_phone()

    def ask(number):
        d = kc.start('dgm-sms')
        t0 = time.time()
        d.post(phone=number)
        return d, time.time() - t0

    bound, t_bound = ask(phone)
    code = next_code(kc.net, phone, seen)
    bound_page, bound_kind = mask_phone(bound.page), bound.kind
    bound.post(code=other_code(code or ''))
    bound_wrong = (bound.kind, bound.message, mask_phone(bound.page))
    bound.post(code=code)
    done = bound.tokens()

    strange, t_strange = ask(strangers[0])
    strange_page, strange_kind = mask_phone(strange.page), strange.kind
    strange.post(code='000000')
    strange_wrong = (strange.kind, strange.message, mask_phone(strange.page))
    _, t_strange_2 = ask(strangers[1])

    expect('привязанный и чужой номер: обоим показана страница кода', bound_kind == 'sms_code' and strange_kind == 'sms_code', '%s и %s' % (bound_kind, strange_kind))
    expect('страницы кода совпадают, кроме маски номера', bound_page == strange_page, 'страницы различаются')
    gap = abs(t_bound - median([t_strange, t_strange_2]))
    expect('время ответа различается меньше чем на 0,4 с (привязанный %.2f с, чужие %.2f и %.2f с)' % (t_bound, t_strange, t_strange_2), gap < 0.4,
           'разница %.2f с' % gap)
    expect('привязанному номеру ушло ровно одно SMS, чужим ни одного', code is not None and len(sms_items(kc.net, phone)) == seen + 1
           and all(sms_items(kc.net, s) == [] for s in strangers), '%s' % len(sms_items(kc.net, phone)))
    expect('неверный код: одна и та же страница и сообщение для привязанного и чужого номера',
           bound_wrong[0] == 'sms_code' and bound_wrong == strange_wrong and bound_wrong[1], '%r и %r' % (bound_wrong[:2], strange_wrong[:2]))
    expect('верный код привязанного номера всё же входит (неверная попытка код не сожгла)', done.ok and done.access().get('amr') == ['sms'], describe(done))


def main():
    kc, _admin = K.from_env(dict(os.environ))
    buyer = G.users().get(BUYER)
    if not buyer:
        print('Нет тестового пользователя %s (make keycloak-users)' % BUYER, file=sys.stderr)
        return 2
    phone, sub = bind_phone(kc, buyer)
    if phone:
        if sms_session_checks(kc, buyer, phone, sub) is not None:
            neutrality_checks(kc, phone)
    else:
        G.bad('вход по SMS не проверен: у покупателя нет подтверждённого номера', 'выполните make reset && make up SET=full DEBUG=1 && make keycloak-users')
    print('\nПроверок успешно: %d, с ошибками: %d' % (G.PASSED, len(G.FAILED)))
    for name in G.FAILED:
        print('  - %s' % name)
    # В GitHub Actions число проверок попадает в аннотацию: по ней видно, сколько проверок выполнено, без доступа к журналу
    if os.environ.get('GITHUB_ACTIONS'):
        print('::notice title=sms_login_checks::Проверок успешно: %d, с ошибками: %d%s' % (
            G.PASSED, len(G.FAILED), ('; не прошли: ' + '|'.join(G.FAILED)) if G.FAILED else ''))
    return 1 if G.FAILED else 0


if __name__ == '__main__':
    sys.exit(main())
