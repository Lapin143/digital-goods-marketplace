#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Смена e-mail через поддержку и сессия по SMS сквозь стенд (шаг 17 среза 3 Ф4, К10; US-1.7, US-7.4, FT-1.7, FT-7.5, NFT-3.5, NFT-3.7, INV-23, INV-34, INV-44, ST-05;
TC-016, TC-720).

    make email-change-check

Нужен тот же стенд, что для support_checks.py (набор full, ускоренные сроки, отладочный файл; make fast-up DEBUG=1, make keycloak-users). Оператор support-1 и
администратор admin-1 входят настоящим входом с TOTP, покупатели одноразовые, их телефоны подтверждаются через платформу (код берётся из SMS-заглушки).
Лимит запросов кода с одного IP (10 в час) общий для всех проверок стенда, поэтому на время сценариев администратор поднимает параметр
otp.requests-per-hour-per-ip до 100 и возвращает значение по умолчанию на выходе (проверка красная, если возврат не выполнен); пределы на номер и учётную запись
(3 за 10 минут, 10 за сутки) остаются по умолчанию: именно их проверяет ST-05.

  смена       TC-016: покупатель без доступа к почте входит по коду из SMS (клиент dgm-sms), создаёт обращение, оператор берёт его и называет новый адрес, SMS-код из
              заглушки вводит покупатель, код из письма на новый адрес читается из почтовой заглушки и тоже вводится покупателем: смена завершена, в Keycloak и в записи
              платформы новый адрес, прежнего нет; письма о смене ушли на оба адреса; ключ ушёл на новый адрес тем же ключом, обращение решено доставкой
  сессия      TC-720: токен по SMS (orders.read, support.write) читает заказы и работает с обращениями (создание, чтение, коды смены e-mail), а оформление заказа,
              платёжная сессия, подтверждение телефона, заявка продавца и кабинет сотрудников дают 403
  отказы      занятый и собственный адрес: одинаковый нейтральный 409 email-not-allowed, SMS не уходит (INV-34); четыре неверных кода: 422 otp-invalid с attemptsLeft
              4, 3, 2, 1, пятая неудача 429 otp-locked с Retry-After, смена failed, адрес в записи обнулён, маска осталась (ST-05); верный код после этого и шестая попытка
              409; этапы вне порядка 409; четвёртый запрос кода на номер за 10 минут 429 otp-request-limited, четвёртого SMS нет (ST-05); чужое обращение 404;
              обращение, возвращённое в очередь, смену не принимает (409)
  оператор    ни в одном ответе оператора нет кодов подтверждения, адреса покупателя и ключа (INV-23); в журнале аудита записи support.email_change_started (оператор) и
              support.email_change_completed (оператор, адрес только хешами)
  канарейка   прежний и новый адрес и коды из SMS и письма не найдены в ответах API журнала, дампах схем audit_admin, support и notification, темах audit.events,
              support.events и identity.events, журналах платформы и шлюза; у каждого поиска есть контроль, который обязан найтись

