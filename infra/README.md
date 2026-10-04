# infra

Инфраструктура стенда: Docker Compose с профилями (`compose.yaml` в корне), центр сертификации и секреты, конфигурация хранилищ, позднее Keycloak (realm как код), заглушки внешних систем, наблюдаемость, Helm-чарты и скрипты развёртывания.

| Каталог | Что внутри | Документ |
| --- | --- | --- |
| [`pki/`](pki/README.md) | Частный центр сертификации, выпуск сертификатов контейнеров и секретов, проверка | [ADR-022](../docs/05-architecture/adr/ADR-022-internal-traffic-encryption.md) |
| [`postgres/`](postgres/README.md) | Настройки PostgreSQL 16, правила входа `pg_hba.conf` (только TLS), сценарий запуска | [c4-deployment.md](../docs/05-architecture/c4-deployment.md) |
| [`redis/`](redis/README.md) | Настройки Redis 8, пользователи ACL по сервисам, сценарий запуска | [ADR-012](../docs/05-architecture/adr/ADR-012-reservation-redis-timer.md) |
| [`kafka/`](kafka/README.md) | Kafka в режиме KRaft с SSL и ACL, задание `kafka-init`, темы и права из AsyncAPI | [ADR-003](../docs/05-architecture/adr/ADR-003-kafka-events.md) |
| [`storage/`](storage/README.md) | Объектное хранилище S3 по TLS (RustFS), задание `storage-init`: бакет, политика и учётная запись на один бакет | [ADR-024](../docs/05-architecture/adr/ADR-024-object-storage.md) |

## Как поднять

```bash
make up SET=dev-min            # PostgreSQL, Redis, Kafka (и заглушки, когда они появятся); сертификаты и секреты создаются сами
make up SET=dev-min DEBUG=1    # то же плюс порты на 127.0.0.1: 15432 (PostgreSQL), 16379 (Redis), 19093 (Kafka), 19000 (хранилище, если есть профиль storage)
make ps                        # состояние
make logs S=kafka              # журнал контейнера
make down                      # остановить, данные сохраняются
make reset                     # остановить и удалить данные
```

Наборы профилей и память каждого: [c4-deployment.md](../docs/05-architecture/c4-deployment.md) (раздел 3), [memory-budget.md](../docs/09-operations/memory-budget.md). Проверка соответствия `compose.yaml` документам: `python3 tools/docs-checks/check_compose.py`. Проверка шифрования и прав на поднятом стенде: `tools/stand-checks/infra_security.sh` (PostgreSQL, Redis, Kafka), `tools/stand-checks/storage_checks.sh` (хранилище).

## Принципы, общие для всех контейнеров

| Принцип | Как выполнено |
| --- | --- |
| Всё зашифровано | TLS у PostgreSQL, Redis и объектного хранилища, SSL с сертификатом клиента у Kafka. Профиля без шифрования нет (ADR-022) |
| Минимум прав | Пользователь не root, `cap_drop: ALL`, `no-new-privileges`, файловая система только для чтения, записи только в `tmpfs` и именованные тома |
| Секреты не в окружении | Файлы `/run/secrets/<имя>`, в переменных окружения секретов нет, в образах и в Git тоже |
| Память ограничена | `mem_limit` и `memswap_limit` из бюджета памяти, своп выключен |
| Сеть закрыта | Хранилища только в сети `data` (`internal: true`), портов на хосте нет |
