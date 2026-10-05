# Команды проекта. Подсказка: make
SHELL := /bin/bash
.DEFAULT_GOAL := help

SERVICES    := catalog-service inventory-service order-service payment-service delivery-service platform-service api-gateway
JAVA_IMAGE  ?= eclipse-temurin:25-jre-noble
NODE_IMAGE  ?= node:24.21.0-alpine3.24
NGINX_IMAGE ?= nginx:1.30.5-alpine3.24
IMAGE_TAG   ?= dev
IMAGE_SOURCE ?= https://github.com/Lapin143/digital-goods-marketplace
GRADLEW     := ./gradlew --console=plain

.PHONY: help
help: ## Показать список команд
	@awk 'BEGIN {FS = ":.*## "} /^[a-zA-Z0-9_.-]+:.*## / {printf "  %-18s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

.PHONY: build
build: ## Собрать все модули и прогнать модульные тесты (Gradle)
	$(GRADLEW) build

.PHONY: test
test: ## Только тесты
	$(GRADLEW) test

.PHONY: jars
jars: ## Собрать исполняемые jar сервисов без тестов
	$(GRADLEW) bootJar

.PHONY: docker-images
docker-images: ## Собрать образы из готовых jar (сначала make jars или make build)
	@docker pull -q $(JAVA_IMAGE) >/dev/null
	@base=$$(docker image inspect --format '{{index .RepoDigests 0}}' $(JAVA_IMAGE) 2>/dev/null || echo unknown); \
	for s in $(SERVICES); do \
	  echo "== образ dgm/$$s:$(IMAGE_TAG)"; \
	  docker build -q -f docker/Dockerfile.service --build-arg SERVICE=$$s --build-arg JAVA_IMAGE=$(JAVA_IMAGE) \
	    --build-arg BASE_DIGEST=$$base --label org.opencontainers.image.source=$(IMAGE_SOURCE) -t dgm/$$s:$(IMAGE_TAG) . || exit 1; \
	done

.PHONY: images
images: jars docker-images ## Собрать jar и образы всех сервисов

.PHONY: certs
certs: ## Центр сертификации (если нет) и сертификаты контейнеров, действующие не перевыпускаются
	python3 infra/pki/dgm_pki.py certs

.PHONY: secrets
secrets: ## Создать недостающие секреты (пароли, ключи) по infra/pki/inventory.json
	python3 infra/pki/dgm_pki.py secrets

.PHONY: pki-verify
pki-verify: ## Проверить сертификаты и секреты
	python3 infra/pki/dgm_pki.py verify

.PHONY: pki-status
pki-status: ## Сколько дней осталось у сертификатов
	python3 infra/pki/dgm_pki.py status

.PHONY: pki-test
pki-test: ## Тесты скрипта сертификатов и секретов
	python3 -m unittest infra/pki/test_pki.py

# ---------------------------------------------------------------- стенд (Docker Compose)
# Наборы профилей (c4-deployment.md, раздел 3). Состав сверяет tools/docs-checks/check_compose.py.
SET             ?= dev-min
SET_dev-min      := infra,stubs
SET_dev-platform := infra,stubs,platform,storage
SET_dev-auth     := infra,stubs,auth,gateway
SET_dev-purchase := infra,stubs,purchase
SET_full         := infra,stubs,auth,gateway,purchase,platform,storage
SET_full-obs     := $(SET_full),obs
SET_server       := $(SET_full-obs),ops
ALL_SETS        := dev-min dev-platform dev-auth dev-purchase full full-obs server
PROFILES         := $(SET_$(SET))
COMPOSE          := docker compose -f compose.yaml $(if $(DEBUG),-f compose.debug.yaml)
# Alloy (профиль obs) читает журналы контейнеров через сокет Docker и входит в его группу. Номер группы берётся у сокета хоста;
# в Docker Desktop сокет принадлежит root: задайте DOCKER_GID=0
DOCKER_GID       ?= $(shell stat -L -c %g /var/run/docker.sock 2>/dev/null || echo 0)
export DOCKER_GID
WAIT_TIMEOUT     ?= 420