Сессия по SMS живёт не больше 15 минут, весь сценарий укладывается в две. Только стандартная библиотека Python.
"""
import json
import os
import re
import sys
import time
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import accounts_checks as A  # noqa: E402
import audit_checks as AU  # noqa: E402
import canary_checks as C  # noqa: E402
import gateway_checks as G  # noqa: E402
import kc_client as K  # noqa: E402
import orders_checks as O  # noqa: E402
import purchase_checks as P  # noqa: E402
import seller_approval_checks as SA  # noqa: E402
import sellers_checks as SC  # noqa: E402
import sms_login_checks as S  # noqa: E402
import support_checks as SU  # noqa: E402

expect = G.expect
TICKETS = SU.TICKETS
STAFF = SU.STAFF
PARAMETERS = '/api/v1/staff/parameters'
IP_HOUR = 'otp.requests-per-hour-per-ip'
IP_HOUR_RAISED = 100
CODE_SUBJECT = 'Код подтверждения нового адреса e-mail'
NEW_SUBJECT = 'E-mail вашей учётной записи изменён'
PREVIOUS_SUBJECT = 'E-mail вашей учётной записи заменён'
SIX_DIGITS = re.compile(r'(?<!\d)(\d{6})(?!\d)')


class Context:
    def __init__(self, kc, admin, people, stamp, fx):
        self.kc, self.admin, self.people, self.stamp, self.fx = kc, admin, people, stamp, fx
        self.stubs = fx['stubs']
        self.operator = SA.Staff(kc, admin, people[SU.OPERATOR])
        self.administrator = SA.Staff(kc, admin, people['admin-1'])
        self.seen = []        # все ответы оператора: в них не должно быть ни кодов, ни адресов, ни ключей
        self.secrets = []     # коды и адреса, которых не должно быть в журналах, дампах и темах
        self.addresses = []


# --------------------------------------------------------------------------------------------------- лимит с одного IP
def parameter(ctx, key):
    answer = G.call('GET', PARAMETERS, headers=SC.auth(ctx.administrator.token()))
    items = {i['key']: i for i in (SU.body_of(answer).get('items') or [])} if answer.status == 200 else {}
    return items.get(key)


def put_parameter(ctx, key, value):
    current = parameter(ctx, key)
    if not current:
        return None
    return SC.put_json('%s/%s' % (PARAMETERS, key), ctx.administrator.token(), '"%d"' % current['version'], {'value': value})


def raise_ip_limit(ctx):
    print('== Лимит запросов кода с одного IP поднят на время проверки (параметр %s, 10 → %d)' % (IP_HOUR, IP_HOUR_RAISED))
    answer = put_parameter(ctx, IP_HOUR, IP_HOUR_RAISED)
    ok = expect('Параметр %s = %d принят платформой: 200' % (IP_HOUR, IP_HOUR_RAISED), answer is not None and answer.status == 200
                and SU.body_of(answer).get('value') == IP_HOUR_RAISED, O.describe(answer) if answer else 'параметра нет в списке')
    time.sleep(3)
    return ok


def restore_ip_limit(ctx):
    print('== Лимит запросов кода с одного IP возвращён к значению по умолчанию (иначе проверка красная)')
    current = parameter(ctx, IP_HOUR)
    if current and current['value'] != current['defaultValue']:
        put_parameter(ctx, IP_HOUR, current['defaultValue'])
    current = parameter(ctx, IP_HOUR) or {}
    expect('Параметр %s равен значению по умолчанию (%s)' % (IP_HOUR, current.get('defaultValue')), current.get('value') == current.get('defaultValue') and bool(current), str(current)[:200])


# --------------------------------------------------------------------------------------------------- покупатель и заказ
def prepared_buyer(ctx, tag):
    """Покупатель с телефоном, подтверждённым через платформу, и выданным заказом; письмо с ключом принято почтой и не доставлено. Словарь или None."""
    stubs = ctx.stubs
    data = A.new_buyer(ctx.kc, ctx.admin, ctx.stamp, tag)
    if not data:
        return None
    phone, sub = AU.confirmed_phone(ctx, data)
    if not phone:
        return None
    buyer = O.Buyer(ctx.kc, data)
    if not expect('%s: в новом токене покупателя phone_verified = true' % tag, A.eventually(lambda: buyer.claims().get('phone_verified') is True, wait=30, step=2.0), ''):
        return None
    with stubs.email_mode(status='none'):
        created = O.create_order(buyer, ctx.fx['plenty'], 1)
        body = SU.body_of(created)
        if not expect('%s: заказ на один ключ оформлен: 201' % tag, created.status == 201 and body.get('orderId'), O.describe(created)):
            return None
        issued, order = O.wait_order(buyer, body['orderId'], 'issued', wait=90)
        letters = P.wait_letters(stubs, to=data['email'], sender='delivery')
    if not expect('%s: заказ выдан, письмо с ключом принято почтой и без статуса доставки' % tag, issued and len(letters) == 1 and letters[0].get('status') == 'accepted',
                  '%s %s' % (order.get('status'), [m.get('status') for m in letters])):
        return None
    ctx.fx['emails'].append(data['email'])
    ctx.addresses.append(data['email'])
    return {'buyer': buyer, 'data': data, 'phone': phone, 'sub': sub, 'oid': order['orderId'], 'number': order['orderNumber'], 'email': data['email'],
            'keys': P.KEY_LINE.findall(P.body_of(letters[0]))}


def sms_session(ctx, person):
    """Вход по коду из SMS: токены или None."""
    seen = len(S.sms_items(ctx.kc.net, person['phone']))
    result = ctx.kc.login_sms('dgm-sms', person['phone'], lambda: S.next_code(ctx.kc.net, person['phone'], seen))
    if not expect('Вход по SMS (клиент dgm-sms): токены выданы', result.ok, S.describe(result)):
        return None
    claims = result.access()
    expect('Сессия по SMS: amr = [sms], области orders.read и support.write, срок не больше 15 минут, refresh-токена нет',
           claims.get('amr') == ['sms'] and {'orders.read', 'support.write'} <= set((claims.get('scope') or '').split()) and 0 < claims.get('exp', 0) - claims.get('iat', 0) <= 900
           and 'refresh_token' not in result.tokens, str(claims.get('amr')))
    return result.tokens['access_token']


def new_codes(ctx, phone, before):
    """Коды из SMS, пришедших на номер после `before` сообщений: заглушка принимает сообщение чуть позже ответа платформы."""
    try:
        items = ctx.stubs.wait_sms(phone, count=before + 1, everything=True)
    except Exception:   # noqa: BLE001 - SMS не пришло: это ошибка проверки, дальше код брать неоткуда
        return None
    code = ctx.stubs.sms_code(items[-1])
    if code:
        ctx.secrets.append(code)
    return code


def sms_count(ctx, phone):
    return len(ctx.stubs.sms(to=phone, everything=True))


def operator_start(ctx, tid, new_email):
    answer = SU.post_json(ctx.operator.token(), '%s/%s/email-change' % (STAFF, tid), {'newEmail': new_email})
    ctx.seen.append(answer.text)
    return answer


def operator_state(ctx, tid):
    answer = SU.get(ctx.operator.token(), '%s/%s/email-change' % (STAFF, tid))
    ctx.seen.append(answer.text)
    return answer


def take(ctx, tid):
    answer = SU.post_json(ctx.operator.token(), '%s/%s/take' % (STAFF, tid))
    ctx.seen.append(answer.text)
    return answer


def create_ticket(ctx, who, token):
    created = SU.post_json(token, TICKETS, {'orderId': who['oid'], 'reason': 'key_not_received'})
    return created, SU.body_of(created)


def verify(token, tid, step, code):
    return SU.post_json(token, '%s/%s/email-change/verifications' % (TICKETS, tid), {'step': step, 'code': code})


def request_code(token, tid, step):
    return SU.post_json(token, '%s/%s/email-change/codes' % (TICKETS, tid), {'step': step})


# --------------------------------------------------------------------------------------------------- TC-016, TC-720
def happy_path(ctx):
    stubs = ctx.stubs
    print('== Смена e-mail через поддержку: покупатель входит по SMS, оператор ведёт смену, ключ уходит на новый адрес (US-1.7, US-7.4, FT-7.5; TC-016)')
    stubs.reset()
    who = prepared_buyer(ctx, 'ec1')
    if not who:
        return
    token = sms_session(ctx, who)
    if not token:
        return
    old_email = who['email']
    new_email = 'ec-new-%d@example.test' % ctx.stamp
    ctx.addresses.append(new_email)
    ctx.fx['emails'].append(new_email)

    print('   Сессия по SMS: что разрешено и что закрыто (US-1.7, FT-1.7; TC-720)')
    listed = G.call('GET', '%s?status=issued&limit=100' % O.ORDERS, headers=G.bearer(token))
    one = G.call('GET', '%s/%s' % (O.ORDERS, who['oid']), headers=G.bearer(token))
    expect('TC-720. Сессия по SMS читает заказы: список и заказ 200, заказ issued', listed.status == 200 and one.status == 200 and SU.body_of(one).get('status') == 'issued',
           '%s %s' % (O.describe(listed), O.describe(one)))
    other = G.call('GET', '%s/%s' % (O.ORDERS, uuid.uuid4()), headers=G.bearer(token))
    expect('TC-720. Чужой и несуществующий заказ по SMS-сессии: 404', other.status == 404, O.describe(other))
    denied = {
        'оформление заказа': S.api('POST', O.ORDERS, token, {'productId': ctx.fx['plenty'], 'quantity': 1}),
        'платёжная сессия': G.call('GET', '%s/%s/payment-session' % (O.ORDERS, who['oid']), headers=G.bearer(token)),
        'подтверждение телефона': S.api('POST', '/api/v1/phone-confirmations', token, {'phone': S.fresh_phone()}),
        'заявка продавца': S.api('POST', '/api/v1/seller-applications', token, {}),
        'очередь обращений оператора': G.call('GET', STAFF, headers=G.bearer(token)),
        'кабинет сотрудников': G.call('GET', '/api/v1/staff/users', headers=G.bearer(token)),
    }
    for name, answer in denied.items():
        expect('TC-720. Сессия по SMS: %s закрыто (403)' % name, answer.status == 403, O.describe(answer))

    created, ticket = create_ticket(ctx, who, token)
    if not expect('TC-720. Сессия по SMS создаёт обращение «Не получил ключ»: 201, created', created.status == 201 and ticket.get('status') == 'created', O.describe(created)):
        return
    tid = ticket['ticketId']
    mine = SU.get(token, '%s/%s' % (TICKETS, tid))
    expect('TC-720. Сессия по SMS читает своё обращение и список обращений: 200', mine.status == 200 and SU.get(token, TICKETS).status == 200, O.describe(mine))

    found = SU.staff_get(SimpleOperator(ctx), '?orderNumber=%d' % who['number'])
    ctx.seen.append(found.text)
    taken = take(ctx, tid)
    expect('TC-016. Оператор нашёл обращение по номеру заказа и взял его в работу: 200, in_progress',
           any(t.get('ticketId') == tid for t in (SU.body_of(found).get('items') or [])) and taken.status == 200 and SU.body_of(taken).get('status') == 'in_progress',
           '%s %s' % (O.describe(found), O.describe(taken)))
    before = sms_count(ctx, who['phone'])
    started = operator_start(ctx, tid, new_email)
    state = SU.body_of(started)
    expect('TC-016. Оператор назвал новый адрес: 201, ждём код из SMS, проверка телефона pending, новый адрес показан только маской',
           started.status == 201 and state.get('status') == 'awaiting_phone_code' and state.get('phoneCheck') == 'pending' and state.get('newEmailCheck') == 'not_started'
           and '***@' in str(state.get('newEmailMasked')) and new_email not in started.text, O.describe(started))
    phone_code = new_codes(ctx, who['phone'], before)
    if not expect('TC-016. SMS с кодом пришло на подтверждённый номер покупателя (шесть цифр)', phone_code and SIX_DIGITS.fullmatch(phone_code), str(phone_code)):
        return
    mails_before = len(stubs.emails(to=new_email, subject=CODE_SUBJECT))
    expect('TC-016. До верного кода из SMS письмо с кодом на новый адрес не уходит', mails_before == 0, str(mails_before))
    wrong = verify(token, tid, 'new_email', phone_code)
    expect('TC-016. Код из письма до проверки телефона не принимается: 409 state-conflict', wrong.status == 409 and wrong.code() == 'state-conflict', O.describe(wrong))
    passed = verify(token, tid, 'phone', phone_code)
    state = SU.body_of(passed)
    expect('TC-016. Верный код из SMS принят: 200, ждём код из письма, телефон passed, проверка адреса pending',
           passed.status == 200 and state.get('status') == 'awaiting_email_code' and state.get('phoneCheck') == 'passed' and state.get('newEmailCheck') == 'pending', O.describe(passed))
    try:
        letters = stubs.wait_email(to=new_email, subject=CODE_SUBJECT)
    except Exception as e:   # noqa: BLE001 - письма нет: дальше код брать неоткуда
        G.bad('TC-016. Письмо с кодом на новый адрес не пришло', str(e)[-200:])
        return
    email_code = (SIX_DIGITS.search(P.body_of(letters[0])) or [None, None])[1]
    ctx.secrets.append(email_code or '')
    expect('TC-016. Письмо с кодом на новый адрес одно, в нём шесть цифр, письма на прежний адрес с кодом нет',
           len(letters) == 1 and bool(email_code) and not stubs.emails(to=old_email, subject=CODE_SUBJECT), str([m.get('subject') for m in letters]))
    check = operator_state(ctx, tid)
    expect('TC-016. Оператор видит только результат проверок (телефон passed, адрес pending), кодов в ответе нет',
           check.status == 200 and SU.body_of(check).get('phoneCheck') == 'passed' and SU.body_of(check).get('newEmailCheck') == 'pending', O.describe(check))
    done = verify(token, tid, 'new_email', email_code or '000000')
    state = SU.body_of(done)
    finished = done.status == 200 and (state.get('status') == 'completed' or A.eventually(lambda: ((SU.body_of(SU.get(token, '%s/%s' % (TICKETS, tid))).get('emailChange') or {})
                                                                                               .get('status')) == 'completed', wait=90, step=3.0))
    expect('TC-016. Верный код из письма принят, смена завершена (completed; если Keycloak ответил не сразу, её доводит сверка)', bool(finished), O.describe(done))

    print('   Результат смены: Keycloak, запись платформы, письма, ключ на новый адрес')
    moved = A.eventually(lambda: (A.keycloak_user(ctx.admin, new_email) or {}).get('id') == who['data']['id'], wait=20, step=2.0)
    expect('TC-016. В Keycloak у того же пользователя новый e-mail, прежний адрес свободен', bool(moved) and A.keycloak_user(ctx.admin, old_email) is None, '')
    rows = O.sql('platform_db', "select count(*) filter (where lower(email) = lower('%s')), count(*) filter (where lower(email) = lower('%s')) "
                                "from identity.user_account where id = '%s'" % (new_email, old_email, who['sub']))
    expect('TC-016. В записи платформы новый адрес, прежнего нет', rows == [['1', '0']], str(rows))
    stored = O.sql('platform_db', "select status, phone_check, new_email_check, coalesce(new_email, ''), new_email_masked from support.email_change where ticket_id = '%s'" % tid)
    expect('TC-016. Этап смены completed, обе проверки passed, новый адрес в записи обнулён, остаётся маска (INV-44)',
           bool(stored) and stored[0][:3] == ['completed', 'passed', 'passed'] and stored[0][3] == '' and '***@' in stored[0][4], str(stored))
    new_letters = P.wait_letters(stubs, wait=60, to=new_email, subject=NEW_SUBJECT)
    old_letters = P.wait_letters(stubs, wait=60, to=old_email, subject=PREVIOUS_SUBJECT)
    expect('TC-016. Письма о смене: одно «%s» на новый адрес, одно «%s» на прежний; кода и ключа в них нет' % (NEW_SUBJECT, PREVIOUS_SUBJECT),
           len(new_letters) == 1 and len(old_letters) == 1 and not any(P.KEY_PREFIX in P.body_of(m) or phone_code in P.body_of(m) for m in new_letters + old_letters),
           '%d и %d' % (len(new_letters), len(old_letters)))
    keys = P.wait_letters(stubs, wait=90, to=new_email, sender='delivery')
    expect('TC-016. Ключ ушёл на новый адрес одним письмом и тот же, что в первом (новый ключ не выпускался)',
           len(keys) == 1 and P.KEY_LINE.findall(P.body_of(keys[0])) == who['keys'], str([m.get('subject') for m in keys]))
    # письмо с ключом уже видно в заглушке, а статус «доставлено» платформа получает вебхуком чуть позже: ждём, а не читаем один раз (F4-71)
    A.eventually(lambda: [(r['kind'], r['status']) for r in (P.deliveries_of(who['oid']) or [])] == [('primary', 'sent'), ('repeat', 'delivered')], wait=30, step=2.0)
    rows = P.deliveries_of(who['oid']) or []
    expect('TC-016. Выдач две: первичная sent на прежний адрес и повторная repeat на новый, повторная доставлена',
           [(r['kind'], r['status']) for r in rows] == [('primary', 'sent'), ('repeat', 'delivered')] and rows[0]['address'] == old_email and rows[1]['address'] == new_email,
           str([(r['kind'], r['status'], r['address']) for r in rows])[:300])
    solved = A.eventually(lambda: [t[2:] for t in (P.tickets_of(who['oid']) or [])] == [('resolved', 'system')], wait=SU.RESOLVE_SECONDS, step=2.0)
    resolution = SU.body_of(SU.get(token, '%s/%s' % (TICKETS, tid)))
    expect('TC-016. Обращение решено доставкой: resolved, решила система, смена e-mail в карточке completed, оба результата passed',
           bool(solved) and resolution.get('status') == 'resolved' and (resolution.get('resolution') or {}).get('resolvedBy') == 'system'
           and (resolution.get('resolution') or {}).get('action') in ('resend', 'email_change')
           and (resolution.get('emailChange') or {}).get('status') == 'completed' and (resolution.get('emailChange') or {}).get('newEmailCheck') == 'passed',
           str(resolution)[:300])

    print('   Журнал аудита: решение оператора и завершение смены, адреса только хешами (FT-11.3, ADR-014)')
    ticket_records = journal_by(ctx, objectType='ticket', objectId=tid)
    user_records = journal_by(ctx, objectType='user', objectId=who['sub'])
    started_record = next((r for r in ticket_records if r.get('action') == 'support.email_change_started'), None)
    completed_record = next((r for r in user_records if r.get('action') == 'support.email_change_completed'), None)
    operator_sub = AU.claims_of(ctx.operator.token())['sub']
    expect('TC-016. Запись support.email_change_started: исполнитель support-1, роль support-operator, объект ticket; нового адреса в записи нет',
           started_record is not None and started_record.get('actorId') == operator_sub and started_record.get('actorRole') == SU.OPERATOR_ROLE
           and new_email not in json.dumps(started_record), str(started_record)[:300])
    change = AU.change_of(completed_record or {}, 'email') or {}
    expect('TC-016. Запись support.email_change_completed: исполнитель support-1, адрес персональный, до и после только хеши из 64 знаков',
           completed_record is not None and completed_record.get('actorId') == operator_sub and change.get('personal') is True and AU.HASH.match(str(change.get('before'))) is not None
           and AU.HASH.match(str(change.get('after'))) is not None, str(completed_record)[:300])
    ctx.fx['ec_done'] = {'old': old_email, 'new': new_email, 'sub': who['sub'], 'tid': tid, 'oid': who['oid']}
    expect('TC-016. Во всех ответах оператора (%d) нет кодов подтверждения, адресов покупателя и ключа (INV-23)' % len(ctx.seen),
           not leaked(ctx.seen, [phone_code, email_code or 'нет-кода', old_email, new_email, P.KEY_PREFIX]), '')
    P.email_is_default(stubs, 'TC-016')


class SimpleOperator:
    """Оператор под интерфейсом, нужным staff_get: только token()."""

    def __init__(self, ctx):
        self.ctx = ctx

    def token(self):
        return self.ctx.operator.token()


def leaked(texts, needles):
    return [n for n in needles if n and any(n in t for t in texts)]


def journal_by(ctx, **query):
    token = ctx.administrator.token()
    A.eventually(lambda: bool(AU.items_of(AU.journal(token, limit=100, **query))), wait=AU.WAIT, step=1.0)
    return AU.items_of(AU.journal(token, limit=100, **query))


# --------------------------------------------------------------------------------------------------- отказы и пределы
def refusals(ctx):
    stubs = ctx.stubs
    print('== Смена e-mail: нейтральный отказ, неверные коды, пятая неудача, четвёртый запрос кода, чужое и возвращённое обращение (INV-34, ST-05; TC-016)')
    who = prepared_buyer(ctx, 'ec2')
    if not who:
        return
    token = who['buyer'].token()
    created, ticket = create_ticket(ctx, who, token)
    if not expect('Обращение создано: 201', created.status == 201, O.describe(created)):
        return
    tid = ticket['ticketId']
    if not expect('Оператор взял обращение: 200', take(ctx, tid).status == 200):
        return
    stranger = P.new_buyer(ctx.kc, ctx.admin, ctx.stamp, 'ec3')     # его адрес платформа знает: он и есть «занятый»
    before = sms_count(ctx, who['phone'])
    busy = operator_start(ctx, tid, stranger.email if stranger else who['email'])
    own = operator_start(ctx, tid, who['email'])
    shape = ('type', 'title', 'status', 'detail', 'code')
    expect('INV-34. Занятый адрес и собственный адрес покупателя: один и тот же нейтральный ответ 409 email-not-allowed, смена не начата',
           busy.status == 409 and own.status == 409 and busy.code() == own.code() == 'email-not-allowed'
           and [SU.body_of(busy).get(k) for k in shape] == [SU.body_of(own).get(k) for k in shape], '%s | %s' % (O.describe(busy), O.describe(own)))
    expect('INV-34. После отказа SMS не ушло, состояния смены нет (404)', sms_count(ctx, who['phone']) == before and operator_state(ctx, tid).status == 404,
           '%d -> %d' % (before, sms_count(ctx, who['phone'])))

    first_address = 'ec-first-%d@example.test' % ctx.stamp
    ctx.addresses.append(first_address)
    started = operator_start(ctx, tid, first_address)
    code = new_codes(ctx, who['phone'], before)
    if not expect('Смена начата, SMS с кодом пришло: 201', started.status == 201 and code, O.describe(started)):
        return
    wrong = S.other_code(code)
    left = []
    for _ in range(4):
        answer = verify(token, tid, 'phone', wrong)
        left.append((answer.status, answer.code(), SU.body_of(answer).get('attemptsLeft')))
    state = operator_state(ctx, tid)
    expect('ST-05. Четыре неверных кода: 422 otp-invalid и attemptsLeft 4, 3, 2, 1; этап не меняется (ждём код из SMS)',
           left == [(422, 'otp-invalid', 4), (422, 'otp-invalid', 3), (422, 'otp-invalid', 2), (422, 'otp-invalid', 1)]
           and SU.body_of(state).get('status') == 'awaiting_phone_code' and SU.body_of(state).get('phoneCheck') == 'pending', '%s %s' % (left, O.describe(state)))
    fifth = verify(token, tid, 'phone', wrong)
    retry = fifth.header('Retry-After')
    expect('ST-05. Пятая неудача: 429 otp-locked с Retry-After', fifth.status == 429 and fifth.code() == 'otp-locked' and bool(retry) and retry.isdigit(), '%s retry=%s' % (O.describe(fifth), retry))
    state = operator_state(ctx, tid)
    body = SU.body_of(state)
    expect('ST-05. Смена failed: телефон failed, проверка адреса не начата, адрес показан только маской; обращение осталось в работе',
           state.status == 200 and body.get('status') == 'failed' and body.get('phoneCheck') == 'failed' and body.get('newEmailCheck') == 'not_started'
           and first_address not in state.text and P.tickets_of(who['oid']) == [('buyer', 'key_not_received', 'in_progress', '')], '%s %s' % (O.describe(state), P.tickets_of(who['oid'])))
    stored = O.sql('platform_db', "select status, coalesce(new_email, ''), new_email_masked from support.email_change where ticket_id = '%s'" % tid)
    expect('ST-05. В записи смены адрес обнулён, маска осталась (INV-44)', bool(stored) and stored[0][0] == 'failed' and stored[0][1] == '' and '***@' in stored[0][2], str(stored))
    sixth = verify(token, tid, 'phone', code)
    expect('ST-05. После пятой неудачи верный код не принимается: 409 state-conflict (этап закрыт, код погашен)', sixth.status == 409 and sixth.code() == 'state-conflict', O.describe(sixth))
    expect('ST-05. Письма с кодом на новый адрес не было: пятая неудача его не отправила', not stubs.emails(to=first_address), '')

    second_address = 'ec-second-%d@example.test' % ctx.stamp
    ctx.addresses.append(second_address)
    before = sms_count(ctx, who['phone'])
    restarted = operator_start(ctx, tid, second_address)
    again = new_codes(ctx, who['phone'], before)
    expect('ST-05. Оператор начал смену заново, попытка заменила прежнюю: 201, awaiting_phone_code, новый код из SMS', restarted.status == 201 and again
           and SU.body_of(restarted).get('status') == 'awaiting_phone_code' and SU.body_of(restarted).get('phoneCheck') == 'pending', O.describe(restarted))
    order = verify(token, tid, 'new_email', again or '000000')
    expect('Этап вне порядка: код из письма до проверки телефона 409 state-conflict', order.status == 409 and order.code() == 'state-conflict', O.describe(order))
    early = request_code(token, tid, 'new_email')
    expect('Этап вне порядка: код на новый адрес до проверки телефона 409 state-conflict', early.status == 409 and early.code() == 'state-conflict', O.describe(early))
    if stranger:
        foreign = [request_code(stranger.token(), tid, 'phone'), verify(stranger.token(), tid, 'phone', again or '000000')]
        expect('Чужое обращение: запрос кода и ввод кода 404', [a.status for a in foreign] == [404, 404], str([O.describe(a) for a in foreign]))
        ctx.fx['emails'].append(stranger.email)

    before = sms_count(ctx, who['phone'])
    limited = request_code(token, tid, 'phone')
    retry = limited.header('Retry-After')
    expect('ST-05. Четвёртый запрос кода на номер за 10 минут (подтверждение, начало, повторное начало, запрос): 429 otp-request-limited с Retry-After',
           limited.status == 429 and limited.code() == 'otp-request-limited' and bool(retry) and retry.isdigit(), '%s retry=%s' % (O.describe(limited), retry))
    time.sleep(2)
    expect('ST-05. Четвёртое SMS не отправлено', sms_count(ctx, who['phone']) == before, '%d -> %d' % (before, sms_count(ctx, who['phone'])))

    released = SU.post_json(ctx.operator.token(), '%s/%s/release' % (STAFF, tid))
    after = request_code(token, tid, 'phone')
    expect('Обращение возвращено в очередь: покупателю запрос кода 409 state-conflict, оператору состояние смены 404',
           released.status == 200 and after.status == 409 and after.code() == 'state-conflict' and operator_state(ctx, tid).status == 404,
           '%s | %s' % (O.describe(released), O.describe(after)))
    expect('Все ответы оператора (%d) без кодов и адресов покупателя' % len(ctx.seen), not leaked(ctx.seen, [code, again or 'нет-кода', who['email'], first_address, second_address]), '')


# --------------------------------------------------------------------------------------------------- канарейка
def dump(schema):
    r = C.sh(G.COMPOSE + ['exec', '-T', 'postgres', 'pg_dump', '-U', 'postgres', '-d', 'platform_db', '--column-inserts', '--no-owner', '-n', schema], timeout=300)
    return r.stdout, r.returncode, (r.stderr or '')[-200:]


def canary(ctx):
    print('== Канарейка: прежний и новый адрес и коды подтверждения не попадают в журналы, дампы, темы Kafka и ответы API (INV-44, NFT-5.3, ADR-014)')
    done = ctx.fx.get('ec_done')
    if not done:
        G.bad('Контроль: смена e-mail не завершена, канарейку проверить нельзя')
        return
    wanted = []
    for address in (done['old'], done['new']):
        wanted += AU.variants(address) + [address.split('@')[0]]
    codes = [c for c in ctx.secrets if c]
    answers = [AU.journal(ctx.administrator.token(), objectId=done['tid'], limit=100), AU.journal(ctx.administrator.token(), objectId=done['sub'], limit=100)]
    mine = [r for a in answers for r in AU.items_of(a)]
    expect('Контроль: журнал по обращению и пользователю содержит записи смены (%d)' % len(mine),
           {'support.email_change_started', 'support.email_change_completed'} <= {r.get('action') for r in mine}, str(sorted({r.get('action') for r in mine})))
    found = C.leaks('\n'.join(a.text for a in answers), wanted + codes)
    expect('В ответах API журнала адресов и кодов нет', not found, str(found[:2]))
    for schema in ('audit_admin', 'support', 'notification'):
        text, code, error = dump(schema)
        expect('Дамп схемы %s снят (%d КБ)' % (schema, len(text) // 1024), code == 0 and len(text) > 1000, error)
        found = C.leaks(text, wanted + codes)
        expect('В схеме %s адресов и кодов нет' % schema, not found, str(found[:2]))
    try:
        topics = P.read_topics(['audit.events', 'support.events', 'identity.events'])
    except Exception as e:   # noqa: BLE001 - темы не прочитаны: это ошибка проверки, а не повод её пропустить
        G.bad('Темы Kafka не прочитаны', str(e)[-300:])
        return
    for name, messages in topics.items():
        wire = '\n'.join(m.value for m in messages)
        control = any(done['tid'] in m.value or done['sub'] in m.value for m in messages)
        expect('Контроль: в теме %s (%d записей, %d КБ) есть события смены e-mail' % (name, len(messages), len(wire) // 1024), control, '')
        found = C.leaks(wire, wanted + codes)
        expect('В теме %s адресов и кодов нет' % name, not found, str(found[:2]))
    for service in ('platform-service', 'api-gateway'):
        out = C.sh(G.COMPOSE + ['logs', '--no-color', service], timeout=300)
        text = out.stdout + out.stderr
        expect('Контроль: журнал %s (%d КБ) настоящий, в нём виден идентификатор обращения или пользователя' % (service, len(text) // 1024),
               len(text) > 2000 and (done['tid'] in text or done['sub'] in text or service == 'api-gateway'), '')
        found = C.leaks(text, wanted + codes)
        expect('В журнале %s адресов и кодов нет' % service, not found, str(found[:2]))


# --------------------------------------------------------------------------------------------------- запуск
def main():
    kc, admin = K.from_env(dict(os.environ))
    people = G.users()
    for name in ('moderator-1', 'admin-1', SU.OPERATOR, 'buyer-1'):
        if name not in people:
            print('Нет тестового пользователя %s (make keycloak-users)' % name, file=sys.stderr)
            return 2
    fx = O.context(kc, admin, people, int(time.time()), scarce_needed=False)
    if not fx:
        G.bad('Продавец и товары для заказов не заведены, сценарии смены e-mail не запускались')
        return O.summary('email_change_checks')
    fx['orders'] = []
    ctx = Context(kc, admin, people, int(time.time()), fx)
    ctx.operator.token()
    ctx.administrator.token()
    try:
        if O.guarded('raise_ip_limit', raise_ip_limit, ctx):
            for step in (happy_path, refusals, canary):
                O.guarded(step.__name__, step, ctx)
        else:
            G.bad('Лимит с одного IP не поднят, сценарии смены e-mail не запускались')
    finally:
        O.guarded('restore_ip_limit', restore_ip_limit, ctx)
    return O.summary('email_change_checks')


if __name__ == '__main__':
    sys.exit(main())
