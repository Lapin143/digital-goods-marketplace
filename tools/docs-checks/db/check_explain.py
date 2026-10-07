#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Проверка индексов запросов историй через EXPLAIN на реалистичных объёмах (шаг 12 Ф2).

Что делает:
  1. загружает в шесть баз тестовые данные (десятки и сотни тысяч строк), выполняет ANALYZE;
  2. для каждого запроса из ответственных историй получает план `EXPLAIN (FORMAT JSON)`;
  3. проверяет: в плане есть ожидаемый индекс (индекс секции приводится к индексу родителя),
     нет полного просмотра больших таблиц, нет сортировки там, где порядок должен идти из индекса;
  4. пишет отчёт docs/07-data/explain-report.md (с ключом --report).

Запуск: python3 check_explain.py [--report путь] [--no-load] [--scale 1.0] [--db база,база]
  --no-load  не пересоздавать базы и данные, взять то, что уже загружено
Окружение: REPO, PGHOST, PGPORT. Любое нарушение даёт код возврата 1.
"""
import json
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pgmini import connect, PgError  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.environ.get('REPO') or os.path.abspath(os.path.join(os.path.dirname(os.path.realpath(__file__)), '..', '..', '..'))
HOST, PORT = os.environ.get('PGHOST', '/tmp'), int(os.environ.get('PGPORT', '5433'))
BIG_ROWS = 5000
# Индексы, которые обслуживают ограничение или внешний ключ, а не запрос истории: их назначение указано в комментарии индекса
INDEX_NOT_QUERY_DRIVEN = {'uq_outbox_event_id', 'uq_seller_document_object_key', 'uq_data_key_active',
                          'uq_refund_attempt_payment_id_attempt_no', 'uq_refund_attempt_payment_id_succeeded',
                          'uq_unmatched_notification_payment_event', 'uq_delivery_order_id_queued', 'uq_audit_log_event_id_fix'}
SORT_ROWS = 2000  # сортировка малой выборки допустима: порядок из индекса нужен, когда сортировать пришлось бы много строк
DBS = ['catalog_db', 'inventory_db', 'order_db', 'payment_db', 'delivery_db', 'platform_db']


def conn(db):
    return connect(host=HOST, port=PORT, user='postgres', dbname=db)


# ------------------------------------------------------------------------------------------------ загрузка данных
def n(base, scale):
    return max(1, int(base * scale))


INFRA = """
insert into public.outbox (event_id, aggregate_type, aggregate_id, event_type, topic, payload, headers, created_at, published_at)
select md5('e' || i)::uuid, 'order', i::text, 'order.created', 'order.events', '{{}}'::jsonb, '{{}}'::jsonb,
       now() - ({n} - i) * interval '2.7 seconds',
       case when i <= {n} - 50 then now() - ({n} - i) * interval '2.7 seconds' + interval '1 second' end
from generate_series(1, {n}) i;
insert into public.processed_event (consumer, event_id, processed_at)
select 'service.handler', md5('pe' || i)::uuid, now() - ({n} - i) * interval '12 seconds'
from generate_series(1, {n}) i;
insert into public.idempotency_key (scope, key, endpoint, request_hash, state, response_code, response_body, created_at)
select 'user:' || (i % 1000), md5('ik' || i)::uuid, 'POST /api/v1/orders', decode(md5('h' || i) || md5('H' || i), 'hex'),
       'completed', 200, '{{}}'::jsonb, now() - ({m} - i) * interval '1.8 seconds'
from generate_series(1, {m}) i;
"""


def load_sql(db, s):
    infra = INFRA.format(n=n(100000, s), m=n(50000, s))
    if db == 'catalog_db':
        np_, ns = n(60000, s), n(5000, s)
        return """
insert into seller_onboarding.seller_profile (id, user_id, seller_type, name, payout_recipient_name, payout_bank_name, payout_bik,
    payout_account_number, assortment_description, status, submitted_at, review_deadline_at, created_at, updated_at)
select md5('s' || i)::uuid, md5('us' || i)::uuid, 'self_employed', 'Продавец ' || i, 'Получатель ' || i, 'Банк', '044525225',
       '40817810099910004312', 'Игры и ключи',
       case when i <= 60 then 'on_review' else 'approved' end,
       case when i <= 60 then now() - i * interval '2 hours' end,
       case when i <= 60 then now() - i * interval '2 hours' + interval '3 days' end,
       now() - interval '90 days', now()
from generate_series(1, {ns}) i;
insert into seller_onboarding.seller_document (id, seller_id, object_key, file_name, content_type, size_bytes, uploaded_at)
select md5('d' || i)::uuid, md5('s' || (1 + i % {ns}))::uuid, 'sellers/' || i || '.pdf', 'doc' || i || '.pdf', 'application/pdf',
       100000 + i, now() - i * interval '1 minute'
from generate_series(1, {nd}) i;
insert into catalog.product (id, seller_id, title, description, product_type, platform, activation_regions, price, status,
    status_before_block, block_reason, rejection_reason, submitted_at, review_deadline_at, published_at, created_at, updated_at)
select id, seller_id, title, 'Описание товара', product_type, platform, regions, price, status,
       case when status = 'blocked' then 'published' end, case when status = 'blocked' then 'moderator_decision' end,
       case when status = 'rejected' then 'Нарушение правил площадки' end,
       case when status = 'on_moderation' then now() - (i % 200) * interval '1 hour' end,
       case when status = 'on_moderation' then now() - (i % 200) * interval '1 hour' + interval '3 days' end,
       case when status = 'published' then now() - i * interval '3 minutes' end,
       now() - i * interval '4 minutes', now()
