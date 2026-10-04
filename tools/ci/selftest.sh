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
