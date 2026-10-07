-- ============================================================================
-- platform-service: база platform_db (модули identity, support, notification, audit_admin)
-- Физическая модель данных R1, PostgreSQL 16. Файл собран из общего служебного блока и части сервиса.
-- Выполняется с нуля одним проходом: psql -v ON_ERROR_STOP=1 -d platform_db -f platform-service.sql
-- Нужны права создания схем, ролей и расширений. В проекте файл разбит на миграции Flyway по схемам (decomposition.md, правило 7),
-- здесь они сведены вместе, чтобы модель читалась и проверялась целиком.
-- Описание: docs/07-data/platform-service-schema.md
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
-- Схема identity: расширение учётной записи (Keycloak хранит пароли и сессии, здесь телефон, роль, соль аудита)
-- ============================================================================
create schema identity;
comment on schema identity is 'Модуль identity: учётные записи платформы. Единственное место, где персональные данные хранятся как основные (раздел 4.1 доменной модели). Своя роль app_identity';

create table identity.user_account (
    id                   uuid        not null,
    email                text        not null,
    previous_email       text,
    previous_email_until timestamptz,
    phone                text,
    phone_confirmed      boolean     not null default false,
    phone_synced_to_idp  boolean     not null default false,
    name                 text        not null,
    role                 text        not null default 'buyer',
    has_2fa              boolean     not null default false,
    audit_salt           bytea,
    anonymized           boolean     not null default false,
    status               text        not null default 'active',
    registered_at        timestamptz not null default now(),
    deactivated_at       timestamptz,
    version              integer     not null default 1,
    updated_at           timestamptz not null default now(),
    constraint pk_user_account primary key (id),
    -- INV-34: e-mail уникален (в нормализованном виде), телефон уникален, пока он есть
    constraint uq_user_account_email unique (email),
    constraint ck_user_account_status check (status in ('active', 'deactivated')),
    constraint ck_user_account_role check (role in ('buyer', 'seller', 'moderator', 'support-operator', 'admin')),
    constraint ck_user_account_email check (email = lower(btrim(email)) and char_length(email) between 3 and 254 and position('@' in email) > 1),
    constraint ck_user_account_previous_email check (
        (previous_email is null) = (previous_email_until is null)
        and (previous_email is null or (previous_email = lower(btrim(previous_email)) and char_length(previous_email) between 3 and 254))
    ),
    constraint ck_user_account_phone check (phone ~ '^\+[1-9][0-9]{6,14}$'),
    constraint ck_user_account_phone_confirmed check (not phone_confirmed or phone is not null),
    constraint ck_user_account_phone_synced check (not phone_synced_to_idp or phone_confirmed),
    constraint ck_user_account_name_len check (char_length(name) between 1 and 200),
    constraint ck_user_account_audit_salt check (audit_salt is null or octet_length(audit_salt) = 16),
    constraint ck_user_account_salt_state check (anonymized or audit_salt is not null),
    constraint ck_user_account_deactivated check ((status = 'deactivated') = (deactivated_at is not null)),
    -- INV-44: анонимизированная запись не содержит персональных значений и соли аудита
    constraint ck_user_account_anonymized check (
        not anonymized
        or (status = 'deactivated' and phone is null and audit_salt is null and previous_email is null
            and email ~ '^anonymized-[0-9a-f-]{36}@invalid$' and name = 'Удалён')
    ),
    constraint ck_user_account_version check (version >= 1)
);
comment on table identity.user_account is 'Учётная запись платформы. Идентификатор равен sub из Keycloak (conventions 3.3). Деактивация сохраняет запись, анонимизация убирает персональные значения и соль аудита';
comment on column identity.user_account.id is 'UserID, он же BuyerID и ActorID в других контекстах. Создаётся Keycloak (UUID 4)';
comment on column identity.user_account.email is 'E-mail в нормализованном виде (нижний регистр, без пробелов по краям, до 254 символов). После анонимизации служебное значение anonymized-<id>@invalid';
comment on column identity.user_account.previous_email is 'Прежний адрес после смены e-mail: нужен, чтобы отправить письмо о смене и на него. Очищается через 7 суток';
comment on column identity.user_account.previous_email_until is 'Когда прежний адрес нужно очистить: смена плюс 7 суток';
comment on column identity.user_account.phone is 'Номер в формате E.164. Пуст, пока не подтверждён, и после анонимизации. Уникален (FT-1.5)';
comment on column identity.user_account.phone_confirmed is 'Признак подтверждения телефона: без него первый заказ не оформляется (INV-35)';
comment on column identity.user_account.phone_synced_to_idp is 'Признак «передан в Keycloak»: атрибут phone_verified для токена. Сверка раз в минуту повторяет передачу, если false (ADR-010)';
comment on column identity.user_account.name is 'Отображаемое имя. После анонимизации «Удалён»';
comment on column identity.user_account.role is 'Роль: buyer, seller, moderator, support-operator, admin. «Гость» не хранится. Роль seller появляется только по событию seller.approved (INV-36)';
comment on column identity.user_account.has_2fa is 'Признак включённой 2FA. Для ролей кроме buyer обязателен для входа в кабинет (INV-37), контроль на стороне Keycloak и кода: роль можно назначить до настройки второго фактора';
comment on column identity.user_account.audit_salt is 'Соль пользователя для ключа хеширования аудита, 16 байт случайных. Удаляется при анонимизации: хеши в журнале становятся несопоставимыми (ADR-014)';
comment on column identity.user_account.anonymized is 'Персональные данные заменены обезличенными значениями (FT-1.4, INV-44)';
comment on column identity.user_account.status is 'active или deactivated. Значение «заблокирован» из раздела 6.1 требований не используется (решение 3 доменной модели)';
comment on column identity.user_account.registered_at is 'Дата регистрации';
comment on column identity.user_account.deactivated_at is 'Дата деактивации';
comment on column identity.user_account.version is 'Версия строки, увеличивается при каждом изменении';
comment on column identity.user_account.updated_at is 'Последнее изменение';
create unique index uq_user_account_phone on identity.user_account (phone) where phone is not null;
comment on index identity.uq_user_account_phone is 'INV-34: один номер привязан только к одной учётной записи (FT-1.5), проверка идёт индексом базы и закрывает гонку двух подтверждений';
create index ix_user_account_role on identity.user_account (role, registered_at desc, id desc);
comment on index identity.ix_user_account_role is 'GET /api/v1/staff/users?role= (US-8.1): новые выше, фильтр status накладывается поверх, роль «покупатель» не читает весь список';
create index ix_user_account_registered_at on identity.user_account (registered_at desc, id desc);
comment on index identity.ix_user_account_registered_at is 'GET /api/v1/staff/users (US-8.1) без фильтров и только со status: новые выше, курсор по (registered_at, id). Поиск по точному e-mail идёт уникальным индексом uq_user_account_email';
create index ix_user_account_phone_unsynced on identity.user_account (id) where phone_confirmed and not phone_synced_to_idp;
comment on index identity.ix_user_account_phone_unsynced is 'Сверка identity-reconciler раз в минуту: подтверждённые телефоны, не переданные в Keycloak';
create index ix_user_account_previous_email_until on identity.user_account (previous_email_until) where previous_email is not null;
comment on index identity.ix_user_account_previous_email_until is 'Очистка прежних адресов e-mail через 7 суток после смены (identity-reconciler)';

