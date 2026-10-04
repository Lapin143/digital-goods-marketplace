#!/usr/bin/env bash
# Спайк выбора объектного хранилища (ADR-024, Ф3, шаг 8). Запускается только в CI, в ветке ci/spike.
#
#   tools/ci/storage-spike.sh recon <кандидат>   образ, пользователь, порты, ключи командной строки (для настройки)
#   tools/ci/storage-spike.sh run <кандидат>     поднять с ограничениями стенда и прогнать проверки S3
# Кандидаты: seaweedfs, rustfs, garage. Результат печатается аннотациями (лимит 10 на шаг), не падает на отдельной проверке.
set -uo pipefail

SEAWEED=chrislusf/seaweedfs:4.48
RUSTFS=rustfs/rustfs:1.0.1
GARAGE=dxflrs/garage:v2.4.1

note() {  # заголовок, файл: аннотации по 35 строк, до 9 штук
  local title="$1" f="$2" n=0
  split -l 35 -d "$f" "$f.part."
  for p in "$f".part.*; do
    n=$((n+1)); [ $n -gt 9 ] && break
    msg=$(tr -d '\r' < "$p" | sed -e 's/\x1b\[[0-9;]*[A-Za-z]//g' | tr -d '\000-\010\013\014\016-\037' | cut -c1-230 | sed -e 's/%/%25/g' | sed -e ':a;N;$!ba;s/\n/%0A/g')
    echo "::notice title=${title} ${n}::${msg}"
  done
}

RC=rustfs/rc:v0.1.36
image_of() { case "$1" in seaweedfs) echo $SEAWEED;; rustfs) echo $RUSTFS;; garage) echo $GARAGE;; rc) echo $RC;; *) echo "неизвестный кандидат $1" >&2; exit 64;; esac; }

recon() {
  local c=$1 img o; img=$(image_of "$c"); o=$(mktemp)
  docker pull -q "$img" >/dev/null 2>&1 || { echo "::error title=образ $c::не скачивается $img"; exit 1; }
  {
    echo "== $img"
    docker image inspect "$img" --format 'Размер {{.Size}} байт; User={{.Config.User}}; EP={{json .Config.Entrypoint}}; CMD={{json .Config.Cmd}}; Ports={{json .Config.ExposedPorts}}; Vol={{json .Config.Volumes}}'
    docker image inspect "$img" --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -v '^PATH='
    echo "== оболочка и утилиты"
    docker run --rm --entrypoint sh "$img" -c 'id; ls -ldn /data /var/lib 2>&1 | head -3; which sh bash wget curl nc netstat ss 2>&1' 2>&1 | head -12
    case "$c" in
      seaweedfs)
        echo "== /entrypoint.sh"
        docker run --rm --entrypoint cat "$img" /entrypoint.sh 2>&1 | grep -v '^\s*#' | grep -v '^\s*$' | cut -c1-170 | head -70
        echo "== weed server -h: volume, master, filer, прочее"
        docker run --rm "$img" server -h 2>&1 | grep -E -i '^\s+-(volume\.(max|port|ip|index|dir|preallocate|minFreeSpace)|master\.(volumeSizeLimitMB|volumePreallocate|defaultReplication)|filer\.(maxMB|defaultReplicaPlacement|disableDirListing)|memprofile|cpuprofile|disk|dataCenter|rack|tls|s3\.(iam|config|port)|webdav|mq|admin)' | cut -c1-150 | head -40 ;;
      rustfs)
        echo "== rustfs server --help"
        docker run --rm -e RUSTFS_ACCESS_KEY=x -e RUSTFS_SECRET_KEY=y "$img" server --help 2>&1 | grep -v -E '^(WARNING|Initializing|Starting)' | cut -c1-200 | head -70
        echo "== rustfs tls --help"
        docker run --rm -e RUSTFS_ACCESS_KEY=x -e RUSTFS_SECRET_KEY=y "$img" tls --help 2>&1 | grep -v -E '^(WARNING|Initializing|Starting)' | cut -c1-200 | head -25
        echo "== entrypoint.sh"
        docker run --rm --entrypoint cat "$img" /entrypoint.sh 2>&1 | grep -v '^\s*#' | grep -v '^\s*$' | cut -c1-170 | head -50
        docker pull -q rustfs/rc:v0.1.36 >/dev/null 2>&1
        echo "== rc --help"
        docker run --rm rustfs/rc:v0.1.36 --help 2>&1 | cut -c1-160 | head -40
        echo "== rc admin --help"
        docker run --rm rustfs/rc:v0.1.36 admin --help 2>&1 | cut -c1-160 | head -30
        echo "== rc admin user/policy"
        docker run --rm rustfs/rc:v0.1.36 admin user --help 2>&1 | cut -c1-160 | head -15
        docker run --rm rustfs/rc:v0.1.36 admin policy --help 2>&1 | cut -c1-160 | head -15 ;;
      rc)
        for h in "alias set" "admin user add" "admin policy create" "admin policy attach" "bucket create" "object pipe"; do
          echo "== rc $h --help"
          docker run --rm "$img" $h --help 2>&1 | cut -c1-170 | head -28
        done ;;
      garage)
        echo "== garage --help"
        docker run --rm --entrypoint /garage "$img" --help 2>&1 | cut -c1-150 | head -30
        echo "== garage key --help"
        docker run --rm --entrypoint /garage "$img" key --help 2>&1 | cut -c1-150 | head -20
        echo "== garage bucket allow --help"
        docker run --rm --entrypoint /garage "$img" bucket allow --help 2>&1 | cut -c1-150 | head -25
        echo "== garage layout --help"
        docker run --rm --entrypoint /garage "$img" layout --help 2>&1 | cut -c1-150 | head -20 ;;
    esac
  } > "$o" 2>&1
  note "recon $c" "$o"
}

