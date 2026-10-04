-- СОЗДАН СКРИПТОМ tools/docs-checks/db/gen_migrations.py ИЗ tools/docs-checks/db/parts/platform.sql. РУКАМИ НЕ ПРАВИТЬ.
-- Сервис platform-service, база platform_db. Миграция V2, схема identity.
-- Применённую миграцию править нельзя: Flyway сверяет контрольную сумму, правка вызовет ошибку проверки при старте.
-- Полная модель одним файлом: docs/07-data/ddl/platform-service.sql

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
