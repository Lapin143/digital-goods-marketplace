-- СОЗДАН СКРИПТОМ tools/docs-checks/db/gen_migrations.py ИЗ tools/docs-checks/db/parts/platform.sql. РУКАМИ НЕ ПРАВИТЬ.
-- Сервис platform-service, база platform_db. Миграция V6, роли и права.
-- Применённую миграцию править нельзя: Flyway сверяет контрольную сумму, правка вызовет ошибку проверки при старте.
-- Полная модель одним файлом: docs/07-data/ddl/platform-service.sql

-- ============================================================================
-- Роли и права
-- ============================================================================
do $$
declare
    r text;
begin
    foreach r in array array['app_identity', 'app_support', 'app_notification', 'app_audit_admin', 'app_audit_writer'] loop
        if not exists (select from pg_roles where rolname = r) then
            execute format('create role %I nologin', r);
        end if;
    end loop;
end
$$;

grant usage on schema identity to app_identity;
-- DELETE у учётных записей нет: деактивация сохраняет историю (US-8.2)
grant select, insert, update on identity.user_account to app_identity;

grant usage on schema support to app_support;
grant select, insert, update on support.ticket, support.email_change, support.order_view to app_support;

grant usage on schema notification to app_notification;
grant select, insert, update on notification.notification to app_notification;
grant select, insert, delete on notification.provider_status_event to app_notification;

grant usage on schema audit_admin to app_audit_admin;
grant select, insert, update on audit_admin.platform_parameter to app_audit_admin;

-- журнал: только INSERT и SELECT, ни UPDATE, ни DELETE, ни TRUNCATE (ADR-014)
grant usage on schema audit_admin to app_audit_writer;
grant select, insert on audit_admin.audit_log to app_audit_writer;

grant usage on schema public to app_identity, app_support, app_notification, app_audit_admin, app_audit_writer;
grant select, insert, update, delete on public.outbox, public.processed_event, public.idempotency_key
    to app_identity, app_support, app_notification, app_audit_admin;
-- приёмник аудита: только дедупликация потребителя
grant select, insert on public.processed_event to app_audit_writer;
grant usage on all sequences in schema public to app_identity, app_support, app_notification, app_audit_admin, app_audit_writer;

comment on function identity.keep_last_admin() is 'В системе остаётся хотя бы один активный администратор (INV-38): проверка под консультативной блокировкой, два одновременных снятия не пройдут оба';
comment on function identity.user_account_touch() is 'Идентификатор и дата регистрации неизменны, анонимизация необратима (INV-44), версия растёт на единицу';
comment on function support.ticket_touch() is 'Заказ, покупатель, источник и причина обращения неизменны, версия растёт на единицу';
comment on function audit_admin.platform_parameter_guard() is 'Ключ параметра неизменен, версия растёт на единицу при каждой правке (ETag)';
comment on function audit_admin.check_session_shorter_than_reserve() is 'Срок платёжной сессии строго короче срока резерва (INV-07): проверка под консультативной блокировкой при правке любого из двух параметров';
comment on function audit_admin.audit_changes_valid(jsonb) is 'Форма поля changes журнала аудита: массив записей «поле, признак персональных данных, до, после», у персональных полей значения только HMAC-хеши (INV-42)';
comment on function audit_admin.audit_log_deny() is 'Запрещает изменение, удаление и очистку записей журнала аудита для всех ролей, в том числе владельца (INV-42)';
