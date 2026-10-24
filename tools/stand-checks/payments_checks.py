#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Платежи сквозь стенд: повторное и потерянное уведомление, поздняя оплата, автовозврат и ручной возврат (шаг 12 Ф4, К5; US-5.5 – US-5.8, FT-6.2 – FT-6.5, NFT-2.3, NFT-4.3; TC-006 – TC-009).

    make payments-check

Нужен тот же стенд, что для orders_checks.py: набор full с ускоренными сроками и отладочным файлом (make fast-up DEBUG=1, make keycloak-users).
Продавца и товары берёт у orders_checks.py (secrets/purchase_fixture.json), если ключи целы, иначе заводит заново. Режимы платёжной заглушки
(stubs_admin.StubsAdmin) задаются на время сценария и возвращаются к умолчанию на выходе.

  повтор      TC-006 (E5): шлюз прислал «оплачен» три раза одновременно и ещё раз после оплаты: заказ оплачен один раз, в Outbox одно order.paid,
              резерв использован один раз, все копии приняты с 200
  потеря      вебхук не отправлен (режим send=false), заглушка у себя платёж оплатила: заказ становится оплаченным только сверкой платежей
              (ADR-015, шаг 8); без сверки этот сценарий красный. Контроль: в журнале заглушки вебхук помечен потерянным, а доставленных нет
  поздняя     TC-007 (E6): подтверждение после снятия резерва, ключей хватает: резерв создаётся заново, заказ cancelled → paid, дальше выдача
              как обычно (шаг 17): заказ issued, одно письмо с двумя разными ключами;
              TC-008 (E7): ключей не хватает: частичной выдачи нет, автовозврат всей суммы, платёж и заказ refunded, покупатель получил
              письмо о возврате с причиной (шаг 17), писем с ключами у него нет;
              TC-009 (E8): шлюз не принимает возврат: попытки по расписанию, очередь администратора, администратор отмечает возврат
              выполненным, платёж и заказ refunded, повторная отметка 409 refund-not-allowed

