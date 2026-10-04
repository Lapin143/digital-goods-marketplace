#!/bin/bash
# Запуск Keycloak с файловой системой только для чтения: секреты читаются из файлов Docker, realm копируется в каталог в памяти.
# Образ собран командой kc.sh build (docker/Dockerfile.keycloak), поэтому запуск идёт с ключом --optimized.
# Файл realm применяется только при первом запуске на пустой базе; чтобы применить изменения, realm удаляют (docs/09-operations, make realm).
set -eu

S=/run/secrets
IMPORT=/opt/keycloak/data/import

secret() { tr -d '\r\n' < "$S/$1"; }

KC_DB_PASSWORD="$(secret db_keycloak)"
KC_BOOTSTRAP_ADMIN_USERNAME=dgm-admin
KC_BOOTSTRAP_ADMIN_PASSWORD="$(secret keycloak_admin)"
DGM_PLATFORM_CLIENT_SECRET="$(secret keycloak_client_platform)"
DGM_VKID_CLIENT_SECRET="$(secret keycloak_vkid_client)"
export KC_DB_PASSWORD KC_BOOTSTRAP_ADMIN_USERNAME KC_BOOTSTRAP_ADMIN_PASSWORD DGM_PLATFORM_CLIENT_SECRET DGM_VKID_CLIENT_SECRET

umask 077
mkdir -p "$IMPORT"
cp /etc/dgm/keycloak/realm/dgm-realm.json "$IMPORT/dgm-realm.json"

exec /opt/keycloak/bin/kc.sh start --optimized --import-realm
