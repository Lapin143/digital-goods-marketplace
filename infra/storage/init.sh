#!/bin/sh
# Задание storage-init (ADR-024): бакет документов продавцов, политика доступа и учётная запись catalog-service.
# Выполняется один раз при запуске профиля storage и завершается. Работает клиентом rc от имени корневой записи.
#
# Повторный запуск безопасен: бакет не пересоздаётся, политика и пароль учётной записи приводятся к тому, что лежит в
# политике из репозитория и в секрете storage_catalog (так выполняется и смена секрета: заменить файл, запустить задание снова).
# Пароли читаются из файлов секретов. В окружение и в журнал они не попадают, из вывода rc значения вырезаются.
# Командная строка rc с паролем видна только внутри этого контейнера, пока он работает (rc не читает пароль иначе).
# Перед позиционными аргументами стоит «--»: пароль может начинаться со знака «-», и rc принял бы его за параметр.
set -eu

EP=${STORAGE_ENDPOINT:-https://object-storage:9000}
CA=/run/secrets/tls_ca.crt
CONF=/etc/dgm/storage
BUCKET=seller-documents
POLICY=seller-documents-rw
SERVICE_USER=catalog-service
OUT=/tmp/rc.out

ROOT_USER=$(tr -d '\r\n' < "$CONF/root_user")
ROOT_PASS=$(tr -d '\r\n' < /run/secrets/storage_admin)
SERVICE_PASS=$(tr -d '\r\n' < /run/secrets/storage_catalog)
[ -n "$ROOT_USER" ] && [ -n "$ROOT_PASS" ] && [ -n "$SERVICE_PASS" ] || { echo "ОШИБКА: пустое имя или пароль в infra/storage/root_user или в секретах"; exit 1; }

# Вывод без паролей (пароли из make secrets состоят из букв, цифр, «-» и «_», в шаблоне sed они безопасны)
clean() { sed -e "s/$ROOT_PASS/***/g" -e "s/$SERVICE_PASS/***/g"; }

# step «описание» команда...: выполняет, коротко печатает результат, при ошибке печатает вывод и завершает задание
step() {
  desc=$1; shift
  if "$@" >"$OUT" 2>&1; then
    echo "$desc: $(clean < "$OUT" | tr '\n' ' ' | cut -c1-140)"
  else
    echo "ОШИБКА: $desc"; clean < "$OUT" | head -n 12; exit 1
  fi
}

step "псевдоним корневой записи" rc alias set --ca-bundle "$CA" -- s "$EP" "$ROOT_USER" "$ROOT_PASS"

# Ожидание: контейнер хранилища уже healthy, но подсистема учётных записей может отвечать позже. Первая настоящая операция повторяется
i=0
until rc bucket create --ignore-existing "s/$BUCKET" >"$OUT" 2>&1; do
  i=$((i+1))
  [ "$i" -ge 30 ] && { echo "ОШИБКА: хранилище не приняло бакет за 60 с"; clean < "$OUT" | head -n 12; exit 1; }
  echo "ожидание хранилища ($i): $(clean < "$OUT" | tr '\n' ' ' | cut -c1-120)"
  sleep 2
done
echo "бакет $BUCKET: $(clean < "$OUT" | tr '\n' ' ' | cut -c1-120)"

step "политика $POLICY" rc admin policy create s "$POLICY" "$CONF/policy-seller-documents.json"
step "учётная запись $SERVICE_USER" rc admin user add -- s "$SERVICE_USER" "$SERVICE_PASS"
step "политика назначена учётной записи" rc admin policy attach s "$POLICY" --user "$SERVICE_USER"

echo "storage-init: бакет, политика и учётная запись на месте"
