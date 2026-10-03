-- ============================================================================
-- inventory-service: база inventory_db (модуль inventory)
-- Физическая модель данных R1, PostgreSQL 16. Файл собран из общего служебного блока и части сервиса.
-- Выполняется с нуля одним проходом: psql -v ON_ERROR_STOP=1 -d inventory_db -f inventory-service.sql
-- Нужны права создания схем, ролей и расширений. В проекте файл разбит на миграции Flyway по схемам (decomposition.md, правило 7),
-- здесь они сведены вместе, чтобы модель читалась и проверялась целиком.
-- Описание: docs/07-data/inventory-service-schema.md
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
-- Схема inventory: пул ключей, резервы, версии ключа данных (SM-03, SM-08)
-- ============================================================================
create schema inventory;
comment on schema inventory is 'Модуль inventory: ключи, резервы, ключи данных шифрования. Своя роль app_inventory, права DELETE на key нет';

-- Копия данных товара из событий product.created и product.updated (раздел 5 доменной модели, реестр товаров).
-- Нужна, чтобы отвечать «товар принадлежит продавцу» и «выдача из пула» без вызова каталога.
create table inventory.product_copy (
    product_id      uuid        not null,
    seller_id       uuid        not null,
    issuance_method text        not null,
    status          text        not null,
    source_version  integer     not null,
    updated_at      timestamptz not null default now(),
    constraint pk_product_copy primary key (product_id),
    constraint ck_product_copy_issuance_method check (issuance_method in ('key_pool', 'seller_api')),
    constraint ck_product_copy_status check (status in ('draft', 'on_moderation', 'published', 'rejected', 'blocked', 'archived')),
    constraint ck_product_copy_source_version check (source_version >= 1)
);
comment on table inventory.product_copy is 'Копия товара из событий каталога: владелец и способ выдачи. Источник истины: catalog-service';
comment on column inventory.product_copy.product_id is 'ProductID (чужая база, внешнего ключа нет)';
comment on column inventory.product_copy.seller_id is 'SellerID владельца товара';
comment on column inventory.product_copy.issuance_method is 'key_pool или seller_api. Загрузка пула разрешена только для key_pool';
comment on column inventory.product_copy.status is 'Статус товара по SM-04 из последнего применённого события';
comment on column inventory.product_copy.source_version is 'Версия товара из события: более старое событие после сбоя порядка игнорируется';
comment on column inventory.product_copy.updated_at is 'Когда копия обновлена';

-- Версии ключа данных (DEK), зашифрованы мастер-ключом (KEK) вне базы (ADR-009)
create table inventory.data_key (
    id          integer     generated always as identity,
    wrapped_key bytea       not null,
    kek_version integer     not null,
    status      text        not null default 'active',
    created_at  timestamptz not null default now(),
    retired_at  timestamptz,
    constraint pk_data_key primary key (id),
    constraint ck_data_key_status check (status in ('active', 'retired')),
    constraint ck_data_key_wrapped_key check (octet_length(wrapped_key) between 60 and 128),
    constraint ck_data_key_kek_version check (kek_version >= 1),
    constraint ck_data_key_retired_at check ((status = 'retired') = (retired_at is not null))
);
comment on table inventory.data_key is 'Версии ключа данных (DEK), зашифрованы мастер-ключом AES-256-GCM. Мастер-ключ в базе не хранится (ADR-009)';
comment on column inventory.data_key.created_at is 'Время создания версии ключа данных';
comment on column inventory.data_key.id is 'Номер версии DEK. Числовая последовательность, а не UUID: это справочник шифрования, а не бизнес-сущность (исключение из conventions 3.1)';
comment on column inventory.data_key.wrapped_key is 'DEK, зашифрованный KEK: nonce 12 байт, шифртекст 32 байта, тег 16 байт';
comment on column inventory.data_key.kek_version is 'Версия мастер-ключа, которым зашифрован DEK: нужна при ротации KEK';
comment on column inventory.data_key.status is 'active: шифрует новые значения, retired: только читается. Активный один';
comment on column inventory.data_key.retired_at is 'Когда версия выведена из использования';
-- не больше одной действующей версии: новые значения всегда шифруются однозначно
create unique index uq_data_key_active on inventory.data_key ((true)) where status = 'active';
comment on index inventory.uq_data_key_active is 'Одна активная версия DEK: константное выражение в частичном уникальном индексе, вторая активная запись невозможна';

