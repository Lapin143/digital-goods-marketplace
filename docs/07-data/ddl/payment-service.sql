-- ============================================================================
-- payment-service: база payment_db (модуль payments)
-- Физическая модель данных R1, PostgreSQL 16. Файл собран из общего служебного блока и части сервиса.
-- Выполняется с нуля одним проходом: psql -v ON_ERROR_STOP=1 -d payment_db -f payment-service.sql
-- Нужны права создания схем, ролей и расширений. В проекте файл разбит на миграции Flyway по схемам (decomposition.md, правило 7),
-- здесь они сведены вместе, чтобы модель читалась и проверялась целиком.
-- Описание: docs/07-data/payment-service-schema.md
-- ============================================================================

-- ============================================================================
-- Служебные таблицы и функции (одинаковы во всех базах сервисов)
-- ============================================================================

-- Исходящие события (Transactional Outbox, ADR-005).
-- Пишет прикладной код в той же транзакции, что и изменение данных. Публикует один экземпляр сервиса
-- (консультативная блокировка pg_try_advisory_lock), порядок по id.
create table public.outbox (
    id             bigint generated always as identity,
    event_id       uuid        not null,
    aggregate_type text        not null,
    aggregate_id   text        not null,
    event_type     text        not null,
    topic          text        not null,
    payload        jsonb       not null,
    headers        jsonb       not null,
    created_at     timestamptz not null default now(),
    published_at   timestamptz,
    attempts       integer     not null default 0,
    failed_at      timestamptz,
    constraint pk_outbox primary key (id),
    constraint uq_outbox_event_id unique (event_id),
    constraint ck_outbox_attempts check (attempts >= 0),
    constraint ck_outbox_published_or_failed check (published_at is null or failed_at is null),
    constraint ck_outbox_payload_object check (jsonb_typeof(payload) = 'object')
);
comment on table public.outbox is 'Исходящие события. Запись в одной транзакции с изменением данных (ADR-005, INV-43)';
comment on column public.outbox.id is 'Порядковый номер: публикатор читает по возрастанию, порядок событий агрегата сохраняется';
comment on column public.outbox.event_id is 'Идентификатор события (UUIDv7), он же заголовок и ключ дедупликации у потребителя (ADR-006)';
comment on column public.outbox.aggregate_type is 'Тип агрегата: order, payment, key, product и другие';
comment on column public.outbox.aggregate_id is 'Идентификатор агрегата, он же ключ сообщения Kafka (порядок по заказу или товару)';
comment on column public.outbox.event_type is 'Тип события, например order.paid';
comment on column public.outbox.topic is 'Тема Kafka, в которую публикуется событие';
comment on column public.outbox.payload is 'Тело события по схеме AsyncAPI, без персональных данных и без значений ключей (INV-20)';
comment on column public.outbox.headers is 'Заголовки сообщения: версия схемы, correlation id, время события';
comment on column public.outbox.created_at is 'Время записи в одной транзакции с изменением данных';
comment on column public.outbox.published_at is 'Время подтверждённой публикации в Kafka, пусто пока не отправлено';
comment on column public.outbox.attempts is 'Число неудачных попыток публикации';
comment on column public.outbox.failed_at is 'Время парковки после исчерпания попыток, разбор вручную, пусто в норме';
-- опрос публикатора: пачка неотправленных по возрастанию id
create index ix_outbox_unpublished on public.outbox (id) where published_at is null and failed_at is null;
-- ежедневная очистка отправленных строк старше 3 суток
create index ix_outbox_published_at on public.outbox (published_at) where published_at is not null;
-- припаркованные строки: разбор вручную и оповещение
create index ix_outbox_failed_at on public.outbox (failed_at) where failed_at is not null;

