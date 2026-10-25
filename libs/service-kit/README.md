# service-kit: общий каркас сервисов

Единственная общая библиотека проекта ([правило модульности 5](../../docs/05-architecture/decomposition.md)): только технический каркас, бизнес-классов и общих DTO здесь нет. Описание компонентов: [c4-components.md](../../docs/05-architecture/c4-components.md), раздел 2.

## Состав

| Пакет `dgm.kit.` | Что делает | Основание |
| --- | --- | --- |
| `problem` | Реестр из 41 типа проблем (`ProblemType`), ответ `application/problem+json` (RFC 9457), `@ControllerAdvice` | conventions, раздел 9. Реестр сверяет `tools/docs-checks/check_kit.py` |
| `route` | Правила маршрутов из JSON (метод, путь, области, роли, запрет сессии по SMS, разрешённые вызывающие), `PathGuard` | Маршрута нет в контракте: 404. Пути с `;`, `%`, `//`, `\`, `..` и не ASCII: 400 |
| `security` | `JwtFilter` (подпись, `iss`, `aud`, срок, области, роль, второй фактор, SMS-сессия), `CallerFilter` (CN сертификата), `JwtDecoders`, `AccessRules` | ADR-021, ADR-022. Нет токена или он неверен: 401, JWKS недоступен: 503, не хватает прав: 403 |
| `trace` | `traceparent` и `X-Correlation-Id` в журнал и в исходящие вызовы | NFT-6.0 |
| `secret` | `SecretValue` (в `toString`, журнал и JSON не попадает), чтение секретов из файлов | conventions, раздел 13; ADR-022 |
| `time` | `Clocks`: единственное место, где берутся системные часы | test-strategy, «Часы» |
| `tls`, `kafka` | Контексты TLS и настройки клиентов PostgreSQL и Kafka из файлов секретов | ADR-022 |
| `outbox` | Запись в `outbox` в транзакции изменения (`OutboxWriter`), публикатор с блокировкой лидера, повторами 1, 2, 4 … 30 с и «парковкой» после 10 неудач (`OutboxRelay`), очистка (`OutboxCleaner`), конверт CloudEvents | ADR-005 |
| `events` | Потребитель: проверка конверта, `processed_event` и обработчик в одной транзакции, повторы 1, 5, 25 с, DLQ с заголовками `dlq-*` | ADR-006, ADR-011 |
| `idempotency` | Охранник ключей идемпотентности (`IdempotencyGuard`) и фильтр заголовка `Idempotency-Key` (`IdempotencyFilter`): ключ занимается отдельной короткой транзакцией, действие и ответ сохраняются в одной, повтор получает прежний ответ (`Idempotency-Replayed: true`), другое тело даёт 422, незавершённый запрос 409. Запоминаются только ответы 2xx. Очистка записей старше 24 часов идёт задачей обслуживания | ADR-006, уровень 1. Обязательность заголовка берётся из OpenAPI (`gen_routes.py`) |
| `web` | `BodyLimitFilter`: предел тела 2 МБ (вебхуки 64 КБ) по длине и по фактически прочитанным байтам, `RequestBodies`, `CachedBodyRequest`, `Etags`, постраничный курсор, разбор параметров запроса | Р-29, ADR-021 пункт 5 |
| `crypto` | `WebhookSignature`: HMAC-SHA-256 от «метка.тело», окно 5 минут, несколько секретов (смена ключа без простоя), сравнение за постоянное время. Фильтр `WebhookSignatureFilter` ставится на операции с `x-webhook-secret` | ADR-015, схема `webhookSignature` |
| `redis` | `KitRedis`: TLS с клиентским сертификатом, пользователь ACL, пароль из секрета, все операции скриптами Lua по SHA-1 (`get`, `set` с обязательным сроком, `setIfAbsent`, счётчик с окном, `delete`), предел команды 500 мс. Отказ превращается в 503 `dependency-unavailable` с `Retry-After` | ADR-022. Для SMS Redis обязателен: без него действие запрещено |
| `keycloak` | `KeycloakAdminClient`: токен сервисной учётной записи (кэш, обновление за 30 с до срока), чтение и постраничный обход пользователей, атрибуты, включение и отключение, роли, список и удаление способов входа. Предел операции 2 с, повторы безопасны | ADR-025. Используется `platform-service` |
| `config` | Слушатель параметров платформы (`ConfigListener`: собственный поток и потребитель без группы, чтение сжатой темы `platform.config` с начала), реестр значений (`ConfigRegistry`, `ConfigValue`): применяется версия выше сохранённой, значение в границах сервиса и INV-07 с соседом; закреплённые ключи профиля `fast-time` не применяются; метрики `config_*` | ADR-017, Р-168 и Р-175 руководства среза 3, правило 19 |
| `log` | Маскирование значений в сообщениях об ошибках | conventions, раздел 13 |

## Архитектурные правила

Правила ArchUnit лежат в тестовых фикстурах (`src/testFixtures/.../ServiceArchRules.java`) и в рабочий jar не входят. Сервис подключает их одним полем:

```java
@AnalyzeClasses(packages = "dgm.order", importOptions = ImportOption.DoNotIncludeTests.class)
class OrderArchitectureTest {
    @ArchTest
    static final ArchTests RULES = ArchTests.in(ServiceArchRules.class);
}
```

Каждое правило проверяется на заведомо плохом классе (`src/test/java/dgm/fixtures`, тест `ServiceArchRulesNegativeTest`): правило, которое ничего не ловит, тест не пропускает.

## Тесты

| Команда | Что идёт | Нужно |
| --- | --- | --- |
| `make test` | Модульные тесты и архитектурные правила (`test`) | Только JDK 25 |
| `make kit-test` | Интеграционные тесты Outbox, потребителя, охранника идемпотентности (20 параллельных повторов) и клиента Redis (`integrationTest`, метка `integration`) | Стенд: `make up SET=dev-min DEBUG=1` и `make db-migrate S="order-service inventory-service"` |

Интеграционные тесты идут на настоящих PostgreSQL и Kafka стенда по TLS (решение 11 в [test-strategy.md](../../docs/08-testing/test-strategy.md)). Секреты берутся из каталога `DGM_SECRETS_DIR` (по умолчанию `secrets/`), адреса стенда меняются переменными `DGM_PG_HOST`, `DGM_PG_PORT`, `DGM_KAFKA_BOOTSTRAP`, `DGM_REDIS_HOST`, `DGM_REDIS_PORT`. Каждый запуск работает со своим префиксом имён, поэтому повторный запуск не мешает предыдущему.

## Риск общей библиотеки

Все сервисы собираются с одной версией каркаса, поэтому его правка затрагивает всех: это связанность версий, которую выбирает [правило модульности 5](../../docs/05-architecture/decomposition.md) осознанно. Защита: в каркас попадает только то, что нужно всем сервисам и не содержит бизнес-смысла; каждая публичная операция покрыта тестами; контракты сообщений и ошибок проверяются по документам.
