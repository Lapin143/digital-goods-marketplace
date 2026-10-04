#!/usr/bin/env bash
# Применить миграции Flyway сервисов к поднятому PostgreSQL стенда (Ф3, шаг 11).
#
#   tools/stand-checks/migrate_stand.sh [сервис...]      без аргументов: все шесть сервисов
#
# Нужен стенду для интеграционных тестов каркаса и сервисов: таблицы outbox и processed_event создаются миграциями, а не тестами.
# Миграции применяются под ролью-мигратором по TLS с проверкой сертификата (verify-full), как в db_migrations.sh,
# где то же самое проверяется подробнее. Повторный запуск безопасен. Каталог секретов только для чтения.
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
cd "$HERE/../.."
SECRETS=$(cd "${DGM_SECRETS_DIR:-secrets}" && pwd)
NET=${DGM_DATA_NETWORK:-dgm_data}
FLYWAY_IMAGE=${FLYWAY_IMAGE:-$(grep -oE 'flyway/flyway:[0-9][0-9.]*' docs/09-operations/versions.md | head -n 1)}
[ -n "$FLYWAY_IMAGE" ] || { echo "::error title=migrate_stand::не найден образ Flyway в docs/09-operations/versions.md"; exit 2; }

declare -A SHORT=([catalog-service]=catalog [inventory-service]=inventory [order-service]=order
                  [payment-service]=payment [delivery-service]=delivery [platform-service]=platform)
declare -A DB=([catalog-service]=catalog_db [inventory-service]=inventory_db [order-service]=order_db
               [payment-service]=payment_db [delivery-service]=delivery_db [platform-service]=platform_db)

if [ "$#" -eq 0 ]; then
  set -- catalog-service inventory-service order-service payment-service delivery-service platform-service
fi

rc=0
for svc in "$@"; do
  short=${SHORT[$svc]:-}
  [ -n "$short" ] || { echo "::error title=migrate_stand::неизвестный сервис $svc"; exit 2; }
  dir="$PWD/services/$svc/src/main/resources/db/migration"
  [ -d "$dir" ] || { echo "::error title=migrate_stand::нет каталога миграций $dir"; exit 2; }
  echo "== $svc: ${DB[$svc]} под ролью migrator_$short"
  docker run --rm --network "$NET" -u 0:0 -v "$SECRETS:/s:ro" -v "$dir:/m:ro" \
    -e FLYWAY_PASSWORD="$(cat "$SECRETS/db_migrator_$short")" "$FLYWAY_IMAGE" \
    "-url=jdbc:postgresql://postgres:5432/${DB[$svc]}?sslmode=verify-full&sslrootcert=/s/tls_ca.crt" \
    "-user=migrator_$short" -locations=filesystem:/m -connectRetries=5 -cleanDisabled=true migrate 2>&1 | tail -n 6
  [ "${PIPESTATUS[0]}" -eq 0 ] || { echo "::error title=migrate_stand::миграции $svc не применились"; rc=1; }
done
exit $rc
