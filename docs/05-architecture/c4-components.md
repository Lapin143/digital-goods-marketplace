# C4, уровень 3: обзор компонентов и общий каркас

| Поле | Содержание |
| --- | --- |
| Документ | C4 Component: как читать уровень 3, общий каркас сервисов, сквозные матрицы покрытия |
| Фаза | Ф2, шаг 6 |
| Нотация | [c4-notation.md](c4-notation.md) |
| Основание | [decomposition.md](decomposition.md) (правила модульности), [c4-containers.md](c4-containers.md), [ADR-003](adr/ADR-003-kafka-events.md), [ADR-005](adr/ADR-005-transactional-outbox.md), [ADR-006](adr/ADR-006-idempotency.md), [ADR-011](adr/ADR-011-guaranteed-delivery.md), [ADR-021](adr/ADR-021-api-gateway.md), [ADR-022](adr/ADR-022-internal-traffic-encryption.md) |
| Предыдущий уровень | [c4-containers.md](c4-containers.md) |
| Следующий шаг | `sequence-*.md` (шаг 7), `c4-deployment.md` (шаг 8) |

Уровень компонентов показывает, как устроен один контейнер внутри: какие в нём контроллеры, сервисы, репозитории, слушатели и планировщики и кто кого вызывает. Он нужен только там, где внутри нетривиальная логика. Из шести прикладных сервисов диаграммы есть у четырёх, где сосредоточены сага, блокировки, очереди, таймеры и деньги. У двух остальных устройство описано таблицами.

## 1. Что где лежит

| Сервис | Файл | Форма | Почему так |
| --- | --- | --- | --- |
| `order-service` | [c4-components-order-service.md](c4-components-order-service.md) | Две диаграммы и таблицы | Оркестратор саги, конечный автомат, таймеры, Outbox |
| `inventory-service` | [c4-components-inventory-service.md](c4-components-inventory-service.md) | Две диаграммы и таблицы | Резерв с блокировками, шифрование, таймер Redis, выдача значений |
| `delivery-service` | [c4-components-delivery-service.md](c4-components-delivery-service.md) | Две диаграммы и таблицы | Очередь в базе, повторы, контроль 30 минут, статус письма |
| `payment-service` | [c4-components-payment-service.md](c4-components-payment-service.md) | Две диаграммы и таблицы | Адаптер шлюза, подпись вебхуков, сверка, возвраты |
| `catalog-service` | [c4-components-catalog-service.md](c4-components-catalog-service.md) | Таблицы | Два модуля без сложных потоков, не на критическом пути |
| `platform-service` | [c4-components-platform-service.md](c4-components-platform-service.md) | Таблицы по модулям | Четыре модуля, диаграмма мало добавит к таблицам |
| `finance-service` (R2) | Нет | Нет | Появится с релизом R2: компоненты споров и баланса описываются перед его началом |

Каждый файл устроен одинаково: перечень компонентов (Spring-слой, ответственность, что вызывает, какие события), диаграммы, таблица «компонент → инварианты и требования», проверка и решения.

## 2. Общий каркас сервисов (`service-kit`)

[Правило модульности 5](decomposition.md): общий код между сервисами только каркас, бизнес-классов и общих jar с DTO нет. Каркас оформлен библиотекой `service-kit` (подключается Maven-зависимостью, в Ф3 заводится каталог `libs/service-kit`). Компоненты каркаса есть в каждом сервисе, и на диаграммах из них показаны только те, что нужны для понимания схемы.

