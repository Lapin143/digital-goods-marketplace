-- СОЗДАН СКРИПТОМ tools/docs-checks/db/gen_migrations.py ИЗ tools/docs-checks/db/parts/order.r2.sql. РУКАМИ НЕ ПРАВИТЬ.
-- Сервис order-service, база order_db. Миграция V4, R2, отмена оплаченного заказа и список заказов продавца (срез 1 Ф5, Р-189, Р-192).
-- Применённую миграцию править нельзя: Flyway сверяет контрольную сумму, правка вызовет ошибку проверки при старте.
-- Полная модель одним файлом: docs/07-data/ddl/order-service.sql

-- ============================================================================
-- R2, orders: отмена оплаченного заказа и список заказов продавца (срез 1 Ф5, Р-189, Р-192)
-- ============================================================================
-- SM-01/T10 (оплачен > отменён): продавец с выдачей по API не ответил или вернул ошибку, причина seller_api_timeout (Р-192).
-- T11 (выдан > возвращён) добавит срез 2
drop trigger trg_orders_status_transition on orders.orders;
create trigger trg_orders_status_transition before update of status on orders.orders
    for each row when (old.status is distinct from new.status)
    execute function public.enforce_status_transition(
        'created>awaiting_payment', 'created>cancelled', 'awaiting_payment>paid', 'awaiting_payment>cancelled',
        'cancelled>paid', 'cancelled>refunded', 'paid>issued', 'paid>cancelled');

-- способ выдачи товара на момент заказа (снимок, Р-190, Р-189): по нему «Выдача» решает, спрашивать ли ключ у системы продавца, T10 допустим
-- только для seller_api, а продавец видит способ в списке заказов. Существующие заказы R1 это key_pool
alter table orders.orders add column issuance_method text not null default 'key_pool';
alter table orders.orders add constraint ck_orders_issuance_method check (issuance_method in ('key_pool', 'seller_api'));
comment on column orders.orders.issuance_method is 'Способ выдачи товара на момент оформления (снимок из карточки каталога, Р-190): key_pool или seller_api. Не меняется, как и остальные снимки заказа';
-- когда деньги вернулись (T8): список заказов продавца показывает время возврата (OP-100, Р-189)
alter table orders.orders add column refunded_at timestamptz;
alter table orders.orders add constraint ck_orders_refunded_at check (refunded_at is null or status = 'refunded');
comment on column orders.orders.refunded_at is 'Когда заказ стал refunded (T8): деньги вернулись покупателю. Пусто у остальных статусов';

-- способ выдачи неизменен, как остальные снимки заказа (INV-12, INV-13); остальное тело функции то же, что в R1
create or replace function orders.orders_immutable() returns trigger
language plpgsql as
$fn$
begin
    if new.id is distinct from old.id
       or new.buyer_id is distinct from old.buyer_id
       or new.seller_id is distinct from old.seller_id
       or new.number is distinct from old.number
       or new.product_id is distinct from old.product_id
       or new.product_title is distinct from old.product_title
       or new.quantity is distinct from old.quantity
       or new.unit_price is distinct from old.unit_price
       or new.amount is distinct from old.amount
       or new.commission_rate_bp is distinct from old.commission_rate_bp
       or new.commission is distinct from old.commission
       or new.currency is distinct from old.currency
       or new.issuance_method is distinct from old.issuance_method
       or new.created_at is distinct from old.created_at then
        raise exception 'снимки заказа (номер, товар, цена, количество, комиссия, способ выдачи) не меняются' using errcode = '23514', constraint = 'ck_orders_snapshot_immutable';
    end if;
    if old.issued_at is not null and new.issued_at is distinct from old.issued_at then
        raise exception 'дата первой выдачи фиксируется один раз' using errcode = '23514', constraint = 'ck_orders_issued_at_once';
    end if;
    new.version := old.version + 1;
    new.updated_at := now();
    return new;
end
$fn$;

-- заказы, возвращённые до R2: время возврата берётся из последнего изменения строки (после T8 строка больше не менялась, кроме обезличивания адреса)
update orders.orders set refunded_at = updated_at where status = 'refunded';

-- кабинет продавца: свои заказы, новые выше
create index ix_orders_seller_id on orders.orders (seller_id, created_at desc, id desc);
comment on index orders.ix_orders_seller_id is 'GET /api/v1/seller/orders (OP-100, Р-189): заказы продавца, новые выше, курсор по (created_at, id), фильтры статуса и дат поверх';
