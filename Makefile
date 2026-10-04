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

.PHONY: clean
clean: ## Удалить результаты сборки
	$(GRADLEW) clean
