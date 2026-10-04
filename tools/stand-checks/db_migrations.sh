#!/usr/bin/env bash
# Миграции Flyway и права ролей на поднятом PostgreSQL (шаг 7 Ф3, ST-03, INV-42).
#
#   tools/stand-checks/db_migrations.sh        стенд уже поднят набором с профилем infra (make up SET=dev-min)
#
# Что проверяется:
#   Миграции   Flyway (образ из docs/09-operations/versions.md, раздел 6) применяет миграции каждого сервиса с нуля под ролью-мигратором
#              по TLS с проверкой сертификата; повторный запуск ничего не меняет; проверка контрольных сумм проходит; подмена
#              уже применённой миграции обнаруживается (почему применённые миграции не правят).
#   Модель     таблиц столько, сколько операторов create table в docs/07-data/ddl (всего 44), месячные секции аудита не считаются.
#   Права      роль-мигратор не суперпользователь и не читает файлы сервера; рабочая роль модуля видит только свои таблицы и схему,
#              не создаёт объекты, не подключается к чужой базе; журнал аудита не изменить и не очистить ни записывающей ролью,
#              ни владельцем (INV-42); предел соединений роли действует; Keycloak владеет только своей базой.
#   Роли       make db-roles повторяет создание баз и ролей без ошибок, пароли по-прежнему подходят.
# Клиенты запускаются контейнерами в сети data. Каталог секретов только для чтения.
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
cd "$HERE/../.."
SECRETS=$(cd "${DGM_SECRETS_DIR:-secrets}" && pwd)
NET=${DGM_DATA_NETWORK:-dgm_data}
COMPOSE="docker compose -f compose.yaml"
source "$HERE/lib.sh"

PG_IMAGE=$($COMPOSE --profile '*' config --images 2>/dev/null | grep -E '^postgres:' | head -n 1)
FLYWAY_IMAGE=${FLYWAY_IMAGE:-$(grep -oE 'flyway/flyway:[0-9][0-9.]*' docs/09-operations/versions.md | head -n 1)}
[ -n "$PG_IMAGE" ] && [ -n "$FLYWAY_IMAGE" ] || { echo "::error title=db_migrations::не найден образ PostgreSQL в compose.yaml или Flyway в versions.md"; exit 2; }
echo "образы: $PG_IMAGE, $FLYWAY_IMAGE"

drun() { docker run --rm --network "$NET" -v "$SECRETS:/s:ro" "$@"; }
secret() { cat "$SECRETS/db_$1"; }          # пароль роли: секрет db_<имя роли>

# psql от имени роли: роль база SQL
pgr() {
  local role=$1 db=$2 sql=$3
  drun -e PGPASSWORD="$(secret "$role")" "$PG_IMAGE" \
    psql "host=postgres port=5432 dbname=$db user=$role sslmode=verify-full sslrootcert=/s/tls_ca.crt connect_timeout=10" \
    -v ON_ERROR_STOP=1 -tAc "$sql"
}
# администратор (postgres): пароль db_postgres_admin
pga() {
  local db=$1 sql=$2
  drun -e PGPASSWORD="$(cat "$SECRETS/db_postgres_admin")" "$PG_IMAGE" \
    psql "host=postgres port=5432 dbname=$db user=postgres sslmode=verify-full sslrootcert=/s/tls_ca.crt connect_timeout=10" \
    -v ON_ERROR_STOP=1 -tAc "$sql"
}
# Flyway от имени роли-мигратора: роль база каталог-миграций команда
flyway() {
  local role=$1 db=$2 dir=$3 cmd=$4
  drun -u 0:0 -v "$dir:/m:ro" -e FLYWAY_PASSWORD="$(secret "$role")" "$FLYWAY_IMAGE" \
    "-url=jdbc:postgresql://postgres:5432/$db?sslmode=verify-full&sslrootcert=/s/tls_ca.crt" \
    "-user=$role" -locations=filesystem:/m -connectRetries=3 -cleanDisabled=true "$cmd"
}
flyway_ok() {  # образец успеха в выводе, остальное как для flyway
  local pat=$1; shift
  OUT=$(flyway "$@" 2>&1); RC=$?
  echo "$OUT" | tail -n 4
  [ $RC -eq 0 ] && grep -Eq "$pat" <<<"$OUT"
}

