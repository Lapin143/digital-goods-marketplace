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

-- кабинет продавца: свои заказы, новые выше
create index ix_orders_seller_id on orders.orders (seller_id, created_at desc, id desc);
comment on index orders.ix_orders_seller_id is 'GET /api/v1/seller/orders (OP-100, Р-189): заказы продавца, новые выше, курсор по (created_at, id), фильтры статуса и дат поверх';
