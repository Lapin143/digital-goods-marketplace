-- СОЗДАН СКРИПТОМ tools/docs-checks/db/gen_migrations.py ИЗ tools/docs-checks/db/parts/catalog.sql. РУКАМИ НЕ ПРАВИТЬ.
-- Сервис catalog-service, база catalog_db. Миграция V4, роли и права.
-- Применённую миграцию править нельзя: Flyway сверяет контрольную сумму, правка вызовет ошибку проверки при старте.
-- Полная модель одним файлом: docs/07-data/ddl/catalog-service.sql

-- ============================================================================
-- Роли и права (Flyway выдаёт их в миграциях, правило модульности 2)
-- ============================================================================
do $$
begin
    if not exists (select from pg_roles where rolname = 'app_seller_onboarding') then
        create role app_seller_onboarding nologin;
    end if;
    if not exists (select from pg_roles where rolname = 'app_catalog') then
        create role app_catalog nologin;
    end if;
end
$$;

grant usage on schema seller_onboarding to app_seller_onboarding;
grant select, insert, update on seller_onboarding.seller_profile to app_seller_onboarding;
grant select, insert, update, delete on seller_onboarding.seller_document to app_seller_onboarding;

grant usage on schema catalog to app_catalog;
grant select, insert, update on catalog.product, catalog.stock_view to app_catalog;

grant usage on schema public to app_seller_onboarding, app_catalog;
grant select, insert, update, delete on public.outbox, public.processed_event, public.idempotency_key
    to app_seller_onboarding, app_catalog;
grant usage on all sequences in schema public to app_seller_onboarding, app_catalog;
