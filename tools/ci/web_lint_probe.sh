#!/usr/bin/env bash
# Контроль правила ESLint «в коде и разметке нет текстов для пользователя» (NFT-7.0, шаг 16 Ф4).
#
#   tools/ci/web_lint_probe.sh     (после установки зависимостей: npm ci в frontend/web-app)
#
# Пишет во временный файл намеренно плохой код и требует, чтобы ESLint его отверг именно этим правилом; чистый файл обязан пройти.
# Правило, которое молча перестало работать, иначе дало бы зелёный запуск при любом тексте в разметке.
set -uo pipefail
cd "$(dirname "$0")/../../frontend/web-app"
probe=src/lint_probe.tsx
out=$(mktemp)
trap 'rm -f "$probe" "$out"' EXIT
rc=0

expect_rejected() {
  printf '%s\n' "$2" > "$probe"
  if npx eslint "$probe" > "$out" 2>&1; then
    echo "ОШИБКА: ESLint пропустил: $1"
    rc=1
  elif ! grep -q 'NFT-7.0' "$out"; then
    echo "ОШИБКА: ESLint отверг не по правилу NFT-7.0: $1"
    cat "$out"
    rc=1
  else
    echo "ok: отвергнуто: $1"
  fi
}

expect_rejected "русский текст между тегами" 'export const A = () => <p>Привет</p>;'
expect_rejected "английский текст между тегами" 'export const A = () => <p>Hello</p>;'
expect_rejected "строковый литерал в атрибуте title" 'export const A = () => <p title="Подсказка">{1}</p>;'
expect_rejected "строковый литерал в aria-label" 'export const A = () => <p aria-label="Метка">{1}</p>;'
expect_rejected "русская строка в коде" "export const message = 'Ошибка';"
expect_rejected "русская строка в шаблоне" 'export const message = `Ошибка ${1}`;'

printf '%s\n' 'export const A = () => <p className="x">{1}</p>;' > "$probe"
if npx eslint "$probe" > "$out" 2>&1; then
  echo "ok: чистый файл проходит"
else
  echo "ОШИБКА: чистый файл не прошёл ESLint"
  cat "$out"
  rc=1
fi
exit $rc
