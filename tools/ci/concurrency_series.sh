#!/usr/bin/env bash
# Серия тестов гонок (шаг 15 Ф4, test-strategy.md, раздел «Конкуренция»).
#
#   tools/ci/concurrency_series.sh [число запусков] [задача Gradle ...]
#
# Запускает задачу concurrencyTest (JUnit, тег concurrency) указанное число раз подряд (по умолчанию 50) на поднятом стенде
# (make up SET=dev-kit DEBUG=1, миграции сделаны). Один зелёный прогон гонки ничего не доказывает: гонка проявляется не в каждом
# запуске. Серия останавливается на первом сбое и печатает его номер, чтобы разбор начался с точного места. Серия, в которой ни один тест
# не выполнился, считается сбоем: иначе пустая задача давала бы «50 из 50».
#
# Переменные: CONCURRENCY_MIN_TESTS (минимум выполненных тестов в запуске, по умолчанию 3), CONCURRENCY_REPORT (куда писать сводку).
set -uo pipefail

runs="${1:-50}"
shift || true
tasks=("$@")
if [ "${#tasks[@]}" -eq 0 ]; then
  tasks=(:libs:service-kit:concurrencyTest :services:inventory-service:concurrencyTest :services:payment-service:concurrencyTest :services:order-service:concurrencyTest :services:delivery-service:concurrencyTest)
fi
min_tests="${CONCURRENCY_MIN_TESTS:-3}"
report="${CONCURRENCY_REPORT:-build/concurrency-series.txt}"
mkdir -p build "$(dirname "$report")"
: >"$report"

# Сколько тестов выполнено в последнем запуске по отчётам JUnit (атрибут tests у testsuite) и сколько из них упало.
count_tests() {
  python3 - "$@" <<'PY'
import glob, re, sys
tests = failures = 0
for path in glob.glob('services/*/build/test-results/concurrencyTest/*.xml') + glob.glob('libs/*/build/test-results/concurrencyTest/*.xml'):
    head = open(path, encoding='utf-8').read(4000)
    m = re.search(r'<testsuite [^>]*', head)
    if not m:
        continue
    for name, target in (('tests', 'tests'), ('failures', 'failures'), ('errors', 'failures')):
        v = re.search(r'\b%s="(\d+)"' % name, m.group(0))
        if v:
            if target == 'tests':
                tests += int(v.group(1))
            else:
                failures += int(v.group(1))
print(tests, failures)
PY
}

started=$(date +%s)
for i in $(seq 1 "$runs"); do
  t0=$(date +%s)
  if ! ./gradlew --console=plain -q "${tasks[@]}" >"build/concurrency-run-$i.log" 2>&1; then
    echo "СЕРИЯ ПРЕРВАНА: сбой в запуске $i из $runs" | tee -a "$report"
    tail -n 60 "build/concurrency-run-$i.log"
    echo "::error title=Серия гонок: сбой в запуске $i из $runs::$(grep -E 'FAILED|AssertionFailedError|Exception|expected' "build/concurrency-run-$i.log" | head -n 12 | sed -e 's/%/%25/g' | sed -e ':a;N;$!ba;s/\n/%0A/g')"
    exit 1
  fi
  read -r tests failures < <(count_tests)
  if [ "${tests:-0}" -lt "$min_tests" ] || [ "${failures:-0}" -ne 0 ]; then
    echo "СЕРИЯ ПРЕРВАНА: в запуске $i выполнено тестов $tests (нужно не меньше $min_tests), упало $failures" | tee -a "$report"
    exit 1
  fi
  echo "запуск $i из $runs: тестов $tests, $(( $(date +%s) - t0 )) с" | tee -a "$report"
  rm -f "build/concurrency-run-$i.log"
done
echo "СЕРИЯ ПРОЙДЕНА: $runs из $runs запусков зелёные, $(( $(date +%s) - started )) с" | tee -a "$report"
