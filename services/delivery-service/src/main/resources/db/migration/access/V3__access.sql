-- СОЗДАН СКРИПТОМ tools/docs-checks/db/gen_migrations.py ИЗ tools/docs-checks/db/parts/delivery.sql. РУКАМИ НЕ ПРАВИТЬ.
-- Сервис delivery-service, база delivery_db. Миграция V3, роли и права.
-- Применённую миграцию править нельзя: Flyway сверяет контрольную сумму, правка вызовет ошибку проверки при старте.
-- Полная модель одним файлом: docs/07-data/ddl/delivery-service.sql

-- ============================================================================
-- Роль и права
-- ============================================================================
do $$
begin
    if not exists (select from pg_roles where rolname = 'app_delivery') then
        create role app_delivery nologin;
    end if;
end
$$;
grant usage on schema delivery to app_delivery;
grant select, insert, update on delivery.delivery, delivery.delivery_watch to app_delivery;
grant select, insert on delivery.delivery_attempt to app_delivery;
grant select, insert, delete on delivery.provider_status_event to app_delivery;
grant usage on schema public to app_delivery;
grant select, insert, update, delete on public.outbox, public.processed_event, public.idempotency_key to app_delivery;
grant usage on all sequences in schema public to app_delivery;

comment on function delivery.delivery_immutable() is 'Заказ, покупатель, тип, количество и название товара выдачи неизменны, время изменения обновляется';
