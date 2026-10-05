#!/usr/bin/env bash
# Проверка шлюза на поднятом стенде (шаг 13 Ф3; NFT-3.2, NFT-3.3, NFT-6.0, ST-03, ST-14).
#
#   make gateway-check     стенд поднят наборами dev-auth и dev-purchase с отладочным файлом (DEBUG=1), тестовые пользователи созданы
#
# Что проверяется:
#   контейнер   готов, запущен с ограничениями стенда (не root, файловая система только для чтения, без привилегий, 352 МБ без свопа),
#               на хост опубликован порт 8443 и отладочный 127.0.0.1:18446, сети edge, app, data и obs, значения секретов не в окружении и журналах
#   шифрование  цепочка нашего центра, TLS 1.1 и обычный HTTP отклоняются, TLS 1.2 и 1.3 принимаются, клиентский сертификат не нужен
#   запросы     tools/stand-checks/gateway_checks.py: маршруты, ошибки Problem, подделки токенов (ST-03), области, 2FA, размер тела, Keycloak за
#               шлюзом, лимиты частоты на Redis, порт управления по mTLS
#   журнал      JSON, без токенов (check_services.py api-gateway)
#   отказ       Redis остановлен: запросы проходят, метрика растёт; Redis запущен: лимиты возвращаются без перезапуска шлюза
#   память      в пределах 95% лимита после всех проверок
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
cd "$HERE/../.."
SECRETS=$(cd "${DGM_SECRETS_DIR:-secrets}" && pwd)
COMPOSE="docker compose -f compose.yaml -f compose.debug.yaml"
GW=${GATEWAY_URL:-https://localhost:8443}
source "$HERE/lib.sh"

cid()   { $COMPOSE --profile '*' ps -a -q "$1"; }
insp()  { docker inspect -f "$2" "$1"; }
curl_() { curl -s --max-time 15 --cacert "$SECRETS/tls_ca.crt" "$@"; }
key()   { tr -d '\r\n' < "$SECRETS/$1"; }

C=$(cid api-gateway)
[ -n "$C" ] || { echo "::error title=gateway_checks::контейнер api-gateway не найден, стенд не поднят профилем gateway"; exit 2; }

echo "== Состояние и ограничения контейнера"
check "api-gateway: healthy" test "$(insp "$C" '{{.State.Health.Status}}')" = healthy
hardened() {
  [ "$(insp "$C" '{{.Config.User}}')" = 10001:10001 ] &&
  [ "$(insp "$C" '{{.HostConfig.ReadonlyRootfs}}')" = true ] &&
  [ "$(insp "$C" '{{.HostConfig.CapDrop}}')" = "[ALL]" ] &&
  insp "$C" '{{.HostConfig.SecurityOpt}}' | grep -q 'no-new-privileges' &&
  [ "$(insp "$C" '{{.HostConfig.Privileged}}')" = false ] &&
  [ "$(insp "$C" '{{.HostConfig.Memory}}')" = 369098752 ] &&
  [ "$(insp "$C" '{{.HostConfig.MemorySwap}}')" = 369098752 ]
}
check "пользователь 10001, файловая система только для чтения, без привилегий, 352 МБ без свопа" hardened
ports_ok() { [ "$(docker port "$C" | tr -d '\r' | grep -v '\[::\]' | sort | tr '\n' ' ')" = "8443/tcp -> 0.0.0.0:8443 8444/tcp -> 127.0.0.1:18446 " ]; }
check "на хост опубликованы 8443 и отладочный 127.0.0.1:18446 (порт управления), больше ничего" ports_ok
nets_ok() {
  local got
  got=$(docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{println $k}}{{end}}' "$C" | grep -v '^$' | sort | tr '\n' ' ')
  [ "$got" = "dgm_app dgm_data dgm_edge dgm_obs " ] || { echo "сети контейнера: [$got]"; return 1; }
}
check "сети: edge (вход), app (сервисы), data (только Redis) и obs (метрики и трассы)" nets_ok
only_gateway_on_edge() {
  local n
  for n in $(docker network inspect dgm_edge -f '{{range $k,$v := .Containers}}{{$v.Name}} {{end}}'); do
    [ "$n" = "$(insp "$C" '{{.Name}}' | tr -d /)" ] || { echo "в сети edge лишний контейнер $n"; return 1; }
  done
}
check "в сети edge только шлюз" only_gateway_on_edge
no_leak() {
  local f v
  for f in redis_gateway; do
    v=$(key "$f")
    docker inspect "$C" | grep -qF -- "$v" && { echo "значение $f в настройках контейнера"; return 1; }
    docker logs "$C" 2>&1 | grep -qF -- "$v" && { echo "значение $f в журнале контейнера"; return 1; }
  done
  return 0
}
check "пароль Redis не встречается в окружении, настройках и журнале (NFT-3.2)" no_leak

echo "== Шифрование на входе"
expect_fail "клиент без нашего центра сертификат шлюза не принимает" 'certificate|SSL' curl -sS --max-time 10 -o /dev/null "$GW/api/v1/products"
tls11_refused() { ! curl_ --tlsv1.0 --tls-max 1.1 -o /dev/null "$GW/api/v1/products"; }
check "TLS 1.1 отклоняется (NFT-3.3: не ниже TLS 1.2)" tls11_refused
tls12_ok() { curl_ --tlsv1.2 --tls-max 1.2 -o /dev/null -w '%{http_code}' "$GW/api/v1/products" | grep -qx 200; }
check "TLS 1.2 принимается" tls12_ok
tls13_ok() { curl_ --tlsv1.3 -o /dev/null -w '%{http_code}' "$GW/api/v1/products" | grep -qx 200; }
check "TLS 1.3 принимается" tls13_ok
plain_http() { local c; c=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "http://localhost:8443/api/v1/products"); [ "$c" = 000 ] || [ "$c" = 400 ]; }
check "обычный HTTP на порту 8443 отклоняется" plain_http
check "сертификат шлюза выдан частным центром для имени localhost" bash -c "openssl s_client -connect localhost:8443 -servername localhost -CAfile '$SECRETS/tls_ca.crt' -verify_hostname localhost </dev/null 2>&1 | grep -q 'Verify return code: 0'"

