-- СОЗДАН СКРИПТОМ tools/docs-checks/db/gen_migrations.py ИЗ tools/docs-checks/db/parts/infra.sql. РУКАМИ НЕ ПРАВИТЬ.
-- Сервис payment-service, база payment_db. Миграция V1, служебные таблицы и функция контроля статусов.
-- Применённую миграцию править нельзя: Flyway сверяет контрольную сумму, правка вызовет ошибку проверки при старте.
-- Полная модель одним файлом: docs/07-data/ddl/payment-service.sql

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
