# Контракты REST API (OpenAPI 3.1), релиз R1

| Поле | Содержание |
| --- | --- |
| Состояние | Шаг 11 Ф2. 68 операций в шести сервисах, 943 примера запросов, ответов и ошибок проверен по схемам |
| Формат | OpenAPI 3.1, YAML, по одному файлу на сервис плюс общий файл компонентов [components.yaml](components.yaml) |
| Принцип | Contract-first: контракт пишется до кода, сверяется с матрицей прав, историями и доменной моделью, дальше по нему строятся код и контрактные тесты (Ф3) |
| Правила | [conventions.md](../../05-architecture/conventions.md) (деньги, время, ошибки, страницы, заголовки), [roles-permissions.md](../../05-architecture/roles-permissions.md) (роли, области, условия) |
| Проверка | Линтер [`tools/docs-checks/check_openapi.py`](../../../tools/docs-checks/check_openapi.py), раздел 8 |

## 1. Как читать

Каждый файл описывает один сервис ([decomposition.md](../../05-architecture/decomposition.md)): только те пути, которыми владеет его контекст. Схемы, параметры, заголовки, схемы безопасности и все ответы об ошибках лежат в [components.yaml](components.yaml), в файлах сервисов остаются только операции и схемы, которые нужны одному сервису.

| Файл | Сервис | Что описывает | Операций |
| --- | --- | --- | --- |
| [catalog-service.yaml](catalog-service.yaml) | `catalog-service` | Заявка продавца и её модерация, товары продавца и модерация товаров, витрина, карточка товара для заказа (внутренний вызов) | 27 |
| [inventory-service.yaml](inventory-service.yaml) | `inventory-service` | Загрузка пулов ключей и остаток, резерв и подтверждение резерва, значения ключей для письма (внутренние вызовы) | 6 |
| [order-service.yaml](order-service.yaml) | `order-service` | Оформление заказа, история и карточка заказа, платёжная сессия заказа, обновление адреса доставки (внутренний вызов) | 5 |
| [payment-service.yaml](payment-service.yaml) | `payment-service` | Платёжная сессия (внутренний вызов), вебхук шлюза, очередь ручных возвратов | 4 |
| [delivery-service.yaml](delivery-service.yaml) | `delivery-service` | Вебхук e-mail-провайдера со статусом письма с ключами | 1 |
| [platform-service.yaml](platform-service.yaml) | `platform-service` | Подтверждение телефона, обращения и смена e-mail, пользователи и роли, параметры, журнал аудита, коды SMS и e-mail (внутренние вызовы), вебхуки провайдеров | 25 |

Общие правила:

- Деньги это объект `Money` (целые копейки и `RUB`), время это строка RFC 3339 в UTC с миллисекундами и `Z`, длительности в секундах с суффиксом `Seconds`, страницы по курсору `{items, page{limit, nextCursor}}`.
- Ошибки это `application/problem+json` (RFC 9457). В `components.yaml` каждая комбинация «статус и типы проблем» оформлена общим ответом с именем вида `E409StateConflict`. Типов проблем 38, все взяты из реестра [conventions.md, раздел 9.1](../../05-architecture/conventions.md), все используются.
- Каждый `POST`, который меняет состояние, требует `Idempotency-Key` и отвечает заголовком `Idempotency-Replayed`. Исключение: вебхуки внешних систем, они отсекают повтор по внешнему идентификатору события.
- `PUT` требует `If-Match` (428 без заголовка, 412 при несовпадении версии). Витрина отвечает `ETag` и понимает `If-None-Match` (304).
- Каждый ответ несёт `X-Correlation-Id`. Ответы с авторизацией несут `Cache-Control: no-store`.
- У каждой операции описаны 401, 403, 429, 500 и те из 400, 404, 409, 413, 415, 422, которые для неё возможны.

## 2. Маршруты шлюза и общий префикс продавца

Публичные пути (`/api/v1/...`) проходят через `api-gateway` по группам [ADR-021](../../05-architecture/adr/ADR-021-api-gateway.md). Внутренние пути (`/internal/v1/...`) в маршрутах шлюза отсутствуют, сервисы вызывают друг друга напрямую по mTLS ([ADR-022](../../05-architecture/adr/ADR-022-internal-traffic-encryption.md)), и у каждой такой операции перечислены вызывающие (`x-allowed-callers`).

Префикс `/api/v1/seller/products` делят два сервиса, поэтому шлюз маршрутизирует его по хвосту пути:

| Путь | Сервис |
| --- | --- |
| `/api/v1/seller/products/{productId}/key-batches`, `.../key-files`, `.../stock` | `inventory-service` |
| Остальные пути под `/api/v1/seller/products` | `catalog-service` |

Лимиты и размеры тел задаёт шлюз: тело до 2 МБ (вебхуки до 64 КБ), загрузка ключей 6 раз в час на пользователя, `POST /api/v1/orders` 10 раз в минуту на пользователя. В контрактах они отражены ответами 413 и 429 и описанием операций.

## 3. Расширения контракта

Матрица прав попадает в контракт полями с префиксом `x-`. Линтер сверяет каждое поле с [roles-permissions.md, раздел 10](../../05-architecture/roles-permissions.md).

| Поле | Значение |
| --- | --- |
| `x-matrix-id` | Строка матрицы прав (`OP-nn`), которую исполняет операция |
| `x-matrix-also` | Другие строки матрицы, исполняемые тем же запросом (правка анкеты в статусе «отклонён» возвращает профиль в «черновик» для повторной заявки: OP-10 и OP-14 в одном `PUT`) |
| `x-stories` | Истории, к которым относится операция |
| `x-allowed-roles` | Роли Keycloak, которым разрешена операция. У внутренних вызовов и вебхуков это `system` |
| `x-conditions` | Условия U1…U18 из матрицы. У чтения условия статуса 409 не дают |
| `x-sms-session` | `allowed`, если операция доступна из ограниченной сессии по SMS (`orders.read`, `support.write`), иначе `denied` |
| `x-allowed-callers` | Какие сервисы вправе вызвать внутреннюю операцию (проверка по сертификату) |
| `x-internal` | `true` у внутренних вызовов |
| `x-internal-nolocation` | `true` у внутреннего `POST` с ответом 201 без `Location`: созданный ресурс отдельно не читается |

Схемы безопасности: `bearerJwt` (HTTP bearer, JWT с областями), `webhookSignature` (заголовки `X-Webhook-Signature` и `X-Webhook-Timestamp`), `mutualTls`.

## 4. Владение путями

Путь принадлежит сервису, который владеет сущностью по [доменной модели](../../04-domain/domain-model.md). Линтер строит эту связь сам и отклоняет операцию в чужом сервисе.

| Пути | Сущность | Сервис |
| --- | --- | --- |
| `/api/v1/seller-applications`, `/api/v1/staff/seller-applications` | Профиль продавца | `catalog-service` |
| `/api/v1/seller/products`, `/api/v1/staff/products`, `/api/v1/products`, `/internal/v1/products` | Товар | `catalog-service` |
| `.../key-batches`, `.../key-files`, `.../stock`, `/internal/v1/keys` | Ключ | `inventory-service` |
| `/internal/v1/reservations` | Резерв | `inventory-service` |
| `/api/v1/orders`, `/internal/v1/orders` | Заказ | `order-service` |
| `/internal/v1/payment-sessions`, `/api/v1/staff/manual-refunds`, `/api/v1/webhooks/payment-gateway` | Платёж | `payment-service` |
| `/api/v1/webhooks/email-provider-keys` | Выдача | `delivery-service` |
| `/api/v1/phone-confirmations`, `/api/v1/staff/users` | Пользователь | `platform-service` |
| `/api/v1/support-tickets`, `/api/v1/staff/support-tickets` | Обращение в поддержку | `platform-service` |
| `/api/v1/staff/parameters` | Параметры платформы | `platform-service` |
| `/api/v1/staff/audit-records` | Журнал аудита | `platform-service` |
| `/internal/v1/otp-codes`, `/internal/v1/otp-verifications` | Одноразовый код | `platform-service` |
| `/api/v1/webhooks/email-provider`, `/api/v1/webhooks/sms-provider` | Уведомление | `platform-service` |

## 5. Что намеренно не отдано через REST

Запись журнала аудита в REST отсутствует: журнал только добавляется событием, а роль приложения в базе имеет `INSERT` и `SELECT` (INV-42, ADR-014). Изменение и удаление записи (OP-87) не существует как операция, и это проверяется тем, что у `/api/v1/staff/audit-records` есть только `GET`.

Значения ключей наружу не отдаются ни одной публичной операцией: их получает только `delivery-service` внутренним вызовом, а покупатель видит ключ в письме (NFT-3.2, ADR-009).

## 6. Истории и строки матрицы без REST

