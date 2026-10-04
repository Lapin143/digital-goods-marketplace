#!/bin/bash
# Запуск Kafka под UID 1000 с файловой системой только для чтения.
# Штатный сценарий образа настраивает брокер переменными окружения и не умеет PEM-ключи, поэтому конфигурация задана
# файлом server.properties, а запуск выполняет этот сценарий.
set -euo pipefail

D=/tmp/dgm
mkdir -p "$D"
# Kafka читает ключ и сертификат из одного PEM-файла
umask 077
cat /run/secrets/tls_kafka.key /run/secrets/tls_kafka.crt > "$D/keystore.pem"

CFG=/etc/dgm/kafka/server.properties
export LOG_DIR=/tmp/kafka-logs
export KAFKA_HEAP_OPTS="${KAFKA_HEAP_OPTS:--Xms256m -Xmx256m}"
export KAFKA_LOG4J_OPTS="-Dlog4j2.configurationFile=file:/etc/kafka/docker/log4j2.yaml"

# Первый запуск: форматирование каталога данных. Повторные запуски его пропускают.
if [ ! -f /var/lib/kafka/data/meta.properties ]; then
  /opt/kafka/bin/kafka-storage.sh format --cluster-id "$(/opt/kafka/bin/kafka-storage.sh random-uuid)" --config "$CFG" --ignore-formatted
fi

exec /opt/kafka/bin/kafka-server-start.sh "$CFG"
