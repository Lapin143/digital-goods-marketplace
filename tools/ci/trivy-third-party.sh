#!/usr/bin/env bash
# Сторонние образы стенда: отчёт об известных уязвимостях (шаг 17 Ф3). Образы собираем не мы, поэтому отчёт ничего не блокирует: решение
# об обновлении принимается по Dependabot (docker-compose) и таблице versions.md. Блокируют только образы сервисов проекта (задание scan).
# В аннотацию попадает по строке на образ: число уязвимостей CRITICAL и HIGH, для которых в реестре уже есть исправление.
set -uo pipefail
cd "$(dirname "$0")/../.."
images=$(docker compose --profile '*' config --images 2>/dev/null | sort -u | grep -Ev '^dgm[-/]' | grep -E '[:@]')
report=""
for img in $images; do
  out=$(mktemp)
  if ! docker pull -q "$img" >/dev/null 2>&1 || ! trivy image --quiet --scanners vuln --severity CRITICAL,HIGH --ignore-unfixed --format json \
       --skip-version-check --output "$out" "$img" 2>/dev/null; then
    line="$img: образ не проверен (не скачан или ошибка сканера)"
  else
    line=$(python3 - "$img" "$out" <<'PY'
import json, sys
img, path = sys.argv[1:3]
counts = {'CRITICAL': 0, 'HIGH': 0}
for res in json.load(open(path)).get('Results') or []:
    for v in res.get('Vulnerabilities') or []:
        counts[v.get('Severity')] = counts.get(v.get('Severity'), 0) + 1
print('%s: CRITICAL %d, HIGH %d' % (img, counts['CRITICAL'], counts['HIGH']))
PY
)
  fi
  rm -f "$out"
  echo "$line"
  report+="$line"$'\n'
done
msg=$(printf '%s' "$report" | sed -e 's/%/%25/g' | sed -e ':a;N;$!ba;s/\n/%0A/g')
echo "::notice title=Trivy: сторонние образы стенда (отчёт, исправления в реестре есть)::$msg"
exit 0
