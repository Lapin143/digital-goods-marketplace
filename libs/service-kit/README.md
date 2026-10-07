# service-kit: общий каркас сервисов

Единственная общая библиотека проекта ([правило модульности 5](../../docs/05-architecture/decomposition.md)): только технический каркас, бизнес-классов и общих DTO здесь нет. Описание компонентов: [c4-components.md](../../docs/05-architecture/c4-components.md), раздел 2.

## Состав

| Пакет `dgm.kit.` | Что делает | Основание |
| --- | --- | --- |
| `problem` | Реестр из 39 типов проблем (`ProblemType`), ответ `application/problem+json` (RFC 9457), `@ControllerAdvice` | conventions, раздел 9. Реестр сверяет `tools/docs-checks/check_kit.py` |
| `route` | Правила маршрутов из JSON (метод, путь, области, роли, запрет сессии по SMS, разрешённые вызывающие), `PathGuard` | Маршрута нет в контракте: 404. Пути с `;`, `%`, `//`, `\`, `..` и не ASCII: 400 |
| `security` | `JwtFilter` (подпись, `iss`, `aud`, срок, области, роль, второй фактор, SMS-сессия), `CallerFilter` (CN сертификата), `JwtDecoders`, `AccessRules` | ADR-021, ADR-022. Нет токена или он неверен: 401, JWKS недоступен: 503, не хватает прав: 403 |
| `trace` | `traceparent` и `X-Correlation-Id` в журнал и в исходящие вызовы | NFT-6.0 |
| `secret` | `SecretValue` (в `toString`, журнал и JSON не попадает), чтение секретов из файлов | conventions, раздел 13; ADR-022 |
| `time` | `Clocks`: единственное место, где берутся системные часы | test-strategy, «Часы» |
| `tls`, `kafka` | Контексты TLS и настройки клиентов PostgreSQL и Kafka из файлов секретов | ADR-022 |
| `outbox` | Запись в `outbox` в транзакции изменения (`OutboxWriter`), публикатор с блокировкой лидера, повторами 1, 2, 4 … 30 с и «парковкой» после 10 неудач (`OutboxRelay`), очистка (`OutboxCleaner`), конверт CloudEvents | ADR-005 |
| `events` | Потребитель: проверка конверта, `processed_event` и обработчик в одной транзакции, повторы 1, 5, 25 с, DLQ с заголовками `dlq-*` | ADR-006, ADR-011 |
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
| `make kit-test` | Интеграционные тесты Outbox и потребителя (`integrationTest`, метка `integration`) | Стенд: `make up SET=dev-min DEBUG=1` и `make db-migrate S="order-service inventory-service"` |

Интеграционные тесты идут на настоящих PostgreSQL и Kafka стенда по TLS (решение 11 в [test-strategy.md](../../docs/08-testing/test-strategy.md)). Секреты берутся из каталога `DGM_SECRETS_DIR` (по умолчанию `secrets/`), адреса стенда меняются переменными `DGM_PG_HOST`, `DGM_PG_PORT`, `DGM_KAFKA_BOOTSTRAP`. Каждый запуск работает со своим префиксом имён, поэтому повторный запуск не мешает предыдущему.

## Риск общей библиотеки

Все сервисы собираются с одной версией каркаса, поэтому его правка затрагивает всех: это связанность версий, которую выбирает [правило модульности 5](../../docs/05-architecture/decomposition.md) осознанно. Защита: в каркас попадает только то, что нужно всем сервисам и не содержит бизнес-смысла; каждая публичная операция покрыта тестами; контракты сообщений и ошибок проверяются по документам.
