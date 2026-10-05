#!/usr/bin/env bash
# Проверка шифрования и прав доступа PostgreSQL, Redis и Kafka на поднятом стенде (ADR-022, ST-03, ST-14).
#
#   tools/stand-checks/infra_security.sh        стенд уже поднят набором с профилем infra (make up SET=dev-min)
#
# Что проверяется, для каждого хранилища и «плохие» и «хорошие» случаи (проверка, которая ни разу не сработала, ничего не гарантирует):
#   PostgreSQL  без шифрования отвергают, неверный пароль отвергают, сервер с сертификатом чужого центра клиент не принимает,
#               верный клиент подключается.
#   Redis       обычный порт закрыт, без пароля и с неверным паролем отвергают, чужой центр не принимается, у пользователя сервиса
#               нет доступа к чужим ключам и опасным командам, у администратора есть.
#   Kafka       клиент без сертификата и клиент с сертификатом чужого центра отвергаются, клиент с верным сертификатом
#               не может писать в чужую тему (ACL), но пишет и читает свою; темы созданы (17).
# Клиенты запускаются контейнерами тех же образов в сети data. Каталог секретов только для чтения.
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
cd "$HERE/../.."
SECRETS=$(cd "${DGM_SECRETS_DIR:-secrets}" && pwd)
NET=${DGM_DATA_NETWORK:-dgm_data}
COMPOSE="docker compose -f compose.yaml"

image() { $COMPOSE --profile '*' config --images 2>/dev/null | grep -E "$1" | head -n 1; }
PG_IMAGE=$(image '^postgres:')
REDIS_IMAGE=$(image '^redis:')
KAFKA_IMAGE=$(image '^apache/kafka:')
[ -n "$PG_IMAGE" ] && [ -n "$REDIS_IMAGE" ] && [ -n "$KAFKA_IMAGE" ] || { echo "::error title=infra_security::не найдены образы в compose.yaml"; exit 2; }

source "$HERE/lib.sh"

# Чужой центр сертификации и клиентский сертификат от него (CN как у настоящего сервиса)
FOREIGN=$(mktemp -d); chmod 755 "$FOREIGN"
trap 'rm -rf "$FOREIGN"' EXIT
openssl ecparam -name prime256v1 -genkey -noout -out "$FOREIGN/ca.key" 2>/dev/null
openssl req -x509 -new -key "$FOREIGN/ca.key" -sha256 -days 2 -subj "/CN=Foreign CA" -out "$FOREIGN/ca.crt" 2>/dev/null
openssl ecparam -name prime256v1 -genkey -noout 2>/dev/null | openssl pkcs8 -topk8 -nocrypt -out "$FOREIGN/client.key" 2>/dev/null
openssl req -new -key "$FOREIGN/client.key" -subj "/CN=order-service" -out "$FOREIGN/client.csr" 2>/dev/null
printf 'extendedKeyUsage=clientAuth\nsubjectAltName=DNS:order-service\n' > "$FOREIGN/ext"
openssl x509 -req -in "$FOREIGN/client.csr" -CA "$FOREIGN/ca.crt" -CAkey "$FOREIGN/ca.key" -CAcreateserial -days 2 -sha256 \
  -extfile "$FOREIGN/ext" -out "$FOREIGN/client.crt" 2>/dev/null
