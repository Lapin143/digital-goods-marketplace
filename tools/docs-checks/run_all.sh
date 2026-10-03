#!/bin/bash
# Все проверки документов и контрактов одной командой. Запуск из любой папки: bash tools/docs-checks/run_all.sh [--db]
# Ключ --db добавляет проверки физической модели: нужен PostgreSQL 16 на сокете /tmp, порт 5433 (см. README).
HERE=$(cd "$(dirname "$(readlink -f "$0")")" && pwd)
export REPO=${REPO:-$(cd "$HERE/../.." && pwd)}
cd "$REPO" || exit 2
fail=0
run() { echo "== $*"; "$@" || fail=1; echo; }
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
run python3 "$HERE/check_refs.py"
if [ "$1" = "--db" ]; then
  run bash "$HERE/db/apply.sh"
  run python3 "$HERE/db/test_db.py"
  run python3 "$HERE/db/check_datamodel.py"
  run python3 "$HERE/db/check_explain.py"
fi
[ $fail -eq 0 ] && echo "ВСЕ ПРОВЕРКИ ПРОЙДЕНЫ" || echo "ЕСТЬ ПРОБЛЕМЫ"
exit $fail