-- Резерв: временное закрепление ключей за заказом (SM-08)
create table inventory.reservation (
    id             uuid        not null,
    order_id       uuid        not null,
    product_id     uuid        not null,
    quantity       integer     not null,
    status         text        not null default 'active',
    expires_at     timestamptz not null,
    release_reason text,
    created_at     timestamptz not null default now(),
    used_at        timestamptz,
    released_at    timestamptz,
    constraint pk_reservation primary key (id),
    constraint fk_reservation_product_id foreign key (product_id) references inventory.product_copy (product_id),
    constraint ck_reservation_status check (status in ('active', 'used', 'released')),
    constraint ck_reservation_quantity check (quantity between 1 and 10),
    constraint ck_reservation_expires_at check (expires_at > created_at),
    constraint ck_reservation_release_reason check (release_reason in ('expired', 'payment_declined', 'order_cancelled')),
    -- причина и время снятия есть ровно у снятого резерва, время использования у использованного
    constraint ck_reservation_released_state check (
        (status = 'released') = (release_reason is not null and released_at is not null)
        and (release_reason is null) = (released_at is null)
    ),
    constraint ck_reservation_used_state check ((status = 'used') = (used_at is not null))
);
comment on table inventory.reservation is 'Резерв ключей за заказом. Источник истины о сроке: эта таблица, Redis лишь запускает снятие по таймеру (ADR-012)';
comment on column inventory.reservation.created_at is 'Время создания резерва, от него считается срок';
comment on column inventory.reservation.id is 'ReservationID';
comment on column inventory.reservation.order_id is 'OrderID. У заказа за жизнь несколько резервов (поздняя оплата создаёт новый), активный не больше одного (INV-06)';
comment on column inventory.reservation.product_id is 'ProductID резервируемого товара';
comment on column inventory.reservation.quantity is 'Количество равно количеству в заказе, частичного резерва нет (INV-05)';
comment on column inventory.reservation.status is 'SM-08: active, used, released';
comment on column inventory.reservation.expires_at is 'Срок резерва: создание плюс значение параметра reservation.ttl-seconds';
comment on column inventory.reservation.release_reason is 'expired (срок истёк), payment_declined (отказ в оплате), order_cancelled (заказ отменён). В conventions 7.3 первое значение отсутствует: срок истёк там отдельное событие, в таблице нужна причина';
comment on column inventory.reservation.used_at is 'Когда резерв перешёл в used (подтверждение оплаты)';
comment on column inventory.reservation.released_at is 'Когда резерв снят';
-- INV-06: у заказа не больше одного активного резерва
create unique index uq_reservation_order_id_active on inventory.reservation (order_id) where status = 'active';
comment on index inventory.uq_reservation_order_id_active is 'INV-06: второй активный резерв заказа невозможен. Тот же индекс отвечает на запрос подтверждения UPDATE ... WHERE order_id = :o AND status = ''active''';
-- сверка резервов и загрузка таймера при старте
create index ix_reservation_expires_at_active on inventory.reservation (expires_at) where status = 'active';
comment on index inventory.ix_reservation_expires_at_active is 'Сверка раз в минуту (reservation-reconciler): активные резервы с прошедшим сроком, и загрузка таймера Redis при старте';
-- история резервов заказа
create index ix_reservation_order_id on inventory.reservation (order_id, created_at desc);
comment on index inventory.ix_reservation_order_id is 'Последний резерв заказа: поздняя оплата (ADR-013) и снятие по order.cancelled';

