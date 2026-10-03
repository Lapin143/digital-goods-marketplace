-- ============================================================================
-- delivery-service: база delivery_db (модуль delivery)
-- Физическая модель данных R1, PostgreSQL 16. Файл собран из общего служебного блока и части сервиса.
-- Выполняется с нуля одним проходом: psql -v ON_ERROR_STOP=1 -d delivery_db -f delivery-service.sql
-- Нужны права создания схем, ролей и расширений. В проекте файл разбит на миграции Flyway по схемам (decomposition.md, правило 7),
-- здесь они сведены вместе, чтобы модель читалась и проверялась целиком.
-- Описание: docs/07-data/delivery-service-schema.md
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
-- Схема delivery: выдача ключей письмом (SM-06). Значений ключей в таблицах нет и быть не может (INV-20)
-- ============================================================================
create schema delivery;
comment on schema delivery is 'Модуль delivery: выдачи, попытки отправки, контроль 30 минут, статусы писем провайдера. Значений ключей нет. Своя роль app_delivery';

create table delivery.delivery (
    id              uuid        not null,
    order_id        uuid        not null,
    buyer_id        uuid        not null,
    kind            text        not null,
    channel         text        not null default 'email',
    address         text        not null,
    product_title   text        not null,
    quantity        integer     not null,
    attempt_no      integer     not null default 0,
    next_attempt_at timestamptz,
    last_error_code text,
    fail_reason     text,
    sent_at         timestamptz,
    delivered_at    timestamptz,
    status          text        not null default 'queued',
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    constraint pk_delivery primary key (id),
    constraint ck_delivery_status check (status in ('queued', 'sent', 'delivered', 'failed')),
    constraint ck_delivery_kind check (kind in ('primary', 'repeat')),
    constraint ck_delivery_channel check (channel = 'email'),
    constraint ck_delivery_address_len check (char_length(address) between 3 and 254),
    constraint ck_delivery_product_title_len check (char_length(product_title) between 1 and 200),
    -- INV-19: в письме ровно столько ключей, сколько в заказе, поэтому количество известно выдаче
    constraint ck_delivery_quantity check (quantity between 1 and 10),
    constraint ck_delivery_attempt_no check (attempt_no between 0 and 6),
    constraint ck_delivery_last_error_code check (last_error_code ~ '^[a-z0-9_.-]{1,64}$'),
    constraint ck_delivery_fail_reason check (fail_reason in ('attempts_exhausted', 'address_rejected')),
    -- в очереди есть срок следующей попытки, у остальных его нет
    constraint ck_delivery_next_attempt check ((status = 'queued') = (next_attempt_at is not null)),
    constraint ck_delivery_sent_at check (status not in ('sent', 'delivered') or sent_at is not null),
    constraint ck_delivery_delivered_at check ((status = 'delivered') = (delivered_at is not null)),
    constraint ck_delivery_fail_state check ((status = 'failed') = (fail_reason is not null))
);
comment on table delivery.delivery is 'Одна доставка ключей по заказу: письмо на адрес из снимка. Ключи не хранятся, значения берутся у inventory-service в момент отправки (INV-20)';
comment on column delivery.delivery.id is 'DeliveryID. Он же идентификатор сообщения у e-mail-провайдера (ADR-006)';
comment on column delivery.delivery.order_id is 'OrderID. У заказа несколько выдач, первичная одна (INV-18). Чужая база, внешнего ключа нет';
comment on column delivery.delivery.buyer_id is 'BuyerID из order.paid: по нему анонимизация находит выдачи пользователя (INV-44)';
comment on column delivery.delivery.kind is 'primary (первичная, одна на заказ) или repeat (повторная по обращению)';
comment on column delivery.delivery.channel is 'Канал выдачи, в R1 только email';
comment on column delivery.delivery.address is 'Снимок адреса доставки из заказа на момент создания выдачи (персональные данные, INV-44)';
comment on column delivery.delivery.product_title is 'Название товара для письма. Приходит в order.paid (находка F12-6), ключей не содержит';
comment on column delivery.delivery.quantity is 'Сколько ключей должно быть в письме: сверка с ответом inventory-service (INV-19)';
comment on column delivery.delivery.attempt_no is 'Число выполненных попыток отправки, от 0 до 6. Автоповторы меняют только его';
comment on column delivery.delivery.next_attempt_at is 'Срок следующей попытки. У занятой выдачи сдвигается на аренду 2 минуты, чтобы упавший процесс не терял выдачу';
comment on column delivery.delivery.last_error_code is 'Код последней ошибки отправки: короткий идентификатор без свободного текста, значения ключей сюда попасть не могут';
comment on column delivery.delivery.fail_reason is 'attempts_exhausted или address_rejected, только у статуса failed';
comment on column delivery.delivery.sent_at is 'Когда провайдер принял письмо';
comment on column delivery.delivery.delivered_at is 'Когда провайдер подтвердил доставку (метрика BG-01)';
comment on column delivery.delivery.status is 'SM-06: queued, sent, delivered, failed';
comment on column delivery.delivery.created_at is 'Когда выдача создана';
comment on column delivery.delivery.updated_at is 'Последнее изменение';
-- INV-18: первичная выдача одна на заказ
create unique index uq_delivery_order_id_primary on delivery.delivery (order_id) where kind = 'primary';
comment on index delivery.uq_delivery_order_id_primary is 'INV-18: вторая первичная выдача заказа невозможна, повторное order.paid её не создаст';
-- одна выдача в очереди на заказ
create unique index uq_delivery_order_id_queued on delivery.delivery (order_id) where status = 'queued';
comment on index delivery.uq_delivery_order_id_queued is 'Не больше одной выдачи в очереди на заказ (ADR-011): повторная отправка не накладывается на ещё не отправленную';
-- диспетчер
create index ix_delivery_dispatch on delivery.delivery (next_attempt_at, id) where status = 'queued';
comment on index delivery.ix_delivery_dispatch is 'Диспетчер раз в секунду: WHERE status = ''queued'' AND next_attempt_at <= now() ORDER BY next_attempt_at LIMIT 20 FOR UPDATE SKIP LOCKED';
-- опрос провайдера
create index ix_delivery_sent_poll on delivery.delivery (sent_at) where status = 'sent';
comment on index delivery.ix_delivery_sent_poll is 'Опрос статусов раз в 5 минут: выдачи sent старше 5 минут без статуса от провайдера';
create index ix_delivery_order_id on delivery.delivery (order_id, created_at);
comment on index delivery.ix_delivery_order_id is 'Выдачи заказа: приём order.address-updated, статус для поддержки, поиск по сообщению провайдера';
create index ix_delivery_buyer_id on delivery.delivery (buyer_id);
comment on index delivery.ix_delivery_buyer_id is 'user.anonymized: замена адресов выдач покупателя служебным значением (INV-44)';