.PHONY: up
up: certs secrets ## Поднять набор профилей и дождаться готовности: make up SET=dev-min [DEBUG=1]
	@test -n "$(PROFILES)" || { echo "Неизвестный набор SET=$(SET). Доступны: $(ALL_SETS)"; exit 2; }
	@# Образы сервисов Java собираются из jar (ADR-023), поэтому наборы с сервисами сначала собирают jar и образы
	@case ",$(PROFILES)," in *,purchase,*|*,platform,*|*,gateway,*) $(MAKE) --no-print-directory images;; esac
	COMPOSE_PROFILES=$(PROFILES) $(COMPOSE) up -d --build --remove-orphans
	python3 tools/stand-checks/wait.py --profiles $(PROFILES) --timeout $(WAIT_TIMEOUT)

.PHONY: down
down: ## Остановить и удалить контейнеры (тома сохраняются)
	$(COMPOSE) --profile '*' down --remove-orphans

.PHONY: reset
reset: ## Остановить и удалить контейнеры вместе с томами (данные баз и Kafka пропадут)
	$(COMPOSE) --profile '*' down --remove-orphans --volumes

.PHONY: ps
ps: ## Состояние контейнеров
	$(COMPOSE) --profile '*' ps -a

.PHONY: logs
logs: ## Журналы: make logs S=kafka
	$(COMPOSE) --profile '*' logs --tail 100 $(S)

.PHONY: compose-config
compose-config: ## Проверить и показать итоговую конфигурацию Compose
	COMPOSE_PROFILES=$(PROFILES) $(COMPOSE) config

.PHONY: kafka-topics
kafka-topics: ## Пересоздать topics.sh из AsyncAPI
	python3 infra/kafka/gen_kafka.py

.PHONY: db-roles
db-roles: ## Повторить создание баз и ролей PostgreSQL (новая роль, смена пароля после замены секрета)
	$(COMPOSE) exec -T postgres psql -U postgres -d postgres -v ON_ERROR_STOP=1 -q -f /docker-entrypoint-initdb.d/10-databases-and-roles.sql

.PHONY: db-migrations
db-migrations: ## Пересоздать миграции Flyway из частей DDL (tools/docs-checks/db/parts)
	python3 tools/docs-checks/db/build.py
	python3 tools/docs-checks/db/gen_migrations.py

.PHONY: db-check
db-check: ## Применить миграции Flyway к поднятому PostgreSQL по TLS и проверить права ролей (стенд поднят, набор с infra)
	tools/stand-checks/db_migrations.sh

.PHONY: db-migrate
db-migrate: ## Применить миграции Flyway сервисов к стенду: make db-migrate [S="order-service inventory-service"], без S все шесть
	tools/stand-checks/migrate_stand.sh $(S)

.PHONY: kit-test
kit-test: ## Интеграционные тесты каркаса, каталога и заказов на стенде (make up SET=dev-min DEBUG=1 и make db-migrate S="order-service inventory-service")
	DGM_SECRETS_DIR=$(CURDIR)/secrets ./gradlew --console=plain --continue :libs:service-kit:integrationTest \
	  :services:catalog-service:integrationTest :services:order-service:integrationTest

.PHONY: contract-check
contract-check: ## Сверить ответы сервисов, записанные интеграционными тестами (make kit-test), со схемами OpenAPI
	python3 tools/stand-checks/check_contract.py

.PHONY: services-check
services-check: ## Проверить контейнеры сервисов Java: здоровье, память, журнал JSON, порты (подняты наборы dev-purchase и dev-platform)
	python3 tools/stand-checks/check_services.py

.PHONY: storage-init
storage-init: ## Повторить инициализацию хранилища: бакет, политика, пароль учётной записи (хранилище поднято, профиль storage)
	$(COMPOSE) run -T --rm --no-deps storage-init

.PHONY: storage-check
storage-check: ## Проверить хранилище: TLS, права учётной записи, подписанные ссылки (поднято с DEBUG=1, нужен aws CLI v2)
	tools/stand-checks/storage_checks.sh

.PHONY: stubs-test
stubs-test: ## Модульные тесты заглушек внешних систем (Node из того же образа, что в контейнере; нужен только Docker)
	docker run --rm -v "$(CURDIR)/tools/external-stubs:/app:ro" -w /app $(NODE_IMAGE) node --test --test-reporter=spec "test/*.test.mjs"