Часть строк матрицы прав и часть историй не превращаются в операции REST: их исполняют Keycloak, обработчики событий Kafka, задания по расписанию или внешние системы. Таблица ниже обязательна: линтер требует, чтобы каждая история R1 была покрыта либо операцией, либо строкой этой таблицы, и чтобы в таблице не было пар, у которых операция есть.

<!-- nonrest:begin -->

| История | Строка матрицы | Как реализована без REST |
| --- | --- | --- |
| US-1.1 | OP-01 | Регистрацию по e-mail и паролю принимает Keycloak, учётные данные хранятся только в нём. REST платформы нет, токены выдаёт Keycloak по OAuth2 |
| US-1.2 | OP-02 | VK ID подключён к Keycloak как внешний провайдер идентификации, вход и привязку ведёт Keycloak. В проекте роль VK ID играет заглушка `external-stubs` |
| US-1.3 | OP-03 | Вход по паролю и выпуск токенов (JWT со сроком 5 минут и способом входа `amr`) выполняет Keycloak, сервисы только проверяют токен |
| US-1.4 | OP-04 | Восстановление пароля по e-mail выполняет Keycloak, платформа в нём не участвует |
| US-1.5 | OP-05 | Настройку и проверку TOTP выполняет Keycloak, в токен попадает `amr` со значением `otp`. Шлюз пускает на маршруты продавца и сотрудников только такие токены |
| US-2.4 | OP-13 | Письма «Заявка одобрена», «отклонена» и «возвращена на доработку» отправляет `notification-handler` по событиям `seller.approved`, `seller.rejected`, `seller.returned` из `catalog.events` |
| US-3.7 | OP-27 | Письма продавцу о решении по товару отправляет `notification-handler` по событиям `product.published`, `product.rejected`, `product.blocked` |
| US-5.4 | OP-53 | Неоплаченный заказ отменяет оркестратор саги по событию `reservation.expired` из `inventory.events` (переход T6 в SM-01), потерянные события догоняет сторож заказов раз в минуту |
| US-5.6 | OP-55 | Позднюю оплату разбирает оркестратор по событию `payment.confirmed` и внутренним подтверждением резерва `POST /internal/v1/reservations/{reservationId}/confirm`: переход T7 или возврат при нехватке ключей (ADR-013) |
| US-5.7 | OP-56 | Автовозврат начинает событие-команда `order.refund-requested`, `payment-service` создаёт возврат и повторяет его по расписанию, при исчерпании попыток публикует `payment.refund-escalated`, ручной возврат отмечает OP-57 |
| US-6.1 | OP-60 | Выдачу запускает событие `order.paid`: `delivery-service` создаёт выдачу, берёт значения ключей внутренним вызовом `GET /internal/v1/keys` и отдаёт письмо провайдеру. Подтверждение принимает вебхук этой же строки (US-6.3) |
| US-6.2 | OP-60 | Двойная выдача исключена состоянием ключа и привязкой к одному заказу (ADR-007): отдельной операции нет, защита работает внутри резерва, подтверждения и выдачи |
| US-6.4 | OP-61 | Повторы отправки ведёт диспетчер выдачи по полю «следующая попытка» (паузы 10 с, 30 с, 2 мин, 5 мин, 10 мин, шесть попыток), после исчерпания публикуется `delivery.failed` и `platform-service` создаёт обращение системы |
| US-6.5 | OP-62 | Контроль 30 минут ведёт задание `delivery-watch-job` раз в 30 секунд, для просроченных выдач публикуется `delivery.overdue`, оповещение администратору отправляет `notification-handler` |
| US-7.4 | OP-75 | Смену e-mail выполняет `platform-service` сам после второго успешного кода: вызов Keycloak Admin REST и событие `user.email-changed`. Запускает её последняя проверка OP-74 (`POST .../email-change/verifications`) |
| US-7.5 | OP-76 | Обращение закрывается по событию `delivery.delivered`: обработчик событий поддержки переводит открытое обращение в «решено» (SM-07, T4) |
| US-8.1 | OP-15 | Роль «Продавец» назначает `platform-service` по событию `seller.approved` через Keycloak Admin REST, вручную она не назначается (U8, INV-36). Остальные роли меняет OP-80 |
| US-8.6 | OP-85 | Сервис пишет действие в Outbox в одной транзакции с самим действием (`audit.recorded` в `audit.events`), `audit-consumer` вставляет запись в `audit_log` только добавлением (ADR-014). Читает журнал OP-86 |
| US-8.8 | OP-88 | Задание `moderation-deadline-job` в `catalog-service` раз в 5 минут помечает заявки и товары старше 3 суток и обновляет метрики, оповещение даёт правило мониторинга. События и письма нет, решение не принимается |
| US-8.9 | OP-89 | Метрики, оповещения и трассу заказа показывает Grafana поверх Prometheus, Loki и Tempo. Это инструмент вне API платформы, доступ задан в матрице прав |