chmod 644 "$FOREIGN"/*

drun() { docker run --rm --network "$NET" -v "$SECRETS:/s:ro" -v "$FOREIGN:/f:ro" "$@"; }

echo "== PostgreSQL"
PGPW=$(cat "$SECRETS/db_postgres_admin")
pg() {  # режим-ssl корневой-сертификат пароль
  drun -e PGPASSWORD="$3" "$PG_IMAGE" psql "host=postgres port=5432 dbname=postgres user=postgres sslmode=$1 sslrootcert=$2 connect_timeout=10" -tAc 'select 1'
}
pg_good() { pg verify-full /s/tls_ca.crt "$PGPW" | grep -qx 1; }
expect_ok   "postgres: TLS verify-full с нашим центром и верным паролем" pg_good
expect_fail "postgres: соединение без шифрования отвергнуто (pg_hba)" 'pg_hba|no encryption|rejects' pg disable /s/tls_ca.crt "$PGPW"
expect_fail "postgres: неверный пароль отвергнут" 'password authentication failed' pg verify-full /s/tls_ca.crt "wrong-password"
expect_fail "postgres: сертификат сервера не от нашего центра не принят клиентом" 'certificate verify failed|unable to get local issuer|self.signed' pg verify-full /f/ca.crt "$PGPW"

echo "== Redis"
rcli() {  # пользователь пароль [аргументы redis-cli...]  (TLS, наш центр)
  local user=$1 pass=$2; shift 2
  drun -e REDISCLI_AUTH="$pass" "$REDIS_IMAGE" redis-cli --tls --cacert /s/tls_ca.crt -h redis --user "$user" "$@"
}
RADMIN=$(cat "$SECRETS/redis_admin"); RCAT=$(cat "$SECRETS/redis_catalog"); RGW=$(cat "$SECRETS/redis_gateway")
redis_admin_ping() { rcli admin "$RADMIN" ping | grep -qx PONG; }
redis_catalog_rw() { rcli catalog "$RCAT" set catalog:probe 1 | grep -qx OK && rcli catalog "$RCAT" get catalog:probe | grep -qx 1; }
redis_plain() { drun "$REDIS_IMAGE" redis-cli -h redis -p 6379 ping; }
redis_foreign_ca() { drun -e REDISCLI_AUTH="$RADMIN" "$REDIS_IMAGE" redis-cli --tls --cacert /f/ca.crt -h redis --user admin ping; }
redis_noauth() { drun "$REDIS_IMAGE" redis-cli --tls --cacert /s/tls_ca.crt -h redis ping; }
expect_ok     "redis: TLS и пароль администратора, PING" redis_admin_ping
expect_denied "redis: без шифрования соединение не устанавливается (порт только TLS)" 'Connection reset|Could not connect|Error|error|Server closed|Broken pipe' redis_plain
expect_denied "redis: неверный пароль отвергнут" 'WRONGPASS|invalid username-password' rcli catalog wrong-password ping
expect_denied "redis: сертификат сервера не от нашего центра не принят" 'certificate verify failed|SSL|error|Error' redis_foreign_ca
expect_denied "redis: без аутентификации команды отвергнуты" 'NOAUTH|Authentication required' redis_noauth
expect_ok     "redis: сервис каталога пишет и читает свои ключи (catalog:*)" redis_catalog_rw
expect_denied "redis: сервис каталога не читает чужие ключи (platform:*)" 'NOPERM|no permissions' rcli catalog "$RCAT" get platform:probe
expect_denied "redis: сервис каталога не выполняет FLUSHALL" 'NOPERM|no permissions' rcli catalog "$RCAT" flushall
expect_denied "redis: сервис каталога не читает настройки (CONFIG)" 'NOPERM|no permissions' rcli catalog "$RCAT" config get maxmemory
# Скрипт Lua в духе ограничителя Spring Cloud Gateway: читает время сервера и пишет ключ своего префикса
redis_gateway_lua() { rcli gateway "$RGW" eval "local t = redis.call('TIME'); redis.call('setex', KEYS[1], 10, t[1]); return 1" 1 'request_rate_limiter.{probe}.tokens' | grep -qx 1; }
expect_ok     "redis: шлюз выполняет скрипт Lua с TIME и записью в свой префикс (request_rate_limiter.*)" redis_gateway_lua
expect_denied "redis: шлюз не читает чужие ключи (catalog:*)" 'NOPERM|no permissions' rcli gateway "$RGW" get catalog:probe
expect_denied "redis: сервис каталога не читает время сервера (TIME разрешён только шлюзу)" 'NOPERM|no permissions' rcli catalog "$RCAT" time
redis_policy() { rcli admin "$RADMIN" config get maxmemory-policy | grep -qx noeviction; }
redis_maxmem() { rcli admin "$RADMIN" config get maxmemory | grep -qx 67108864; }
expect_ok     "redis: политика noeviction" redis_policy
expect_ok     "redis: maxmemory 64 МБ" redis_maxmem

echo "== Kafka"
# Клиент Kafka в контейнере образа. Принципал: none (без сертификата), foreign (сертификат чужого центра) или имя сервиса.
kcli() {  # принципал корневой-сертификат команда
  local who=$1 ca=$2 cmd=$3 ks=""
  case "$who" in
    none) ;;
    foreign) ks='cat /f/client.key /f/client.crt > $D/ks.pem' ;;
    *) ks='cat /s/tls_'"$who"'.key /s/tls_'"$who"'.crt > $D/ks.pem' ;;
  esac
  [ "$who" = none ] || ks="$ks"'; printf "ssl.keystore.type=PEM\nssl.keystore.location=$D/ks.pem\n" >> $D/c.properties'
  drun --entrypoint bash -u 0:0 "$KAFKA_IMAGE" -c "
D=/tmp/c; mkdir -p \$D
printf 'security.protocol=SSL\nssl.truststore.type=PEM\nssl.truststore.location=$ca\ndefault.api.timeout.ms=15000\nrequest.timeout.ms=10000\nmax.block.ms=15000\ndelivery.timeout.ms=15000\nsocket.connection.setup.timeout.max.ms=8000\n' > \$D/c.properties
$ks
export KAFKA_HEAP_OPTS=-Xmx128m LOG_DIR=/tmp/l
$cmd"
}
B=kafka:9093
BIN=/opt/kafka/bin
LIST="$BIN/kafka-topics.sh --bootstrap-server $B --command-config \$D/c.properties --list"
PROD="$BIN/kafka-console-producer.sh --bootstrap-server $B --producer.config \$D/c.properties"
CONS="$BIN/kafka-console-consumer.sh --bootstrap-server $B --consumer.config \$D/c.properties"
DENY='not authorized|AuthorizationException|AUTHORIZATION_FAILED'
SSLFAIL='SSL|handshake|TimeoutException|Timed out|disconnected|Authentication'

k_none()    { kcli none /s/tls_ca.crt "$LIST"; }
k_foreign() { kcli foreign /s/tls_ca.crt "$LIST"; }
expect_denied "kafka: клиент без сертификата отвергнут" "$SSLFAIL" k_none
expect_denied "kafka: клиент с сертификатом чужого центра отвергнут" "$SSLFAIL" k_foreign

topics_count() { local o; o=$(kcli kafka-init /s/tls_ca.crt "$LIST") || { echo "$o"; return 1; }
  echo "$o" | grep -v '^dgm-init-' | grep -c '\.' | grep -qx 17 || { echo "тем не 17:"; echo "$o"; return 1; }
  echo "$o" | grep -q '^dgm-init-' || { echo "нет метки инициализации"; return 1; }; }
expect_ok "kafka: темы созданы (17) и метка инициализации есть" topics_count

k_prod_foreign_topic() { kcli order-service /s/tls_ca.crt "echo probe | $PROD --topic catalog.events"; }
k_prod_own_topic()     { kcli order-service /s/tls_ca.crt "echo probe | $PROD --topic order.events"; }
k_cons_own()           { kcli payment-service /s/tls_ca.crt "$CONS --topic order.events --group payment-service --from-beginning --max-messages 1 --timeout-ms 20000" | grep -qx probe; }
k_cons_publisher()     { kcli order-service /s/tls_ca.crt "$CONS --topic order.events --group order-service --from-beginning --max-messages 1 --timeout-ms 15000"; }
k_cons_foreign_topic() { kcli catalog-service /s/tls_ca.crt "$CONS --topic order.events --group catalog-service --from-beginning --max-messages 1 --timeout-ms 15000"; }
k_cons_foreign_group() { kcli payment-service /s/tls_ca.crt "$CONS --topic order.events --group order-service --from-beginning --max-messages 1 --timeout-ms 15000"; }
expect_denied "kafka: order-service не может писать в тему catalog.events (ACL)" "$DENY" k_prod_foreign_topic
expect_ok     "kafka: order-service пишет в свою тему order.events" k_prod_own_topic
expect_ok     "kafka: payment-service (потребитель по AsyncAPI) читает order.events в своей группе" k_cons_own
expect_denied "kafka: order-service издатель order.events и не потребитель: читать её не может (ACL)" "$DENY" k_cons_publisher
expect_denied "kafka: catalog-service не читает order.events (ACL)" "$DENY" k_cons_foreign_topic
expect_denied "kafka: payment-service не читает в чужой группе order-service (ACL)" "$DENY" k_cons_foreign_group

finish