Три заказа ждут истечения резерва одновременно (30 секунд), поэтому сценарии поздней оплаты укладываются в несколько минут. Только
стандартная библиотека Python.
"""
import os
import sys
import time
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import accounts_checks as A  # noqa: E402
import gateway_checks as G  # noqa: E402
import kc_client as K  # noqa: E402
import orders_checks as O  # noqa: E402
import purchase_checks as P  # noqa: E402
import seller_approval_checks as SA  # noqa: E402
import sellers_checks as SC  # noqa: E402

expect = G.expect
MANUAL_REFUNDS = '/api/v1/staff/manual-refunds'


def journal_of(stubs, order_id, kind='webhook', event_type=None):
    items = stubs.journal(system='payment', kind=kind).get('items') or []
    return [e for e in items if e.get('orderId') == order_id and (event_type is None or e.get('type') == event_type)]


def open_order(buyer, product_id, quantity, **kw):
    """Заказ оформлен: (тело ответа или None, идентификатор платежа у заглушки)."""
    created = O.create_order(buyer, product_id, quantity, **kw)
    body = created.json() if created.status == 201 else None
    if not expect('Заказ на %d шт. оформлен: 201' % quantity, body is not None and body.get('status') == 'awaiting_payment', O.describe(created)):
        return None, None
    return body, O.stub_payment_id(body['paymentSession']['url'])


# --------------------------------------------------------------------------------------------------- сценарии
def repeated_notification(fx):
    kc, admin, stubs = fx['kc'], fx['admin'], fx['stubs']
    print('== Шлюз повторяет уведомление об оплате (US-5.5, FT-6.2, NFT-2.3; TC-006, BPMN-01 E5)')
    stubs.reset()
    buyer = P.new_buyer(kc, admin, fx['stamp'], 'p1')
    if not buyer:
        return
    pid = fx['plenty']
    correlation = str(uuid.uuid4())
    with stubs.payment_mode(scenario='success', delayMs=300, webhook={'repeat': 3, 'parallel': True}):
        body, spid = open_order(buyer, pid, 1, correlation=correlation)
        if not body:
            return
        oid = body['orderId']
        ok, paid = O.wait_order(buyer, oid, 'paid', wait=30)
        stubs.idle()
    fx['correlations'].append(correlation)
    fx['emails'].append(buyer.email)
    expect('TC-006. Заказ оплачен: paid, время оплаты записано', ok and paid.get('paidAt'), str(paid)[:300])
    sent = journal_of(stubs, oid, event_type='payment.paid')
    accepted = [e for e in sent if e.get('status') == 200]
    expect('TC-006. Контроль: шлюз прислал «оплачен» три раза одновременно и все копии приняты с 200 (отправлено %d, принято %d)' % (len(sent), len(accepted)),
           len({e.get('copy') for e in accepted}) == 3, str(sent)[:300])
    expect('TC-006. Оплата применена один раз: в Outbox одно order.paid, резерв использован один раз, платёж confirmed',
           O.outbox_count(oid, 'order.paid') == 1 and O.reservations_of(oid) == [('used', '', 1)] and O.payment_of(oid) and O.payment_of(oid)[0] == 'confirmed',
           '%s %s %s' % (O.outbox_count(oid, 'order.paid'), O.reservations_of(oid), O.payment_of(oid)))
    paid_at = paid.get('paidAt')
    stubs.resend_webhook(spid, 'payment.paid')
    stubs.idle()
    after = O.get_order(buyer, oid)
    expect('TC-006. Повтор уведомления уже после оплаты ничего не меняет: статус paid или issued, время оплаты прежнее, второго order.paid нет',
           after.status == 200 and O.reached_status('paid', after.json().get('status')) and after.json().get('paidAt') == paid_at and O.outbox_count(oid, 'order.paid') == 1, O.describe(after))
    page, again = O.pay_on_page(body['paymentSession']['url'])
    expect('TC-006. Повторное нажатие «Оплатить» на странице уже оплаченного платежа безвредно: возврат на платформу 303, заказ тот же',
           again.status == 303 and O.get_order(buyer, oid).json().get('paidAt') == paid_at and O.outbox_count(oid, 'order.paid') == 1, '%s' % again.status)
    O.modes_are_default(stubs, 'TC-006')


def lost_notification(fx):
    kc, admin, stubs = fx['kc'], fx['admin'], fx['stubs']
    print('== Вебхук потерян: платёж подбирает сверка со шлюзом (FT-6.2, NFT-4.3, ADR-015; TC-006)')
    stubs.reset()
    buyer = P.new_buyer(kc, admin, fx['stamp'], 'p2')
    if not buyer:
        return
    with stubs.payment_mode(scenario='success', delayMs=300, webhook={'send': False}):
        body, spid = open_order(buyer, fx['plenty'], 1)
        if not body:
            return
        oid = body['orderId']
        stubs.idle()
        lost = journal_of(stubs, oid, event_type='payment.paid')
        expect('TC-006. Контроль: шлюз оплатил платёж у себя, а вебхук потерян и не доставлен ни разу',
               stubs.payment(spid).get('status') == 'paid' and lost and all(e.get('outcome') == 'lost' for e in lost), str(lost)[:300])
        ok, paid = O.wait_order(buyer, oid, 'paid', wait=75)
        delivered = [e for e in journal_of(stubs, oid, event_type='payment.paid') if e.get('outcome') != 'lost']
    expect('TC-006. Без вебхука заказ оплачен сверкой платежей: paid в течение 75 секунд', ok and paid.get('paidAt'), str(paid)[:300])
    expect('TC-006. Вебхук так и не пришёл (оплату нашла сверка, а не уведомление), платёж confirmed', not delivered and O.payment_of(oid) and O.payment_of(oid)[0] == 'confirmed',
           '%s %s' % (delivered, O.payment_of(oid)))
    O.modes_are_default(stubs, 'TC-006')
    fx['emails'].append(buyer.email)


def late_payments(fx):
    kc, admin, stubs = fx['kc'], fx['admin'], fx['stubs']
    print('== Поздняя оплата: ключи есть, ключей нет, возврат не принят (US-5.6 – US-5.8, FT-6.4, FT-6.5, INV-05; TC-007, TC-008, TC-009, BPMN-01 E6, E7, E8)')
    stubs.reset()
    plenty, scarce = fx['plenty'], fx['scarce']
    people = fx['people']
    names = ['p7', 'p8', 'p9', 'pb']
    buyers = {n: P.new_buyer(kc, admin, fx['stamp'], n) for n in names}
    if not all(buyers.values()):
        return
    fx['emails'] += [b.email for b in buyers.values()]
    before_scarce = O.keys_of(scarce) or {}
    before_plenty = O.keys_of(plenty) or {}
    orders = {}
    with stubs.payment_mode(scenario='hold'):
        for name, product, quantity in (('p7', plenty, 2), ('p8', scarce, 2), ('p9', scarce, 2)):
            body, spid = open_order(buyers[name], product, quantity)
            if not body:
                return
            orders[name] = (body['orderId'], spid, SC.instant(body['reserveExpiresAt']))
    held = O.keys_of(scarce) or {}
    expect('Два заказа взяли в резерв все %d ключа редкого товара' % O.SCARCE_KEYS, held.get('reserved', 0) == before_scarce.get('free', 0) and not held.get('free'), '%s -> %s' % (before_scarce, held))

    print('-- Ждём истечения резерва трёх заказов (30 секунд по профилю fast-time)')
    for name in ('p7', 'p8', 'p9'):
        ok, cancelled = O.wait_order(buyers[name], orders[name][0], 'cancelled', wait=120)
        expect('Заказ %s отменён по сроку резерва: cancelled, reservation_expired' % name, ok and cancelled.get('cancelReason') == 'reservation_expired', str(cancelled)[:240])
    expect('Ключи редкого товара снова свободны', A.eventually(lambda: O.keys_of(scarce) == before_scarce, wait=30), '%s -> %s' % (before_scarce, O.keys_of(scarce)))

    # Другой покупатель забирает три ключа из четырёх и платит (режим по умолчанию: заглушка сама присылает вебхук)
    body, _ = open_order(buyers['pb'], scarce, 3)
    if not body:
        return
    ok, paid = O.wait_order(buyers['pb'], body['orderId'], 'paid', wait=40)
    taken = O.keys_of(scarce) or {}
    expect('Другой покупатель оплатил три ключа: три выданы, свободен один, меньше заказанных двух', ok and taken.get('free') == 1 and taken.get('issued') == 3 and not taken.get('reserved'), '%s %s' % (paid.get('status'), taken))

    # TC-007: ключей хватает
    oid, spid, reserve_until = orders['p7']
    stubs.confirm_payment(spid)
    ok, late = O.wait_order(buyers['p7'], oid, 'paid', wait=45)
    expect('TC-007. Подтверждение пришло после снятия резерва, ключей хватает: заказ cancelled → paid', ok and late.get('paidAt'), str(late)[:300])
    expect('TC-007. Оплата позже срока резерва: время оплаты после reserveExpiresAt', ok and SC.instant(late['paidAt']) > reserve_until, '%s %s' % (late.get('paidAt'), reserve_until))
    expect('TC-007. Ключи зарезервированы заново: первый резерв снят по сроку, второй использован; свободных на 2 меньше, чем до заказа',
           O.reservations_of(oid) == [('released', 'expired', 2), ('used', '', 2)] and (O.keys_of(plenty) or {}).get('free') == before_plenty.get('free', 0) - 2,
           '%s %s -> %s' % (O.reservations_of(oid), before_plenty, O.keys_of(plenty)))
    expect('TC-007. Платёж confirmed, возврата нет, в Outbox одно order.paid', O.payment_of(oid) and O.payment_of(oid)[0] == 'confirmed' and O.outbox_count(oid, 'order.paid') == 1
           and O.outbox_count(oid, 'order.refund-requested') == 0, '%s %s' % (O.payment_of(oid), O.outbox_count(oid, 'order.paid')))
    issued_ok, issued = O.wait_order(buyers['p7'], oid, 'issued', wait=60)
    letter, keys = P.key_letter('TC-007', stubs, buyers['p7'].email, 2)
    expect('TC-007. После поздней оплаты выдача идёт как обычно: заказ issued со временем выдачи, письмо с двумя разными ключами одно', issued_ok and bool(issued.get('issuedAt')) and len(keys) == 2,
           '%s %s' % (str(issued)[:200], len(keys)))

    # TC-008: ключей не хватает, шлюз принимает возврат сразу
    oid, spid, _ = orders['p8']
    stubs.confirm_payment(spid)
    ok, refunded = O.wait_order(buyers['p8'], oid, 'refunded', wait=75)
    expect('TC-008. Подтверждение пришло, ключей меньше заказанного: частичной выдачи нет, автовозврат, заказ refunded', ok, str(refunded)[:300])
    expect('TC-008. Платёж refunded по причине late_payment, шлюз вернул деньги, нового резерва нет',
           O.payment_of(oid) == ('refunded', False, 'late_payment') and O.reservations_of(oid) == [('released', 'expired', 2)] and stubs.payment(spid).get('status') == 'refunded',
           '%s %s %s' % (O.payment_of(oid), O.reservations_of(oid), stubs.payment(spid).get('status')))
    expect('TC-008. Один запрос возврата и одно order.refunded; чужие ключи не тронуты (свободен один)', O.outbox_count(oid, 'order.refund-requested') == 1
           and O.outbox_count(oid, 'order.refunded') == 1 and (O.keys_of(scarce) or {}).get('free') == 1, '%s %s' % (O.outbox_count(oid, 'order.refunded'), O.keys_of(scarce)))
    expect('TC-008. Покупатель видит возврат в истории заказов', any(i.get('orderId') == oid for i in O.list_orders(buyers['p8'], 'refunded')))
    refund_letters = P.wait_letters(stubs, to=buyers['p8'].email, subject='Деньги за заказ возвращены')
    refund_text = P.body_of(refund_letters[0]) if refund_letters else ''
    expect('TC-008. Покупатель получил одно письмо «Деньги за заказ возвращены» с номером заказа и причиной «оплата пришла после того, как срок заказа истёк»',
           len(refund_letters) == 1 and '№%s' % refunded.get('orderNumber') in refund_text and 'оплата пришла после того, как срок заказа истёк' in refund_text,
           '%s %s' % (len(refund_letters), refund_text[:200]))
    expect('TC-008. Ключей покупателю не выдавали: писем от выдачи у него нет, ключей в его письмах нет', not stubs.emails(to=buyers['p8'].email, sender='delivery')
           and not any(P.KEY_PREFIX in P.body_of(m) for m in stubs.emails(to=buyers['p8'].email)), str([m['subject'] for m in stubs.emails(to=buyers['p8'].email)]))

    # TC-009: шлюз возврат не принимает
    oid, spid, _ = orders['p9']
    with stubs.payment_mode(refund={'mode': 'reject'}):
        stubs.confirm_payment(spid)
        queued = A.eventually(lambda: O.payment_of(oid) and O.payment_of(oid)[1], wait=300, step=3.0)
        still = O.get_order(buyers['p9'], oid)
    expect('TC-009. Шлюз не принимает возврат: после попыток по расписанию платёж в очереди администратора, confirmed', queued and O.payment_of(oid)[0] == 'confirmed', str(O.payment_of(oid)))
    expect('TC-009. Пока администратор не отметил возврат, заказ остаётся cancelled', still.status == 200 and still.json().get('status') == 'cancelled', O.describe(still))
    administrator = SA.Staff(kc, admin, people['admin-1'])
    rows = O.sql('payment_db', "select id from payments.payment where order_id = '%s'" % oid)
    payment_id = rows[0][0] if rows else ''
    queue = G.call('GET', MANUAL_REFUNDS + '?limit=100', headers=SC.auth(administrator.token()))
    mine = [i for i in (queue.json().get('items') or [])] if queue.status == 200 else []
    mine = [i for i in mine if i.get('orderId') == oid]
    expect('TC-009. Очередь ручных возвратов показывает платёж: сумма, причина late_payment, число попыток', queue.status == 200 and len(mine) == 1 and mine[0].get('paymentId') == payment_id
           and mine[0].get('refundReason') == 'late_payment' and (mine[0].get('amount') or {}).get('amount') == O.PRICE * 2 and (mine[0].get('attempts') or 0) >= 1, O.describe(queue))
    done = SC.post_json('%s/%s/complete' % (MANUAL_REFUNDS, payment_id), administrator.token(), {'externalRefundId': 'rf_manual_%d' % fx['stamp']})
    expect('TC-009. Администратор отметил возврат выполненным: 200, платёж refunded', done.status == 200 and done.json().get('paymentStatus') == 'refunded', O.describe(done))
    ok, refunded = O.wait_order(buyers['p9'], oid, 'refunded', wait=45)
    expect('TC-009. Заказ стал refunded после отметки администратора', ok, str(refunded)[:300])
    repeat = SC.post_json('%s/%s/complete' % (MANUAL_REFUNDS, payment_id), administrator.token(), {'externalRefundId': 'rf_manual_again_%d' % fx['stamp']})
    expect('TC-009. Повторная отметка: 409 refund-not-allowed, статусы прежние', repeat.status == 409 and repeat.code() == 'refund-not-allowed' and O.payment_of(oid)[0] == 'refunded', O.describe(repeat))
    expect('TC-009. Платёж ушёл из очереди, в Outbox одно order.refunded', O.outbox_count(oid, 'order.refunded') == 1 and not [
        i for i in (G.call('GET', MANUAL_REFUNDS + '?limit=100', headers=SC.auth(administrator.token())).json().get('items') or []) if i.get('orderId') == oid])
    O.modes_are_default(stubs, 'TC-009')


def run_scenarios(fx):
    for fn in (repeated_notification, lost_notification, late_payments):
        O.guarded(fn.__name__, fn, fx)


def main():
    kc, admin = K.from_env(dict(os.environ))
    people = G.users()
    for name in ('moderator-1', 'admin-1'):
        if name not in people:
            print('Нет тестового пользователя %s (make keycloak-users)' % name, file=sys.stderr)
            return 2
    fx = O.context(kc, admin, people, int(time.time()))
    if fx:
        fx['people'] = people
        run_scenarios(fx)
        O.guarded('no_personal_data', O.no_personal_data, fx)
    else:
        G.bad('Продавец и товары для заказов не заведены, сценарии платежей не запускались')
    return O.summary('payments_checks')


if __name__ == '__main__':
    sys.exit(main())
