-- СОЗДАН СКРИПТОМ tools/docs-checks/db/gen_migrations.py ИЗ tools/docs-checks/db/parts/order.sql. РУКАМИ НЕ ПРАВИТЬ.
-- Сервис order-service, база order_db. Миграция V3, роли и права.
-- Применённую миграцию править нельзя: Flyway сверяет контрольную сумму, правка вызовет ошибку проверки при старте.
-- Полная модель одним файлом: docs/07-data/ddl/order-service.sql

-- ============================================================================
-- Роль и права
-- ============================================================================
do $$
begin
    if not exists (select from pg_roles where rolname = 'app_orders') then
        create role app_orders nologin;
    end if;
end
$$;
grant usage on schema orders to app_orders;
grant select, insert, update on orders.orders to app_orders;
grant usage on schema public to app_orders;
grant select, insert, update, delete on public.outbox, public.processed_event, public.idempotency_key to app_orders;
grant usage on all sequences in schema public to app_orders;

comment on function orders.orders_immutable() is 'Снимки заказа неизменны (INV-12, INV-13), дата первой выдачи записывается один раз (INV-17), версия растёт на единицу при каждом изменении';