| Компонент | Алиас | Spring-слой | Что делает | Где используется | Основание |
| --- | --- | --- | --- | --- | --- |
| Фильтр токена | `jwt-filter` | Spring Security, resource server | Проверяет подпись, срок, издателя и адресата токена по ключам Keycloak (JWKS), проверяет область токена на маршрут. Роль и владельца ресурса проверяет сам сервис | Все сервисы с внешними маршрутами | [ADR-021](adr/ADR-021-api-gateway.md) |
| Фильтр вызывающих | `caller-filter` | Фильтр mTLS | Сверяет имя вызывающего сервиса из сертификата со списком разрешённых для маршрута (`x-allowed-callers`) | Все сервисы с внутренними маршрутами. На диаграммах нарисован у `inventory-service`, где он защищает значения ключей | [ADR-022](adr/ADR-022-internal-traffic-encryption.md) |
| Охранник идемпотентности | `idempotency-guard` | `HandlerInterceptor` | Обрабатывает `Idempotency-Key`: запись в `idempotency_key`, повтор с тем же телом возвращает сохранённый ответ | Сервисы с `POST`, создающими данные | [ADR-006](adr/ADR-006-idempotency.md), уровень 1 |
| Сквозной идентификатор и ошибки | `trace-filter` | Фильтр и `@ControllerAdvice` | Передаёт `traceparent` и `X-Correlation-Id` в контекст логов и в исходящие вызовы и события, формирует ответы об ошибке в одном формате (`application/problem+json`) | Все сервисы | NFT-6.0, `conventions.md` (шаг 10) |
| Потребитель событий | `event-consumer` | Контейнер слушателей Kafka и обработчик ошибок | Читает темы сервиса. Проверяет `processed_event`, вызывает обработчик, после фиксации транзакции подтверждает смещение. Повторы с паузами 1, 5, 25 с, затем сообщение уходит в `<тема>.dlq`, чтение продолжается | Все сервисы | [ADR-003](adr/ADR-003-kafka-events.md), [ADR-006](adr/ADR-006-idempotency.md), [ADR-011](adr/ADR-011-guaranteed-delivery.md) |
| Слушатель параметров | `config-listener` | Слушатель Kafka без группы | Читает сжатую тему `platform.config` с начала при старте и дальше, хранит копию значений в памяти. До первого чтения действуют значения из конфигурации | Все сервисы R1, кроме `platform-service` (он их хозяин) | [decomposition.md](decomposition.md), раздел 8 |
| Публикатор Outbox | `outbox-relay` | `@Scheduled`, раз в 200 мс | Публикует записи таблицы `outbox` в Kafka. Устройство в [c4-components-order-service.md](c4-components-order-service.md), раздел 5 | Все сервисы | [ADR-005](adr/ADR-005-transactional-outbox.md) |
| Запись аудита | `audit-recorder` | Помощник доменного слоя | Пишет событие `audit.recorded` в Outbox в той же транзакции, что и действие сотрудника (INV-43). Для персональных полей записывает хеши, посчитанные владельцем данных | Сервисы с действиями сотрудников: `catalog-service`, `payment-service`, `platform-service`, `order-service` | [ADR-014](adr/ADR-014-audit-log-no-pii.md) |

Тип `SecretValue` (значение, которое не печатается и не сериализуется) тоже входит в каркас. Это тип, а не компонент, поэтому на диаграммах его нет, но им пользуются `key-crypto`, `key-value-reader`, `key-client` и `letter-builder`.

## 3. Правила, общие для всех сервисов

| № | Правило | Как проверяется |
| --- | --- | --- |
| 1 | Транзакция базы открывается только в методах доменного и сервисного слоя (`@Transactional`), не в контроллерах, клиентах и оркестраторе | ArchUnit-тест |
| 2 | Сетевой вызов не делается внутри транзакции. Схема для событий и очередей: проверить и занять (короткая транзакция), вызвать соседа или провайдера вне транзакции, записать итог новой транзакцией с повторной проверкой статуса | Обзор кода, тесты на сбой между шагами |
| 3 | Очередь в базе (выдачи, попытки возврата, контроль 30 минут): занять пачку запросом `FOR UPDATE SKIP LOCKED` с арендой (`next_attempt_at` сдвигается вперёд), выполнить вне транзакции, зафиксировать итог. Упавший процесс не теряет задание, оно вернётся по истечении аренды | Тесты с остановкой процесса |
| 4 | Изменение данных, запись события в Outbox и запись в `processed_event` (если изменение вызвано событием) выполняются одной транзакцией | Интеграционные тесты, сверка INV-43 |
| 5 | Контроллеры вызывают только сервисный и доменный слой, репозитории вызывает только слой выше, доменный слой не делает сетевых вызовов. Единственное исключение: постановка таймера резерва в Redis после фиксации транзакции (результат не влияет на решение) | ArchUnit-тест |
| 6 | Клиент соседнего сервиса или провайдера (`*-client`) вызывается только оркестратором или сервисным слоем, не контроллером и не репозиторием | ArchUnit-тест |
| 7 | Компонент пишет только в таблицы своей базы и своего модуля, чужие таблицы не читает | Права ролей базы, интеграционный тест |
| 8 | Значения ключей и тела писем с ключами существуют только как `SecretValue` в памяти и не пишутся в логи, метрики, трассировку, события и таблицы | Тест «канарейка» ([ADR-009](adr/ADR-009-key-encryption-hmac.md)) |

