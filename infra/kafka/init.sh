#!/bin/bash
# Задание kafka-init: темы и права доступа Kafka (conventions.md 11.3, ADR-022). Выполняется один раз и завершается.
# Темы и права записаны в topics.sh, который создаёт infra/kafka/gen_kafka.py из AsyncAPI. Повторный запуск безопасен:
# готовые темы пропускаются, а о выполненной инициализации говорит тема-метка dgm-init-<хеш спецификации>.
set -euo pipefail

D=/tmp/dgm
mkdir -p "$D"
umask 077
cat /run/secrets/tls_kafka-init.key /run/secrets/tls_kafka-init.crt > "$D/keystore.pem"
cat > "$D/client.properties" <<PROPS
security.protocol=SSL
ssl.keystore.type=PEM
ssl.keystore.location=$D/keystore.pem
ssl.truststore.type=PEM
ssl.truststore.location=/run/secrets/tls_ca.crt
PROPS

B=${KAFKA_BOOTSTRAP:-kafka:9093}
C=$D/client.properties
BIN=/opt/kafka/bin
SPEC=/etc/dgm/kafka/topics.sh
export LOG_DIR=$D/logs
export KAFKA_HEAP_OPTS="-Xms24m -Xmx64m"
export KAFKA_JVM_PERFORMANCE_OPTS="-XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:MaxMetaspaceSize=96m"

hash=$(sed -n 's/^SPEC_HASH=//p' "$SPEC" | head -n 1)
marker="dgm-init-$hash"

# Ожидание брокера: сам список тем заодно проверяет сертификат и права kafka-init
existing=""
for i in $(seq 1 30); do
  if existing=$("$BIN/kafka-topics.sh" --bootstrap-server "$B" --command-config "$C" --list 2>"$D/err"); then break; fi
  echo "ожидание брокера ($i): $(tail -n 1 "$D/err" | cut -c1-160)"
  sleep 3
done
[ -n "$existing" ] || { echo "ОШИБКА: брокер не ответил"; cat "$D/err"; exit 1; }

if grep -qx "$marker" <<<"$existing"; then
  echo "kafka-init: инициализация $hash уже выполнена, тем: $(grep -vc '^dgm-init-' <<<"$existing")"
  exit 0
fi

create_topic() {  # имя партиций cleanup retention
  local name=$1 parts=$2 cleanup=$3 ret=$4
  if grep -qx "$name" <<<"$existing"; then echo "тема есть: $name"; return 0; fi
  local args=(--create --topic "$name" --partitions "$parts" --replication-factor 1 --config "cleanup.policy=$cleanup")
  [ "$ret" = "-" ] || args+=(--config "retention.ms=$ret")
  "$BIN/kafka-topics.sh" --bootstrap-server "$B" --command-config "$C" "${args[@]}" >"$D/out" 2>&1 \
    || { echo "ОШИБКА: тема $name"; cat "$D/out"; exit 1; }
  echo "тема создана: $name"
}

allow() {  # сервис операции-и-ресурсы...
  local svc=$1; shift
  "$BIN/kafka-acls.sh" --bootstrap-server "$B" --command-config "$C" --add --allow-principal "User:$svc" "$@" >"$D/out" 2>&1 \
    || { echo "ОШИБКА: права $svc $*"; cat "$D/out"; exit 1; }
  echo "права: $svc $*" | cut -c1-150
}

# shellcheck source=/dev/null
. "$SPEC"

"$BIN/kafka-topics.sh" --bootstrap-server "$B" --command-config "$C" --create --topic "$marker" --partitions 1 \
  --replication-factor 1 --config retention.ms=86400000 >"$D/out" 2>&1 || { cat "$D/out"; exit 1; }
# Метки прежних версий спецификации больше не нужны
for old in $(grep '^dgm-init-' <<<"$existing" || true); do
  "$BIN/kafka-topics.sh" --bootstrap-server "$B" --command-config "$C" --delete --topic "$old" >/dev/null 2>&1 || true
done
echo "kafka-init: готово, спецификация $hash"