-- Обработанные события потребителя (уровень 2 идемпотентности, ADR-006). Хранение 14 суток.
create table public.processed_event (
    consumer     text        not null,
    event_id     uuid        not null,
    processed_at timestamptz not null default now(),
    constraint pk_processed_event primary key (consumer, event_id)
);
comment on table public.processed_event is 'Обработанные события: повтор распознаётся по паре (потребитель, event_id), хранение 14 суток';
comment on column public.processed_event.consumer is 'Имя потребителя: сервис и обработчик, у каждого свой набор обработанных событий';
comment on column public.processed_event.event_id is 'Идентификатор обработанного события из заголовка';
comment on column public.processed_event.processed_at is 'Время обработки, по нему идёт очистка через 14 суток';
create index ix_processed_event_processed_at on public.processed_event (processed_at);

-- Ключи идемпотентности HTTP-запросов (уровень 1, ADR-006). Хранение 24 часа.
create table public.idempotency_key (
    scope         text        not null,
    key           uuid        not null,
    endpoint      text        not null,
    request_hash  bytea       not null,
    state         text        not null,
    response_code integer,
    response_body jsonb,
    created_at    timestamptz not null default now(),
    constraint pk_idempotency_key primary key (scope, key),
    constraint ck_idempotency_key_state check (state in ('in_progress', 'completed')),
    constraint ck_idempotency_key_request_hash check (octet_length(request_hash) = 32),
    constraint ck_idempotency_key_response check (
        (state = 'in_progress' and response_code is null and response_body is null)
        or (state = 'completed' and response_code between 100 and 599)
    )
);
comment on table public.idempotency_key is 'Ключи идемпотентности запросов, хранение 24 часа (ADR-006, уровень 1)';
comment on column public.idempotency_key.scope is 'Область ключа: пользователь и метод, у разных пользователей одинаковые ключи не пересекаются';
comment on column public.idempotency_key.key is 'Ключ из заголовка Idempotency-Key (UUID)';
comment on column public.idempotency_key.endpoint is 'Метод и путь первого запроса, повтор с другим адресом отклоняется';
comment on column public.idempotency_key.request_hash is 'SHA-256 тела первого запроса: повтор с другим телом даёт 422 вместо ответа из кэша';
comment on column public.idempotency_key.state is 'in_progress: запрос выполняется, completed: ответ сохранён';
comment on column public.idempotency_key.response_code is 'Код ответа первого запроса, пусто пока in_progress';
comment on column public.idempotency_key.response_body is 'Тело ответа первого запроса, повтор возвращает его без выполнения';
comment on column public.idempotency_key.created_at is 'Время первого запроса, очистка через 24 часа';
create index ix_idempotency_key_created_at on public.idempotency_key (created_at);

-- Контроль допустимых переходов статуса (диаграммы SM). Аргументы триггера: 'initial:<статус>' и '<из>><в>'.
-- Статус хранится в столбце status. Нарушение даёт check_violation с именем ck_<таблица>_status_transition.
create function public.enforce_status_transition() returns trigger
language plpgsql as
$fn$
declare
    rule text;
begin
    if tg_op = 'INSERT' then
        if ('initial:' || new.status) = any (tg_argv) then
            return new;
        end if;
        raise exception 'недопустимый начальный статус % в таблице %', new.status, tg_table_name
            using errcode = '23514', constraint = 'ck_' || tg_table_name || '_status_transition';
    end if;
    if old.status = new.status then
        return new;
    end if;
    if (old.status || '>' || new.status) = any (tg_argv) then
        return new;
    end if;
    raise exception 'недопустимый переход статуса % -> % в таблице %', old.status, new.status, tg_table_name
        using errcode = '23514', constraint = 'ck_' || tg_table_name || '_status_transition';
end
$fn$;

comment on function public.enforce_status_transition() is 'Допустимые переходы статуса по диаграммам SM. Аргументы триггера: initial:<статус> для вставки и <из>><в> для изменения. Нарушение даёт ошибку 23514 с ограничением ck_<таблица>_status_transition';

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
