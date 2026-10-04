-- СОЗДАН СКРИПТОМ tools/docs-checks/db/gen_migrations.py ИЗ tools/docs-checks/db/parts/payment.sql. РУКАМИ НЕ ПРАВИТЬ.
-- Сервис payment-service, база payment_db. Миграция V2, схема payments.
-- Применённую миграцию править нельзя: Flyway сверяет контрольную сумму, правка вызовет ошибку проверки при старте.
-- Полная модель одним файлом: docs/07-data/ddl/payment-service.sql

-- ============================================================================
-- Схема payments: платежи, дедупликация уведомлений шлюза, попытки возврата (SM-02)
-- ============================================================================
create schema payments;
comment on schema payments is 'Модуль payments: платёж, события шлюза, попытки возврата, несопоставленные уведомления. Данных карт нет (NFT-5.1). Своя роль app_payments';

create table payments.payment (
    id                   uuid        not null,
    order_id             uuid        not null,
    gateway_payment_id   text,
    gateway_refund_id    text,
    refund_reason        text,
    amount               bigint      not null,
    currency             char(3)     not null default 'RUB',
    session_url          text,
    session_expires_at   timestamptz,
    in_admin_queue       boolean     not null default false,
    refund_escalated_at  timestamptz,
    confirmed_at         timestamptz,
    rejected_at          timestamptz,
    refunded_at          timestamptz,
    status               text        not null default 'created',
    version              integer     not null default 1,
    created_at           timestamptz not null default now(),
    updated_at           timestamptz not null default now(),
    constraint pk_payment primary key (id),
    -- INV-11: один заказ оплачивается одним платежом
    constraint uq_payment_order_id unique (order_id),
    constraint ck_payment_status check (status in ('created', 'confirmed', 'rejected', 'refunded')),
    constraint ck_payment_amount check (amount > 0 and amount <= 9007199254740991),
    constraint ck_payment_currency check (currency = 'RUB'),
    constraint ck_payment_gateway_payment_id_len check (char_length(gateway_payment_id) between 1 and 128),
    constraint ck_payment_gateway_refund_id_len check (char_length(gateway_refund_id) between 1 and 128),
    constraint ck_payment_session_url_len check (char_length(session_url) between 1 and 2048),
    constraint ck_payment_refund_reason check (refund_reason in ('late_payment', 'seller_api_timeout', 'dispute_decision')),
    -- INV-14: подтверждённый платёж известен шлюзу, время подтверждения записано
    constraint ck_payment_confirmed check (status not in ('confirmed', 'refunded') or (gateway_payment_id is not null and confirmed_at is not null)),
    constraint ck_payment_rejected check ((status = 'rejected') = (rejected_at is not null)),
    -- INV-15: возвращённый платёж знает причину и время возврата, возврат в обработке виден по внешнему идентификатору
    constraint ck_payment_refunded check ((status = 'refunded') = (refunded_at is not null) and (status <> 'refunded' or refund_reason is not null)),
    constraint ck_payment_gateway_refund_state check (gateway_refund_id is null or status in ('confirmed', 'refunded')),
    -- «в очереди администратора» бывает только у подтверждённого платежа с исчерпанными попытками возврата
    constraint ck_payment_admin_queue check (
        (in_admin_queue and status = 'confirmed' and refund_escalated_at is not null) or (not in_admin_queue)
    ),
    constraint ck_payment_version check (version >= 1)
);
comment on table payments.payment is 'Платёж по заказу и возврат по нему. Статус меняется только по ответу или уведомлению шлюза либо отметкой администратора (INV-14)';
comment on column payments.payment.id is 'PaymentID';
comment on column payments.payment.order_id is 'OrderID. Уникален: один платёж на заказ (INV-11). Чужая база, внешнего ключа нет';
comment on column payments.payment.gateway_payment_id is 'Внешний идентификатор платежа у шлюза, непрозрачная строка. Пуст, пока шлюз не ответил';
comment on column payments.payment.gateway_refund_id is 'Внешний идентификатор возврата, один на платёж (INV-15)';
comment on column payments.payment.refund_reason is 'Причина возврата: late_payment, seller_api_timeout (R2), dispute_decision (R2)';
comment on column payments.payment.amount is 'Сумма платежа в копейках, равна сумме заказа. Возврат всегда полный';
comment on column payments.payment.currency is 'Валюта, в R1 только RUB';
comment on column payments.payment.session_url is 'Адрес страницы оплаты у шлюза, ссылка без данных карты';
comment on column payments.payment.session_expires_at is 'Срок платёжной сессии (12 минут от создания, INV-07). Платёж остаётся created и после него: шлюз может подтвердить позже (поздняя оплата)';
comment on column payments.payment.in_admin_queue is 'Возврат после исчерпания попыток ждёт ручного исполнения администратором (FT-6.4)';
comment on column payments.payment.refund_escalated_at is 'Когда возврат попал в очередь администратора';
comment on column payments.payment.confirmed_at is 'Когда шлюз подтвердил платёж';
comment on column payments.payment.rejected_at is 'Когда шлюз сообщил об отказе';
comment on column payments.payment.refunded_at is 'Когда возврат подтверждён шлюзом или отмечен администратором';
comment on column payments.payment.status is 'SM-02: created, confirmed, rejected, refunded';
comment on column payments.payment.version is 'Версия строки, увеличивается при каждом изменении';
comment on column payments.payment.created_at is 'Дата создания платежа';
comment on column payments.payment.updated_at is 'Последнее изменение';
-- внешний идентификатор уникален, пока он есть
create unique index uq_payment_gateway_payment_id on payments.payment (gateway_payment_id) where gateway_payment_id is not null;
comment on index payments.uq_payment_gateway_payment_id is 'INV-14: один внешний платёж соответствует одному нашему. Поиск платежа по идентификатору из уведомления шлюза';
create unique index uq_payment_gateway_refund_id on payments.payment (gateway_refund_id) where gateway_refund_id is not null;
comment on index payments.uq_payment_gateway_refund_id is 'INV-15: один внешний возврат соответствует одному платежу. Поиск по идентификатору возврата из уведомления шлюза';
-- сверка со шлюзом
create index ix_payment_created_open on payments.payment (created_at) where status = 'created';
comment on index payments.ix_payment_created_open is 'Сверка (reconciler): платежи created моложе 2 часов раз в 5 минут, до 24 часов раз в час, старше 24 часов в ежедневный отчёт';
-- очередь ручных возвратов
create index ix_payment_manual_refund_queue on payments.payment (refund_escalated_at, id) where in_admin_queue;
comment on index payments.ix_payment_manual_refund_queue is 'GET /api/v1/staff/payments/manual-refunds (FT-6.4): очередь ручных возвратов, старые выше';