## 4. Соглашения об именах компонентов

Компонент называется латиницей через дефис, а суффикс показывает роль. Те же названия, переведённые в `PascalCase`, становятся классами (например, `saga-orchestrator` → `SagaOrchestrator`) в пакете `<сервис>.<модуль>.<слой>`.

| Суффикс | Роль | Пример |
| --- | --- | --- |
| `-controller` | Контроллер REST | `order-controller` |
| `-service` | Сервис с логикой, у которого нет единого автомата | `reservation-service` |
| `-domain` | Доменный слой с автоматом SM и инвариантами | `order-domain`, `delivery-domain`, `payment-domain` |
| `-orchestrator` | Оркестрация шагов без правил | `saga-orchestrator` |
| `-client` | Исходящий вызов соседа или провайдера (адаптер) | `catalog-client`, `gateway-client` |
| `-repository` | Репозиторий, единственный путь к таблицам модуля | `order-repository` |
| `event-consumer`, `-listener`, `-handler` | Приём событий. `-handler` это метод общего потребителя | `event-consumer`, `seller-role-handler` |
| `-job`, `-timer`, `-worker`, `-poller`, `-reconciler`, `-dispatcher`, `-watchdog` | Планировщик по роли | `delivery-watch-job`, `reservation-timer` |
| `-relay` | Публикатор Outbox | `outbox-relay` |
| `-filter`, `-verifier` | Проверка входящего запроса | `caller-filter`, `signature-verifier` |

Таблица заказов называется `orders`, потому что `order` зарезервировано в SQL. Это единственное исключение из правила «таблицы называются в единственном числе»: полные правила имён таблиц и колонок задаёт `conventions.md` (шаг 10).

## 5. Связи контейнеров и компоненты

Каждая связь из [c4-containers.md](c4-containers.md), которая касается прикладного сервиса, отображается на компонент-источник и компонент-приёмник. Связи Keycloak, шлюза и веб-интерфейса, не касающиеся сервисов, в таблицу не вошли.

### 5.1. Запросы и вызовы

| Связь | Источник (компонент) | Приёмник (компонент) |
| --- | --- | --- |
| Раздел 2, связь 5: `api-gateway` → `catalog-service` | Шлюз | `storefront-controller`, `seller-controller`, `product-controller` |
| Раздел 2, связь 6: `api-gateway` → `inventory-service` | Шлюз | `caller-filter` → `inventory-controller` → `key-pool-service` |
| Раздел 2, связь 7: `api-gateway` → `order-service` | Шлюз | `order-controller` |
| Раздел 2, связь 8: `api-gateway` → `payment-service` | Шлюз | `webhook-controller`, `payment-controller` |
| Раздел 2, связь 9: `api-gateway` → `delivery-service` | Шлюз | `webhook-controller` |
| Раздел 2, связь 10: `api-gateway` → `platform-service` | Шлюз | `identity-controller`, `ticket-controller`, `parameter-controller`, `audit-controller`, `provider-webhook-controller` |
| Раздел 3, связь 1: «карточка товара» | `catalog-client` | `product-card-controller` |
| Раздел 3, связь 2: «зарезервировать», «подтвердить резерв» | `inventory-client` | `caller-filter` → `inventory-controller` → `reservation-service` |
| Раздел 3, связь 3: «открыть платёжную сессию» | `payment-client` | `payment-controller` → `payment-session-service` |
| Раздел 3, связь 4: «значения ключей по заказу» | `key-client` | `caller-filter` → `inventory-controller` → `key-value-reader` |
| Раздел 3, связь 5: «обновить адрес доставки» | `order-client` | `order-controller` → `order-domain` |
| Раздел 3, связь 6: роль, e-mail, деактивация | `keycloak-admin-client` | `keycloak` |
| Раздел 3, связь 7: «отправить код», «проверить код» | `keycloak` | `otp-controller` → `otp-service` |
| Раздел 4, связь 1: платёж и возврат у шлюза | `gateway-client` | `payment-gateway` |
| Раздел 4, связь 2: webhook платёжного шлюза | Шлюз | `webhook-controller` → `signature-verifier` → `payment-domain` |
| Раздел 4, связь 3: письмо с ключом | `email-client` | `email-provider` |
| Раздел 4, связь 4: статус письма | Шлюз | `webhook-controller` → `delivery-domain` |
| Раздел 4, связь 5: остальные письма | `email-sender` | `email-provider` |
| Раздел 4, связь 6: статус остальных писем | Шлюз | `provider-webhook-controller` |
| Раздел 4, связь 7: коды | `sms-client` | `sms-provider` |
| Раздел 4, связь 8: статус SMS | Шлюз | `provider-webhook-controller` |

