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
    mutate "образ с плавающим тегом" "image: redis:8.10.2-alpine" "image: redis:latest" "без точной версии"
    mutate "порт хранилища объектов опубликован на хост" "    profiles: [storage]
    user: \"10001:10001\"" "    profiles: [storage]
    ports: [\"9000:9000\"]
    user: \"10001:10001\"" "порт 9000:9000 публикуется"
    mutate "хранилище читает пароль учётной записи сервиса" "      - storage_admin
    healthcheck:" "      - storage_admin
      - storage_catalog
    healthcheck:" "секрет storage_catalog: читатели"
    mutate "у разового задания появился собственный сертификат" "      - tls_ca.crt
      - storage_admin
      - storage_catalog" "      - tls_ca.crt
      - tls_storage-init.key
      - storage_admin
      - storage_catalog" "у разового задания нет своего сертификата"
    mutate "заглушки читают чужой секрет" "      - platform_webhook_secret
    healthcheck:" "      - platform_webhook_secret
      - db_keycloak
    healthcheck:" "секрет db_keycloak"
    mutate "админ-порт заглушек опубликован на хост" "    profiles: [stubs]
    user: \"10001:10001\"" "    profiles: [stubs]
    ports: [\"8444:8444\"]
    user: \"10001:10001\"" "порт 8444:8444 публикуется"
    mutate "база сборки заглушек не равна versions.md" "        NODE_IMAGE: node:24.21.0-alpine3.24" "        NODE_IMAGE: node:latest" "build.args.NODE_IMAGE"
    # Контракт заглушки: адрес вебхука с другим путём и схема, расходящаяся с OpenAPI, отвергаются check_stub_contract.py.
    python3 - "$REPO/compose.yaml" "$tmp/compose-contract.yaml" <<'PY' || { echo "САМОПРОВЕРКА НЕ ПРОЙДЕНА: не удалось внести ошибку в адрес вебхука"; fail=1; }
import sys
src, dst = sys.argv[1:3]
s = open(src, encoding='utf-8').read()
old = 'api-gateway:8443/api/v1/webhooks/payment-gateway'
assert old in s
open(dst, 'w', encoding='utf-8').write(s.replace(old, 'api-gateway:8443/api/v1/webhooks/payments', 1))
PY
    expect_fail "check_stub_contract находит чужой путь вебхука в compose.yaml" env DGM_COMPOSE_FILE="$tmp/compose-contract.yaml" python3 "$REPO/tools/docs-checks/check_stub_contract.py"
    grep -q 'STUBS_PAYMENT_WEBHOOK_URL' "$tmp/out.txt" || { echo "САМОПРОВЕРКА НЕ ПРОЙДЕНА: контракт упал не по адресу вебхука"; tail -n 5 "$tmp/out.txt"; fail=1; }
    python3 - "$REPO/tools/external-stubs/test/contract/webhook-schemas.json" "$tmp/schemas-bad.json" <<'PY' || { echo "САМОПРОВЕРКА НЕ ПРОЙДЕНА: не удалось исказить схему"; fail=1; }
import sys
src, dst = sys.argv[1:3]
s = open(src, encoding='utf-8').read()
assert '"payment.paid"' in s
open(dst, 'w', encoding='utf-8').write(s.replace('"payment.paid"', '"payment.settled"', 1))
PY
    expect_fail "check_stub_contract находит схему, расходящуюся с OpenAPI" env DGM_STUB_CONTRACT_FILE="$tmp/schemas-bad.json" python3 "$REPO/tools/docs-checks/check_stub_contract.py"
    grep -q 'не совпадает с OpenAPI' "$tmp/out.txt" || { echo "САМОПРОВЕРКА НЕ ПРОЙДЕНА: контракт упал не из-за расхождения схем"; tail -n 5 "$tmp/out.txt"; fail=1; }
    # 4. Миграции Flyway и роли базы: правка миграции, предела соединений и секрета роли ловится контролями шага 7.
    mkdir -p "$tmp/repo"
    cp -r "$REPO/.github" "$REPO/docs" "$REPO/infra" "$REPO/services" "$REPO/libs" "$REPO/tools" "$REPO/compose.yaml" "$REPO/compose.debug.yaml" "$REPO/gradle.properties" "$REPO/docker" "$REPO/gradle" "$REPO/build-logic" "$REPO/Makefile" "$tmp/repo/"
    mutate_repo() {  # название, файл от корня, старое, новое, образец сообщения, команда от корня...
      local name="$1" f="$2" old="$3" new="$4" pat="$5"; shift 5
      python3 - "$tmp/repo/$f" "$old" "$new" <<'PY' || { echo "САМОПРОВЕРКА НЕ ПРОЙДЕНА: не удалось внести ошибку «$name»"; fail=1; return; }
import sys
path, old, new = sys.argv[1:4]
s = open(path, encoding='utf-8').read()
assert old in s, 'в файле нет «%s»' % old
open(path, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY
      expect_fail "$name" env REPO="$tmp/repo" python3 "$tmp/repo/$@"
      if ! grep -q -- "$pat" "$tmp/out.txt"; then
        echo "САМОПРОВЕРКА НЕ ПРОЙДЕНА: «$name»: контроль упал, но не по той причине (нет «$pat»)"; tail -n 5 "$tmp/out.txt"; fail=1
      fi
      cp "$REPO/$f" "$tmp/repo/$f"
    }
    mutate_repo "gen_migrations находит правку миграции" \
      "services/order-service/src/main/resources/db/migration/orders/V2__orders.sql" "create schema orders;" "create schema orders; -- правка" \
      "не соответствует частям DDL" tools/docs-checks/db/gen_migrations.py --check
    mutate_repo "check_db_roles находит предел соединений сверх пула сервиса" \
      "infra/postgres/roles.json" '"app_inventory",         "secret": "db_app_inventory",         "limit": 8' '"app_inventory",         "secret": "db_app_inventory",         "limit": 9' \
      "больше пула сервиса" tools/docs-checks/check_db_roles.py
    mutate_repo "check_db_roles находит чужой секрет у роли" \
      "infra/postgres/roles.json" '"secret": "db_app_catalog"' '"secret": "db_app_orders"' \
      "ожидался db_app_catalog" tools/docs-checks/check_db_roles.py
    # 5. Realm Keycloak: правка срока токена, второго фактора, привязки области, секрета и политики паролей ловится check_realm.py.
    R=infra/keycloak/realm/dgm-realm.json
    mutate_repo "check_realm находит срок access-токена не по документу" "$R" '"accessTokenLifespan": 300' '"accessTokenLifespan": 3600' \
      "срок access-токена realm" tools/docs-checks/check_realm.py
    mutate_repo "check_realm находит ослабленную защиту от перебора" "$R" '"failureFactor": 5' '"failureFactor": 50' \
      "защита от перебора" tools/docs-checks/check_realm.py
    mutate_repo "check_realm находит TLS не для всех адресов" "$R" '"sslRequired": "all"' '"sslRequired": "external"' \
      "TLS обязателен для всех адресов" tools/docs-checks/check_realm.py
    mutate_repo "check_realm находит политику паролей без Argon2" "$R" 'hashAlgorithm(argon2)' 'hashAlgorithm(pbkdf2-sha512)' \
      "политика паролей без hashAlgorithm" tools/docs-checks/check_realm.py
    mutate_repo "check_realm находит второй фактор не у той роли" "$R" '"condUserRole": "seller"' '"condUserRole": "buyer"' \
      "второй фактор в потоке входа у ролей" tools/docs-checks/check_realm.py
    mutate_repo "check_realm находит область, выданную не той роли" "$R" '"clientScope": "orders.create",
      "roles": [
        "buyer"' '"clientScope": "orders.create",
      "roles": [
        "admin"' \
      "области orders.create выдаются ролям" tools/docs-checks/check_realm.py
    mutate_repo "check_realm находит значение секрета вместо подстановки" "$R" '"secret": "${DGM_PLATFORM_CLIENT_SECRET}"' '"secret": "plain-secret-value"' \
      "секрет клиента должен быть подстановкой" tools/docs-checks/check_realm.py
    mutate_repo "check_realm находит amr без срока хранения" "$R" '"default.reference.maxAge": "36000"' '"default.reference.maxAge": "0"' \
      "у amr нет default.reference.maxAge" tools/docs-checks/check_realm.py
    mutate_repo "gen_realm --check находит ручную правку realm" "$R" '"verifyEmail": true' '"verifyEmail": true, "x": 1' \
      "не равен результату gen_realm.py" infra/keycloak/gen_realm.py --check
    # 6. Каркас сервисов: тип проблемы, статус и столбцы Outbox расходятся с документами и DDL, check_kit.py это находит.
    K=libs/service-kit/src/main/java/dgm/kit
    mutate_repo "check_kit находит статус типа проблемы не по реестру" "$K/problem/ProblemType.java" 'NOT_FOUND("not-found", 404,' 'NOT_FOUND("not-found", 410,' \
      "статус в документе 404, в коде 410" tools/docs-checks/check_kit.py
    mutate_repo "check_kit находит тип проблемы, которого нет в реестре" "docs/05-architecture/conventions.md" '| `forbidden` | 403 |' '| `forbidden-x` | 403 |' \
      "есть в коде и нет в документе" tools/docs-checks/check_kit.py
    mutate_repo "check_kit находит запись в Outbox без обязательного столбца" "$K/outbox/OutboxWriter.java" 'event_type, topic, payload' 'event_type, payload' \
      "не задаёт обязательный столбец topic" tools/docs-checks/check_kit.py
    mutate_repo "check_kit находит столбец, которого нет в DDL" "$K/outbox/OutboxRelay.java" 'failed_at is null order by id' 'failed_on is null order by id' \
      "нет в DDL outbox: failed_on" tools/docs-checks/check_kit.py
    # 7. Правила маршрутов сервисов создаются из OpenAPI: ручная правка файла и правка прав в контракте без перегенерации ловятся.
    mutate_repo "gen_routes --check находит ручную правку правил маршрутов" "services/order-service/src/main/resources/dgm/routes.json" '"orders.read"' '"orders.write"' \
      "не равен результату gen_routes.py" tools/docs-checks/gen_routes.py --check
    mutate_repo "gen_routes --check находит правку прав в OpenAPI без перегенерации" "docs/06-api/openapi/order-service.yaml" '        - orders.read' '        - orders.create' \
      "не равен результату gen_routes.py" tools/docs-checks/gen_routes.py --check
    # Таблица шлюза (группа лимита и сервис каждого маршрута) создаётся из тех же файлов: правка группы вручную ловится
    mutate_repo "gen_routes --check находит ручную правку таблицы маршрутов шлюза" "services/api-gateway/src/main/resources/dgm/gateway-routes.json" '"limit": "order-create"' '"limit": "buyer"' \
      "не равен результату gen_routes.py" tools/docs-checks/gen_routes.py --check
    # 7а. Стек наблюдения: цель сбора, срок хранения, источник панели, оповещение без теста и сокет Docker с записью ловятся check_obs.py и check_compose.py.
    mutate_repo "check_obs находит сервис, которого нет среди целей Prometheus" "infra/obs/prometheus/prometheus.yml" '          - order-service:8444' '          - order-service-x:8444' \
      "цели .*, а сервисы Java в сети obs дают" tools/docs-checks/check_obs.py
    mutate_repo "check_obs находит срок хранения журналов не по документу" "infra/obs/loki/loki.yaml" 'retention_period: 168h' 'retention_period: 720h' \
      "хранение журналов 7 суток" tools/docs-checks/check_obs.py
    mutate_repo "check_obs находит панель с неизвестным источником данных" "infra/obs/grafana/dashboards/services.json" '"uid": "prometheus"' '"uid": "victoria"' \
      "не объявлен в datasources.yaml" tools/docs-checks/check_obs.py
    mutate_repo "check_obs находит оповещение без модульного теста" "infra/obs/prometheus/rules/dgm.yml" '  - name: dgm-backup
    rules:
' '  - name: dgm-backup
    rules:
      - alert: NewUntested
        expr: vector(1)
        labels: {severity: warning}
        annotations: {summary: a, description: b}
' \
      "NewUntested не покрыто модульным тестом" tools/docs-checks/check_obs.py
    mutate_repo "check_obs находит сокет Docker с правом записи" "compose.yaml" '/var/run/docker.sock:/var/run/docker.sock:ro' '/var/run/docker.sock:/var/run/docker.sock' \
      "сокет Docker подключается только как" tools/docs-checks/check_obs.py
    mutate_repo "check_obs находит пароль Grafana в окружении" "compose.yaml" 'GF_SECURITY_ADMIN_PASSWORD__FILE: /run/secrets/grafana_admin' 'GF_SECURITY_ADMIN_PASSWORD: admin' \
      "GF_SECURITY_ADMIN_PASSWORD" tools/docs-checks/check_obs.py
    mutate "сервис Java вне сети obs" "    networks: [edge, app, data, obs]" "    networks: [edge, app, data]" "сервис должен быть в сети obs"
    mutate "стек наблюдения без TLS получил сертификат" "      - grafana_admin
      - grafana_secret_key" "      - tls_ca.crt
      - grafana_admin
      - grafana_secret_key" "подключён tls_ca.crt: сертификат ему не нужен"
    # 7б. Цепочка поставок: действие без хеша коммита, задание без срока, широкие права токена, небезопасная передача токена и пропавший Dependabot ловятся check_workflows.py.
    W=".github/workflows/ci.yml"
    mutate_repo "check_workflows находит действие, закреплённое тегом, а не хешем" "$W" 'uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4.4.0' 'uses: actions/checkout@v4' \
      "закреплено не хешем коммита" tools/docs-checks/check_workflows.py
    mutate_repo "check_workflows находит задание без timeout-minutes" "$W" '  docs:
    runs-on: ubuntu-24.04
    timeout-minutes: 30
' '  docs:
    runs-on: ubuntu-24.04
' "нет timeout-minutes" tools/docs-checks/check_workflows.py
    mutate_repo "check_workflows находит запись в репозиторий в правах токена" "$W" 'permissions:
  contents: read

concurrency:' 'permissions:
  contents: write

concurrency:' "права GITHUB_TOKEN на верхнем уровне" tools/docs-checks/check_workflows.py
    mutate_repo "check_workflows находит сохранённый токен после checkout" "$W" '          persist-credentials: false' '          persist-credentials: true' \
      "нет persist-credentials: false" tools/docs-checks/check_workflows.py
    mutate_repo "check_workflows находит пропавший Dependabot для compose" ".github/dependabot.yml" 'package-ecosystem: docker-compose' 'package-ecosystem: pip' \
      "не описан пакетный менеджер docker-compose" tools/docs-checks/check_workflows.py
    # 7в. Руководство разработчика и runbook (шаг 19): пропавшая цель Makefile, неверный набор, лишний порт, несуществующий файл, неверная сумма
    #     набора, несуществующая переменная и недокументированная цель ловятся check_guide_commands.py.
    G="docs/09-operations/developer-guide.md"
    mutate_repo "check_guide_commands находит цель, которой нет в Makefile" "Makefile" 'stand-check: ## Проверить стенд целиком' 'stand-checkx: ## Проверить стенд целиком' \
      "цели make stand-check нет в Makefile" tools/docs-checks/check_guide_commands.py
    mutate_repo "check_guide_commands находит несуществующий набор SET" "$G" 'make up SET=dev-min         # PostgreSQL' 'make up SET=dev-mini        # PostgreSQL' \
      "набор SET=dev-mini" tools/docs-checks/check_guide_commands.py
    mutate_repo "check_guide_commands находит порт, которого нет в compose.debug.yaml" "compose.debug.yaml" '127.0.0.1:19094:9093' '127.0.0.1:19095:9093' \
      "порт 19095 из compose.debug.yaml не описан" tools/docs-checks/check_guide_commands.py
    mutate_repo "check_guide_commands находит несуществующий файл в руководстве" "$G" '`tools/docs-checks/check_guide_commands.py`' '`tools/docs-checks/check_guide_commandz.py`' \
      "check_guide_commandz.py нет в репозитории" tools/docs-checks/check_guide_commands.py
    mutate_repo "check_guide_commands находит расхождение суммы набора с memory-budget.md" "$G" '| `dev-min` | PostgreSQL, Kafka, Redis, заглушки | 1344 |' '| `dev-min` | PostgreSQL, Kafka, Redis, заглушки | 1345 |' \
      "лимиты набора dev-min 1345" tools/docs-checks/check_guide_commands.py
    mutate_repo "check_guide_commands находит переменную, которой нет в коде" "$G" '`DGM_PG_HOST`, `DGM_PG_PORT`' '`DGM_PG_HOSTX`, `DGM_PG_PORT`' \
      "переменной DGM_PG_HOSTX нет" tools/docs-checks/check_guide_commands.py
    mutate_repo "check_guide_commands находит недокументированную цель Makefile" "Makefile" 'otp: ## Текущий код' 'otp2: ## Текущий код' \
      "цель make otp2 не описана" tools/docs-checks/check_guide_commands.py
    # 8. Контракт сервисов: ответы из интеграционных тестов сверяются со схемами OpenAPI. Образцы собираются из примеров самого OpenAPI:
    #    полный набор проходит, испорченное поле, лишнее поле и пропавший обязательный образец отвергаются.
    REPO="$REPO" python3 - "$tmp/contract" <<'PY' || { echo "САМОПРОВЕРКА НЕ ПРОЙДЕНА: не удалось собрать образцы для контрактной проверки"; fail=1; }
import json, os, sys, yaml
root = sys.argv[1]
repo = os.environ.get('REPO') or os.getcwd()
def example(service, path, status='200'):
    doc = yaml.safe_load(open(os.path.join(repo, 'docs', '06-api', 'openapi', service + '.yaml'), encoding='utf-8'))
    return doc['paths'][path]['get']['responses'][status]['content']['application/json']['example']
def problem(status, code):
    return {'type': 'https://api.marketplace.example/problems/' + code, 'title': 'Проблема', 'status': status, 'detail': 'Пояснение', 'code': code,
            'correlationId': '0199e0a0-0000-7000-8000-0000000000c1'}
def put(service, name, path, status, body):
    d = os.path.join(root, 'services', service, 'build', 'contract-samples')
    os.makedirs(d, exist_ok=True)
    json.dump({'method': 'GET', 'path': path, 'status': status, 'body': body}, open(os.path.join(d, name + '.json'), 'w', encoding='utf-8'), ensure_ascii=False)
put('catalog-service', 'list', '/api/v1/products', 200, example('catalog-service', '/api/v1/products'))
put('catalog-service', 'list422', '/api/v1/products', 422, problem(422, 'validation-failed'))
put('catalog-service', 'card', '/internal/v1/products/0199e0a0-0000-7000-8000-000000000201', 200, example('catalog-service', '/internal/v1/products/{productId}'))
put('catalog-service', 'card404', '/internal/v1/products/0199e0a0-0000-7000-8000-000000000999', 404, problem(404, 'not-found'))
put('order-service', 'orders', '/api/v1/orders', 200, example('order-service', '/api/v1/orders'))
put('order-service', 'orders422', '/api/v1/orders', 422, problem(422, 'validation-failed'))
PY
    C="$REPO/tools/stand-checks/check_contract.py"
    if (cd "$tmp/contract" && python3 "$C" "$tmp/contract/services/catalog-service/build/contract-samples" "$tmp/contract/services/order-service/build/contract-samples") >"$tmp/out.txt" 2>&1; then
      echo "самопроверка ок: check_contract принимает ответы, совпадающие с OpenAPI"
    else
      echo "САМОПРОВЕРКА НЕ ПРОЙДЕНА: check_contract отверг ответы, взятые из примеров OpenAPI"; tail -n 8 "$tmp/out.txt"; fail=1
    fi
    sed -i 's/"issuedAt": "2026-10-03T12:00:00.123Z"/"issuedAt": "03.10.2026"/' "$tmp/contract/services/order-service/build/contract-samples/orders.json"
    expect_fail "check_contract находит поле ответа не по схеме OpenAPI" python3 "$C" "$tmp/contract/services/order-service/build/contract-samples"
    grep -q 'issuedAt' "$tmp/out.txt" || { echo "САМОПРОВЕРКА НЕ ПРОЙДЕНА: контракт упал не из-за issuedAt"; tail -n 5 "$tmp/out.txt"; fail=1; }
    sed -i 's/"issuedAt": "03.10.2026"/"issuedAt": "2026-10-03T12:00:00.123Z", "sellerCommission": 1500/' "$tmp/contract/services/order-service/build/contract-samples/orders.json"
    expect_fail "check_contract находит лишнее поле, которого нет в OpenAPI" python3 "$C" "$tmp/contract/services/order-service/build/contract-samples"
    grep -q 'sellerCommission' "$tmp/out.txt" || { echo "САМОПРОВЕРКА НЕ ПРОЙДЕНА: контракт упал не из-за лишнего поля"; tail -n 5 "$tmp/out.txt"; fail=1; }
    rm -f "$tmp/contract/services/catalog-service/build/contract-samples/card404.json"
    expect_fail "check_contract находит пропавший образец обязательной операции" python3 "$C" "$tmp/contract/services/catalog-service/build/contract-samples"
    grep -q 'getProductCard' "$tmp/out.txt" || { echo "САМОПРОВЕРКА НЕ ПРОЙДЕНА: контракт упал не из-за пропавшего образца"; tail -n 5 "$tmp/out.txt"; fail=1; }
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