SERVICES=(catalog-service:catalog:catalog_db inventory-service:inventory:inventory_db order-service:order:order_db
          payment-service:payment:payment_db delivery-service:delivery:delivery_db platform-service:platform:platform_db)
migration_dir() { echo "$PWD/services/$1/src/main/resources/db/migration"; }
count_files()   { find "$(migration_dir "$1")" -name 'V*.sql' | wc -l | tr -d ' '; }
ddl_tables()    { grep -c '^create table ' "docs/07-data/ddl/$1.sql"; }

echo "== Миграции Flyway: применение с нуля под ролью-мигратором"
total_expected=0
for s in "${SERVICES[@]}"; do
  IFS=: read -r svc short db <<<"$s"
  n=$(count_files "$svc")
  expect_ok "$svc: Flyway применяет $n миграций в $db по TLS (verify-full)" \
    flyway_ok "Successfully applied $n migrations?" "migrator_$short" "$db" "$(migration_dir "$svc")" migrate
  expect_ok "$svc: повторный запуск ничего не меняет" \
    flyway_ok 'No migration necessary|is up to date' "migrator_$short" "$db" "$(migration_dir "$svc")" migrate
  expect_ok "$svc: контрольные суммы применённых миграций сходятся (validate)" \
    flyway_ok 'Successfully validated' "migrator_$short" "$db" "$(migration_dir "$svc")" validate
done

