#!/usr/bin/env bash
# Установка зависимостей веб-интерфейса по package-lock.json (шаг 16 Ф4).
#
#   tools/ci/web_install.sh
#
# Обычный путь: `npm ci` (строго по lock-файлу). Если файла нет или он не сходится с package.json, скрипт собирает новый lock-файл
# (`npm install --package-lock-only`), выдаёт его в аннотациях задания (tools/ci/lock_emit.sh) и падает: зависимости фиксируются
# коммитом, а не меняются молча на каждом запуске. В рабочей среде разработки npm недоступен (реестр закрыт), поэтому lock-файл
# забирается из аннотаций командой `python3 tools/ci/lock_fetch.py <ветка>`.
set -uo pipefail
cd "$(dirname "$0")/../../frontend/web-app"

if npm ci --ignore-scripts --no-audit --no-fund; then
  exit 0
fi
echo "npm ci не прошёл: package-lock.json отсутствует или не сходится с package.json. Собираю новый и выдаю его в аннотациях."
npm install --package-lock-only --ignore-scripts --no-audit --no-fund || exit 1
bash ../../tools/ci/lock_emit.sh package-lock.json
exit 1
