-- СОЗДАН СКРИПТОМ tools/docs-checks/db/gen_migrations.py ИЗ tools/docs-checks/db/parts/platform.sql. РУКАМИ НЕ ПРАВИТЬ.
-- Сервис platform-service, база platform_db. Миграция V3, схема support.
-- Применённую миграцию править нельзя: Flyway сверяет контрольную сумму, правка вызовет ошибку проверки при старте.
-- Полная модель одним файлом: docs/07-data/ddl/platform-service.sql

-- ============================================================================
-- Схема support: обращения, смена e-mail, модель чтения заказа (SM-07)
-- ============================================================================
create schema support;
comment on schema support is 'Модуль support: обращения в поддержку, этапы смены e-mail, модель чтения заказов для окна 72 часа. Ключей и значений кодов нет. Своя роль app_support';

create table support.ticket (
    id                uuid        not null,
    order_id          uuid        not null,
    buyer_id          uuid        not null,
    source            text        not null,
    reason            text        not null,
    operator_id       uuid,
    action            text,
    code_check_result text,
    resolved_by       text,
    taken_at          timestamptz,
    resolved_at       timestamptz,
    status            text        not null default 'created',
    version           integer     not null default 1,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    constraint pk_ticket primary key (id),
    constraint ck_ticket_status check (status in ('created', 'in_progress', 'resolved')),
    constraint ck_ticket_source check (source in ('buyer', 'system')),
    constraint ck_ticket_reason check (reason in ('key_not_received', 'not_delivered', 'attempts_exhausted', 'delivery_overdue')),
    -- причина «не получил ключ» только у покупателя, остальные только у системы
    constraint ck_ticket_source_reason check ((source = 'buyer') = (reason = 'key_not_received')),
    constraint ck_ticket_action check (action in ('resend', 'email_change')),
    constraint ck_ticket_code_check_result check (code_check_result in ('passed', 'failed')),
    constraint ck_ticket_resolved_by check (resolved_by in ('operator', 'system')),
    -- SM-07/T3: у обращения «создано» оператора нет, после взятия в работу он один
    constraint ck_ticket_created_state check (status <> 'created' or (operator_id is null and taken_at is null)),
    constraint ck_ticket_in_progress_state check (status <> 'in_progress' or (operator_id is not null and taken_at is not null)),
    constraint ck_ticket_resolved_state check ((status = 'resolved') = (resolved_at is not null and resolved_by is not null)),
    constraint ck_ticket_version check (version >= 1)
);
comment on table support.ticket is 'Обращение в поддержку по одному заказу. Значения ключей и коды проверки не хранятся (INV-23, FT-7.5)';
comment on column support.ticket.id is 'TicketID';
comment on column support.ticket.order_id is 'OrderID. Открытое обращение по заказу не больше одного (INV-21)';
comment on column support.ticket.buyer_id is 'BuyerID заказа';
comment on column support.ticket.source is 'buyer или system. Окно 72 часа действует только для buyer (INV-22)';
comment on column support.ticket.reason is 'key_not_received (покупатель), not_delivered, attempts_exhausted, delivery_overdue (система)';
comment on column support.ticket.operator_id is 'Оператор, взявший обращение. При возврате в очередь сбрасывается (SM-07/T3)';
comment on column support.ticket.action is 'resend (повторная отправка) или email_change (смена e-mail)';
comment on column support.ticket.code_check_result is 'Результат проверки кода покупателя: passed или failed. Сам код оператору не виден (FT-7.5)';
comment on column support.ticket.resolved_by is 'operator или system (решено по факту доставки, SM-07/T4)';
comment on column support.ticket.taken_at is 'Когда оператор взял обращение в работу';
comment on column support.ticket.resolved_at is 'Когда обращение решено';
comment on column support.ticket.status is 'SM-07: created, in_progress, resolved';
comment on column support.ticket.version is 'Версия строки, увеличивается при каждом изменении';
comment on column support.ticket.created_at is 'Дата создания обращения';
comment on column support.ticket.updated_at is 'Последнее изменение';
-- INV-21
create unique index uq_ticket_order_id_open on support.ticket (order_id) where status in ('created', 'in_progress');
comment on index support.uq_ticket_order_id_open is 'INV-21: открытое обращение по заказу одно, второе (создано или в работе) невозможно, в том числе при гонке события системы и обращения покупателя';
create index ix_ticket_status_created_at on support.ticket (status, created_at, id);
comment on index support.ix_ticket_status_created_at is 'GET /api/v1/staff/support-tickets (US-7.2): очередь по статусу, старые выше. Контроль NFT-2.4: created старше часа';
create index ix_ticket_operator_id on support.ticket (operator_id, status, created_at) where operator_id is not null;
comment on index support.ix_ticket_operator_id is 'GET /api/v1/staff/support-tickets?operatorId=: обращения оператора';
create index ix_ticket_buyer_id on support.ticket (buyer_id, created_at desc, id desc);
comment on index support.ix_ticket_buyer_id is 'GET /api/v1/support-tickets (US-7.1): обращения покупателя, новые выше, фильтры orderId и status поверх';
create index ix_ticket_order_id on support.ticket (order_id, created_at desc);
comment on index support.ix_ticket_order_id is 'Обращения заказа: фильтр orderId, закрытие обращения системы по delivery.delivered';

