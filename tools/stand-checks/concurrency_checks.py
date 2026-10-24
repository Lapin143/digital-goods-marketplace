#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Гонка за последний ключ и потерянный ответ шлюза сквозь стенд (шаг 18 Ф4, К7; US-5.2, FT-5.2, FT-6.1, NFT-2.2, NFT-4.3, INV-01, INV-05; ST-10, TC-040).

    make concurrency-check

Нужен тот же стенд, что для orders_checks.py: набор full с ускоренными сроками и отладочным файлом (make fast-up DEBUG=1, make keycloak-users).
Продавца и товары берёт у orders_checks.py (secrets/purchase_fixture.json): товар `race` с одним ключом тратится этой проверкой, поэтому второй
запуск подряд заводит продавца и товары заново. Платёжная и почтовая заглушки (stubs_admin.StubsAdmin) возвращаются в исходный режим на выходе.

  гонка       ST-10 (та же гонка в JUnit на 20 потоках описана в TC-concurrency.md): два покупателя одновременно, по общей защёлке, оформляют заказ на последний
              ключ товара через шлюз платформы. Один получает 201, другой 409 insufficient-stock, а не 5xx; ключ в резерве один и один резерв;
              проигравший не получил ни ключа, ни письма от выдачи; победитель платит и получает письмо с одним ключом; после продажи ключа заказ
              проигравшего по-прежнему 409. SQL-проверка: ни один заказ не держит больше одного ключа, а ключ не принадлежит двум заказам
  потеря      TC-040 (NFT-4.3, SEQ-01): шлюз создал платёж, а ответ не дошёл (loseResponseFirst). Клиент платёжной службы повторяет вызов с тем же ключом
              идемпотентности и находит тот же платёж: заказ оформлен, у шлюза один платёж, в базе платежей одна строка, оплата доводит заказ до
              выдачи. Недоступность шлюза при открытии сессии проверяет сценарий «сессия» orders_checks.py, почту, отказывающую шесть раз, сценарии delivery_checks.py: все три части описаны в TC-040