echo "== Маршруты, токены, лимиты, порт управления"
python3 "$HERE/gateway_checks.py"
rc=$?
if [ $rc -eq 0 ]; then ok "gateway_checks.py: все проверки запросов"; else bad "gateway_checks.py (код $rc)"; fi

echo "== Журнал и контейнер после проверок запросов"
services_check() { python3 "$HERE/check_services.py" api-gateway; }
check "check_services.py api-gateway: здоров, порты 8443 и 8444, журнал JSON без ERROR, без перезапусков" services_check
no_tokens_in_log() { ! docker logs "$C" 2>&1 | grep -Eq 'eyJhbGciOi|Bearer [A-Za-z0-9._-]{20,}'; }
check "токены доступа не попадают в журнал" no_tokens_in_log

echo "== Отказ открытым: Redis"
python3 "$HERE/gateway_checks.py" outage
rc=$?
if [ $rc -eq 0 ]; then ok "gateway_checks.py outage: отказ открытым и возврат лимитов"; else bad "gateway_checks.py outage (код $rc)"; fi

echo "== Память после проверок"
mem_ok() {
  local pct
  pct=$(docker stats --no-stream --format '{{.MemPerc}}' "$C" | tr -d '%')
  echo "память шлюза: $(docker stats --no-stream --format '{{.MemUsage}}' "$C") (${pct}% лимита)"
  awk -v p="$pct" 'BEGIN { exit !(p + 0 <= 95) }'
}
check "память в пределах 95% лимита" mem_ok
echo "память: $(docker stats --no-stream --format '{{.MemUsage}} ({{.MemPerc}})' "$C")"
check "шлюз не перезапускался и не убит по памяти" test "$(insp "$C" '{{.RestartCount}} {{.State.OOMKilled}}')" = "0 false"

finish
