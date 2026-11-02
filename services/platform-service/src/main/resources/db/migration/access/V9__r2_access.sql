-- СОЗДАН СКРИПТОМ tools/docs-checks/db/gen_migrations.py ИЗ tools/docs-checks/db/parts/platform.r2.sql. РУКАМИ НЕ ПРАВИТЬ.
-- Сервис platform-service, база platform_db. Миграция V9, R2, права на таблицы R2.
-- Применённую миграцию править нельзя: Flyway сверяет контрольную сумму, правка вызовет ошибку проверки при старте.
-- Полная модель одним файлом: docs/07-data/ddl/platform-service.sql

-- ============================================================================
-- R2, access: права на таблицы R2
-- ============================================================================
grant select, insert, update on notification.webhook_delivery to app_notification;