create trigger trg_user_account_status_initial before insert on identity.user_account
    for each row execute function public.enforce_status_transition('initial:active');
-- в R1 учётную запись можно только деактивировать, возврата нет (US-8.2)
create trigger trg_user_account_status_transition before update of status on identity.user_account
    for each row when (old.status is distinct from new.status)
    execute function public.enforce_status_transition('active>deactivated');

-- INV-38: в системе всегда есть хотя бы один активный администратор. Консультативная блокировка
-- сериализует одновременные попытки двух администраторов убрать друг друга
create function identity.keep_last_admin() returns trigger
language plpgsql as
$fn$
begin
    if old.role = 'admin' and old.status = 'active'
       and (tg_op = 'DELETE' or new.role <> 'admin' or new.status <> 'active') then
        perform pg_advisory_xact_lock(hashtext('identity.keep_last_admin'));
        if not exists (select 1 from identity.user_account where role = 'admin' and status = 'active' and id <> old.id) then
            raise exception 'нельзя убрать или деактивировать последнего активного администратора'
                using errcode = '23514', constraint = 'ck_user_account_last_admin';
        end if;
    end if;
    if tg_op = 'DELETE' then
        return old;
    end if;
    return new;
end
$fn$;
create trigger trg_user_account_last_admin before update of role, status or delete on identity.user_account
    for each row execute function identity.keep_last_admin();