from (
    select i, md5('p' || i)::uuid id, md5('s' || (1 + i % {ns}))::uuid seller_id,
           (array['Cyberpunk', 'Witcher', 'Steam Wallet', 'Windows 11', 'Office 365', 'FIFA', 'Minecraft', 'Spotify'])[1 + i % 8] || ' ' || i title,
           case when i % 100 < 60 then 'game_key' when i % 100 < 85 then 'software_license' when i % 100 < 99 then 'gift_card' else 'subscription' end product_type,
           case when i % 200 = 0 then 'Ubisoft'
                else (array['Steam', 'Epic', 'PlayStation', 'Xbox', 'Nintendo', 'Windows', 'GOG'])[1 + (i / 7) % 7] end platform,
           case when i % 1000 = 7 then array['IS']
                when i % 10 < 4 then '{{}}'::text[]
                when i % 10 < 8 then array['RU']
                else array['RU', 'KZ', 'BY'] end regions,
           (10000 + (i * 37) % 490000)::bigint price,
           case when i % 1000 < 700 then 'published' when i % 1000 < 790 then 'draft' when i % 1000 < 794 then 'on_moderation'
                when i % 1000 < 850 then 'rejected' when i % 1000 < 880 then 'blocked' else 'archived' end status
    from generate_series(1, {np}) i
) t;
insert into catalog.stock_view (product_id, available, last_event_at)
select id, (row_number() over ()) % 50, now() from catalog.product where status = 'published';
""".format(ns=ns, np=np_, nd=n(8000, s)) + infra
    if db == 'inventory_db':
        nr, nf, nprod = n(150000, s), n(150000, s), n(20000, s)
        return """
insert into inventory.data_key (id, wrapped_key, kek_version, status, retired_at, created_at) overriding system value
values (1, decode(repeat('ab', 70), 'hex'), 1, 'retired', now() - interval '30 days', now() - interval '200 days'),
       (2, decode(repeat('cd', 70), 'hex'), 1, 'active', null, now() - interval '30 days');
insert into inventory.product_copy (product_id, seller_id, issuance_method, status, source_version)
select md5('p' || i)::uuid, md5('s' || (1 + i % 5000))::uuid, 'key_pool', 'published', 1 from generate_series(1, {np}) i;
insert into inventory.reservation (id, order_id, product_id, quantity, status, expires_at, release_reason, created_at, used_at, released_at)
select md5('r' || i)::uuid, md5('o' || i)::uuid, md5('p' || (1 + i % {nprod}))::uuid, 1, st,
       created + interval '15 minutes',
       case when st = 'released' then (array['expired', 'payment_declined', 'order_cancelled'])[1 + i % 3] end,
       created,
       case when st = 'used' then created + interval '1 minute' end,
       case when st = 'released' then created + interval '15 minutes' end
from (
    select i,
           case when i % 1000 < 3 then 'active' when i % 5 = 0 then 'released' else 'used' end st,
           case when i % 1000 < 3 then now() - (i % 20 + 1) * interval '1 minute' else now() - i * interval '2 minutes' end created
    from generate_series(1, {nr}) i
) t;
insert into inventory.key (id, product_id, dek_id, nonce, ciphertext, hmac, order_id, reservation_id, issued_at, status, created_at, updated_at)
select md5('k' || r.id)::uuid, r.product_id, case when (row_number() over ()) % 100 = 0 then 1 else 2 end,
       decode(substr(md5('n' || r.id), 1, 24), 'hex'), decode(repeat('ab', 40), 'hex'),
       decode(md5('h' || r.id) || md5('H' || r.id), 'hex'),
       case when r.status <> 'released' then r.order_id end, case when r.status <> 'released' then r.id end,
       case when r.status = 'used' then r.used_at end,
       case r.status when 'used' then 'issued' when 'active' then 'reserved' else 'free' end,
       now() - interval '120 days', now()
from inventory.reservation r;
insert into inventory.key (id, product_id, dek_id, nonce, ciphertext, hmac, status, created_at, updated_at)
select md5('kf' || i)::uuid, md5('p' || (1 + i % {nprod}))::uuid, 2, decode(substr(md5('n' || i), 1, 24), 'hex'),
       decode(repeat('ab', 40), 'hex'), decode(md5('hf' || i) || md5('Hf' || i), 'hex'), 'free', now() - interval '30 days', now()
from generate_series(1, {nf}) i;
""".format(np=n(60000, s), nr=nr, nf=nf, nprod=nprod) + infra
    if db == 'order_db':
        return """
insert into orders.orders (id, buyer_id, seller_id, product_id, product_title, quantity, unit_price, amount, commission_rate_bp,
    commission, delivery_address, reserve_until, session_until, payment_session_url, cancel_reason, paid_at, issued_at, status,
    created_at, updated_at)
select md5('o' || i)::uuid, md5('u' || (1 + i % {nb}))::uuid, md5('s' || (1 + i % 5000))::uuid, md5('p' || (1 + i % 60000))::uuid,
       'Товар ' || i, q, price, price * q, 200, div(price * q * 200 + 5000, 10000), 'buyer' || (1 + i % {nb}) || '@example.com',
       case when st = 'awaiting_payment' then created + interval '15 minutes' end,
       case when st = 'awaiting_payment' then created + interval '12 minutes' end,
       case when st = 'awaiting_payment' then 'https://pay.example/s/' || i end,
       case when st = 'cancelled' then (array['out_of_stock', 'payment_declined', 'reservation_expired'])[1 + i % 3] end,
       case when st in ('paid', 'issued', 'refunded') then created + interval '5 minutes' end,
       case when st = 'issued' then created + interval '6 minutes' end,
       st, created, created
from (
    select i, 1 + i % 3 q, (10000 + (i * 37) % 490000)::bigint price,
           case when i % 1000 < 2 then 'created' when i % 1000 < 5 then 'awaiting_payment' when i % 1000 < 8 then 'paid'
                when i % 1000 < 100 then 'cancelled' when i % 1000 < 120 then 'refunded' else 'issued' end st,
           case when i % 1000 < 8 then now() - (i % 10 + 7) * interval '1 minute' else now() - i * interval '3 minutes' end created
    from generate_series(1, {no}) i
) t;
""".format(nb=n(30000, s), no=n(150000, s)) + infra
    if db == 'payment_db':
        no = n(150000, s)
        return """
insert into payments.payment (id, order_id, gateway_payment_id, gateway_refund_id, refund_reason, amount, session_url,
    session_expires_at, in_admin_queue, refund_escalated_at, confirmed_at, rejected_at, refunded_at, status, created_at, updated_at)
select md5('pay' || i)::uuid, md5('o' || i)::uuid,
       case when st in ('confirmed', 'refunded') then 'gw_' || i end,
       case when st = 'refunded' then 'rf_' || i end,
       case when st = 'refunded' then 'late_payment' end,
       amount, 'https://pay.example/s/' || i, created + interval '12 minutes',
       adm, case when adm then now() - (i % 50) * interval '1 minute' end,
       case when st in ('confirmed', 'refunded') then created + interval '2 minutes' end,
       case when st = 'rejected' then created + interval '1 minute' end,
       case when st = 'refunded' then created + interval '30 minutes' end,
       st, created, created
