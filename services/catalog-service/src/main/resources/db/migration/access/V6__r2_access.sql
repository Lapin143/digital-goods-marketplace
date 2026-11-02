-- СОЗДАН СКРИПТОМ tools/docs-checks/db/gen_migrations.py ИЗ tools/docs-checks/db/parts/catalog.r2.sql. РУКАМИ НЕ ПРАВИТЬ.
-- Сервис catalog-service, база catalog_db. Миграция V6, R2, права на таблицы R2.
-- Применённую миграцию править нельзя: Flyway сверяет контрольную сумму, правка вызовет ошибку проверки при старте.
-- Полная модель одним файлом: docs/07-data/ddl/catalog-service.sql

-- ============================================================================
-- R2, access: права на таблицы R2
-- ============================================================================
grant select, insert, update on seller_onboarding.api_key, seller_onboarding.seller_integration to app_seller_onboarding;