create function identity.user_account_touch() returns trigger
language plpgsql as
$fn$
begin
    if new.id is distinct from old.id or new.registered_at is distinct from old.registered_at then
        raise exception 'идентификатор и дата регистрации не меняются' using errcode = '23514', constraint = 'ck_user_account_immutable';
    end if;
    -- анонимизация необратима
    if old.anonymized and not new.anonymized then
        raise exception 'анонимизация необратима' using errcode = '23514', constraint = 'ck_user_account_immutable';
    end if;
    new.version := old.version + 1;
    new.updated_at := now();
    return new;
end
$fn$;
create trigger trg_user_account_touch before update on identity.user_account
    for each row execute function identity.user_account_touch();

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
    order_number        bigint      not null,
    buyer_id            uuid        not null,
    order_status        text        not null,
    issued_at           timestamptz,
    delivery_status     text,
    delivery_updated_at timestamptz,
    updated_at          timestamptz not null default now(),
    constraint pk_order_view primary key (order_id),
    constraint uq_order_view_order_number unique (order_number),
    constraint ck_order_view_order_number check (order_number >= 1),
    constraint ck_order_view_order_status check (order_status in ('created', 'awaiting_payment', 'paid', 'issued', 'cancelled', 'refunded')),
    constraint ck_order_view_delivery_status check (delivery_status in ('queued', 'sent', 'delivered', 'failed'))
);
comment on table support.order_view is 'Копия данных заказа для поддержки: номер заказа, покупатель, время первичной выдачи (окно 72 часа, INV-22) и статус выдачи. Источник истины: order-service и delivery-service';
comment on column support.order_view.order_id is 'OrderID';
comment on column support.order_view.order_number is 'Короткий номер заказа из события order.issued (FT-5.0): по нему оператор находит обращение, не вводя UUID. Уникален, как в order-service';
comment on column support.order_view.buyer_id is 'BuyerID: обращение создаёт только покупатель заказа';
comment on column support.order_view.order_status is 'Статус заказа по SM-01 из последнего события';
comment on column support.order_view.issued_at is 'Время первичной выдачи из order.issued: от него считается окно 72 часа';
comment on column support.order_view.delivery_status is 'Статус выдачи по SM-06, который видит оператор';
comment on column support.order_view.delivery_updated_at is 'Время последнего применённого события выдачи: более раннее событие игнорируется';
comment on column support.order_view.updated_at is 'Когда запись обновлена';

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

-- ============================================================================
-- Схема audit_admin: параметры платформы и неизменяемый журнал аудита
-- ============================================================================
create schema audit_admin;
comment on schema audit_admin is 'Модуль audit_admin: параметры платформы и журнал аудита. Журнал только добавляется. Роли app_audit_admin (параметры) и app_audit_writer (журнал: только INSERT и SELECT)';

create table audit_admin.platform_parameter (
    key           text        not null,
    description   text        not null,
    unit          text        not null,
    value         bigint      not null,
    default_value bigint      not null,
    min_value     bigint      not null,
    max_value     bigint      not null,
    version       integer     not null default 1,
    updated_at    timestamptz not null default now(),
    updated_by    uuid,
    constraint pk_platform_parameter primary key (key),
    constraint ck_platform_parameter_key check (key ~ '^[a-z0-9]+([.-][a-z0-9]+)*$' and char_length(key) <= 128),
    constraint ck_platform_parameter_unit check (unit in ('seconds', 'count', 'basis_points', 'kopecks', 'text', 'flag')),
    -- значения только положительные целые в допустимых границах (US-8.3)
    constraint ck_platform_parameter_bounds check (min_value >= 1 and min_value <= max_value),
    constraint ck_platform_parameter_value check (value between min_value and max_value),
    constraint ck_platform_parameter_default check (default_value between min_value and max_value),
    constraint ck_platform_parameter_version check (version >= 1)
);
comment on table audit_admin.platform_parameter is 'Параметры платформы, которые администратор меняет без выпуска версии (US-8.3). Раздаются событием config.changed в сжатую тему platform.config';
comment on column audit_admin.platform_parameter.key is 'Ключ параметра, совпадает с ключом записи Kafka platform.config, например reservation.ttl-seconds';
comment on column audit_admin.platform_parameter.description is 'Назначение параметра';
comment on column audit_admin.platform_parameter.unit is 'Единица значения: seconds, count, basis_points, kopecks, text, flag';
comment on column audit_admin.platform_parameter.value is 'Текущее значение, целое положительное в границах параметра';
comment on column audit_admin.platform_parameter.default_value is 'Значение по умолчанию';
comment on column audit_admin.platform_parameter.min_value is 'Нижняя граница, не меньше 1';
comment on column audit_admin.platform_parameter.max_value is 'Верхняя граница';
comment on column audit_admin.platform_parameter.version is 'Номер версии, он же ETag при правке (If-Match)';
comment on column audit_admin.platform_parameter.updated_at is 'Когда изменён';
comment on column audit_admin.platform_parameter.updated_by is 'ActorID администратора, пусто у начального значения';

