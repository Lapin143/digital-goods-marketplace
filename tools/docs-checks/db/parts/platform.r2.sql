-- ============================================================================
-- R2, notification: уведомления продавцу в его систему, webhook (срез 1 Ф5, Р-193)
-- ============================================================================
create table notification.webhook_delivery (
    id                 uuid        not null,
    seller_id          uuid        not null,
    event_id           uuid        not null,
    event_type         text        not null,
    order_number       bigint      not null,
    payload            jsonb       not null,
    status             text        not null default 'queued',
    attempts           integer     not null default 0,
    next_attempt_at    timestamptz,
    last_attempt_at    timestamptz,
    last_response_code integer,
    last_error_code    text,
    sent_at            timestamptz,
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now(),
    constraint pk_webhook_delivery primary key (id),
    -- одно событие даёт одно уведомление продавцу, повторная обработка события безопасна (ADR-006)
    constraint uq_webhook_delivery_event_id unique (event_id),
    constraint ck_webhook_delivery_status check (status in ('queued', 'sent', 'failed', 'skipped')),
    constraint ck_webhook_delivery_event_type check (event_type in ('order.paid', 'order.issued', 'order.cancelled', 'order.refunded')),
    constraint ck_webhook_delivery_order_number check (order_number >= 1001),
    -- INV-44: в теле нет персональных данных покупателя, только идентификаторы, количество и сумма
    constraint ck_webhook_delivery_payload check (
        jsonb_typeof(payload) = 'object' and not (payload ?| array['email', 'phone', 'name', 'address', 'recipient', 'buyerId', 'buyer_id'])
    ),
    -- шесть попыток (паузы 0, 30 с, 2, 10, 30 минут, 2 часа), затем статус failed (Р-193)
    constraint ck_webhook_delivery_attempts check (attempts between 0 and 6),
    constraint ck_webhook_delivery_failed_attempts check (status <> 'failed' or attempts = 6),
    constraint ck_webhook_delivery_skipped_attempts check (status <> 'skipped' or attempts = 0),
    constraint ck_webhook_delivery_last_response_code check (last_response_code between 100 and 599),
    constraint ck_webhook_delivery_last_error_code check (last_error_code ~ '^[a-z0-9_.-]{1,64}$'),
    constraint ck_webhook_delivery_queue check ((status = 'queued') = (next_attempt_at is not null)),
    constraint ck_webhook_delivery_sent_at check ((status = 'sent') = (sent_at is not null))
);
comment on table notification.webhook_delivery is 'Уведомление о событии заказа для системы продавца (R2, Р-193): очередь отправки, повторы и итог. Отдельно от notification.notification: получатель продавец, а не пользователь, и диспетчер писем эти строки не берёт';
comment on column notification.webhook_delivery.id is 'DeliveryID уведомления, он же в списке «Недоставленные уведомления» кабинета';
comment on column notification.webhook_delivery.seller_id is 'SellerID получателя. Адрес и версию секрета платформа берёт у catalog-service в момент отправки (getSellerEndpoint), в таблице их нет';
comment on column notification.webhook_delivery.event_id is 'Идентификатор события, одинаков во всех попытках (заголовок X-Event-Id): по нему продавец отсекает дубли. Равен eventId события Kafka, из которого создано уведомление';
comment on column notification.webhook_delivery.event_type is 'Тип события: order.paid, order.issued, order.cancelled, order.refunded. События споров добавит срез 2 миграцией';
comment on column notification.webhook_delivery.order_number is 'Короткий номер заказа для списка в кабинете';
comment on column notification.webhook_delivery.payload is 'Тело уведомления: eventId, type, occurredAt, orderNumber, productId, quantity, amount. Персональных данных нет (INV-44)';
comment on column notification.webhook_delivery.status is 'queued (ждёт отправки или повтора), sent (получатель ответил 2xx), failed (шесть попыток исчерпаны), skipped (подключение отключено или не настроено)';
comment on column notification.webhook_delivery.attempts is 'Сколько попыток сделано, от 0 до 6';
comment on column notification.webhook_delivery.next_attempt_at is 'Срок следующей попытки, есть только у уведомления в очереди';
comment on column notification.webhook_delivery.last_attempt_at is 'Когда была последняя попытка';
comment on column notification.webhook_delivery.last_response_code is 'HTTP-код последнего ответа получателя, пусто, если ответа не было (тайм-аут, обрыв соединения)';
comment on column notification.webhook_delivery.last_error_code is 'Короткий код последней ошибки отправки без свободного текста (например timeout, connection_failed, http_5xx)';
comment on column notification.webhook_delivery.sent_at is 'Когда получатель ответил 2xx';
comment on column notification.webhook_delivery.created_at is 'Когда уведомление поставлено в очередь';
comment on column notification.webhook_delivery.updated_at is 'Последнее изменение';
-- отправитель: пачка уведомлений, срок которых наступил
create index ix_webhook_delivery_dispatch on notification.webhook_delivery (next_attempt_at, id) where status = 'queued';
comment on index notification.ix_webhook_delivery_dispatch is 'Отправитель webhook: WHERE status = ''queued'' AND next_attempt_at <= now() ORDER BY next_attempt_at LIMIT 20 FOR UPDATE SKIP LOCKED';
-- кабинет: доставки продавца, новые выше
create index ix_webhook_delivery_seller_id on notification.webhook_delivery (seller_id, created_at desc, id desc);
comment on index notification.ix_webhook_delivery_seller_id is 'GET /api/v1/seller/webhook-deliveries (OP-102): доставки продавца, новые выше, курсор по (created_at, id), фильтр status поверх';

create trigger trg_webhook_delivery_status_initial before insert on notification.webhook_delivery
    for each row execute function public.enforce_status_transition('initial:queued');
create trigger trg_webhook_delivery_status_transition before update of status on notification.webhook_delivery
    for each row when (old.status is distinct from new.status)
    execute function public.enforce_status_transition('queued>sent', 'queued>failed', 'queued>skipped');

create function notification.webhook_delivery_immutable() returns trigger
language plpgsql as
$fn$
begin
    if new.id is distinct from old.id or new.seller_id is distinct from old.seller_id or new.event_id is distinct from old.event_id
       or new.event_type is distinct from old.event_type or new.order_number is distinct from old.order_number
       or new.payload is distinct from old.payload or new.created_at is distinct from old.created_at then
        raise exception 'получатель, событие и тело уведомления не меняются' using errcode = '23514', constraint = 'ck_webhook_delivery_immutable';
    end if;
    new.updated_at := now();
    return new;
end
$fn$;
create trigger trg_webhook_delivery_immutable before update on notification.webhook_delivery
    for each row execute function notification.webhook_delivery_immutable();
comment on function notification.webhook_delivery_immutable() is 'Получатель, событие и тело webhook-уведомления неизменны (повтор шлёт то же тело с тем же eventId), время изменения обновляется';

-- ============================================================================
-- R2, audit_admin: параметр окна ответа продавца (срез 1 Ф5, Р-190)
-- ============================================================================
insert into audit_admin.platform_parameter (key, description, unit, value, default_value, min_value, max_value) values
    ('delivery.seller-response-seconds', 'Окно ответа продавца на запрос ключа по API: от оплаты до тайм-аута выдачи (FT-7.4, US-6.7)', 'seconds', 600, 600, 30, 900);

-- ============================================================================
-- R2, access: права на таблицы R2
-- ============================================================================
grant select, insert, update on notification.webhook_delivery to app_notification;