Связи 9 и 10 раздела 4 (Keycloak с VK ID и SMTP) относятся к контейнеру Keycloak, прикладных компонентов у них нет.

### 5.2. Хранилища и секреты

| Связь | Компонент |
| --- | --- |
| Раздел 6: доступ к базам | `order-repository`, `inventory-repository`, `delivery-repository`, `payment-repository`, `seller-repository`, `catalog-repository`, пять репозиториев `platform-service`. Дополнительно `history-reader` и публикаторы Outbox читают свою базу |
| Раздел 7, связь 2: кэш каталога | `catalog-cache` |
| Раздел 7, связь 3: таймеры резервов | `reservation-service` (постановка), `reservation-timer` (чтение) |
| Раздел 7, связь 4: коды и счётчики | `otp-service` |
| Раздел 7, связь 5: документы продавцов | `document-store` |
| Раздел 7.1, связь 1: мастер-ключ и секрет HMAC | `key-crypto` |
| Раздел 7.1, связь 2: ключи шлюза и подпись | `gateway-client`, `signature-verifier` |
| Раздел 7.1, связь 3: ключ e-mail-провайдера | `email-client` (`delivery-service`) |
| Раздел 7.1, связь 4: ключи провайдеров и секрет аудита | `sms-client`, `email-sender`, `otp-service`, `audit-service` |

### 5.3. События

Каждое событие из [c4-containers.md](c4-containers.md), раздел 8, сопоставлено с компонентом, который его записывает в Outbox, и компонентами, которые его читают. Имена событий здесь технические и совпадают с `ADR-004` и `ADR-011`, а полные схемы фиксирует AsyncAPI (шаг 10).

| Событие (раздел 8) | Техническое имя | Издатель (компонент) | Подписчики (компоненты) |
| --- | --- | --- | --- |
| Профиль одобрен | `seller.approved` | `seller-profile-service` | `seller-role-handler`, `notification-handler` |
| Профиль отклонён, возвращён на доработку | `seller.rejected`, `seller.returned` | `seller-profile-service` | `notification-handler` |
| Товар создан, изменён | `product.created`, `product.updated` | `product-service` | `event-consumer` → `product-registry` (`inventory-service`) |
| Товар опубликован, отклонён, заблокирован | `product.published`, `product.rejected`, `product.blocked` | `product-service` | `notification-handler` |
| Остаток изменился | `stock.changed` | `reservation-service`, `key-pool-service` | `event-consumer` → `stock-view-service` (`catalog-service`) |
| Резерв истёк, резерв снят | `reservation.expired`, `reservation.released` | `reservation-service` | `event-consumer` → `saga-orchestrator` (`order-service`, только `reservation.expired`) |
| Заказ оплачен | `order.paid` | `order-domain` | `event-consumer` → `delivery-domain` (`delivery-service`), `notification-handler` |
| Заказ создан, выдан, возвращён | `order.created`, `order.issued`, `order.refunded` | `order-domain` | `notification-handler` |
| Заказ отменён | `order.cancelled` | `order-domain` | `event-consumer` → `reservation-service` (`inventory-service`), `notification-handler` |
| Адрес доставки обновлён | `order.address-updated` | `order-domain` | `event-consumer` → `delivery-domain` (`delivery-service`) |
| Вернуть деньги | `order.refund-requested` | `order-domain` | `event-consumer` → `refund-service` (`payment-service`) |
| Платёж подтверждён, отклонён, возвращён | `payment.confirmed`, `payment.rejected`, `payment.refunded` | `payment-domain` | `event-consumer` → `saga-orchestrator` (`order-service`) |
| Возврат ждёт администратора | `payment.refund-escalated` | `payment-domain` | `notification-handler` |
| Письмо принято | `delivery.accepted` | `delivery-domain` | `event-consumer` → `saga-orchestrator` (`order-service`) |
| Выдача доставлена | `delivery.delivered` | `delivery-domain` | `system-ticket-handler` |
| Выдача не удалась, 30 минут без доставки | `delivery.failed`, `delivery.overdue` | `delivery-domain` | `system-ticket-handler`, `notification-handler` |
| Пользователь зарегистрирован, роль назначена, e-mail изменён, телефон подтверждён, пользователь деактивирован | `user.registered`, `user.role-assigned`, `user.email-changed`, `user.phone-confirmed`, `user.deactivated` | `user-service` | `notification-handler`, аудит через `audit.recorded` |
| Пользователь анонимизирован | `user.anonymized` | `user-service` | `event-consumer` → `order-domain` (`order-service`), `event-consumer` → `delivery-domain` (`delivery-service`) |
| Обращение создано, решено | `ticket.created`, `ticket.resolved` | `ticket-service` | Метрики, аудит через `audit.recorded` |
| Письмо не доставлено | `notification.failed` | `notification-service` | Метрика и оповещение |
| Параметры изменены | `config.changed` | `parameter-service` | `config-listener` во всех сервисах R1 |
| События аудита | `audit.recorded` | `audit-recorder` в сервисах | `audit-consumer` → `audit-service` |