create trigger trg_payment_status_initial before insert on payments.payment
    for each row execute function public.enforce_status_transition('initial:created');
create trigger trg_payment_status_transition before update of status on payments.payment
    for each row when (old.status is distinct from new.status)
    execute function public.enforce_status_transition('created>confirmed', 'created>rejected', 'confirmed>refunded');

create function payments.payment_immutable() returns trigger
language plpgsql as
$fn$
begin
    if new.id is distinct from old.id or new.order_id is distinct from old.order_id
       or new.amount is distinct from old.amount or new.currency is distinct from old.currency
       or new.created_at is distinct from old.created_at then
        raise exception 'заказ, сумма и валюта платежа не меняются' using errcode = '23514', constraint = 'ck_payment_immutable';
    end if;
    -- внешние идентификаторы записываются один раз
    if old.gateway_payment_id is not null and new.gateway_payment_id is distinct from old.gateway_payment_id then
        raise exception 'внешний идентификатор платежа записывается один раз' using errcode = '23514', constraint = 'ck_payment_immutable';
    end if;
    if old.gateway_refund_id is not null and new.gateway_refund_id is distinct from old.gateway_refund_id then
        raise exception 'внешний идентификатор возврата записывается один раз' using errcode = '23514', constraint = 'ck_payment_immutable';
    end if;
    new.version := old.version + 1;
    new.updated_at := now();
    return new;
end
$fn$;
create trigger trg_payment_immutable before update on payments.payment
    for each row execute function payments.payment_immutable();