create function audit_admin.platform_parameter_guard() returns trigger
language plpgsql as
$fn$
begin
    if tg_op = 'UPDATE' then
        if new.key is distinct from old.key then
            raise exception 'ключ параметра не меняется' using errcode = '23514', constraint = 'ck_platform_parameter_immutable';
        end if;
        new.version := old.version + 1;
        new.updated_at := now();
    end if;
    return new;
end
$fn$;
create trigger trg_platform_parameter_guard before insert or update on audit_admin.platform_parameter
    for each row execute function audit_admin.platform_parameter_guard();

-- INV-07: срок платёжной сессии строго короче срока резерва. Блокировка сериализует одновременные правки двух параметров
create function audit_admin.check_session_shorter_than_reserve() returns trigger
language plpgsql as
$fn$
declare
    session_ttl bigint;
    reserve_ttl bigint;
begin
    if new.key in ('payment-session.ttl-seconds', 'reservation.ttl-seconds') then
        perform pg_advisory_xact_lock(hashtext('platform_parameter.session_vs_reserve'));
        select value into session_ttl from audit_admin.platform_parameter where key = 'payment-session.ttl-seconds';
        select value into reserve_ttl from audit_admin.platform_parameter where key = 'reservation.ttl-seconds';
        if session_ttl is not null and reserve_ttl is not null and session_ttl >= reserve_ttl then
            raise exception 'срок платёжной сессии (%) должен быть строго короче срока резерва (%)', session_ttl, reserve_ttl
                using errcode = '23514', constraint = 'ck_platform_parameter_session_vs_reserve';
        end if;
    end if;
    return null;
end
$fn$;
create trigger trg_platform_parameter_session_vs_reserve after insert or update of value on audit_admin.platform_parameter
    for each row execute function audit_admin.check_session_shorter_than_reserve();

-- Проверка формы изменений аудита (INV-42): имя поля, признак персональности, значения. Персональные значения только HMAC-хеш
create function audit_admin.audit_changes_valid(changes jsonb) returns boolean
language sql immutable parallel safe as
$fn$
    select jsonb_typeof(changes) = 'array'
       and coalesce(bool_and(
               jsonb_typeof(e) = 'object'
               and (select array_agg(k order by k) from jsonb_object_keys(e) k) = array['after', 'before', 'field', 'personal']
               and jsonb_typeof(e -> 'field') = 'string' and char_length(e ->> 'field') between 1 and 64
               and jsonb_typeof(e -> 'personal') = 'boolean'
               and jsonb_typeof(e -> 'before') in ('string', 'null')
               and jsonb_typeof(e -> 'after') in ('string', 'null')
               and (not (e ->> 'personal')::boolean
                    or ((e ->> 'before') is null or (e ->> 'before') ~ '^[0-9a-f]{64}$')
                       and ((e ->> 'after') is null or (e ->> 'after') ~ '^[0-9a-f]{64}$'))
           ), true)
    from jsonb_array_elements(case when jsonb_typeof(changes) = 'array' then changes else '[]'::jsonb end) e
$fn$;

