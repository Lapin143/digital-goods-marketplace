-- СОЗДАН СКРИПТОМ tools/docs-checks/db/gen_migrations.py ИЗ tools/docs-checks/db/parts/catalog.r2.sql. РУКАМИ НЕ ПРАВИТЬ.
-- Сервис catalog-service, база catalog_db. Миграция V5, R2, API-ключи продавца и подключение его системы (срез 1 Ф5, Р-185, Р-187).
-- Применённую миграцию править нельзя: Flyway сверяет контрольную сумму, правка вызовет ошибку проверки при старте.
-- Полная модель одним файлом: docs/07-data/ddl/catalog-service.sql

-- ============================================================================
-- R2, seller_onboarding: API-ключи продавца и подключение его системы (срез 1 Ф5, Р-185, Р-187)
-- ============================================================================
create table seller_onboarding.api_key (
    id          uuid        not null,
    seller_id   uuid        not null,
    name        text        not null,
    key_hash    bytea       not null,
    fingerprint text        not null,
    status      text        not null default 'active',
    created_at  timestamptz not null default now(),
    revoked_at  timestamptz,
    constraint pk_api_key primary key (id),
    constraint fk_api_key_seller_id foreign key (seller_id) references seller_onboarding.seller_profile (id),
    -- INV-41: значение ключа нигде не хранится, поиск идёт по хешу, одинаковых хешей у разных ключей нет
    constraint uq_api_key_key_hash unique (key_hash),
    constraint ck_api_key_status check (status in ('active', 'revoked')),
    constraint ck_api_key_name_len check (char_length(name) between 1 and 60),
    constraint ck_api_key_key_hash check (octet_length(key_hash) = 32),
    constraint ck_api_key_fingerprint check (fingerprint ~ '^[A-Za-z0-9_-]{8}$'),
    constraint ck_api_key_revoked_state check ((status = 'revoked') = (revoked_at is not null))
);
comment on table seller_onboarding.api_key is 'API-ключ продавца (R2, Р-185). Значения ключа нет: показывается один раз при выпуске, хранится SHA-256 (INV-41). Не больше пяти активных ключей на продавца';
comment on column seller_onboarding.api_key.id is 'Идентификатор ключа: по нему шлюз считает лимит 600 запросов в минуту (Р-188), он же в журналах вместо значения';
comment on column seller_onboarding.api_key.seller_id is 'SellerID владельца ключа, профиль в этой же схеме';
comment on column seller_onboarding.api_key.name is 'Имя, которое продавец задал при выпуске, от 1 до 60 знаков: по нему он отличает ключи разных систем';
comment on column seller_onboarding.api_key.key_hash is 'SHA-256 значения ключа, 32 байта. Значение (dgm_ и 40 знаков base64url) в базе не хранится и восстановлению не подлежит (INV-41)';
comment on column seller_onboarding.api_key.fingerprint is 'Первые восемь знаков ключа после префикса dgm_: продавец узнаёт ключ в списке, они же в журналах. Не секрет';
comment on column seller_onboarding.api_key.status is 'active или revoked. Отозванный ключ не возвращается в active: отзыв необратим';
comment on column seller_onboarding.api_key.created_at is 'Когда ключ выпущен';
comment on column seller_onboarding.api_key.revoked_at is 'Когда ключ отозван, есть ровно у отозванного. Шлюз перестаёт принимать ключ не позже чем через 30 секунд (кэш проверки)';
-- кабинет: список своих ключей, новые выше
create index ix_api_key_seller_id on seller_onboarding.api_key (seller_id, created_at desc, id desc);
comment on index seller_onboarding.ix_api_key_seller_id is 'GET /api/v1/seller/api-keys (OP-91): ключи продавца, новые выше, курсор по (created_at, id), счёт активных при выпуске (не больше пяти), проверка внешнего ключа';

create trigger trg_api_key_status_initial before insert on seller_onboarding.api_key
    for each row execute function public.enforce_status_transition('initial:active');
create trigger trg_api_key_status_transition before update of status on seller_onboarding.api_key
    for each row when (old.status is distinct from new.status)
    execute function public.enforce_status_transition('active>revoked');

-- Не больше пяти активных ключей: счёт под консультативной блокировкой продавца, два одновременных выпуска не пройдут оба
create function seller_onboarding.api_key_limit() returns trigger
language plpgsql as
$fn$
begin
    perform pg_advisory_xact_lock(hashtextextended('api_key:' || new.seller_id::text, 0));
    if (select count(*) from seller_onboarding.api_key where seller_id = new.seller_id and status = 'active') >= 5 then
        raise exception 'у продавца уже пять активных API-ключей' using errcode = '23514', constraint = 'ck_api_key_limit';
    end if;
    return new;