<!-- nonrest:end -->

## 7. Каталог операций

Раздел создаётся скриптом из контрактов: `python3 tools/docs-checks/check_openapi.py --write-catalog`. Руками его править не нужно, линтер сообщает, если он устарел.

<!-- catalog:begin -->
### Операции по сервисам

#### `catalog-service`, операций 27

| Метод | Путь | `operationId` | Матрица | Доступ | Роли | Условия | Истории |
| --- | --- | --- | --- | --- | --- | --- | --- |
| POST | `/api/v1/seller-applications` | `createSellerApplication` | OP-10 | `seller.apply` | `buyer` | U15 | US-2.1 |
| GET | `/api/v1/seller-applications/current` | `getCurrentSellerApplication` | OP-10 | `seller.apply` | `buyer` | U15 | US-2.1 |
| PUT | `/api/v1/seller-applications/current` | `replaceSellerApplicationDraft` | OP-10 + OP-14 | `seller.apply` | `buyer` | U14, U15 | US-2.1, US-2.5 |
| POST | `/api/v1/seller-applications/current/documents` | `uploadSellerDocument` | OP-10 | `seller.apply` | `buyer` | U15 | US-2.1 |
| DELETE | `/api/v1/seller-applications/current/documents/{documentId}` | `deleteSellerDocument` | OP-10 | `seller.apply` | `buyer` | U15 | US-2.1 |
| GET | `/api/v1/seller-applications/current/documents/{documentId}/link` | `getSellerDocumentLink` | OP-10 | `seller.apply` | `buyer` | U15 | US-2.1 |
| POST | `/api/v1/seller-applications/current/submit` | `submitSellerApplication` | OP-10 + OP-14 | `seller.apply` | `buyer` | U14, U15 | US-2.1, US-2.5 |
| GET | `/api/v1/staff/seller-applications` | `listSellerApplicationQueue` | OP-11 | `staff.moderation` | `moderator` | - | US-2.2 |
| GET | `/api/v1/staff/seller-applications/{sellerId}` | `getSellerApplicationCard` | OP-11 | `staff.moderation` | `moderator` | - | US-2.2, US-2.3 |
| POST | `/api/v1/staff/seller-applications/{sellerId}/approve` | `approveSellerApplication` | OP-12 | `staff.moderation` | `moderator` | U6 | US-2.2 |
| POST | `/api/v1/staff/seller-applications/{sellerId}/reject` | `rejectSellerApplication` | OP-12 | `staff.moderation` | `moderator` | U6 | US-2.3 |
| POST | `/api/v1/staff/seller-applications/{sellerId}/return` | `returnSellerApplication` | OP-12 | `staff.moderation` | `moderator` | U6 | US-2.3 |
| GET | `/api/v1/seller/products` | `listSellerProducts` | OP-20 | `seller.catalog` | `seller` | U3 | US-3.1 |
| POST | `/api/v1/seller/products` | `createSellerProduct` | OP-20 | `seller.catalog` | `seller` | U3 | US-3.1, US-4.1 |
| GET | `/api/v1/seller/products/{productId}` | `getSellerProduct` | OP-25 | `seller.catalog` | `seller` | U3 | US-3.6 |
| PUT | `/api/v1/seller/products/{productId}` | `replaceSellerProduct` | OP-25 | `seller.catalog` | `seller` | U3 | US-3.6 |
| POST | `/api/v1/seller/products/{productId}/submit` | `submitSellerProduct` | OP-21 | `seller.catalog` | `seller` | U3, U4 | US-3.2 |
| POST | `/api/v1/seller/products/{productId}/archive` | `archiveSellerProduct` | OP-26 | `seller.catalog` | `seller` | U3, U4 | US-3.8 |
| POST | `/api/v1/seller/products/{productId}/restore` | `restoreSellerProduct` | OP-26 | `seller.catalog` | `seller` | U3, U4 | US-3.8 |
| GET | `/api/v1/staff/products` | `listProductModerationQueue` | OP-22 | `staff.moderation` | `moderator` | - | US-3.3 |
| GET | `/api/v1/staff/products/{productId}` | `getProductForModeration` | OP-22 | `staff.moderation` | `moderator` | - | US-3.3 |
| POST | `/api/v1/staff/products/{productId}/approve` | `approveProduct` | OP-23 | `staff.moderation` | `moderator` | U6 | US-3.3 |
| POST | `/api/v1/staff/products/{productId}/reject` | `rejectProduct` | OP-23 | `staff.moderation` | `moderator` | U6 | US-3.4 |
| POST | `/api/v1/staff/products/{productId}/block` | `blockProduct` | OP-24 | `staff.moderation` | `moderator` | U4 | US-3.5 |
| GET | `/api/v1/products` | `listStorefrontProducts` | OP-28 | публично | `guest`, `buyer`, `seller`, `moderator`, `support-operator`, `admin` | - | US-3.9, US-3.10, US-3.11 |
| GET | `/api/v1/products/{productId}` | `getStorefrontProduct` | OP-28 | публично | `guest`, `buyer`, `seller`, `moderator`, `support-operator`, `admin` | - | US-3.9 |
| GET | `/internal/v1/products/{productId}` | `getProductCard` | OP-50 | mTLS: `order-service` | `system` | - | US-5.1 |