.PHONY: stubs-check
stubs-check: ## Проверить контейнер заглушек: TLS, ключи, SMTP, вебхуки получателю (поднято с DEBUG=1, профиль stubs)
	tools/stand-checks/stubs_checks.sh

# Keycloak: адреса отладочных портов (make up ... DEBUG=1), по которым проверки заходят минуя шлюз
KC_ENV := KC_TARGET=https://127.0.0.1:18445 STUBS_TARGET=https://127.0.0.1:18443 STUBS_ADMIN_TARGET=https://127.0.0.1:18444

.PHONY: realm
realm: ## Пересоздать файл realm Keycloak из генератора (infra/keycloak/gen_realm.py)
	python3 infra/keycloak/gen_realm.py

.PHONY: keycloak-users
keycloak-users: ## Создать тестовых пользователей Keycloak: случайные пароли в secrets/test_users.json (поднято с DEBUG=1, профиль auth)
	$(KC_ENV) python3 infra/keycloak/provision_test_users.py

.PHONY: keycloak-reimport
keycloak-reimport: ## Применить изменённый realm: удалить realm dgm и перезапустить Keycloak (пользователи пропадут; поднято с DEBUG=1)
	$(KC_ENV) python3 infra/keycloak/reimport_realm.py
	$(COMPOSE) --profile '*' restart keycloak
	python3 tools/stand-checks/wait.py --profiles infra,stubs,auth --timeout 240

.PHONY: keycloak-test
keycloak-test: ## Модульные тесты клиента входа Keycloak (сеть не нужна)
	python3 -m unittest tools/stand-checks/test_kc_client.py

.PHONY: keycloak-check
keycloak-check: ## Проверить Keycloak: вход по ролям, второй фактор, сроки, перебор, VK ID, Argon2 (поднято с DEBUG=1, профили infra, stubs, auth)
	tools/stand-checks/keycloak_checks.sh

.PHONY: gateway-check
gateway-check: ## Проверить шлюз: маршруты, токены Keycloak, лимиты, отказ открытым (подняты dev-auth и dev-purchase с DEBUG=1, make keycloak-users)
	$(KC_ENV) tools/stand-checks/gateway_checks.sh

.PHONY: stand-check
stand-check: ## Проверить стенд целиком после make up: состав, готовность, порты, сети, секреты, память (SET=full, DEBUG=1 если был)
	python3 tools/stand-checks/stand_checks.py up $(PROFILES)

.PHONY: stand-down-check
stand-down-check: ## Остановить стенд и проверить чистое состояние: make down оставляет тома, make reset убирает всё (DEBUG=1 если был)
	python3 tools/stand-checks/stand_checks.py down

.PHONY: web-check
web-check: ## Проверить веб-интерфейс: доступ только шлюзу, TLS, заголовки, журнал (поднят набор с профилем gateway с DEBUG=1)
	tools/stand-checks/webapp_checks.sh

.PHONY: obs-validate
obs-validate: ## Проверить конфигурацию стека наблюдения проверяющими программами образов (promtool, amtool, Loki, Tempo, Alloy) и тесты правил оповещений
	tools/stand-checks/obs_validate.sh

.PHONY: obs-check
obs-check: ## Проверить стек наблюдения: цели Prometheus, Grafana, журнал в Loki, трасса в Tempo, оповещение письмом (make up SET=full-obs DEBUG=1)
	python3 tools/stand-checks/obs_checks.py

.PHONY: smoke
smoke: ## Дымовой тест: токен, операции через шлюз, ST-03, mTLS, Outbox → Kafka, сквозной идентификатор (набор full, DEBUG=1, make keycloak-users)
	$(KC_ENV) python3 tools/stand-checks/smoke.py

.PHONY: smoke-sabotage
smoke-sabotage: ## Проверка проверки: испорченный токен, область и сертификат делают дымовой тест красным
	$(KC_ENV) tools/ci/smoke-sabotage.sh

.PHONY: stateless-check
stateless-check: ## NFT-1.3: два экземпляра order-service и catalog-service, один перезапускается посреди серии (подняты dev-auth и dev-purchase, DEBUG=1)
	$(KC_ENV) python3 tools/stand-checks/stateless_check.py

.PHONY: clean
clean: ## Удалить результаты сборки
	$(GRADLEW) clean
