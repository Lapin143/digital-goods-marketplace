-- СОЗДАН СКРИПТОМ tools/docs-checks/db/gen_migrations.py ИЗ tools/docs-checks/db/parts/payment.sql. РУКАМИ НЕ ПРАВИТЬ.
-- Сервис payment-service, база payment_db. Миграция V3, роли и права.
-- Применённую миграцию править нельзя: Flyway сверяет контрольную сумму, правка вызовет ошибку проверки при старте.
-- Полная модель одним файлом: docs/07-data/ddl/payment-service.sql

-- ============================================================================
-- Роль и права
-- ============================================================================
do $$
begin
    if not exists (select from pg_roles where rolname = 'app_payments') then
        create role app_payments nologin;
    end if;
end
$$;
grant usage on schema payments to app_payments;
grant select, insert, update on payments.payment, payments.refund_attempt, payments.unmatched_notification to app_payments;
grant select, insert, delete on payments.payment_event to app_payments;
grant usage on schema public to app_payments;
grant select, insert, update, delete on public.outbox, public.processed_event, public.idempotency_key to app_payments;
grant usage on all sequences in schema public to app_payments;

comment on function payments.payment_immutable() is 'Заказ, сумма и валюта платежа неизменны, внешние идентификаторы платежа и возврата записываются один раз (INV-14, INV-15), версия растёт на единицу';
comment on function payments.refund_attempt_guard() is 'Завершённая попытка возврата не меняется, платёж и номер попытки неизменны (INV-15)';
