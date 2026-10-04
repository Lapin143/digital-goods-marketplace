#!/usr/bin/env bash
# Проверка объектного хранилища на поднятом стенде (ADR-024, ADR-022, NFT-3.2, ST-03).
#
#   tools/stand-checks/storage_checks.sh     стенд поднят профилем storage с отладочным файлом (DEBUG=1), нужен aws CLI v2
#
# Что проверяется (для каждого «хорошего» случая есть «плохой»: проверка, которая ни разу не сработала, ничего не гарантирует):
#   состояние   хранилище готово, init завершился с кодом 0, обе стороны запущены с ограничениями стенда (не root, файловая система
#               только для чтения, без привилегий, память 192 МБ), паролей нет ни в окружении, ни в журналах
#   шифрование  порт S3 принимает только TLS с проверкой цепочки нашего центра, обычный HTTP не обслуживается, чужой центр не принят,
#               наружу слушает один порт 9000 (консоли нет)
#   доступ      учётная запись catalog-service пишет, читает, перечисляет и удаляет в своём бакете, загружает потоком файл 12 МБ,
#               получает подписанную ссылку на 5 минут. Просроченная ссылка и ссылка с испорченной подписью отвергаются, анонимный
#               доступ закрыт. В чужом бакете она не пишет, не читает, не перечисляет, не получает подписанную ссылку, не создаёт
#               и не удаляет бакеты. Неверный пароль и неизвестный ключ отвергаются. У корневой записи доступ есть (контроль)
#   устойчивость  повторный запуск init ничего не ломает, смена пароля (в том числе начинающегося со знака «-») применяется
#               повторным запуском init, данные переживают перезапуск хранилища
# Клиент S3 один из стандартных: aws CLI v2 (на раннере GitHub есть, локально: https://docs.aws.amazon.com/cli/).
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
cd "$HERE/../.."
SECRETS=$(cd "${DGM_SECRETS_DIR:-secrets}" && pwd)
EP=${STORAGE_DEBUG_ENDPOINT:-https://localhost:19000}
COMPOSE="docker compose -f compose.yaml -f compose.debug.yaml"
BUCKET=seller-documents
FOREIGN=dgm-check-other
DENIED='AccessDenied|Access Denied|Forbidden|403'
BADKEY='SignatureDoesNotMatch|InvalidAccessKeyId|AccessDenied|Access Denied|Forbidden|403'

source "$HERE/lib.sh"
command -v aws >/dev/null || { echo "::error title=storage_checks::нужен aws CLI v2 (на раннере GitHub он есть)"; exit 2; }

ROOT_USER=$(tr -d '\r\n' < infra/storage/root_user)
ROOT_PASS=$(tr -d '\r\n' < "$SECRETS/storage_admin")
CAT_USER=catalog-service
CAT_PASS=$(tr -d '\r\n' < "$SECRETS/storage_catalog")

WORK=$(mktemp -d)
cleanup() {
  aws_ "$ROOT_USER" "$ROOT_PASS" s3 rb "s3://$FOREIGN" --force >/dev/null 2>&1
  aws_ "$ROOT_USER" "$ROOT_PASS" s3 rm "s3://$BUCKET/check" --recursive >/dev/null 2>&1
  rm -rf "$WORK"
}
trap cleanup EXIT
printf '[default]\ns3 =\n  addressing_style = path\n' > "$WORK/awsconfig"

aws_() {  # ключ пароль аргументы aws...
  local ak=$1 sk=$2; shift 2
  AWS_ACCESS_KEY_ID=$ak AWS_SECRET_ACCESS_KEY=$sk AWS_DEFAULT_REGION=us-east-1 AWS_CA_BUNDLE=$SECRETS/tls_ca.crt \
  AWS_CONFIG_FILE=$WORK/awsconfig AWS_EC2_METADATA_DISABLED=true AWS_PAGER= aws --endpoint-url "$EP" "$@"
}
svc()  { aws_ "$CAT_USER" "$CAT_PASS" "$@"; }       # учётная запись сервиса каталога
root() { aws_ "$ROOT_USER" "$ROOT_PASS" "$@"; }     # корневая запись (контроль и подготовка)
cid()  { $COMPOSE --profile '*' ps -a -q "$1"; }
insp() { docker inspect -f "$2" "$1"; }
http() {  # код ответа curl с нашим центром; аргументы curl
  curl -s -o /dev/null -w '%{http_code}' --max-time 15 --cacert "$SECRETS/tls_ca.crt" "$@"
}
wait_healthy() {  # контейнер, секунд
  local i
  for i in $(seq 1 "$2"); do
    [ "$(insp "$1" '{{.State.Health.Status}}' 2>/dev/null)" = healthy ] && return 0
    sleep 1
  done
  return 1
}

OS=$(cid object-storage); SI=$(cid storage-init)
[ -n "$OS" ] || { echo "::error title=storage_checks::контейнер object-storage не найден, стенд не поднят профилем storage"; exit 2; }

echo "== Состояние и ограничения контейнеров"
check "object-storage: healthy" test "$(insp "$OS" '{{.State.Health.Status}}')" = healthy
check "storage-init: завершился с кодом 0" test "$(insp "$SI" '{{.State.Status}} {{.State.ExitCode}}')" = "exited 0"
hardened() {  # контейнер пользователь память-в-байтах
  [ "$(insp "$1" '{{.Config.User}}')" = "$2" ] &&
  [ "$(insp "$1" '{{.HostConfig.ReadonlyRootfs}}')" = true ] &&
  [ "$(insp "$1" '{{.HostConfig.CapDrop}}')" = "[ALL]" ] &&
  insp "$1" '{{.HostConfig.SecurityOpt}}' | grep -q 'no-new-privileges' &&
  [ "$(insp "$1" '{{.HostConfig.Privileged}}')" = false ] &&
  [ "$(insp "$1" '{{.HostConfig.Memory}}')" = "$3" ] &&
  [ "$(insp "$1" '{{.HostConfig.MemorySwap}}')" = "$3" ]
}
check "object-storage: пользователь 10001, файловая система только для чтения, без привилегий, 192 МБ без свопа" hardened "$OS" 10001:10001 201326592
check "storage-init: пользователь 100:101, файловая система только для чтения, без привилегий, 32 МБ без свопа" hardened "$SI" 100:101 33554432
ports_ok() { [ "$(docker port "$OS" | tr -d '\r')" = "9000/tcp -> 127.0.0.1:19000" ]; }
check "object-storage: на хост опубликован только отладочный порт 127.0.0.1:19000" ports_ok
no_leak() {  # значения паролей не должны встречаться в настройках и журналах обоих контейнеров
  local c v
  for c in "$OS" "$SI"; do
    for v in "$ROOT_PASS" "$CAT_PASS"; do
      docker inspect "$c" | grep -qF -- "$v" && { echo "пароль в настройках контейнера $c"; return 1; }
      docker logs "$c" 2>&1 | grep -qF -- "$v" && { echo "пароль в журнале контейнера $c"; return 1; }
    done
  done
  return 0
}
check "пароли не встречаются в окружении, настройках и журналах контейнеров" no_leak

echo "== Шифрование"
check "S3 по TLS: цепочка нашего центра принята, /health отвечает 200" test "$(http "$EP/health")" = 200
plain_http() { local c; c=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "http://localhost:19000/"); [ "$c" = 000 ] || [ "$c" = 400 ]; }
check "обычный HTTP на порту S3 не обслуживается" plain_http
expect_fail "клиент без нашего центра сертификат не принимает" 'certificate|SSL' curl -sS --max-time 10 -o /dev/null "$EP/health"
chain_ok() {
  timeout 15 openssl s_client -connect localhost:19000 -servername object-storage -verify_hostname object-storage \
    -verify_return_error -CAfile "$SECRETS/tls_ca.crt" </dev/null 2>&1 | grep -q 'Verify return code: 0'
}
check "сертификат сервера выдан нашим центром и содержит имя object-storage" chain_ok
listeners_ok() {
  local out bad
  out=$(docker exec "$OS" netstat -tln 2>&1) || { echo "$out"; return 1; }
  bad=$(echo "$out" | awk 'NR>2 {print $4}' | grep -v -E '^(127\.0\.0\.1|\[?::1\]?):' | grep -v -E '^(0\.0\.0\.0|:::|\[::\]):9000$' | grep -v '^$')
  [ -z "$bad" ] || { echo "лишние слушающие адреса: $bad"; return 1; }
  echo "$out" | awk 'NR>2 {print $4}' | grep -q -E ':9000$'
}
check "снаружи слушает только порт 9000 (консоли и других портов нет)" listeners_ok

