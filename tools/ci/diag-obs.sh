#!/usr/bin/env bash
# Диагностика стека наблюдения при ошибке: состояние и причина остановки контейнеров, строки с ошибками из их журналов, цели Prometheus не в
# состоянии UP. Каждый блок выводится аннотацией ::warning: аннотации видны без доступа к журналам шагов, а журнал шага GitHub отдаёт обрезанным.
set -uo pipefail
cd "$(dirname "$0")/../.."

note() {  # заголовок, затем команда; вывод идёт в аннотацию (первые 14 строк по 360 знаков)
  local title=$1; shift
  local out
  out=$("$@" 2>&1 | cut -c1-360 | head -n 14)
  echo "== $title"; echo "$out"
  echo "::warning title=$title::$(printf '%s' "$out" | sed -e 's/%/%25/g' | sed -e ':a;N;$!ba;s/\n/%0A/g')"
}

states() {
  for s in prometheus alertmanager grafana loki tempo alloy; do
    id=$(docker compose --profile '*' ps -a -q "$s" | head -n1)
    [ -n "$id" ] || { echo "$s: контейнера нет"; continue; }
    docker inspect -f '{{.Name}} {{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}нет{{end}} код={{.State.ExitCode}} oom={{.State.OOMKilled}} перезапусков={{.RestartCount}}' "$id"
  done
}
problems() {  # журнал контейнера: строки с признаками ошибки, а если их нет, последние строки
  local s=$1 out
  out=$(docker compose --profile '*' logs --no-color --no-log-prefix --tail 200 "$s" 2>&1 | grep -iE 'error|fail|panic|denied|fatal|cannot|unable|permission|invalid|unknown|not found' | cut -c1-340 | tail -n 8)
  [ -n "$out" ] || out=$(docker compose --profile '*' logs --no-color --no-log-prefix --tail 6 "$s" 2>&1 | cut -c1-340)
  printf '%s\n' "$out"
}
targets_down() {
  curl -s --max-time 10 http://127.0.0.1:9090/api/v1/targets?state=active | python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
except ValueError:
    print("Prometheus не ответил"); sys.exit()
for t in d["data"]["activeTargets"]:
    if t["health"] != "up":
        print("%s %s: %s" % (t["labels"].get("job"), t["labels"].get("service", t["scrapeUrl"]), (t.get("lastError") or t["health"])[:200]))
' | head -n 12
}

note "obs: состояние контейнеров" states
for s in prometheus alertmanager grafana loki tempo alloy; do
  note "obs: журнал $s" problems "$s"
done
note "obs: цели Prometheus не в состоянии UP" targets_down
exit 0