from (
    select i, (10000 + (i * 37) % 490000)::bigint amount,
           case when i % 1000 < 5 then 'created' when i % 1000 < 30 then 'rejected' when i % 1000 < 50 then 'refunded' else 'confirmed' end st,
           (i % 1000 between 900 and 902) adm,
           case when i % 1000 < 5 then now() - (i % 100 + 1) * interval '1 minute' else now() - i * interval '3 minutes' end created
    from generate_series(1, {no}) i
) t where not (adm and i % 1000 < 50);
insert into payments.payment_event (gateway_payment_id, event_type, gateway_event_id, payment_id, outcome, occurred_at, received_at)
select p.gateway_payment_id, 'payment.confirmed', 'ev_' || p.gateway_payment_id, p.id, 'applied', p.confirmed_at,
       now() - (row_number() over (order by p.id)) * interval '8 seconds'
from payments.payment p where p.gateway_payment_id is not null;
insert into payments.refund_attempt (id, payment_id, attempt_no, status, next_attempt_at, finished_at, created_at)
select md5('ra' || p.id)::uuid, p.id, 1, 'succeeded', null, p.refunded_at, p.created_at from payments.payment p where p.status = 'refunded';
insert into payments.refund_attempt (id, payment_id, attempt_no, status, next_attempt_at, created_at)
select md5('rp' || p.id)::uuid, p.id, 1, case when (row_number() over ()) % 2 = 0 then 'pending' else 'in_progress' end,
       now() - interval '10 seconds', now() - interval '1 minute'
from payments.payment p where p.in_admin_queue limit 3;
insert into payments.unmatched_notification (id, gateway_payment_id, event_type, gateway_event_id, order_id, occurred_at, received_at, matched_at)
select md5('un' || i)::uuid, 'gw_unknown_' || i, 'payment.confirmed', 'evu_' || i, null, now() - i * interval '1 minute',
       now() - i * interval '1 minute', case when i > 50 then now() - i * interval '3 seconds' end
from generate_series(1, 30000) i;
""".format(no=no) + infra
    if db == 'delivery_db':
        no = n(150000, s)
        return """
insert into delivery.delivery (id, order_id, buyer_id, kind, address, product_title, quantity, attempt_no, next_attempt_at,
    fail_reason, sent_at, delivered_at, status, created_at, updated_at)
select md5('d' || i)::uuid, md5('o' || i)::uuid, md5('u' || (1 + i % {nb}))::uuid, 'primary', 'buyer' || i || '@example.com',
       'Товар ' || i, 1 + i % 3, 1,
       case when st = 'queued' then now() - (i % 10 - 3) * interval '1 second' end,
       case when st = 'failed' then 'attempts_exhausted' end,
       case when st in ('sent', 'delivered') then created + interval '1 minute' end,
       case when st = 'delivered' then created + interval '2 minutes' end,
       st, created, created
from (
    select i, case when i % 1000 < 3 then 'queued' when i % 1000 < 6 then 'sent' when i % 1000 < 10 then 'failed' else 'delivered' end st,
           case when i % 1000 < 6 then now() - (i % 10 + 6) * interval '1 minute' else now() - i * interval '3 minutes' end created
    from generate_series(1, {no}) i
) t;
insert into delivery.delivery_attempt (delivery_id, attempt_no, outcome, error_code, started_at, finished_at)
select id, 1, 'accepted', null, created_at, created_at + interval '1 second' from delivery.delivery;
insert into delivery.delivery_watch (order_id, deadline_at, status, created_at, closed_at)
select order_id, created_at + interval '30 minutes', case when i % 7500 = 0 then 'open' else 'closed' end, created_at,
       case when i % 7500 <> 0 then created_at + interval '2 minutes' end
from (select d.*, row_number() over (order by d.id) i from delivery.delivery d) d;
insert into delivery.provider_status_event (delivery_id, provider_status, occurred_at, received_at)
select id, 'delivered', created_at, now() - (row_number() over (order by id)) * interval '8 seconds' from delivery.delivery;
""".format(nb=n(30000, s), no=no) + infra
    if db == 'platform_db':
        nu, nt, nv, nn, na = n(100000, s), n(20000, s), n(150000, s), n(400000, s), n(200000, s)
        return """
insert into identity.user_account (id, email, previous_email, previous_email_until, phone, phone_confirmed, phone_synced_to_idp,
    name, role, has_2fa, audit_salt, status, registered_at, updated_at)
select md5('u' || i)::uuid, 'user' || i || '@example.com',
       case when i % 2000 = 0 then 'old' || i || '@example.com' end,
       case when i % 2000 = 0 then now() + ((i / 2000) % 10 - 3) * interval '1 day' end,
       '+7900' || lpad(i::text, 7, '0'), i % 10 <> 0, (i % 10 <> 0 and i % 3000 <> 1),
       'Пользователь ' || i,
       case when i <= 40 then (array['moderator', 'support-operator', 'admin', 'seller'])[1 + i % 4] else 'buyer' end,
       i <= 40, decode(md5('salt' || i), 'hex'), 'active', now() - i * interval '5 minutes', now()
from generate_series(1, {nu}) i;
insert into support.order_view (order_id, order_number, buyer_id, order_status, issued_at, delivery_status, delivery_updated_at, updated_at)
select md5('o' || i)::uuid, 1000 + i, md5('u' || (1 + i % 30000))::uuid, 'issued', now() - i * interval '3 minutes', 'delivered', now(), now()
from generate_series(1, {nv}) i;
insert into support.ticket (id, order_id, buyer_id, source, reason, operator_id, status, taken_at, resolved_at, resolved_by,
    created_at, updated_at)
select md5('t' || i)::uuid, md5('o' || i)::uuid, md5('u' || (1 + i % 30000))::uuid, 'buyer', 'key_not_received',
       case when st = 'in_progress' then md5('u' || (1 + i % 20))::uuid end, st,
       case when st = 'in_progress' then now() - interval '5 minutes' end,
       case when st = 'resolved' then now() - interval '1 hour' end, case when st = 'resolved' then 'operator' end,
       case when st = 'resolved' then now() - i * interval '10 minutes' else now() - (i % 300) * interval '1 minute' end, now()