echo "== Учётная запись catalog-service в своём бакете"
head -c 12000000 /dev/urandom > "$WORK/blob"
SHA=$(sha256sum "$WORK/blob" | cut -d' ' -f1)
echo "документ" > "$WORK/small"
put_stream() { svc s3 cp - "s3://$BUCKET/check/blob" --expected-size 12000000 --no-progress < "$WORK/blob"; }
check "загрузка потоком 12 МБ (многочастная)" put_stream
get_stream() { [ "$(svc s3 cp "s3://$BUCKET/check/blob" - --no-progress 2>/dev/null | sha256sum | cut -d' ' -f1)" = "$SHA" ]; }
check "скачивание потоком, контрольная сумма совпадает" get_stream
list_own() { svc s3 ls "s3://$BUCKET/check/" | grep -q blob; }
check "перечисление объектов своего бакета" list_own
URL=$(svc s3 presign "s3://$BUCKET/check/blob" --expires-in 300)
presigned() {
  local c
  c=$(curl -s -o "$WORK/dl" -w '%{http_code}' --max-time 30 --cacert "$SECRETS/tls_ca.crt" "$URL")
  echo "HTTP $c"
  [ "$c" = 200 ] && [ "$(sha256sum "$WORK/dl" | cut -d' ' -f1)" = "$SHA" ]
}
check "подписанная ссылка на 5 минут отдаёт тот же файл" presigned
URL1=$(svc s3 presign "s3://$BUCKET/check/blob" --expires-in 1)
sleep 3
expired() { local c; c=$(http "$URL1"); echo "HTTP $c"; [ "$c" = 403 ] || [ "$c" = 400 ]; }
check "просроченная подписанная ссылка отвергается" expired
BADURL=$(echo "$URL" | sed -E 's/(X-Amz-Signature=)[0-9a-f]{4}/\10000/')
tampered() { local c; c=$(http "$BADURL"); echo "HTTP $c"; [ "$c" = 403 ]; }
check "ссылка с испорченной подписью отвергается" tampered
anonymous() { local c; c=$(http "$EP/$BUCKET/check/blob"); echo "HTTP $c"; [ "$c" = 403 ]; }
check "анонимный доступ к объекту закрыт" anonymous
delete_own() { svc s3 rm "s3://$BUCKET/check/blob" --no-progress >/dev/null && [ "$(svc s3 ls "s3://$BUCKET/check/" | wc -l)" = 0 ]; }
check "удаление объекта своим ключом" delete_own

echo "== За пределами своего бакета"
root s3 mb "s3://$FOREIGN" >/dev/null 2>&1
root s3 cp "$WORK/small" "s3://$FOREIGN/secret" --no-progress >/dev/null 2>&1
check "контроль: корневая запись создаёт чужой бакет и пишет в него" root s3 ls "s3://$FOREIGN/secret"
expect_denied "запись в чужой бакет запрещена" "$DENIED" svc s3 cp "$WORK/small" "s3://$FOREIGN/x" --no-progress
expect_denied "чтение из чужого бакета запрещено" "$DENIED" svc s3 cp "s3://$FOREIGN/secret" - --no-progress
expect_denied "перечисление чужого бакета запрещено" "$DENIED" svc s3 ls "s3://$FOREIGN"
expect_denied "создание бакета запрещено" "$DENIED" svc s3 mb s3://dgm-check-hacker
expect_denied "удаление своего бакета запрещено" "$DENIED" svc s3 rb "s3://$BUCKET"
FURL=$(svc s3 presign "s3://$FOREIGN/secret" --expires-in 300)
foreign_url() { local c; c=$(http "$FURL"); echo "HTTP $c"; [ "$c" = 403 ]; }
check "подписанная ссылка учётной записи на чужой бакет не работает" foreign_url
expect_denied "неверный пароль отвергается" "$BADKEY" aws_ "$CAT_USER" "wrong-$CAT_PASS" s3 ls "s3://$BUCKET"
expect_denied "неизвестный ключ доступа отвергается" "$BADKEY" aws_ no-such-user "$CAT_PASS" s3 ls "s3://$BUCKET"
check "контроль: корневая запись пишет и в бакет документов" root s3 cp "$WORK/small" "s3://$BUCKET/check/root" --no-progress

echo "== Повторный запуск init, смена пароля, перезапуск"
rerun_init() {  # окружение: DGM_SECRETS_DIR при необходимости
  local out
  out=$($COMPOSE --profile '*' run -T --rm --no-deps storage-init 2>&1) || { echo "$out" | tail -n 12; return 1; }
  echo "$out" | tail -n 2
  echo "$out" | grep -q 'на месте'
}
check "повторный запуск init завершается успешно" rerun_init
own_rw() { svc s3 cp "$WORK/small" "s3://$BUCKET/check/again" --no-progress >/dev/null && svc s3 cp "s3://$BUCKET/check/again" - --no-progress | grep -q документ; }
check "после повторного init учётная запись по-прежнему работает в своём бакете" own_rw
expect_denied "после повторного init чужой бакет по-прежнему закрыт" "$DENIED" svc s3 cp "$WORK/small" "s3://$FOREIGN/x" --no-progress

# Политику из репозитория init применяет заново: подменяем её узкой (только чтение чужого бакета), доступ пропадает, init возвращает его
narrow_policy() {
  cat > "$WORK/narrow.json" <<'JSON'
{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":["s3:GetObject"],"Resource":["arn:aws:s3:::dgm-check-other/*"]}]}
JSON
  chmod 644 "$WORK/narrow.json"
  $COMPOSE --profile '*' run -T --rm --no-deps -v "$WORK/narrow.json:/narrow.json:ro" --entrypoint sh storage-init -c '
    rc alias set --ca-bundle /run/secrets/tls_ca.crt -- s https://object-storage:9000 "$(cat /etc/dgm/storage/root_user)" "$(cat /run/secrets/storage_admin)" >/dev/null &&
    rc admin policy create s seller-documents-rw /narrow.json'
}
own_put_denied() {  # ждём применения политики до 20 с
  local i
  for i in $(seq 1 20); do
    svc s3 cp "$WORK/small" "s3://$BUCKET/check/policy" --no-progress >/dev/null 2>&1 || return 0
    sleep 1
  done
  echo "запись в свой бакет всё ещё разрешена после подмены политики"; return 1
}
own_put_allowed() {
  local i
  for i in $(seq 1 20); do
    svc s3 cp "$WORK/small" "s3://$BUCKET/check/policy" --no-progress >/dev/null 2>&1 && return 0
    sleep 1
  done
  echo "запись в свой бакет не вернулась после init"; return 1
}
check "подмена политики узкой выполнена" narrow_policy
check "после подмены политики запись в свой бакет запрещена" own_put_denied
check "init возвращает политику из репозитория" rerun_init
check "после init запись в свой бакет снова разрешена" own_put_allowed

# Новый пароль начинается со знака «-»: так проверяется и разделитель «--» в init
NEWPASS="-$(python3 -c 'import secrets; print(secrets.token_urlsafe(24))')"
ALT="$WORK/secrets"; cp -r "$SECRETS" "$ALT"; printf '%s' "$NEWPASS" > "$ALT/storage_catalog"; chmod 644 "$ALT/storage_catalog"
rotate() { DGM_SECRETS_DIR=$ALT rerun_init; }
check "init с новым паролем (начинается с «-») выполняется" rotate
expect_denied "старый пароль после смены отвергается" "$BADKEY" aws_ "$CAT_USER" "$CAT_PASS" s3 ls "s3://$BUCKET"
new_ok() { aws_ "$CAT_USER" "$NEWPASS" s3 cp "s3://$BUCKET/check/again" - --no-progress | grep -q документ; }
check "новый пароль принят и работает" new_ok
check "init с прежним паролем возвращает его" rerun_init
back_ok() { svc s3 cp "s3://$BUCKET/check/again" - --no-progress | grep -q документ; }
check "прежний пароль снова работает" back_ok
expect_denied "пароль, поставленный на время проверки, отвергнут" "$BADKEY" aws_ "$CAT_USER" "$NEWPASS" s3 ls "s3://$BUCKET"

# Пик памяти читается до перезапуска: после него счётчик cgroup начинается заново
ID=$(insp "$OS" '{{.Id}}')
PEAK=$(cat "/sys/fs/cgroup/system.slice/docker-$ID.scope/memory.peak" 2>/dev/null || cat "/sys/fs/cgroup/docker/$ID/memory.peak" 2>/dev/null || true)

restart_keeps_data() {
  $COMPOSE --profile '*' restart object-storage >/dev/null 2>&1 || return 1
  wait_healthy "$OS" 90 || { echo "хранилище не стало healthy за 90 с после перезапуска"; return 1; }
  local i
  for i in $(seq 1 30); do
    svc s3 cp "s3://$BUCKET/check/again" - --no-progress 2>/dev/null | grep -q документ && return 0
    sleep 1
  done
  echo "данные не читаются после перезапуска"; return 1
}
check "данные на месте после перезапуска хранилища" restart_keeps_data

echo "== Память"
check "object-storage не убивался по памяти (OOM)" test "$(insp "$OS" '{{.State.OOMKilled}}')" = false
if [ -n "$PEAK" ]; then
  MB=$((PEAK / 1024 / 1024))
  echo "  пик памяти object-storage до перезапуска: $MB МБ из 192"
  check "пик памяти не выше 85% лимита (163 МБ)" test "$MB" -le 163
else
  echo "  пик памяти недоступен (нет cgroup v2 у демона), проверка пропущена"
fi
docker stats --no-stream --format '  {{.Name}}: {{.MemUsage}}' "$OS"

finish