Строка «Пользователь анонимизирован» новая для Ф2: событие вытекает из [ADR-014](adr/ADR-014-audit-log-no-pii.md) и INV-44 (снимок e-mail в заказе и снимок адреса в выдаче очищаются вместе с пользователем), но его не было в разделе 8 [c4-containers.md](c4-containers.md) и в списках «принимает события» [decomposition.md](decomposition.md). Обе таблицы дополнены этим шагом.

## 6. Инварианты и компоненты

Таблица показывает, за каким сервисом и компонентом закреплён каждый инвариант [domain-model.md](../04-domain/domain-model.md). Подробно «как обеспечивает» написано в разделах «Компонент → инварианты и требования» файлов сервисов.

| Инвариант | Сервис | Компоненты |
| --- | --- | --- |
| INV-01 | `inventory-service` | `reservation-service`, `inventory-repository` |
| INV-02 | `inventory-service` | `reservation-service`, `inventory-repository` |
| INV-03 | `inventory-service` | `key-pool-service`, `inventory-repository` |
| INV-04 | `inventory-service` | `reservation-service`, `inventory-repository` |
| INV-05 | `inventory-service` | `reservation-service` |
| INV-06 | `inventory-service` | `reservation-service`, `reservation-timer`, `reservation-reconciler`, `inventory-repository` |
| INV-07 | `platform-service`, `order-service` | `parameter-service` (проверка при сохранении), `order-domain` (срок сессии при создании заказа) |
| INV-08 (R2) | `inventory-service` | `api-stock-service` |
| INV-09 | `inventory-service`, `catalog-service` | `reservation-service`, `product-registry`, `storefront-controller`, `stock-view-service` |
| INV-10 | `order-service` | `order-controller`, `order-domain` |
| INV-11 | `order-service`, `payment-service` | `order-domain`, `payment-session-service`, `payment-domain`, `payment-repository` |
| INV-12 | `order-service` | `order-domain` |
| INV-13 | `order-service` | `order-domain` |
| INV-14 | `payment-service` | `payment-domain`, `webhook-controller`, `signature-verifier`, `reconciler` |
| INV-15 | `payment-service` | `refund-service`, `payment-domain`, `payment-repository` |
| INV-16 | `order-service` | `order-domain`, `saga-orchestrator` |
| INV-17 | `order-service`, `delivery-service` | `order-domain`, `order-repository`, `delivery-domain` |
| INV-18 | `delivery-service` | `delivery-domain`, `delivery-repository`, `event-consumer` |
| INV-19 | `delivery-service`, `inventory-service` | `letter-builder`, `key-value-reader` |
| INV-20 | `inventory-service`, `delivery-service` | `key-crypto`, `key-pool-service`, `key-value-reader`, `inventory-controller`, `caller-filter`, `key-client`, `letter-builder`, `delivery-dispatcher`, `delivery-repository` |
| INV-21 | `platform-service` | `ticket-service`, `system-ticket-handler` |
| INV-22 | `platform-service` | `ticket-controller`, `ticket-service` |
| INV-23 | `platform-service`, `delivery-service`, `inventory-service` | `ticket-service`, `delivery-domain`, `key-value-reader` |
| INV-24 – INV-25 (R2) | `finance-service` | Модуль `disputes`, компоненты описываются перед R2 |
| INV-26 (R2) | `platform-service` | `parameter-service` |
| INV-27 – INV-33 (R2) | `finance-service` | Модуль `balance`, компоненты описываются перед R2 |
| INV-34 | `platform-service` | `user-service`, `identity-controller` |
| INV-35 | `order-service`, `platform-service` | `order-domain`, `user-service` (признак подтверждения для токена) |
| INV-36 | `platform-service`, `catalog-service` | `seller-role-handler`, `user-service`, `seller-profile-service` |
| INV-37 | `platform-service` | `user-service`, `keycloak-admin-client` |
| INV-38 | `platform-service` | `user-service` |
| INV-39 | `catalog-service` | `seller-controller`, `seller-profile-service` |
| INV-40 | `catalog-service` | `product-service`, `storefront-controller`, `search-service` |
| INV-41 (R2) | `catalog-service` | `api-key-service` |
| INV-42 | `platform-service` | `audit-service`, `audit-consumer`, `audit-controller`, `admin-repository` |
| INV-43 | Все сервисы с действиями сотрудников | `audit-recorder`, `order-domain`, `payment-domain`, `product-service`, `seller-profile-service`, `ticket-service`, `parameter-service`, `user-service`, `outbox-relay` |
| INV-44 | `platform-service`, `order-service`, `delivery-service` | `user-service`, `notification-service`, `order-domain`, `delivery-domain` |