#### `inventory-service`, операций 6

| Метод | Путь | `operationId` | Матрица | Доступ | Роли | Условия | Истории |
| --- | --- | --- | --- | --- | --- | --- | --- |
| POST | `/api/v1/seller/products/{productId}/key-batches` | `uploadKeyBatch` | OP-36 | `seller.catalog` | `seller` | U3, U4 | US-4.2 |
| POST | `/api/v1/seller/products/{productId}/key-files` | `uploadKeyFile` | OP-37 | `seller.catalog` | `seller` | U3, U4 | US-4.3 |
| GET | `/api/v1/seller/products/{productId}/stock` | `getProductStock` | OP-39 | `seller.catalog` | `seller` | U3 | US-4.5 |
| POST | `/internal/v1/reservations` | `reserveKeys` | OP-51 | mTLS: `order-service` | `system` | - | US-5.2 |
| POST | `/internal/v1/reservations/{reservationId}/confirm` | `confirmReservation` | OP-54 | mTLS: `order-service` | `system` | - | US-5.5 |
| GET | `/internal/v1/keys` | `getKeyValues` | OP-38 | mTLS: `delivery-service` | `system` | U17 | US-4.4 |

#### `order-service`, операций 5

| Метод | Путь | `operationId` | Матрица | Доступ | Роли | Условия | Истории |
| --- | --- | --- | --- | --- | --- | --- | --- |
| GET | `/api/v1/orders` | `listOrders` | OP-58 | `orders.read` | `buyer` | U1 | US-5.9 |
| POST | `/api/v1/orders` | `createOrder` | OP-50 | `orders.create` | `buyer` | U2, U16 | US-5.1, US-4.6 |
| GET | `/api/v1/orders/{orderId}/payment-session` | `getOrderPaymentSession` | OP-52 | `orders.create` | `buyer` | U1, U4 | US-5.3 |
| GET | `/api/v1/orders/{orderId}` | `getOrder` | OP-58 | `orders.read` | `buyer` | U1 | US-5.9, US-6.6 |
| POST | `/internal/v1/orders/{orderId}/update-delivery-address` | `updateDeliveryAddress` | OP-72 | mTLS: `platform-service` | `system` | - | US-7.3 |

#### `payment-service`, операций 4

| Метод | Путь | `operationId` | Матрица | Доступ | Роли | Условия | Истории |
| --- | --- | --- | --- | --- | --- | --- | --- |
| POST | `/internal/v1/payment-sessions` | `openPaymentSession` | OP-51 | mTLS: `order-service` | `system` | - | US-5.2 |
| POST | `/api/v1/webhooks/payment-gateway` | `receivePaymentGatewayNotification` | OP-54 | подпись вебхука | `system` | - | US-5.5 |
| GET | `/api/v1/staff/manual-refunds` | `listManualRefunds` | OP-57 | `staff.admin` | `admin` | U4 | US-5.8 |
| POST | `/api/v1/staff/manual-refunds/{paymentId}/complete` | `completeManualRefund` | OP-57 | `staff.admin` | `admin` | U4 | US-5.8 |

#### `delivery-service`, операций 1

| Метод | Путь | `operationId` | Матрица | Доступ | Роли | Условия | Истории |
| --- | --- | --- | --- | --- | --- | --- | --- |
| POST | `/api/v1/webhooks/email-provider-keys` | `receiveKeyEmailStatus` | OP-60 | подпись вебхука | `system` | - | US-6.3 |

