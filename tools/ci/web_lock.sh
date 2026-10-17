#!/usr/bin/env bash
# Создаёт package-lock.json интерфейса и возвращает его в ту же ветку коммитом (шаг 16 Ф4, решение Р-106).
#
#   tools/ci/web_lock.sh        (запускает web-lock.yml по пушу в ветку ci/web-lock; нужны права contents: write)
#
# Зачем: в рабочей среде разработки реестр npm закрыт, lock-файл там не создать. Скрипт сравнивает lock-файл коммита с package.json
# (`npm ci --dry-run`). Если файла нет или он расходится, собирает новый (`npm install --package-lock-only`) и коммитит. Пуш токеном
# GITHUB_TOKEN новый запуск рабочих процессов не создаёт. Дальше человек переносит файл в рабочую ветку:
#   git fetch origin ci/web-lock && git checkout FETCH_HEAD -- frontend/web-app/package-lock.json
set -euo pipefail
root=$(git rev-parse --show-toplevel)
cd "$root/frontend/web-app"

if [ -f package-lock.json ] && npm ci --ignore-scripts --no-audit --no-fund --dry-run > /dev/null 2>&1; then
  echo "package-lock.json сходится с package.json, новый не нужен"
  exit 0
fi

echo "package-lock.json нет или он не сходится с package.json: собираю новый"
rm -f package-lock.json
npm install --package-lock-only --ignore-scripts --no-audit --no-fund
packages=$(grep -c '"resolved":' package-lock.json || true)
echo "пакетов в новом файле: $packages"

cd "$root"
git config user.name "github-actions"
git config user.email "github-actions@users.noreply.github.com"
git add frontend/web-app/package-lock.json
git commit -m "web: package-lock.json (создан web-lock.yml, пакетов $packages)"
git push origin "HEAD:${GITHUB_REF_NAME}"
echo "::notice title=web-lock::package-lock.json (пакетов $packages) отправлен в ветку ${GITHUB_REF_NAME}"
