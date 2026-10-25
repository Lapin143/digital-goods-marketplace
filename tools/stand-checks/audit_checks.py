#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Журнал аудита сквозь стенд (шаг 8 среза 3 Ф4, К9; US-8.7, FT-11.3, NFT-3.6, NFT-5.3, ST-09, INV-42; TC-815, TC-802, TC-815).

    make audit-check

Нужен набор full с отладочным файлом: make up SET=full DEBUG=1, затем make keycloak-users (в CI после проверок покупки, до итога замеров памяти).
Решает moderator-1, журнал читает admin-1, оба настоящим входом с TOTP. Одноразовые покупатели регистрируются через страницу регистрации.
Что проверяется:

  решения     заявка продавца: отказ, одобрение и открытие документа модератором (US-2.2, US-2.3) попадают в журнал за 10 секунд: исполнитель,
              роль, тип и идентификатор объекта, статус до и после, причина, адрес исполнителя как есть, сквозной идентификатор запроса;
              товары фикстуры (публикация модератором на шаге 13 среза 1) найдены в журнале по идентификатору товара
  просмотр    открытие журнала администратором записывается как audit.viewed (тип audit-log, без параметров поиска), запись видна при
              следующем чтении; журнал отдаётся без кэша, записи идут от новых к старым, страницы листаются курсором
  права       модератор и покупатель получают 403, без токена 401, ошибки в параметрах 400 или 422; операций записи в API журнала нет:
              POST, PUT, PATCH и DELETE по адресу журнала и записи не проходят (ST-09, INV-42)
  неизменность  роли приложения (app_audit_writer, app_audit_admin) под SET ROLE не меняют и не удаляют записи: permission denied, а запись
              читается через API так же, как до попыток (на стенде, поверх проверки прав db-check)
  канарейка   TC-802: уникальные e-mail и телефон покупателя проходят подтверждение телефона, деактивацию и анонимизацию; в таблице
              audit_admin (дамп), в теме audit.events и в ответах API журнала открытых значений нет ни как есть, ни в hex и Base64.
              У каждого поиска есть контроль: записи этого покупателя в дампе, теме и ответах обязаны найтись

