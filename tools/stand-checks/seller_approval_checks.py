#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Решения модератора и роль продавца сквозь стенд (шаг 11 Ф4; TC-013, US-2.2, US-2.3, ST-13, INV-36, INV-37, Р-18).

    make seller-approval-check

Нужен набор full (платформа, Keycloak, шлюз, каталог, Kafka) с отладочным файлом: make up SET=full DEBUG=1, затем make keycloak-users.
Заявки подают два одноразовых покупателя, которых проверка регистрирует через страницу регистрации; решает moderator-1 настоящим входом
с TOTP, роли смотрит admin-1. Что проверяется:

  очередь       заявка на проверке видна модератору, карточка даёт анкету и ссылку на документ, ссылка открывается через /files шлюза;
  отказ         без причины 422, с причиной 200, повтор решения 409, заявитель видит причину и срок новой заявки, раньше срока подать нельзя;
  доработка     комментарий обязателен, паузы нет, заявитель правит анкету и подаёт снова;
  одобрение     платформа по событию seller.approved ставит роль seller вместо buyer, атрибут seller_id и обязательную настройку TOTP;
  вход          после пароля Keycloak ведёт на настройку TOTP, первый токен (amr = pwd) кабинет не открывает (ST-13), токен после входа
                с кодом (amr = pwd и otp) открывает и несёт claim seller_id;
  роль вручную  администратор не может ни назначить роль seller, ни снять её (U8, INV-36);
  товар         сквозной сценарий К2 (шаг 13): продавец, одобренный по заявке, заводит товар и подаёт его, модератор открывает карточку и
                одобряет, карточка на витрине без токена (ETag и 304, «нет в наличии», поиск по названию), отклонение с причиной, блокировка
                убирает товар с витрины (кэш сброшен), продавец исправляет и подаёт снова.