create table audit_admin.audit_log (
    id            uuid        not null,
    event_id      uuid        not null,
    occurred_at   timestamptz not null,
    actor_id      uuid        not null,
    actor_role    text        not null,
    action        text        not null,
    object_type   text        not null,
    object_id     text        not null,
    changes       jsonb       not null default '[]',
    actor_ip      text,
    actor_ip_hashed boolean   not null default false,
    correlation_id uuid,
    recorded_at   timestamptz not null default now(),
    constraint pk_audit_log primary key (id, occurred_at),
    -- повторная доставка события не создаёт вторую запись: occurred_at берётся из события, поэтому пара стабильна
    constraint uq_audit_log_event_id unique (event_id, occurred_at),
    constraint ck_audit_log_actor_role check (actor_role in ('buyer', 'seller', 'moderator', 'support-operator', 'admin', 'system')),
    constraint ck_audit_log_action check (action ~ '^[a-z0-9_.-]{1,128}$'),
    constraint ck_audit_log_object_type check (object_type ~ '^[a-z0-9_.-]{1,64}$'),
    constraint ck_audit_log_object_id_len check (char_length(object_id) between 1 and 128),
    constraint ck_audit_log_changes check (audit_admin.audit_changes_valid(changes)),
    constraint ck_audit_log_actor_ip check (
        actor_ip is null or (char_length(actor_ip) <= 128 and (not actor_ip_hashed or actor_ip ~ '^[0-9a-f]{64}$'))
    )
) partition by range (occurred_at);
comment on table audit_admin.audit_log is 'Журнал аудита: только добавление. Персональные поля только HMAC-хешем (INV-42). Секции по месяцам, без удаления, срок хранения не менее 3 лет';
comment on column audit_admin.audit_log.id is 'RecordID (UUID 7). Идентификатор по времени, а не последовательный номер (ADR-014 уточнён, F12-3)';
comment on column audit_admin.audit_log.event_id is 'Идентификатор события audit.recorded: ключ защиты от дубля вместе с occurred_at';
comment on column audit_admin.audit_log.occurred_at is 'Время действия из события. Ключ секционирования';
comment on column audit_admin.audit_log.actor_id is 'ActorID: кто выполнил действие (идентификатор, не имя)';
comment on column audit_admin.audit_log.actor_role is 'Роль исполнителя на момент действия: buyer, seller, moderator, support-operator, admin, system';
comment on column audit_admin.audit_log.action is 'Код действия, например product.rejected, parameter.changed';
comment on column audit_admin.audit_log.object_type is 'Тип объекта действия';
comment on column audit_admin.audit_log.object_id is 'Идентификатор объекта строкой: объектом бывает и параметр с ключом вида reservation.ttl-seconds (F11-8)';
comment on column audit_admin.audit_log.changes is 'Массив {field, personal, before, after}. Для персональных полей before и after только 64 hex-символа HMAC или null';
comment on column audit_admin.audit_log.actor_ip is 'IP исполнителя: у сотрудника как есть, у обычного пользователя HMAC-хеш';
comment on column audit_admin.audit_log.actor_ip_hashed is 'true, если actor_ip хеш';
comment on column audit_admin.audit_log.correlation_id is 'Сквозной идентификатор действия (NFT-6.0)';
comment on column audit_admin.audit_log.recorded_at is 'Когда запись вставлена в журнал';
create index ix_audit_log_occurred_at on audit_admin.audit_log (occurred_at desc, id desc);
comment on index audit_admin.ix_audit_log_occurred_at is 'GET /api/v1/staff/audit-records (US-8.7) без фильтров и по периоду: новые выше, курсор по (occurred_at, id)';
create index ix_audit_log_actor_id on audit_admin.audit_log (actor_id, occurred_at desc, id desc);
comment on index audit_admin.ix_audit_log_actor_id is 'Фильтр actorId журнала аудита: действия одного сотрудника, новые выше';
create index ix_audit_log_object on audit_admin.audit_log (object_type, object_id, occurred_at desc, id desc);
comment on index audit_admin.ix_audit_log_object is 'Фильтр objectType и objectId журнала: история одного объекта';
create index ix_audit_log_action on audit_admin.audit_log (action, occurred_at desc, id desc);
comment on index audit_admin.ix_audit_log_action is 'Фильтр action журнала: все действия одного вида';

