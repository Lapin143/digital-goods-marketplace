-- СОЗДАН СКРИПТОМ tools/docs-checks/db/gen_migrations.py ИЗ tools/docs-checks/db/parts/platform.sql. РУКАМИ НЕ ПРАВИТЬ.
-- Сервис platform-service, база platform_db. Миграция V4, схема notification.
-- Применённую миграцию править нельзя: Flyway сверяет контрольную сумму, правка вызовет ошибку проверки при старте.
-- Полная модель одним файлом: docs/07-data/ddl/platform-service.sql

-- ============================================================================
-- Схема notification: очередь писем (одноразовые коды и счётчики лежат в Redis, таблиц для них нет)
-- ============================================================================
create schema notification;
comment on schema notification is 'Модуль notification: очередь уведомлений. Адреса получателей не хранятся (INV-44). Своя роль app_notification';

create table notification.notification (
    id              uuid        not null,
    user_id         uuid        not null,
    template        text        not null,
    channel         text        not null default 'email',
    params          jsonb       not null default '{}',
    source_event_id uuid,
    attempts        integer     not null default 0,
    next_attempt_at timestamptz,
    last_error_code text,
    in_dead_queue   boolean     not null default false,
    sent_at         timestamptz,
    status          text        not null default 'queued',
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    constraint pk_notification primary key (id),
    constraint ck_notification_status check (status in ('queued', 'sent', 'delivered', 'failed')),
    constraint ck_notification_channel check (channel in ('email', 'sms', 'webhook')),
    constraint ck_notification_template check (template ~ '^[a-z0-9_.-]{1,64}$'),
    -- INV-44: параметры шаблона не содержат адресов и имён, получатель берётся у identity в момент отправки
    constraint ck_notification_params check (
        jsonb_typeof(params) = 'object' and not (params ?| array['email', 'phone', 'name', 'address', 'recipient'])
    ),
    constraint ck_notification_attempts check (attempts between 0 and 10),
    constraint ck_notification_last_error_code check (last_error_code ~ '^[a-z0-9_.-]{1,64}$'),
    constraint ck_notification_queue check ((status = 'queued') = (next_attempt_at is not null)),
    constraint ck_notification_dead_queue check (not in_dead_queue or status = 'failed'),
    constraint ck_notification_sent_at check (status not in ('sent', 'delivered') or sent_at is not null)
);
comment on table notification.notification is 'Письмо, SMS или webhook в очереди отправки. Получатель только по идентификатору пользователя (INV-44)';
comment on column notification.notification.id is 'NotificationID, он же идентификатор сообщения у провайдера';
comment on column notification.notification.user_id is 'UserID получателя, адрес берётся у identity.api в момент отправки';
comment on column notification.notification.template is 'Код шаблона письма, например order.paid';
comment on column notification.notification.channel is 'email, sms или webhook (в R1 письма)';
comment on column notification.notification.params is 'Параметры шаблона без персональных данных: идентификаторы, суммы, статусы';
comment on column notification.notification.source_event_id is 'Событие, из которого создано уведомление: повторная обработка события не создаёт второе письмо по тому же шаблону';
comment on column notification.notification.attempts is 'Число выполненных попыток отправки';
comment on column notification.notification.next_attempt_at is 'Срок следующей попытки, есть только у уведомления в очереди';
comment on column notification.notification.last_error_code is 'Код последней ошибки без свободного текста';
comment on column notification.notification.in_dead_queue is 'Признак «в очереди недоставленных»: письмо не доставлено после повторов (FT-7.3), даёт notification.failed';
comment on column notification.notification.sent_at is 'Когда провайдер принял сообщение';
comment on column notification.notification.status is 'queued, sent, delivered, failed (статусной модели SM нет, значения служебные)';
comment on column notification.notification.created_at is 'Когда поставлено в очередь';
comment on column notification.notification.updated_at is 'Последнее изменение';
create unique index uq_notification_source_event_id_template on notification.notification (source_event_id, template) where source_event_id is not null;
comment on index notification.uq_notification_source_event_id_template is 'Одно событие даёт одно письмо по шаблону, повторная обработка события безопасна (ADR-006)';
create index ix_notification_dispatch on notification.notification (next_attempt_at, id) where status = 'queued';
comment on index notification.ix_notification_dispatch is 'Отправитель: WHERE status = ''queued'' AND next_attempt_at <= now() ORDER BY next_attempt_at FOR UPDATE SKIP LOCKED';
create index ix_notification_dead_queue on notification.notification (updated_at) where in_dead_queue;
comment on index notification.ix_notification_dead_queue is 'Разбор недоставленных писем администратором и оповещение';
create index ix_notification_user_id on notification.notification (user_id, created_at desc);
comment on index notification.ix_notification_user_id is 'Уведомления пользователя для разбора обращений и обезличивания по идентификатору';

create trigger trg_notification_status_initial before insert on notification.notification
    for each row execute function public.enforce_status_transition('initial:queued');
create trigger trg_notification_status_transition before update of status on notification.notification
    for each row when (old.status is distinct from new.status)
    execute function public.enforce_status_transition('queued>sent', 'queued>failed', 'sent>delivered', 'sent>failed');

create table notification.provider_status_event (
    notification_id uuid        not null,
    provider_status text        not null,
    occurred_at     timestamptz not null,
    received_at     timestamptz not null default now(),
    constraint pk_provider_status_event primary key (notification_id, provider_status),
    constraint fk_provider_status_event_notification_id foreign key (notification_id) references notification.notification (id),
    constraint ck_provider_status_event_status_len check (char_length(provider_status) between 1 and 64)
);
comment on table notification.provider_status_event is 'Принятые статусы писем от провайдера: дедупликация по паре «сообщение, статус»';
comment on column notification.provider_status_event.notification_id is 'Уведомление, оно же сообщение у провайдера';
comment on column notification.provider_status_event.provider_status is 'Статус от провайдера (непрозрачная строка)';
comment on column notification.provider_status_event.occurred_at is 'Время события по данным провайдера';
comment on column notification.provider_status_event.received_at is 'Когда статус принят';
create index ix_provider_status_event_received_at on notification.provider_status_event (received_at);
comment on index notification.ix_provider_status_event_received_at is 'Очистка записей старше срока хранения (14 суток)';
