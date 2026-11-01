#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Обращение «Не получил ключ» и повторная отправка сквозь стенд (шаг 17 среза 3 Ф4, К10; US-7.1, US-7.2, US-7.3, FT-7.2, FT-7.5, FT-9.0, NFT-3.6, INV-21, INV-23; BPMN-04 E1, E3, E6;
TC-015, TC-716, TC-718).

    make support-check

Нужен тот же стенд, что для delivery_checks.py: набор full с ускоренными сроками и отладочным файлом (make fast-up DEBUG=1, затем make keycloak-users). Решает оператор
support-1 (настоящий вход с TOTP), журнал читает admin-1, покупатели одноразовые. Продавца и товары берёт у orders_checks.py (secrets/purchase_fixture.json). Платёжная
заглушка остаётся в режиме по умолчанию (оплачивает заказ сама), режим почтовой заглушки меняется только на время сценария и возвращается на выходе.

  обращение   TC-015, шаги 1 – 4: покупатель оплатил заказ, провайдер принял письмо с ключом и больше ничего не сообщил (выдача sent), покупатель создаёт обращение:
              201, источник buyer, статус created; повтор по тому же заказу 409 ticket-already-open, чужой заказ 404, в модели чтения поддержки выдача sent; событие
              ticket.created одно. Покупателю операции оператора закрыты (403)
  очередь     оператор находит обращение по номеру заказа (статус заказа и выдачи, ни ключа, ни адреса в ответе нет); повторная отправка до взятия 409
              ticket-not-in-progress; два одновременных «взять» дают один 200 и один 409; возврат в очередь и повторное взятие; в журнале аудита записи
              support.ticket_taken (две) и support.ticket_released с исполнителем, ролью support-operator, статусами до и после
  повтор      два одновременных «Повторно отправить» (двойной щелчок): выдача одна, лишние нажатия получают 409; письмо с ключом приходит второй раз на тот же адрес и
              с теми же ключами, ключ заново не выпускается; провайдер подтвердил доставку, выдача repeat delivered, обращение решено системой (resolved, resolved_by =
              system, action = resend); в журнале support.resend_requested с источником operator и без адреса и ключа; сквозной идентификатор запроса виден в записи
              журнала, событии ticket.created и журнале платформы (Р-172)
  закрытие    TC-716: решённое обращение нельзя ни взять, ни вернуть, ни повторно отправить, ни начать по нему смену e-mail (409), операции «решить» у оператора нет
              (запрос отклонён, обращение не изменилось): обращение закрывает только доставка
  письма      TC-718: письмо платформы, которое провайдер принял и отклонил (failed, address_rejected), даёт уведомление failed в очереди недоставленных с кодом
              provider_address_rejected и одно notification.failed без адреса; принятое без статуса остаётся sent, статус delivered вебхуком переводит его в delivered,
              запоздалый failed после доставки ничего не меняет; у письма в режиме по умолчанию статус delivered приходит сам
  канарейка   ST-01 для кода повторной отправки: значения ключей и адреса покупателей запуска не найдены в журналах контейнеров, темах payment, order и delivery.events,
              теме support.events и дампах баз (общий поиск purchase_checks.leak_scan с контролем: номера заказов видны в журналах, события заказов в темах)

