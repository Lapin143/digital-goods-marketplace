#!/bin/bash
# Запуск PostgreSQL под UID 999 с файловой системой только для чтения.
# PostgreSQL требует, чтобы закрытый ключ принадлежал пользователю сервера и имел права не шире 0600, а секреты Docker
# монтируются с правами хоста. Поэтому ключ копируется в каталог в памяти (tmpfs) перед запуском штатного сценария образа.
set -euo pipefail

D=/run/dgm
install -m 0600 /run/secrets/tls_postgres.key "$D/server.key"
install -m 0644 /run/secrets/tls_postgres.crt "$D/server.crt"
install -m 0644 /run/secrets/tls_ca.crt "$D/ca.crt"

export POSTGRES_PASSWORD_FILE=/run/secrets/db_postgres_admin
export POSTGRES_INITDB_ARGS="--auth-local=trust --auth-host=scram-sha-256 --data-checksums"

exec docker-entrypoint.sh postgres -c config_file=/etc/dgm/postgres/postgresql.conf
