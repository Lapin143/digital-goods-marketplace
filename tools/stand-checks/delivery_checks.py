#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Недоставка ключа сквозь стенд (шаг 17 Ф4, К6; US-6.4, US-6.5, FT-7.3, NFT-2.3, NFT-2.4, NFT-4.3, BPMN-01 E9, E10, E11; TC-010, TC-011).

    make delivery-check

Нужен тот же стенд, что для orders_checks.py: набор full с ускоренными сроками и отладочным файлом (make fast-up DEBUG=1, make keycloak-users).
Контроль доставки на ускоренных сроках 3 минуты, паузы повторов выдачи 1, 3, 12, 30 и 60 секунд. Продавца и товары берёт у orders_checks.py
(secrets/purchase_fixture.json). Платёжная заглушка остаётся в режиме по умолчанию (шлюз сам оплачивает заказ), режим почтовой заглушки
меняется только на время заказа и возвращается на выходе.

  TC-010  почта не принимает письма выдачи (503 на каждую попытку): шесть попыток по расписанию с паузами 1, 3, 12, 30 и 60 секунд (с разбросом 20 %),
          выдача failed с причиной attempts_exhausted, заказ остаётся paid (ключа покупатель не получил), письма провайдеру не ушли;
          платформа завела обращение системы «attempts_exhausted» и написала администратору «Ключ не доставлен покупателю».
          Часть 2: окончательный отказ почты (422): одна попытка, повторов нет, выдача failed с причиной address_rejected, обращение «not_delivered»
  TC-011  часть 1: провайдер принял письмо и сообщил об отказе адреса (failed, address_rejected): одна принятая попытка, выдача failed с причиной
          address_rejected, обращение «not_delivered», письмо администратору; запоздалое «delivered» у выдачи в статусе failed ничего не меняет
          часть 2: провайдер принял письмо и больше ничего не сообщил: выдача остаётся sent, через 3 минуты после оплаты контроль передаёт заказ в поддержку
          (delivery.overdue), обращение «delivery_overdue», письмо администратору «Ключ не доставлен вовремя», заказ остаётся issued; запоздалое
          «delivered» закрывает контроль и решает обращение (resolved системой). У выдачи с отказом контроль через 3 минуты тоже срабатывает,
          но второго обращения нет (одно открытое обращение на заказ)

