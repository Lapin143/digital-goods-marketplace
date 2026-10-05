#!/bin/bash
# Все проверки документов и контрактов одной командой. Запуск из любой папки: bash tools/docs-checks/run_all.sh [--db]
# Ключ --db добавляет проверки физической модели: нужен PostgreSQL 16 на сокете /tmp, порт 5433 (см. README).
HERE=$(cd "$(dirname "$(readlink -f "$0")")" && pwd)
export REPO=${REPO:-$(cd "$HERE/../.." && pwd)}
cd "$REPO" || exit 2
fail=0
summary=$(mktemp)
trap 'rm -f "$summary"' EXIT
# Каждая проверка печатает свой вывод; проверки с ошибками дополнительно попадают в сводку в конце
# (по ней видно, что именно сломалось, без прокрутки длинного журнала, в том числе в аннотациях CI).
run() {
  local out rc
  echo "== $*"
  out=$(mktemp)
  "$@" 2>&1 | tee "$out"; rc=${PIPESTATUS[0]}
  if [ "$rc" -ne 0 ]; then
    fail=1
    { echo "== ${*##*/}"; grep -E '^\s+- |ОШИБКА|Traceback|Error' "$out" | head -n 20; } >> "$summary"
  fi
  rm -f "$out"
  echo
}
run python3 "$HERE/validate_docs.py" docs
run python3 "$HERE/check_us.py"
run python3 "$HERE/check_uc.py"
run python3 "$HERE/check_asyncapi.py"
run python3 "$HERE/check_openapi.py"
run python3 "$HERE/check_adr.py"
run python3 "$HERE/check_decomposition.py"
run python3 "$HERE/check_components.py"
run python3 "$HERE/check_seq.py"
run python3 "$HERE/check_budgets.py"
run python3 "$HERE/check_security.py"
run python3 "$HERE/check_strategy.py"
run python3 "$HERE/check_traceability.py"
run python3 "$HERE/check_refs.py"
run python3 "$HERE/check_compose.py"
run python3 "$HERE/check_stub_contract.py"
run python3 "$HERE/check_realm.py"
run python3 "$REPO/infra/keycloak/gen_realm.py" --check
run python3 "$REPO/infra/kafka/gen_kafka.py" --check
run python3 "$HERE/db/gen_migrations.py" --check
run python3 "$HERE/check_db_roles.py"
run python3 "$HERE/check_kit.py"
run python3 "$HERE/gen_routes.py" --check
if [ "$1" = "--db" ]; then
  run bash "$HERE/db/apply.sh"
  run python3 "$HERE/db/test_db.py"
  run python3 "$HERE/db/check_datamodel.py"
  run python3 "$HERE/db/check_explain.py"
fi
if [ $fail -eq 0 ]; then echo "ВСЕ ПРОВЕРКИ ПРОЙДЕНЫ"; else echo "СВОДКА ОШИБОК:"; cat "$summary"; echo "ЕСТЬ ПРОБЛЕМЫ"; fi
exit $fail