#### `platform-service`, операций 25

| Метод | Путь | `operationId` | Матрица | Доступ | Роли | Условия | Истории |
| --- | --- | --- | --- | --- | --- | --- | --- |
| POST | `/api/v1/phone-confirmations` | `createPhoneConfirmation` | OP-06 | `account.manage` | `buyer` | - | US-1.6 |
| POST | `/api/v1/phone-confirmations/{confirmationId}/verify` | `verifyPhoneConfirmation` | OP-06 | `account.manage` | `buyer` | - | US-1.6 |
| GET | `/api/v1/support-tickets` | `listSupportTickets` | OP-70 | `support.write` | `buyer`, `system` | U1, U5, U18 | US-7.1 |
| POST | `/api/v1/support-tickets` | `createSupportTicket` | OP-70 | `support.write` | `buyer`, `system` | U1, U5, U18 | US-7.1 |
| GET | `/api/v1/support-tickets/{ticketId}` | `getSupportTicket` | OP-70 | `support.write` | `buyer`, `system` | U1, U5, U18 | US-7.1 |
| POST | `/api/v1/support-tickets/{ticketId}/email-change/codes` | `requestEmailChangeCode` | OP-74 | `support.write` | `buyer` | U1 | US-7.4 |
| POST | `/api/v1/support-tickets/{ticketId}/email-change/verifications` | `verifyEmailChangeCode` | OP-74 | `support.write` | `buyer` | U1 | US-7.4 |
| GET | `/api/v1/staff/support-tickets` | `listSupportTicketQueue` | OP-71 | `staff.support` | `support-operator` | U4 | US-7.2 |
| GET | `/api/v1/staff/support-tickets/{ticketId}` | `getStaffSupportTicket` | OP-71 | `staff.support` | `support-operator` | U4 | US-7.2 |
| POST | `/api/v1/staff/support-tickets/{ticketId}/take` | `takeSupportTicket` | OP-71 | `staff.support` | `support-operator` | U4 | US-7.2 |
| POST | `/api/v1/staff/support-tickets/{ticketId}/release` | `releaseSupportTicket` | OP-71 | `staff.support` | `support-operator` | U4 | US-7.2 |
| POST | `/api/v1/staff/support-tickets/{ticketId}/resend` | `resendKeyForTicket` | OP-72 | `staff.support` | `support-operator` | U4, U10 | US-7.3 |
| GET | `/api/v1/staff/support-tickets/{ticketId}/email-change` | `getEmailChangeState` | OP-73 | `staff.support` | `support-operator` | U4, U10 | US-7.4 |
| POST | `/api/v1/staff/support-tickets/{ticketId}/email-change` | `startEmailChange` | OP-73 | `staff.support` | `support-operator` | U4, U10 | US-7.4 |
| GET | `/api/v1/staff/users` | `listStaffUsers` | OP-80 | `staff.admin` | `admin` | U7, U8 | US-8.1 |
| POST | `/api/v1/staff/users/{userId}/change-role` | `changeUserRole` | OP-80 | `staff.admin` | `admin` | U7, U8 | US-8.1 |
| POST | `/api/v1/staff/users/{userId}/deactivate` | `deactivateUser` | OP-81 | `staff.admin` | `admin` | U7 | US-8.2 |
| POST | `/api/v1/staff/users/{userId}/anonymize` | `anonymizeUser` | OP-82 | `staff.admin` | `admin` | U11 | US-8.2 |
| GET | `/api/v1/staff/parameters` | `listPlatformParameters` | OP-83 | `staff.admin` | `admin` | U13 | US-8.3 |
| PUT | `/api/v1/staff/parameters/{key}` | `setPlatformParameter` | OP-83 | `staff.admin` | `admin` | U13 | US-8.3, US-8.4 |
| GET | `/api/v1/staff/audit-records` | `listAuditRecords` | OP-86 | `staff.audit` | `admin` | - | US-8.7 |
| POST | `/internal/v1/otp-codes` | `issueOtpCode` | OP-07 | mTLS: `keycloak` | `system` | - | US-1.7 |
| POST | `/internal/v1/otp-verifications` | `verifyOtpCode` | OP-07 | mTLS: `keycloak` | `system` | - | US-1.7 |
| POST | `/api/v1/webhooks/email-provider` | `receiveMailStatus` | OP-84 | подпись вебхука | `system` | - | US-8.5 |
| POST | `/api/v1/webhooks/sms-provider` | `receiveSmsStatus` | OP-06 | подпись вебхука | `system` | - | US-1.6 |