Сценарии идут по очереди с общими ожиданиями: заказ части 2 оплачивается первым и ждёт срока контроля, пока идут остальные (так весь прогон
укладывается в 5 – 6 минут). Только стандартная библиотека Python.
"""
import os
import re
import sys
import time

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
DELAYS = [1, 3, 12, 30, 60]          # паузы повторов выдачи в профиле fast-time, секунды
JITTER = 0.2
FAILED_SUBJECT = 'Ключ не доставлен покупателю'
OVERDUE_SUBJECT = 'Ключ не доставлен вовремя'
CONTROL_SECONDS = 180
ERROR_CODE = re.compile(r'^[a-z0-9_.-]{1,64}$')


# --------------------------------------------------------------------------------------------------- общие шаги
def attempt_times(delivery_id):
    """Попытки с временем: [(номер, исход, код, начало, конец)], время в секундах Unix."""
    rows = O.sql('delivery_db', "select attempt_no, outcome, coalesce(error_code, ''), extract(epoch from started_at), extract(epoch from finished_at) "
                                "from delivery.delivery_attempt where delivery_id = '%s' order by attempt_no" % delivery_id)
    return [(int(r[0]), r[1], r[2], float(r[3]), float(r[4])) for r in rows] if rows is not None else None


def admins_of_platform():
    rows = O.sql('platform_db', "select lower(email) from identity.user_account where role = 'admin' and status = 'active' and not anonymized")
    return [r[0] for r in rows] if rows is not None else None


def admin_letters(stubs, subject, order_id, wait=90.0):
    """Письма администраторам с темой и номером заказа в тексте; ждёт первого."""
    deadline = time.time() + wait
    while True:
        found = [m for m in stubs.emails(subject=subject) if order_id in P.body_of(m)]
        if found or time.time() >= deadline:
            return found
        time.sleep(2)


def provision_admin(fx):
    """Профиль администратора на платформе появляется при первом запросе с его токеном; письма уходят только администраторам платформы."""
    administrator = SA.Staff(fx['kc'], fx['admin'], fx['people']['admin-1'])
    listed = G.call('GET', '/api/v1/staff/users', headers=SC.auth(administrator.token()))
    admins = admins_of_platform() or []
    mine = fx['people']['admin-1']['email'].lower()
    expect('Администратор admin-1 известен платформе: список пользователей 200, среди активных администраторов (%d) он есть' % len(admins),
           listed.status == 200 and mine in admins, '%s %s' % (O.describe(listed), admins))
    fx['admins'] = admins
    return mine in admins


def start_order(fx, tag):
    """Покупатель и заказ на один ключ; оплачивает платёжная заглушка в режиме по умолчанию (сама присылает вебхук). Режим почты задаёт вызывающий.
    Возвращает (покупатель, идентификатор заказа) или (None, None)."""
    buyer = O.new_buyer(fx['kc'], fx['admin'], fx['stamp'], tag)
    if not buyer:
        return None, None
    fx['emails'].append(buyer.email)
    created = O.create_order(buyer, fx['plenty'], 1)
    body = created.json() if created.status == 201 else {}
    if not expect('%s: заказ на один ключ оформлен: 201, awaiting_payment' % tag, created.status == 201 and body.get('status') == 'awaiting_payment', O.describe(created)):
        return None, None
    fx['orders'].append(body['orderId'])
    return buyer, body['orderId']


def exhausted_attempts(oid, label):
    row = P.primary_of(oid) or {}
    attempts = attempt_times(row.get('id')) or []
    expect('%s. Шесть попыток, все с временной ошибкой провайдера, код ошибки один и по форме допустимый, ни адреса, ни ключа в коде нет' % label,
           [a[0] for a in attempts] == [1, 2, 3, 4, 5, 6] and {a[1] for a in attempts} == {'retryable_error'} and len({a[2] for a in attempts}) == 1
           and all(ERROR_CODE.match(a[2]) for a in attempts), str(attempts)[:300])
    gaps = [attempts[i + 1][3] - attempts[i][4] for i in range(len(attempts) - 1)]
    inside = [DELAYS[i] * (1 - JITTER) - 0.5 <= gaps[i] <= DELAYS[i] * (1 + JITTER) + 6 for i in range(len(gaps))] if len(gaps) == len(DELAYS) else [False]
    expect('%s. Паузы между попытками идут по расписанию 1, 3, 12, 30 и 60 секунд с разбросом 20 %% (получилось %s с)' % (label, ', '.join('%.1f' % g for g in gaps)), all(inside), str(gaps))
    return row


# --------------------------------------------------------------------------------------------------- TC-011, часть 2, старт
def overdue_start(fx):
    stubs = fx['stubs']
    print('== TC-011, часть 2, старт: провайдер принял письмо и больше ничего не сообщает, контроль доставки ждёт срока (3 минуты)')
    with stubs.email_mode(status='none'):
        buyer, oid = start_order(fx, 'd3')
        if not buyer:
            return
        ok, order = O.wait_order(buyer, oid, 'issued', wait=90)
    letters = P.wait_letters(stubs, to=buyer.email, sender='delivery')
    row = P.primary_of(oid) or {}
    watch = P.watch_of(oid) or ('', 0.0)
    paid = P.instant(order['paidAt']).timestamp() if ok and order.get('paidAt') else 0.0
    expect('TC-011. Провайдер принял письмо, а статуса нет: заказ issued, выдача sent с одной принятой попыткой, письмо в заглушке в статусе accepted',
           ok and row.get('status') == 'sent' and row.get('attempt_no') == 1 and P.attempts_of(row.get('id')) == [(1, 'accepted', '')]
           and len(letters) == 1 and letters[0].get('status') == 'accepted', '%s %s' % (row, [m.get('status') for m in letters]))
    expect('TC-011. Контроль доставки открыт, срок через %d секунд после оплаты (3 минуты в профиле fast-time)' % CONTROL_SECONDS,
           watch[0] == 'open' and paid > 0 and CONTROL_SECONDS - 10 <= watch[1] - paid <= CONTROL_SECONDS + 10, '%s %.0f' % (watch, watch[1] - paid))
    P.email_is_default(stubs, 'TC-011')
    fx['overdue'] = {'buyer': buyer, 'oid': oid, 'letter': letters[0] if letters else None, 'deadline': watch[1]}


# --------------------------------------------------------------------------------------------------- TC-010
def attempts_exhausted(fx):
    stubs = fx['stubs']
    print('== TC-010: почта не принимает письма, шесть попыток, обращение системы и письмо администратору (US-6.4, FT-7.3, NFT-4.3, BPMN-01 E9)')
    with stubs.email_mode(behavior='temp_error', sender='delivery', failFirst=-1):
        buyer, oid = start_order(fx, 'd1')
        if not buyer:
            return
        paid, order = O.wait_order(buyer, oid, 'paid', wait=60)
        failed = A.eventually(lambda: (P.primary_of(oid) or {}).get('status') == 'failed', wait=240, step=3.0)
    row = exhausted_attempts(oid, 'TC-010')
    card = O.get_order(buyer, oid)
    expect('TC-010. Заказ оплачен и остаётся paid: ключ покупателю не выдан, issued нет', paid and card.status == 200 and card.json().get('status') == 'paid'
           and not card.json().get('issuedAt'), O.describe(card))
    expect('TC-010. Выдача failed с причиной attempts_exhausted после шестой попытки, код ошибки записан; повторов в очереди нет',
           failed and row.get('status') == 'failed' and row.get('fail_reason') == 'attempts_exhausted' and row.get('attempt_no') == 6 and bool(ERROR_CODE.match(row.get('error_code') or '')), str(row))
    expect('TC-010. Провайдеру письмо не ушло: в заглушке писем от выдачи на адрес покупателя нет', not stubs.emails(to=buyer.email, sender='delivery'), str(stubs.emails(to=buyer.email, sender='delivery'))[:200])
    expect('TC-010. В Outbox выдачи одно delivery.failed, принятых и просроченных нет',
           [P.outbox_count('delivery_db', oid, t) for t in ('delivery.failed', 'delivery.accepted', 'delivery.overdue')] == [1, 0, 0],
           str([P.outbox_count('delivery_db', oid, t) for t in ('delivery.failed', 'delivery.accepted', 'delivery.overdue')]))
    ticket = A.eventually(lambda: P.tickets_of(oid) == [('system', 'attempts_exhausted', 'created', '')], wait=90, step=3.0)
    expect('TC-010. Обращение системы одно: причина attempts_exhausted, статус created, оператора нет', ticket, str(P.tickets_of(oid)))
    expect('TC-010. В карточке поддержки статус выдачи failed', A.eventually(lambda: P.support_delivery_status(oid) == 'failed', wait=30), str(P.support_delivery_status(oid)))
    letters = admin_letters(stubs, FAILED_SUBJECT, oid)
    text = P.body_of(letters[0]) if letters else ''
    recipients = {a for m in letters for a in m['to']}
    expect('TC-010. Администратору ушло письмо «%s»: номер заказа, шесть попыток, причина словами, ключей в письме нет' % FAILED_SUBJECT,
           bool(letters) and fx['people']['admin-1']['email'].lower() in {r.lower() for r in recipients} and 'попыток отправки: 6' in text
           and 'письмо не принято после всех попыток отправки' in text and P.KEY_PREFIX not in text, '%s %s' % (recipients, text[:200]))
    mails = stubs.emails(to=buyer.email)
    expect('TC-010. У покупателя нет ни письма о сбое, ни ключей: только письма платформы «Заказ создан» и «Заказ оплачен»',
           {m['subject'] for m in mails} <= {P.CREATED_SUBJECT, P.PAID_SUBJECT} and not any(P.KEY_PREFIX in P.body_of(m) for m in mails), str([m['subject'] for m in mails]))
    P.email_is_default(stubs, 'TC-010')
    O.modes_are_default(stubs, 'TC-010')
    fx['failed_order'] = oid


def permanent_rejection(fx):
    stubs = fx['stubs']
    print('== TC-010, часть 2: почта отвечает окончательным отказом 422: повторов нет, одна попытка (US-6.4, FT-7.3, BPMN-01 E9)')
    with stubs.email_mode(behavior='perm_error', sender='delivery', failFirst=-1, permStatus=422):
        buyer, oid = start_order(fx, 'd4')
        if not buyer:
            return
        paid, order = O.wait_order(buyer, oid, 'paid', wait=60)
        failed = A.eventually(lambda: (P.primary_of(oid) or {}).get('status') == 'failed', wait=60, step=2.0)
    row = P.primary_of(oid) or {}
    card = O.get_order(buyer, oid)
    expect('TC-010. Окончательный отказ (422) не повторяют: выдача failed сразу после первой попытки, причина address_rejected, попытка одна и окончательная',
           failed and row.get('fail_reason') == 'address_rejected' and row.get('attempt_no') == 1 and P.attempts_of(row.get('id')) == [(1, 'permanent_error', 'address_rejected')],
           '%s %s' % (row, P.attempts_of(row.get('id'))))
    expect('TC-010. Заказ остаётся paid, ключ покупателю не выдан, провайдеру письмо не ушло', paid and card.status == 200 and card.json().get('status') == 'paid'
           and not stubs.emails(to=buyer.email, sender='delivery'), O.describe(card))
    expect('TC-010. Обращение системы одно: причина not_delivered (адрес отклонён), статус created',
           A.eventually(lambda: P.tickets_of(oid) == [('system', 'not_delivered', 'created', '')], wait=90, step=3.0), str(P.tickets_of(oid)))
    letters = admin_letters(stubs, FAILED_SUBJECT, oid)
    text = P.body_of(letters[0]) if letters else ''
    expect('TC-010. Администратору ушло письмо «%s» с причиной «адрес покупателя отклонён почтовым провайдером» и одной попыткой' % FAILED_SUBJECT,
           bool(letters) and 'адрес покупателя отклонён почтовым провайдером' in text and 'попыток отправки: 1' in text, text[:240])
    P.email_is_default(stubs, 'TC-010')


# --------------------------------------------------------------------------------------------------- TC-011, часть 1
def address_rejected(fx):
    stubs = fx['stubs']
    print('== TC-011, часть 1: провайдер принял письмо и сообщил об отказе адреса (US-6.4, FT-7.3, BPMN-01 E10)')
    with stubs.email_mode(status='failed', failReason='address_rejected'):
        buyer, oid = start_order(fx, 'd2')
        if not buyer:
            return
        issued, order = O.wait_order(buyer, oid, 'issued', wait=90)
        failed = A.eventually(lambda: (P.primary_of(oid) or {}).get('status') == 'failed', wait=60, step=2.0)
    row = P.primary_of(oid) or {}
    letters = P.wait_letters(stubs, to=buyer.email, sender='delivery')
    expect('TC-011. Провайдер принял письмо (заказ issued), затем сообщил об отказе: выдача failed, причина address_rejected, код provider_address_rejected, одна принятая попытка',
           issued and failed and row.get('fail_reason') == 'address_rejected' and row.get('error_code') == 'provider_address_rejected' and row.get('attempt_no') == 1
           and P.attempts_of(row.get('id')) == [(1, 'accepted', '')] and 'failed' in (P.provider_statuses(row.get('id')) or []), '%s %s' % (row, P.attempts_of(row.get('id'))))
    expect('TC-011. В заглушке письмо в статусе failed с причиной address_rejected', len(letters) == 1 and letters[0].get('status') == 'failed' and letters[0].get('reason') == 'address_rejected',
           str([(m.get('status'), m.get('reason')) for m in letters]))
    expect('TC-011. Заказ остаётся issued (провайдер принял письмо), в Outbox выдачи одно delivery.accepted и одно delivery.failed',
           O.get_order(buyer, oid).json().get('status') == 'issued' and [P.outbox_count('delivery_db', oid, t) for t in ('delivery.accepted', 'delivery.failed')] == [1, 1],
           str([P.outbox_count('delivery_db', oid, t) for t in ('delivery.accepted', 'delivery.failed')]))
    expect('TC-011. Обращение системы одно: причина not_delivered, статус created; в карточке поддержки выдача failed',
           A.eventually(lambda: P.tickets_of(oid) == [('system', 'not_delivered', 'created', '')], wait=90, step=3.0)
           and A.eventually(lambda: P.support_delivery_status(oid) == 'failed', wait=30), '%s %s' % (P.tickets_of(oid), P.support_delivery_status(oid)))
    admin = admin_letters(stubs, FAILED_SUBJECT, oid)
    text = P.body_of(admin[0]) if admin else ''
    expect('TC-011. Администратору ушло письмо «%s» с причиной «адрес отклонён провайдером» и одной попыткой' % FAILED_SUBJECT,
           bool(admin) and 'адрес покупателя отклонён почтовым провайдером' in text and 'попыток отправки: 1' in text, text[:240])
    # Запоздалое «delivered» после отказа: состояние «ошибка» конечное, провайдеру отвечают 200 и ничего не меняют
    if letters:
        stubs.email_status(letters[0]['messageId'], 'delivered')
        stubs.idle()
    time.sleep(2)
    expect('TC-011. Запоздалое «delivered» после отказа ничего не меняет: выдача по-прежнему failed, события delivery.delivered нет, обращение не решено',
           (P.primary_of(oid) or {}).get('status') == 'failed' and P.outbox_count('delivery_db', oid, 'delivery.delivered') == 0
           and P.tickets_of(oid) == [('system', 'not_delivered', 'created', '')], '%s %s' % (P.primary_of(oid), P.tickets_of(oid)))
    P.email_is_default(stubs, 'TC-011')
    O.modes_are_default(stubs, 'TC-011')


# --------------------------------------------------------------------------------------------------- TC-011, часть 2, финиш
def overdue_finish(fx):
    stubs = fx['stubs']
    print('== TC-011, часть 2: контроль доставки передаёт заказ в поддержку, запоздалое «delivered» решает обращение (US-6.5, NFT-2.4, BPMN-01 E11)')
    state = fx.get('overdue')
    if not state:
        G.bad('Контроль: заказ части 2 не оформлен, сценарий просрочки не запускался')
        return
    buyer, oid = state['buyer'], state['oid']
    wait = max(0.0, state['deadline'] - time.time()) + 90
    handed = A.eventually(lambda: (P.watch_of(oid) or ('', 0))[0] == 'handed_over', wait=wait, step=3.0)
    expect('TC-011. Через 3 минуты после оплаты контроль передал заказ в поддержку: handed_over, одно delivery.overdue в Outbox выдачи',
           handed and P.outbox_count('delivery_db', oid, 'delivery.overdue') == 1, '%s %s' % (P.watch_of(oid), P.outbox_count('delivery_db', oid, 'delivery.overdue')))
    expect('TC-011. Статус выдачи контроль не меняет: sent; заказ остаётся issued', (P.primary_of(oid) or {}).get('status') == 'sent'
           and O.get_order(buyer, oid).json().get('status') == 'issued', str(P.primary_of(oid)))
    expect('TC-011. Обращение системы одно: причина delivery_overdue, статус created',
           A.eventually(lambda: P.tickets_of(oid) == [('system', 'delivery_overdue', 'created', '')], wait=90, step=3.0), str(P.tickets_of(oid)))
    letters = admin_letters(stubs, OVERDUE_SUBJECT, oid)
    expect('TC-011. Администратору ушло письмо «%s» с номером заказа, ключей в письме нет' % OVERDUE_SUBJECT,
           bool(letters) and P.KEY_PREFIX not in P.body_of(letters[0]), str(len(letters)))

    # Запоздалое «delivered» закрывает контроль и решает обращение
    message = state.get('letter') or {}
    if message.get('messageId'):
        stubs.email_status(message['messageId'], 'delivered')
        stubs.idle()
    done = A.eventually(lambda: (P.primary_of(oid) or {}).get('status') == 'delivered', wait=30)
    expect('TC-011. Запоздалое «delivered»: выдача delivered, контроль closed, одно delivery.delivered', done and (P.watch_of(oid) or ('', 0))[0] == 'closed'
           and P.outbox_count('delivery_db', oid, 'delivery.delivered') == 1, '%s %s' % (P.primary_of(oid), P.watch_of(oid)))
    solved = A.eventually(lambda: P.tickets_of(oid) == [('system', 'delivery_overdue', 'resolved', 'system')], wait=90, step=3.0)
    expect('TC-011. Обращение решено системой по факту доставки (resolved, resolved_by = system), в карточке поддержки выдача delivered',
           solved and A.eventually(lambda: P.support_delivery_status(oid) == 'delivered', wait=30), '%s %s' % (P.tickets_of(oid), P.support_delivery_status(oid)))

    # Выдача, закончившаяся ошибкой в TC-010: контроль срабатывает и у неё, но второго обращения нет
    failed = fx.get('failed_order')
    if failed:
        until = (P.watch_of(failed) or ('', time.time()))[1]
        passed = A.eventually(lambda: (P.watch_of(failed) or ('', 0))[0] == 'handed_over', wait=max(0.0, until - time.time()) + 60, step=3.0)
        time.sleep(5)
        expect('TC-011. У выдачи TC-010 контроль тоже передан в поддержку, но обращение по заказу по-прежнему одно (одно открытое обращение на заказ)',
               passed and P.tickets_of(failed) == [('system', 'attempts_exhausted', 'created', '')], '%s %s' % (P.watch_of(failed), P.tickets_of(failed)))
    P.email_is_default(stubs, 'TC-011')
    O.modes_are_default(stubs, 'TC-011')


# --------------------------------------------------------------------------------------------------- запуск
def run_scenarios(fx):
    fx['stubs'].reset()
    if not O.guarded('provision_admin', provision_admin, fx):
        G.bad('Контроль: администратор платформы не найден, письма администраторам проверить нельзя')
    for fn in (overdue_start, attempts_exhausted, permanent_rejection, address_rejected, overdue_finish):
        O.guarded(fn.__name__, fn, fx)
    O.guarded('leak_scan', P.leak_scan, fx, 'delivery_checks')


def main():
    kc, admin = K.from_env(dict(os.environ))
    people = G.users()
    for name in ('moderator-1', 'admin-1'):
        if name not in people:
            print('Нет тестового пользователя %s (make keycloak-users)' % name, file=sys.stderr)
            return 2
    fx = O.context(kc, admin, people, int(time.time()), scarce_needed=False)
    if fx:
        fx['people'] = people
        fx['orders'] = []
        run_scenarios(fx)
    else:
        G.bad('Продавец и товары для заказов не заведены, сценарии недоставки не запускались')
    return O.summary('delivery_checks')


if __name__ == '__main__':
    sys.exit(main())
