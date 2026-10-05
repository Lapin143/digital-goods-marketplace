#!/usr/bin/env bash
# Проверка конфигурации стека наблюдения без запуска стенда (шаг 16 Ф3): каждый файл проверяет программа из образа, который его читает.
#
#   make obs-validate     нужен только Docker; секреты и сертификаты создаются, если их нет (make certs secrets)
#
#   Prometheus  promtool check config (в том числе наличие файлов сертификатов из секретов), check rules, модульные тесты правил
#               infra/obs/tests/rules_test.yml: каждое оповещение срабатывает при условии и молчит без него
#   Alertmanager amtool check-config
#   Loki        -verify-config
#   Tempo       -config.verify
#   Alloy       validate (если такой команды в образе нет, проверка пропускается с пометкой: запуск стенда проверит файл в любом случае)
# Образы берутся из compose.yaml, поэтому проверка идёт той же версией, что и стенд.
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
cd "$HERE/../.."
source "$HERE/lib.sh"

[ -f "${DGM_SECRETS_DIR:-secrets}/tls_ca.crt" ] || make --no-print-directory certs secrets >/dev/null
SECRETS=$(cd "${DGM_SECRETS_DIR:-secrets}" && pwd)
OBS=$PWD/infra/obs

image() {   # образ сервиса из compose.yaml
  awk -v s="$1" '$0 == "  " s ":" {f=1; next} f && /^  [a-z-]+:$/ {exit} f && $1 == "image:" {print $2; exit}' compose.yaml
}
PROM=$(image prometheus); AM=$(image alertmanager); LOKI=$(image loki); TEMPO=$(image tempo); ALLOY=$(image alloy)
for v in PROM AM LOKI TEMPO ALLOY; do
  [ -n "${!v}" ] || { echo "::error title=obs_validate::не найден образ $v в compose.yaml"; exit 2; }
done
echo "Образы: $PROM, $AM, $LOKI, $TEMPO, $ALLOY"
RUN="docker run --rm -u $(id -u):$(id -g)"

echo "== Prometheus"
check "promtool check config: файл читается, сертификаты и каталог правил на месте" \
  $RUN --entrypoint promtool -v "$OBS/prometheus:/etc/prometheus:ro" -v "$SECRETS:/run/secrets:ro" "$PROM" check config /etc/prometheus/prometheus.yml
check "promtool check rules: выражения и шаблоны правил верны" \
  $RUN --entrypoint promtool -v "$OBS/prometheus:/etc/prometheus:ro" "$PROM" check rules /etc/prometheus/rules/dgm.yml
check "promtool test rules: оповещения срабатывают и молчат как задумано" \
  $RUN --entrypoint promtool -v "$OBS:/obs:ro" "$PROM" test rules /obs/tests/rules_test.yml

echo "== Alertmanager"
check "amtool check-config: маршруты и приёмники" \
  $RUN --entrypoint amtool -v "$OBS/alertmanager:/etc/alertmanager:ro" "$AM" check-config /etc/alertmanager/alertmanager.yml

echo "== Loki и Tempo"
check "Loki -verify-config" $RUN -v "$OBS/loki:/etc/loki:ro" "$LOKI" -config.file=/etc/loki/loki.yaml -verify-config
check "Tempo -config.verify" $RUN -v "$OBS/tempo:/etc/tempo:ro" "$TEMPO" -config.file=/etc/tempo/tempo.yaml -config.verify

echo "== Alloy"
OUT=$($RUN -v "$OBS/alloy:/etc/alloy:ro" -v "$SECRETS:/run/secrets:ro" "$ALLOY" validate /etc/alloy/config.alloy 2>&1); RC=$?
if [ $RC -eq 0 ]; then
  ok "alloy validate: конфигурация верна"
elif grep -qi 'unknown command' <<<"$OUT"; then
  echo "  пропущено: в образе $ALLOY нет команды validate, файл проверит запуск стенда"
else
  bad "alloy validate (код $RC)" "$OUT"
fi

finish
