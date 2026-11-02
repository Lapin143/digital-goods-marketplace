-- СОЗДАН СКРИПТОМ tools/docs-checks/db/gen_migrations.py ИЗ tools/docs-checks/db/parts/inventory.r2.sql. РУКАМИ НЕ ПРАВИТЬ.
-- Сервис inventory-service, база inventory_db. Миграция V5, R2, права на таблицы R2.
-- Применённую миграцию править нельзя: Flyway сверяет контрольную сумму, правка вызовет ошибку проверки при старте.
-- Полная модель одним файлом: docs/07-data/ddl/inventory-service.sql

-- ============================================================================
-- R2, access: права на таблицы R2
-- ============================================================================
grant select, insert, update on inventory.api_stock to app_inventory;
