#!/usr/bin/env bash
# Проверка веб-интерфейса на поднятом стенде (шаг 14 Ф3; NFT-3.3, NFT-3.2, NFT-6.0).
#
#   make web-check     стенд поднят набором с профилем gateway (например dev-auth) с отладочным файлом (DEBUG=1)
#
# Что проверяется:
#   контейнер   готов, запущен с ограничениями стенда (не root, файловая система только для чтения, без привилегий, 64 МБ без свопа),
#               один рабочий процесс nginx, только сеть app, на хост ничего не опубликовано, кроме отладочного 127.0.0.1:18447
#   доступ      страницу получает только шлюз: без клиентского сертификата отказ, с сертификатом другого сервиса 403, с сертификатом шлюза 200
#   шифрование  TLS 1.1 отклоняется, 1.2 и 1.3 принимаются, обычный HTTP отклоняется, цепочка нашего центра
#   содержимое  страница со ссылкой на витрину, типы файлов, заголовки безопасности, методы кроме GET и HEAD отклоняются, обход каталогов закрыт
#   журнал      JSON, без аварийных записей nginx
# Страницу через шлюз (с HSTS и общим входом) проверяет gateway_checks.py.
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
cd "$HERE/../.."
SECRETS=$(cd "${DGM_SECRETS_DIR:-secrets}" && pwd)
COMPOSE="docker compose -f compose.yaml -f compose.debug.yaml"
WEB=${WEB_URL:-https://localhost:18447}
source "$HERE/lib.sh"

cid()   { $COMPOSE --profile '*' ps -a -q "$1"; }
insp()  { docker inspect -f "$2" "$1"; }
# curl от имени шлюза (по умолчанию) или другого сервиса: web_curl order-service ...
web_curl() { local who=$1; shift; curl -s --max-time 15 --cacert "$SECRETS/tls_ca.crt" --cert "$SECRETS/tls_$who.crt" --key "$SECRETS/tls_$who.key" "$@"; }
gw_curl()  { web_curl api-gateway "$@"; }

C=$(cid web-app)
[ -n "$C" ] || { echo "::error title=webapp_checks::контейнер web-app не найден, стенд не поднят профилем gateway"; exit 2; }

echo "== Состояние и ограничения контейнера"
check "web-app: healthy" test "$(insp "$C" '{{.State.Health.Status}}')" = healthy
hardened() {
  [ "$(insp "$C" '{{.Config.User}}')" = 10001:10001 ] &&
  [ "$(insp "$C" '{{.HostConfig.ReadonlyRootfs}}')" = true ] &&
  [ "$(insp "$C" '{{.HostConfig.CapDrop}}')" = "[ALL]" ] &&
  insp "$C" '{{.HostConfig.SecurityOpt}}' | grep -q 'no-new-privileges' &&
  [ "$(insp "$C" '{{.HostConfig.Privileged}}')" = false ] &&
  [ "$(insp "$C" '{{.HostConfig.Memory}}')" = 67108864 ] &&
  [ "$(insp "$C" '{{.HostConfig.MemorySwap}}')" = 67108864 ]
}
check "пользователь 10001, файловая система только для чтения, без привилегий, 64 МБ без свопа" hardened
uid_ok() { [ "$(docker exec "$C" id -u)" = 10001 ] && ! docker exec "$C" sh -c 'touch /etc/nginx/x 2>/dev/null'; }
check "процессы идут от 10001, запись в /etc/nginx невозможна" uid_ok
one_worker() { [ "$(docker exec "$C" ps | grep -c 'nginx: worker process')" = 1 ]; }
check "один рабочий процесс nginx" one_worker
ports_ok() { [ "$(docker port "$C" | tr -d '\r' | grep -v '\[::\]' | sort | tr '\n' ' ')" = "8443/tcp -> 127.0.0.1:18447 " ]; }
check "на хост опубликован только отладочный 127.0.0.1:18447" ports_ok
nets_ok() {
  local got
  got=$(docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{println $k}}{{end}}' "$C" | grep -v '^$' | sort | tr '\n' ' ')
  [ "$got" = "dgm_app " ] || { echo "сети контейнера: [$got]"; return 1; }
}
check "контейнер только в сети app" nets_ok
check "web-app не перезапускался и не убит по памяти" test "$(insp "$C" '{{.RestartCount}} {{.State.OOMKilled}}')" = "0 false"
no_secrets_in_env() { ! docker inspect "$C" | grep -qi 'BEGIN .*PRIVATE KEY'; }
check "закрытый ключ не лежит в окружении и настройках контейнера" no_secrets_in_env

echo "== Кто может получить страницу"
no_cert() {
  local code
  code=$(curl -s --max-time 10 --cacert "$SECRETS/tls_ca.crt" -o /dev/null -w '%{http_code}' "$WEB/")
  [ "$code" = 000 ] || [ "$code" = 400 ]
}
check "без клиентского сертификата страницы нет (отказ при подключении или 400)" no_cert
check "сертификат другого сервиса (order-service): 403" test "$(web_curl order-service -o /dev/null -w '%{http_code}' "$WEB/")" = 403
check "сертификат шлюза: 200" test "$(gw_curl -o /dev/null -w '%{http_code}' "$WEB/")" = 200
wrong_ca() {
  # Свой самоподписанный сертификат с тем же именем: цепочка не наш центр, nginx его не принимает
  local d; d=$(mktemp -d)
  openssl req -x509 -newkey rsa:2048 -nodes -keyout "$d/k.pem" -out "$d/c.pem" -days 1 -subj "/O=digital-goods-marketplace/CN=api-gateway" 2>/dev/null
  local code
  code=$(curl -s --max-time 10 --cacert "$SECRETS/tls_ca.crt" --cert "$d/c.pem" --key "$d/k.pem" -o /dev/null -w '%{http_code}' "$WEB/")
  rm -rf "$d"
  [ "$code" = 000 ] || [ "$code" = 400 ] || [ "$code" = 403 ] && [ "$code" != 200 ]
}
check "самоподписанный сертификат с именем api-gateway не принимается" wrong_ca

echo "== Шифрование"
tls11_refused() { ! gw_curl --tlsv1.0 --tls-max 1.1 -o /dev/null "$WEB/"; }
check "TLS 1.1 отклоняется (NFT-3.3: не ниже TLS 1.2)" tls11_refused
check "TLS 1.2 принимается" test "$(gw_curl --tlsv1.2 --tls-max 1.2 -o /dev/null -w '%{http_code}' "$WEB/")" = 200
check "TLS 1.3 принимается" test "$(gw_curl --tlsv1.3 -o /dev/null -w '%{http_code}' "$WEB/")" = 200
plain_http() { local c; c=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "http://localhost:18447/"); [ "$c" = 000 ] || [ "$c" = 400 ]; }
check "обычный HTTP на порту 8443 отклоняется" plain_http
check "сертификат выдан частным центром для имени web-app" bash -c "openssl s_client -connect localhost:18447 -servername web-app -CAfile '$SECRETS/tls_ca.crt' -cert '$SECRETS/tls_api-gateway.crt' -key '$SECRETS/tls_api-gateway.key' -verify_hostname web-app </dev/null 2>&1 | grep -q 'Verify return code: 0'"