create trigger trg_delivery_status_initial before insert on delivery.delivery
    for each row execute function public.enforce_status_transition('initial:queued');
create trigger trg_delivery_status_transition before update of status on delivery.delivery
    for each row when (old.status is distinct from new.status)
    execute function public.enforce_status_transition('queued>sent', 'queued>failed', 'sent>delivered', 'sent>failed');

create function delivery.delivery_immutable() returns trigger
language plpgsql as
$fn$
begin
    if new.id is distinct from old.id or new.order_id is distinct from old.order_id or new.buyer_id is distinct from old.buyer_id
       or new.kind is distinct from old.kind or new.quantity is distinct from old.quantity
       or new.product_title is distinct from old.product_title or new.created_at is distinct from old.created_at then
        raise exception 'заказ, покупатель, тип и количество выдачи не меняются' using errcode = '23514', constraint = 'ck_delivery_immutable';
    end if;
    new.updated_at := now();
    return new;
end
$fn$;
create trigger trg_delivery_immutable before update on delivery.delivery
    for each row execute function delivery.delivery_immutable();

-- История попыток: время, результат, код ошибки. Ни значений ключей, ни тела письма
create table delivery.delivery_attempt (
    delivery_id uuid        not null,
    attempt_no  integer     not null,
    outcome     text        not null,
    error_code  text,
    started_at  timestamptz not null,
    finished_at timestamptz not null,
    constraint pk_delivery_attempt primary key (delivery_id, attempt_no),
    constraint fk_delivery_attempt_delivery_id foreign key (delivery_id) references delivery.delivery (id),
    constraint ck_delivery_attempt_attempt_no check (attempt_no between 1 and 6),
    constraint ck_delivery_attempt_outcome check (outcome in ('accepted', 'retryable_error', 'permanent_error')),
    -- код ошибки по форме не может вместить значение ключа или текст письма
    constraint ck_delivery_attempt_error_code check (error_code ~ '^[a-z0-9_.-]{1,64}$'),
    constraint ck_delivery_attempt_outcome_error check ((outcome = 'accepted') = (error_code is null)),
    constraint ck_delivery_attempt_time check (finished_at >= started_at)
);
comment on table delivery.delivery_attempt is 'Попытки отправки письма: результат и код ошибки. Значений ключей и тела письма нет (INV-20)';
comment on column delivery.delivery_attempt.delivery_id is 'Выдача';
comment on column delivery.delivery_attempt.attempt_no is 'Номер попытки от 1 до 6 (расписание 10 с, 30 с, 2, 5, 10 минут)';
comment on column delivery.delivery_attempt.outcome is 'accepted (провайдер принял), retryable_error (тайм-аут, 5xx, 429), permanent_error (отказ по адресу, 400, 422)';
comment on column delivery.delivery_attempt.error_code is 'Короткий код ошибки провайдера или проверки (например key_count_mismatch), без свободного текста';
comment on column delivery.delivery_attempt.started_at is 'Начало попытки';
comment on column delivery.delivery_attempt.finished_at is 'Конец попытки';

