-- СОЗДАН СКРИПТОМ tools/docs-checks/db/gen_migrations.py ИЗ tools/docs-checks/db/parts/platform.r2.sql. РУКАМИ НЕ ПРАВИТЬ.
-- Сервис platform-service, база platform_db. Миграция V8, R2, параметр окна ответа продавца (срез 1 Ф5, Р-190).
-- Применённую миграцию править нельзя: Flyway сверяет контрольную сумму, правка вызовет ошибку проверки при старте.
-- Полная модель одним файлом: docs/07-data/ddl/platform-service.sql

-- ============================================================================
-- R2, audit_admin: параметр окна ответа продавца (срез 1 Ф5, Р-190)
-- ============================================================================
insert into audit_admin.platform_parameter (key, description, unit, value, default_value, min_value, max_value) values
    ('delivery.seller-response-seconds', 'Окно ответа продавца на запрос ключа по API: от оплаты до тайм-аута выдачи (FT-7.4, US-6.7)', 'seconds', 600, 600, 30, 900);