-- События шлюза: дедупликация по паре «внешний идентификатор платежа, тип события» (ADR-015)
create table payments.payment_event (
    gateway_payment_id text        not null,
    event_type         text        not null,
    gateway_event_id   text        not null,
    payment_id         uuid        not null,
    outcome            text        not null,
    occurred_at        timestamptz not null,
    received_at        timestamptz not null default now(),
    constraint pk_payment_event primary key (gateway_payment_id, event_type),
    constraint fk_payment_event_payment_id foreign key (payment_id) references payments.payment (id),
    constraint ck_payment_event_outcome check (outcome in ('applied', 'amount_mismatch', 'anomaly')),
    constraint ck_payment_event_event_type_len check (char_length(event_type) between 1 and 64),
    constraint ck_payment_event_ids_len check (char_length(gateway_payment_id) between 1 and 128 and char_length(gateway_event_id) between 1 and 128)
);
comment on table payments.payment_event is 'Обработанные уведомления шлюза. Повтор той же пары даёт нарушение первичного ключа: второй раз статус не меняется (INV-14, FT-6.2)';
comment on column payments.payment_event.gateway_payment_id is 'Внешний идентификатор платежа из уведомления';
comment on column payments.payment_event.event_type is 'Тип события шлюза: подтверждение, отказ, возврат (непрозрачная строка)';
comment on column payments.payment_event.gateway_event_id is 'Идентификатор уведомления у шлюза: для разбора, ключом дедупликации не служит';
comment on column payments.payment_event.payment_id is 'Платёж, к которому применено уведомление';
comment on column payments.payment_event.outcome is 'applied (статус изменён), amount_mismatch (сумма не совпала, статус не менялся), anomaly (подтверждение после отказа, разбор вручную)';
comment on column payments.payment_event.occurred_at is 'Время события по данным шлюза';
comment on column payments.payment_event.received_at is 'Когда уведомление принято';
create index ix_payment_event_payment_id on payments.payment_event (payment_id);
comment on index payments.ix_payment_event_payment_id is 'История уведомлений платежа для разбора администратором и проверка внешнего ключа';
create index ix_payment_event_received_at on payments.payment_event (received_at);
comment on index payments.ix_payment_event_received_at is 'Очистка записей старше срока хранения (14 суток, как processed_event)';

-- Попытки возврата с расписанием (ADR-015): одна строка на попытку, открытая попытка одна
create table payments.refund_attempt (
    id              uuid        not null,
    payment_id      uuid        not null,
    attempt_no      integer     not null,
    status          text        not null default 'pending',
    next_attempt_at timestamptz,
    error_code      text,
    created_at      timestamptz not null default now(),
    finished_at     timestamptz,
    constraint pk_refund_attempt primary key (id),
    constraint fk_refund_attempt_payment_id foreign key (payment_id) references payments.payment (id),
    constraint uq_refund_attempt_payment_id_attempt_no unique (payment_id, attempt_no),
    constraint ck_refund_attempt_attempt_no check (attempt_no between 1 and 5),
    constraint ck_refund_attempt_status check (status in ('pending', 'in_progress', 'processing', 'succeeded', 'failed')),
    constraint ck_refund_attempt_error_code check (error_code ~ '^[a-z0-9_.-]{1,64}$'),
    -- у открытой попытки есть срок, у завершённой есть время завершения
    constraint ck_refund_attempt_open check ((status in ('pending', 'in_progress')) = (next_attempt_at is not null)),
    constraint ck_refund_attempt_finished check ((status in ('succeeded', 'failed', 'processing')) = (finished_at is not null))
);
comment on table payments.refund_attempt is 'Попытки возврата по расписанию 0, 30 с, 2, 5, 10 минут (до 5 попыток). Исполнитель занимает попытку короткой транзакцией с арендой (FOR UPDATE SKIP LOCKED)';
comment on column payments.refund_attempt.id is 'Идентификатор попытки';
comment on column payments.refund_attempt.payment_id is 'Платёж, по которому выполняется возврат';
comment on column payments.refund_attempt.attempt_no is 'Номер попытки от 1 до 5. Сумма завершённых попыток это «число попыток возврата» из доменной модели';
comment on column payments.refund_attempt.status is 'pending (ждёт срока), in_progress (занята исполнителем, аренда), processing (шлюз принял, ждём подтверждение), succeeded, failed';
comment on column payments.refund_attempt.next_attempt_at is 'Когда попытку можно выполнить. У занятой попытки сдвигается на время аренды. Это «время следующей попытки возврата» из доменной модели';
comment on column payments.refund_attempt.error_code is 'Код ошибки шлюза: короткий идентификатор без свободного текста';
comment on column payments.refund_attempt.created_at is 'Когда попытка запланирована';
comment on column payments.refund_attempt.finished_at is 'Когда попытка завершена';
-- INV-15: возврат один, повторная команда второго не создаёт
create unique index uq_refund_attempt_payment_id_open on payments.refund_attempt (payment_id) where status in ('pending', 'in_progress');
comment on index payments.uq_refund_attempt_payment_id_open is 'Открытая попытка по платежу одна: повторная команда возврата не создаст вторую параллельную';
create unique index uq_refund_attempt_payment_id_succeeded on payments.refund_attempt (payment_id) where status in ('succeeded', 'processing');
comment on index payments.uq_refund_attempt_payment_id_succeeded is 'INV-15: у платежа не больше одной принятой шлюзом попытки возврата, второй возврат невозможен';
create index ix_refund_attempt_due on payments.refund_attempt (next_attempt_at) where status in ('pending', 'in_progress');
comment on index payments.ix_refund_attempt_due is 'Исполнитель раз в 5 секунд: попытки со сроком, который подошёл, FOR UPDATE SKIP LOCKED. Занятые, у которых аренда истекла, берутся повторно';