create trigger trg_ticket_status_initial before insert on support.ticket
    for each row execute function public.enforce_status_transition('initial:created');
create trigger trg_ticket_status_transition before update of status on support.ticket
    for each row when (old.status is distinct from new.status)
    execute function public.enforce_status_transition('created>in_progress', 'in_progress>created', 'created>resolved', 'in_progress>resolved');

create function support.ticket_touch() returns trigger
language plpgsql as
$fn$
begin
    if new.id is distinct from old.id or new.order_id is distinct from old.order_id or new.buyer_id is distinct from old.buyer_id
       or new.source is distinct from old.source or new.reason is distinct from old.reason or new.created_at is distinct from old.created_at then
        raise exception 'заказ, покупатель, источник и причина обращения не меняются' using errcode = '23514', constraint = 'ck_ticket_immutable';
    end if;
    new.version := old.version + 1;
    new.updated_at := now();
    return new;
end
$fn$;
create trigger trg_ticket_touch before update on support.ticket
    for each row execute function support.ticket_touch();

-- Этапы смены e-mail по обращению (SEQ-04). Новый адрес живёт здесь только пока смена не завершена
create table support.email_change (
    ticket_id         uuid        not null,
    user_id           uuid        not null,
    new_email         text,
    new_email_masked  text        not null,
    phone_check       text        not null default 'pending',
    new_email_check   text        not null default 'not_started',
    status            text        not null default 'awaiting_phone_code',
    started_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    constraint pk_email_change primary key (ticket_id),
    constraint fk_email_change_ticket_id foreign key (ticket_id) references support.ticket (id),
    constraint ck_email_change_status check (status in ('awaiting_phone_code', 'awaiting_email_code', 'completed', 'failed')),
    constraint ck_email_change_phone_check check (phone_check in ('pending', 'passed', 'failed')),
    constraint ck_email_change_new_email_check check (new_email_check in ('not_started', 'pending', 'passed', 'failed')),
    constraint ck_email_change_new_email check (new_email = lower(btrim(new_email)) and char_length(new_email) between 3 and 254),
    -- INV-44: после завершения или отказа новый адрес не хранится (остаётся только маска)
    constraint ck_email_change_pii_cleanup check (status in ('awaiting_phone_code', 'awaiting_email_code') or new_email is null),
    -- нельзя перейти к коду из письма, не пройдя проверку телефона
    constraint ck_email_change_order_of_checks check (
        new_email_check = 'not_started' or phone_check = 'passed'
    ),
    constraint ck_email_change_completed check (status <> 'completed' or (phone_check = 'passed' and new_email_check = 'passed'))
);
comment on table support.email_change is 'Этап смены e-mail по обращению: проверка телефона, затем нового адреса. Результат проверки, не код (коды лежат в Redis как HMAC)';
comment on column support.email_change.ticket_id is 'Обращение, по которому идёт смена. Одна запись на обращение, повторная попытка меняет её';
comment on column support.email_change.user_id is 'Пользователь, чей адрес меняется (покупатель заказа)';
comment on column support.email_change.new_email is 'Новый адрес в нормализованном виде (персональные данные). Очищается при завершении или отказе (INV-44)';
comment on column support.email_change.new_email_masked is 'Новый адрес с маской для показа оператору, например n***@mail.example';
comment on column support.email_change.phone_check is 'Проверка кода из SMS: pending, passed, failed';
comment on column support.email_change.new_email_check is 'Проверка кода из письма на новый адрес: not_started, pending, passed, failed';
comment on column support.email_change.status is 'awaiting_phone_code, awaiting_email_code, completed, failed';
comment on column support.email_change.started_at is 'Когда смена начата';
comment on column support.email_change.updated_at is 'Последнее изменение этапа';

-- Модель чтения заказа для окна 72 часа и статуса выдачи (по событиям order.issued и delivery.*)
create table support.order_view (
    order_id            uuid        not null,
    buyer_id            uuid        not null,
    order_status        text        not null,
    issued_at           timestamptz,
    delivery_status     text,
    delivery_updated_at timestamptz,
    updated_at          timestamptz not null default now(),
    constraint pk_order_view primary key (order_id),
    constraint ck_order_view_order_status check (order_status in ('created', 'awaiting_payment', 'paid', 'issued', 'cancelled', 'refunded')),
    constraint ck_order_view_delivery_status check (delivery_status in ('queued', 'sent', 'delivered', 'failed'))
);
comment on table support.order_view is 'Копия данных заказа для поддержки: покупатель, время первичной выдачи (окно 72 часа, INV-22) и статус выдачи. Источник истины: order-service и delivery-service';
comment on column support.order_view.order_id is 'OrderID';
comment on column support.order_view.buyer_id is 'BuyerID: обращение создаёт только покупатель заказа';
comment on column support.order_view.order_status is 'Статус заказа по SM-01 из последнего события';
comment on column support.order_view.issued_at is 'Время первичной выдачи из order.issued: от него считается окно 72 часа';
comment on column support.order_view.delivery_status is 'Статус выдачи по SM-06, который видит оператор';
comment on column support.order_view.delivery_updated_at is 'Время последнего применённого события выдачи: более раннее событие игнорируется';
comment on column support.order_view.updated_at is 'Когда запись обновлена';
