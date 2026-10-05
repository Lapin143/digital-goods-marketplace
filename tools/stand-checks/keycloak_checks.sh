#!/usr/bin/env bash
# Проверка Keycloak на поднятом стенде (шаг 10 Ф3; NFT-3.0, NFT-3.1, NFT-3.3, ST-13 в части Ф3).
#
#   tools/stand-checks/keycloak_checks.sh     стенд поднят профилями infra, stubs, auth с отладочным файлом (DEBUG=1)
#
# Что проверяется:
#   контейнер   готов, запущен с ограничениями стенда (не root, файловая система только для чтения, без привилегий, 672 МБ без свопа),
#               на хост опубликован только отладочный порт 127.0.0.1:18445, значения секретов не встречаются в окружении и журналах,
#               память в пределах 95% лимита после всех проверок входа
#   шифрование  порт отвечает только по TLS с цепочкой нашего центра, TLS 1.1 и обычный HTTP отклоняются, издатель токенов равен
#               публичному адресу, здоровье и метрики на клиентском порту не отдаются (они на порту управления 9000, наружу не открыт)
#   вход        tools/stand-checks/keycloak_checks.py: настоящий вход по коду авторизации с PKCE для каждой роли, второй фактор,
#               сроки, ротация refresh-токена, защита от перебора, VK ID, письма Keycloak (создаёт тестовых пользователей заново)
#   пароли      в таблице credential базы Keycloak у всех пользователей realm Argon2, у каждого своя соль, паролей в открытом виде нет (NFT-3.1)
# Тестовые пользователи пишутся в secrets/test_users.json (права 0600, каталог не входит в Git).
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
cd "$HERE/../.."
SECRETS=$(cd "${DGM_SECRETS_DIR:-secrets}" && pwd)
NET=${DGM_DATA_NETWORK:-dgm_data}
COMPOSE="docker compose -f compose.yaml -f compose.debug.yaml"
KC=${KC_DEBUG_URL:-https://localhost:18445}
source "$HERE/lib.sh"

cid()  { $COMPOSE --profile '*' ps -a -q "$1"; }
insp() { docker inspect -f "$2" "$1"; }
curl_() { curl -s --max-time 15 --cacert "$SECRETS/tls_ca.crt" "$@"; }
code()  { curl_ -o /dev/null -w '%{http_code}' "$@"; }
key()   { tr -d '\r\n' < "$SECRETS/$1"; }

C=$(cid keycloak)
[ -n "$C" ] || { echo "::error title=keycloak_checks::контейнер keycloak не найден, стенд не поднят профилем auth"; exit 2; }
PG_IMAGE=$($COMPOSE --profile '*' config --images 2>/dev/null | grep -E '^postgres:' | head -n 1)
[ -n "$PG_IMAGE" ] || { echo "::error title=keycloak_checks::не найден образ PostgreSQL в compose.yaml"; exit 2; }

echo "== Состояние и ограничения контейнера"
check "keycloak: healthy" test "$(insp "$C" '{{.State.Health.Status}}')" = healthy
hardened() {
  [ "$(insp "$C" '{{.Config.User}}')" = 1000:0 ] &&
  [ "$(insp "$C" '{{.HostConfig.ReadonlyRootfs}}')" = true ] &&
  [ "$(insp "$C" '{{.HostConfig.CapDrop}}')" = "[ALL]" ] &&
  insp "$C" '{{.HostConfig.SecurityOpt}}' | grep -q 'no-new-privileges' &&
  [ "$(insp "$C" '{{.HostConfig.Privileged}}')" = false ] &&
  [ "$(insp "$C" '{{.HostConfig.Memory}}')" = 704643072 ] &&
  [ "$(insp "$C" '{{.HostConfig.MemorySwap}}')" = 704643072 ]
}
check "пользователь 1000, файловая система только для чтения, без привилегий, 672 МБ без свопа" hardened
ports_ok() { [ "$(docker port "$C" | tr -d '\r' | sort | tr '\n' ' ')" = "8443/tcp -> 127.0.0.1:18445 " ]; }
check "на хост опубликован только отладочный порт 127.0.0.1:18445 (9000 не опубликован)" ports_ok
no_leak() {
  local f v
  for f in db_keycloak keycloak_admin keycloak_client_platform keycloak_vkid_client; do
    v=$(key "$f")
    docker inspect "$C" | grep -qF -- "$v" && { echo "значение $f в настройках контейнера"; return 1; }
    docker logs "$C" 2>&1 | grep -qF -- "$v" && { echo "значение $f в журнале контейнера"; return 1; }
  done
  return 0
}
check "значения секретов не встречаются в окружении, настройках и журналах" no_leak
in_network() { docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}' "$C" | tr ' ' '\n' | grep -E '^dgm_' | sort | tr '\n' ' '; }
nets_ok() { [ "$(in_network)" = "dgm_app dgm_data " ]; }
check "контейнер в сетях app и data, в edge его нет" nets_ok

echo "== Шифрование"
ISSUER_URL="$KC/auth/realms/dgm/.well-known/openid-configuration"
check "порт по TLS: цепочка нашего центра принята, метаданные realm отвечают 200" test "$(code "$ISSUER_URL")" = 200
issuer_ok() { curl_ "$ISSUER_URL" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["issuer"]=="https://localhost:8443/auth/realms/dgm", d["issuer"]; assert d["authorization_endpoint"].startswith("https://localhost:8443/auth/"), d["authorization_endpoint"]'; }
check "издатель и адреса для браузера равны публичному адресу шлюза, а не адресу контейнера" issuer_ok
plain_http() { local c; c=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "http://localhost:18445/auth/"); [ "$c" = 000 ] || [ "$c" = 400 ]; }
check "обычный HTTP на порту не обслуживается" plain_http
expect_fail "клиент без нашего центра сертификат не принимает" 'certificate|SSL' curl -sS --max-time 10 -o /dev/null "$ISSUER_URL"
tls11_refused() { ! curl -s --max-time 10 --cacert "$SECRETS/tls_ca.crt" --tlsv1.0 --tls-max 1.1 -o /dev/null "$ISSUER_URL"; }
check "TLS 1.1 отклоняется (NFT-3.3: не ниже TLS 1.2)" tls11_refused
tls13_ok() { curl_ --tlsv1.3 -o /dev/null -w '%{http_code}' "$ISSUER_URL" | grep -qx 200; }
check "TLS 1.3 принимается" tls13_ok
check "здоровье не отдаётся на клиентском порту" test "$(code "$KC/auth/health/ready")" = 404
check "метрики не отдаются на клиентском порту" test "$(code "$KC/auth/metrics")" = 404

echo "== Вход, роли, второй фактор, сроки, перебор, VK ID, письма"
export KC_TARGET=${KC_TARGET:-$KC} STUBS_TARGET=${STUBS_TARGET:-https://127.0.0.1:18443} STUBS_ADMIN_TARGET=${STUBS_ADMIN_TARGET:-https://127.0.0.1:18444}
export DGM_SECRETS_DIR="$SECRETS"
check "тестовые пользователи созданы" python3 infra/keycloak/provision_test_users.py
OUT=$(python3 tools/stand-checks/keycloak_checks.py 2>&1); RC=$?
echo "$OUT" | tail -n 8
if [ $RC -eq 0 ]; then ok "keycloak_checks.py: все проверки входа и настроек"; else bad "keycloak_checks.py (код $RC)" "$OUT"; fi

echo "== Пароли в базе Keycloak (NFT-3.1)"
pga() {  # SQL от имени администратора в базе keycloak_db
  docker run --rm --network "$NET" -v "$SECRETS:/s:ro" -e PGPASSWORD="$(key db_postgres_admin)" "$PG_IMAGE" \
    psql "host=postgres port=5432 dbname=keycloak_db user=postgres sslmode=verify-full sslrootcert=/s/tls_ca.crt connect_timeout=10" \
    -v ON_ERROR_STOP=1 -tAc "$1"
}
PW="select c.credential_data::json->>'algorithm' as a, c.secret_data::json->>'salt' as s, c.secret_data, c.credential_data
    from credential c join user_entity u on u.id = c.user_id join realm r on r.id = u.realm_id where r.name = 'dgm' and c.type = 'password'"
sql_is() { local want=$1 sql=$2 got; got=$(pga "$sql" 2>&1 | tr -d ' \r\n'); [ "$got" = "$want" ] || { echo "ожидалось $want, получено $got"; return 1; }; }
check "у всех паролей realm dgm алгоритм argon2" sql_is 0 "select count(*) from ($PW) t where a is distinct from 'argon2'"
check "паролей не меньше одиннадцати (тестовые пользователи)" sql_is t "select count(*) >= 11 from ($PW) t"
check "у каждого пароля своя непустая соль" sql_is t "select count(distinct s) = count(*) and bool_and(length(s) > 0) from ($PW) t"
plain_absent() {
  local list
  list=$(python3 -c 'import json; print(",".join("\x27%s\x27" % u["password"] for u in json.load(open("'"$SECRETS"'/test_users.json"))["users"]))')
  sql_is 0 "select count(*) from ($PW) t, unnest(array[$list]) p where strpos(t.secret_data || t.credential_data, p) > 0"
}
check "паролей в открытом виде в таблице credential нет" plain_absent

echo "== Память после проверок"
mem_ok() {
  local pct; pct=$(docker stats --no-stream --format '{{.MemPerc}}' "$C" | tr -d '%')
  local usage; usage="$(docker stats --no-stream --format '{{.MemUsage}}' "$C"), $pct%"
  echo "использование памяти: $usage"
  [ -n "${GITHUB_ACTIONS:-}" ] && echo "::notice title=keycloak память после проверок::$usage"
  python3 -c "import sys; sys.exit(0 if float('$pct') < 95 else 1)"
}
check "память контейнера меньше 95% лимита" mem_ok

finish