Хеш персонального поля считает владелец данных, журнал открытого значения не знает (ADR-014). Модульный и интеграционный уровни те же
случаи проверяют в AuditViewServiceTest, AuditControllerTest, PlatformServiceIT. Только стандартная библиотека Python.
"""
import base64
import json
import os
import re
import sys
import time
import urllib.parse
import uuid
from datetime import datetime, timedelta, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import accounts_checks as A  # noqa: E402
import canary_checks as C  # noqa: E402
import gateway_checks as G  # noqa: E402
import kc_client as K  # noqa: E402
import orders_checks as O  # noqa: E402
import purchase_checks as P  # noqa: E402
import seller_approval_checks as SA  # noqa: E402
import sellers_checks as SC  # noqa: E402
import sms_login_checks as S  # noqa: E402

expect = G.expect
JOURNAL = '/api/v1/staff/audit-records'
WAIT = 10.0   # запись о действии видна в журнале не позже, чем через столько секунд (Р-165 среза 3)
APP_ROLES = ('app_audit_writer', 'app_audit_admin')
HASH = re.compile(r'^[0-9a-f]{64}$')


# --------------------------------------------------------------------------------------------------- вспомогательное
def claims_of(token):
    payload = token.split('.')[1]
    return json.loads(base64.urlsafe_b64decode(payload + '=' * (-len(payload) % 4)))


def stamp_of(moment):
    return moment.astimezone(timezone.utc).strftime('%Y-%m-%dT%H:%M:%S.') + '%03dZ' % (moment.microsecond // 1000)


def instant(text):
    return datetime.fromisoformat(text.replace('Z', '+00:00'))


def journal(token, **query):
    """Чтение журнала через шлюз; параметры со значением None не передаются."""
    wanted = {k: v for k, v in query.items() if v is not None}
    path = JOURNAL + ('?' + urllib.parse.urlencode(wanted) if wanted else '')
    return G.call('GET', path, headers=SC.auth(token) if token else {})


def items_of(response):
    try:
        return (response.json().get('items') or []) if response.status == 200 else []
    except ValueError:
        return []


def wait_record(token, seconds=WAIT, **query):
    """(запись, секунд до её появления): журнал читается раз в секунду, пока запись по фильтру не найдётся или не выйдет срок."""
    started, box = time.time(), {}

    def seen():
        box['items'] = items_of(journal(token, limit=100, **query))
        return bool(box['items'])
    found = A.eventually(seen, wait=seconds, step=1.0)
    return (box['items'][0] if found else None), time.time() - started


def change_of(record, field):
    for change in record.get('changes') or []:
        if change.get('field') == field:
            return change
    return None


def decision_headers(token, correlation):
    return SC.auth(token, **{'Content-Type': 'application/json', 'Idempotency-Key': str(uuid.uuid4()), 'X-Correlation-Id': correlation})


def decide(moderator, seller_id, decision, body, correlation):
    return G.call('POST', '%s/%s/%s' % (SA.QUEUE, seller_id, decision), headers=decision_headers(moderator.token(), correlation),
                  body=json.dumps(body).encode() if body is not None else None)


def variants(value):
    """Значение как есть, в записи hex и в Base64 (обычном и для адресов): так его мог бы унести сериализатор или кодировщик."""
    raw = value.encode()
    return [value, raw.hex(), base64.b64encode(raw).decode(), base64.urlsafe_b64encode(raw).decode().rstrip('=')]


def sql(statement, role=None):
    """(код возврата, вывод, ошибка) запроса к platform_db от имени postgres; с role выполняется под SET ROLE в транзакции, которая откатывается."""
    text = 'begin; set local role %s; %s; rollback' % (role, statement) if role else statement
    r = C.sh(G.COMPOSE + ['exec', '-T', 'postgres', 'psql', '-U', 'postgres', '-d', 'platform_db', '-v', 'ON_ERROR_STOP=1', '-tA', '-c', text], timeout=60)
    return r.returncode, r.stdout, r.stderr


def describe(response):
    return '%s %s' % (response.status, response.text[:200])


class Context:
    def __init__(self, kc, admin, people, stamp):
        self.kc, self.admin, self.people, self.stamp = kc, admin, people, stamp
        self.moderator = SA.Staff(kc, admin, people['moderator-1'])
        self.administrator = SA.Staff(kc, admin, people['admin-1'])
        self.started = datetime.now(timezone.utc) - timedelta(seconds=2)   # записи этого прогона не раньше этого момента
        self.sellers = {}


# --------------------------------------------------------------------------------------------------- решения модератора
def check_decision(name, record, took, seller_id, moderator_sub, correlation, before, after, reason=None):
    expect('TC-815. %s: запись в журнале через %.1f с (предел %d с)' % (name, took, WAIT), record is not None and took <= WAIT + 3, 'записи нет за %.1f с' % took)
    if record is None:
        return
    status = change_of(record, 'status') or {}
    expect('TC-815. %s: исполнитель moderator-1 (sub), роль moderator, объект seller и идентификатор заявки' % name,
           record.get('actorId') == moderator_sub and record.get('actorRole') == 'moderator' and record.get('objectType') == 'seller'
           and record.get('objectId') == seller_id, str(record)[:300])
    expect('TC-815. %s: статус до и после (%s, %s), поле не персональное' % (name, before, after),
           status.get('before') == before and status.get('after') == after and status.get('personal') is False, str(status))
    if reason is not None:
        why = change_of(record, 'decisionReason') or {}
        expect('TC-815. %s: причина решения в журнале как есть (она не персональная, ADR-014)' % name, why.get('after') == reason and why.get('personal') is False, str(why))
    expect('TC-815. %s: адрес исполнителя записан как есть, не хеш (сотрудник)' % name, bool(record.get('actorIp')) and record.get('actorIpHashed') is False,
           '%s %s' % (record.get('actorIp'), record.get('actorIpHashed')))
    expect('TC-815. %s: сквозной идентификатор запроса из заголовка X-Correlation-Id сохранён в записи (NFT-6.0)' % name, record.get('correlationId') == correlation,
           '%s вместо %s' % (record.get('correlationId'), correlation))


def seller_decisions(ctx):
    print('== Решения модератора по заявке продавца доходят до журнала за %d секунд (US-8.7, FT-11.3; TC-815)' % WAIT)
    for_rejection = SA.applicant(ctx.kc, ctx.admin, ctx.stamp, 'aur')
    for_approval = SA.applicant(ctx.kc, ctx.admin, ctx.stamp, 'aua')
    if not (for_rejection and for_approval):
        return
    ctx.sellers = {'rejected': for_rejection, 'approved': for_approval}
    moderator_sub = claims_of(ctx.moderator.token())['sub']
    admin_token = ctx.administrator.token()
    since = stamp_of(ctx.started)

    card = G.call('GET', '%s/%s' % (SA.QUEUE, for_approval['sellerId']), headers=SC.auth(ctx.moderator.token()))
    documents = (card.json().get('documents') or []) if card.status == 200 else []
    expect('Карточка заявки открыта модератором: 200 и один документ со ссылкой', card.status == 200 and len(documents) == 1 and (documents[0].get('link') or {}).get('url'), describe(card))
    if documents:
        record, took = wait_record(admin_token, action='seller.document_opened', objectType='seller', objectId=for_approval['sellerId'], occurredFrom=since)
        expect('TC-815. Выдача ссылки на документ записана в журнале через %.1f с' % took, record is not None, 'записи нет')
        if record:
            opened = change_of(record, 'documentId') or {}
            expect('TC-815. В записи только идентификатор документа (до: пусто), роль moderator, объект seller; ссылки и содержимого нет',
                   opened.get('before') is None and opened.get('after') == documents[0].get('documentId') and record.get('actorRole') == 'moderator'
                   and (documents[0]['link']['url'] not in json.dumps(record)), str(record)[:300])

    reason = 'Скан паспорта не читается, загрузите чёткий снимок'
    correlation = str(uuid.uuid4())
    rejected = decide(ctx.moderator, for_rejection['sellerId'], 'reject', {'reason': reason}, correlation)
    if expect('Отказ по заявке: 200', rejected.status == 200, describe(rejected)):
        record, took = wait_record(admin_token, action='seller.rejected', objectType='seller', objectId=for_rejection['sellerId'], occurredFrom=since)
        check_decision('Отказ', record, took, for_rejection['sellerId'], moderator_sub, correlation, 'on_review', 'rejected', reason)

    correlation = str(uuid.uuid4())
    approved = decide(ctx.moderator, for_approval['sellerId'], 'approve', None, correlation)
    if expect('Одобрение заявки: 200', approved.status == 200, describe(approved)):
        record, took = wait_record(admin_token, action='seller.approved', objectType='seller', objectId=for_approval['sellerId'], occurredFrom=since)
        check_decision('Одобрение', record, took, for_approval['sellerId'], moderator_sub, correlation, 'on_review', 'approved')

    everything = items_of(journal(admin_token, objectType='seller', objectId=for_rejection['sellerId'], limit=100))
    expect('Журнал по объекту: отказ ровно один раз (событие не задвоилось), других действий по заявке кроме подачи нет',
           [r.get('action') for r in everything].count('seller.rejected') == 1, str([r.get('action') for r in everything]))


def product_decisions(ctx):
    print('== Публикация товаров модератором найдена в журнале (US-3.3, FT-11.3; TC-815)')
    fixture = O.prepare(ctx.kc, ctx.admin, ctx.people, ctx.stamp, scarce_needed=False)
    if not fixture:
        G.bad('Товары фикстуры не заведены: проверка журнала по товарам не выполнена')
        return
    admin_token = ctx.administrator.token()
    for label, product_id in (('основной товар', fixture['plenty']), ('редкий товар', fixture['scarce'])):
        found = items_of(journal(admin_token, objectType='product', objectId=product_id, action='product.published', limit=100))
        expect('TC-815. %s: публикация записана в журнале ровно один раз, роль moderator, статус после published' % label,
               len(found) == 1 and found[0].get('actorRole') == 'moderator' and (change_of(found[0], 'status') or {}).get('after') == 'published',
               str(found)[:300])


# --------------------------------------------------------------------------------------------------- просмотр журнала
def viewing(ctx):
    print('== Просмотр журнала записывается, журнал отдаётся без кэша, страницы идут от новых к старым (US-8.7; TC-815)')
    token = ctx.administrator.token()
    admin_sub = claims_of(token)['sub']
    began = datetime.now(timezone.utc) - timedelta(seconds=2)
    opened = journal(token, objectType='seller', objectId='0199e0a0-0000-4000-8000-0000000000aa')
    expect('Журнал открыт администратором с фильтром, которому ничего не отвечает: 200 и пустая страница', opened.status == 200 and items_of(opened) == [], describe(opened))
    expect('Журнал: Cache-Control no-store и сквозной идентификатор в ответе', 'no-store' in (opened.header('Cache-Control') or '') and bool(opened.header('X-Correlation-Id')),
           '%s %s' % (opened.header('Cache-Control'), opened.header('X-Correlation-Id')))
    record, took = wait_record(token, action='audit.viewed', actorId=admin_sub, occurredFrom=stamp_of(began))
    expect('TC-815. Открытие журнала записано как audit.viewed через %.1f с: объект audit-log/journal, роль admin' % took,
           record is not None and record.get('objectType') == 'audit-log' and record.get('objectId') == 'journal' and record.get('actorRole') == 'admin',
           str(record)[:300])
    if record:
        expect('TC-815. В записи о просмотре нет параметров поиска и найденного: список изменений пуст', record.get('changes') == []
               and '0199e0a0-0000-4000-8000-0000000000aa' not in json.dumps(record), str(record)[:300])

    page = journal(token, limit=2)
    body = page.json() if page.status == 200 else {}
    items = body.get('items') or []
    times = [(i.get('occurredAt'), i.get('recordId')) for i in items]
    expect('Первая страница: 200, две записи, курсор следующей страницы есть', page.status == 200 and len(items) == 2 and (body.get('page') or {}).get('nextCursor'), describe(page))
    expect('Записи идут от новых к старым', len(times) == 2 and instant(times[0][0]) >= instant(times[1][0]), str(times))
    cursor = (body.get('page') or {}).get('nextCursor')
    if cursor:
        second = journal(token, limit=2, cursor=cursor)
        next_items = items_of(second)
        expect('Вторая страница по курсору: 200, записи другие и не новее последней записи первой страницы',
               second.status == 200 and next_items and not ({i['recordId'] for i in items} & {i['recordId'] for i in next_items})
               and instant(next_items[0]['occurredAt']) <= instant(items[-1]['occurredAt']), describe(second))
    future = journal(token, occurredFrom=stamp_of(datetime.now(timezone.utc) + timedelta(days=1)))
    expect('Диапазон в будущем: 200 и пустая страница', future.status == 200 and items_of(future) == [], describe(future))
    past = journal(token, occurredTo=stamp_of(ctx.started), limit=100)
    expect('Верхняя граница occurredTo не включается: все записи раньше начала прогона',
           past.status == 200 and all(instant(i['occurredAt']) < ctx.started for i in items_of(past)), describe(past))


# --------------------------------------------------------------------------------------------------- права и отсутствие записи
def access_and_no_writes(ctx):
    print('== Журнал читает только администратор с областью staff.audit; операций записи в API нет (OP-86, ST-09, INV-42; TC-815)')
    token = ctx.administrator.token()
    buyer_token = (ctx.sellers.get('rejected') or {}).get('token')
    moderator = journal(ctx.moderator.token())
    expect('Модератор журнал не читает: 403', moderator.status == 403 and not items_of(moderator), describe(moderator))
    if buyer_token:
        buyer = journal(buyer_token)
        expect('Покупатель журнал не читает: 403', buyer.status == 403 and not items_of(buyer), describe(buyer))
    anonymous = journal(None)
    expect('Без токена: 401', anonymous.status == 401, describe(anonymous))
    for name, query in (('limit=0', {'limit': 0}), ('limit=1000', {'limit': 1000}), ('occurredFrom не дата', {'occurredFrom': 'вчера'}),
                        ('actorId не UUID', {'actorId': 'не-uuid'}), ('курсор не разбирается', {'cursor': 'xx'})):
        bad = journal(token, **query)
        expect('Параметр %s: 400 или 422, записей в ответе нет' % name, bad.status in (400, 422) and not items_of(bad), describe(bad))
    one = items_of(journal(token, limit=1))
    record_id = one[0]['recordId'] if one else '0199e0a0-0000-7000-8000-000000000b01'
    for method in ('POST', 'PUT', 'PATCH', 'DELETE'):
        for path in (JOURNAL, '%s/%s' % (JOURNAL, record_id)):
            headers = SC.auth(token, **{'Content-Type': 'application/json', 'Idempotency-Key': str(uuid.uuid4()), 'If-Match': '"1"'})
            attempt = G.call(method, path, headers=headers, body=b'{}' if method != 'DELETE' else None)
            expect('%s %s: операции нет (404 или 405), не 2xx' % (method, path.replace(record_id, '{recordId}')), attempt.status in (404, 405), describe(attempt))


def append_only(ctx):
    print('== Роли приложения не меняют и не удаляют записи журнала на стенде (ST-09, INV-42; TC-815)')
    token = ctx.administrator.token()
    target = (ctx.sellers.get('rejected') or {}).get('sellerId')
    before = items_of(journal(token, objectType='seller', objectId=target, action='seller.rejected', limit=100)) if target else []
    code, out, error = sql('select count(*) from audit_admin.audit_log')
    total = int(out.strip() or 0) if code == 0 else 0
    expect('Контроль: суперпользователь видит записи журнала в platform_db (%d)' % total, code == 0 and total > 0, error[-200:])
    for role in APP_ROLES:
        code, out, error = sql('select count(*) from audit_admin.audit_log', role)
        expect('%s читает журнал (контроль: запрет ниже относится к изменению, а не к схеме)' % role, code == 0, error[-200:])
        for statement, word in (('update audit_admin.audit_log set changes = changes', 'UPDATE'), ("delete from audit_admin.audit_log where action = 'x'", 'DELETE')):
            code, out, error = sql(statement, role)
            expect('ST-09. %s: %s по журналу отвергнут (permission denied for table audit_log)' % (role, word), code != 0 and 'permission denied for table audit_log' in error,
                   '%s %s' % (code, error[-200:]))
    after = items_of(journal(token, objectType='seller', objectId=target, action='seller.rejected', limit=100)) if target else []
    expect('Записи в API те же, что до попыток: ни одно поле не изменилось', bool(before) and before == after, '%d и %d записей' % (len(before), len(after)))
    code, out, error = sql('select count(*) from audit_admin.audit_log')
    expect('Число записей не уменьшилось после попыток удаления', code == 0 and int(out.strip() or 0) >= total, '%s -> %s' % (total, out.strip()))


# --------------------------------------------------------------------------------------------------- канарейка
def confirmed_phone(ctx, buyer):
    """Покупатель подтверждает телефон через шлюз, код берётся из SMS-заглушки. Возвращает (номер, sub) или (None, None)."""
    logged = ctx.kc.login('dgm-web', buyer['email'], buyer['password'])
    if not expect('Канарейка: одноразовый покупатель входит паролем', logged.ok, S.describe(logged)):
        return None, None
    token, sub = logged.tokens['access_token'], logged.access().get('sub')
    phone = S.fresh_phone()
    before = len(S.sms_items(ctx.kc.net, phone))
    created = S.api('POST', '/api/v1/phone-confirmations', token, {'phone': phone})
    if not expect('Канарейка: запрос кода подтверждения телефона: 201', created.status == 201 and created.json().get('confirmationId'), describe(created)):
        return None, None
    code = S.next_code(ctx.kc.net, phone, before)
    if not expect('Канарейка: SMS с кодом пришло на номер', code and re.fullmatch(r'\d{6}', code), str(code)):
        return None, None
    verified = S.api('POST', '/api/v1/phone-confirmations/%s/verify' % created.json()['confirmationId'], token, {'code': code})
    if not expect('Канарейка: телефон подтверждён: 200', verified.status == 200 and verified.json().get('phoneConfirmed') is True, describe(verified)):
        return None, None
    return phone, sub


def canary(ctx):
    print('== Канарейка TC-802: e-mail и телефон покупателя не попадают в журнал, тему audit.events и ответы API (NFT-5.3, ADR-014)')
    buyer = A.new_buyer(ctx.kc, ctx.admin, ctx.stamp, 'auc')
    if not buyer:
        return
    phone, sub = confirmed_phone(ctx, buyer)
    if not phone:
        return
    token = ctx.administrator.token()
    since = stamp_of(ctx.started)
    record, took = wait_record(token, action='user.phone_confirmed', objectType='user', objectId=sub, occurredFrom=since)
    expect('TC-802. Подтверждение телефона записано в журнале через %.1f с: исполнитель сам покупатель, роль buyer, адрес хеш' % took,
           record is not None and record.get('actorId') == sub and record.get('actorRole') == 'buyer' and record.get('actorIpHashed') is True, str(record)[:300])
    if record:
        number = change_of(record, 'phone') or {}
        flag = change_of(record, 'phone_confirmed') or {}
        # API отдаёт значения изменений строками, в том числе логические (F4-29): false и true приходят как «false» и «true»
        expect('TC-802. Номер в записи персональный: до пусто, после только хеш из 64 знаков; признак подтверждения false → true',
               number.get('personal') is True and number.get('before') is None and HASH.match(str(number.get('after'))) is not None
               and str(flag.get('before')).lower() == 'false' and str(flag.get('after')).lower() == 'true', str(record.get('changes'))[:300])
        expect('TC-815. Адрес исполнителя у пользователя хеш, а не открытый адрес', HASH.match(str(record.get('actorIp'))) is not None, str(record.get('actorIp'))[:80])

    deactivated = A.post('/api/v1/staff/users/%s/deactivate' % sub, token)
    expect('Канарейка: деактивация записи покупателя: 200', deactivated.status == 200, describe(deactivated))
    erased = A.post('/api/v1/staff/users/%s/anonymize' % sub, ctx.administrator.token())
    expect('Канарейка: анонимизация деактивированной записи: 200', erased.status == 200 and erased.json().get('anonymized') is True, describe(erased))
    for action in ('user.deactivated', 'user.anonymized'):
        found, took = wait_record(ctx.administrator.token(), action=action, objectType='user', objectId=sub, occurredFrom=since)
        expect('TC-802. Запись %s в журнале через %.1f с: исполнитель admin, в изменениях открытых значений нет' % (action, took),
               found is not None and found.get('actorRole') == 'admin' and not any(c.get('personal') and not HASH.match(str(c.get('after') or '0' * 64)) for c in found.get('changes') or []),
               str(found)[:300])

    wanted = variants(buyer['email']) + variants(phone) + variants(phone.lstrip('+')) + variants(phone[2:]) + [buyer['email'].split('@')[0]]
    answers = journal(ctx.administrator.token(), objectId=sub, limit=100)
    mine = items_of(answers)
    expect('TC-802. Контроль: журнал по объекту содержит записи покупателя (подтверждение телефона, деактивация, анонимизация), всего %d' % len(mine),
           {'user.phone_confirmed', 'user.deactivated', 'user.anonymized'} <= {r.get('action') for r in mine}, str([r.get('action') for r in mine]))
    found = C.leaks(answers.text, wanted)
    expect('TC-802. В ответе API журнала по записям покупателя открытых значений нет', not found, str(found[:2]))

    code, dump, error = 0, '', ''
    run = C.sh(G.COMPOSE + ['exec', '-T', 'postgres', 'pg_dump', '-U', 'postgres', '-d', 'platform_db', '--column-inserts', '--no-owner', '-n', 'audit_admin'], timeout=300)
    dump, code, error = run.stdout, run.returncode, (run.stderr or '')[-200:]
    rows = [line for line in dump.splitlines() if line.startswith('INSERT INTO audit_admin.audit_log') and sub in line]
    expect('TC-802. Контроль: дамп схемы audit_admin снят (%d КБ), в нём записи этого покупателя (%d)' % (len(dump) // 1024, len(rows)), code == 0 and len(rows) >= 3, error)
    found = C.leaks(dump, wanted)
    expect('TC-802. В таблице журнала и её секциях открытых e-mail и телефона нет (дамп audit_admin)', not found, str(found[:2]))

    try:
        messages = P.read_topics(['audit.events'])['audit.events']
    except Exception as e:   # noqa: BLE001 - тему не прочитали: это ошибка проверки, а не повод пропустить её
        G.bad('TC-802. Тема audit.events не прочитана', str(e)[-300:])
        return
    mine = [m for m in messages if ((m.envelope or {}).get('data') or {}).get('objectId') == sub]
    wire = '\n'.join(m.value for m in messages)
    expect('TC-802. Контроль: в теме audit.events (%d записей, %d КБ) события этого покупателя: %d' % (len(messages), len(wire) // 1024, len(mine)), len(mine) >= 3, str(len(mine)))
    found = C.leaks(wire, wanted)
    expect('TC-802. В теме audit.events открытых e-mail и телефона нет', not found, str(found[:2]))


# --------------------------------------------------------------------------------------------------- запуск
def main():
    kc, admin = K.from_env(dict(os.environ))
    people = G.users()
    for name in ('moderator-1', 'admin-1'):
        if name not in people:
            print('Нет тестового пользователя %s (make keycloak-users)' % name, file=sys.stderr)
            return 2
    ctx = Context(kc, admin, people, int(time.time()))
    ctx.moderator.token()
    ctx.administrator.token()
    for step in (seller_decisions, product_decisions, viewing, access_and_no_writes, append_only, canary):
        O.guarded(step.__name__, step, ctx)
    return O.summary('audit_checks')


if __name__ == '__main__':
    sys.exit(main())
