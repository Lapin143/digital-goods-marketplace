# -*- coding: utf-8 -*-
"""Тесты физической модели данных на настоящем PostgreSQL 16 (шаг 12 Ф2).

Каждый тест называет инвариант или требование, которое проверяет. Базы создаются заново скриптом apply.sh,
тесты работают на настоящих параллельных сессиях (pgmini). Запуск: python3 test_db.py [подстрока имени]
"""
import os
import sys
import threading
import time
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pgmini import connect, PgError, q  # noqa: E402

HOST, PORT = os.environ.get('PGHOST', '/tmp'), int(os.environ.get('PGPORT', '5433'))
RESULTS = []


def conn(db):
    return connect(host=HOST, port=PORT, user='postgres', dbname=db)


def uid():
    return str(uuid.uuid4())


def raises(c, sql, code=None, constraint=None, text=None):
    """Оператор должен упасть с заданным SQLSTATE и (если указано) именем ограничения."""
    try:
        c.execute(sql)
    except PgError as e:
        if code and e.code != code:
            raise AssertionError('ожидали %s, получили %s: %s' % (code, e.code, e.message))
        if constraint and e.constraint != constraint and constraint not in e.message:
            raise AssertionError('ожидали ограничение %s, получили %r (%s)' % (constraint, e.constraint, e.message))
        if text and text not in e.message:
            raise AssertionError('ожидали текст %r, получили %s' % (text, e.message))
        return e
    raise AssertionError('оператор не упал: ' + sql[:120])


def ok(c, sql):
    return c.execute(sql)


def test(name, inv):
    def deco(fn):
        fn.test_name, fn.inv = name, inv
        RESULTS.append(fn)
        return fn
    return deco


# --------------------------------------------------------------------------------------------- общий блок
@test('outbox: опубликованная и припаркованная одновременно, не объект в payload, дубль event_id', 'INV-43')
def t_outbox():
    c = conn('order_db')
    ev = uid()
    ins = "insert into public.outbox (event_id, aggregate_type, aggregate_id, event_type, topic, payload, headers%s) values (%s, 'Order', 'x', 'order.paid', 'order.events', %s, '{}'%s)"
    ok(c, ins % ('', q(ev), "'{}'", ''))
    raises(c, ins % ('', q(ev), "'{}'", ''), '23505', 'uq_outbox_event_id')
    raises(c, ins % ('', q(uid()), "'[]'", ''), '23514', 'ck_outbox_payload_object')
    raises(c, ins % (', published_at, failed_at', q(uid()), "'{}'", ', now(), now()'), '23514', 'ck_outbox_published_or_failed')


@test('processed_event: повторная пара (потребитель, event_id) отклоняется', 'ADR-006, уровень 2')
def t_processed_event():
    c = conn('order_db')
    ev = uid()
    ok(c, "insert into public.processed_event (consumer, event_id) values ('order-service', %s)" % q(ev))
    raises(c, "insert into public.processed_event (consumer, event_id) values ('order-service', %s)" % q(ev), '23505', 'pk_processed_event')
    ok(c, "insert into public.processed_event (consumer, event_id) values ('other-service', %s)" % q(ev))


@test('idempotency_key: два одновременных запроса с одним ключом, победитель один; хеш запроса 32 байта', 'ADR-006, уровень 1')
def t_idem():
    key = uid()
    results = []

    def run():
        c = conn('order_db')
        try:
            c.execute("insert into public.idempotency_key (scope, key, endpoint, request_hash, state) values ('buyer-1', %s, 'POST /orders', decode(repeat('ab', 32), 'hex'), 'in_progress')" % q(key))
            results.append('ok')
        except PgError as e:
            results.append(e.code)
    th = [threading.Thread(target=run) for _ in range(6)]
    [t.start() for t in th]
    [t.join() for t in th]
    assert results.count('ok') == 1 and results.count('23505') == 5, results
    c = conn('order_db')
    raises(c, "insert into public.idempotency_key (scope, key, endpoint, request_hash, state) values ('b', %s, 'e', decode('abcd', 'hex'), 'in_progress')" % q(uid()), '23514', 'ck_idempotency_key_request_hash')
    raises(c, "insert into public.idempotency_key (scope, key, endpoint, request_hash, state, response_code) values ('b', %s, 'e', decode(repeat('ab', 32), 'hex'), 'in_progress', 201)" % q(uid()), '23514', 'ck_idempotency_key_response')


# --------------------------------------------------------------------------------------------- каталог
@test('seller_profile: один профиль на пользователя; переходы SM-05; на проверке есть срок; вне черновика анкета заполнена', 'INV-39, SM-05')
def t_seller():
    c = conn('catalog_db')
    user = uid()
    sid = uid()
    ok(c, "insert into seller_onboarding.seller_profile (id, user_id) values (%s, %s)" % (q(sid), q(user)))
    raises(c, "insert into seller_onboarding.seller_profile (id, user_id) values (%s, %s)" % (q(uid()), q(user)), '23505', 'uq_seller_profile_user_id')
    # draft -> approved запрещён, и без заполненной анкеты в on_review не уйти
    raises(c, "update seller_onboarding.seller_profile set status = 'approved' where id = %s" % q(sid), '23514')
    raises(c, "update seller_onboarding.seller_profile set status = 'on_review', submitted_at = now(), review_deadline_at = now() + interval '3 days' where id = %s" % q(sid), '23514', 'ck_seller_profile_complete')
    ok(c, "update seller_onboarding.seller_profile set seller_type = 'self_employed', name = 'ООО Ромашка', payout_recipient_name = 'Иванов И. И.', payout_bank_name = 'Банк', payout_bik = '044525225', payout_account_number = '40702810900000000001', assortment_description = 'Ключи игр' where id = %s" % q(sid))
    raises(c, "update seller_onboarding.seller_profile set status = 'on_review' where id = %s" % q(sid), '23514', 'ck_seller_profile_review_state')
    ok(c, "update seller_onboarding.seller_profile set status = 'on_review', submitted_at = now(), review_deadline_at = now() + interval '3 days' where id = %s" % q(sid))
    raises(c, "update seller_onboarding.seller_profile set status = 'rejected' where id = %s" % q(sid), '23514', 'ck_seller_profile_decision_reason')
    ok(c, "update seller_onboarding.seller_profile set status = 'rejected', decision_reason = 'Нет документов', last_rejected_at = now() where id = %s" % q(sid))
    raises(c, "update seller_onboarding.seller_profile set status = 'approved' where id = %s" % q(sid), '23514', 'ck_seller_profile_status_transition')
    raises(c, "update seller_onboarding.seller_profile set payout_bik = '123' where id = %s" % q(sid), '23514', 'ck_seller_profile_payout_bik')


@test('product: блокировка требует «статус до» и причину, у остальных они пусты; переходы SM-04', 'INV-40, SM-04')
def t_product():
    c = conn('catalog_db')
    pid = uid()
    ins = "insert into catalog.product (id, seller_id, title, description, product_type, platform, price%s) values (%s, %s, 'Игра', 'Описание', 'game_key', 'Steam', 99900%s)"
    ok(c, ins % ('', q(pid), q(uid()), ''))
    raises(c, "update catalog.product set status = 'blocked' where id = %s" % q(pid), '23514')  # draft -> blocked запрещён переходом
    ok(c, "update catalog.product set status = 'on_moderation', submitted_at = now(), review_deadline_at = now() + interval '3 days' where id = %s" % q(pid))
    ok(c, "update catalog.product set status = 'published', published_at = now(), submitted_at = null, review_deadline_at = null where id = %s" % q(pid))
    raises(c, "update catalog.product set status = 'blocked' where id = %s" % q(pid), '23514', 'ck_product_block_state')
    ok(c, "update catalog.product set status = 'blocked', status_before_block = 'published', block_reason = 'moderator_decision' where id = %s" % q(pid))
    # T10 (снятие блокировки продавца) относится к R2: из «заблокирован» в «опубликован» напрямую в R1 нельзя
    raises(c, "update catalog.product set status = 'published', published_at = now(), status_before_block = null, block_reason = null where id = %s" % q(pid),
           '23514', 'ck_product_status_transition')
    raises(c, "update catalog.product set status = 'on_moderation', submitted_at = now(), review_deadline_at = now() + interval '3 days' where id = %s" % q(pid),
           '23514', 'ck_product_block_state')
    # T11: продавец исправил товар, он снова на модерации, поля блокировки очищены
    ok(c, "update catalog.product set status = 'on_moderation', status_before_block = null, block_reason = null, submitted_at = now(), review_deadline_at = now() + interval '3 days' where id = %s" % q(pid))
    ok(c, "update catalog.product set status = 'published', published_at = now(), submitted_at = null, review_deadline_at = null where id = %s" % q(pid))
    raises(c, ins % (', status', q(uid()), q(uid()), ", 'published'"), '23514')  # начальный статус только draft
    raises(c, "update catalog.product set price = 0 where id = %s" % q(pid), '23514', 'ck_product_price')
    raises(c, "update catalog.product set currency = 'USD' where id = %s" % q(pid), '23514', 'ck_product_currency')
    raises(c, "update catalog.product set activation_regions = '{ru}' where id = %s" % q(pid), '23514', 'ck_product_activation_regions')


