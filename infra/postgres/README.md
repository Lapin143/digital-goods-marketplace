# infra/postgres

PostgreSQL 16 (образ `postgres:16.15-trixie`), один экземпляр на семь баз (шесть сервисов и Keycloak, [ADR-002](../../docs/05-architecture/adr/ADR-002-microservices-consolidation.md)).

| Файл | Назначение |
| --- | --- |
| `postgresql.conf` | Память по бюджету (`shared_buffers` 128 МБ, `max_connections` 100, `work_mem` 4 МБ), TLS от 1.2, `scram-sha-256`, журнал в стандартный вывод |
| `pg_hba.conf` | Сокет внутри контейнера без пароля (инициализация и проверка готовности), по сети только `hostssl` и пароль, `hostnossl` отвергается |
| `entrypoint.sh` | Копирует ключ сервера в `tmpfs` с правами 0600 (PostgreSQL не принимает ключ с чужими правами, а секреты Docker монтируются с правами хоста) и запускает штатный сценарий образа |
| `init/` | Создание баз и ролей при первой инициализации (шаг 7 Ф3) |

## Как это работает

- **Запуск без root.** Контейнер работает под UID 999 с файловой системой только для чтения. Сокет PostgreSQL, каталог ключей и `/tmp` лежат в `tmpfs`, данные в томе `pgdata`.
- **TLS.** Клиент подключается с `sslmode=verify-full` и корневым сертификатом `tls_ca.crt`. Имя в сертификате сервера `postgres`, также есть `localhost` и `127.0.0.1` (отладка).
- **Аутентификация.** Администратор `postgres` (пароль `db_postgres_admin`) нужен только для инициализации. Сервисы ходят под своими ролями, без прав суперпользователя (шаг 7).
- **Готовность.** `pg_isready` по TCP на `127.0.0.1`. На время инициализации сервер слушает только сокет, поэтому TCP-проверка не даёт ложной готовности.
- **Архив журнала транзакций** (`archive_mode`, том `pgarchive`) включается в Ф6 вместе с резервным копированием ([c4-deployment.md](../../docs/05-architecture/c4-deployment.md), решение 11).

## Подключиться с хоста (только для отладки)

```bash
make up SET=dev-min DEBUG=1
PGPASSWORD=$(cat secrets/db_postgres_admin) psql "host=localhost port=15432 user=postgres dbname=postgres sslmode=verify-full sslrootcert=secrets/tls_ca.crt"
```