k6-сценарий той же гонки (ST-10, нагрузка малая) входит в нагрузочный набор шага 23 и запускается на сервере. Только стандартная библиотека Python.
"""
import concurrent.futures
import os
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import accounts_checks as A  # noqa: E402
import gateway_checks as G  # noqa: E402
import kc_client as K  # noqa: E402
import orders_checks as O  # noqa: E402
import purchase_checks as P  # noqa: E402

expect = G.expect


def duplicates(product_id):
    """Сколько заказов держат больше одного ключа и сколько резервов заведено на ключи товара (None: база не ответила)."""
    per_order = O.sql('inventory_db', "select count(*) from (select order_id from inventory.key where product_id = '%s' and order_id is not null "
                                      "group by order_id having count(*) > 1) t" % product_id)
    reservations = O.sql('inventory_db', "select count(distinct reservation_id) from inventory.key where product_id = '%s' and reservation_id is not null" % product_id)
    if per_order is None or reservations is None:
        return None, None
    return int(per_order[0][0]), int(reservations[0][0])


def last_key_race(fx):
    kc, admin, stubs = fx['kc'], fx['admin'], fx['stubs']
    print('== Два заказа на последний ключ: один выигрывает, другой получает 409 (US-5.2, FT-5.2, INV-01, INV-05; ST-10)')
    stubs.reset()
    first = P.new_buyer(kc, admin, fx['stamp'], 'r1')
    second = P.new_buyer(kc, admin, fx['stamp'], 'r2')
    if not first or not second:
        return
    pid = fx['race']
    before = O.keys_of(pid)
    if not expect('ST-10. Перед гонкой у товара один свободный ключ и больше ничего', before == {'free': O.RACE_KEYS}, str(before)):
        return
    first.token()
    second.token()   # токены получены до гонки: в ней участвует только запрос заказа
    gate = threading.Barrier(2)

    def attempt(buyer):
        gate.wait(timeout=30)
        return O.create_order(buyer, pid, 1)

    with stubs.payment_mode(scenario='hold'):
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            answers = [f.result(timeout=120) for f in [pool.submit(attempt, b) for b in (first, second)]]
        statuses = sorted(r.status for r in answers)
        if not expect('ST-10. Два одновременных заказа на один ключ: один 201, другой 409', statuses == [201, 409], str([O.describe(r) for r in answers])):
            return
        winner, win = [(b, r) for b, r in zip((first, second), answers) if r.status == 201][0]
        loser, lose = [(b, r) for b, r in zip((first, second), answers) if r.status == 409][0]
        expect('ST-10. Проигравший получил 409 insufficient-stock, а не 5xx', lose.code() == 'insufficient-stock', O.describe(lose))
        body = win.json()
        oid = body['orderId']
        expect('ST-10. Ключ в резерве один, свободных нет', O.keys_of(pid) == {'reserved': 1}, str(O.keys_of(pid)))
        expect('ST-10. Резерв победителя один, активный, на одну штуку', O.reservations_of(oid) == [('active', '', 1)], str(O.reservations_of(oid)))
        extra, reservations = duplicates(pid)
        expect('ST-10. Ни один заказ не держит больше одного ключа, на ключи товара заведён один резерв (INV-01, INV-05)', extra == 0 and reservations == 1, '%s %s' % (extra, reservations))
        cancelled = [i for i in O.list_orders(loser, 'cancelled') if i.get('productId') == pid]
        expect('ST-10. Заказ проигравшего отменён с причиной out_of_stock, платежа у шлюза нет',
               len(cancelled) == 1 and cancelled[0].get('cancelReason') == 'out_of_stock' and not O.stub_payments(stubs, cancelled[0]['orderId']), str(cancelled)[:300])

        order, seconds = P.pay_and_wait_issue(winner, body)
    expect('ST-10. Победитель заплатил, заказ стал issued (%.1f с)' % seconds, bool(order), str(order)[:300])
    expect('ST-10. Ключ выдан один, свободных и зарезервированных нет', A.eventually(lambda: O.keys_of(pid) == {'issued': 1}, wait=30), str(O.keys_of(pid)))
    letter, keys = P.key_letter('ST-10', stubs, winner.email, 1)
    expect('ST-10. Победитель получил письмо с одним ключом', bool(letter) and len(keys) == 1, str(keys))
    expect('ST-10. Проигравший не получил ни ключа, ни письма от выдачи', stubs.emails(to=loser.email, sender='delivery') == [], str(stubs.emails(to=loser.email, sender='delivery'))[:200])
    again = O.create_order(loser, pid, 1)
    expect('ST-10. После продажи ключа повторный заказ проигравшего по-прежнему 409 insufficient-stock', again.status == 409 and again.code() == 'insufficient-stock', O.describe(again))
    O.modes_are_default(stubs, 'ST-10')
    fx['emails'] += [first.email, second.email]


def lost_gateway_answer(fx):
    kc, admin, stubs = fx['kc'], fx['admin'], fx['stubs']
    print('== Шлюз создал платёж, а ответ не дошёл: заказ и деньги не теряются (US-5.2, NFT-4.3, SEQ-01; TC-040)')
    stubs.reset()
    buyer = P.new_buyer(kc, admin, fx['stamp'], 'r3')
    if not buyer:
        return
    pid = fx['plenty']
    with stubs.payment_mode(scenario='hold', create={'loseResponseFirst': 1}):
        started = time.time()
        created = O.create_order(buyer, pid, 1)
        elapsed = time.time() - started
        body = created.json() if created.status == 201 else {}
        if not expect('TC-040. Ответ шлюза на создание платежа потерян: заказ всё равно оформлен, 201 awaiting_payment за %.1f с' % elapsed,
                      created.status == 201 and body.get('status') == 'awaiting_payment' and elapsed < 30, O.describe(created)):
            return
        oid = body['orderId']
        payments = O.stub_payments(stubs, oid)
        expect('TC-040. У шлюза один платёж по заказу: повтор вызова с тем же ключом идемпотентности нашёл прежний, а не завёл второй', len(payments) == 1, str(payments)[:300])
        rows = O.sql('payment_db', "select count(*), count(distinct gateway_payment_id) from payments.payment where order_id = '%s'" % oid)
        expect('TC-040. В базе платежей одна строка, связанная с платежом шлюза', bool(rows) and rows[0][0] == '1' and rows[0][1] == '1', str(rows))
        expect('TC-040. Резерв заказа активен, ключ не потерян и не удвоен', O.reservations_of(oid) == [('active', '', 1)], str(O.reservations_of(oid)))
        order, seconds = P.pay_and_wait_issue(buyer, body)
    expect('TC-040. Оплата платежа, ответ о создании которого терялся, доводит заказ до выдачи (%.1f с)' % seconds, bool(order), str(order)[:300])
    expect('TC-040. После оплаты у шлюза по-прежнему один платёж, подтверждённый', len(O.stub_payments(stubs, oid)) == 1 and O.payment_of(oid) and O.payment_of(oid)[0] == 'confirmed',
           '%s %s' % (O.stub_payments(stubs, oid), O.payment_of(oid)))
    O.modes_are_default(stubs, 'TC-040')
    fx['emails'].append(buyer.email)


def run_scenarios(fx):
    for fn in (last_key_race, lost_gateway_answer):
        O.guarded(fn.__name__, fn, fx)


def main():
    kc, admin = K.from_env(dict(os.environ))
    people = G.users()
    if 'moderator-1' not in people:
        print('Нет тестового пользователя moderator-1 (make keycloak-users)', file=sys.stderr)
        return 2
    fx = O.context(kc, admin, people, int(time.time()), scarce_needed=False, race_needed=True)
    if fx:
        run_scenarios(fx)
    else:
        G.bad('Продавец и товары для заказов не заведены, сценарии гонки не запускались')
    return O.summary('concurrency_checks')


if __name__ == '__main__':
    sys.exit(main())
