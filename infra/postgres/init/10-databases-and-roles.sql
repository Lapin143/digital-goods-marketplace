-- Базы и роли PostgreSQL стенда. Выполняется один раз при первом запуске пустого тома штатным сценарием образа
-- (каталог /docker-entrypoint-initdb.d, пользователь postgres, сокет внутри контейнера). Повторный запуск безопасен:
--   make db-roles    применяет файл снова (новая роль, смена пароля после замены секрета, исправление лимитов)
-- Что где создаётся:
--   базы         по одной на сервис (catalog_db, inventory_db, order_db, payment_db, delivery_db, platform_db) и keycloak_db;
--   миграторы    migrator_<сервис>: владелец своей базы, создаёт схемы, таблицы и выдаёт права (Flyway при старте сервиса);
--                суперпользователем не является, создавать роли и базы не может;
--   рабочие роли app_<модуль>: ходят сервисы во время работы, права на таблицы выдают миграции (раздел «Роли и права»);
--   keycloak     владелец keycloak_db, схему создаёт сам Keycloak.
-- Список ролей, баз, секретов и лимитов соединений: /etc/dgm/postgres/roles.json (infra/postgres/roles.json).
-- Пароли читаются из файлов /run/secrets/<имя секрета> на стороне сервера и в окружение, в команды и в журнал не попадают.
\set ON_ERROR_STOP on
\set cfg `cat /etc/dgm/postgres/roles.json`

set password_encryption = 'scram-sha-256';
-- Если команда с паролем завершится ошибкой, текст команды не должен попасть в журнал сервера
set log_min_error_statement = 'panic';
set log_statement = 'none';

select set_config('dgm.roles', :'cfg', false) \gset

-- 1. Роли: создать недостающие, затем для всех (в том числе созданных раньше) выставить атрибуты, лимит и пароль из секрета
do $$
declare
    r record;
    secret text;
begin
    for r in select * from jsonb_to_recordset(current_setting('dgm.roles')::jsonb -> 'roles') as x(name text, secret text, "limit" int, database text) loop
        if not exists (select from pg_roles where rolname = r.name) then
            execute format('create role %I login', r.name);
        end if;
        secret := rtrim(pg_read_file('/run/secrets/' || r.secret), E'\r\n');
        if secret is null or length(secret) < 16 then
            raise exception 'секрет % пуст или короче 16 символов', r.secret;
        end if;
        execute format('alter role %I with login nosuperuser nocreatedb nocreaterole noinherit noreplication nobypassrls connection limit %s password %L',
                       r.name, r."limit", secret);
    end loop;
end
$$;

-- 2. Базы: создать недостающие (CREATE DATABASE не выполняется внутри блока DO, поэтому через \gexec)
select format('create database %I owner %I', d.name, d.owner)
from jsonb_to_recordset(current_setting('dgm.roles')::jsonb -> 'databases') as d(name text, owner text)
where not exists (select from pg_database where datname = d.name)
\gexec

-- 3. Владелец, доступ: подключаться к базе может только её владелец и её рабочие роли, остальные нет
do $$
declare
    d record;
    r record;
begin
    for d in select * from jsonb_to_recordset(current_setting('dgm.roles')::jsonb -> 'databases') as x(name text, owner text) loop
        execute format('alter database %I owner to %I', d.name, d.owner);
        execute format('revoke all on database %I from public', d.name);
        execute format('grant connect on database %I to %I', d.name, d.owner);
    end loop;
    for r in select * from jsonb_to_recordset(current_setting('dgm.roles')::jsonb -> 'roles') as x(name text, database text) loop
        execute format('grant connect on database %I to %I', r.database, r.name);
    end loop;
    -- служебная база postgres нужна только администратору
    execute 'revoke all on database postgres from public';
end
$$;

-- 4. Итоговая проверка: все базы на месте, у каждой верный владелец, ни одна роль не суперпользователь
do $$
declare
    expected int := jsonb_array_length(current_setting('dgm.roles')::jsonb -> 'databases');
    found int;
    bad text;
begin
    select count(*) into found
    from jsonb_to_recordset(current_setting('dgm.roles')::jsonb -> 'databases') as d(name text, owner text)
    join pg_database pd on pd.datname = d.name
    join pg_roles po on po.oid = pd.datdba and po.rolname = d.owner;
    if found <> expected then
        raise exception 'баз с нужным владельцем % из %', found, expected;
    end if;
    select string_agg(rolname, ', ') into bad from pg_roles
    where rolname in (select x.name from jsonb_to_recordset(current_setting('dgm.roles')::jsonb -> 'roles') as x(name text))
      and (rolsuper or rolcreaterole or rolcreatedb or rolreplication or rolbypassrls or not rolcanlogin);
    if bad is not null then
        raise exception 'у ролей лишние права или нет входа: %', bad;
    end if;
end
$$;

\echo базы и роли на месте