echo "== Содержимое"
HEAD_OUT=$(gw_curl -D - -o /tmp/web_index.html "$WEB/")
header() { grep -i "^$1:" <<<"$HEAD_OUT" | head -n 1 | tr -d '\r' | cut -d: -f2- | sed 's/^ *//'; }
check "страница: text/html с кодировкой UTF-8" test "$(header Content-Type)" = "text/html; charset=utf-8"
page_ok() { grep -q 'Маркетплейс цифровых товаров' /tmp/web_index.html && grep -q 'href="/api/v1/products"' /tmp/web_index.html; }
check "на странице название и ссылка на витрину /api/v1/products" page_ok
csp_ok() { header Content-Security-Policy | grep -q "default-src 'none'" && header Content-Security-Policy | grep -q "frame-ancestors 'none'"; }
check "строгая политика содержимого (CSP)" csp_ok
check "X-Content-Type-Options: nosniff" test "$(header X-Content-Type-Options)" = nosniff
check "X-Frame-Options: DENY" test "$(header X-Frame-Options)" = DENY
check "Referrer-Policy: no-referrer" test "$(header Referrer-Policy)" = no-referrer
check "страница не кэшируется (Cache-Control: no-cache)" test "$(header Cache-Control)" = no-cache
check "версия nginx в заголовке Server не раскрывается" test "$(header Server)" = nginx
type_of() { gw_curl -o /dev/null -w '%{content_type}' "$WEB/$1"; }
css_ok() { type_of styles.css | grep -Eq '^text/css(;|$)'; }
check "styles.css: text/css" css_ok
js_ok() { type_of status.js | grep -Eq '^(text|application)/javascript'; }
check "status.js: javascript" js_ok
check "favicon.svg: image/svg+xml" test "$(type_of favicon.svg)" = "image/svg+xml"
check "файлы кэшируются на час (Cache-Control: public, max-age=3600)" test "$(gw_curl -D - -o /dev/null "$WEB/styles.css" | tr -d '\r' | grep -i '^cache-control:' | cut -d: -f2- | sed 's/^ *//')" = "public, max-age=3600"
check "HEAD /: 200" test "$(gw_curl -I -o /dev/null -w '%{http_code}' "$WEB/")" = 200
check "POST /: 405" test "$(gw_curl -X POST -d x=1 -o /dev/null -w '%{http_code}' "$WEB/")" = 405
check "DELETE /: 405" test "$(gw_curl -X DELETE -o /dev/null -w '%{http_code}' "$WEB/")" = 405
check "неизвестный файл: 404" test "$(gw_curl -o /dev/null -w '%{http_code}' "$WEB/no-such-file.html")" = 404
check "/health на порту TLS не отдаётся: 404" test "$(gw_curl -o /dev/null -w '%{http_code}' "$WEB/health")" = 404
traversal() { local c; c=$(gw_curl --path-as-is -o /dev/null -w '%{http_code}' "$WEB/../../etc/passwd"); [ "$c" = 400 ] || [ "$c" = 404 ]; }
check "обход каталогов (/../../etc/passwd) закрыт" traversal
check "конфигурация nginx и секреты по адресу не доступны: 404" test "$(gw_curl --path-as-is -o /dev/null -w '%{http_code}' "$WEB/../../run/secrets/tls_web-app.key")" != 200
big_body() { local c; c=$(head -c 5000 /dev/zero | tr '\0' 'a' | gw_curl -X POST --data-binary @- -o /dev/null -w '%{http_code}' "$WEB/"); [ "$c" = 405 ] || [ "$c" = 413 ]; }
check "тело больше 1 КБ не принимается (405 или 413)" big_body