-- Контроль 30 минут от оплаты (E11, NFT-2.4): одна запись на заказ
create table delivery.delivery_watch (
    order_id       uuid        not null,
    deadline_at    timestamptz not null,
    status         text        not null default 'open',
    created_at     timestamptz not null default now(),
    handed_over_at timestamptz,
    closed_at      timestamptz,
    constraint pk_delivery_watch primary key (order_id),
    constraint ck_delivery_watch_status check (status in ('open', 'handed_over', 'closed')),
    constraint ck_delivery_watch_handed_over check (status <> 'handed_over' or handed_over_at is not null),
    constraint ck_delivery_watch_handed_over_state check (status in ('handed_over', 'closed') or handed_over_at is null),
    constraint ck_delivery_watch_closed check ((status = 'closed') = (closed_at is not null))
);
comment on table delivery.delivery_watch is 'Контроль доставки: срок равен paid_at плюс 30 минут. Повторная выдача новой записи не создаёт, окна считаются от первичной';
comment on column delivery.delivery_watch.order_id is 'OrderID, один контроль на заказ';
comment on column delivery.delivery_watch.deadline_at is 'paid_at плюс окно контроля (по умолчанию 30 минут)';
comment on column delivery.delivery_watch.status is 'open (ждём доставку), handed_over (передан в поддержку, событие delivery.overdue), closed';
comment on column delivery.delivery_watch.created_at is 'Когда контроль открыт (по order.paid)';
comment on column delivery.delivery_watch.handed_over_at is 'Когда передан в поддержку';
comment on column delivery.delivery_watch.closed_at is 'Когда закрыт доставкой';
create index ix_delivery_watch_due on delivery.delivery_watch (deadline_at) where status = 'open';
comment on index delivery.ix_delivery_watch_due is 'Задание контроля раз в 30 секунд: WHERE status = ''open'' AND deadline_at <= now() FOR UPDATE SKIP LOCKED';

create trigger trg_delivery_watch_status_initial before insert on delivery.delivery_watch
    for each row execute function public.enforce_status_transition('initial:open');
create trigger trg_delivery_watch_status_transition before update of status on delivery.delivery_watch
    for each row when (old.status is distinct from new.status)
    execute function public.enforce_status_transition('open>handed_over', 'open>closed', 'handed_over>closed');

-- Статусы писем от провайдера: дедупликация по паре «идентификатор сообщения, статус»
create table delivery.provider_status_event (
    delivery_id     uuid        not null,
    provider_status text        not null,
    occurred_at     timestamptz not null,
    received_at     timestamptz not null default now(),
    constraint pk_provider_status_event primary key (delivery_id, provider_status),
    constraint fk_provider_status_event_delivery_id foreign key (delivery_id) references delivery.delivery (id),
    constraint ck_provider_status_event_status_len check (char_length(provider_status) between 1 and 64)
);
comment on table delivery.provider_status_event is 'Принятые статусы писем провайдера. Идентификатор сообщения у провайдера равен DeliveryID. Повтор пары не меняет ничего (ADR-006)';
comment on column delivery.provider_status_event.delivery_id is 'Выдача, она же сообщение у провайдера';
comment on column delivery.provider_status_event.provider_status is 'Статус от провайдера: доставлено или отказ (непрозрачная строка)';
comment on column delivery.provider_status_event.occurred_at is 'Время события по данным провайдера';
comment on column delivery.provider_status_event.received_at is 'Когда статус принят';
create index ix_provider_status_event_received_at on delivery.provider_status_event (received_at);
comment on index delivery.ix_provider_status_event_received_at is 'Очистка записей старше срока хранения (14 суток)';

-- ============================================================================
-- Роль и права
-- ============================================================================
do $$
begin
    if not exists (select from pg_roles where rolname = 'app_delivery') then
        create role app_delivery nologin;
    end if;
end
$$;
grant usage on schema delivery to app_delivery;
grant select, insert, update on delivery.delivery, delivery.delivery_watch to app_delivery;
grant select, insert on delivery.delivery_attempt to app_delivery;
grant select, insert, delete on delivery.provider_status_event to app_delivery;
grant usage on schema public to app_delivery;
grant select, insert, update, delete on public.outbox, public.processed_event, public.idempotency_key to app_delivery;
grant usage on all sequences in schema public to app_delivery;

comment on function delivery.delivery_immutable() is 'Заказ, покупатель, тип, количество и название товара выдачи неизменны, время изменения обновляется';