from (select i, case when i % 1000 < 3 then 'created' when i % 1000 < 5 then 'in_progress' else 'resolved' end st
      from generate_series(1, {nt}) i) t;
insert into notification.notification (id, user_id, template, channel, params, source_event_id, attempts, next_attempt_at,
    last_error_code, in_dead_queue, sent_at, status, created_at, updated_at)
select md5('nt' || i)::uuid, md5('u' || (1 + i % {nu}))::uuid, 'order_issued', 'email', jsonb_build_object('orderId', md5('o' || i)),
       md5('ne' || i)::uuid, case when st = 'failed' then 5 else 0 end,
       case when st = 'queued' then now() - (i % 10 - 3) * interval '1 second' end,
       case when st = 'failed' then 'provider_rejected' end, st = 'failed',
       case when st = 'sent' then now() - i * interval '1 minute' end, st,
       case when st = 'queued' then now() - interval '1 minute' else now() - i * interval '1 minute' end, now()
from (select i, case when i % 10000 < 40 then 'queued' when i % 10000 < 45 then 'failed' else 'sent' end st
      from generate_series(1, {nn}) i) t;
insert into notification.provider_status_event (notification_id, provider_status, occurred_at, received_at)
select id, 'delivered', created_at, now() - (row_number() over (order by id)) * interval '8 seconds'
from notification.notification where status = 'sent' limit 50000;
insert into audit_admin.audit_log (id, event_id, occurred_at, actor_id, actor_role, action, object_type, object_id, changes,
    actor_ip, actor_ip_hashed, correlation_id)
select md5('a' || i)::uuid, md5('ae' || i)::uuid,
       timestamptz '2026-10-01' + (i % 180) * interval '1 day' + (i % 86400) * interval '1 second',
       md5('u' || (1 + i % 40))::uuid, (array['moderator', 'support-operator', 'admin'])[1 + i % 3],
       (array['product.approve', 'product.reject', 'user.deactivate', 'parameter.update', 'ticket.resolve'])[1 + i % 5],
       (array['product', 'user', 'parameter', 'ticket'])[1 + i % 4], md5('obj' || (i % 5000)),
       '[{{"field":"status","personal":false,"before":"on_moderation","after":"published"}}]'::jsonb, null, false, md5('c' || i)::uuid
