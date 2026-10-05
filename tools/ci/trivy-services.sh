#!/usr/bin/env bash
# Образы сервисов проекта: сканирование Trivy на уязвимости и секреты (шаг 17 Ф3, цепочка поставок).
#
#   tools/ci/trivy-services.sh [тег образа, по умолчанию ci]
#
# Блокируют уязвимости HIGH и CRITICAL, для которых в реестре уже есть исправленная версия (--ignore-unfixed: то, что исправить нечем, сборку
# не останавливает), и любой найденный секрет. Проверяются все образы, а не до первого красного: в одной аннотации видно всё, что надо поднять.
# В аннотацию попадает по строке на библиотеку: версия в образе, версия с исправлением, номера CVE. Код выхода 1, если нашлось хоть что-то.
set -uo pipefail
cd "$(dirname "$0")/../.."
tag="${1:-ci}"
services=$(make -s --eval='_services: ; @echo $(SERVICES)' _services)
failed=0
for s in $services; do
  out=$(mktemp)
  if ! trivy image --quiet --scanners vuln,secret --severity HIGH,CRITICAL --ignore-unfixed --format json --skip-version-check --no-progress \
       --output "$out" "dgm/$s:$tag" 2>"$out.err"; then
    echo "::error title=Trivy образ $s::сканер завершился с ошибкой: $(tail -n 5 "$out.err" | tr '\n' ' ' | cut -c1-500)"
    failed=1
    continue
  fi
  report=$(python3 - "$s" "$out" <<'PY'
import json, sys
service, path = sys.argv[1:3]
libs, secrets = {}, []
for res in json.load(open(path)).get('Results') or []:
    for v in res.get('Vulnerabilities') or []:
        key = (v['PkgName'], v['InstalledVersion'], v.get('FixedVersion', '?'))
        libs.setdefault(key, []).append('%s %s' % (v['VulnerabilityID'], v['Severity']))
    for sec in res.get('Secrets') or []:
        secrets.append('%s: %s (%s), строка %s' % (res.get('Target'), sec.get('Title'), sec.get('RuleID'), sec.get('StartLine')))
for (pkg, installed, fixed), ids in sorted(libs.items()):
    print('%s %s, исправлено в %s: %s' % (pkg, installed, fixed, ', '.join(ids)))
for line in secrets:
    print('СЕКРЕТ %s' % line)
PY
)
  rm -f "$out" "$out.err"
  if [ -z "$report" ]; then
    echo "ok    dgm/$s:$tag: HIGH и CRITICAL с исправлением и секретов нет"
  else
    failed=1
    echo "ОШИБКА dgm/$s:$tag"
    printf '%s\n' "$report" | sed 's/^/        | /'
    msg=$(printf '%s' "$report" | cut -c1-400 | head -n 20 | sed -e 's/%/%25/g' | sed -e ':a;N;$!ba;s/\n/%0A/g')
    echo "::error title=Trivy образ $s::$msg"
  fi
done
exit "$failed"
