#!/usr/bin/env bash
# Проверка рецепта «сервис из IDE при инфраструктуре в Compose» (шаг 19 Ф3; developer-guide.md, раздел 10).
#
#   make ide-check     стенд поднят набором dev-min с отладочными портами (make up SET=dev-min DEBUG=1), нужен JDK 25.
#                      Миграции сервис применяет сам при старте, как в контейнере. В CI база уже наполнена тестами (миграция 1000
#                      тестовых данных), а сервис запускается без профиля testdata: именно этот случай проверка и закрывает
#
# Что делает: запускает order-service на стороне хоста задачей Gradle bootRun, как это делает IDE, с переменными из руководства
# (DGM_SECRETS_DIR, DGM_PG_HOST, DGM_PG_PORT, DGM_KAFKA_BOOTSTRAP и свободные порты вместо 8443 и 8444), и проверяет по mTLS с сертификатом шлюза:
#   готовность   порт управления отвечает «UP»: сервис дошёл до базы по TLS (отладочный порт 15432) и до Kafka (19093)
#   защита       прикладной порт без токена отвечает 401, а не пропускает запрос и не падает
#   сертификат   без клиентского сертификата рукопожатие не проходит (mTLS включён и на ноутбуке)
# По окончании процесс сервиса останавливается. Порты: IDE_SERVER_PORT (по умолчанию 18448), IDE_MANAGEMENT_PORT (18449).
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
cd "$HERE/../.."
SECRETS=$(cd "${DGM_SECRETS_DIR:-secrets}" && pwd)
APP_PORT=${IDE_SERVER_PORT:-18448}
MGMT_PORT=${IDE_MANAGEMENT_PORT:-18449}
WAIT=${IDE_WAIT_SECONDS:-240}
LOG=$(mktemp)
source "$HERE/lib.sh"

# Переменные ровно из таблицы раздела 10 руководства
export DGM_SECRETS_DIR=$SECRETS DGM_PG_HOST=localhost DGM_PG_PORT=15432 DGM_KAFKA_BOOTSTRAP=localhost:19093
export SERVER_PORT=$APP_PORT MANAGEMENT_SERVER_PORT=$MGMT_PORT

# Группа процессов: bootRun запускает дочернюю JVM, и остановить нужно её, а не только оболочку Gradle
setsid ./gradlew --console=plain :services:order-service:bootRun >"$LOG" 2>&1 &
PID=$!
stop() { kill -TERM -- "-$PID" 2>/dev/null; sleep 2; kill -KILL -- "-$PID" 2>/dev/null; true; }
trap stop EXIT

mtls() { curl -sS --max-time 10 --cacert "$SECRETS/tls_ca.crt" --cert "$SECRETS/tls_api-gateway.crt" --key "$SECRETS/tls_api-gateway.key" "$@"; }

echo "== Запуск order-service из Gradle на стороне хоста (порты $APP_PORT и $MGMT_PORT)"
UP=""
for _ in $(seq 1 "$WAIT"); do
  if ! kill -0 "$PID" 2>/dev/null; then break; fi
  UP=$(mtls "https://localhost:$MGMT_PORT/actuator/health" 2>/dev/null || true)
  grep -q '"status":"UP"' <<<"$UP" && break
  UP=""
  sleep 1
done
if [ -n "$UP" ]; then
  ok "порт управления отвечает UP (база и Kafka стенда достигнуты с хоста)"
else
  # Причина падения: цепочка «Caused by», строки о проверке миграций и хвост журнала (журнал шага без входа в аккаунт не читается)
  why=$( { grep -oE 'Caused by: .{0,200}' "$LOG" | head -n 8; grep -m1 -A10 -E 'failed validation|Validate failed' "$LOG" | grep -vE '^\s*(at |\.\.\.)'; tail -n 6 "$LOG"; } | cut -c1-220 )
  echo "$why" | sed 's/^/        | /'
  bad "сервис не стал готовым (процесс $(kill -0 "$PID" 2>/dev/null && echo работает || echo завершился), ждали до $WAIT с)" "$(echo "$why" | head -n 3)"
fi

if [ -n "$UP" ]; then
  code=$(mtls -o /dev/null -w '%{http_code}' "https://localhost:$APP_PORT/api/v1/orders" 2>&1)
  if [ "$code" = "401" ]; then ok "заказы без токена: 401"; else bad "заказы без токена: ожидался 401, получен $code"; fi
  out=$(curl -sS --max-time 10 --cacert "$SECRETS/tls_ca.crt" "https://localhost:$APP_PORT/api/v1/orders" 2>&1)
  if [ $? -ne 0 ]; then ok "без клиентского сертификата рукопожатие отклонено (mTLS)"; else bad "без клиентского сертификата получен ответ" "$out"; fi
fi

finish