## 7. Проверка

Проверка выполняется скриптом `check_components.py`. Скрипты проверки документов переносятся в репозиторий в `tools/docs-checks` на шаге 14.

| Проверка | Результат |
| --- | --- |
| Алиасы компонентов на диаграммах совпадают с таблицами компонентов, а граница диаграммы названа алиасом контейнера | Выполнено |
| Соседи на диаграммах это контейнеры и внешние системы из [c4-containers.md](c4-containers.md) и [c4-context.md](c4-context.md) | Выполнено |
| На диаграмме не больше 15 элементов | Выполнено, максимум 15 (запросы `order-service`) |
| Каждый из 44 инвариантов упомянут в таблице хотя бы одного компонента (R2 и модуль финансов оговорены отдельно) | Выполнено |
| Каждая связь и событие разделов 2–8 [c4-containers.md](c4-containers.md) имеет компонент (раздел 5 этого файла) | Выполнено. Найден один пропуск: событие `user.anonymized` отсутствовало в разделе 8, дополнено |
| Компоненты не обращаются к таблицам других сервисов | Выполнено: у каждого сервиса один репозиторий на модуль и одна база |

## 8. Решения

| № | Вопрос | Решение | Причина |
| --- | --- | --- | --- |
| 1 | Для каких сервисов рисовать диаграммы | Для заказов, остатков, выдачи и платежей. Для каталога и служебного сервиса таблицы | Требование шага 6. Устройство каталога и служебного сервиса не содержит таймеров и блокировок, диаграмма повторила бы таблицу |
| 2 | Сколько диаграмм на сервис | Две, по вопросам «запросы» и «события и таймеры» | Одна диаграмма сервиса превышает 15 элементов и нечитаема |
| 3 | Общие компоненты | Вынесены в каркас `service-kit` и описаны один раз | Правило 5 декомпозиции: общий код только каркас. Каркас не знает про предметную область |
| 4 | Как оформить расхождение Ф1 | Дополнить c4-containers и decomposition и записать в отчёт Ф2 | Событие нужно сервисам заказов и выдачи по INV-44 |
| 5 | Названия компонентов | Латиница через дефис с суффиксом роли, они же названия классов | Одно название в документе, диаграмме и коде |