-- запрет изменения: триггеры на UPDATE, DELETE (строчные, клонируются в секции) и TRUNCATE (на каждой секции)
create function audit_admin.audit_log_deny() returns trigger
language plpgsql as
$fn$
begin
    raise exception 'журнал аудита только добавляется (INV-42): % запрещён', tg_op
        using errcode = '23001', constraint = 'ck_audit_log_append_only';
end
$fn$;
create trigger trg_audit_log_no_update before update on audit_admin.audit_log
    for each row execute function audit_admin.audit_log_deny();
create trigger trg_audit_log_no_delete before delete on audit_admin.audit_log
    for each row execute function audit_admin.audit_log_deny();
create trigger trg_audit_log_no_truncate before truncate on audit_admin.audit_log
    for each statement execute function audit_admin.audit_log_deny();

-- Месячные секции: функция создаёт секцию и вешает на неё запрет TRUNCATE (триггер уровня оператора не наследуется)
create function audit_admin.create_audit_log_partition(month_start date) returns text
language plpgsql as
$fn$
declare
    part_name text := 'audit_log_' || to_char(month_start, 'YYYY_MM');
    from_ts   timestamptz := month_start::timestamp at time zone 'UTC';
    to_ts     timestamptz := (month_start + interval '1 month')::timestamp at time zone 'UTC';
begin
    if month_start <> date_trunc('month', month_start)::date then
        raise exception 'начало секции должно быть первым числом месяца: %', month_start;
    end if;
    execute format('create table if not exists audit_admin.%I partition of audit_admin.audit_log for values from (%L) to (%L)', part_name, from_ts, to_ts);
    if not exists (
        select 1 from pg_trigger t where t.tgrelid = ('audit_admin.' || quote_ident(part_name))::regclass and t.tgname = 'trg_audit_log_no_truncate'
    ) then
        execute format('create trigger trg_audit_log_no_truncate before truncate on audit_admin.%I for each statement execute function audit_admin.audit_log_deny()', part_name);
    end if;
    return part_name;
end
$fn$;
comment on function audit_admin.create_audit_log_partition(date) is 'Создаёт месячную секцию журнала аудита. Вызывается миграцией и ежемесячным заданием за 3 месяца вперёд';

create table audit_admin.audit_log_default partition of audit_admin.audit_log default;
create trigger trg_audit_log_no_truncate before truncate on audit_admin.audit_log_default
    for each statement execute function audit_admin.audit_log_deny();
comment on table audit_admin.audit_log_default is 'Страховочная секция: запись с неожиданным временем не теряется. Строки из неё переносят в месячные секции вручную';
select audit_admin.create_audit_log_partition(d::date)
from generate_series(date '2026-10-01', date '2027-03-01', interval '1 month') d;

-- Начальные значения параметров R1 (перечень совпадает с примером ответа listPlatformParameters)
insert into audit_admin.platform_parameter (key, description, unit, value, default_value, min_value, max_value) values
    ('commission.default-rate-bp',        'Комиссия по умолчанию, базисные пункты (200 это 2%). Фиксируется в заказе при создании', 'basis_points', 200, 200, 1, 3000),
    ('reservation.ttl-seconds',           'Время резерва ключей', 'seconds', 900, 900, 300, 3600),
    ('payment-session.ttl-seconds',       'Срок платёжной сессии, строго короче резерва (INV-07)', 'seconds', 720, 720, 60, 3599),
    ('orders.max-unpaid',                 'Число одновременно неоплаченных заказов пользователя: «создан» и «ожидает оплаты» (FT-5.1, T-12)', 'count', 3, 3, 1, 20),
    ('support.window-seconds',            'Окно обращения «Не получил ключ» от первичной выдачи (FT-7.2)', 'seconds', 259200, 259200, 3600, 2592000),
    ('seller.reapplication-pause-seconds', 'Пауза после отказа продавцу перед новой заявкой (FT-2.4)', 'seconds', 86400, 86400, 3600, 604800),
    ('otp.requests-per-10-minutes',       'Запросов кода на учётную запись и номер за 10 минут (NFT-3.5)', 'count', 3, 3, 1, 20),
    ('otp.requests-per-day',              'Запросов кода на учётную запись и номер за сутки (NFT-3.5)', 'count', 10, 10, 1, 100),
    ('sms.daily-cap',                     'Общий потолок SMS в сутки, оповещение при 80% (ADR-010)', 'count', 1000, 1000, 10, 100000);

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