from generate_series(1, {na}) i;
""".format(nu=nu, nt=nt, nv=nv, nn=nn, na=na) + infra
    raise ValueError(db)


def load_all(scale, only=None):
    for svc, db in zip(['catalog', 'inventory', 'order', 'payment', 'delivery', 'platform'], DBS):
        if only and db not in only:
            continue
        t = time.time()
        subprocess.run([os.path.join(HERE, 'apply.sh'), svc + '-service'], check=True, stdout=subprocess.DEVNULL)
        c = conn(db)
        c.execute('set session_replication_role = replica')
        c.execute(load_sql(db, scale))
        c.execute('reset session_replication_role')
        c.execute('analyze')
        c.close()
        print('загружено %s за %.0f с' % (db, time.time() - t), flush=True)


# ------------------------------------------------------------------------------------------------ запросы
def U(prefix, i):
    return "md5('%s%d')::uuid" % (prefix, i)


STOREFRONT = """select p.id, p.title, p.price, p.platform, p.product_type, p.published_at, s.available, s.in_stock
from catalog.product p left join catalog.stock_view s on s.product_id = p.id
where p.status = 'published' {where}
order by p.published_at desc, p.id desc limit 20"""

QUERIES = []


def Q(db, qid, story, sql, expect, no_sort=False, allow_seq=()):
    QUERIES.append(dict(db=db, id=qid, story=story, sql=sql, expect=expect, no_sort=no_sort, allow_seq=allow_seq))


def build_queries():
    c = 'catalog_db'
    Q(c, 'C-01', 'US-3.9 витрина без фильтров', STOREFRONT.format(where=''), ['ix_product_published'], True)
    Q(c, 'C-02', 'US-3.9 витрина, следующая страница по курсору',
      STOREFRONT.format(where="and (p.published_at, p.id) < (now() - interval '2 days', %s)" % U('p', 1000)), ['ix_product_published'], True)
    Q(c, 'C-03', 'US-3.10 фильтр по типу', STOREFRONT.format(where="and p.product_type = 'game_key'"), ['ix_product_published_type', 'ix_product_published'], True)
    Q(c, 'C-03b', 'US-3.10 фильтр по редкому типу (подписки)', STOREFRONT.format(where="and p.product_type = 'subscription'"),
      ['ix_product_published_type'], True)
    Q(c, 'C-04', 'US-3.10 фильтр по редкой платформе', STOREFRONT.format(where="and p.platform = 'Ubisoft'"), ['ix_product_published_platform'], True)
    Q(c, 'C-05', 'US-3.10 фильтр по частой платформе', STOREFRONT.format(where="and p.platform = 'Steam'"),
      ['ix_product_published_platform', 'ix_product_published'], True)
    Q(c, 'C-06', 'US-3.11 узкий диапазон цены', STOREFRONT.format(where="and p.price between 100000 and 100500"), ['ix_product_published_price'])
    Q(c, 'C-07', 'US-3.11 широкий диапазон цены', STOREFRONT.format(where="and p.price between 50000 and 450000"),
      ['ix_product_published_price', 'ix_product_published'])
    Q(c, 'C-08', 'US-3.10 регион, частый (РФ, плюс товары без ограничений)',
      STOREFRONT.format(where="and (p.activation_regions = '{}' or p.activation_regions @> array['RU'])"),
      ['ix_product_published_regions', 'ix_product_published'], True)
    Q(c, 'C-09', 'US-3.10 регион, редкий (Исландия, плюс товары без ограничений)',
      STOREFRONT.format(where="and (p.activation_regions = '{}' or p.activation_regions @> array['IS'])"),
      ['ix_product_published_regions', 'ix_product_published'], True)
    Q(c, 'C-10', 'US-3.9 поиск по части названия', STOREFRONT.format(where="and lower(p.title) like '%witcher 2%'"),
      ['ix_product_published_title_trgm'])
    Q(c, 'C-11', 'US-3.9 карточка товара с остатком',
      "select p.*, s.available from catalog.product p left join catalog.stock_view s on s.product_id = p.id where p.id = %s" % U('p', 4242),
      ['pk_product', 'pk_stock_view'])
    Q(c, 'C-12', 'US-3.1 товары продавца', "select * from catalog.product where seller_id = %s order by created_at desc, id desc limit 20" % U('s', 77),
      ['ix_product_seller_id'], True)
    Q(c, 'C-13', 'US-3.1 товары продавца с фильтром статуса',
      "select * from catalog.product where seller_id = %s and status = 'draft' order by created_at desc, id desc limit 20" % U('s', 77),
      ['ix_product_seller_id'], True)
    Q(c, 'C-14', 'US-3.3 очередь модерации товаров',
      "select id, title, submitted_at from catalog.product where status = 'on_moderation' order by submitted_at, id limit 20",
      ['ix_product_moderation_queue'], True)
    Q(c, 'C-15', 'US-8.8 просрочка модерации товаров (задание раз в 5 минут)',
      "select id from catalog.product where status = 'on_moderation' and overdue_marked_at is null and review_deadline_at <= now()",
      ['ix_product_overdue'])
    Q(c, 'C-16', 'US-2.2 очередь заявок продавцов',
      "select id, name, submitted_at from seller_onboarding.seller_profile where status = 'on_review' order by submitted_at, id limit 20",
      ['ix_seller_profile_review_queue'], True)
    Q(c, 'C-17', 'US-8.8 просрочка рассмотрения заявок',
      "select id from seller_onboarding.seller_profile where status = 'on_review' and overdue_marked_at is null and review_deadline_at <= now()",
      ['ix_seller_profile_overdue'])
    Q(c, 'C-18', 'US-2.1 профиль продавца по пользователю', "select * from seller_onboarding.seller_profile where user_id = %s" % U('us', 55),
      ['uq_seller_profile_user_id'])
    Q(c, 'C-19', 'US-2.2 документы заявки',
      "select * from seller_onboarding.seller_document where seller_id = %s order by uploaded_at" % U('s', 55), ['ix_seller_document_seller_id'])

    i = 'inventory_db'
    Q(i, 'I-01', 'ADR-007 выбор свободных ключей для резерва',
      "select id from inventory.key where product_id = %s and status = 'free' order by id limit 2 for update skip locked" % U('p', 321),
      ['ix_key_product_id_free'], True)
    Q(i, 'I-02', 'stock.changed число свободных ключей товара',
      "select count(*) from inventory.key where product_id = %s and status = 'free'" % U('p', 321), ['ix_key_product_id_free'])
    Q(i, 'I-03', 'US-4.4 число ключей по статусам товара',
      "select status, count(*) from inventory.key where product_id = %s group by status" % U('p', 321),
      ['ix_key_product_id_status', 'ix_key_product_id_free', 'uq_key_product_id_hmac'])
    Q(i, 'I-04', 'FT-7.1 ключи заказа для письма',
      "select id, dek_id, nonce, ciphertext from inventory.key where order_id = %s and status = 'issued'" % U('o', 9001), ['ix_key_order_id'])
    Q(i, 'I-05', 'Снятие резерва: ключи резерва', "select id from inventory.key where reservation_id = %s for update" % U('r', 9001), ['ix_key_reservation_id'])
    Q(i, 'I-06', 'Подтверждение резерва по заказу',
      "update inventory.reservation set status = 'used', used_at = now() where order_id = %s and status = 'active'" % U('o', 2), ['uq_reservation_order_id_active'])
    Q(i, 'I-07', 'Сверка раз в минуту: резервы с прошедшим сроком',
      "select id from inventory.reservation where status = 'active' and expires_at <= now() order by expires_at limit 100",
      ['ix_reservation_expires_at_active'], True)
    Q(i, 'I-08', 'ADR-013 последний резерв заказа',
      "select * from inventory.reservation where order_id = %s order by created_at desc limit 1" % U('o', 9001), ['ix_reservation_order_id'], True)
    Q(i, 'I-09', 'ADR-009 перешифровка значений старой версии ключа данных',
      "select id from inventory.key where dek_id = 1 order by id limit 100", ['ix_key_dek_id'])
    Q(i, 'I-10', 'FT-4.1 проверка дублей при загрузке пачки',
      "select hmac from inventory.key where product_id = %s and hmac = any (array[decode(md5('x1') || md5('x2'), 'hex'), decode(md5('x3') || md5('x4'), 'hex')])" % U('p', 321),
      ['uq_key_product_id_hmac'])
    Q(i, 'I-11', 'Реестр товара: владелец и способ выдачи', "select * from inventory.product_copy where product_id = %s" % U('p', 321), ['pk_product_copy'])

    o = 'order_db'
    Q(o, 'O-01', 'US-5.9 история заказов покупателя',
      "select * from orders.orders where buyer_id = %s order by created_at desc, id desc limit 20" % U('u', 700), ['ix_orders_buyer_id'], True)
    Q(o, 'O-02', 'US-5.9 история заказов с фильтром статуса',
      "select * from orders.orders where buyer_id = %s and status = 'issued' order by created_at desc, id desc limit 20" % U('u', 700), ['ix_orders_buyer_id'], True)
    Q(o, 'O-03', 'US-5.3 заказ по идентификатору', "select * from orders.orders where id = %s" % U('o', 12345), ['pk_orders'])
    Q(o, 'O-04', 'Сторож: заказы created старше 60 секунд',
      "select id from orders.orders where status = 'created' and created_at < now() - interval '60 seconds'", ['ix_orders_created_watchdog'])
    Q(o, 'O-05', 'Сторож: заказы awaiting_payment с истёкшим резервом',
      "select id from orders.orders where status = 'awaiting_payment' and reserve_until < now() - interval '5 minutes'", ['ix_orders_awaiting_reserve_until'])
    Q(o, 'O-06', 'INV-44 замена адресов покупателя при анонимизации',
      "update orders.orders set delivery_address = 'anonymized@invalid' where buyer_id = %s" % U('u', 700), ['ix_orders_buyer_id'])
    Q(o, 'O-07', 'FT-5.1 число неоплаченных заказов покупателя (лимит orders.max-unpaid)',
      "select count(*) from orders.orders where buyer_id = %s and status in ('created', 'awaiting_payment')" % U('u', 700), ['ix_orders_buyer_id'])
    Q(o, 'O-08', 'FT-5.0 заказ по короткому номеру (оператор поддержки)', "select * from orders.orders where number = 1001 + 12345", ['uq_orders_number'])

    p = 'payment_db'
    Q(p, 'P-01', 'FT-6.1 платёж по заказу', "select * from payments.payment where order_id = %s" % U('o', 12345), ['uq_payment_order_id'])
    Q(p, 'P-02', 'FT-6.2 платёж по идентификатору из уведомления шлюза', "select * from payments.payment where gateway_payment_id = 'gw_12345'", ['uq_payment_gateway_payment_id'])
    Q(p, 'P-03', 'FT-6.4 платёж по идентификатору возврата', "select * from payments.payment where gateway_refund_id = 'rf_12345'", ['uq_payment_gateway_refund_id'])
    Q(p, 'P-04', 'Сверка: открытые платежи моложе 2 часов',
      "select id from payments.payment where status = 'created' and created_at > now() - interval '2 hours' order by created_at", ['ix_payment_created_open'])
    Q(p, 'P-05', 'FT-6.4 очередь ручных возвратов',
      "select id, refund_escalated_at from payments.payment where in_admin_queue order by refund_escalated_at, id limit 20", ['ix_payment_manual_refund_queue'], True)
    Q(p, 'P-06', 'ADR-015 исполнитель возвратов',
      "select id from payments.refund_attempt where status in ('pending', 'in_progress') and next_attempt_at <= now() order by next_attempt_at limit 10 for update skip locked",
      ['ix_refund_attempt_due'], True)
    Q(p, 'P-07', 'ADR-015 открытая попытка возврата по платежу',
      "select * from payments.refund_attempt where payment_id = %s and status in ('pending', 'in_progress')" % U('pay', 910),
      ['uq_refund_attempt_payment_id_open', 'uq_refund_attempt_payment_id_attempt_no'])
    Q(p, 'P-08', 'ADR-015 сопоставление неопознанных уведомлений',
      "select * from payments.unmatched_notification where matched_at is null order by received_at limit 100", ['ix_unmatched_notification_pending'], True)
    Q(p, 'P-09', 'INV-14 дедупликация уведомления шлюза',
      "select 1 from payments.payment_event where gateway_payment_id = 'gw_12345' and event_type = 'payment.confirmed'", ['pk_payment_event'])
    Q(p, 'P-10', 'История уведомлений платежа', "select * from payments.payment_event where payment_id = %s" % U('pay', 12345), ['ix_payment_event_payment_id'])
    Q(p, 'P-11', 'Очистка уведомлений старше 14 суток',
      "select gateway_payment_id, event_type from payments.payment_event where received_at < now() - interval '14 days' limit 10000", ['ix_payment_event_received_at'])

    d = 'delivery_db'
    Q(d, 'D-01', 'ADR-011 диспетчер выдачи',
      "select id from delivery.delivery where status = 'queued' and next_attempt_at <= now() order by next_attempt_at limit 20 for update skip locked",
      ['ix_delivery_dispatch'], True)
    Q(d, 'D-02', 'Опрос статусов отправленных писем',
      "select id from delivery.delivery where status = 'sent' and sent_at < now() - interval '5 minutes'", ['ix_delivery_sent_poll'])
    Q(d, 'D-03', 'Выдачи заказа', "select * from delivery.delivery where order_id = %s order by created_at" % U('o', 12345), ['ix_delivery_order_id'])
    Q(d, 'D-04', 'INV-18 первичная выдача заказа',
      "select id from delivery.delivery where order_id = %s and kind = 'primary'" % U('o', 12345), ['uq_delivery_order_id_primary', 'ix_delivery_order_id'])
    Q(d, 'D-05', 'INV-44 выдачи покупателя при анонимизации',
      "update delivery.delivery set address = 'anonymized@invalid' where buyer_id = %s" % U('u', 700), ['ix_delivery_buyer_id'])
    Q(d, 'D-06', 'NFT-2.4 контроль доставки 30 минут',
      "select order_id from delivery.delivery_watch where status = 'open' and deadline_at <= now() order by deadline_at limit 50 for update skip locked",
      ['ix_delivery_watch_due'], True)
    Q(d, 'D-07', 'Попытки отправки выдачи', "select * from delivery.delivery_attempt where delivery_id = %s order by attempt_no" % U('d', 12345), ['pk_delivery_attempt'])
    Q(d, 'D-08', 'Статус письма от провайдера',
      "select 1 from delivery.provider_status_event where delivery_id = %s and provider_status = 'delivered'" % U('d', 12345), ['pk_provider_status_event'])

    f = 'platform_db'
    Q(f, 'F-01', 'US-8.1 пользователи, фильтр роли и статуса',
      "select * from identity.user_account where role = 'moderator' and status = 'active' order by registered_at desc, id desc limit 20",
      ['ix_user_account_role_status', 'ix_user_account_role', 'ix_user_account_registered_at'], True)
    Q(f, 'F-02', 'US-8.1 пользователи, только роль (покупатели)',
      "select * from identity.user_account where role = 'buyer' order by registered_at desc, id desc limit 20",
      ['ix_user_account_role', 'ix_user_account_role_status', 'ix_user_account_registered_at'], True)
    Q(f, 'F-03', 'US-8.1 пользователи без фильтров',
      "select * from identity.user_account order by registered_at desc, id desc limit 20", ['ix_user_account_registered_at'], True)
    Q(f, 'F-04', 'US-8.1 пользователь по точному e-mail', "select * from identity.user_account where email = 'user4242@example.com'", ['uq_user_account_email'])
    Q(f, 'F-05', 'INV-34 пользователь по номеру телефона', "select id from identity.user_account where phone = '+79000004242'", ['uq_user_account_phone'])
    Q(f, 'F-06', 'Сверка identity-reconciler: телефоны не переданы в Keycloak',
      "select id from identity.user_account where phone_confirmed and not phone_synced_to_idp", ['ix_user_account_phone_unsynced'])
    Q(f, 'F-07', 'Очистка прежних адресов e-mail',
      "select id from identity.user_account where previous_email is not null and previous_email_until <= now()", ['ix_user_account_previous_email_until'])
    Q(f, 'F-08', 'US-7.2 очередь обращений',
      "select * from support.ticket where status = 'created' order by created_at, id limit 20", ['ix_ticket_status_created_at'], True)
    Q(f, 'F-09', 'US-7.2 обращения оператора',
      "select * from support.ticket where operator_id = %s and status = 'in_progress' order by created_at" % U('u', 5), ['ix_ticket_operator_id'])
    Q(f, 'F-10', 'US-7.1 обращения покупателя',
      "select * from support.ticket where buyer_id = %s order by created_at desc, id desc limit 20" % U('u', 700), ['ix_ticket_buyer_id'], True)
    Q(f, 'F-11', 'Обращения заказа', "select * from support.ticket where order_id = %s order by created_at desc" % U('o', 700), ['ix_ticket_order_id'])
    Q(f, 'F-12', 'INV-21 открытое обращение по заказу',
      "select id from support.ticket where order_id = %s and status in ('created', 'in_progress')" % U('o', 700), ['uq_ticket_order_id_open', 'ix_ticket_order_id'])
    Q(f, 'F-13', 'INV-22 заказ в модели чтения поддержки', "select * from support.order_view where order_id = %s" % U('o', 700), ['pk_order_view'])
    Q(f, 'F-25', 'FT-5.0 заказ в модели чтения поддержки по короткому номеру', "select * from support.order_view where order_number = 1700", ['uq_order_view_order_number'])
    Q(f, 'F-14', 'Отправитель уведомлений',
      "select id from notification.notification where status = 'queued' and next_attempt_at <= now() order by next_attempt_at, id limit 50 for update skip locked",
      ['ix_notification_dispatch'], True)
    Q(f, 'F-15', 'Недоставленные письма', "select * from notification.notification where in_dead_queue order by updated_at limit 50", ['ix_notification_dead_queue'], True)
    Q(f, 'F-16', 'Уведомления пользователя',
      "select * from notification.notification where user_id = %s order by created_at desc limit 20" % U('u', 700), ['ix_notification_user_id'], True)
    Q(f, 'F-17', 'ADR-006 одно событие, одно письмо',
      "select 1 from notification.notification where source_event_id = %s and template = 'order_issued'" % U('ne', 700), ['uq_notification_source_event_id_template'])
    Q(f, 'F-18', 'US-8.7 журнал аудита без фильтров',
      "select * from audit_admin.audit_log order by occurred_at desc, id desc limit 50", ['ix_audit_log_occurred_at'], True)
    Q(f, 'F-19', 'US-8.7 журнал аудита за период',
      "select * from audit_admin.audit_log where occurred_at >= '2026-11-10' and occurred_at < '2026-11-12' order by occurred_at desc, id desc limit 50",
      ['ix_audit_log_occurred_at'], True)
    Q(f, 'F-20', 'US-8.7 журнал аудита по сотруднику',
      "select * from audit_admin.audit_log where actor_id = %s order by occurred_at desc, id desc limit 50" % U('u', 7), ['ix_audit_log_actor_id'], True)
    Q(f, 'F-21', 'US-8.7 журнал аудита по объекту',
      "select * from audit_admin.audit_log where object_type = 'product' and object_id = md5('obj1234') order by occurred_at desc, id desc limit 50",
      ['ix_audit_log_object'], True)
    Q(f, 'F-22', 'US-8.7 журнал аудита по виду действия',
      "select * from audit_admin.audit_log where action = 'parameter.update' order by occurred_at desc, id desc limit 50", ['ix_audit_log_action'], True)
    Q(f, 'F-23', 'ADR-014 дубль события аудита',
      "select 1 from audit_admin.audit_log where event_id = %s and occurred_at = timestamptz '2026-10-02 00:00:01'" % U('ae', 1), ['uq_audit_log_event_id'])

    # Служебные таблицы есть в каждой базе: запросы публикатора и очистки проверяем везде
    for k, db in enumerate(DBS, 1):
        Q(db, 'S-%d1' % k, 'ADR-005 публикатор outbox (%s)' % db,
          "select id, event_id, topic, payload from public.outbox where published_at is null and failed_at is null order by id limit 100",
          ['ix_outbox_unpublished'], True)
        Q(db, 'S-%d2' % k, 'ADR-005 очистка отправленных событий старше 3 суток (%s)' % db,
          "select id from public.outbox where published_at < now() - interval '3 days' limit 10000", ['ix_outbox_published_at'])
        Q(db, 'S-%d3' % k, 'ADR-006 очистка обработанных событий старше 14 суток (%s)' % db,
          "select consumer, event_id from public.processed_event where processed_at < now() - interval '14 days' limit 10000",
          ['ix_processed_event_processed_at'])
        Q(db, 'S-%d4' % k, 'ADR-006 очистка ключей идемпотентности старше 24 часов (%s)' % db,
          "select scope, key from public.idempotency_key where created_at < now() - interval '24 hours' limit 10000", ['ix_idempotency_key_created_at'])
        Q(db, 'S-%d5' % k, 'ADR-005 припаркованные события (%s)' % db,
          "select * from public.outbox where failed_at is not null order by failed_at limit 100", ['ix_outbox_failed_at'])
    Q('order_db', 'S-61', 'ADR-006 ключ идемпотентности запроса',
      "select * from public.idempotency_key where scope = 'user:5' and key = md5('ik5')::uuid", ['pk_idempotency_key'])
    Q('order_db', 'S-62', 'ADR-006 обработанное событие потребителя',
      "select 1 from public.processed_event where consumer = 'service.handler' and event_id = md5('pe5')::uuid", ['pk_processed_event'])
    Q('delivery_db', 'D-09', 'Очистка статусов провайдера старше 14 суток',
      "select delivery_id, provider_status from delivery.provider_status_event where received_at < now() - interval '14 days' limit 10000",
      ['ix_provider_status_event_received_at'])
    Q('platform_db', 'F-24', 'Очистка статусов провайдера уведомлений старше 14 суток',
      "select notification_id, provider_status from notification.provider_status_event where received_at < now() - interval '14 days' limit 10000",
      ['ix_provider_status_event_received_at'])


# ------------------------------------------------------------------------------------------------ разбор плана
def walk(node, out):
    out.append(node)
    for ch in node.get('Plans', []) or []:
        walk(ch, out)


def parent_indexes(c, name):
    """Индекс секции приводится к индексу родителя (через pg_inherits)."""
    res, todo = {name}, [name]
    while todo:
        cur = todo.pop()
        rows = c.query("select p.relname from pg_inherits i join pg_class ch on ch.oid = i.inhrelid join pg_class p on p.oid = i.inhparent where ch.relname = '%s'" % cur)
        for (p,) in rows:
            if p not in res:
                res.add(p)
                todo.append(p)
    return res


def analyze_plan(c, db, sql, big_tables):
    raw = c.scalar('explain (format json) ' + sql)
    plan = json.loads(raw)[0]['Plan']
    nodes = []
    walk(plan, nodes)
    used, seq, sorts, desc = set(), [], 0, []
    for nd in nodes:
        t = nd['Node Type']
        if 'Index Name' in nd:
            used |= parent_indexes(c, nd['Index Name'])
            desc.append('%s по %s' % (t, nd['Index Name']))
        if t.endswith('Seq Scan'):
            rel = nd.get('Relation Name')
            seq.append(rel)
            desc.append('%s по %s' % (t, rel))
        if t in ('Sort', 'Incremental Sort'):
            rows_in = max([ch.get('Plan Rows', 0) for ch in nd.get('Plans', [])] or [0])
            desc.append('%s %d строк' % (t, rows_in))
            if rows_in > SORT_ROWS:
                sorts += 1
    return used, seq, sorts, desc, plan.get('Total Cost')


def big_tables_of(c):
    rows = c.query("select n.nspname || '.' || c.relname, c.reltuples::bigint from pg_class c join pg_namespace n on n.oid = c.relnamespace "
                   "where c.relkind in ('r', 'p') and n.nspname not in ('pg_catalog', 'information_schema')")
    out = {}
    for name, t in rows:
        bare = name.split('.', 1)[1]
        out[bare] = max(out.get(bare, 0), int(t))
    return out


def all_indexes(c):
    rows = c.query("select i.relname from pg_index x join pg_class i on i.oid = x.indexrelid join pg_class t on t.oid = x.indrelid "
                   "join pg_namespace n on n.oid = t.relnamespace where n.nspname not in ('pg_catalog', 'information_schema', 'pg_toast') "
                   "and not x.indisprimary and not i.relispartition")
    return {r[0] for r in rows}


def run_queries(report_path, only=None):
    build_queries()
    if only:
        QUERIES[:] = [q for q in QUERIES if q['db'] in only]
    failures, results, sizes = [], [], {}
    conns = {}
    used_by_db = {}
    for q in QUERIES:
        db = q['db']
        if db not in conns:
            conns[db] = conn(db)
            sizes[db] = big_tables_of(conns[db])
        c = conns[db]
        try:
            used, seq, sorts, desc, cost = analyze_plan(c, db, q['sql'], sizes[db])
        except PgError as e:
            failures.append('%s: запрос не выполнился: %s' % (q['id'], e.message))
            results.append((q, ['ошибка'], 'ошибка'))
            continue
        problems = []
        if not (set(q['expect']) & used):
            problems.append('нет ожидаемого индекса %s, в плане: %s' % (' или '.join(q['expect']), ', '.join(sorted(used)) or 'ни одного'))
        for rel in seq:
            rows = sizes[db].get(rel, 0)
            if rows >= BIG_ROWS and rel not in q['allow_seq']:
                problems.append('полный просмотр большой таблицы %s (%d строк)' % (rel, rows))
        if q['no_sort'] and sorts:
            problems.append('в плане есть сортировка больше %d строк: порядок не берётся из индекса' % SORT_ROWS)
        status = 'ошибка' if problems else 'ok'
        results.append((q, desc, status))
        used_by_db.setdefault(db, set()).update(used)
        if problems:
            failures.append('%s (%s): %s' % (q['id'], q['story'], '; '.join(problems)))
        print('%s %s %s' % (status.upper().ljust(6), q['id'], q['story']), flush=True)
    unused = {}
    for db in DBS:
        if only and db not in only:
            continue
        if db not in conns:
            conns[db] = conn(db)
        names = sorted(x for x in all_indexes(conns[db]) - used_by_db.get(db, set()) if x not in INDEX_NOT_QUERY_DRIVEN)
        if names:
            unused[db] = names
            failures.append('%s: индексы без запроса в проверке: %s' % (db, ', '.join(names)))
    if report_path:
        write_report(report_path, results, sizes, unused)
    for c in conns.values():
        c.close()
    return failures, len(QUERIES)


def write_report(path, results, sizes, unused):
    lines = ['# Отчёт проверки индексов запросов (EXPLAIN)', '',
             'Отчёт собирает скрипт `check_explain.py`: данные загружаются в базы, выполняется `ANALYZE`, для каждого запроса истории берётся план `EXPLAIN (FORMAT JSON)`. '
             'Критерии успеха: в плане есть ожидаемый индекс, нет полного просмотра таблицы больше %d строк, у запросов со страницей «новые выше» нет сортировки больше %d строк (порядок берётся из индекса). ' % (BIG_ROWS, SORT_ROWS) +
             'Кроме того, каждый индекс базы (кроме обслуживающих только ограничения) должен встретиться хотя бы в одном плане, иначе он лишний. ' +
             'План показывает, что именно читает база, но не замеряет время: скорость зависит от оборудования.', '',
             '## Объёмы тестовых данных', '', '| База | Таблица | Строк |', '| --- | --- | --- |']
    for db in DBS:
        for name, rows in sorted(sizes.get(db, {}).items()):
            if rows >= 1000 and '_20' not in name and not name.endswith('audit_log_default'):
                lines.append('| `%s` | `%s` | %s |' % (db, name, format(rows, ',').replace(',', ' ')))
    lines += ['', '## Запросы', '', '| № | База | История и запрос | Что читает база | Итог |', '| --- | --- | --- | --- | --- |']
    for q, desc, status in results:
        what = '; '.join(dict.fromkeys(desc)) or 'нет узлов чтения'
        lines.append('| %s | `%s` | %s | %s | %s |' % (q['id'], q['db'], q['story'], what.replace('|', '/'), 'верно' if status == 'ok' else 'нарушение'))
    lines.append('')
    text = '\n'.join(lines)
    with open(path, 'w', encoding='utf-8') as fh:
        fh.write(text)


def main():
    args = sys.argv[1:]
    report = None
    scale = 1.0
    if '--report' in args:
        report = args[args.index('--report') + 1]
    if '--scale' in args:
        scale = float(args[args.index('--scale') + 1])
    only = args[args.index('--db') + 1].split(',') if '--db' in args else None
    if '--no-load' not in args:
        load_all(scale, only)
    failures, total = run_queries(report, only)
    print('\nзапросов проверено: %d, нарушений: %d' % (total, len(failures)))
    for f in failures:
        print('НАРУШЕНИЕ', f)
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
