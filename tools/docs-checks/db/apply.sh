#!/bin/bash
# Применяет модель каждого сервиса с нуля в отдельную базу на PG16 и печатает итог.
#   apply.sh [сервис]            из миграций Flyway (services/<сервис>/src/main/resources/db/migration), как их применит Flyway:
#                                по возрастанию номера версии, каждая в своей транзакции
#   DB_SOURCE=ddl apply.sh       из docs/07-data/ddl/<сервис>.sql одним проходом
# Склейка миграций равна DDL байт в байт (gen_migrations.py --check), поэтому тесты db/* на базе из миграций проверяют и DDL.
PSQL="psql -h /tmp -p 5433 -U postgres -v ON_ERROR_STOP=1 -q"
HERE=$(cd "$(dirname "$(readlink -f "$0")")" && pwd)
REPO=${REPO:-$(cd "$HERE/../../.." && pwd)}
SOURCE=${DB_SOURCE:-migrations}
only="$1"
fail=0
for pair in catalog-service:catalog_db inventory-service:inventory_db order-service:order_db payment-service:payment_db delivery-service:delivery_db platform-service:platform_db; do
  svc=${pair%%:*}; db=${pair##*:}
  if [ -n "$only" ] && [ "$only" != "$svc" ]; then continue; fi
  $PSQL -d postgres -c "drop database if exists $db" -c "create database $db" >/dev/null 2>&1
  if [ "$SOURCE" = ddl ]; then
    out=$($PSQL -d $db -f $REPO/docs/07-data/ddl/$svc.sql 2>&1); rc=$?
    n=1
  else
    rc=0; out=""; n=0
    # номер версии из имени V<n>__<имя>.sql, порядок по номеру
    for f in $(find "$REPO/services/$svc/src/main/resources/db/migration" -name 'V*.sql' \
               | awk -F/ '{v=$NF; sub(/^V/,"",v); sub(/__.*/,"",v); print v "\t" $0}' | sort -n | cut -f2); do
      o=$($PSQL -1 -d $db -f "$f" 2>&1) || { rc=1; out="${f##*/}: $o"; break; }
      n=$((n+1))
    done
    [ $n -gt 0 ] || { rc=1; out="нет миграций сервиса $svc"; }
  fi
  if [ $rc -ne 0 ]; then echo "ОШИБКА $svc:"; echo "$out" | head -20; fail=1; else echo "OK $svc ($SOURCE: $n)"; fi
done
exit $fail