echo "== Модель: таблицы совпадают с DDL (44)"
for s in "${SERVICES[@]}"; do
  IFS=: read -r svc short db <<<"$s"
  want=$(ddl_tables "$svc"); total_expected=$((total_expected + want))
  tables_ok() {
    local have
    have=$(pga "$db" "select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace
                      where c.relkind in ('r','p') and n.nspname not in ('pg_catalog','information_schema')
                        and c.relname <> 'flyway_schema_history' and c.relname !~ '^audit_log_[0-9]{4}_[0-9]{2}\$'") || return 1
    [ "$have" = "$want" ] || { echo "в $db таблиц $have, в DDL $want"; return 1; }
  }
  expect_ok "$db: таблиц $want, как в docs/07-data/ddl/$svc.sql" tables_ok
done
[ "$total_expected" = 44 ] && ok "всего 44 таблицы, как в документах (6 баз)" || bad "в DDL таблиц $total_expected, в документах 44"

echo "== Изменение применённой миграции обнаруживается"
TAMPER=$(mktemp -d); chmod 755 "$TAMPER"
trap 'rm -rf "$TAMPER"' EXIT
cp -r "$(migration_dir order-service)/." "$TAMPER/"
printf '\n-- правка уже применённой миграции\n' >> "$TAMPER/common/V1__common.sql"
expect_fail "order-service: изменённая V1 отвергается проверкой контрольных сумм" 'checksum mismatch|Validate failed' \
  flyway migrator_order order_db "$TAMPER" validate

echo "== Права: роль-мигратор"
expect_denied "migrator_catalog не создаёт роли (не суперпользователь)" 'permission denied to create role' pgr migrator_catalog catalog_db 'create role probe_role'
expect_denied "migrator_catalog не читает файлы сервера" 'permission denied for function pg_read_file' pgr migrator_catalog catalog_db "select pg_read_file('/etc/hostname')"
expect_denied "migrator_catalog не подключается к чужой базе (order_db)" 'permission denied for database' pgr migrator_catalog order_db 'select 1'
owns_schemas() { [ "$(pgr migrator_catalog catalog_db "select count(*) from pg_namespace where nspname in ('seller_onboarding','catalog') and pg_get_userbyid(nspowner) = 'migrator_catalog'")" = 2 ]; }
expect_ok     "migrator_catalog владеет схемами seller_onboarding и catalog" owns_schemas

echo "== Права: рабочие роли модулей"
expect_ok     "app_catalog читает свои таблицы (catalog.product)" pgr app_catalog catalog_db 'select count(*) from catalog.product'
expect_denied "app_catalog не видит схему другого модуля (seller_onboarding)" 'permission denied for schema seller_onboarding' pgr app_catalog catalog_db 'select count(*) from seller_onboarding.seller_profile'
expect_denied "app_catalog не создаёт объекты в public" 'permission denied for schema public' pgr app_catalog catalog_db 'create table public.probe (i int)'
expect_denied "app_catalog не читает историю миграций" 'permission denied for table flyway_schema_history' pgr app_catalog catalog_db 'select count(*) from public.flyway_schema_history'
expect_denied "app_catalog не подключается к чужой базе (order_db)" 'permission denied for database' pgr app_catalog order_db 'select 1'
expect_denied "app_catalog не подключается к служебной базе postgres" 'permission denied for database' pgr app_catalog postgres 'select 1'
expect_denied "keycloak не подключается к базе каталога" 'permission denied for database' pgr keycloak catalog_db 'select 1'
expect_ok     "keycloak создаёт и удаляет таблицу в своей базе keycloak_db" pgr keycloak keycloak_db 'create table kc_probe (i int); drop table kc_probe'
expect_denied "неверный пароль рабочей роли отвергнут" 'password authentication failed' \
  drun -e PGPASSWORD=wrong-password "$PG_IMAGE" psql "host=postgres dbname=catalog_db user=app_catalog sslmode=verify-full sslrootcert=/s/tls_ca.crt connect_timeout=10" -c 'select 1'

echo "== Права: журнал аудита только добавляется (INV-42, ADR-014)"
expect_ok     "app_audit_writer читает журнал" pgr app_audit_writer platform_db 'select count(*) from audit_admin.audit_log'
expect_denied "app_audit_writer не меняет записи (UPDATE)" 'permission denied for table audit_log' pgr app_audit_writer platform_db 'update audit_admin.audit_log set changes = changes'
expect_denied "app_audit_writer не удаляет записи (DELETE)" 'permission denied for table audit_log' pgr app_audit_writer platform_db 'delete from audit_admin.audit_log'
expect_denied "app_audit_writer не очищает журнал (TRUNCATE)" 'permission denied for table audit_log' pgr app_audit_writer platform_db 'truncate audit_admin.audit_log'
expect_denied "app_audit_writer не читает параметры платформы" 'permission denied for table platform_parameter' pgr app_audit_writer platform_db 'select count(*) from audit_admin.platform_parameter'
expect_denied "владелец (migrator_platform) тоже не очищает журнал: запрет в триггере" 'журнал аудита только добавляется' pgr migrator_platform platform_db 'truncate audit_admin.audit_log'

echo "== Предел соединений роли"
SLEEPER=dgm-dbcheck-sleeper
limit_test() {
  docker rm -f "$SLEEPER" >/dev/null 2>&1
  drun -d --name "$SLEEPER" -e PGPASSWORD="$(secret app_audit_admin)" "$PG_IMAGE" \
    psql "host=postgres dbname=platform_db user=app_audit_admin sslmode=verify-full sslrootcert=/s/tls_ca.crt" -c 'select pg_sleep(40)' >/dev/null || return 1
  local i seen=0
  for i in $(seq 1 15); do
    seen=$(pga postgres "select count(*) from pg_stat_activity where usename = 'app_audit_admin'" 2>/dev/null || echo 0)
    [ "$seen" -ge 1 ] && break
    sleep 1
  done
  [ "$seen" -ge 1 ] || { echo "первое соединение app_audit_admin не появилось"; return 1; }
  pgr app_audit_admin platform_db 'select 1' 2>&1
}
expect_denied "app_audit_admin: второе соединение сверх предела 1 отвергнуто" 'too many connections for role' limit_test
docker rm -f "$SLEEPER" >/dev/null 2>&1

echo "== Повторное создание баз и ролей (make db-roles)"
roles_again() { $COMPOSE exec -T postgres psql -U postgres -d postgres -v ON_ERROR_STOP=1 -q -f /docker-entrypoint-initdb.d/10-databases-and-roles.sql; }
expect_denied "повторный запуск 10-databases-and-roles.sql проходит без ошибок" 'базы и роли на месте' roles_again
[ "$RC" = 0 ] || bad "повторное создание баз и ролей завершилось с кодом $RC"
expect_ok     "после повторного запуска рабочая роль подключается с прежним паролем" pgr app_catalog catalog_db 'select 1'

finish
