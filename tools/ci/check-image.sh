#!/usr/bin/env bash
# Проверка собранного образа сервиса: пользователь без прав, запуск с файловой системой только для чтения,
# ответ /actuator/health. Использование: tools/ci/check-image.sh образ [порт приложения, по умолчанию 8080]
set -euo pipefail
image="$1"
port="${2:-8080}"
name="check-$$"
cleanup() { docker logs "$name" 2>&1 | tail -n 40 || true; docker rm -f "$name" >/dev/null 2>&1 || true; }
trap cleanup EXIT

uid=$(docker run --rm --entrypoint id "$image" -u)
[ "$uid" = "10001" ] || { echo "образ $image работает от пользователя $uid, ожидался 10001"; exit 1; }
echo "ок: образ $image запускается от UID $uid"

docker run -d --name "$name" --read-only --tmpfs /tmp --cap-drop ALL -p "127.0.0.1:18080:${port}" "$image" >/dev/null
for i in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:18080/actuator/health" || true)
  if [ "$code" = "200" ]; then echo "ок: /actuator/health ответил 200 за ${i} с"; trap - EXIT; docker rm -f "$name" >/dev/null; exit 0; fi
  sleep 1
done
echo "образ $image не ответил на /actuator/health за 60 секунд (последний код: $code)"
exit 1