@test('stock_view: in_stock вычисляется из available, отрицательный остаток невозможен', 'INV-09')
def t_stock_view():
    c = conn('catalog_db')
    pid = uid()
    ok(c, "insert into catalog.product (id, seller_id, title, description, product_type, platform, price) values (%s, %s, 'Т', 'О', 'game_key', 'Steam', 100)" % (q(pid), q(uid())))
    ok(c, "insert into catalog.stock_view (product_id, available, last_event_at) values (%s, 0, now())" % q(pid))
    assert c.scalar("select in_stock from catalog.stock_view where product_id = %s" % q(pid)) == 'f'
    ok(c, "update catalog.stock_view set available = 3 where product_id = %s" % q(pid))
    assert c.scalar("select in_stock from catalog.stock_view where product_id = %s" % q(pid)) == 't'
    raises(c, "update catalog.stock_view set available = -1 where product_id = %s" % q(pid), '23514', 'ck_stock_view_available')


# --------------------------------------------------------------------------------------------- остатки
def inv_setup(c, n_keys=0, hmac_prefix='k'):
    """Создаёт товар, версию DEK (если нет) и n_keys свободных ключей. Возвращает product_id."""
    pid = uid()
    ok(c, "insert into inventory.product_copy (product_id, seller_id, issuance_method, status, source_version) values (%s, %s, 'key_pool', 'published', 1)" % (q(pid), q(uid())))
    if not c.scalar("select 1 from inventory.data_key where status = 'active'"):
        ok(c, "insert into inventory.data_key (wrapped_key, kek_version) values (decode(repeat('00', 60), 'hex'), 1)")
    for i in range(n_keys):
        insert_key(c, pid, '%s%d' % (hmac_prefix, i))
    return pid


def insert_key(c, pid, tag, key_id=None):
    kid = key_id or uid()
    dek = c.scalar("select id from inventory.data_key where status = 'active'")
    ok(c, "insert into inventory.key (id, product_id, dek_id, nonce, ciphertext, hmac) values (%s, %s, %s, decode(repeat('01', 12), 'hex'), decode(repeat('02', 40), 'hex'), decode(rpad(encode(%s::bytea, 'hex'), 64, '0'), 'hex'))" % (q(kid), q(pid), dek, q(tag)))
    return kid


@test('key: дубль значения в товаре отклоняется, в другом товаре допустим; нормализованная длина HMAC', 'INV-03')
def t_key_dup():
    c = conn('inventory_db')
    p1, p2 = inv_setup(c), inv_setup(c)
    insert_key(c, p1, 'same')
    e = raises(c, "insert into inventory.key (id, product_id, dek_id, nonce, ciphertext, hmac) values (%s, %s, (select id from inventory.data_key where status = 'active'), decode(repeat('01', 12), 'hex'), decode(repeat('02', 40), 'hex'), decode(rpad(encode('same'::bytea, 'hex'), 64, '0'), 'hex'))" % (q(uid()), q(p1)), '23505', 'uq_key_product_id_hmac')
    insert_key(c, p2, 'same')
    raises(c, "insert into inventory.key (id, product_id, dek_id, nonce, ciphertext, hmac) values (%s, %s, (select id from inventory.data_key where status = 'active'), decode(repeat('01', 12), 'hex'), decode(repeat('02', 40), 'hex'), decode('abcd', 'hex'))" % (q(uid()), q(p1)), '23514', 'ck_key_hmac')


@test('key: загрузка пачкой с ON CONFLICT DO NOTHING отклоняет дубли и считает их по результату', 'ADR-009')
def t_key_batch():
    c = conn('inventory_db')
    p = inv_setup(c)
    dek = c.scalar("select id from inventory.data_key where status = 'active'")
    vals = ','.join("(%s, %s, %s, decode(repeat('01', 12), 'hex'), decode(repeat('02', 40), 'hex'), decode(rpad(encode(%s::bytea, 'hex'), 64, '0'), 'hex'))" % (q(uid()), q(p), dek, q(t)) for t in ['a', 'b', 'a', 'c', 'b'])
    n = c.execute("insert into inventory.key (id, product_id, dek_id, nonce, ciphertext, hmac) values %s on conflict (product_id, hmac) do nothing" % vals)
    assert n == 3, n


@test('key: свободный не привязан, зарезервированный и выданный привязаны (INV-02); переходы SM-03 (INV-04); перепривязка запрещена (INV-01)', 'INV-01, INV-02, INV-04')
def t_key_states():
    c = conn('inventory_db')
    p = inv_setup(c)
    k = insert_key(c, p, 'x')
    order, order2 = uid(), uid()
    r1, r2 = uid(), uid()
    ok(c, "insert into inventory.reservation (id, order_id, product_id, quantity, expires_at) values (%s, %s, %s, 1, now() + interval '15 minutes')" % (q(r1), q(order), q(p)))
    ok(c, "insert into inventory.reservation (id, order_id, product_id, quantity, expires_at) values (%s, %s, %s, 1, now() + interval '15 minutes')" % (q(r2), q(order2), q(p)))
    raises(c, "update inventory.key set status = 'reserved' where id = %s" % q(k), '23514', 'ck_key_binding')           # зарезервирован без заказа
    raises(c, "update inventory.key set order_id = %s where id = %s" % (q(order), q(k)), '23514', 'ck_key_binding')     # свободный с заказом
    raises(c, "update inventory.key set status = 'issued', order_id = %s, reservation_id = %s, issued_at = now() where id = %s" % (q(order), q(r1), q(k)), '23514', 'ck_key_status_transition')  # free > issued
    ok(c, "update inventory.key set status = 'reserved', order_id = %s, reservation_id = %s where id = %s" % (q(order), q(order and r1), q(k)))
    # второй заказ не может перехватить закреплённый ключ, даже если код забудет проверить статус
    raises(c, "update inventory.key set order_id = %s, reservation_id = %s where id = %s" % (q(order2), q(r2), q(k)), '23514', 'ck_key_rebind')
    ok(c, "update inventory.key set status = 'issued', issued_at = now() where id = %s" % q(k))
    raises(c, "update inventory.key set status = 'free', order_id = null, reservation_id = null, issued_at = null where id = %s" % q(k), '23514')   # выданный не вернуть
    raises(c, "update inventory.key set product_id = %s where id = %s" % (q(inv_setup(c)), q(k)), '23514', 'ck_key_immutable')


@test('key: роль приложения не может удалять ключи, но читает и обновляет', 'FT-7.1, право без DELETE')
def t_key_no_delete():
    c = conn('inventory_db')
    p = inv_setup(c)
    k = insert_key(c, p, 'del')
    ok(c, "set role app_inventory")
    try:
        assert c.query("select id from inventory.key where id = %s" % q(k))
        raises(c, "delete from inventory.key where id = %s" % q(k), '42501')
        raises(c, "truncate inventory.key", '42501')
        ok(c, "update inventory.key set hmac_version = 2 where id = %s" % q(k))
    finally:
        ok(c, "reset role")


