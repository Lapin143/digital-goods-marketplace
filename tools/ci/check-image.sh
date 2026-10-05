#!/usr/bin/env bash
# Проверка собранного образа: пользователь без прав, запуск с файловой системой только для чтения.
# Использование: tools/ci/check-image.sh образ [порт приложения, по умолчанию 8080] [static]
# Без третьего параметра контейнер запускается и должен ответить на /actuator/health (шлюз до шага 13).
# С параметром static контейнер не запускается как приложение: сервисы Ф3 без базы, секретов и сертификатов не стартуют, их здоровье
# проверяет стенд (make up, задание kit). Здесь проверяется то, что от стенда не зависит: пользователь, Java 25, jar, файловая система.
set -euo pipefail
image="$1"
port="${2:-8080}"
mode="${3:-health}"
name="check-$$"
cleanup() { docker logs "$name" 2>&1 | tail -n 40 || true; docker rm -f "$name" >/dev/null 2>&1 || true; }
trap cleanup EXIT

uid=$(docker run --rm --entrypoint id "$image" -u)
[ "$uid" = "10001" ] || { echo "образ $image работает от пользователя $uid, ожидался 10001"; exit 1; }
echo "ок: образ $image запускается от UID $uid"

if [ "$mode" = "static" ]; then
  ver=$(docker run --rm --read-only --tmpfs /tmp --cap-drop ALL --entrypoint java "$image" -version 2>&1)
  ver=${ver%%$'\n'*}
  case "$ver" in *'"25'*) echo "ок: $ver";; *) echo "в образе $image Java не 25: $ver"; exit 1;; esac
  docker run --rm --read-only --tmpfs /tmp --cap-drop ALL --entrypoint test "$image" -f /application/application.jar \
    || { echo "в образе $image нет /application/application.jar"; exit 1; }
  echo "ок: в образе $image есть исполняемый jar"
  trap - EXIT
  exit 0
fi

docker run -d --name "$name" --read-only --tmpfs /tmp --cap-drop ALL -p "127.0.0.1:18080:${port}" "$image" >/dev/null
for i in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:18080/actuator/health" || true)
  if [ "$code" = "200" ]; then echo "ок: /actuator/health ответил 200 за ${i} с"; trap - EXIT; docker rm -f "$name" >/dev/null; exit 0; fi
  sleep 1
done
echo "образ $image не ответил на /actuator/health за 60 секунд (последний код: $code)"
exit 1