echo "== Журнал и память"
log_json() {
  local logs lines json
  logs=$(docker logs "$C" 2>&1)
  lines=$(grep -c . <<<"$logs")
  json=$(grep -c '^{"@timestamp"' <<<"$logs")
  echo "строк в журнале $lines, JSON $json"
  [ "$json" -ge 5 ] && grep -q '"logger_name":"nginx.access"' <<<"$logs"
}
check "журнал доступа в формате JSON (logger_name nginx.access)" log_json
no_emerg() { ! docker logs "$C" 2>&1 | grep -Eq '\[(emerg|alert|crit)\]'; }
check "в журнале нет аварийных записей nginx (emerg, alert, crit)" no_emerg
no_query() { gw_curl -o /dev/null "$WEB/?token=SECRETVALUE123" && ! docker logs "$C" 2>&1 | grep -q 'SECRETVALUE123'; }
check "параметры запроса в журнал не попадают" no_query
mem_ok() {
  local pct
  pct=$(docker stats --no-stream --format '{{.MemPerc}}' "$C" | tr -d '%')
  echo "память web-app: $(docker stats --no-stream --format '{{.MemUsage}}' "$C") (${pct}% лимита)"
  awk -v p="$pct" 'BEGIN { exit !(p + 0 <= 60) }'
}
check "память в пределах 60% лимита (64 МБ)" mem_ok
echo "память: $(docker stats --no-stream --format '{{.MemUsage}} ({{.MemPerc}})' "$C")"
rm -f /tmp/web_index.html

finish