@test('reservation: второй активный резерв заказа невозможен, после снятия новый допустим', 'INV-06')
def t_reservation_unique():
    c = conn('inventory_db')
    p = inv_setup(c)
    order = uid()
    r1 = uid()
    ins = "insert into inventory.reservation (id, order_id, product_id, quantity, expires_at) values (%s, %s, %s, 1, now() + interval '15 minutes')"
    ok(c, ins % (q(r1), q(order), q(p)))
    raises(c, ins % (q(uid()), q(order), q(p)), '23505', 'uq_reservation_order_id_active')
    ok(c, "update inventory.reservation set status = 'released', release_reason = 'expired', released_at = now() where id = %s" % q(r1))
    ok(c, ins % (q(uid()), q(order), q(p)))
    # поздняя оплата: новый резерв сразу становится «использован» в одной транзакции
    ok(c, "begin")
    r3 = uid()
    ok(c, "update inventory.reservation set status = 'released', release_reason = 'order_cancelled', released_at = now() where order_id = %s and status = 'active'" % q(order))
    ok(c, ins % (q(r3), q(order), q(p)))
    ok(c, "update inventory.reservation set status = 'used', used_at = now() where id = %s" % q(r3))
    ok(c, "commit")
    # использованный не возвращается в активные; причина снятия обязательна
    raises(c, "update inventory.reservation set status = 'active', used_at = null where id = %s" % q(r3), '23514')
    r4 = uid()
    ok(c, ins % (q(r4), q(uid()), q(p)))
    raises(c, "update inventory.reservation set status = 'released' where id = %s" % q(r4), '23514', 'ck_reservation_released_state')
    raises(c, "update inventory.reservation set quantity = 11 where id = %s" % q(r4), '23514', 'ck_reservation_quantity')


def reserve_one(db, pid, order, barrier, out, hold=0.0):
    """Резерв одного ключа по ADR-007: резерв, затем выбор FOR UPDATE SKIP LOCKED и привязка. Все потоки держат транзакции одновременно."""
    c = conn(db)
    try:
        c.execute("begin")
        rid = uid()
        c.execute("insert into inventory.reservation (id, order_id, product_id, quantity, expires_at) values (%s, %s, %s, 1, now() + interval '15 minutes')" % (q(rid), q(order), q(pid)))
        rows = c.query("select id from inventory.key where product_id = %s and status = 'free' order by id limit 1 for update skip locked" % q(pid))
        barrier.wait()          # все потоки уже выбрали, победитель держит блокировку, остальные её пропустили
        if hold:
            time.sleep(hold)
        if len(rows) < 1:
            c.execute("rollback")
            out.append(('short', order))
            return
        n = c.execute("update inventory.key set status = 'reserved', order_id = %s, reservation_id = %s where id = %s and status = 'free'" % (q(order), q(rid), q(rows[0][0])))
        c.execute("commit")
        out.append(('got' if n == 1 else 'lost', order))
    except Exception as e:  # noqa
        out.append(('error', repr(e)))
        try:
            c.execute("rollback")
        except Exception:
            pass


@test('конкуренция: 10 одновременных заказов на единственный свободный ключ дают ровно одну выдачу (FOR UPDATE SKIP LOCKED)', 'INV-01, ADR-007')
def t_race_last_key():
    c = conn('inventory_db')
    p = inv_setup(c, n_keys=1, hmac_prefix='last')
    n = 10
    barrier = threading.Barrier(n)
    out = []
    th = [threading.Thread(target=reserve_one, args=('inventory_db', p, uid(), barrier, out)) for _ in range(n)]
    [t.start() for t in th]
    [t.join() for t in th]
    kinds = [k for k, _ in out]
    assert kinds.count('got') == 1 and kinds.count('short') == n - 1 and 'error' not in kinds, out
    assert c.scalar("select count(*) from inventory.key where product_id = %s and status = 'reserved'" % q(p)) == '1'
    # два заказа на два ключа получают разные ключи
    p2 = inv_setup(c, n_keys=2, hmac_prefix='pair')
    barrier = threading.Barrier(2)
    out = []
    th = [threading.Thread(target=reserve_one, args=('inventory_db', p2, uid(), barrier, out)) for _ in range(2)]
    [t.start() for t in th]
    [t.join() for t in th]
    assert [k for k, _ in out].count('got') == 2, out
    assert c.scalar("select count(distinct order_id) from inventory.key where product_id = %s and status = 'reserved'" % q(p2)) == '2'


@test('конкуренция: условное обновление ловит ошибку кода (две транзакции выбрали один ключ без блокировки), второй рубеж', 'INV-01, ADR-007')
def t_race_conditional_update():
    c = conn('inventory_db')
    p = inv_setup(c, n_keys=1, hmac_prefix='cond')
    kid = c.scalar("select id from inventory.key where product_id = %s" % q(p))
    results = []
    gate = threading.Barrier(2)

    def worker():
        w = conn('inventory_db')
        order, rid = uid(), uid()
        w.execute("insert into inventory.reservation (id, order_id, product_id, quantity, expires_at) values (%s, %s, %s, 1, now() + interval '15 minutes')" % (q(rid), q(order), q(p)))
        w.execute("begin")
        gate.wait()             # оба «прочитали» одинаковый свободный ключ без блокировки, теперь пишут
        n = w.execute("update inventory.key set status = 'reserved', order_id = %s, reservation_id = %s where id = %s and status = 'free'" % (q(order), q(rid), q(kid)))
        time.sleep(0.2)
        w.execute("commit")
        results.append(n)
    th = [threading.Thread(target=worker) for _ in range(2)]
    [t.start() for t in th]
    [t.join() for t in th]
    assert sorted(results) == [0, 1], results


@test('data_key: активная версия ключа данных одна', 'ADR-009')
def t_data_key():
    c = conn('inventory_db')
    inv_setup(c)
    raises(c, "insert into inventory.data_key (wrapped_key, kek_version) values (decode(repeat('00', 60), 'hex'), 1)", '23505', 'uq_data_key_active')
    ok(c, "insert into inventory.data_key (wrapped_key, kek_version, status, retired_at) values (decode(repeat('00', 60), 'hex'), 1, 'retired', now())")


# --------------------------------------------------------------------------------------------- заказы
ORD_INS = ("insert into orders.orders (id, buyer_id, seller_id, product_id, product_title, quantity, unit_price, amount, commission_rate_bp, commission, delivery_address%s) "
           "values (%s, %s, %s, %s, 'Игра', %s, %s, %s, %s, %s, 'buyer@example.com'%s)")


def new_order(c, qty=1, unit=14999, rate=200, commission=None, order=None, extra_cols='', extra_vals=''):
    oid = order or uid()
    amount = unit * qty
    com = commission if commission is not None else (amount * rate + 5000) // 10000
    ok(c, ORD_INS % (extra_cols, q(oid), q(uid()), q(uid()), q(uid()), qty, unit, amount, rate, com, extra_vals))
    return oid