Окно обращения (E1) на стенде не проверяется: параметр support.window-seconds нельзя опустить ниже часа, закрытое окно проверяют TicketServiceTest и PlatformServiceIT. Только стандартная библиотека Python.
"""
import concurrent.futures
import json
import os
import sys
import time
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import accounts_checks as A  # noqa: E402
import audit_checks as AU  # noqa: E402
import delivery_checks as DC  # noqa: E402
import gateway_checks as G  # noqa: E402
import kc_client as K  # noqa: E402
import orders_checks as O  # noqa: E402
import purchase_checks as P  # noqa: E402
import seller_approval_checks as SA  # noqa: E402
import sellers_checks as SC  # noqa: E402

expect = G.expect
TICKETS = '/api/v1/support-tickets'
STAFF = '/api/v1/staff/support-tickets'
OPERATOR = 'support-1'
OPERATOR_ROLE = 'support-operator'
RESOLVE_SECONDS = 90
BUSY_CODES = ('delivery-in-progress', 'request-in-progress', 'ticket-not-in-progress')


# --------------------------------------------------------------------------------------------------- запросы
def post_json(token, path, body=None, **extra):
    headers = SC.auth(token, **dict({'Content-Type': 'application/json', 'Idempotency-Key': str(uuid.uuid4())}, **extra))
    return G.call('POST', path, headers=headers, body=json.dumps(body).encode() if body is not None else None)


def get(token, path):
    return G.call('GET', path, headers=SC.auth(token))


def open_ticket(buyer, order_id, **extra):
    return post_json(buyer.token(), TICKETS, {'orderId': order_id, 'reason': 'key_not_received'}, **extra)


def open_ticket_when_issued(buyer, order_id, wait=30.0, token=None, **extra):
    """Обращение по только что выданному заказу (токен покупателя или, если передан token, сессии по SMS). Платформа видит статус заказа в своей
    модели чтения, которую ведёт событиями, и сразу после выдачи она может отстать от ответа сервиса заказов на доли секунды: тогда 409
    state-conflict («создаётся по выданному заказу»). Проверка повторяет запрос, пока приходит именно он (F4-72); любой другой ответ
    возвращается сразу."""
    deadline = time.time() + wait
    while True:
        answer = post_json(token or buyer.token(), TICKETS, {'orderId': order_id, 'reason': 'key_not_received'}, **extra)
        if answer.status != 409 or answer.code() != 'state-conflict' or time.time() >= deadline:
            return answer
        time.sleep(1)


def staff_get(operator, path):
    return get(operator.token(), STAFF + path)


def staff_post(operator, path, body=None, **extra):
    return post_json(operator.token(), STAFF + path, body, **extra)


def body_of(response):
    try:
        return response.json()
    except ValueError:
        return {}


def row_of(sql_rows):
    return sql_rows[0] if sql_rows else None


# --------------------------------------------------------------------------------------------------- заказ с молчащей почтой
def silent_order(fx, tag):
    """Покупатель и выданный заказ, у которого провайдер принял письмо с ключом и больше ничего не сообщил: (покупатель, заказ, письма выдачи) или (None, None, [])."""
    stubs = fx['stubs']
    with stubs.email_mode(status='none'):
        buyer, oid = DC.start_order(fx, tag)
        if not buyer:
            return None, None, []
        issued, order = O.wait_order(buyer, oid, 'issued', wait=90)
        letters = P.wait_letters(stubs, to=buyer.email, sender='delivery')
    if not expect('%s: заказ выдан, провайдер принял одно письмо с ключом и статуса не прислал (accepted)' % tag,
                  issued and len(letters) == 1 and letters[0].get('status') == 'accepted', '%s %s' % (order.get('status'), [m.get('status') for m in letters])):
        return None, None, []
    return buyer, order, letters


# --------------------------------------------------------------------------------------------------- TC-015
def not_received(fx):
    kc, admin, stubs, people = fx['kc'], fx['admin'], fx['stubs'], fx['people']
    print('== Обращение «Не получил ключ»: создание покупателем (US-7.1, FT-7.2, INV-21; TC-015, шаги 1 – 4)')
    stubs.reset()
    buyer, order, letters = silent_order(fx, 'sp1')
    if not buyer:
        return
    oid, number = order['orderId'], order['orderNumber']
    first_keys = P.KEY_LINE.findall(P.body_of(letters[0]))
    expect('TC-015. В письме выдачи есть ключи (с ними сравнивается повторное письмо)', len(first_keys) == 1, str(first_keys))
    correlation = str(uuid.uuid4())
    created = open_ticket_when_issued(buyer, oid, **{'X-Correlation-Id': correlation})
    ticket = body_of(created)
    if not expect('TC-015. Обращение создано: 201, источник buyer, причина key_not_received, статус created, решения и смены e-mail нет',
                  created.status == 201 and ticket.get('source') == 'buyer' and ticket.get('reason') == 'key_not_received' and ticket.get('status') == 'created'
                  and ticket.get('orderId') == oid and ticket.get('resolution') is None and ticket.get('emailChange') is None, O.describe(created)):
        return
    tid = ticket['ticketId']
    fx['ticket'] = {'buyer': buyer, 'order': oid, 'number': number, 'id': tid, 'keys': first_keys, 'correlation': correlation}
    again = open_ticket(buyer, oid)
    expect('TC-015. Второе обращение по тому же заказу: 409 ticket-already-open (INV-21)', again.status == 409 and again.code() == 'ticket-already-open', O.describe(again))
    stranger = P.new_buyer(kc, admin, fx['stamp'], 'sp2')
    if stranger:
        foreign = open_ticket(stranger, oid)
        expect('TC-015. Чужой заказ: 404, как у несуществующего (доступ по владельцу)', foreign.status == 404, O.describe(foreign))
        missing = open_ticket(stranger, str(uuid.uuid4()))
        expect('TC-015. Несуществующий заказ даёт тот же ответ: 404', missing.status == 404 and missing.code() == foreign.code(), '%s и %s' % (O.describe(missing), O.describe(foreign)))
        hidden = get(stranger.token(), '%s/%s' % (TICKETS, tid))
        expect('TC-015. Чужое обращение покупателю не видно: 404', hidden.status == 404, O.describe(hidden))
    listed = get(buyer.token(), TICKETS)
    mine = [t for t in (body_of(listed).get('items') or []) if t.get('ticketId') == tid]
    expect('TC-015. Обращение в списке покупателя, ключа и адреса в ответе нет', listed.status == 200 and len(mine) == 1 and P.KEY_PREFIX not in listed.text and buyer.email not in listed.text,
           O.describe(listed))
    expect('TC-015. В базе платформы одно обращение: источник buyer, причина key_not_received, статус created; выдача в модели чтения sent',
           P.tickets_of(oid) == [('buyer', 'key_not_received', 'created', '')] and A.eventually(lambda: P.support_delivery_status(oid) == 'sent', wait=30),
           '%s %s' % (P.tickets_of(oid), P.support_delivery_status(oid)))
    expect('TC-015. В Outbox платформы одно ticket.created', P.outbox_count('platform_db', tid, 'ticket.created') == 1, str(P.outbox_count('platform_db', tid, 'ticket.created')))
    denied = staff_post(SimpleToken(buyer), '/%s/take' % tid)
    expect('TC-015. Покупателю операции оператора закрыты: 403', denied.status == 403, O.describe(denied))
    denied_queue = get(buyer.token(), STAFF)
    expect('TC-015. Очередь оператора покупателю закрыта: 403', denied_queue.status == 403, O.describe(denied_queue))


class SimpleToken:
    """Покупатель под тем же интерфейсом, что оператор: нужен только token()."""

    def __init__(self, buyer):
        self.buyer = buyer

    def token(self):
        return self.buyer.token()


def queue_and_take(fx):
    print('== Очередь оператора: найти по номеру заказа, взять, вернуть, взять снова (US-7.2, FT-9.0; TC-015)')
    state = fx.get('ticket')
    if not state:
        G.bad('Контроль: обращение не создано, очередь оператора проверить нельзя')
        return
    operator, tid, number, oid = fx['operator'], state['id'], state['number'], state['order']
    sub = AU.claims_of(operator.token())['sub']
    state['operator_sub'] = sub
    seen = fx.setdefault('operator_seen', [])

    found = staff_get(operator, '?orderNumber=%d' % number)
    seen.append(found.text)
    items = [t for t in (body_of(found).get('items') or []) if t.get('ticketId') == tid]
    row = items[0] if items else {}
    expect('TC-015. Оператор нашёл обращение по номеру заказа №%d: статус created, оператора нет, заказ issued, выдача sent' % number,
           found.status == 200 and len(items) == 1 and row.get('status') == 'created' and row.get('operatorId') is None and row.get('orderStatus') == 'issued'
           and row.get('deliveryStatus') == 'sent' and row.get('orderNumber') == number and row.get('emailChange') is None, O.describe(found))
    card = staff_get(operator, '/%s' % tid)
    seen.append(card.text)
    expect('TC-015. Карточка обращения открывается: 200', card.status == 200 and body_of(card).get('ticketId') == tid, O.describe(card))

    early = staff_post(operator, '/%s/resend' % tid)
    expect('TC-015. Повторная отправка до взятия в работу: 409 ticket-not-in-progress', early.status == 409 and early.code() == 'ticket-not-in-progress', O.describe(early))
    unknown = staff_post(operator, '/%s/take' % uuid.uuid4())
    expect('TC-015. Взять несуществующее обращение: 404', unknown.status == 404, O.describe(unknown))

    token = operator.token()
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        answers = list(pool.map(lambda _: post_json(token, '%s/%s/take' % (STAFF, tid)), range(2)))
    seen.extend(a.text for a in answers)
    codes = sorted(a.status for a in answers)
    won = [a for a in answers if a.status == 200]
    lost = [a for a in answers if a.status == 409]
    expect('TC-015. Два «взять» одновременно: один 200, другой 409 state-conflict (обращение уже взято)',
           codes == [200, 409] and lost[0].code() == 'state-conflict', str([(a.status, a.code()) for a in answers]))
    expect('TC-015. Взятое обращение: in_progress, оператор support-1', len(won) == 1 and body_of(won[0]).get('status') == 'in_progress'
           and body_of(won[0]).get('operatorId') == sub, O.describe(won[0]) if won else '')

    released = staff_post(operator, '/%s/release' % tid)
    expect('TC-015. Возврат в очередь: 200, статус created, оператора нет', released.status == 200 and body_of(released).get('status') == 'created'
           and body_of(released).get('operatorId') is None, O.describe(released))
    twice = staff_post(operator, '/%s/release' % tid)
    expect('TC-015. Повторный возврат: 409 state-conflict (обращение уже не в работе)', twice.status == 409 and twice.code() == 'state-conflict', O.describe(twice))
    retaken = staff_post(operator, '/%s/take' % tid)
    seen.append(retaken.text)
    expect('TC-015. Повторное взятие после возврата: 200, in_progress', retaken.status == 200 and body_of(retaken).get('status') == 'in_progress', O.describe(retaken))
    expect('TC-015. В базе обращение in_progress', P.tickets_of(oid) == [('buyer', 'key_not_received', 'in_progress', '')], str(P.tickets_of(oid)))


def resend_and_resolve(fx):
    stubs = fx['stubs']
    print('== Повторная отправка: двойной щелчок, письмо на тот же адрес с теми же ключами, доставка решает обращение (US-7.3, FT-7.5; TC-015, шаги 5 – 7)')
    state = fx.get('ticket')
    if not state or not state.get('operator_sub'):
        G.bad('Контроль: обращение не взято в работу, повторную отправку проверить нельзя')
        return
    operator, buyer, tid, oid = fx['operator'], state['buyer'], state['id'], state['order']
    seen = fx['operator_seen']
    token = operator.token()
    correlation = str(uuid.uuid4())
    state['resend_correlation'] = correlation
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        answers = list(pool.map(lambda _: post_json(token, '%s/%s/resend' % (STAFF, tid), **{'X-Correlation-Id': correlation}), range(2)))
    seen.extend(a.text for a in answers)
    accepted = [a for a in answers if a.status == 202]
    refused = [a for a in answers if a.status != 202]
    expect('TC-015. Двойной щелчок «Повторно отправить»: хотя бы один ответ 202 accepted с номером обращения и заказа, лишний получает 409 (%s)' % ', '.join(BUSY_CODES),
           bool(accepted) and all(body_of(a).get('status') == 'accepted' and body_of(a).get('ticketId') == tid and body_of(a).get('orderId') == oid for a in accepted)
           and all(a.status == 409 and a.code() in BUSY_CODES for a in refused), str([(a.status, a.code()) for a in answers]))

    found = P.wait_letters(stubs, count=2, wait=90, to=buyer.email, sender='delivery')
    keys = [P.KEY_LINE.findall(P.body_of(m)) for m in found]
    expect('TC-015. Письма с ключом на адрес покупателя два (первичное и повторное), в обоих тот же ключ: новый ключ не выпускался',
           len(found) == 2 and keys[0] == keys[1] == state['keys'] and all(m['to'] == [buyer.email] for m in found), str([(m.get('subject'), m.get('to')) for m in found]))
    delivered = A.eventually(lambda: sorted(m.get('status') for m in stubs.emails(to=buyer.email, sender='delivery')) == ['accepted', 'delivered'], wait=30, step=1.0)
    expect('TC-015. Провайдер подтвердил доставку повторного письма: в заглушке accepted (первичное) и delivered (повторное)', delivered,
           str([m.get('status') for m in stubs.emails(to=buyer.email, sender='delivery')]))
    time.sleep(3)
    rows = P.deliveries_of(oid) or []
    expect('TC-015. Выдач две: первичная sent и повторная repeat delivered с тем же адресом (двойной щелчок не создал третьей)',
           [(r['kind'], r['status']) for r in rows] == [('primary', 'sent'), ('repeat', 'delivered')] and rows[1]['address'] == buyer.email, str(rows))
    solved = A.eventually(lambda: P.tickets_of(oid) == [('buyer', 'key_not_received', 'resolved', 'system')], wait=RESOLVE_SECONDS, step=2.0)
    expect('TC-015. Обращение решено доставкой: resolved, resolved_by = system', bool(solved), str(P.tickets_of(oid)))
    mine = body_of(get(buyer.token(), '%s/%s' % (TICKETS, tid)))
    expect('TC-015. Покупатель видит итог: resolved, решила система, действие resend, время решения есть',
           mine.get('status') == 'resolved' and mine.get('resolution') == {'resolvedBy': 'system', 'action': 'resend'} and bool(mine.get('resolvedAt')), str(mine)[:300])
    expect('TC-015. В карточке поддержки выдача delivered', A.eventually(lambda: P.support_delivery_status(oid) == 'delivered', wait=30), str(P.support_delivery_status(oid)))
    expect('TC-015. Заказ остаётся issued, адрес доставки в заказе прежний', O.get_order(buyer, oid).json().get('status') == 'issued', '')
    leaked = [t for t in seen if P.KEY_PREFIX in t or buyer.email in t]
    expect('TC-015. Во всех ответах оператора (%d) нет ни ключа, ни адреса покупателя (INV-19, INV-23)' % len(seen), not leaked, str(leaked[:1])[:200])
    P.email_is_default(stubs, 'TC-015')
    O.modes_are_default(stubs, 'TC-015')


def audit_and_trace(fx):
    print('== Журнал аудита и сквозной идентификатор обращения (FT-11.3, NFT-6.0, Р-172; TC-015)')
    state = fx.get('ticket')
    if not state or not state.get('resend_correlation'):
        G.bad('Контроль: повторная отправка не выполнена, журнал проверить нельзя')
        return
    administrator, tid, buyer, oid = fx['administrator'], state['id'], state['buyer'], state['order']
    sub = state['operator_sub']
    A.eventually(lambda: any(r.get('action') == 'support.resend_requested' for r in AU.items_of(AU.journal(administrator.token(), objectType='ticket', objectId=tid, limit=100))),
                 wait=AU.WAIT, step=1.0)
    records = AU.items_of(AU.journal(administrator.token(), objectType='ticket', objectId=tid, limit=100))
    actions = [r.get('action') for r in records]
    expect('TC-015. В журнале по обращению: ticket_taken дважды, ticket_released один раз, resend_requested не меньше одного (%s)' % ', '.join(actions),
           actions.count('support.ticket_taken') == 2 and actions.count('support.ticket_released') == 1 and actions.count('support.resend_requested') >= 1, str(actions))
    mine = [r for r in records if r.get('action') in ('support.ticket_taken', 'support.ticket_released', 'support.resend_requested')]
    expect('TC-015. У каждой записи исполнитель support-1, роль %s, объект ticket с идентификатором обращения, адрес оператора записан как есть' % OPERATOR_ROLE,
           bool(mine) and all(r.get('actorId') == sub and r.get('actorRole') == OPERATOR_ROLE and r.get('objectType') == 'ticket' and r.get('objectId') == tid
                              and bool(r.get('actorIp')) and r.get('actorIpHashed') is False for r in mine), str(mine[:1])[:300])
    taken = [r for r in records if r.get('action') == 'support.ticket_taken']
    status = AU.change_of(taken[0], 'status') or {} if taken else {}
    order = AU.change_of(taken[0], 'orderId') or {} if taken else {}
    expect('TC-015. Запись о взятии: статус created → in_progress, заказ назван идентификатором', status.get('before') == 'created' and status.get('after') == 'in_progress'
           and order.get('after') == oid, str(taken[:1])[:300])
    resend = [r for r in records if r.get('action') == 'support.resend_requested']
    action = AU.change_of(resend[0], 'action') or {} if resend else {}
    source = AU.change_of(resend[0], 'source') or {} if resend else {}
    expect('TC-015. Запись о повторной отправке: действие resend, источник operator, идентификатор запроса из X-Correlation-Id сохранён',
           bool(resend) and action.get('after') == 'resend' and source.get('after') == 'operator' and resend[0].get('correlationId') == state['resend_correlation'],
           str(resend[:1])[:300])
    text = json.dumps(records, ensure_ascii=False)
    expect('TC-015. В записях аудита нет ни адреса покупателя, ни ключа', buyer.email not in text and P.KEY_PREFIX not in text, '')

    seen = P.services_seen(state['correlation'], ['api-gateway', 'platform-service'])
    expect('TC-803, Р-172. Идентификатор запроса «создать обращение» виден в журналах шлюза и платформы (найден в %s)' % (', '.join(sorted(seen)) or 'нигде'),
           {'api-gateway', 'platform-service'} <= seen, str(sorted(seen)))
    try:
        topic = P.read_topics(['support.events'])['support.events']
    except Exception as e:   # noqa: BLE001 - тему не прочитали: это ошибка проверки, а не повод её пропустить
        G.bad('Р-172. Тема support.events не прочитана', str(e)[-300:])
        return
    events = [m.envelope for m in topic if (m.envelope or {}).get('type') == 'ticket.created' and ((m.envelope or {}).get('data') or {}).get('ticketId') == tid]
    expect('TC-803, Р-172. В теме support.events одно ticket.created по обращению, его correlationid равен идентификатору запроса создания (%d записей в теме)' % len(topic),
           len(events) == 1 and events[0].get('correlationid') == state['correlation'], str([e.get('correlationid') for e in events]))
    wire = '\n'.join(m.value for m in topic if tid in m.value)
    expect('Контроль и канарейка: в событиях обращения (%d КБ) адреса покупателя и ключа нет' % (len(wire) // 1024 + 1), bool(wire) and buyer.email not in wire and P.KEY_PREFIX not in wire, '')


def closed_by_delivery(fx):
    print('== Обращение закрывает доставка, вручную его не закрыть (SM-07, FT-7.5; TC-716)')
    state = fx.get('ticket')
    if not state:
        G.bad('Контроль: обращение не создано, закрытие проверить нельзя')
        return
    operator, tid, oid, buyer = fx['operator'], state['id'], state['order'], state['buyer']
    solved = P.tickets_of(oid) == [('buyer', 'key_not_received', 'resolved', 'system')]
    if not expect('TC-716. Предусловие: обращение решено системой', solved, str(P.tickets_of(oid))):
        return
    for name, answer in (('взять', staff_post(operator, '/%s/take' % tid)), ('вернуть в очередь', staff_post(operator, '/%s/release' % tid))):
        expect('TC-716. Решённое обращение нельзя %s: 409 state-conflict' % name, answer.status == 409 and answer.code() == 'state-conflict', O.describe(answer))
    resend = staff_post(operator, '/%s/resend' % tid)
    expect('TC-716. Повторно отправить по решённому обращению нельзя: 409 ticket-not-in-progress', resend.status == 409 and resend.code() == 'ticket-not-in-progress', O.describe(resend))
    change = staff_post(operator, '/%s/email-change' % tid, {'newEmail': 'closed-%d@example.test' % fx['stamp']})
    expect('TC-716. Начать смену e-mail по решённому обращению нельзя: 409', change.status == 409, O.describe(change))
    for path, body in (('/%s/resolve' % tid, {}), ('/%s/close' % tid, {}), ('/%s/resolution' % tid, {'resolvedBy': 'operator', 'action': 'resend'})):
        manual = staff_post(operator, path, body)
        expect('TC-716. Операции «%s» у оператора нет: запрос отклонён (%s)' % (path.rsplit('/', 1)[1], manual.status), manual.status >= 400, O.describe(manual))
    manual_put = G.call('PUT', '%s/%s' % (STAFF, tid), headers=SC.auth(operator.token(), **{'Content-Type': 'application/json'}), body=json.dumps({'status': 'resolved'}).encode())
    expect('TC-716. Правки статуса обращения у оператора нет: запрос отклонён (%s)' % manual_put.status, manual_put.status >= 400, O.describe(manual_put))
    expect('TC-716. После всех попыток обращение прежнее: resolved, решила система', P.tickets_of(oid) == [('buyer', 'key_not_received', 'resolved', 'system')], str(P.tickets_of(oid)))
    after = O.sql('platform_db', "select count(*) from support.ticket where order_id = '%s'" % oid)
    expect('TC-716. Новых обращений по заказу попытки не создали (одно)', row_of(after) == ['1'], str(after))
    queue = staff_get(operator, '?status=created&orderNumber=%d' % state['number'])
    expect('TC-716. В очереди необработанных обращения по этому заказу нет: решённое из неё ушло', queue.status == 200 and not (body_of(queue).get('items') or []), O.describe(queue))


# --------------------------------------------------------------------------------------------------- TC-718
def notification_of(order_id):
    """Уведомление «Заказ создан и ждёт оплаты» по заказу: словарь или None."""
    rows = O.sql('platform_db', "select id, status, in_dead_queue, coalesce(last_error_code, ''), attempts from notification.notification "
                                "where template = 'order-created' and params->>'orderId' = '%s'" % order_id)
    if not rows:
        return None
    r = rows[0]
    return {'id': r[0], 'status': r[1], 'dead': r[2] == 't', 'error': r[3], 'attempts': int(r[4])}


def letter_of(stubs, buyer, order_id, wait=60.0):
    """Письма «Заказ создан и ждёт оплаты» по заказу в заглушке; ждёт первого."""
    deadline = time.time() + wait
    while True:
        found = [m for m in stubs.emails(to=buyer.email, subject=P.CREATED_SUBJECT) if order_id in P.body_of(m)]
        if found or time.time() >= deadline:
            return found
        time.sleep(1)


def platform_letter_statuses(fx):
    kc, admin, stubs = fx['kc'], fx['admin'], fx['stubs']
    print('== Статусы писем платформы от провайдера: delivered, failed, запоздалый failed, событие notification.failed (FT-7.0, ADR-011, F4-6; TC-718)')
    stubs.reset()
    buyer = P.new_buyer(kc, admin, fx['stamp'], 'sp3')
    if not buyer:
        return
    made = {}
    with stubs.payment_mode(scenario='hold'):
        for tag, mode in (('rejected', dict(sender='platform', status='failed', failReason='address_rejected')), ('silent', dict(sender='platform', status='none')), ('default', {})):
            with stubs.email_mode(**mode):
                created = O.create_order(buyer, fx['plenty'], 1)
                body = body_of(created)
                if not expect('TC-718. Заказ «%s» оформлен: 201' % tag, created.status == 201 and body.get('orderId'), O.describe(created)):
                    return
                made[tag] = body['orderId']
                # режим должен действовать, пока письмо не дошло до заглушки: платформа отправляет его не сразу после события
                if not expect('TC-718. Письмо «%s» по заказу «%s» дошло до заглушки почты' % (P.CREATED_SUBJECT, tag), letter_of(stubs, buyer, made[tag]), 'письма нет'):
                    return
                stubs.idle()

    rejected = A.eventually(lambda: (notification_of(made['rejected']) or {}).get('status') == 'failed', wait=60, step=2.0)
    note = notification_of(made['rejected']) or {}
    expect('TC-718. Провайдер принял письмо и отклонил адрес: уведомление failed, в очереди недоставленных, код provider_address_rejected, одна попытка',
           bool(rejected) and note.get('dead') is True and note.get('error') == 'provider_address_rejected' and note.get('attempts') == 1, str(note))
    letters = [m for m in stubs.emails(to=buyer.email, subject=P.CREATED_SUBJECT) if made['rejected'] in P.body_of(m)]
    expect('TC-718. В заглушке письмо в статусе failed с причиной address_rejected, идентификатор сообщения равен идентификатору уведомления',
           len(letters) == 1 and letters[0].get('status') == 'failed' and letters[0].get('reason') == 'address_rejected' and letters[0].get('messageId') == note.get('id'),
           str([(m.get('status'), m.get('reason'), m.get('messageId')) for m in letters]))
    events = O.sql('platform_db', "select payload::text from public.outbox where event_type = 'notification.failed' and aggregate_id = '%s'" % note.get('id', ''))
    expect('TC-718. В Outbox платформы одно notification.failed по этому уведомлению, адреса покупателя в нём нет',
           events is not None and len(events) == 1 and buyer.email not in '|'.join(events[0]), str(events)[:300])

    silent = notification_of(made['silent']) or {}
    expect('TC-718. Провайдер принял письмо и статуса не прислал: уведомление sent, отказа нет', silent.get('status') == 'sent' and silent.get('dead') is False, str(silent))
    mails = [m for m in stubs.emails(to=buyer.email, subject=P.CREATED_SUBJECT) if made['silent'] in P.body_of(m)]
    if expect('TC-718. В заглушке письмо accepted с идентификатором уведомления', len(mails) == 1 and mails[0].get('status') == 'accepted' and mails[0].get('messageId') == silent.get('id'),
              str([(m.get('status'), m.get('messageId')) for m in mails])):
        stubs.email_status(silent['id'], 'delivered')
        stubs.idle()
        done = A.eventually(lambda: (notification_of(made['silent']) or {}).get('status') == 'delivered', wait=30, step=1.0)
        expect('TC-718. Статус delivered вебхуком переводит уведомление из sent в delivered', bool(done), str(notification_of(made['silent'])))
        stubs.email_status(silent['id'], 'delivered')
        stubs.email_status(silent['id'], 'failed', reason='address_rejected')
        stubs.idle()
        time.sleep(2)
        after = notification_of(made['silent']) or {}
        expect('TC-718. Повтор delivered и запоздалый failed после доставки ничего не меняют: уведомление delivered, события notification.failed нет',
               after.get('status') == 'delivered' and after.get('dead') is False and P.outbox_count('platform_db', silent['id'], 'notification.failed') == 0, str(after))

    auto = A.eventually(lambda: (notification_of(made['default']) or {}).get('status') == 'delivered', wait=60, step=2.0)
    expect('TC-718. В режиме по умолчанию заглушка присылает delivered сама: уведомление delivered без ручных шагов', bool(auto), str(notification_of(made['default'])))
    P.email_is_default(stubs, 'TC-718')
    O.modes_are_default(stubs, 'TC-718')


# --------------------------------------------------------------------------------------------------- канарейка
def canary(fx):
    """ST-01 для кода среза 3: повторная отправка собирает письмо с ключом заново, поэтому после неё те же поиски, что после покупки.

    Значения ключей и адреса покупателей этого запуска (первичные и повторные выдачи, обращения) не должны попасть в журналы контейнеров,
    темы payment, order и delivery.events и дампы баз (общий поиск purchase_checks.leak_scan); тема support.events читается целиком отдельно.
    """
    try:
        fx['topics'] = P.read_topics(P.EVENT_TOPICS)
        support = P.read_topics(['support.events'])['support.events']
    except Exception as e:   # noqa: BLE001 - темы не прочитаны: это ошибка проверки, а не повод её пропустить
        G.bad('Канарейка: темы Kafka не прочитаны', str(e)[-300:])
        return
    P.leak_scan(fx, 'support_checks, после повторной отправки')
    wire = '\n'.join(m.value for m in support)
    ticket = (fx.get('ticket') or {}).get('id', '')
    expect('Контроль: в теме support.events (%d записей, %d КБ) есть событие созданного обращения' % (len(support), len(wire) // 1024 + 1), bool(ticket) and ticket in wire, '')
    seen = [e for e in fx['emails'] if e in wire]
    expect('TC-015. В теме support.events нет ни значений ключей (%s…), ни адресов покупателей этого запуска (%d)' % (P.KEY_PREFIX, len(fx['emails'])),
           P.KEY_PREFIX not in wire and not seen, str(seen[:2]))


# --------------------------------------------------------------------------------------------------- запуск
def run_scenarios(fx):
    fx['stubs'].reset()
    for fn in (not_received, queue_and_take, resend_and_resolve, audit_and_trace, closed_by_delivery, platform_letter_statuses, canary):
        O.guarded(fn.__name__, fn, fx)


def main():
    kc, admin = K.from_env(dict(os.environ))
    people = G.users()
    for name in ('moderator-1', 'admin-1', OPERATOR):
        if name not in people:
            print('Нет тестового пользователя %s (make keycloak-users)' % name, file=sys.stderr)
            return 2
    fx = O.context(kc, admin, people, int(time.time()), scarce_needed=False)
    if fx:
        fx['people'] = people
        fx['orders'] = []
        fx['operator'] = SA.Staff(kc, admin, people[OPERATOR])
        fx['administrator'] = SA.Staff(kc, admin, people['admin-1'])
        fx['operator'].token()
        fx['administrator'].token()
        run_scenarios(fx)
    else:
        G.bad('Продавец и товары для заказов не заведены, сценарии поддержки не запускались')
    return O.summary('support_checks')


if __name__ == '__main__':
    sys.exit(main())
