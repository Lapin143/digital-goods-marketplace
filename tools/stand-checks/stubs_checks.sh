#!/usr/bin/env bash
# Проверка контейнера заглушек внешних систем на поднятом стенде (ADR-015, ADR-022, NFT-3.2, test-strategy раздел 10).
#
#   tools/stand-checks/stubs_checks.sh     стенд поднят профилем stubs с отладочным файлом (DEBUG=1)
#
# Что проверяется (у каждой «хорошей» проверки есть «плохая»):
#   состояние    контейнер готов, запущен с ограничениями стенда (не root, файловая система только для чтения, без привилегий,
#                память 128 МБ), на хост опубликованы только отладочные порты, секретов нет ни в окружении, ни в журналах,
#                в образе нет node_modules, а package.json без зависимостей
#   шифрование   оба HTTP-порта принимают только TLS с проверкой цепочки нашего центра, обычный HTTP не обслуживается, чужой
#                центр не принят, слушают только 8443, 8444 и 1025
#   разделение   команды управления видны только на административном порту, прикладной интерфейс только на прикладном (ADR-015)
#   доступ       у каждого интерфейса свой ключ: без ключа и с ключом соседа отказ
#   SMTP         письмо Keycloak по SMTP принимается и видно в списке писем с разобранной темой
#   вебхуки      заглушка доставляет подписанные вебхуки настоящему получателю по HTTPS: цепочка проверена, подпись верна секретом
#                своего источника (шлюз, письма с ключом, письма без ключа, SMS), метка времени свежая, предъявлен клиентский
#                сертификат. Дефектные режимы (неверная подпись, старая метка) получатель видит
#   устойчивость после перезапуска режимы возвращаются к умолчанию, идентификаторы не повторяются, память в пределах 85% лимита
# Получатель: tools/stand-checks/stubs_receiver.mjs, запускается в образе самой заглушки под именем api-gateway.
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
cd "$HERE/../.."
SECRETS=$(cd "${DGM_SECRETS_DIR:-secrets}" && pwd)
API=${STUBS_DEBUG_API:-https://localhost:18443}
ADM=${STUBS_DEBUG_ADMIN:-https://localhost:18444}
COMPOSE="docker compose -f compose.yaml -f compose.debug.yaml"
RECEIVER=dgm-check-receiver

source "$HERE/lib.sh"

cid()  { $COMPOSE --profile '*' ps -a -q "$1"; }
insp() { docker inspect -f "$2" "$1"; }
curl_() { curl -s --max-time 15 --cacert "$SECRETS/tls_ca.crt" "$@"; }
code()  { curl_ -o /dev/null -w '%{http_code}' "$@"; }            # код ответа
jget()  { python3 -c 'import json,sys; d=json.load(sys.stdin)
for k in sys.argv[1].split("."): d=d[int(k)] if k.isdigit() else d[k]
print(d)' "$1"; }                                                   # поле JSON из стандартного ввода: a.b.0.c
key()   { tr -d '\r\n' < "$SECRETS/$1"; }
jpost() { local url=$1 tok=$2; shift 2; curl_ -X POST "$url" -H "Authorization: Bearer $tok" -H 'Content-Type: application/json' "$@"; }
put_mode() { curl_ -o /dev/null -w '%{http_code}' -X PUT "$ADM/admin/modes/$1" -H 'Content-Type: application/json' -d "$2"; }
reset() { curl_ -o /dev/null -X POST "$ADM/admin/reset"; }
wait_healthy() {
  local i
  for i in $(seq 1 "$2"); do
    [ "$(insp "$1" '{{.State.Health.Status}}' 2>/dev/null)" = healthy ] && return 0
    sleep 1
  done
  return 1
}
wait_log() {  # контейнер шаблон секунд
  local i
  for i in $(seq 1 "$3"); do
    docker logs "$1" 2>&1 | grep -q -- "$2" && return 0
    sleep 1
  done
  return 1
}

ST=$(cid external-stubs)
[ -n "$ST" ] || { echo "::error title=stubs_checks::контейнер external-stubs не найден, стенд не поднят профилем stubs"; exit 2; }
IMAGE=$(insp "$ST" '{{.Config.Image}}')
cleanup() { docker rm -f "$RECEIVER" >/dev/null 2>&1; }
trap cleanup EXIT
cleanup

PAY_KEY=$(key payment_gateway_key); DLV_KEY=$(key delivery_email_key); PLT_KEY=$(key platform_email_key); SMS_KEY=$(key platform_sms_key)

echo "== Состояние и ограничения контейнера"
check "external-stubs: healthy" test "$(insp "$ST" '{{.State.Health.Status}}')" = healthy
hardened() {
  [ "$(insp "$ST" '{{.Config.User}}')" = 10001:10001 ] &&
  [ "$(insp "$ST" '{{.HostConfig.ReadonlyRootfs}}')" = true ] &&
  [ "$(insp "$ST" '{{.HostConfig.CapDrop}}')" = "[ALL]" ] &&
  insp "$ST" '{{.HostConfig.SecurityOpt}}' | grep -q 'no-new-privileges' &&
  [ "$(insp "$ST" '{{.HostConfig.Privileged}}')" = false ] &&
  [ "$(insp "$ST" '{{.HostConfig.Memory}}')" = 134217728 ] &&
  [ "$(insp "$ST" '{{.HostConfig.MemorySwap}}')" = 134217728 ]
}
check "пользователь 10001, файловая система только для чтения, без привилегий, 128 МБ без свопа" hardened
ports_ok() {
  [ "$(docker port "$ST" | tr -d '\r' | sort | tr '\n' ' ')" = "1025/tcp -> 127.0.0.1:11025 8443/tcp -> 127.0.0.1:18443 8444/tcp -> 127.0.0.1:18444 " ]
}
check "на хост опубликованы только отладочные порты 127.0.0.1: 18443, 18444, 11025" ports_ok
no_leak() {
  local f v
  for f in payment_gateway_key payment_webhook_secret delivery_email_key delivery_webhook_secret platform_email_key platform_sms_key platform_webhook_secret; do
    v=$(key "$f")
    docker inspect "$ST" | grep -qF -- "$v" && { echo "значение $f в настройках контейнера"; return 1; }
    docker logs "$ST" 2>&1 | grep -qF -- "$v" && { echo "значение $f в журнале контейнера"; return 1; }
  done
  return 0
}
check "значения секретов не встречаются в окружении, настройках и журналах" no_leak
no_modules() { ! docker run --rm --entrypoint sh "$IMAGE" -c 'test -e /opt/stubs/node_modules || test -e /opt/stubs/package-lock.json'; }
check "в образе нет node_modules и package-lock.json" no_modules
no_deps() { python3 -c 'import json; d=json.load(open("tools/external-stubs/package.json")); assert not d.get("dependencies") and not d.get("devDependencies") and not d.get("optionalDependencies"), "есть зависимости"'; }
check "package.json без зависимостей" no_deps

echo "== Шифрование"
check "прикладной порт по TLS: цепочка нашего центра принята, /health отвечает 200" test "$(code "$API/health")" = 200
check "административный порт по TLS: цепочка нашего центра принята, /health отвечает 200" test "$(code "$ADM/health")" = 200
plain_http() { local c; c=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "http://localhost:18443/health"); [ "$c" = 000 ] || [ "$c" = 400 ]; }
check "обычный HTTP на прикладном порту не обслуживается" plain_http
expect_fail "клиент без нашего центра сертификат не принимает" 'certificate|SSL' curl -sS --max-time 10 -o /dev/null "$API/health"
chain_ok() {  # порт
  timeout 15 openssl s_client -connect "localhost:$1" -servername external-stubs -verify_hostname external-stubs \
    -verify_return_error -CAfile "$SECRETS/tls_ca.crt" </dev/null 2>&1 | grep -q 'Verify return code: 0'
}
check "сертификат на 18443 выдан нашим центром и содержит имя external-stubs" chain_ok 18443
check "сертификат на 18444 выдан нашим центром и содержит имя external-stubs" chain_ok 18444
listeners_ok() {
  local out bad
  out=$(docker exec "$ST" netstat -tln 2>&1) || { echo "$out"; return 1; }
  bad=$(echo "$out" | awk 'NR>2 {print $4}' | grep -v -E '^(127\.[0-9.]+):' | grep -v -E '^(0\.0\.0\.0:(8443|8444|1025)|:::(8443|8444|1025))$' | grep -v '^$')
  [ -z "$bad" ] || { echo "лишние слушающие адреса: $bad"; return 1; }
  for p in 8443 8444 1025; do echo "$out" | awk 'NR>2 {print $4}' | grep -q -E ":$p\$" || { echo "порт $p не слушается"; return 1; }; done
}
check "слушают только порты 8443, 8444 и 1025" listeners_ok

echo "== Разделение прикладного и административного портов"
check "команды управления на прикладном порту не видны (404)" test "$(code "$API/admin/state")" = 404
check "изменение режима на прикладном порту невозможно (404)" test "$(code -X PUT "$API/admin/modes/payment" -d '{}')" = 404
check "прикладной интерфейс на административном порту не виден (404)" test "$(code -X POST "$ADM/payment/v1/payments" -H "Authorization: Bearer $PAY_KEY")" = 404
check "команды управления на административном порту работают (200)" test "$(code "$ADM/admin/state")" = 200

echo "== Ключи интерфейсов"
BODY='{"orderId":"check-order-1","amount":{"amount":129900,"currency":"RUB"}}'
create() { local tok=$1 order=$2; jpost "$API/payment/v1/payments" "$tok" -H "Idempotency-Key: $order" -d "{\"orderId\":\"$order\",\"amount\":{\"amount\":129900,\"currency\":\"RUB\"}}" "${@:3}"; }
reset
check "шлюз: платёж без ключа отвергнут (401)" test "$(code -X POST "$API/payment/v1/payments" -H 'Idempotency-Key: x' -H 'Content-Type: application/json' -d "$BODY")" = 401
check "шлюз: ключ e-mail-провайдера не подходит (401)" test "$(create "$DLV_KEY" order-x -o /dev/null -w '%{http_code}')" = 401
check "шлюз: свой ключ принят, платёж создан (201)" test "$(create "$PAY_KEY" order-y -o /dev/null -w '%{http_code}')" = 201
check "e-mail: ключ SMS-провайдера не подходит (401)" test "$(jpost "$API/email/v1/messages" "$SMS_KEY" -o /dev/null -w '%{http_code}' -d '{}')" = 401
check "SMS: ключ шлюза не подходит (401)" test "$(jpost "$API/sms/v1/messages" "$PAY_KEY" -o /dev/null -w '%{http_code}' -d '{}')" = 401

echo "== SMTP (письма Keycloak)"
reset
smtp_send() {
  python3 - <<'PY'
import smtplib
from email.message import EmailMessage
m = EmailMessage()
m['From'] = 'noreply@dgm.test'; m['To'] = 'buyer@example.test'; m['Subject'] = 'Подтвердите почту'
m.set_content('Перейдите по ссылке для подтверждения')
m.add_alternative('<p>Ссылка подтверждения</p>', subtype='html')
with smtplib.SMTP('127.0.0.1', 11025, timeout=10) as s:
    s.send_message(m)
PY
}
check "письмо по SMTP принято" smtp_send
smtp_seen() {
  local out
  out=$(curl_ "$ADM/admin/emails?sender=smtp") || return 1
  [ "$(echo "$out" | jget items.0.subject)" = "Подтвердите почту" ] && echo "$out" | jget items.0.text | grep -q 'Перейдите по ссылке'
}
check "письмо видно в списке писем, тема и текст разобраны" smtp_seen
check "страница просмотра писем отдаёт список (200)" test "$(code "$API/mail")" = 200

echo "== Вебхуки настоящему получателю по HTTPS"
NET=$(insp "$ST" '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{end}}')
docker run -d --name "$RECEIVER" --network "$NET" --network-alias api-gateway --user "$(id -u):$(id -g)" --read-only --cap-drop ALL \
  --entrypoint node -v "$PWD/tools/stand-checks/stubs_receiver.mjs:/receiver.mjs:ro" \
  -v "$SECRETS/tls_ca.crt:/run/secrets/tls_ca.crt:ro" \
  -v "$SECRETS/tls_api-gateway.crt:/run/secrets/tls_api-gateway.crt:ro" -v "$SECRETS/tls_api-gateway.key:/run/secrets/tls_api-gateway.key:ro" \
  -v "$SECRETS/payment_webhook_secret:/run/secrets/payment_webhook_secret:ro" \
  -v "$SECRETS/delivery_webhook_secret:/run/secrets/delivery_webhook_secret:ro" \
  -v "$SECRETS/platform_webhook_secret:/run/secrets/platform_webhook_secret:ro" \
  "$IMAGE" /receiver.mjs >/dev/null
check "получатель (под именем api-gateway) запущен" wait_log "$RECEIVER" receiver-ready 20
reset
put_mode payment '{"delayMs":0,"webhook":{"retries":0}}' >/dev/null
put_mode email '{"statusDelayMs":0,"webhook":{"retries":0}}' >/dev/null
put_mode sms '{"statusDelayMs":0,"webhook":{"retries":0}}' >/dev/null

line() { docker logs "$RECEIVER" 2>&1 | grep -F -- "$1" | tail -n 1; }       # последняя строка получателя с образцом
wait_line() { local i l; for i in $(seq 1 15); do l=$(line "$1"); [ -n "$l" ] && { echo "$l"; return 0; }; sleep 1; done; return 1; }
field() { python3 -c 'import json,sys; print(json.loads(sys.stdin.read())[sys.argv[1]])' "$1"; }

create "$PAY_KEY" order-hook-1 -o /dev/null
PAID=$(wait_line '"type":"payment.paid"')
check "шлюз: вебхук payment.paid дошёл до получателя" test -n "$PAID"
check "шлюз: путь приёма как в OpenAPI" test "$(echo "$PAID" | field path)" = /api/v1/webhooks/payment-gateway
check "шлюз: подпись верна секретом payment_webhook_secret" test "$(echo "$PAID" | field sigOk)" = True
check "шлюз: метка времени свежая" test "$(echo "$PAID" | field fresh)" = True
check "шлюз: заглушка предъявила клиентский сертификат нашего центра (CN external-stubs)" test "$(echo "$PAID" | field clientCn)/$(echo "$PAID" | field clientVerified)" = external-stubs/True

jpost "$API/email/v1/messages" "$DLV_KEY" -o /dev/null -d '{"to":"buyer@example.test","subject":"Ваш ключ","text":"KEY-1"}'
jpost "$API/email/v1/messages" "$PLT_KEY" -o /dev/null -d '{"to":"buyer@example.test","subject":"Оповещение","text":"x"}'
jpost "$API/sms/v1/messages" "$SMS_KEY" -o /dev/null -d '{"to":"+79991234567","text":"Код: 482913"}'
for p in email-provider-keys email-provider sms-provider; do
  L=$(wait_line "\"path\":\"/api/v1/webhooks/$p\"")
  [ -n "$L" ] || L='{"sigOk":false}'
  check "статус по пути /api/v1/webhooks/$p дошёл, подпись верна секретом своего источника" test "$(echo "$L" | field sigOk)" = True
done

paid_count() { docker logs "$RECEIVER" 2>&1 | grep -cF '"type":"payment.paid"'; }
wait_paid() {  # сколько вебхуков payment.paid должно быть у получателя
  local i; for i in $(seq 1 15); do [ "$(paid_count)" -ge "$1" ] && return 0; sleep 1; done; return 1
}
put_mode payment '{"delayMs":0,"webhook":{"retries":0,"badSignature":true}}' >/dev/null
create "$PAY_KEY" order-hook-2 -o /dev/null
wait_paid 2
BAD=$(docker logs "$RECEIVER" 2>&1 | grep -F '"type":"payment.paid"' | tail -n 1)
check "режим badSignature: получатель видит неверную подпись" test "$(echo "$BAD" | field sigOk)" = False
put_mode payment '{"delayMs":0,"webhook":{"retries":0,"timestampOffsetSeconds":-600}}' >/dev/null
create "$PAY_KEY" order-hook-3 -o /dev/null
wait_paid 3
OLD=$(docker logs "$RECEIVER" 2>&1 | grep -F '"type":"payment.paid"' | tail -n 1)
check "режим timestampOffsetSeconds: подпись верна, метка времени устарела" test "$(echo "$OLD" | field sigOk)/$(echo "$OLD" | field fresh)" = True/False
journal_ok() { curl_ "$ADM/admin/journal?kind=webhook" | python3 -c 'import json,sys; i=json.load(sys.stdin)["items"]; assert any(e.get("outcome")==200 for e in i), "нет успешной доставки в журнале"'; }
check "журнал заглушки хранит доставленные вебхуки с ответом получателя 200" journal_ok

echo "== Перезапуск и память"
ID=$(insp "$ST" '{{.Id}}')
PEAK=$(cat "/sys/fs/cgroup/system.slice/docker-$ID.scope/memory.peak" 2>/dev/null || cat "/sys/fs/cgroup/docker/$ID/memory.peak" 2>/dev/null || true)
TAG1=$(curl_ "$ADM/admin/state" | jget bootTag)
restart_ok() {
  put_mode payment '{"scenario":"hold"}' >/dev/null
  $COMPOSE --profile '*' restart external-stubs >/dev/null 2>&1 || return 1
  wait_healthy "$ST" 60 || { echo "не стал healthy за 60 с после перезапуска"; return 1; }
  local tag2
  tag2=$(curl_ "$ADM/admin/state" | jget bootTag)
  [ "$tag2" != "$TAG1" ] || { echo "метка запуска не изменилась: $tag2"; return 1; }
  [ "$(curl_ "$ADM/admin/modes/payment" | jget scenario)" = success ] || { echo "режим не вернулся к умолчанию"; return 1; }
}
check "после перезапуска режимы по умолчанию, метка запуска (часть идентификаторов) новая" restart_ok
check "external-stubs не убивался по памяти (OOM)" test "$(insp "$ST" '{{.State.OOMKilled}}')" = false
if [ -n "$PEAK" ]; then
  MB=$((PEAK / 1024 / 1024))
  echo "  пик памяти external-stubs до перезапуска: $MB МБ из 128"
  check "пик памяти не выше 85% лимита (108 МБ)" test "$MB" -le 108
else
  echo "  пик памяти недоступен (нет cgroup v2 у демона), проверка пропущена"
fi
docker stats --no-stream --format '  {{.Name}}: {{.MemUsage}}' "$ST"

finish
