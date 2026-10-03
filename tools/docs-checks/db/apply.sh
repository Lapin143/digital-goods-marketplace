#!/bin/bash
# Применяет каждый DDL с нуля в отдельную базу на PG16 и печатает итог
PSQL="psql -h /tmp -p 5433 -U postgres -v ON_ERROR_STOP=1 -q"
HERE=$(cd "$(dirname "$(readlink -f "$0")")" && pwd)
REPO=${REPO:-$(cd "$HERE/../../.." && pwd)}
only="$1"
fail=0
for pair in catalog-service:catalog_db inventory-service:inventory_db order-service:order_db payment-service:payment_db delivery-service:delivery_db platform-service:platform_db; do
  svc=${pair%%:*}; db=${pair##*:}
  if [ -n "$only" ] && [ "$only" != "$svc" ]; then continue; fi
  $PSQL -d postgres -c "drop database if exists $db" -c "create database $db" >/dev/null 2>&1
  out=$($PSQL -d $db -f $REPO/docs/07-data/ddl/$svc.sql 2>&1)
  rc=$?
  if [ $rc -ne 0 ]; then echo "ОШИБКА $svc:"; echo "$out" | head -20; fail=1; else echo "OK $svc"; fi
done
exit $fail