create function payments.refund_attempt_guard() returns trigger
language plpgsql as
$fn$
begin
    -- завершённая попытка не меняется
    if old.status in ('succeeded', 'failed') then
        raise exception 'завершённая попытка возврата не меняется' using errcode = '23514', constraint = 'ck_refund_attempt_final';
    end if;
    if new.id is distinct from old.id or new.payment_id is distinct from old.payment_id or new.attempt_no is distinct from old.attempt_no then
        raise exception 'платёж и номер попытки не меняются' using errcode = '23514', constraint = 'ck_refund_attempt_immutable';
    end if;
    return new;
end
$fn$;
create trigger trg_refund_attempt_guard before update on payments.refund_attempt
    for each row execute function payments.refund_attempt_guard();

-- Уведомления, которые не удалось сопоставить с платежом (сопоставляются планировщиком раз в 10 секунд)
create table payments.unmatched_notification (
    id                 uuid        not null,
    gateway_payment_id text        not null,
    event_type         text        not null,
    gateway_event_id   text        not null,
    order_id           uuid,
    occurred_at        timestamptz not null,
    received_at        timestamptz not null default now(),
    matched_at         timestamptz,
    alert_sent_at      timestamptz,
    constraint pk_unmatched_notification primary key (id),
    constraint uq_unmatched_notification_payment_event unique (gateway_payment_id, event_type),
    constraint ck_unmatched_notification_ids_len check (char_length(gateway_payment_id) between 1 and 128 and char_length(gateway_event_id) between 1 and 128),
    constraint ck_unmatched_notification_alert check (alert_sent_at is null or matched_at is null)
);
comment on table payments.unmatched_notification is 'Уведомления шлюза о неизвестных платежах. Тело уведомления не хранится: идентификаторы достаточно для сопоставления и разбора';
comment on column payments.unmatched_notification.id is 'Идентификатор записи';
comment on column payments.unmatched_notification.gateway_payment_id is 'Внешний идентификатор платежа из уведомления';
comment on column payments.unmatched_notification.event_type is 'Тип события шлюза';
comment on column payments.unmatched_notification.gateway_event_id is 'Идентификатор уведомления у шлюза';
comment on column payments.unmatched_notification.order_id is 'OrderID из метаданных уведомления, если шлюз его передал';
comment on column payments.unmatched_notification.occurred_at is 'Время события по данным шлюза';
comment on column payments.unmatched_notification.received_at is 'Когда уведомление принято';
comment on column payments.unmatched_notification.matched_at is 'Когда сопоставлено и применено';
comment on column payments.unmatched_notification.alert_sent_at is 'Когда отправлено оповещение (через час без сопоставления)';
create index ix_unmatched_notification_pending on payments.unmatched_notification (received_at) where matched_at is null;
comment on index payments.ix_unmatched_notification_pending is 'Планировщик раз в 10 секунд: несопоставленные уведомления, старые первыми. Оповещение через час: те же строки без alert_sent_at';