end
$fn$;
create trigger trg_api_key_limit before insert on seller_onboarding.api_key
    for each row execute function seller_onboarding.api_key_limit();

-- Хеш, отпечаток, имя и владелец не меняются: ключ можно только отозвать
create function seller_onboarding.api_key_immutable() returns trigger
language plpgsql as
$fn$
begin
    if new.id is distinct from old.id or new.seller_id is distinct from old.seller_id or new.name is distinct from old.name
       or new.key_hash is distinct from old.key_hash or new.fingerprint is distinct from old.fingerprint
       or new.created_at is distinct from old.created_at
       or (old.revoked_at is not null and new.revoked_at is distinct from old.revoked_at) then
        raise exception 'владелец, имя, хеш и отпечаток API-ключа не меняются, ключ только отзывается' using errcode = '23514', constraint = 'ck_api_key_immutable';
    end if;
    return new;
end
$fn$;
create trigger trg_api_key_immutable before update on seller_onboarding.api_key
    for each row execute function seller_onboarding.api_key_immutable();

create table seller_onboarding.seller_integration (
    seller_id      uuid        not null,
    endpoint_url   text        not null,
    secret_version integer     not null default 1,
    status         text        not null default 'active',
    version        integer     not null default 1,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),
    constraint pk_seller_integration primary key (seller_id),
    constraint fk_seller_integration_seller_id foreign key (seller_id) references seller_onboarding.seller_profile (id),
    constraint ck_seller_integration_endpoint_url check (endpoint_url ~ '^https://[^[:space:]]+$' and char_length(endpoint_url) <= 2048),
    constraint ck_seller_integration_secret_version check (secret_version >= 1),
    constraint ck_seller_integration_status check (status in ('active', 'disabled')),
    constraint ck_seller_integration_version check (version >= 1)
);
comment on table seller_onboarding.seller_integration is 'Подключение системы продавца (R2, Р-187): куда платформа отправляет запрос ключа и уведомления. Секрета подписи в таблице нет: он производный, считается из идентификатора продавца и версии секрета';
comment on column seller_onboarding.seller_integration.seller_id is 'SellerID, одно подключение на продавца';
comment on column seller_onboarding.seller_integration.endpoint_url is 'Адрес системы продавца, только https, до 2048 знаков. Защиту от SSRF проходит при сохранении и при каждом вызове (ADR-027), а не в базе';
comment on column seller_onboarding.seller_integration.secret_version is 'Версия секрета подписи, растёт при смене: прежний секрет перестаёт действовать сразу, наложения нет';
comment on column seller_onboarding.seller_integration.status is 'active (платформа вызывает адрес) или disabled (адрес сохранён, вызовов нет)';
comment on column seller_onboarding.seller_integration.version is 'Версия записи для ETag и If-Match, растёт на единицу при каждом изменении';
comment on column seller_onboarding.seller_integration.created_at is 'Когда система подключена';
comment on column seller_onboarding.seller_integration.updated_at is 'Последнее изменение';

-- Владелец и время создания неизменны, версия секрета не уменьшается, версия записи растёт на единицу
create function seller_onboarding.seller_integration_touch() returns trigger
language plpgsql as
$fn$
begin
    if new.seller_id is distinct from old.seller_id or new.created_at is distinct from old.created_at then
        raise exception 'владелец и время создания подключения не меняются' using errcode = '23514', constraint = 'ck_seller_integration_immutable';
    end if;
    if new.secret_version < old.secret_version then
        raise exception 'версия секрета подписи не уменьшается' using errcode = '23514', constraint = 'ck_seller_integration_secret_version_down';
    end if;
    new.version := old.version + 1;
    new.updated_at := now();
    return new;
end
$fn$;
create trigger trg_seller_integration_touch before update on seller_onboarding.seller_integration
    for each row execute function seller_onboarding.seller_integration_touch();

comment on function seller_onboarding.api_key_limit() is 'Не больше пяти активных API-ключей на продавца (Р-185): счёт под консультативной блокировкой продавца';
comment on function seller_onboarding.api_key_immutable() is 'Владелец, имя, хеш и отпечаток API-ключа неизменны, ключ только отзывается';
comment on function seller_onboarding.seller_integration_touch() is 'Владелец и время создания подключения неизменны, версия секрета не уменьшается, версия записи растёт на единицу';