@test('orders: количество 1..10, сумма = цена × количество, комиссия half-up (пример 149,99 ₽ при 2% = 300 копеек)', 'INV-10, INV-12, INV-13')
def t_order_amounts():
    c = conn('order_db')
    new_order(c)                                  # 14999 * 200 = 2 999 800, +5000, /10000 = 300
    assert c.scalar("select commission from orders.orders where amount = 14999 and commission_rate_bp = 200 limit 1") == '300'
    new_order(c, qty=1, unit=25, rate=200)        # 0,25 ₽: (5000 + 5000) / 10000 = 1
    raises(c, ORD_INS % ('', q(uid()), q(uid()), q(uid()), q(uid()), 11, 100, 1100, 200, 22, ''), '23514', 'ck_orders_quantity')
    raises(c, ORD_INS % ('', q(uid()), q(uid()), q(uid()), q(uid()), 0, 100, 0, 200, 0, ''), '23514')
    raises(c, ORD_INS % ('', q(uid()), q(uid()), q(uid()), q(uid()), 2, 100, 199, 200, 4, ''), '23514', 'ck_orders_amount')
    raises(c, ORD_INS % ('', q(uid()), q(uid()), q(uid()), q(uid()), 1, 14999, 14999, 200, 299, ''), '23514', 'ck_orders_commission')
    raises(c, ORD_INS % ('', q(uid()), q(uid()), q(uid()), q(uid()), 1, 14999, 14999, 200, 301, ''), '23514', 'ck_orders_commission')
    new_order(c, qty=10, unit=9007199254740991 // 10, rate=3000)       # верхняя граница без переполнения bigint в проверке


@test('orders: срок сессии строго короче срока резерва, оба срока задаются вместе; ожидание оплаты требует адрес страницы оплаты', 'INV-07')
def t_order_deadlines():
    c = conn('order_db')
    cols = ', reserve_until, session_until'
    raises(c, ORD_INS % (cols, q(uid()), q(uid()), q(uid()), q(uid()), 1, 100, 100, 200, 2, ", now() + interval '15 minutes', now() + interval '15 minutes'"), '23514', 'ck_orders_deadlines')
    raises(c, ORD_INS % (cols, q(uid()), q(uid()), q(uid()), q(uid()), 1, 100, 100, 200, 2, ", now() + interval '15 minutes', now() + interval '16 minutes'"), '23514', 'ck_orders_deadlines')
    raises(c, ORD_INS % (', reserve_until', q(uid()), q(uid()), q(uid()), q(uid()), 1, 100, 100, 200, 2, ", now() + interval '15 minutes'"), '23514', 'ck_orders_deadlines')
    oid = new_order(c, extra_cols=cols, extra_vals=", now() + interval '15 minutes', now() + interval '12 minutes'")
    raises(c, "update orders.orders set status = 'awaiting_payment' where id = %s" % q(oid), '23514', 'ck_orders_awaiting_payment')
    ok(c, "update orders.orders set status = 'awaiting_payment', payment_session_url = 'https://pay.example/s/1' where id = %s" % q(oid))


@test('orders: переходы SM-01 R1, поздняя оплата cancelled > paid, снимки и дата первой выдачи неизменны', 'INV-12, INV-13, INV-17, SM-01')
def t_order_transitions():
    c = conn('order_db')
    oid = new_order(c)
    raises(c, "update orders.orders set status = 'paid', paid_at = now() where id = %s" % q(oid), '23514', 'ck_orders_status_transition')       # created > paid
    ok(c, "update orders.orders set status = 'cancelled', cancel_reason = 'reservation_expired' where id = %s" % q(oid))
    ok(c, "update orders.orders set status = 'paid', paid_at = now() where id = %s" % q(oid))                                                    # T7
    raises(c, "update orders.orders set status = 'issued' where id = %s" % q(oid), '23514', 'ck_orders_issued_at')
    ok(c, "update orders.orders set status = 'issued', issued_at = now() where id = %s" % q(oid))
    raises(c, "update orders.orders set status = 'cancelled', cancel_reason = 'system_error' where id = %s" % q(oid), '23514', 'ck_orders_status_transition')  # T10 только R2
    raises(c, "update orders.orders set unit_price = 1, amount = 1, commission = 0 where id = %s" % q(oid), '23514', 'ck_orders_snapshot_immutable')
    raises(c, "update orders.orders set commission = commission + 1 where id = %s" % q(oid), '23514')
    raises(c, "update orders.orders set issued_at = now() + interval '1 hour' where id = %s" % q(oid), '23514', 'ck_orders_issued_at_once')
    ok(c, "update orders.orders set delivery_address = 'anonymized-x@invalid' where id = %s" % q(oid))     # адрес меняется повторной отправкой и анонимизацией
    assert int(c.scalar("select version from orders.orders where id = %s" % q(oid))) >= 5
    raises(c, ORD_INS.replace("'created'", "'created'") % (', status', q(uid()), q(uid()), q(uid()), q(uid()), 1, 100, 100, 200, 2, ", 'paid'"), '23514')   # начальный статус только created


@test('orders: короткий номер выдаётся базой, растёт, уникален и неизменен; приложение не может задать его само', 'FT-5.0, F10-5')
def t_order_number():
    c = conn('order_db')
    a, b = new_order(c), new_order(c)
    na, nb = (int(c.scalar("select number from orders.orders where id = %s" % q(x))) for x in (a, b))
    assert na >= 1001 and nb > na, (na, nb)
    raises(c, "update orders.orders set number = 1 where id = %s" % q(a), '428C9')          # identity always: задать вручную нельзя
    raises(c, "insert into orders.orders (id, number, buyer_id, seller_id, product_id, product_title, quantity, unit_price, amount, commission_rate_bp, commission, delivery_address) "
              "values (%s, 5, %s, %s, %s, 'Игра', 1, 100, 100, 200, 2, 'buyer@example.com')" % (q(uid()), q(uid()), q(uid()), q(uid())), '428C9')
    ok(c, "set role app_orders")
    try:
        new_order(c)                                                                       # роль приложения вставляет заказ без прав на последовательность
    finally:
        ok(c, "reset role")


# --------------------------------------------------------------------------------------------- платежи
def new_payment(c, order=None, status='created', extra_cols='', extra_vals=''):
    pid = uid()
    ok(c, "insert into payments.payment (id, order_id, amount%s) values (%s, %s, 14999%s)" % (extra_cols, q(pid), q(order or uid()), extra_vals))
    return pid


@test('payment: один платёж на заказ, один внешний идентификатор платежа и возврата', 'INV-11, INV-14')
def t_payment_unique():
    c = conn('payment_db')
    order = uid()
    p = new_payment(c, order)
    raises(c, "insert into payments.payment (id, order_id, amount) values (%s, %s, 100)" % (q(uid()), q(order)), '23505', 'uq_payment_order_id')
    ok(c, "update payments.payment set gateway_payment_id = 'gw-1' where id = %s" % q(p))
    p2 = new_payment(c)
    raises(c, "update payments.payment set gateway_payment_id = 'gw-1' where id = %s" % q(p2), '23505', 'uq_payment_gateway_payment_id')
    raises(c, "update payments.payment set gateway_payment_id = 'gw-2' where id = %s" % q(p), '23514', 'ck_payment_immutable')   # внешний идентификатор пишется один раз
    raises(c, "update payments.payment set amount = 1 where id = %s" % q(p), '23514', 'ck_payment_immutable')


@test('payment: переходы SM-02, подтверждённый платёж известен шлюзу, возврат полный и единственный', 'INV-14, INV-15, SM-02')
def t_payment_transitions():
    c = conn('payment_db')
    p = new_payment(c)
    raises(c, "update payments.payment set status = 'confirmed', confirmed_at = now() where id = %s" % q(p), '23514', 'ck_payment_confirmed')  # нет внешнего id
    raises(c, "update payments.payment set status = 'refunded', gateway_payment_id = 'g', confirmed_at = now(), refunded_at = now(), refund_reason = 'late_payment' where id = %s" % q(p), '23514', 'ck_payment_status_transition')
    ok(c, "update payments.payment set status = 'confirmed', gateway_payment_id = 'gw-%s', confirmed_at = now() where id = %s" % (p, q(p)))
    raises(c, "update payments.payment set status = 'created', confirmed_at = null where id = %s" % q(p), '23514')
    raises(c, "update payments.payment set status = 'rejected', rejected_at = now() where id = %s" % q(p), '23514')
    raises(c, "update payments.payment set status = 'refunded', refunded_at = now() where id = %s" % q(p), '23514', 'ck_payment_refunded')    # без причины
    ok(c, "update payments.payment set status = 'refunded', refunded_at = now(), refund_reason = 'late_payment', gateway_refund_id = 'rf-%s' where id = %s" % (p, q(p)))
    raises(c, "update payments.payment set status = 'confirmed' where id = %s" % q(p), '23514')
    # отказанный не подтверждается (аномалия SM-02, решение 3)
    p2 = new_payment(c)
    ok(c, "update payments.payment set status = 'rejected', rejected_at = now() where id = %s" % q(p2))
    raises(c, "update payments.payment set status = 'confirmed', gateway_payment_id = 'x', confirmed_at = now(), rejected_at = null where id = %s" % q(p2), '23514', 'ck_payment_status_transition')
    # очередь администратора только у подтверждённого платежа
    p3 = new_payment(c)
    raises(c, "update payments.payment set in_admin_queue = true, refund_escalated_at = now() where id = %s" % q(p3), '23514', 'ck_payment_admin_queue')


@test('payment_event: повтор пары (внешний платёж, тип события) отклоняется, при гонке победитель один', 'INV-14, FT-6.2')
def t_payment_event_race():
    c = conn('payment_db')
    p = new_payment(c)
    gw = 'gw-evt-' + p
    results = []
    barrier = threading.Barrier(5)

    def run():
        w = conn('payment_db')
        barrier.wait()
        try:
            w.execute("insert into payments.payment_event (gateway_payment_id, event_type, gateway_event_id, payment_id, outcome, occurred_at) values (%s, 'payment.succeeded', %s, %s, 'applied', now())" % (q(gw), q(uid()), q(p)))
            results.append('ok')
        except PgError as e:
            results.append(e.code)
    th = [threading.Thread(target=run) for _ in range(5)]
    [t.start() for t in th]
    [t.join() for t in th]
    assert results.count('ok') == 1 and results.count('23505') == 4, results
    ok(c, "insert into payments.payment_event (gateway_payment_id, event_type, gateway_event_id, payment_id, outcome, occurred_at) values (%s, 'payment.refunded', %s, %s, 'applied', now())" % (q(gw), q(uid()), q(p)))


@test('refund_attempt: открытая попытка одна, принятая шлюзом одна (возврат единственный), завершённая не меняется', 'INV-15')
def t_refund_attempts():
    c = conn('payment_db')
    p = new_payment(c)
    ins = "insert into payments.refund_attempt (id, payment_id, attempt_no, status, next_attempt_at%s) values (%s, %s, %s, %s, %s%s)"
    a1 = uid()
    ok(c, ins % ('', q(a1), q(p), 1, "'pending'", 'now()', ''))
    raises(c, ins % ('', q(uid()), q(p), 2, "'pending'", 'now()', ''), '23505', 'uq_refund_attempt_payment_id_open')
    raises(c, ins % ('', q(uid()), q(p), 6, "'failed'", 'null', ''), '23514')
    ok(c, "update payments.refund_attempt set status = 'failed', next_attempt_at = null, finished_at = now(), error_code = 'gateway_timeout' where id = %s" % q(a1))
    raises(c, "update payments.refund_attempt set error_code = 'other' where id = %s" % q(a1), '23514', 'ck_refund_attempt_final')
    a2 = uid()
    ok(c, ins % ('', q(a2), q(p), 2, "'pending'", 'now()', ''))
    ok(c, "update payments.refund_attempt set status = 'succeeded', next_attempt_at = null, finished_at = now() where id = %s" % q(a2))
    raises(c, ins % (', finished_at', q(uid()), q(p), 3, "'processing'", 'null', ', now()'), '23505', 'uq_refund_attempt_payment_id_succeeded')
    raises(c, ins % (', error_code', q(uid()), q(p), 3, "'pending'", 'now()', ", 'Текст с пробелами'"), '23514', 'ck_refund_attempt_error_code')


def take_batch(db, sql, n, out, barrier):
    c = conn(db)
    c.execute("begin")
    rows = c.query(sql)
    barrier.wait()
    out.append({r[0] for r in rows})
    time.sleep(0.2)
    c.execute("commit")


@test('исполнитель возвратов: два экземпляра берут разные попытки (FOR UPDATE SKIP LOCKED), аренда сдвигает срок', 'ADR-015')
def t_refund_worker():
    c = conn('payment_db')
    ids = []
    for i in range(6):
        p = new_payment(c)
        a = uid()
        ids.append(a)
        ok(c, "insert into payments.refund_attempt (id, payment_id, attempt_no, status, next_attempt_at) values (%s, %s, 1, 'pending', now() - interval '1 second')" % (q(a), q(p)))
    sql = ("select id from payments.refund_attempt where status in ('pending', 'in_progress') and next_attempt_at <= now() "
           "and id in (%s) order by next_attempt_at, id limit 3 for update skip locked" % ','.join(q(i) for i in ids))
    out = []
    barrier = threading.Barrier(2)
    th = [threading.Thread(target=take_batch, args=('payment_db', sql, 3, out, barrier)) for _ in range(2)]
    [t.start() for t in th]
    [t.join() for t in th]
    assert len(out) == 2 and not (out[0] & out[1]) and len(out[0] | out[1]) == 6, out
    # аренда: занятая попытка уходит из выборки до истечения аренды
    ok(c, "update payments.refund_attempt set status = 'in_progress', next_attempt_at = now() + interval '2 minutes' where id = %s" % q(ids[0]))
    assert c.scalar("select count(*) from payments.refund_attempt where id = %s and next_attempt_at <= now()" % q(ids[0])) == '0'


@test('unmatched_notification: повтор того же уведомления не размножается, оповещение только у несопоставленного', 'ADR-015')
def t_unmatched():
    c = conn('payment_db')
    gw = 'gw-u-' + uid()
    ins = "insert into payments.unmatched_notification (id, gateway_payment_id, event_type, gateway_event_id, occurred_at) values (%s, %s, 'payment.succeeded', 'e1', now())"
    ok(c, ins % (q(uid()), q(gw)))
    raises(c, ins % (q(uid()), q(gw)), '23505', 'uq_unmatched_notification_payment_event')


# --------------------------------------------------------------------------------------------- выдача
def new_delivery(c, order=None, kind='primary', status='queued'):
    did = uid()
    ok(c, "insert into delivery.delivery (id, order_id, buyer_id, kind, address, product_title, quantity, next_attempt_at) values (%s, %s, %s, %s, 'buyer@example.com', 'Игра', 2, now())" % (q(did), q(order or uid()), q(uid()), q(kind)))
    return did


@test('delivery: первичная выдача одна на заказ, повторное order.paid второй не создаёт; одна выдача в очереди на заказ', 'INV-18')
def t_delivery_unique():
    c = conn('delivery_db')
    order = uid()
    d1 = new_delivery(c, order)
    raises(c, "insert into delivery.delivery (id, order_id, buyer_id, kind, address, product_title, quantity, next_attempt_at) values (%s, %s, %s, 'primary', 'b@example.com', 'Игра', 2, now())" % (q(uid()), q(order), q(uid())), '23505', 'uq_delivery_order_id_primary')
    raises(c, "insert into delivery.delivery (id, order_id, buyer_id, kind, address, product_title, quantity, next_attempt_at) values (%s, %s, %s, 'repeat', 'b@example.com', 'Игра', 2, now())" % (q(uid()), q(order), q(uid())), '23505', 'uq_delivery_order_id_queued')
    ok(c, "update delivery.delivery set status = 'sent', sent_at = now(), next_attempt_at = null where id = %s" % q(d1))
    ok(c, "insert into delivery.delivery (id, order_id, buyer_id, kind, address, product_title, quantity, next_attempt_at) values (%s, %s, %s, 'repeat', 'b@example.com', 'Игра', 2, now())" % (q(uid()), q(order), q(uid())))


@test('delivery: переходы SM-06, поля состояния согласованы; в журнал ошибок нельзя записать произвольный текст (ключ) вместо кода', 'INV-20, SM-06')
def t_delivery_states():
    c = conn('delivery_db')
    d = new_delivery(c)
    raises(c, "update delivery.delivery set status = 'delivered', delivered_at = now(), sent_at = now(), next_attempt_at = null where id = %s" % q(d), '23514', 'ck_delivery_status_transition')   # queued > delivered
    raises(c, "update delivery.delivery set last_error_code = 'ABCD-EFGH-1234' where id = %s" % q(d), '23514', 'ck_delivery_last_error_code')   # похоже на ключ
    raises(c, "update delivery.delivery set last_error_code = 'XXXXX XXXXX' where id = %s" % q(d), '23514', 'ck_delivery_last_error_code')
    ok(c, "update delivery.delivery set last_error_code = 'smtp_timeout', attempt_no = 1, next_attempt_at = now() + interval '10 seconds' where id = %s" % q(d))
    raises(c, "update delivery.delivery set attempt_no = 7 where id = %s" % q(d), '23514', 'ck_delivery_attempt_no')
    ok(c, "update delivery.delivery set status = 'failed', fail_reason = 'attempts_exhausted', next_attempt_at = null where id = %s" % q(d))
    raises(c, "update delivery.delivery set status = 'sent', sent_at = now(), fail_reason = null where id = %s" % q(d), '23514', 'ck_delivery_status_transition')
    raises(c, "update delivery.delivery set quantity = 3 where id = %s" % q(d), '23514', 'ck_delivery_immutable')
    d2 = new_delivery(c)
    raises(c, "update delivery.delivery set status = 'sent', next_attempt_at = null where id = %s" % q(d2), '23514', 'ck_delivery_sent_at')
    raises(c, "insert into delivery.delivery_attempt (delivery_id, attempt_no, outcome, error_code, started_at, finished_at) values (%s, 1, 'retryable_error', 'Не код ошибки: произвольный текст', now(), now())" % q(d2), '23514', 'ck_delivery_attempt_error_code')
    ok(c, "insert into delivery.delivery_attempt (delivery_id, attempt_no, outcome, error_code, started_at, finished_at) values (%s, 1, 'retryable_error', 'key_count_mismatch', now(), now())" % q(d2))
    raises(c, "insert into delivery.delivery_attempt (delivery_id, attempt_no, outcome, started_at, finished_at) values (%s, 1, 'accepted', now(), now())" % q(d2), '23505', 'pk_delivery_attempt')


@test('диспетчер выдачи: два экземпляра берут разные выдачи, блокировка не держится на время сетевых вызовов', 'ADR-011')
def t_dispatcher():
    c = conn('delivery_db')
    ids = [new_delivery(c) for _ in range(40)]
    sql = ("select id from delivery.delivery where status = 'queued' and next_attempt_at <= now() and id in (%s) "
           "order by next_attempt_at, id limit 20 for update skip locked" % ','.join(q(i) for i in ids))
    out = []
    barrier = threading.Barrier(2)
    th = [threading.Thread(target=take_batch, args=('delivery_db', sql, 20, out, barrier)) for _ in range(2)]
    [t.start() for t in th]
    [t.join() for t in th]
    assert len(out) == 2 and len(out[0]) == 20 and len(out[1]) == 20 and not (out[0] & out[1]), [len(x) for x in out]


@test('delivery_watch: одна запись контроля на заказ, переходы open > handed_over > closed', 'NFT-2.4')
def t_watch():
    c = conn('delivery_db')
    order = uid()
    ok(c, "insert into delivery.delivery_watch (order_id, deadline_at) values (%s, now() + interval '30 minutes')" % q(order))
    raises(c, "insert into delivery.delivery_watch (order_id, deadline_at) values (%s, now() + interval '30 minutes')" % q(order), '23505', 'pk_delivery_watch')
    raises(c, "update delivery.delivery_watch set status = 'closed' where order_id = %s" % q(order), '23514', 'ck_delivery_watch_closed')
    ok(c, "update delivery.delivery_watch set status = 'handed_over', handed_over_at = now() where order_id = %s" % q(order))
    ok(c, "update delivery.delivery_watch set status = 'closed', closed_at = now() where order_id = %s" % q(order))
    raises(c, "update delivery.delivery_watch set status = 'open', closed_at = null where order_id = %s" % q(order), '23514')


# --------------------------------------------------------------------------------------------- платформа
def new_user(c, email=None, role='buyer', phone=None, status='active'):
    u = uid()
    email = email or ('u-%s@mail.example' % u[:8])
    ok(c, "insert into identity.user_account (id, email, name, role, audit_salt%s) values (%s, %s, 'Имя', %s, decode(repeat('aa', 16), 'hex')%s)" % (
        ', phone, phone_confirmed' if phone else '', q(u), q(email), q(role), (', %s, true' % q(phone)) if phone else ''))
    return u


@test('user_account: e-mail и телефон уникальны (в нормализованном виде), пустых телефонов может быть много, формат E.164', 'INV-34')
def t_user_unique():
    c = conn('platform_db')
    email = 'unique-%s@mail.example' % uid()[:8]
    new_user(c, email)
    raises(c, "insert into identity.user_account (id, email, name, audit_salt) values (%s, %s, 'И', decode(repeat('aa', 16), 'hex'))" % (q(uid()), q(email)), '23505', 'uq_user_account_email')
    raises(c, "insert into identity.user_account (id, email, name, audit_salt) values (%s, %s, 'И', decode(repeat('aa', 16), 'hex'))" % (q(uid()), q(email.upper())), '23514', 'ck_user_account_email')
    phone = '+7999%07d' % (int(time.time() * 1000) % 10000000)
    new_user(c, phone=phone)
    raises(c, "insert into identity.user_account (id, email, name, audit_salt, phone, phone_confirmed) values (%s, %s, 'И', decode(repeat('aa', 16), 'hex'), %s, true)" % (q(uid()), q('x-%s@mail.example' % uid()[:8]), q(phone)), '23505', 'uq_user_account_phone')
    new_user(c)
    new_user(c)
    raises(c, "insert into identity.user_account (id, email, name, audit_salt, phone) values (%s, %s, 'И', decode(repeat('aa', 16), 'hex'), '89991234567')" % (q(uid()), q('y-%s@mail.example' % uid()[:8])), '23514', 'ck_user_account_phone')
    raises(c, "insert into identity.user_account (id, email, name, audit_salt, phone_confirmed) values (%s, %s, 'И', decode(repeat('aa', 16), 'hex'), true)" % (q(uid()), q('z-%s@mail.example' % uid()[:8])), '23514', 'ck_user_account_phone_confirmed')


@test('user_account: гонка двух регистраций с одним телефоном даёт одну запись', 'INV-34, FT-1.5')
def t_user_phone_race():
    phone = '+7888%07d' % (int(time.time() * 1000) % 10000000)
    results = []
    barrier = threading.Barrier(4)

    def run():
        w = conn('platform_db')
        barrier.wait()
        try:
            w.execute("insert into identity.user_account (id, email, name, audit_salt, phone, phone_confirmed) values (%s, %s, 'И', decode(repeat('aa', 16), 'hex'), %s, true)" % (q(uid()), q('r-%s@mail.example' % uid()), q(phone)))
            results.append('ok')
        except PgError as e:
            results.append(e.code)
    th = [threading.Thread(target=run) for _ in range(4)]
    [t.start() for t in th]
    [t.join() for t in th]
    assert results.count('ok') == 1 and results.count('23505') == 3, results


@test('user_account: всегда есть хотя бы один активный администратор, в том числе при одновременном снятии двух', 'INV-38')
def t_last_admin():
    c = conn('platform_db')
    ok(c, "update identity.user_account set role = 'buyer' where role = 'admin' and false")   # no-op, база может содержать прежних администраторов
    # изолируем: убираем прежних администраторов тестов, понижая их до последнего
    others = [r[0] for r in c.query("select id from identity.user_account where role = 'admin' and status = 'active'")]
    a, b = new_user(c, role='admin'), new_user(c, role='admin')
    for o in others:
        ok(c, "update identity.user_account set role = 'buyer' where id = %s" % q(o))
    ok(c, "update identity.user_account set role = 'buyer' where id = %s" % q(a))                 # остаётся b
    raises(c, "update identity.user_account set role = 'buyer' where id = %s" % q(b), '23514', 'ck_user_account_last_admin')
    raises(c, "update identity.user_account set status = 'deactivated', deactivated_at = now() where id = %s" % q(b), '23514', 'ck_user_account_last_admin')
    raises(c, "delete from identity.user_account where id = %s" % q(b), '23514', 'ck_user_account_last_admin')
    # гонка: два администратора снимают друг друга одновременно
    a2 = new_user(c, role='admin')
    admins = [b, a2]
    results = []
    started = threading.Event()

    def demote(user, delay):
        w = conn('platform_db')
        try:
            w.execute("begin")
            time.sleep(delay)
            w.execute("update identity.user_account set role = 'buyer' where id = %s" % q(user))
            if delay == 0:
                started.set()
                time.sleep(0.7)     # держим транзакцию: вторая обязана ждать консультативную блокировку
            w.execute("commit")
            results.append('ok')
        except PgError as e:
            results.append(e.code)
            try:
                w.execute("rollback")
            except Exception:
                pass
    t1 = threading.Thread(target=demote, args=(admins[0], 0))
    t2 = threading.Thread(target=demote, args=(admins[1], 0.3))
    t1.start()
    t2.start()
    t1.join()
    t2.join()
    assert sorted(results) == ['23514', 'ok'], results
    assert c.scalar("select count(*) from identity.user_account where role = 'admin' and status = 'active'") == '1'


@test('user_account: деактивированный не возвращается, анонимизация необратима и убирает соль, телефон и прежний адрес', 'INV-44, US-8.2')
def t_user_anonymize():
    c = conn('platform_db')
    u = new_user(c, phone='+7777%07d' % (int(time.time() * 1000) % 10000000))
    raises(c, "update identity.user_account set anonymized = true where id = %s" % q(u), '23514', 'ck_user_account_anonymized')
    ok(c, "update identity.user_account set status = 'deactivated', deactivated_at = now() where id = %s" % q(u))
    raises(c, "update identity.user_account set status = 'active', deactivated_at = null where id = %s" % q(u), '23514', 'ck_user_account_status_transition')
    ok(c, "update identity.user_account set anonymized = true, email = 'anonymized-%s@invalid', name = 'Удалён', phone = null, phone_confirmed = false, phone_synced_to_idp = false, audit_salt = null where id = %s" % (u, q(u)))
    raises(c, "update identity.user_account set anonymized = false where id = %s" % q(u), '23514')
    raises(c, "update identity.user_account set audit_salt = decode(repeat('bb', 16), 'hex') where id = %s" % q(u), '23514', 'ck_user_account_anonymized')


def new_ticket(c, order=None, source='buyer', reason='key_not_received'):
    t = uid()
    ok(c, "insert into support.ticket (id, order_id, buyer_id, source, reason) values (%s, %s, %s, %s, %s)" % (q(t), q(order or uid()), q(uid()), q(source), q(reason)))
    return t


@test('ticket: открытое обращение по заказу одно (в том числе при гонке события системы и покупателя), после решения новое допустимо', 'INV-21')
def t_ticket_open():
    c = conn('platform_db')
    order = uid()
    results = []
    barrier = threading.Barrier(2)

    def run(source, reason):
        w = conn('platform_db')
        barrier.wait()
        try:
            w.execute("insert into support.ticket (id, order_id, buyer_id, source, reason) values (%s, %s, %s, %s, %s)" % (q(uid()), q(order), q(uid()), q(source), q(reason)))
            results.append('ok')
        except PgError as e:
            results.append((e.code, e.constraint))
    th = [threading.Thread(target=run, args=('buyer', 'key_not_received')), threading.Thread(target=run, args=('system', 'delivery_overdue'))]
    [t.start() for t in th]
    [t.join() for t in th]
    assert results.count('ok') == 1 and ('23505', 'uq_ticket_order_id_open') in results, results
    t = c.scalar("select id from support.ticket where order_id = %s" % q(order))
    ok(c, "update support.ticket set status = 'resolved', resolved_at = now(), resolved_by = 'system' where id = %s" % q(t))
    new_ticket(c, order)


@test('ticket: SM-07, оператор есть только у обращения в работе, источник согласован с причиной, неизменяемые поля', 'SM-07, INV-22, INV-23')
def t_ticket_states():
    c = conn('platform_db')
    t = new_ticket(c)
    raises(c, "update support.ticket set status = 'in_progress' where id = %s" % q(t), '23514', 'ck_ticket_in_progress_state')
    op = uid()
    ok(c, "update support.ticket set status = 'in_progress', operator_id = %s, taken_at = now() where id = %s" % (q(op), q(t)))
    raises(c, "update support.ticket set status = 'created' where id = %s" % q(t), '23514', 'ck_ticket_created_state')
    ok(c, "update support.ticket set status = 'created', operator_id = null, taken_at = null where id = %s" % q(t))
    raises(c, "update support.ticket set status = 'resolved' where id = %s" % q(t), '23514', 'ck_ticket_resolved_state')
    raises(c, "insert into support.ticket (id, order_id, buyer_id, source, reason) values (%s, %s, %s, 'system', 'key_not_received')" % (q(uid()), q(uid()), q(uid())), '23514', 'ck_ticket_source_reason')
    raises(c, "update support.ticket set order_id = %s where id = %s" % (q(uid()), q(t)), '23514', 'ck_ticket_immutable')


@test('email_change: после завершения новый адрес не хранится, нельзя перейти к проверке нового адреса без проверки телефона', 'INV-44, SEQ-04')
def t_email_change():
    c = conn('platform_db')
    t = new_ticket(c)
    ok(c, "insert into support.email_change (ticket_id, user_id, new_email, new_email_masked) values (%s, %s, 'new@mail.example', 'n***@mail.example')" % (q(t), q(uid())))
    raises(c, "update support.email_change set new_email_check = 'pending' where ticket_id = %s" % q(t), '23514', 'ck_email_change_order_of_checks')
    ok(c, "update support.email_change set phone_check = 'passed', new_email_check = 'pending', status = 'awaiting_email_code' where ticket_id = %s" % q(t))
    raises(c, "update support.email_change set phone_check = 'passed', new_email_check = 'passed', status = 'completed' where ticket_id = %s" % q(t), '23514', 'ck_email_change_pii_cleanup')
    ok(c, "update support.email_change set status = 'completed', new_email = null, new_email_check = 'passed' where ticket_id = %s" % q(t))


@test('notification: в параметрах шаблона нет адресов и имён, получатель только по идентификатору; очередь и недоставленные согласованы', 'INV-44')
def t_notification():
    c = conn('platform_db')
    ins = "insert into notification.notification (id, user_id, template, next_attempt_at, params%s) values (%s, %s, 'order.paid', now(), %s%s)"
    ok(c, ins % ('', q(uid()), q(uid()), """'{"orderId": "x", "amount": 100}'""", ''))
    for key in ('email', 'phone', 'name', 'address'):
        raises(c, ins % ('', q(uid()), q(uid()), """'{"%s": "v"}'""" % key, ''), '23514', 'ck_notification_params')
    ev = uid()
    ok(c, ins % (', source_event_id', q(uid()), q(uid()), "'{}'", ', ' + q(ev)))
    raises(c, ins % (', source_event_id', q(uid()), q(uid()), "'{}'", ', ' + q(ev)), '23505', 'uq_notification_source_event_id_template')
    n = uid()
    ok(c, ins % ('', q(n), q(uid()), "'{}'", ''))
    raises(c, "update notification.notification set in_dead_queue = true where id = %s" % q(n), '23514', 'ck_notification_dead_queue')
    ok(c, "update notification.notification set status = 'failed', next_attempt_at = null, in_dead_queue = true where id = %s" % q(n))


@test('параметры: границы значения, срок платёжной сессии строго короче резерва, в том числе при одновременной правке двух параметров', 'INV-07')
def t_parameters():
    c = conn('platform_db')
    raises(c, "update audit_admin.platform_parameter set value = 0 where key = 'otp.requests-per-day'", '23514', 'ck_platform_parameter_value')
    raises(c, "update audit_admin.platform_parameter set value = 101 where key = 'otp.requests-per-day'", '23514', 'ck_platform_parameter_value')
    raises(c, "update audit_admin.platform_parameter set value = 700 where key = 'reservation.ttl-seconds'", '23514', 'ck_platform_parameter_session_vs_reserve')
    raises(c, "update audit_admin.platform_parameter set value = 900 where key = 'payment-session.ttl-seconds'", '23514', 'ck_platform_parameter_session_vs_reserve')
    ok(c, "update audit_admin.platform_parameter set value = 3000 where key = 'reservation.ttl-seconds'")
    ok(c, "update audit_admin.platform_parameter set value = 1500 where key = 'payment-session.ttl-seconds'")
    # гонка: один поднимает сессию до 2900, другой опускает резерв до 1600, по отдельности допустимо, вместе нет
    ok(c, "update audit_admin.platform_parameter set value = 1500 where key = 'payment-session.ttl-seconds'")
    results = []
    barrier = threading.Barrier(2)

    def run(sql):
        w = conn('platform_db')
        try:
            w.execute("begin")
            barrier.wait()
            w.execute(sql)
            time.sleep(0.4)
            w.execute("commit")
            results.append('ok')
        except PgError as e:
            results.append(e.code)
            try:
                w.execute("rollback")
            except Exception:
                pass
    th = [threading.Thread(target=run, args=("update audit_admin.platform_parameter set value = 2900 where key = 'payment-session.ttl-seconds'",)),
          threading.Thread(target=run, args=("update audit_admin.platform_parameter set value = 1600 where key = 'reservation.ttl-seconds'",))]
    [t.start() for t in th]
    [t.join() for t in th]
    assert sorted(results) == ['23514', 'ok'], results
    s, r = (int(x) for x in c.query("select (select value from audit_admin.platform_parameter where key = 'payment-session.ttl-seconds'), (select value from audit_admin.platform_parameter where key = 'reservation.ttl-seconds')")[0])
    assert s < r, (s, r)
    ok(c, "update audit_admin.platform_parameter set value = 720 where key = 'payment-session.ttl-seconds'")
    ok(c, "update audit_admin.platform_parameter set value = 900 where key = 'reservation.ttl-seconds'")
    assert c.scalar("select version from audit_admin.platform_parameter where key = 'reservation.ttl-seconds'") != '1'


AUDIT_INS = ("insert into audit_admin.audit_log (id, event_id, occurred_at, actor_id, actor_role, action, object_type, object_id, changes%s) "
             "values (%s, %s, %s, %s, 'moderator', 'product.rejected', 'product', %s, %s%s)")


def audit_row(c, ts="now()", changes="""'[{"field":"status","personal":false,"before":"on_moderation","after":"rejected"}]'""", event=None, extra_cols='', extra_vals=''):
    rid, ev = uid(), event or uid()
    ok(c, AUDIT_INS % (extra_cols, q(rid), q(ev), ts, q(uid()), q(uid()), changes, extra_vals))
    return rid, ev


@test('audit_log: записи нельзя изменить, удалить и очистить (триггеры для владельца), роль записи без UPDATE, DELETE и TRUNCATE', 'INV-42, NFT-5.2')
def t_audit_immutable():
    c = conn('platform_db')
    rid, ev = audit_row(c)
    raises(c, "update audit_admin.audit_log set action = 'x' where id = %s" % q(rid), '23001', 'ck_audit_log_append_only')
    raises(c, "delete from audit_admin.audit_log where id = %s" % q(rid), '23001', 'ck_audit_log_append_only')
    raises(c, "truncate audit_admin.audit_log", '23001', 'ck_audit_log_append_only')
    part = c.scalar("select tableoid::regclass::text from audit_admin.audit_log where id = %s" % q(rid))
    raises(c, "truncate %s" % part, '23001', 'ck_audit_log_append_only')
    raises(c, "update %s set action = 'x' where id = %s" % (part, q(rid)), '23001')
    # перенос между секциями правкой времени тоже запрещён
    raises(c, "update audit_admin.audit_log set occurred_at = occurred_at - interval '3 months' where id = %s" % q(rid), '23001')
    # роль записи: только INSERT и SELECT
    ok(c, "set role app_audit_writer")
    try:
        assert c.query("select id from audit_admin.audit_log where id = %s" % q(rid))
        audit_row(c)
        raises(c, "update audit_admin.audit_log set action = 'x' where id = %s" % q(rid), '42501')
        raises(c, "delete from audit_admin.audit_log where id = %s" % q(rid), '42501')
        raises(c, "truncate audit_admin.audit_log", '42501')
        raises(c, "select * from identity.user_account", '42501')   # чужая схема недоступна
    finally:
        ok(c, "reset role")
    assert c.scalar("select action from audit_admin.audit_log where id = %s" % q(rid)) == 'product.rejected'


@test('audit_log: повторная доставка события не создаёт запись, персональные значения только HMAC, запись вне секций не теряется', 'INV-42, ADR-014')
def t_audit_content():
    c = conn('platform_db')
    ts = "timestamptz '2026-10-15 12:00:00+00'"
    rid, ev = audit_row(c, ts=ts)
    raises(c, AUDIT_INS % ('', q(uid()), q(ev), ts, q(uid()), q(uid()), "'[]'", ''), '23505', 'event_id_occurred_at_key')
    h = 'a' * 64
    ok_changes = """'[{"field":"email","personal":true,"before":"%s","after":"%s"}]'""" % (h, 'b' * 64)
    audit_row(c, changes=ok_changes)
    open_value = """'[{"field":"email","personal":true,"before":"ivan@mail.example","after":"%s"}]'""" % h
    raises(c, AUDIT_INS % ('', q(uid()), q(uid()), 'now()', q(uid()), q(uid()), open_value, ''), '23514', 'ck_audit_log_changes')
    raises(c, AUDIT_INS % ('', q(uid()), q(uid()), 'now()', q(uid()), q(uid()), """'[{"field":"x","personal":false}]'""", ''), '23514', 'ck_audit_log_changes')
    raises(c, AUDIT_INS % ('', q(uid()), q(uid()), 'now()', q(uid()), q(uid()), """'{"field":"x"}'""", ''), '23514', 'ck_audit_log_changes')
    raises(c, AUDIT_INS % (', actor_ip, actor_ip_hashed', q(uid()), q(uid()), 'now()', q(uid()), q(uid()), "'[]'", ", '203.0.113.7', true"), '23514', 'ck_audit_log_actor_ip')
    audit_row(c, extra_cols=', actor_ip, actor_ip_hashed', extra_vals=", '203.0.113.7', false")
    rid2, _ = audit_row(c, ts="timestamptz '2020-01-01 00:00:00+00'")
    assert c.scalar("select tableoid::regclass::text from audit_admin.audit_log where id = %s" % q(rid2)) == 'audit_admin.audit_log_default'
    assert c.scalar("select tableoid::regclass::text from audit_admin.audit_log where id = %s" % q(rid)) == 'audit_admin.audit_log_2026_10'
    ok(c, "select audit_admin.create_audit_log_partition(date '2027-04-01')")
    ok(c, "select audit_admin.create_audit_log_partition(date '2027-04-01')")   # повторный вызов безопасен
    raises(c, "truncate audit_admin.audit_log_2027_04", '23001')
    raises(c, "select audit_admin.create_audit_log_partition(date '2027-04-15')")


@test('роли модулей видят только свою схему (правило модульности 2)', 'decomposition 7.2')
def t_roles():
    c = conn('catalog_db')
    ok(c, "set role app_catalog")
    try:
        assert c.query("select count(*) from catalog.product") is not None
        raises(c, "select * from seller_onboarding.seller_profile", '42501')
        raises(c, "insert into catalog.product (id) values (gen_random_uuid())", None)
    finally:
        ok(c, "reset role")
    ok(c, "set role app_seller_onboarding")
    try:
        raises(c, "select * from catalog.product", '42501')
    finally:
        ok(c, "reset role")
    c = conn('platform_db')
    for role, other in (('app_identity', 'support.ticket'), ('app_support', 'identity.user_account'), ('app_notification', 'audit_admin.platform_parameter'), ('app_audit_admin', 'notification.notification')):
        ok(c, "set role " + role)
        try:
            raises(c, "select * from " + other, '42501')
        finally:
            ok(c, "reset role")


def main():
    flt = sys.argv[1] if len(sys.argv) > 1 else ''
    if not os.environ.get('NO_APPLY'):
        import subprocess
        r = subprocess.run([os.path.join(os.path.dirname(os.path.abspath(__file__)), 'apply.sh')], capture_output=True, text=True)
        print(r.stdout.strip())
        if r.returncode != 0:
            print(r.stderr)
            return 1
    failed = 0
    done = 0
    for fn in RESULTS:
        if flt and flt not in fn.__name__ and flt not in fn.test_name:
            continue
        t0 = time.time()
        try:
            fn()
            print('OK    [%-22s] %s (%.2f с)' % (fn.inv, fn.test_name, time.time() - t0))
        except Exception as e:  # noqa
            failed += 1
            import traceback
            print('FAIL  [%-22s] %s\n      %s: %s' % (fn.inv, fn.test_name, type(e).__name__, e))
            traceback.print_exc(limit=3)
        done += 1
    print('\nитого: %d тестов, провалено %d' % (done, failed))
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
