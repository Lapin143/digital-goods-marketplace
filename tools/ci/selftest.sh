#!/usr/bin/env bash
# Проверка самих контролей CI: контроль, который ни разу не сработал, ничего не гарантирует.
#
#   tools/ci/selftest.sh docs       намеренно битый документ должен быть отвергнут, диагностика должна появиться
#   tools/ci/selftest.sh security   подложенный фиктивный секрет должен быть найден Gitleaks
#
# Фиктивный секрет создаётся при запуске из случайных символов и в репозитории не хранится.
set -uo pipefail
HERE=$(cd "$(dirname "$(readlink -f "$0")")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
mode="${1:-}"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fail=0

expect_fail() {  # название, команда...
  local name="$1"; shift
  if "$@" >"$tmp/out.txt" 2>&1; then
    echo "САМОПРОВЕРКА НЕ ПРОЙДЕНА: «$name» должен был упасть, но прошёл"
    fail=1
  else
    echo "самопроверка ок: «$name» отвергнут, как и должно быть"
  fi
}

case "$mode" in
  docs)
    # 1. Документ с несуществующей ссылкой и длинным тире отвергается проверкой документов.
    mkdir -p "$tmp/docs"
    printf '# Битый документ\n\nСсылка на [ничто](net-takogo-faila.md) и длинное тире \xe2\x80\x94 в тексте.\n' > "$tmp/docs/bad.md"
    expect_fail "проверка документов находит битую ссылку" python3 "$REPO/tools/docs-checks/validate_docs.py" "$tmp/docs"
    # 2. Помощник диагностики превращает падение команды в аннотацию с причиной.
    out=$("$HERE/diag.sh" "самопроверка диагностики" bash -c 'echo причина-падения-12345; exit 7' 2>&1)
    rc=$?
    if [ "$rc" -ne 7 ]; then echo "САМОПРОВЕРКА НЕ ПРОЙДЕНА: diag.sh вернул код $rc вместо 7"; fail=1; fi
    if ! grep -q '^::error title=самопроверка диагностики (код 7)::.*причина-падения-12345' <<<"$out"; then
      echo "САМОПРОВЕРКА НЕ ПРОЙДЕНА: в выводе diag.sh нет аннотации с причиной"; echo "$out"; fail=1
    else
      echo "самопроверка ок: diag.sh печатает аннотацию с причиной падения"
    fi
    # 3. check_compose.py находит намеренно внесённые ошибки в compose.yaml (лишний порт, неверный лимит, лишние права, чужой секрет, плавающий тег).
    mutate() {  # название, старое, новое, образец сообщения (контроль должен упасть именно по этой причине)
      python3 - "$REPO/compose.yaml" "$tmp/compose-bad.yaml" "$2" "$3" <<'PY' || { echo "САМОПРОВЕРКА НЕ ПРОЙДЕНА: не удалось внести ошибку «$1»"; fail=1; return; }
import sys
src, dst, old, new = sys.argv[1:5]
s = open(src, encoding='utf-8').read()
assert old in s, 'в compose.yaml нет «%s»' % old
open(dst, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY
      expect_fail "check_compose находит: $1" env DGM_COMPOSE_FILE="$tmp/compose-bad.yaml" python3 "$REPO/tools/docs-checks/check_compose.py"
      if ! grep -q -- "$4" "$tmp/out.txt"; then
        echo "САМОПРОВЕРКА НЕ ПРОЙДЕНА: «$1»: контроль упал, но не по той причине (нет «$4»)"; tail -n 5 "$tmp/out.txt"; fail=1
      fi
    }
    mutate "лишний порт на хосте" "    profiles: [infra]
    user: \"999:999\"" "    profiles: [infra]
    ports: [\"5432:5432\"]
    user: \"999:999\"" "порт 5432:5432 публикуется"
    mutate "неверный лимит памяти" "mem_limit: 512m" "mem_limit: 600m" "mem_limit 600 МБ, в memory-budget.md 512"
    mutate "файловая система не только для чтения" "read_only: true" "read_only: false" "read_only должен быть true"
    mutate "секрет, которого у контейнера быть не должно" "      - redis_gateway
    healthcheck:" "      - redis_gateway
      - db_keycloak
    healthcheck:" "секрет db_keycloak"
    mutate "образ с плавающим тегом" "image: redis:8.2.10-alpine" "image: redis:latest" "без точной версии"
    ;;
  security)
    # Фиктивный токен формата GitHub (ghp_ и 36 случайных символов). Он не настоящий и нигде не работает.
    rnd=$(python3 -c "import secrets,string;print(''.join(secrets.choice(string.ascii_letters+string.digits) for _ in range(36)))")
    printf 'GITHUB_TOKEN=ghp_%s\n' "$rnd" > "$tmp/leak.env"
    expect_fail "Gitleaks находит подложенный секрет" gitleaks dir "$tmp" --no-banner --redact --config "$REPO/.gitleaks.toml"
    ;;
  *)
    echo "Использование: $0 docs|security" >&2
    exit 64
    ;;
esac
exit $fail
