-- СОЗДАН СКРИПТОМ tools/docs-checks/db/gen_migrations.py ИЗ tools/docs-checks/db/parts/inventory.sql. РУКАМИ НЕ ПРАВИТЬ.
-- Сервис inventory-service, база inventory_db. Миграция V3, роли и права.
-- Применённую миграцию править нельзя: Flyway сверяет контрольную сумму, правка вызовет ошибку проверки при старте.
-- Полная модель одним файлом: docs/07-data/ddl/inventory-service.sql

-- ============================================================================
-- Роль и права
-- ============================================================================
do $$
begin
    if not exists (select from pg_roles where rolname = 'app_inventory') then
        create role app_inventory nologin;
    end if;
end
$$;
grant usage on schema inventory to app_inventory;
grant select, insert, update on inventory.product_copy, inventory.data_key, inventory.reservation to app_inventory;
-- у ключей нет DELETE: единица товара не исчезает, аннулирование это статус
grant select, insert, update on inventory.key to app_inventory;
grant usage on schema public to app_inventory;
grant select, insert, update, delete on public.outbox, public.processed_event, public.idempotency_key to app_inventory;
grant usage on all sequences in schema public to app_inventory;
grant usage on all sequences in schema inventory to app_inventory;

comment on function inventory.key_immutable() is 'Идентификатор и товар ключа неизменны (на них опирается шифрование), закреплённый за заказом ключ не перепривязывается (INV-01, второй рубеж)';