# ---------------------------------------------------------------------------------------------------------------
# Прогон: ограничения стенда, TLS, учётная запись на один бакет, S3-вызовы, память
# ---------------------------------------------------------------------------------------------------------------
NAME=spike-store
EP=https://localhost:9000
RESULTS=$(mktemp)
res() {  # название, код (0 успешно), подробность
  if [ "$2" -eq 0 ]; then echo "ЕСТЬ   $1${3:+ ($3)}" >> "$RESULTS"; else echo "НЕТ    $1${3:+ ($3)}" >> "$RESULTS"; fi
}
rnd() { python3 -c "import secrets,string;print(''.join(secrets.choice(string.ascii_letters+string.digits) for _ in range($1)))"; }

make_certs() {
  # Как в нашем центре сертификации: корневой сертификат (CA:TRUE) и сертификат сервера от него (CA:FALSE). Самоподписанный сертификат
  # с CA:TRUE в роли сертификата сервера отвергают клиенты на rustls (webpki: CaUsedAsEndEntity), поэтому спайк проверяет настоящую цепочку.
  mkdir -p "$W/certs"
  openssl ecparam -name prime256v1 -genkey -noout -out "$W/ca.key" 2>/dev/null
  openssl req -x509 -new -key "$W/ca.key" -sha256 -days 2 -subj "/CN=Spike CA" -addext "basicConstraints=critical,CA:TRUE" \
    -addext "keyUsage=critical,keyCertSign,cRLSign" -out "$W/certs/ca.pem" 2>/dev/null
  openssl ecparam -name prime256v1 -genkey -noout 2>/dev/null | openssl pkcs8 -topk8 -nocrypt -out "$W/certs/key.pem" 2>/dev/null
  openssl req -new -key "$W/certs/key.pem" -subj "/CN=object-storage" -out "$W/server.csr" 2>/dev/null
  printf 'basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:object-storage,DNS:localhost,IP:127.0.0.1\n' > "$W/ext"
  openssl x509 -req -in "$W/server.csr" -CA "$W/certs/ca.pem" -CAkey "$W/ca.key" -CAcreateserial -days 2 -sha256 -extfile "$W/ext" -out "$W/certs/cert.pem" 2>/dev/null
  cp "$W/certs/cert.pem" "$W/certs/rustfs_cert.pem"; cp "$W/certs/key.pem" "$W/certs/rustfs_key.pem"
  chmod 755 "$W/certs"; chmod 644 "$W/certs"/*
}

# Ограничения контейнера как на стенде (c4-deployment, принципы): не root, без привилегий, файловая система только для чтения, 192 МБ без свопа
HARDEN=(--memory 192m --memory-swap 192m --read-only --cap-drop ALL --security-opt no-new-privileges --user 10001:10001
        --tmpfs /tmp:uid=10001,gid=10001,mode=1777,size=32m -p 127.0.0.1:9000:9000)

aws_() {  # ключ секрет аргументы aws...
  local ak=$1 sk=$2; shift 2
  AWS_ACCESS_KEY_ID=$ak AWS_SECRET_ACCESS_KEY=$sk AWS_DEFAULT_REGION=us-east-1 AWS_CA_BUNDLE=$W/certs/ca.pem \
  AWS_CONFIG_FILE=$W/awsconfig AWS_EC2_METADATA_DISABLED=true AWS_PAGER= aws --endpoint-url "$EP" "$@"
}

wait_ready() {
  local i code
  for i in $(seq 1 90); do
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 --cacert "$W/certs/ca.pem" "$EP/" 2>/dev/null)
    [ -n "$code" ] && [ "$code" != 000 ] && { echo "$i"; return 0; }
    docker ps -q -f "name=$NAME" | grep -q . || { echo "контейнер остановился" >&2; return 1; }
    sleep 1
  done
  return 1
}

start_seaweedfs() {
  cat > "$W/s3.json" <<JSON
{"identities":[
 {"name":"admin","credentials":[{"accessKey":"$ADM_AK","secretKey":"$ADM_SK"}],"actions":["Admin","Read","Write","List","Tagging"]},
 {"name":"catalog","credentials":[{"accessKey":"$CAT_AK","secretKey":"$CAT_SK"}],"actions":["Read:docs","Write:docs","List:docs","Tagging:docs"]}]}
JSON
  chmod 644 "$W/s3.json"
  docker run -d --name $NAME "${HARDEN[@]}" -e GOMEMLIMIT=128MiB -v "$W/data:/data" -v "$W/certs:/certs:ro" -v "$W/s3.json:/etc/s3.json:ro" \
    "$SEAWEED" server -dir=/data -ip=127.0.0.1 -ip.bind=127.0.0.1 -master.volumeSizeLimitMB=64 -volume.max=8 \
    -metricsPort=0 -s3.port.iceberg=0 -s3.port.lance=0 -s3 -s3.config=/etc/s3.json -s3.port=0 -s3.port.https=9000 -s3.ip.bind=0.0.0.0 -s3.cert.file=/certs/cert.pem -s3.key.file=/certs/key.pem
}
provision_seaweedfs() { :; }

start_rustfs() {
  mkdir -p "$W/s"; printf '%s' "$ADM_AK" > "$W/s/ak"; printf '%s' "$ADM_SK" > "$W/s/sk"; chmod 755 "$W/s"; chmod 644 "$W/s"/*
  docker run -d --name $NAME "${HARDEN[@]}" --tmpfs /logs:uid=10001,gid=10001,mode=0755,size=16m \
    -e RUSTFS_ACCESS_KEY_FILE=/run/s/ak -e RUSTFS_SECRET_KEY_FILE=/run/s/sk -e RUSTFS_TLS_PATH=/certs -e RUSTFS_CONSOLE_ENABLE=false \
    -e RUSTFS_ADDRESS=:9000 -v "$W/data:/data" -v "$W/certs:/certs:ro" -v "$W/s:/run/s:ro" "$RUSTFS"
}
provision_rustfs() {
  cat > "$W/policy.json" <<JSON
{"Version":"2012-10-17","Statement":[
 {"Effect":"Allow","Action":["s3:GetObject","s3:PutObject","s3:DeleteObject","s3:AbortMultipartUpload","s3:ListMultipartUploadParts"],"Resource":["arn:aws:s3:::docs/*"]},
 {"Effect":"Allow","Action":["s3:ListBucket","s3:GetBucketLocation","s3:ListBucketMultipartUploads"],"Resource":["arn:aws:s3:::docs"]}]}
JSON
  chmod 644 "$W/policy.json"
  docker run --rm --network host -v "$W/certs:/certs:ro" -v "$W/policy.json:/policy.json:ro" --entrypoint sh "$RC" -c "
set -e
rc alias set s https://localhost:9000 '$ADM_AK' '$ADM_SK' --ca-bundle /certs/ca.pem
rc bucket create s/docs
rc bucket create s/other
rc admin policy create s docs-rw /policy.json
rc admin user add s '$CAT_AK' '$CAT_SK'
rc admin policy attach s docs-rw --user '$CAT_AK'
" 2>&1
}

start_garage() {
  cat > "$W/garage.toml" <<TOML
metadata_dir = "/data/meta"
data_dir = "/data/blocks"
db_engine = "sqlite"
replication_factor = 1
rpc_bind_addr = "127.0.0.1:3901"
rpc_secret = "$(openssl rand -hex 32)"
[s3_api]
s3_region = "us-east-1"
api_bind_addr = "0.0.0.0:9000"
[admin]
api_bind_addr = "127.0.0.1:3903"
TOML
  chmod 644 "$W/garage.toml"
  docker run -d --name $NAME "${HARDEN[@]}" -v "$W/data:/data" -v "$W/garage.toml:/etc/garage.toml:ro" "$GARAGE"
}
provision_garage() {
  local g="docker exec $NAME /garage" id out
  for i in 1 2 3 4 5 6 7 8 9 10; do id=$($g node id -q 2>/dev/null | cut -d@ -f1); [ -n "$id" ] && break; sleep 1; done
  $g layout assign -z dc1 -c 1G "$id" && $g layout apply --version 1 || return 1
  sleep 2
  $g bucket create docs && $g bucket create other || return 1
  for k in catalog admin; do
    out=$($g key create "$k") || return 1
    eval "${k^^}_AK=\$(echo \"\$out\" | awk '/Key ID/ {print \$NF}')"
    eval "${k^^}_SK=\$(echo \"\$out\" | awk '/Secret key/ {print \$NF}')"
  done
  CAT_AK=$CATALOG_AK; CAT_SK=$CATALOG_SK; ADM_AK=$ADMIN_AK; ADM_SK=$ADMIN_SK
  $g bucket allow --read --write --owner docs --key catalog && \
  $g bucket allow --read --write --owner docs --key admin && $g bucket allow --read --write --owner other --key admin
}

s3_suite() {
  local blob="$W/blob" sha dl code url
  head -c 12000000 /dev/urandom > "$blob"; sha=$(sha256sum "$blob" | cut -d' ' -f1)
  # бакеты создаёт администратор (у Garage и RustFS они уже созданы командой init)
  if [ "$CAND" = seaweedfs ]; then
    aws_ "$ADM_AK" "$ADM_SK" s3 mb s3://docs >/dev/null 2>&1; res "создание бакета docs (администратор)" $? 
    aws_ "$ADM_AK" "$ADM_SK" s3 mb s3://other >/dev/null 2>&1; res "создание бакета other (администратор)" $?
  fi
  out=$(aws_ "$CAT_AK" "$CAT_SK" s3 cp - s3://docs/blob --expected-size 12000000 --no-progress < "$blob" 2>&1); res "загрузка потоком 12 МБ (многочастная) учётной записью каталога" $? "$(echo "$out" | tail -n1 | cut -c1-110)"
  got=$(aws_ "$CAT_AK" "$CAT_SK" s3 cp s3://docs/blob - --no-progress 2>/dev/null | sha256sum | cut -d' ' -f1); [ "$got" = "$sha" ]; res "скачивание потоком, контрольная сумма совпадает" $?
  url=$(aws_ "$CAT_AK" "$CAT_SK" s3 presign s3://docs/blob --expires-in 300)
  code=$(curl -s -o "$W/dl" -w '%{http_code}' --max-time 20 --cacert "$W/certs/ca.pem" "$url"); [ "$code" = 200 ] && [ "$(sha256sum "$W/dl" | cut -d' ' -f1)" = "$sha" ]; res "подписанная ссылка на 5 минут отдаёт объект" $? "HTTP $code"
  url1=$(aws_ "$CAT_AK" "$CAT_SK" s3 presign s3://docs/blob --expires-in 1); sleep 3
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 --cacert "$W/certs/ca.pem" "$url1"); [ "$code" = 403 ] || [ "$code" = 400 ]; res "просроченная подписанная ссылка отвергается" $? "HTTP $code"
  bad=$(echo "$url" | sed -E 's/(X-Amz-Signature=)[0-9a-f]{4}/\10000/')
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 --cacert "$W/certs/ca.pem" "$bad"); [ "$code" = 403 ]; res "ссылка с испорченной подписью отвергается" $? "HTTP $code"
  # учётная запись на один бакет
  if [ "${SCOPED:-1}" = 1 ]; then
  echo secret > "$W/small"
  aws_ "$ADM_AK" "$ADM_SK" s3 cp "$W/small" s3://other/secret --no-progress >/dev/null 2>&1
  out=$(aws_ "$CAT_AK" "$CAT_SK" s3 cp "$W/small" s3://other/x --no-progress 2>&1); echo "$out" | grep -qi 'AccessDenied\|Forbidden\|403'; res "запись в чужой бакет other запрещена" $? "$(echo "$out" | tail -n1 | cut -c1-90)"
  out=$(aws_ "$CAT_AK" "$CAT_SK" s3 cp s3://other/secret - --no-progress 2>&1); echo "$out" | grep -qi 'AccessDenied\|Forbidden\|403'; res "чтение из чужого бакета other запрещено" $? "$(echo "$out" | tail -n1 | cut -c1-90)"
  out=$(aws_ "$CAT_AK" "$CAT_SK" s3 ls s3://other 2>&1); echo "$out" | grep -qi 'AccessDenied\|Forbidden\|403'; res "список чужого бакета запрещён" $? "$(echo "$out" | tail -n1 | cut -c1-90)"
  out=$(aws_ "$CAT_AK" "$CAT_SK" s3 mb s3://hacker 2>&1); echo "$out" | grep -qi 'AccessDenied\|Forbidden\|403\|not allowed'; res "создание бакетов запрещено" $? "$(echo "$out" | tail -n1 | cut -c1-90)"
  url2=$(aws_ "$CAT_AK" "$CAT_SK" s3 presign s3://other/secret --expires-in 300)
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 --cacert "$W/certs/ca.pem" "$url2"); [ "$code" = 403 ]; res "подписанная ссылка учётной записи каталога на чужой бакет не работает" $? "HTTP $code"
  out=$(aws_ "$CAT_AK" "wrong${CAT_SK}" s3 ls s3://docs 2>&1); echo "$out" | grep -qi 'SignatureDoesNotMatch\|AccessDenied\|403'; res "неверный секретный ключ отвергается" $? "$(echo "$out" | tail -n1 | cut -c1-90)"
  fi
  # устойчивость: перезапуск контейнера не теряет данные
  docker restart $NAME >/dev/null 2>&1; t=$(wait_ready) ; sleep 2
  got=""; for i in $(seq 1 30); do got=$(aws_ "$CAT_AK" "$CAT_SK" s3 cp s3://docs/blob - --no-progress 2>/dev/null | sha256sum | cut -d' ' -f1); [ "$got" = "$sha" ] && break; sleep 1; done
  [ "$got" = "$sha" ]; res "после перезапуска данные на месте" $? "S3 отвечает через ${t:-?} с, данные читаются через ещё $((i-1)) с"
  aws_ "$CAT_AK" "$CAT_SK" s3 rm s3://docs/blob >/dev/null 2>&1; n=$(aws_ "$CAT_AK" "$CAT_SK" s3 ls s3://docs 2>/dev/null | wc -l); [ "$n" = 0 ]; res "удаление объекта учётной записью каталога" $?
}

run() {
  CAND=$1
  local img; img=$(image_of "$CAND"); W=$(mktemp -d); chmod 755 "$W"; mkdir -p "$W/data"; chmod 777 "$W/data"
  printf '[default]\ns3 =\n  addressing_style = path\n' > "$W/awsconfig"
  ADM_AK=spikeadmin$(rnd 6); ADM_SK=$(rnd 40); CAT_AK=catalog$(rnd 8); CAT_SK=$(rnd 40)
  make_certs
  docker pull -q "$img" >/dev/null 2>&1 || { echo "::error title=образ $CAND::не скачивается $img"; exit 1; }
  local t0 t1 size startlog
  size=$(docker image inspect "$img" --format '{{.Size}}'); res "образ $img, размер $((size/1024/1024)) МБ" 0
  t0=$(date +%s)
  "start_$CAND" > "$W/start.out" 2>&1 || { res "запуск с ограничениями стенда (не root, read-only, без привилегий, 192 МБ)" 1 "$(tail -n 3 "$W/start.out" | tr '\n' ' ' | cut -c1-200)"; finish_run; return; }
  if [ "$CAND" = garage ]; then
    EP=http://localhost:9000
    startsecs=$(wait_ready); rc=$?
    # у Garage нет TLS на S3: проверяем HTTPS-запросом и фиксируем отсутствие шифрования
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 --cacert "$W/certs/ca.pem" https://localhost:9000/ 2>&1); [ -n "$code" ] && [ "$code" != 000 ]
    res "TLS на порту S3" $? "HTTPS-запрос: код ${code:-нет}"
  else
    startsecs=$(wait_ready); rc=$?
  fi
  res "запуск с ограничениями стенда (не root, read-only, без привилегий, 192 МБ)" $rc "ответ через ${startsecs:-?} с; $(docker logs $NAME 2>&1 | tail -n 3 | tr '\n' ' ' | cut -c1-200)"
  [ $rc -eq 0 ] || { finish_run; return; }
  if [ "$CAND" != garage ]; then
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 --cacert "$W/certs/ca.pem" "$EP/" 2>&1); [ "$code" != 000 ]; res "TLS на порту S3 с нашим сертификатом (проверка цепочки)" $? "HTTP $code"
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://localhost:9000/ 2>&1); [ "$code" = 000 ] || [ "$code" = 400 ]; res "обычный HTTP на порту S3 не обслуживается" $? "HTTP $code"
  fi
  docker pull -q busybox:1.37 >/dev/null 2>&1
  for hp in /health /minio/health/live /rustfs/health/live /cluster/healthz /status; do
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 --cacert "$W/certs/ca.pem" "$EP$hp" 2>/dev/null); echo "проба $hp: HTTP $code" >> "$W/extra.txt"
  done
  hc=$(docker exec $NAME sh -c 'which curl wget nc 2>/dev/null | tr "\n" " "' 2>&1 | cut -c1-80); echo "утилиты в контейнере: ${hc:-нет}" >> "$W/extra.txt"
  ports=$(docker run --rm --network container:$NAME busybox:1.37 netstat -tln 2>/dev/null | awk 'NR>2 {print $4}' | sort -u | tr '\n' ' ')
  nonloop=$(echo "$ports" | tr ' ' '\n' | grep -v -E '^(127\.0\.0\.1|\[?::1\]?):' | grep -v '^$' | tr '\n' ' ')
  badp=0; for a in $nonloop; do case "$a" in 0.0.0.0:9000|:::9000|'[::]:9000'|::1:*) ;; *) badp=1;; esac; done
  res "снаружи слушает только порт S3 (остальные на 127.0.0.1)" $badp "все: ${ports:-нет}"
  if [ "$CAND" = garage ] || [ "$CAND" = seaweedfs ] || [ "$CAND" = rustfs ]; then "provision_$CAND" > "$W/prov.out" 2>&1; PRC=$?; res "создание учётной записи на один бакет (init)" $PRC "$(tail -n 2 "$W/prov.out" | tr '\n' ' ' | cut -c1-200)"; [ $PRC -eq 0 ] || { echo "== вывод init"; head -n 14 "$W/prov.out"; } >> "$W/extra.txt"; fi
  s3_suite
  finish_run
}

finish_run() {
  local peak id mem oom
  id=$(docker inspect -f '{{.Id}}' $NAME 2>/dev/null)
  mem=$(docker stats --no-stream --format '{{.MemUsage}}' $NAME 2>/dev/null)
  peak=$(cat /sys/fs/cgroup/system.slice/docker-${id}.scope/memory.peak 2>/dev/null || cat /sys/fs/cgroup/docker/${id}/memory.peak 2>/dev/null || echo)
  oom=$(docker inspect -f '{{.State.OOMKilled}}' $NAME 2>/dev/null)
  res "память: сейчас $mem, пик $([ -n "$peak" ] && echo $((peak/1024/1024)) МБ || echo н/д), OOM: $oom, лимит 192 МБ" $([ "$oom" = false ] && echo 0 || echo 1)
  { echo "== $CAND"; cat "$RESULTS"; [ -f "$W/extra.txt" ] && cat "$W/extra.txt"
    echo "== порты в журнале"; docker logs $NAME 2>&1 | grep -i -E 'listen|serving|port ' | cut -c1-170 | head -n 14
  } > "$W/report.txt"
  note "спайк $CAND" "$W/report.txt"
  docker rm -f $NAME >/dev/null 2>&1
}

case "${1:-}" in
  recon) recon "${2:?кандидат}" ;;
  run) run "${2:?кандидат}" ;;
  *) echo "Использование: $0 recon|run <seaweedfs|rustfs|garage>" >&2; exit 64 ;;
esac
