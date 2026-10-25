#!/usr/bin/env bash
# Проверка проверки параметров (шаг 8 среза 3, К9): слушатель order-service выключен, сценарий лимита неоплаченных заказов обязан покраснеть.
#
#   tools/stand-checks/parameters_sabotage.sh     набор full с ускоренными сроками, DEBUG=1, make keycloak-users, зелёный make parameters-check
#
# Платформа принимает правку параметра и пишет её в тему platform.config, но сервис заказов её не читает (DGM_CONFIG_ENABLED=false задаёт
# файл-дополнение к compose и пересоздаёт только order-service). Если проверка при этом остаётся зелёной, она проверяет не слушатель, а сам
# ответ платформы. Что делает скрипт:
#   1. пересоздаёт order-service с выключенным слушателем, ждёт готовности;
#   2. запускает только сценарий лимита (PARAMETERS_ONLY=unpaid), ожидание применения сокращено до 15 секунд: он обязан завершиться ошибкой;
#   3. пересоздаёт order-service как был, ждёт готовности и запускает тот же сценарий ещё раз: теперь он обязан быть зелёным (контроль «хорошей» проверки).
# Код выхода 0, только если красный прогон был красным, а прогон после возврата зелёным. Параметры сценарий возвращает сам (restore).
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
cd "$HERE/../.."
source "$HERE/lib.sh"
BASE=(docker compose -f compose.yaml -f compose.debug.yaml)
OVERRIDE=$(mktemp "${TMPDIR:-/tmp}/dgm-config-off.XXXXXX.yaml")
trap 'rm -f "$OVERRIDE"' EXIT
cat >"$OVERRIDE" <<'YAML'
services:
  order-service:
    environment:
      DGM_CONFIG_ENABLED: "false"
YAML

wait_healthy() {   # wait_healthy сервис секунд
  local cid deadline=$((SECONDS + $2))
  cid=$("${BASE[@]}" --profile '*' ps -q "$1" | head -n 1)
  [ -n "$cid" ] || return 1
  while [ $SECONDS -lt $deadline ]; do
    [ "$(docker inspect -f '{{.State.Health.Status}}' "$cid" 2>/dev/null)" = healthy ] && return 0
    sleep 3
  done
  return 1
}

recreate() {   # recreate [файл-дополнение]
  local files=(); [ -n "${1:-}" ] && files=(-f "$1")
  "${BASE[@]}" "${files[@]}" --profile '*' up -d --no-deps --force-recreate order-service
}

echo "== Слушатель order-service выключен: сценарий лимита обязан покраснеть"
check "order-service пересоздан с DGM_CONFIG_ENABLED=false" recreate "$OVERRIDE"
check "order-service здоров без слушателя" wait_healthy order-service 240
PARAMETERS_ONLY=unpaid PARAMETERS_WAIT=15 python3 tools/stand-checks/parameters_checks.py >/tmp/parameters-sabotage.log 2>&1
red=$?
tail -n 12 /tmp/parameters-sabotage.log | sed 's/^/        | /'
if [ $red -ne 0 ]; then ok "без слушателя проверка параметров красная (код $red)"; else bad "без слушателя проверка параметров осталась зелёной: она не проверяет слушатель" "$(tail -n 6 /tmp/parameters-sabotage.log)"; fi

echo "== Слушатель возвращён: тот же сценарий зелёный"
check "order-service пересоздан как был" recreate
check "order-service здоров со слушателем" wait_healthy order-service 240
PARAMETERS_ONLY=unpaid python3 tools/stand-checks/parameters_checks.py >/tmp/parameters-green.log 2>&1
green=$?
tail -n 6 /tmp/parameters-green.log | sed 's/^/        | /'
if [ $green -eq 0 ]; then ok "со слушателем тот же сценарий зелёный"; else bad "со слушателем сценарий лимита красный (код $green)" "$(grep -E 'ОШИБКА' -A1 /tmp/parameters-green.log | tail -n 6)"; fi
finish