### Покрытие историй R1 операциями REST

| История | Операции |
| --- | --- |
| US-1.6 | `createPhoneConfirmation`, `verifyPhoneConfirmation`, `receiveSmsStatus` |
| US-1.7 | `issueOtpCode`, `verifyOtpCode` |
| US-2.1 | `createSellerApplication`, `getCurrentSellerApplication`, `replaceSellerApplicationDraft`, `uploadSellerDocument`, `deleteSellerDocument`, `getSellerDocumentLink`, `submitSellerApplication` |
| US-2.2 | `listSellerApplicationQueue`, `getSellerApplicationCard`, `approveSellerApplication` |
| US-2.3 | `getSellerApplicationCard`, `rejectSellerApplication`, `returnSellerApplication` |
| US-2.5 | `replaceSellerApplicationDraft`, `submitSellerApplication` |
| US-3.1 | `listSellerProducts`, `createSellerProduct` |
| US-3.2 | `submitSellerProduct` |
| US-3.3 | `listProductModerationQueue`, `getProductForModeration`, `approveProduct` |
| US-3.4 | `rejectProduct` |
| US-3.5 | `blockProduct` |
| US-3.6 | `getSellerProduct`, `replaceSellerProduct` |
| US-3.8 | `archiveSellerProduct`, `restoreSellerProduct` |
| US-3.9 | `listStorefrontProducts`, `getStorefrontProduct` |
| US-3.10 | `listStorefrontProducts` |
| US-3.11 | `listStorefrontProducts` |
| US-4.1 | `createSellerProduct` |
| US-4.2 | `uploadKeyBatch` |
| US-4.3 | `uploadKeyFile` |
| US-4.4 | `getKeyValues` |
| US-4.5 | `getProductStock` |
| US-4.6 | `createOrder` |
| US-5.1 | `getProductCard`, `createOrder` |
| US-5.2 | `reserveKeys`, `openPaymentSession` |
| US-5.3 | `getOrderPaymentSession` |
| US-5.5 | `confirmReservation`, `receivePaymentGatewayNotification` |
| US-5.8 | `listManualRefunds`, `completeManualRefund` |
| US-5.9 | `listOrders`, `getOrder` |
| US-6.3 | `receiveKeyEmailStatus` |
| US-6.6 | `getOrder` |
| US-7.1 | `listSupportTickets`, `createSupportTicket`, `getSupportTicket` |
| US-7.2 | `listSupportTicketQueue`, `getStaffSupportTicket`, `takeSupportTicket`, `releaseSupportTicket` |
| US-7.3 | `updateDeliveryAddress`, `resendKeyForTicket` |
| US-7.4 | `requestEmailChangeCode`, `verifyEmailChangeCode`, `getEmailChangeState`, `startEmailChange` |
| US-8.1 | `listStaffUsers`, `changeUserRole` |
| US-8.2 | `deactivateUser`, `anonymizeUser` |
| US-8.3 | `listPlatformParameters`, `setPlatformParameter` |
| US-8.4 | `setPlatformParameter` |
| US-8.5 | `receiveMailStatus` |
| US-8.7 | `listAuditRecords` |
<!-- catalog:end -->

## 8. Проверка контрактов

Линтер `tools/docs-checks/check_openapi.py` читает YAML, [conventions.md](../../05-architecture/conventions.md), [roles-permissions.md](../../05-architecture/roles-permissions.md), истории требований и доменную модель и проверяет:

1. Структуру: пути, методы, уникальные `operationId`, обязательные поля, один успешный ответ, имена путей и параметров.
2. Ссылки `$ref`, неиспользуемые схемы, ответы, параметры и заголовки.
3. Ошибки: только типы из реестра 9.1, статус и название совпадают с реестром, у операции есть все обязательные коды (401, 403, 429, 500, а также 404, 409, 412, 413, 415, 422, 428 по её свойствам).
4. Безопасность: области, роли, условия и признак сессии по SMS равны матрице, у внутренних вызовов совпадают вызывающие с разделом 8.1 матрицы.
5. Идемпотентность и версии: `Idempotency-Key` у `POST`, `If-Match` у `PUT`, заголовки ответов.
6. Примеры запросов, ответов и ошибок: проходят по JSON Schema 2020-12, включая форматы `uuid`, `date-time`, `email`, `uri`.
7. Соглашения: camelCase, идентификаторы ссылаются на `Id`, момент времени на `Timestamp`, деньги это `Money`, перечисления в нижнем регистре, словари статусов и причин совпадают с conventions.md, раздел 7.2.
8. Покрытие историй R1 в обе стороны, строки матрицы без операции и владение сущностями.
9. Этот README: таблица без REST, актуальность каталога, число операций в шапке.

