#!/usr/bin/env bash
# Общие функции проверок на поднятом стенде. Подключается командой `source "$(dirname "$0")/lib.sh"`.
#
#   ok / bad              учёт результата; bad печатает последние строки вывода как «подробности»
#   check                 код 0 (вывод показывается только при ошибке)
#   expect_ok             код 0 и в выводе нет признаков ошибки (ERR_RE)
#   expect_fail           код не 0 и вывод содержит образец
#   expect_denied         вывод содержит образец (клиенты Kafka могут вернуть код 0 после сообщения об отказе)
#   finish                итог и код выхода: 1, если были ошибки
# Проверка, которая ни разу не сработала, ничего не гарантирует, поэтому у каждой «хорошей» проверки есть «плохая».
FAILED=()
PASSED=0
ok()   { PASSED=$((PASSED+1)); echo "  ok    $1"; }
bad()  { FAILED+=("$1"); echo "  ОШИБКА $1"; [ -n "${2:-}" ] && echo "$2" | tail -n 6 | sed 's/^/        | /'; }

check()         { local name=$1; shift; OUT=$("$@" 2>&1); RC=$?
  if [ $RC -eq 0 ]; then ok "$name"; else bad "$name (код $RC)" "$OUT"; fi; }
ERR_RE='not authorized|authorization|exception|error|failed|denied|refused'
expect_ok()     { local name=$1; shift; OUT=$("$@" 2>&1); RC=$?
  if [ $RC -eq 0 ] && ! grep -Eqi "$ERR_RE" <<<"$OUT"; then ok "$name"; else bad "$name (код $RC)" "$OUT"; fi; }
expect_fail()   { local name=$1 pat=$2; shift 2; OUT=$("$@" 2>&1); RC=$?
  if [ $RC -ne 0 ] && grep -Eqi "$pat" <<<"$OUT"; then ok "$name"; else bad "$name (ожидался отказ «$pat», код $RC)" "$OUT"; fi; }
expect_denied() { local name=$1 pat=$2; shift 2; OUT=$("$@" 2>&1); RC=$?
  if grep -Eqi "$pat" <<<"$OUT"; then ok "$name"; else bad "$name (ожидался отказ «$pat», код $RC)" "$OUT"; fi; }

finish() {
  echo
  echo "Проверок успешно: $PASSED, с ошибками: ${#FAILED[@]}"
  # В GitHub Actions число проверок попадает в аннотацию: по ней видно, сколько проверок выполнено, без доступа к журналу
  # Имена непрошедших проверок идут в ту же аннотацию: журнал шага длинный и обрезается, аннотация доходит целиком
  [ -n "${GITHUB_ACTIONS:-}" ] && echo "::notice title=$(basename "$0" .sh)::Проверок успешно: $PASSED, с ошибками: ${#FAILED[@]}$([ ${#FAILED[@]} -ne 0 ] && printf '; не прошли: %s' "$(IFS='|'; echo "${FAILED[*]}")")"
  if [ ${#FAILED[@]} -ne 0 ]; then
    printf '  - %s\n' "${FAILED[@]}"
    return 1
  fi
}
