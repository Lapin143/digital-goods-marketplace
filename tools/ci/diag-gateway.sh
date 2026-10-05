#!/usr/bin/env bash
# Диагностика шлюза при ошибке задания gateway: записи WARN и ERROR из журнала с началом стека, метрики шлюза, журнал отказов ACL Redis.
# Каждый блок выводится аннотацией ::warning: аннотации видны без доступа к журналам шагов, а журнал шага GitHub отдаёт обрезанным.
set -uo pipefail
cd "$(dirname "$0")/../.."
SECRETS=${DGM_SECRETS_DIR:-secrets}

note() {  # заголовок, затем команда; вывод идёт в аннотацию (первые 16 строк по 420 знаков)
  local title=$1; shift
  local out
  out=$("$@" 2>&1 | cut -c1-420 | head -n 16)
  echo "== $title"; echo "$out"
  echo "::warning title=$title::$(printf '%s' "$out" | sed -e 's/%/%25/g' | sed -e ':a;N;$!ba;s/\n/%0A/g')"
}

log_problems() {
  docker compose --profile '*' logs --no-color --no-log-prefix api-gateway 2>&1 | python3 -c '
import json, sys
seen = set()
for line in sys.stdin:
    try:
        o = json.loads(line)
    except ValueError:
        continue
    if not isinstance(o, dict) or o.get("level") not in ("WARN", "ERROR"):
        continue
    msg = str(o.get("message", ""))[:160]
    if msg in seen:
        continue
    seen.add(msg)
    stack = str(o.get("stack_trace", "")).replace("\n", " | ")[:260]
    print("%s %s: %s %s" % (o.get("level"), str(o.get("logger_name", "")).rsplit(".", 1)[-1], msg, stack))
'
}
gateway_metrics() {
  curl -s --max-time 10 --cacert "$SECRETS/tls_ca.crt" --cert "$SECRETS/tls_order-service.crt" --key "$SECRETS/tls_order-service.key" \
    https://localhost:18446/actuator/prometheus | grep -E '^dgm_gateway' | head -n 14
}
redis_acl_log() {
  docker compose --profile '*' exec -T redis sh -c 'REDISCLI_AUTH="$(cat /run/secrets/redis_admin)" redis-cli --tls --cacert /run/secrets/tls_ca.crt --user admin -h localhost ACL LOG 4 | paste -sd" "'
}
redis_clients() {
  docker compose --profile '*' exec -T redis sh -c 'REDISCLI_AUTH="$(cat /run/secrets/redis_admin)" redis-cli --tls --cacert /run/secrets/tls_ca.crt --user admin -h localhost CLIENT LIST | cut -c1-200'
}

note "шлюз: предупреждения и ошибки журнала" log_problems
note "шлюз: метрики dgm_gateway" gateway_metrics
note "Redis: журнал отказов ACL" redis_acl_log
note "Redis: клиенты" redis_clients
exit 0