create trigger trg_reservation_status_initial before insert on inventory.reservation
    for each row execute function public.enforce_status_transition('initial:active');
create trigger trg_reservation_status_transition before update of status on inventory.reservation
    for each row when (old.status is distinct from new.status)
    execute function public.enforce_status_transition('active>used', 'active>released');

-- Ключ: единица товара, значение хранится только в зашифрованном виде (SM-03, ADR-009)
create table inventory.key (
    id             uuid        not null,
    product_id     uuid        not null,
    dek_id         integer     not null,
    nonce          bytea       not null,
    ciphertext     bytea       not null,
    hmac           bytea       not null,
    hmac_version   smallint    not null default 1,
    order_id       uuid,
    reservation_id uuid,
    issued_at      timestamptz,
    status         text        not null default 'free',
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),
    constraint pk_key primary key (id),
    constraint fk_key_product_id foreign key (product_id) references inventory.product_copy (product_id),
    constraint fk_key_dek_id foreign key (dek_id) references inventory.data_key (id),
    constraint fk_key_reservation_id foreign key (reservation_id) references inventory.reservation (id),
    -- INV-03: значения уникальны в пределах товара (сравнение по HMAC, открытого значения в базе нет)
    constraint uq_key_product_id_hmac unique (product_id, hmac),
    constraint ck_key_status check (status in ('free', 'reserved', 'issued', 'voided')),
    constraint ck_key_nonce check (octet_length(nonce) = 12),
    constraint ck_key_ciphertext check (octet_length(ciphertext) between 17 and 4112),
    constraint ck_key_hmac check (octet_length(hmac) = 32),
    constraint ck_key_hmac_version check (hmac_version >= 1),
    -- INV-02: свободный ключ ни к чему не привязан, зарезервированный и выданный привязаны к заказу и резерву
    constraint ck_key_binding check (
        (status = 'free' and order_id is null and reservation_id is null)
        or (status in ('reserved', 'issued') and order_id is not null and reservation_id is not null)
        or status = 'voided'
    ),
    constraint ck_key_issued_at check (
        (status in ('free', 'reserved') and issued_at is null) or (status in ('issued', 'voided') and issued_at is not null)
    )
);
comment on table inventory.key is 'Ключи товаров. Открытого значения нет нигде: шифртекст AES-256-GCM и HMAC для проверки дублей (INV-20). DELETE роли приложения запрещён';
comment on column inventory.key.id is 'KeyID. Входит в дополнительные аутентифицируемые данные шифрования, поэтому не меняется';
comment on column inventory.key.product_id is 'ProductID пула. Входит в AAD шифрования и в HMAC, поэтому не меняется';
comment on column inventory.key.dek_id is 'Версия ключа данных, которым зашифровано значение';
comment on column inventory.key.nonce is 'Одноразовое значение AES-GCM, 96 бит, случайное на каждое шифрование';
comment on column inventory.key.ciphertext is 'Шифртекст значения вместе с тегом проверки целостности (16 байт). Значение до 1024 символов UTF-8';
comment on column inventory.key.hmac is 'HMAC-SHA-256 от «ProductID, 0x00, нормализованное значение» с секретом вне базы. Пара с product_id уникальна (INV-03)';
comment on column inventory.key.hmac_version is 'Версия секрета HMAC: нужна при миграции после компрометации секрета (ADR-009)';
comment on column inventory.key.order_id is 'OrderID. Пуст у свободного ключа (INV-02), чужая база, внешнего ключа нет';
comment on column inventory.key.reservation_id is 'Резерв, который держит ключ. Пуст у свободного ключа (INV-02)';
comment on column inventory.key.issued_at is 'Дата перехода в issued (подтверждение оплаты)';
comment on column inventory.key.status is 'SM-03: free, reserved, issued, voided (voided появляется в R2)';
comment on column inventory.key.created_at is 'Когда ключ загружен';
comment on column inventory.key.updated_at is 'Последнее изменение статуса';
-- выбор свободных ключей под блокировкой: порядок по id, без сортировки на горячем пути
create index ix_key_product_id_free on inventory.key (product_id, id) where status = 'free';
comment on index inventory.ix_key_product_id_free is 'ADR-007: SELECT ... WHERE product_id = :p AND status = ''free'' ORDER BY id LIMIT :n FOR UPDATE SKIP LOCKED без сортировки, и счёт свободных для stock.changed';
-- статистика пула продавца
create index ix_key_product_id_status on inventory.key (product_id, status);
comment on index inventory.ix_key_product_id_status is 'GET /api/v1/seller/products/{id}/stock (US-4.4): число ключей по статусам одного товара';
-- значения по заказу
create index ix_key_order_id on inventory.key (order_id) where order_id is not null;
comment on index inventory.ix_key_order_id is 'Ключи заказа: getKeyValues (статус issued) и подтверждение резерва';
-- ключи резерва: снятие и подтверждение
create index ix_key_reservation_id on inventory.key (reservation_id) where reservation_id is not null;
comment on index inventory.ix_key_reservation_id is 'Ключи резерва: возврат в free при снятии и перевод в issued при подтверждении, а также проверка внешнего ключа';
create index ix_key_dek_id on inventory.key (dek_id);
comment on index inventory.ix_key_dek_id is 'Фоновая перешифровка: поиск значений старой версии DEK, и проверка внешнего ключа';

