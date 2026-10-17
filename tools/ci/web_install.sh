#!/usr/bin/env bash
# Установка зависимостей веб-интерфейса по package-lock.json (шаг 16 Ф4).
#
#   tools/ci/web_install.sh
#
# Строго `npm ci`: пакеты и контрольные суммы берутся из lock-файла, файл не меняется. Если файла нет или он не сходится с package.json,
# задание падает. Новый lock-файл создаёт задание web-lock (tools/ci/web_lock.sh) и кладёт в ветку ci/web-lock: в рабочей среде
# разработки реестр npm закрыт, поэтому файл создаёт только CI, а в репозиторий его переносит человек коммитом (Р-106).
set -uo pipefail
cd "$(dirname "$0")/../../frontend/web-app"

if npm ci --ignore-scripts --no-audit --no-fund; then
  exit 0
fi
echo "npm ci не прошёл: package-lock.json отсутствует или не сходится с package.json."
echo "Новый файл собирает задание web-lock и кладёт в ветку ci/web-lock; перенести: git fetch origin ci/web-lock && git checkout FETCH_HEAD -- frontend/web-app/package-lock.json"
exit 1
