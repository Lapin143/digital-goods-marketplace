#!/bin/sh
# Запуск RustFS под UID 10001 с файловой системой только для чтения (ADR-024).
# Сервер ждёт сертификат и ключ в каталоге под именами rustfs_cert.pem и rustfs_key.pem, а секреты Docker называются иначе,
# поэтому файлы копируются в каталог в памяти. Корневая запись: имя берётся из файла в репозитории (не секрет), пароль из секрета.
# Консоль управления отключена, наружу слушает один порт S3 (9000, TLS). Остальное делает штатный сценарий образа.
set -eu

D=/run/dgm
S=/run/secrets
T="$D/tls"

umask 077
mkdir -p "$T"
cp "$S/tls_object-storage.crt" "$T/rustfs_cert.pem"
cp "$S/tls_object-storage.key" "$T/rustfs_key.pem"
tr -d '\r\n' < /etc/dgm/storage/root_user > "$D/root_user"
tr -d '\r\n' < "$S/storage_admin" > "$D/root_password"

export RUSTFS_ACCESS_KEY_FILE="$D/root_user"
export RUSTFS_SECRET_KEY_FILE="$D/root_password"
export RUSTFS_TLS_PATH="$T"
export RUSTFS_ADDRESS=":9000"
export RUSTFS_CONSOLE_ENABLE=false
export RUSTFS_VOLUMES=/data

exec /entrypoint.sh rustfs