-- Идентификатор и товар ключа не меняются: на них опирается шифрование (AAD). Закреплённый ключ не перепривязывается (INV-01)
create function inventory.key_immutable() returns trigger
language plpgsql as
$fn$
begin
    if new.id is distinct from old.id or new.product_id is distinct from old.product_id then
        raise exception 'идентификатор и товар ключа не меняются' using errcode = '23514', constraint = 'ck_key_immutable';
    end if;
    -- INV-01, второй рубеж: закреплённый за заказом ключ нельзя перепривязать к другому заказу или резерву, не освободив
    if (old.order_id is not null and new.order_id is not null and new.order_id is distinct from old.order_id)
       or (old.reservation_id is not null and new.reservation_id is not null and new.reservation_id is distinct from old.reservation_id) then
        raise exception 'ключ уже закреплён за заказом, перепривязка запрещена' using errcode = '23514', constraint = 'ck_key_rebind';
    end if;
    new.updated_at := now();
    return new;
end
$fn$;
create trigger trg_key_immutable before update on inventory.key
    for each row execute function inventory.key_immutable();

create trigger trg_key_status_initial before insert on inventory.key
    for each row execute function public.enforce_status_transition('initial:free');
-- INV-04: выданный ключ не возвращается в свободные. R1: T1 .. T4, R2 добавит T5 (issued>voided) миграцией
create trigger trg_key_status_transition before update of status on inventory.key
    for each row when (old.status is distinct from new.status)
    execute function public.enforce_status_transition('free>reserved', 'reserved>free', 'reserved>issued');

-- ============================================================================
-- Роль и права
-- ============================================================================
do $$
begin
    if not exists (select from pg_roles where rolname = 'app_inventory') then
        create role app_inventory nologin;
    end if;
end
$$;
grant usage on schema inventory to app_inventory;
grant select, insert, update on inventory.product_copy, inventory.data_key, inventory.reservation to app_inventory;
-- у ключей нет DELETE: единица товара не исчезает, аннулирование это статус
grant select, insert, update on inventory.key to app_inventory;
grant usage on schema public to app_inventory;
grant select, insert, update, delete on public.outbox, public.processed_event, public.idempotency_key to app_inventory;
grant usage on all sequences in schema public to app_inventory;
grant usage on all sequences in schema inventory to app_inventory;

comment on function inventory.key_immutable() is 'Идентификатор и товар ключа неизменны (на них опирается шифрование), закреплённый за заказом ключ не перепривязывается (INV-01, второй рубеж)';