Обязательность второго фактора у продавца без устройства (seller-2) проверяет keycloak_checks.py, границы срока рассмотрения, откат
решения при сбое записи и запись аудита в Outbox (одно событие решения и одна запись аудита) проверяет SellerModerationIT, роль и
аудит на стороне платформы PlatformServiceIT: журнал аудита и операция чтения журнала появятся позже (listAuditRecords). Только стандартная
библиотека Python.
"""
import os
import sys
import time
import urllib.parse
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import accounts_checks as A  # noqa: E402
import canary_checks as C  # noqa: E402
import gateway_checks as G  # noqa: E402
import kc_client as K  # noqa: E402
import sellers_checks as SC  # noqa: E402
import sms_login_checks as S  # noqa: E402

expect = G.expect
QUEUE = '/api/v1/staff/seller-applications'
CABINET = '/api/v1/seller/products'
STAFF_PRODUCTS = '/api/v1/staff/products'
STOREFRONT = '/api/v1/products'


# --------------------------------------------------------------------------------------------------- вспомогательное
class Staff:
    """Сотрудник со вторым фактором. Токен доступа живёт 5 минут, а вход с ожиданием шага TOTP долгий: токен старше 200 секунд берётся заново."""

    def __init__(self, kc, admin, user):
        self.kc, self.admin, self.user = kc, admin, user
        self.value, self.issued = None, 0.0

    def token(self):
        if self.value is None or time.time() - self.issued > 200:
            token, note = A.staff_login(self.kc, self.admin, self.user)
            expect('%s вошёл со вторым фактором' % self.user['name'], token, note)
            self.value = token or self.value
            self.issued = time.time()
        return self.value


def applicant(kc, admin, stamp, tag):
    """Покупатель с поданной заявкой: (покупатель, токен, идентификатор профиля) или None. Первое обращение к платформе создаёт её запись о покупателе
    заранее: иначе событие одобрения пришло бы раньше записи и ждало сверки (платформа повторяет обработку 1, 5 и 25 секунд, потом DLQ)."""
    buyer = A.new_buyer(kc, admin, stamp, tag)
    if not buyer:
        return None
    logged = kc.login('dgm-web', buyer['email'], buyer['password'])
    if not expect('%s: покупатель входит паролем' % tag, logged.ok, S.describe(logged)):
        return None
    token = logged.tokens['access_token']
    known = A.post('/api/v1/phone-confirmations', token, {'phone': S.fresh_phone()})
    expect('%s: первый запрос к платформе создаёт её запись о покупателе: 201' % tag, known.status == 201, '%s %s' % (known.status, known.text[:160]))
    created = SC.post_json(SC.BASE, token, SC.FORM)
    uploaded = SC.upload(token, 'справка.pdf', SC.pdf(4096))
    submitted = SC.post_json(SC.CURRENT + '/submit', token)
    ok = created.status == 201 and uploaded.status == 201 and submitted.status == 200 and submitted.json().get('status') == 'on_review'
    if not expect('%s: заявка создана, документ загружен, заявка подана' % tag, ok, '%s %s %s' % (created.status, uploaded.status, submitted.text[:160])):
        return None
    buyer['token'], buyer['sellerId'] = token, submitted.json()['sellerId']
    return buyer


def decide(moderator, seller_id, decision, body=None):
    return SC.post_json('%s/%s/%s' % (QUEUE, seller_id, decision), moderator.token(), body)


def application(buyer):
    return G.call('GET', SC.CURRENT, headers=SC.auth(buyer['token']))


def in_queue(moderator, seller_id):
    cursor = None
    for _ in range(50):
        page = G.call('GET', QUEUE + '?limit=100' + ('&cursor=' + urllib.parse.quote(cursor) if cursor else ''), headers=SC.auth(moderator.token()))
        if page.status != 200:
            return None
        body = page.json()
        for item in body.get('items') or []:
            if item.get('sellerId') == seller_id:
                return item
        cursor = (body.get('page') or {}).get('nextCursor')
        if not cursor:
            return None
    return None


def keycloak_is_seller(admin, user_id, seller_id):
    """Роль seller без buyer, атрибут seller_id и обязательное действие «настроить TOTP»."""
    rep = admin.realm('/users/%s' % user_id).json()
    roles = A.realm_roles(admin, user_id)
    return ('seller' in roles and 'buyer' not in roles and (rep.get('attributes') or {}).get('seller_id') == [seller_id]
            and 'CONFIGURE_TOTP' in (rep.get('requiredActions') or []))


# --------------------------------------------------------------------------------------------------- проверки
def queue_and_card(moderator, buyer):
    print('== Очередь и карточка заявки (US-2.2)')
    item = in_queue(moderator, buyer['sellerId'])
    expect('заявка на проверке есть в очереди модератора с типом, числом документов и сроком', item is not None and item.get('sellerType') == 'self_employed'
           and item.get('documentsCount') == 1 and item.get('overdue') is False and item.get('reviewDeadlineAt'), str(item))
    card = G.call('GET', '%s/%s' % (QUEUE, buyer['sellerId']), headers=SC.auth(moderator.token()))
    body = card.json() if card.status == 200 else {}
    expect('карточка: 200, анкета, идентификатор пользователя равен sub покупателя, статус on_review',
           card.status == 200 and body.get('userId') == buyer['id'] and body.get('status') == 'on_review' and body.get('name') == SC.FORM['name']
           and (body.get('payoutDetails') or {}).get('bik') == SC.FORM['payoutDetails']['bik'], '%s %s' % (card.status, card.text[:200]))
    expect('карточка: Cache-Control no-store', 'no-store' in (card.header('Cache-Control') or ''), card.header('Cache-Control'))
    documents = body.get('documents') or []
    url = ((documents[0].get('link') or {}).get('url') if documents else '') or ''
    if expect('карточка: один документ со ссылкой на /files шлюза', len(documents) == 1 and '/files/seller-documents/' in url, str(documents)[:200]):
        file = G.call('GET', SC.storage_path(url))
        expect('ссылка из карточки открывается без токена и отдаёт загруженный файл', file.status == 200 and file.body == SC.pdf(4096), '%s, %d байт' % (file.status, len(file.body)))
    draftless = G.call('GET', '%s/%s' % (QUEUE, '0199e0a0-0000-4000-8000-0000000000dd'), headers=SC.auth(moderator.token()))
    expect('карточки несуществующей заявки нет: 404', draftless.status == 404, '%s %s' % (draftless.status, draftless.text[:160]))


def rejection(moderator, buyer):
    print('== Отказ: причина обязательна, пауза 24 часа, повторное решение невозможно (TC-013)')
    sid, reason = buyer['sellerId'], 'Скан паспорта не читается, загрузите чёткий снимок'
    blank = decide(moderator, sid, 'reject', {})
    expect('отказ без причины: 422, поле /reason', blank.status == 422 and [e.get('pointer') for e in blank.json().get('errors') or []] == ['/reason'], '%s %s' % (blank.status, blank.text[:200]))
    spaces = decide(moderator, sid, 'reject', {'reason': '   '})
    expect('причина из одних пробелов считается пустой: 422', spaces.status == 422, spaces.status)
    done = decide(moderator, sid, 'reject', {'reason': reason})
    expect('отказ с причиной: 200, статус rejected, причина сохранена', done.status == 200 and done.json().get('status') == 'rejected' and done.json().get('decisionReason') == reason,
           '%s %s' % (done.status, done.text[:200]))
    again = decide(moderator, sid, 'reject', {'reason': reason})
    expect('повторное решение: 409 state-conflict с текущим статусом', again.status == 409 and SC.code_of(again) == 'state-conflict' and again.json().get('currentStatus') == 'rejected',
           '%s %s' % (again.status, again.text[:200]))
    other = decide(moderator, sid, 'approve')
    expect('одобрить отклонённую заявку нельзя: 409', other.status == 409, '%s %s' % (other.status, other.text[:200]))
    expect('решённая заявка ушла из очереди', in_queue(moderator, sid) is None)

    seen = application(buyer)
    body = seen.json() if seen.status == 200 else {}
    until = SC.instant(body['nextApplicationAt']) if body.get('nextApplicationAt') else None
    left = (until - datetime.now(until.tzinfo)).total_seconds() if until else 0
    expect('заявитель видит отказ, причину и срок новой заявки около 24 часов (%d с)' % left,
           body.get('status') == 'rejected' and body.get('decisionReason') == reason and 86_000 < left <= 86_400, seen.text[:200])
    early = SC.post_json(SC.CURRENT + '/submit', buyer['token'])
    expect('до конца паузы подать заявку нельзя: 409 application-pause-active', early.status == 409 and SC.code_of(early) == 'application-pause-active', '%s %s' % (early.status, early.text[:200]))


def rework_and_approval(moderator, buyer, admin):
    print('== Доработка и одобрение (TC-013, INV-36)')
    sid = buyer['sellerId']
    blank = decide(moderator, sid, 'return', {})
    expect('доработка без комментария: 422, поле /comment', blank.status == 422 and [e.get('pointer') for e in blank.json().get('errors') or []] == ['/comment'],
           '%s %s' % (blank.status, blank.text[:200]))
    comment = 'Уточните БИК банка получателя'
    returned = decide(moderator, sid, 'return', {'comment': comment})
    expect('доработка: 200, статус returned, комментарий сохранён', returned.status == 200 and returned.json().get('status') == 'returned'
           and returned.json().get('decisionReason') == comment, '%s %s' % (returned.status, returned.text[:200]))
    seen = application(buyer)
    expect('заявитель видит комментарий, паузы нет', seen.status == 200 and seen.json().get('status') == 'returned' and seen.json().get('decisionReason') == comment
           and not seen.json().get('nextApplicationAt'), seen.text[:200])
    fixed = SC.put_json(SC.CURRENT, buyer['token'], seen.header('ETag'), dict(SC.FORM, payoutDetails=dict(SC.FORM['payoutDetails'], bik='044525225')))
    expect('заявитель исправил анкету: 200', fixed.status == 200, '%s %s' % (fixed.status, fixed.text[:200]))
    resubmitted = SC.post_json(SC.CURRENT + '/submit', buyer['token'])
    expect('повторная подача сразу, без паузы: 200, on_review', resubmitted.status == 200 and resubmitted.json().get('status') == 'on_review', '%s %s' % (resubmitted.status, resubmitted.text[:200]))
    expect('заявка снова в очереди', in_queue(moderator, sid) is not None)

    approved = decide(moderator, sid, 'approve')
    expect('одобрение: 200, статус approved', approved.status == 200 and approved.json().get('status') == 'approved', '%s %s' % (approved.status, approved.text[:200]))
    expect('заявитель видит одобрение', application(buyer).json().get('status') == 'approved')
    repeat = decide(moderator, sid, 'approve')
    expect('повторное одобрение: 409 state-conflict', repeat.status == 409 and SC.code_of(repeat) == 'state-conflict', '%s %s' % (repeat.status, repeat.text[:200]))

    ok = A.eventually(lambda: keycloak_is_seller(admin, buyer['id'], sid), wait=45)
    rep = admin.realm('/users/%s' % buyer['id']).json()
    expect('платформа по seller.approved поставила в Keycloak роль seller вместо buyer, атрибут seller_id и обязательный TOTP', ok,
           'роли %s, атрибуты %s, действия %s' % (sorted(A.realm_roles(admin, buyer['id'])), rep.get('attributes'), rep.get('requiredActions')))
    return ok


def first_login_and_cabinet(kc, buyer):
    """Возвращает токен продавца со вторым фактором (amr = pwd и otp) или None."""
    print('== Вход нового продавца: настройка TOTP, кабинет только со вторым фактором (ST-13, INV-37)')
    sid = buyer['sellerId']
    d = kc.start('dgm-staff')
    d.post(username=buyer['email'], password=buyer['password'])
    if not expect('после пароля Keycloak требует настроить TOTP (%s)' % d.kind, d.kind == 'totp_setup', d.kind + ' ' + d.message):
        return
    secret = d.setup_totp('проверка шага 11')
    first = d.tokens()
    claims = first.access() if first.ok else {}
    expect('после настройки выдан токен роли seller с областью seller.catalog и claim seller_id (Р-18)',
           first.ok and (claims.get('realm_access') or {}).get('roles') == ['seller'] and 'seller.catalog' in (claims.get('scope') or '').split()
           and claims.get('seller_id') == sid, S.describe(first) + ' ' + str({k: claims.get(k) for k in ('realm_access', 'scope', 'seller_id')}))
    if first.ok:
        denied = G.call('GET', CABINET, headers=G.bearer(first.tokens['access_token']))
        expect('ST-13: токен без otp в amr (%s) кабинет не открывает: 403 second-factor-required' % claims.get('amr'),
               denied.status == 403 and denied.code() == 'second-factor-required', '%s %s' % (denied.status, denied.text[:200]))
    again = kc.login('dgm-staff', buyer['email'], buyer['password'], secret)
    claims = again.access() if again.ok else {}
    expect('повторный вход с кодом TOTP: amr = otp и pwd, claim seller_id на месте, роль одна', again.ok and sorted(claims.get('amr') or []) == ['otp', 'pwd']
           and claims.get('seller_id') == sid and (claims.get('realm_access') or {}).get('roles') == ['seller'], S.describe(again) + ' ' + str(claims.get('amr')))
    if again.ok:
        bearer = G.bearer(again.tokens['access_token'])
        opened = G.call('GET', CABINET, headers=bearer)
        expect('кабинет продавца открыт токеном с otp: шлюз не отвечает 401 или 403 (%s)' % opened.status, opened.status not in (401, 403), '%s %s' % (opened.status, opened.text[:200]))
        old = G.call('GET', SC.CURRENT, headers=bearer)
        expect('продавец заявку на статус продавца больше не подаёт: 403 (роль не buyer)', old.status == 403, '%s %s' % (old.status, old.text[:160]))
        return again.tokens['access_token']
    return None


def product_form(title, amount=149900):
    return {'title': title, 'description': 'Приключенческая игра, ключ активации Steam', 'productType': 'game_key', 'platform': 'Steam',
            'activationRegions': ['RU', 'KZ'], 'price': {'amount': amount, 'currency': 'RUB'}, 'issuanceMethod': 'key_pool'}


def staff_decide(moderator, product_id, decision, body=None):
    return SC.post_json('%s/%s/%s' % (STAFF_PRODUCTS, product_id, decision), moderator.token(), body)


def storefront(product_id, **headers):
    return G.call('GET', '%s/%s' % (STOREFRONT, product_id), headers=headers)


def product_to_storefront(moderator, seller_token, stamp):
    print('== Товар от заявки до витрины: подача, решения модератора, карточка с кэшем (шаг 13, К2, US-3.3 – US-3.5, US-4.5)')
    title = 'Лунная тропа %d: ключ Steam' % stamp
    created = SC.post_json(CABINET, seller_token, product_form(title))
    if not expect('продавец завёл товар: 201, черновик', created.status == 201 and created.json().get('status') == 'draft', '%s %s' % (created.status, created.text[:200])):
        return
    pid = created.json()['productId']
    expect('черновика нет ни на витрине, ни у модератора: 404 и 404', storefront(pid).status == 404
           and G.call('GET', '%s/%s' % (STAFF_PRODUCTS, pid), headers=SC.auth(moderator.token())).status == 404)
    submitted = SC.post_json('%s/%s/submit' % (CABINET, pid), seller_token)
    expect('товар подан: 200, on_moderation', submitted.status == 200 and submitted.json().get('status') == 'on_moderation', '%s %s' % (submitted.status, submitted.text[:200]))

    queue = G.call('GET', STAFF_PRODUCTS + '?limit=100', headers=SC.auth(moderator.token()))
    ids = [i.get('productId') for i in queue.json().get('items') or []] if queue.status == 200 else []
    expect('товар в очереди модератора: 200', queue.status == 200 and pid in ids, '%s %s' % (queue.status, queue.text[:200]))
    card = G.call('GET', '%s/%s' % (STAFF_PRODUCTS, pid), headers=SC.auth(moderator.token()))
    expect('карточка модератора: 200, no-store, значений ключей в ответе нет', card.status == 200 and 'no-store' in (card.header('Cache-Control') or '')
           and card.json().get('title') == title and 'keys' not in card.json(), '%s %s' % (card.status, card.text[:200]))

    blank = staff_decide(moderator, pid, 'reject', {})
    expect('отклонение без причины: 422', blank.status == 422, '%s %s' % (blank.status, blank.text[:160]))
    rejected = staff_decide(moderator, pid, 'reject', {'reason': 'В описании не указан регион активации'})
    expect('отклонение с причиной: 200, rejected', rejected.status == 200 and rejected.json().get('status') == 'rejected', '%s %s' % (rejected.status, rejected.text[:200]))
    own = G.call('GET', '%s/%s' % (CABINET, pid), headers=SC.auth(seller_token))
    expect('продавец видит причину отклонения', own.status == 200 and own.json().get('rejectionReason') == 'В описании не указан регион активации', own.text[:200])
    again = SC.post_json('%s/%s/submit' % (CABINET, pid), seller_token)
    expect('продавец подал товар снова (T5): 200, on_moderation', again.status == 200 and again.json().get('status') == 'on_moderation', '%s %s' % (again.status, again.text[:200]))

    approved = staff_decide(moderator, pid, 'approve')
    expect('одобрение: 200, published', approved.status == 200 and approved.json().get('status') == 'published', '%s %s' % (approved.status, approved.text[:200]))
    repeat = staff_decide(moderator, pid, 'approve')
    expect('повторное одобрение: 409 state-conflict', repeat.status == 409 and SC.code_of(repeat) == 'state-conflict', '%s %s' % (repeat.status, repeat.text[:160]))

    shown = storefront(pid)
    body = shown.json() if shown.status == 200 else {}
    expect('витрина без токена: 200, название и цена верные', shown.status == 200 and body.get('title') == title and (body.get('price') or {}).get('amount') == 149900,
           '%s %s' % (shown.status, shown.text[:200]))
    expect('ключей нет: inStock false, maxOrderQuantity 0 (FT-4.4)', body.get('inStock') is False and body.get('maxOrderQuantity') == 0, str(body)[:200])
    etag = shown.header('ETag')
    expect('карточка: ETag и публичный Cache-Control', bool(etag) and 'public' in (shown.header('Cache-Control') or ''), '%s %s' % (etag, shown.header('Cache-Control')))
    again304 = storefront(pid, **{'If-None-Match': etag or ''})
    expect('повторный запрос с If-None-Match: 304 без тела', again304.status == 304 and not again304.body, '%s, %d байт' % (again304.status, len(again304.body)))
    search = G.call('GET', '%s?q=%s&limit=5' % (STOREFRONT, urllib.parse.quote('лунная тропа %d' % stamp)))
    found = [i.get('productId') for i in search.json().get('items') or []] if search.status == 200 else []
    expect('поиск по части названия находит товар: 200', search.status == 200 and pid in found, '%s %s' % (search.status, search.text[:200]))

    blocked = staff_decide(moderator, pid, 'block', {'reason': 'Жалоба правообладателя'})
    expect('блокировка: 200, blocked', blocked.status == 200 and blocked.json().get('status') == 'blocked', '%s %s' % (blocked.status, blocked.text[:200]))
    gone = A.eventually(lambda: storefront(pid).status == 404, wait=20)
    expect('заблокированный товар исчез с витрины: 404 (кэш карточки сброшен решением, а не истёк по TTL)', gone, str(storefront(pid).status))
    seen = G.call('GET', '%s/%s' % (CABINET, pid), headers=SC.auth(seller_token))
    expect('продавец видит причину блокировки', seen.status == 200 and seen.json().get('blockReason') == 'Жалоба правообладателя', seen.text[:200])
    fixed = SC.put_json('%s/%s' % (CABINET, pid), seller_token, seen.header('ETag'), product_form(title + ' (исправлено)'))
    resubmitted = SC.post_json('%s/%s/submit' % (CABINET, pid), seller_token)
    expect('продавец исправил товар и подал снова (T11): 200 и 200', fixed.status == 200 and resubmitted.status == 200 and resubmitted.json().get('status') == 'on_moderation',
           '%s %s' % (fixed.status, resubmitted.status))
    final = staff_decide(moderator, pid, 'approve')
    back = A.eventually(lambda: storefront(pid).status == 200, wait=10)
    expect('после повторной модерации товар снова на витрине', final.status == 200 and back, '%s %s' % (final.status, storefront(pid).status))
    return pid if final.status == 200 and back else None


def manual_role_checks(admin_user, buyer_seller, buyer_rejected):
    print('== Роль «Продавец» вручную не назначается и не снимается (U8, INV-36, ST-13)')
    token = admin_user.token()
    listed = G.call('GET', '/api/v1/staff/users?email=%s' % urllib.parse.quote(buyer_seller['email']), headers=G.bearer(token))
    items = listed.json().get('items') or [] if listed.status == 200 else []
    expect('платформа хранит роль seller у одобренного продавца, запись активна', len(items) == 1 and items[0].get('role') == 'seller' and items[0].get('status') == 'active',
           '%s %s' % (listed.status, listed.text[:300]))
    assign = A.post('/api/v1/staff/users/%s/change-role' % buyer_rejected['id'], token, {'role': 'seller'})
    expect('администратор назначить seller не может: 409 role-managed-by-system', assign.status == 409 and assign.code() == 'role-managed-by-system', '%s %s' % (assign.status, assign.text[:200]))
    take = A.post('/api/v1/staff/users/%s/change-role' % buyer_seller['id'], token, {'role': 'buyer'})
    expect('снять роль seller нельзя: 409 role-managed-by-system', take.status == 409 and take.code() == 'role-managed-by-system', '%s %s' % (take.status, take.text[:200]))
    rejected = G.call('GET', '/api/v1/staff/users?email=%s' % urllib.parse.quote(buyer_rejected['email']), headers=G.bearer(token))
    items = rejected.json().get('items') or [] if rejected.status == 200 else []
    expect('отклонённый заявитель остаётся покупателем', len(items) == 1 and items[0].get('role') == 'buyer', '%s %s' % (rejected.status, rejected.text[:300]))


def main():
    kc, admin = K.from_env(dict(os.environ))
    people = G.users()
    for name in ('moderator-1', 'admin-1'):
        if name not in people:
            print('Нет тестового пользователя %s (make keycloak-users)' % name, file=sys.stderr)
            return 2
    stamp = int(time.time())
    moderator, administrator = Staff(kc, admin, people['moderator-1']), Staff(kc, admin, people['admin-1'])
    print('== Две заявки от одноразовых покупателей')
    for_approval = applicant(kc, admin, stamp, 'appr')
    for_rejection = applicant(kc, admin, stamp, 'rej')
    if for_approval and for_rejection:
        moderator.token()
        queue_and_card(moderator, for_approval)
        rejection(moderator, for_rejection)
        if rework_and_approval(moderator, for_approval, admin):
            seller_token = first_login_and_cabinet(kc, for_approval)
            if seller_token:
                product_id = product_to_storefront(moderator, seller_token, stamp)
                if product_id:
                    C.run(seller_token, moderator, product_id)
            manual_role_checks(administrator, for_approval, for_rejection)
    print('\nПроверок успешно: %d, с ошибками: %d' % (G.PASSED, len(G.FAILED)))
    for name in G.FAILED:
        print('  - %s' % name)
    if os.environ.get('GITHUB_ACTIONS'):
        print('::notice title=seller_approval_checks::Проверок успешно: %d, с ошибками: %d%s' % (
            G.PASSED, len(G.FAILED), ('; не прошли: ' + '|'.join(G.FAILED)) if G.FAILED else ''))
    return 1 if G.FAILED else 0


if __name__ == '__main__':
    sys.exit(main())
