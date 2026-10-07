-- СОЗДАН СКРИПТОМ tools/docs-checks/db/gen_migrations.py ИЗ tools/docs-checks/db/parts/platform.sql. РУКАМИ НЕ ПРАВИТЬ.
-- Сервис platform-service, база platform_db. Миграция V5, схема audit_admin.
-- Применённую миграцию править нельзя: Flyway сверяет контрольную сумму, правка вызовет ошибку проверки при старте.
-- Полная модель одним файлом: docs/07-data/ddl/platform-service.sql

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