Запуск из корня репозитория: `python3 tools/docs-checks/check_openapi.py`. Код выхода 0 означает «проблем: 0». Нужны Python 3 с PyYAML и jsonschema.

## 9. Решения и находки шага

**Решения при составлении.**

1. Контракт делится по сервисам, а не по ролям: [ADR-002](../../05-architecture/adr/ADR-002-microservices-consolidation.md) определил владельцев, а шлюз маршрутизирует по пути.
2. Действие над ресурсом это `POST` на подресурс-глагол (`/submit`, `/approve`, `/take`), прямая запись статуса запрещена (conventions.md, раздел 8.2).
3. Заявка продавца это ресурс `current`: у пользователя один профиль продавца (INV-39), идентификатор ему не нужен.
4. Чужой объект даёт 404, а не 403 (U1): ответ не раскрывает, что объект существует.
5. Примеры пишутся для каждого запроса, ответа и ошибки и проверяются схемой: контракт, который не проходит собственные примеры, не выпускается.

**Находки для отчёта Ф2.**

| Код | Находка | Что сделано или предложено |
| --- | --- | --- |
| F11-1 | Реестр проблем конвенций расходился с историями и ADR: `webhook-expired` был 400, адрес и номер раскрывали владельца (`email-already-used`, `phone-already-used`), не было кодов для переполнения пула, последнего администратора и роли, управляемой системой | Исправлено: `webhook-expired` стал 401 (ADR-015), введены нейтральные `email-not-allowed` и `phone-not-allowed`, добавлены `pool-limit-exceeded`, `last-admin-required`, `role-managed-by-system` |
| F11-2 | Два типа проблем реестра не возвращались ни одной операцией: `account-deactivated` (деактивацию обрабатывает Keycloak: вход невозможен, сессии завершены) и `amount-mismatch` (расхождение суммы в вебхуке отвечает 200, ADR-015) | Оба типа удалены из реестра, остаётся 38 типов, все используются |
| F11-3 | В заказе не было названия товара: история заказов и письма показывали бы текущее название, а оно меняется, пока продавец правит карточку | В заказ добавлен снимок названия, снимков в доменной модели теперь шесть (domain-model.md, 4.8 и решение 5, logical-er.md). Физическая модель заказа получит столбец на шаге 12 |
| F11-4 | В компонентных документах остались имена областей `seller.keys` и `staff.finance`, которых нет в реестре областей | Заменены на `seller.catalog` и `staff.admin` в c4-components-inventory-service.md и c4-components-payment-service.md |
| F11-5 | Конвенции не описывали `If-None-Match` и ответ 304 для витрины, исключение `Idempotency-Key` для вебхуков и 201 без `Location` у внутренней операции | Добавлено в conventions.md, разделы 8.3 и 8.4 |
| F11-6 | У фильтра цены витрины (US-3.11) не было определено, включаются ли границы и что при перевёрнутом диапазоне | Раздел 10.5 conventions.md: `priceFrom` и `priceTo` в копейках, обе границы включаются, нижняя больше верхней даёт 422 |
| F11-7 | Префикс `/api/v1/seller/products` делят два сервиса, шлюз должен маршрутизировать по хвосту пути | Правило записано в разделе 2, ссылка из ADR-021 остаётся на OpenAPI |
| F11-8 | `AuditRecord.objectId` не может быть UUID: объект записи бывает параметром платформы с ключом вида `reservation.ttl-seconds` | Поле оставлено строкой до 128 символов, это единственное исключение из правила идентификаторов |
| F11-9 | Поле «всего ключей» с именем `total` линтер принимает за денежную сумму, а в R1 аннулированных ключей нет, поэтому «всего» это сумма трёх статусов | Поле названо `poolSize` (свободные, в резерве и выданные), имя `total` зарезервировано под `Money` |
| F11-10 | Условия статуса (U4, U5, U10, U15) относятся к изменяющим операциям строки матрицы, а читающая операция той же строки наследует их, но 409 не даёт | Правило записано в roles-permissions.md, раздел 10, и в линтере |
| F11-11 | Строки матрицы без REST (в таблице 20 пар «история и строка») нужно объяснять отдельно, иначе покрытие историй нельзя проверить | Раздел 6 и проверка в линтере: пара «история и строка» без операции должна быть описана |
