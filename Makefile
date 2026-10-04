# Команды проекта. Подсказка: make
SHELL := /bin/bash
.DEFAULT_GOAL := help

SERVICES    := catalog-service inventory-service order-service payment-service delivery-service platform-service api-gateway
JAVA_IMAGE  ?= eclipse-temurin:25-jre-noble
IMAGE_TAG   ?= dev
GRADLEW     := ./gradlew --console=plain

.PHONY: help
help: ## Показать список команд
	@awk 'BEGIN {FS = ":.*## "} /^[a-zA-Z0-9_.-]+:.*## / {printf "  %-16s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

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
	    --build-arg BASE_DIGEST=$$base -t dgm/$$s:$(IMAGE_TAG) . || exit 1; \
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

.PHONY: up
up: certs secrets ## Поднять набор профилей и дождаться готовности: make up SET=dev-min [DEBUG=1]
	@test -n "$(PROFILES)" || { echo "Неизвестный набор SET=$(SET). Доступны: $(ALL_SETS)"; exit 2; }
	COMPOSE_PROFILES=$(PROFILES) $(COMPOSE) up -d --remove-orphans
	python3 tools/stand-checks/wait.py --profiles $(PROFILES)

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

.PHONY: storage-init
storage-init: ## Повторить инициализацию хранилища: бакет, политика, пароль учётной записи (хранилище поднято, профиль storage)
	$(COMPOSE) run -T --rm --no-deps storage-init

.PHONY: storage-check
storage-check: ## Проверить хранилище: TLS, права учётной записи, подписанные ссылки (поднято с DEBUG=1, нужен aws CLI v2)
	tools/stand-checks/storage_checks.sh

.PHONY: clean
clean: ## Удалить результаты сборки
	$(GRADLEW) clean
