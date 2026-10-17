#!/usr/bin/env bash
# Выдаёт файл в аннотациях задания CI (gzip, base64, части по 60000 знаков), чтобы забрать его без входа в аккаунт GitHub.
#
#   tools/ci/lock_emit.sh package-lock.json
#
# Забирает части tools/ci/lock_fetch.py. Аннотаций на шаг не больше десяти, сообщение не длиннее 64 КБ: файл должен сжиматься не более чем до 360 КБ.
set -euo pipefail
file=${1:?файл}
data=$(gzip -9 -c "$file" | base64 -w0)
size=${#data}
part=60000
parts=$(( (size + part - 1) / part ))
if [ "$parts" -gt 8 ]; then
  echo "::error title=lock_emit::файл слишком велик для аннотаций (частей $parts, можно 8)"
  exit 1
fi
echo "Файл $file: $size знаков в $parts частях"
for ((i = 0; i < parts; i++)); do
  echo "::error title=package-lock.json часть $((i + 1)) из $parts::${data:$((i * part)):$part}"
done
