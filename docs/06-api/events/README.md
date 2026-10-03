# Каталог событий

| Поле | Содержание |
| --- | --- |
| Контракт | [asyncapi.yaml](../asyncapi/asyncapi.yaml), AsyncAPI 3.0: темы, сообщения, схемы данных, издатели, потребители, ключи идемпотентности, обработка ошибок |
| Объём | 55 событий: R1 37, R2 18 (черновик) |
| Примеры | [examples](examples): по одному файлу на событие, полный конверт, имя файла равно типу события |
| Правила | [conventions.md](../../05-architecture/conventions.md), разделы 2 (событие, команда, запрос), 11 (конверт, темы, версии), 12 (потребитель, DLQ) |
| Проверка | Скрипт `check_asyncapi.py`: структура и ссылки, схемы, примеры по схемам, один издатель на событие, владелец агрегата, ключи идемпотентности, сверка с реестром событий и границами контекстов. Таблицы ниже между маркерами генерируются из контракта, ручная правка запрещена |

## 1. Как читать

Каталог отвечает на пять вопросов о каждом событии: кто публикует, в какую тему и с каким ключом, кто читает, что делает при чтении и чем защищён от повторной доставки. Полные схемы полей лежат в контракте, здесь только сводка.

- **Ключ записи** это поле данных, значение которого служит ключом сообщения Kafka и равно `subject` конверта. События с одним ключом читаются по порядку, между разными ключами порядка нет ([conventions.md](../../05-architecture/conventions.md), раздел 11.3).
- **Потребители** указаны как сервис и обработчик внутри него (имя совпадает с компонентом из `c4-components-*.md` и с `processed_event.consumer`). Пометка R2 означает, что потребитель появится в релизе R2.
- События без потребителей в R1 оставлены намеренно, причина указана в строке.
- События R2 это черновики: имена и владельцы согласованы с границами контекстов, поля уточняются перед релизом.

## 2. Каталог

<!-- catalog:begin -->
### Темы

| Тема | Издатель | Ключ записи | Партиций | Хранение | Событий | Тема недоставленного |
| --- | --- | --- | --- | --- | --- | --- |
| `catalog.events` | `catalog-service` | Идентификатор товара либо профиля продавца | 3 | 7 суток | 12 | `catalog.events.dlq`, предупреждение |
| `inventory.events` | `inventory-service` | Идентификатор товара для stock.changed, идентификатор заказа для событий резерва | 3 | 7 суток | 6 | `inventory.events.dlq`, инцидент высокой важности |
| `order.events` | `order-service` | Идентификатор заказа | 3 | 7 суток | 7 | `order.events.dlq`, инцидент высокой важности |
| `payment.events` | `payment-service` | Идентификатор заказа | 3 | 7 суток | 4 | `payment.events.dlq`, инцидент высокой важности |
| `delivery.events` | `delivery-service` | Идентификатор заказа | 3 | 7 суток | 5 | `delivery.events.dlq`, инцидент высокой важности |
| `identity.events` | `platform-service` | Идентификатор пользователя | 1 | 7 суток | 7 | `identity.events.dlq`, предупреждение |
| `support.events` | `platform-service` | Идентификатор обращения | 1 | 7 суток | 2 | нет |
| `notification.events` | `platform-service` | Идентификатор уведомления | 1 | 7 суток | 1 | нет |
| `audit.events` | все сервисы | Идентификатор объекта действия | 1 | 30 суток | 1 | `audit.events.dlq`, предупреждение |
| `platform.config` | `platform-service` | Ключ параметра | 1 | сжатие по ключу | 1 | нет |
| `finance.events` | `finance-service` | Идентификатор заказа, спора или заявки на вывод | 3 | 7 суток | 9 | `finance.events.dlq`, инцидент высокой важности |

### События R1

#### `catalog.events`

| Событие | Ключ записи | Компонент издателя | Что произошло | Потребители |
| --- | --- | --- | --- | --- |
| [`seller.approved`](examples/seller.approved.json) | `sellerId` | `seller-profile-service` | Профиль продавца одобрен. | `platform-service`: `seller-role-handler`, `notification-handler` |
| [`seller.rejected`](examples/seller.rejected.json) | `sellerId` | `seller-profile-service` | Профиль продавца отклонён. | `platform-service`: `notification-handler` |
| [`seller.returned`](examples/seller.returned.json) | `sellerId` | `seller-profile-service` | Профиль продавца возвращён на доработку. | `platform-service`: `notification-handler` |
| [`product.created`](examples/product.created.json) | `productId` | `product-service` | Товар создан. | `inventory-service`: `product-registry` |
| [`product.updated`](examples/product.updated.json) | `productId` | `product-service` | Товар изменён. | `inventory-service`: `product-registry` |
| [`product.published`](examples/product.published.json) | `productId` | `product-service` | Товар опубликован. | `platform-service`: `notification-handler` |
| [`product.rejected`](examples/product.rejected.json) | `productId` | `product-service` | Товар отклонён. | `platform-service`: `notification-handler` |
| [`product.blocked`](examples/product.blocked.json) | `productId` | `product-service` | Товар заблокирован. | `platform-service`: `notification-handler` |

#### `inventory.events`

| Событие | Ключ записи | Компонент издателя | Что произошло | Потребители |
| --- | --- | --- | --- | --- |
| [`stock.changed`](examples/stock.changed.json) | `productId` | `reservation-service`, `key-pool-service` | Остаток товара изменился. | `catalog-service`: `stock-view-service` |
| [`reservation.expired`](examples/reservation.expired.json) | `orderId` | `reservation-service` | Резерв ключей истёк. | `order-service`: `saga-orchestrator` |
| [`reservation.released`](examples/reservation.released.json) | `orderId` | `reservation-service` | Резерв ключей снят. | нет, факт для метрик и аудита. Витрина узнаёт об изменении остатка из `stock.changed` |

#### `order.events`

| Событие | Ключ записи | Компонент издателя | Что произошло | Потребители |
| --- | --- | --- | --- | --- |
| [`order.created`](examples/order.created.json) | `orderId` | `order-domain` | Заказ создан и ожидает оплаты. | `platform-service`: `notification-handler` |
| [`order.paid`](examples/order.paid.json) | `orderId` | `order-domain` | Заказ оплачен. Персональные данные. | `delivery-service`: `delivery-domain`; `platform-service`: `notification-handler` |
| [`order.cancelled`](examples/order.cancelled.json) | `orderId` | `order-domain` | Заказ отменён. | `inventory-service`: `reservation-service` |
| [`order.issued`](examples/order.issued.json) | `orderId` | `order-domain` | Заказ выдан. | `platform-service`: `support-event-handler`; `finance-service`: `balance-module` (R2) |
| [`order.refunded`](examples/order.refunded.json) | `orderId` | `order-domain` | Заказ возвращён. | `platform-service`: `notification-handler`; `finance-service`: `balance-module` (R2) |
| [`order.address-updated`](examples/order.address-updated.json) | `orderId` | `order-domain` | Адрес доставки заказа обновлён. Персональные данные. | `delivery-service`: `delivery-domain` |
| [`order.refund-requested`](examples/order.refund-requested.json) | `orderId` | `order-domain` | Запрошен возврат денег по заказу. | `payment-service`: `refund-service` |

#### `payment.events`

| Событие | Ключ записи | Компонент издателя | Что произошло | Потребители |
| --- | --- | --- | --- | --- |
| [`payment.confirmed`](examples/payment.confirmed.json) | `orderId` | `payment-domain` | Платёж подтверждён. | `order-service`: `saga-orchestrator` |
| [`payment.rejected`](examples/payment.rejected.json) | `orderId` | `payment-domain` | Платёж отклонён. | `order-service`: `saga-orchestrator` |
| [`payment.refunded`](examples/payment.refunded.json) | `orderId` | `payment-domain` | Платёж возвращён. | `order-service`: `saga-orchestrator` |
| [`payment.refund-escalated`](examples/payment.refund-escalated.json) | `orderId` | `payment-domain` | Возврат передан администратору. | `platform-service`: `notification-handler` |

#### `delivery.events`

| Событие | Ключ записи | Компонент издателя | Что произошло | Потребители |
| --- | --- | --- | --- | --- |
| [`delivery.accepted`](examples/delivery.accepted.json) | `orderId` | `delivery-domain` | Письмо с ключами принято провайдером. | `order-service`: `saga-orchestrator`; `platform-service`: `support-event-handler` |
| [`delivery.delivered`](examples/delivery.delivered.json) | `orderId` | `delivery-domain` | Письмо доставлено покупателю. | `platform-service`: `support-event-handler` |
| [`delivery.failed`](examples/delivery.failed.json) | `orderId` | `delivery-domain` | Выдача не удалась. | `platform-service`: `support-event-handler`, `notification-handler` |
| [`delivery.overdue`](examples/delivery.overdue.json) | `orderId` | `delivery-domain` | Прошло 30 минут без доставки. | `platform-service`: `support-event-handler`, `notification-handler` |

#### `identity.events`

| Событие | Ключ записи | Компонент издателя | Что произошло | Потребители |
| --- | --- | --- | --- | --- |
| [`user.registered`](examples/user.registered.json) | `userId` | `user-service` | Пользователь зарегистрирован. | нет, факт для аудита и метрик регистраций. Письмо подтверждения отправляет Keycloak через SMTP |
| [`user.role-assigned`](examples/user.role-assigned.json) | `userId` | `user-service` | Пользователю назначена роль. | нет, факт для аудита. Токен с новой ролью пользователь получает из Keycloak |
| [`user.email-changed`](examples/user.email-changed.json) | `userId` | `user-service` | E-mail пользователя изменён. | `platform-service`: `notification-handler` |
| [`user.phone-confirmed`](examples/user.phone-confirmed.json) | `userId` | `user-service` | Телефон пользователя подтверждён. | нет, факт для аудита. Признак попадает в токен при следующем обновлении |
| [`user.deactivated`](examples/user.deactivated.json) | `userId` | `user-service` | Пользователь деактивирован. | `platform-service`: `notification-handler` |
| [`user.anonymized`](examples/user.anonymized.json) | `userId` | `user-service` | Пользователь анонимизирован. | `order-service`: `order-domain`; `delivery-service`: `delivery-domain` |

#### `support.events`

| Событие | Ключ записи | Компонент издателя | Что произошло | Потребители |
| --- | --- | --- | --- | --- |
| [`ticket.created`](examples/ticket.created.json) | `ticketId` | `ticket-service` | Обращение создано. | нет, факт для метрик очереди и аудита |
| [`ticket.resolved`](examples/ticket.resolved.json) | `ticketId` | `ticket-service` | Обращение решено. | нет, факт для метрик очереди и аудита |

#### `notification.events`

| Событие | Ключ записи | Компонент издателя | Что произошло | Потребители |
| --- | --- | --- | --- | --- |
| [`notification.failed`](examples/notification.failed.json) | `notificationId` | `notification-service` | Уведомление не доставлено. | нет, метрика и оповещение строятся на счётчике, а не на подписке |

#### `audit.events`

| Событие | Ключ записи | Компонент издателя | Что произошло | Потребители |
| --- | --- | --- | --- | --- |
| [`audit.recorded`](examples/audit.recorded.json) | `objectId` | `audit-recorder` | Действие записано для журнала аудита. | `platform-service`: `audit-consumer` |

#### `platform.config`

| Событие | Ключ записи | Компонент издателя | Что произошло | Потребители |
| --- | --- | --- | --- | --- |
| [`config.changed`](examples/config.changed.json) | `key` | `parameter-service` | Параметр платформы изменён. | `catalog-service`: `config-listener`; `delivery-service`: `config-listener`; `inventory-service`: `config-listener`; `order-service`: `config-listener`; `payment-service`: `config-listener` |

### События R2 (черновик)

#### `catalog.events`

| Событие | Ключ записи | Компонент издателя | Что произошло | Потребители |
| --- | --- | --- | --- | --- |
| [`seller.blocked`](examples/seller.blocked.json) | `sellerId` | `seller-profile-service` | Продавец заблокирован. | `catalog-service`: `seller-block-handler`; `platform-service`: `notification-handler` |
| [`seller.unblocked`](examples/seller.unblocked.json) | `sellerId` | `seller-profile-service` | Блокировка продавца снята. | `catalog-service`: `seller-block-handler`; `platform-service`: `notification-handler` |
| [`api-key.issued`](examples/api-key.issued.json) | `sellerId` | `api-key-service` | API-ключ продавца выпущен. | нет, факт для аудита. Шлюз проверяет ключ синхронным вызовом с кэшем 30 секунд |
| [`api-key.revoked`](examples/api-key.revoked.json) | `sellerId` | `api-key-service` | API-ключ продавца отозван. | нет, факт для аудита. Шлюз узнаёт об отзыве по истечении кэша |

#### `inventory.events`

| Событие | Ключ записи | Компонент издателя | Что произошло | Потребители |
| --- | --- | --- | --- | --- |
| [`key.voided`](examples/key.voided.json) | `productId` | `key-replacement-handler` | Ключ аннулирован. | нет, факт для аудита и метрик |
| [`key.replaced`](examples/key.replaced.json) | `orderId` | `key-replacement-handler` | Ключ заменён. | `delivery-service`: `key-replacement-handler`; `finance-service`: `disputes-module` |
| [`key.replacement-failed`](examples/key.replacement-failed.json) | `orderId` | `key-replacement-handler` | Заменить ключ не удалось. | `finance-service`: `disputes-module` |

#### `delivery.events`

| Событие | Ключ записи | Компонент издателя | Что произошло | Потребители |
| --- | --- | --- | --- | --- |
| [`delivery.seller-timeout`](examples/delivery.seller-timeout.json) | `orderId` | `delivery-domain` | Продавец не ответил на запрос выдачи. | `order-service`: `saga-orchestrator` |

#### `identity.events`

| Событие | Ключ записи | Компонент издателя | Что произошло | Потребители |
| --- | --- | --- | --- | --- |
| [`user.phone-changed`](examples/user.phone-changed.json) | `userId` | `user-service` | Телефон пользователя изменён. | `platform-service`: `notification-handler` |

#### `finance.events`

| Событие | Ключ записи | Компонент издателя | Что произошло | Потребители |
| --- | --- | --- | --- | --- |
| [`dispute.opened`](examples/dispute.opened.json) | `orderId` | `disputes-module` | Спор открыт. | `finance-service`: `balance-module`; `platform-service`: `notification-handler` |
| [`dispute.resolved`](examples/dispute.resolved.json) | `orderId` | `disputes-module` | Спор решён. | `finance-service`: `balance-module`; `platform-service`: `notification-handler` |
| [`dispute.returned`](examples/dispute.returned.json) | `orderId` | `disputes-module` | Спор возвращён модератору. | нет, очередь модератора внутри сервиса, событие для метрик и аудита |
| [`dispute.refund-requested`](examples/dispute.refund-requested.json) | `orderId` | `disputes-module` | По спору запрошен возврат денег. | `payment-service`: `refund-service` |
| [`dispute.key-replacement-requested`](examples/dispute.key-replacement-requested.json) | `orderId` | `disputes-module` | По спору запрошена замена ключа. | `inventory-service`: `key-replacement-handler` |
| [`balance.funds-available`](examples/balance.funds-available.json) | `sellerId` | `balance-module` | Деньги стали доступны для вывода. | `platform-service`: `notification-handler` |
| [`withdrawal.created`](examples/withdrawal.created.json) | `withdrawalId` | `balance-module` | Заявка на вывод создана. | `platform-service`: `notification-handler` |
| [`withdrawal.paid`](examples/withdrawal.paid.json) | `withdrawalId` | `balance-module` | Заявка на вывод выплачена. | `platform-service`: `notification-handler` |
| [`withdrawal.rejected`](examples/withdrawal.rejected.json) | `withdrawalId` | `balance-module` | Заявка на вывод отклонена. | `platform-service`: `notification-handler` |

### Потребители и защита от дублей

Каждый обработчик пишет `processed_event(consumer, event_id)` в одной транзакции с изменением данных, повторы 1, 5 и 25 секунд, затем `<тема>.dlq`. В таблице второй уровень защиты: бизнес-проверка или ограничение базы, которые остановят дубль, даже если первый уровень обойдён. Слушатели параметров работают иначе и описаны в [conventions.md](../../05-architecture/conventions.md), раздел 12.4.

#### `catalog-service`

| Обработчик | Событие | Релиз | Что делает | Защита от дубля |
| --- | --- | --- | --- | --- |
| `config-listener` | `config.changed` | R1 | Обновить копию параметров в памяти | Естественная идемпотентность: последнее значение по `version` побеждает |
| `seller-block-handler` | `seller.blocked` | R2 | Заблокировать товары продавца (внутри сервиса), записать `product.blocked` | Статус товара: повторная блокировка заблокированного ничего не меняет |
| `seller-block-handler` | `seller.unblocked` | R2 | Вернуть товары в состояние до блокировки | Восстановление только для товаров с причиной блокировки «блокировка продавца» |
| `stock-view-service` | `stock.changed` | R1 | Обновить число для витрины и карточки (NFT-1.0) | Запись по `productId`, применяется, только если `version` больше сохранённой: событие после возврата из DLQ не откатывает остаток |

#### `inventory-service`

| Обработчик | Событие | Релиз | Что делает | Защита от дубля |
| --- | --- | --- | --- | --- |
| `config-listener` | `config.changed` | R1 | Обновить срок резерва и лимиты загрузки | Естественная идемпотентность: последнее значение по `version` побеждает |
| `key-replacement-handler` | `dispute.key-replacement-requested` | R2 | Аннулировать ключ, выбрать новый, записать `key.voided` и `key.replaced` | Переход ключа по SM-03 проверяет статус |
| `product-registry` | `product.created` | R1 | Создать копию товара: продавец и способ выдачи для проверки прав загрузки (INV-09) | Запись по `productId`, применяется, только если `version` больше сохранённой, поэтому поздний повтор старой версии ничего не портит |
| `product-registry` | `product.updated` | R1 | Обновить копию товара | Запись по `productId`, применяется, только если `version` больше сохранённой |
| `reservation-service` | `order.cancelled` | R1 | Снять активный резерв заказа (SM-08, причина `order_cancelled`), вернуть ключи в «свободен» | Частичный `unique (order_id) where status = 'active'` и условие обновления по статусу: повторная отмена не имеет эффекта (INV-06) |

#### `order-service`

| Обработчик | Событие | Релиз | Что делает | Защита от дубля |
| --- | --- | --- | --- | --- |
| `config-listener` | `config.changed` | R1 | Обновить срок платёжной сессии, ставку комиссии, пределы количества | Естественная идемпотентность: последнее значение по `version` побеждает |
| `order-domain` | `user.anonymized` | R1 | Заменить снимок e-mail во всех заказах пользователя служебным значением (INV-44) | Операция идемпотентна по природе: повторная очистка уже очищенных снимков ничего не меняет |
| `saga-orchestrator` | `reservation.expired` | R1 | T6: заказ «ожидает оплаты» становится «отменён» с причиной `reservation_expired`, публикуется `order.cancelled` | Статус заказа (SM-01): только из `awaiting_payment`, в остальных статусах событие игнорируется (резерв старой попытки), блокировка строки заказа |
| `saga-orchestrator` | `payment.confirmed` | R1 | Шаг 5 саги: подтвердить резерв, затем T4 или T7, либо отмена и автовозврат | Статус заказа (SM-01): для «оплачен» и «выдан» событие игнорируется (E5), блокировка строки заказа; «создан» повторяется позже |
| `saga-orchestrator` | `payment.rejected` | R1 | T5: заказ «отменён» с причиной `payment_declined`, публикуется `order.cancelled` | Статус заказа (SM-01): только из `awaiting_payment` |
| `saga-orchestrator` | `payment.refunded` | R1 | T8: заказ «отменён» становится «возвращён» (INV-16), публикуется `order.refunded` | Статус заказа (SM-01): только из `cancelled`, иначе игнорируется |
| `saga-orchestrator` | `delivery.accepted` | R1 | T9: заказ «оплачен» становится «выдан», публикуется `order.issued` | Статус заказа (SM-01): только для `deliveryType = primary` и статуса `paid`, один раз за жизнь заказа (INV-17) |
| `saga-orchestrator` | `delivery.seller-timeout` | R2 | T10: заказ «оплачен» становится «отменён» с причиной `seller_api_timeout`, публикуется `order.refund-requested` | Статус заказа: только из `paid` |

#### `payment-service`

| Обработчик | Событие | Релиз | Что делает | Защита от дубля |
| --- | --- | --- | --- | --- |
| `config-listener` | `config.changed` | R1 | Обновить расписание возвратов и интервалы сверки | Естественная идемпотентность: последнее значение по `version` побеждает |
| `refund-service` | `order.refund-requested` | R1 | Создать возврат и запросить его у шлюза с ключом `refund-<paymentId>` | Один возврат на платёж (`unique` по платежу, INV-15) и ключ идемпотентности шлюза |
| `refund-service` | `dispute.refund-requested` | R2 | Создать возврат и запросить его у шлюза | Один возврат на платёж и ключ идемпотентности шлюза (INV-15) |

#### `delivery-service`

| Обработчик | Событие | Релиз | Что делает | Защита от дубля |
| --- | --- | --- | --- | --- |
| `config-listener` | `config.changed` | R1 | Обновить расписание повторов, окно контроля, параметры опроса | Естественная идемпотентность: последнее значение по `version` побеждает |
| `delivery-domain` | `order.paid` | R1 | Создать первичную выдачу и запустить контроль 30 минут (SM-06/T1) | Частичный `unique (order_id) where type = 'primary'` (INV-18): второе событие не создаёт вторую первичную выдачу |
| `delivery-domain` | `order.address-updated` | R1 | Создать повторную выдачу на новый адрес (SM-06/T1) | Частичный `unique (order_id) where status = 'queued'`: не больше одной выдачи в очереди на заказ |
| `delivery-domain` | `user.anonymized` | R1 | Заменить снимок адреса во всех выдачах пользователя служебным значением (INV-44) | Идемпотентна по природе. Нужен `buyer_id` в таблице выдачи (находка F10-6) |
| `key-replacement-handler` | `key.replaced` | R2 | Создать повторную выдачу с новым ключом (SM-06/T1) | Частичный `unique (order_id) where status = 'queued'` |

#### `platform-service`

| Обработчик | Событие | Релиз | Что делает | Защита от дубля |
| --- | --- | --- | --- | --- |
| `audit-consumer` | `audit.recorded` | R1 | Вставить запись в `audit_log` (только добавление) | Уникальный индекс по `event_id` в `audit_log`: дубль не создаёт вторую запись |
| `notification-handler` | `seller.approved` | R1 | Письмо «Заявка одобрена» продавцу (FT-2.2) | Уникальный индекс `(event_id, template)` в таблице уведомлений: письмо по одному событию уходит один раз |
| `notification-handler` | `seller.rejected` | R1 | Письмо «Заявка отклонена» с причиной (FT-2.2) | Уникальный индекс `(event_id, template)` в таблице уведомлений: письмо по одному событию уходит один раз |
| `notification-handler` | `seller.returned` | R1 | Письмо «Заявка возвращена на доработку» с комментарием (FT-2.2) | Уникальный индекс `(event_id, template)` в таблице уведомлений: письмо по одному событию уходит один раз |
| `notification-handler` | `product.published` | R1 | Письмо «Товар опубликован» продавцу (FT-11.4) | Уникальный индекс `(event_id, template)` в таблице уведомлений: письмо по одному событию уходит один раз |
| `notification-handler` | `product.rejected` | R1 | Письмо «Товар отклонён» с причиной (FT-11.4) | Уникальный индекс `(event_id, template)` в таблице уведомлений: письмо по одному событию уходит один раз |
| `notification-handler` | `product.blocked` | R1 | Письмо «Товар заблокирован» продавцу (FT-11.4) | Уникальный индекс `(event_id, template)` в таблице уведомлений: письмо по одному событию уходит один раз |
| `notification-handler` | `order.created` | R1 | Письмо «Заказ создан» покупателю (FT-11.0) | Уникальный индекс `(event_id, template)` в таблице уведомлений: письмо по одному событию уходит один раз |
| `notification-handler` | `order.paid` | R1 | Письмо «Заказ оплачен» покупателю (FT-11.0) | Уникальный индекс `(event_id, template)` в таблице уведомлений: письмо по одному событию уходит один раз |
| `notification-handler` | `order.refunded` | R1 | Письмо «Деньги возвращены» покупателю (FT-11.0) | Уникальный индекс `(event_id, template)` в таблице уведомлений: письмо по одному событию уходит один раз |
| `notification-handler` | `payment.refund-escalated` | R1 | Оповещение администратору «Возврат ждёт ручного исполнения» (FT-6.4) | Уникальный индекс `(event_id, template)` в таблице уведомлений: письмо по одному событию уходит один раз |
| `notification-handler` | `delivery.failed` | R1 | Оповещение администратору «Выдача не удалась» (NFT-2.4) | Уникальный индекс `(event_id, template)` в таблице уведомлений: письмо по одному событию уходит один раз |
| `notification-handler` | `delivery.overdue` | R1 | Оповещение администратору «Нет подтверждения доставки за 30 минут» (NFT-2.4) | Уникальный индекс `(event_id, template)` в таблице уведомлений: письмо по одному событию уходит один раз |
| `notification-handler` | `user.email-changed` | R1 | Письма об изменении e-mail на новый и прежний адрес (FT-11.0) | Уникальный индекс `(event_id, template)` в таблице уведомлений: письмо по одному событию уходит один раз |
| `notification-handler` | `user.deactivated` | R1 | Письмо о деактивации учётной записи (FT-11.0) | Уникальный индекс `(event_id, template)` в таблице уведомлений: письмо по одному событию уходит один раз |
| `notification-handler` | `seller.blocked` | R2 | Письмо о блокировке продавцу | Уникальный индекс `(event_id, template)` в таблице уведомлений: письмо по одному событию уходит один раз |
| `notification-handler` | `seller.unblocked` | R2 | Письмо о снятии блокировки продавцу | Уникальный индекс `(event_id, template)` в таблице уведомлений: письмо по одному событию уходит один раз |
| `notification-handler` | `dispute.opened` | R2 | Письмо продавцу об открытом споре | Уникальный индекс `(event_id, template)` в таблице уведомлений: письмо по одному событию уходит один раз |
| `notification-handler` | `dispute.resolved` | R2 | Письма сторонам о решении по спору | Уникальный индекс `(event_id, template)` в таблице уведомлений: письмо по одному событию уходит один раз |
| `notification-handler` | `balance.funds-available` | R2 | Письмо продавцу «Деньги доступны» | Уникальный индекс `(event_id, template)` в таблице уведомлений: письмо по одному событию уходит один раз |
| `notification-handler` | `withdrawal.created` | R2 | Оповещение администратору о новой заявке на вывод | Уникальный индекс `(event_id, template)` в таблице уведомлений: письмо по одному событию уходит один раз |
| `notification-handler` | `withdrawal.paid` | R2 | Письмо продавцу «Выплата выполнена» | Уникальный индекс `(event_id, template)` в таблице уведомлений: письмо по одному событию уходит один раз |
| `notification-handler` | `withdrawal.rejected` | R2 | Письмо продавцу «Заявка отклонена» с причиной | Уникальный индекс `(event_id, template)` в таблице уведомлений: письмо по одному событию уходит один раз |
| `notification-handler` | `user.phone-changed` | R2 | Письмо об изменении номера на e-mail пользователя | Уникальный индекс `(event_id, template)` в таблице уведомлений: письмо по одному событию уходит один раз |
| `seller-role-handler` | `seller.approved` | R1 | Назначить пользователю роль `seller` в Keycloak, включить требование 2FA, записать `user.role-assigned` | Назначение той же роли повторно безопасно (операция Keycloak идемпотентна), флаг `keycloak_synced` в учётной записи показывает, дошло ли изменение |
| `support-event-handler` | `order.issued` | R1 | Обновить модель чтения очереди поддержки: покупатель и время первичной выдачи | Время первичной выдачи записывается один раз (`issued_at is null`), повтор ничего не меняет |
| `support-event-handler` | `delivery.accepted` | R1 | Обновить статус выдачи в модели чтения очереди | Запись по `deliveryId`, значение статуса применяется, если оно «дальше» по SM-06 |
| `support-event-handler` | `delivery.delivered` | R1 | Закрыть открытое обращение по заказу (SM-07/T4), обновить статус выдачи | Переход только из `created` и `in_progress`, для закрытого обращения игнорируется |
| `support-event-handler` | `delivery.failed` | R1 | Создать обращение системы в очереди поддержки (SM-07/T1) | Частичный `unique (order_id) where status in ('created', 'in_progress')` (INV-21) |
| `support-event-handler` | `delivery.overdue` | R1 | Создать обращение системы, если открытого нет (SM-07/T1) | Частичный `unique (order_id) where status in ('created', 'in_progress')` (INV-21) |

#### `finance-service`

| Обработчик | Событие | Релиз | Что делает | Защита от дубля |
| --- | --- | --- | --- | --- |
| `balance-module` | `order.issued` | R2 | Начислить продавцу сумму и комиссию с удержанием 7 дней (SM-11) | Уникальная пара «заказ, тип операции» в журнале баланса (INV-27) |
| `balance-module` | `order.refunded` | R2 | Сторнировать начисление и комиссию (SM-11) | Операция сторнирования уникальна для заказа (INV-28) |
| `balance-module` | `dispute.opened` | R2 | Заморозить начисление по заказу (SM-11) | Переход операции по SM-11 проверяет статус |
| `balance-module` | `dispute.resolved` | R2 | Разморозить деньги (отказ) или сторнировать (возврат) | Переход операции по SM-11 проверяет статус |
| `disputes-module` | `key.replaced` | R2 | Закрыть исполнение решения «замена ключа» | Признак исполнения спора выставляется один раз |
| `disputes-module` | `key.replacement-failed` | R2 | Вернуть спор модератору или перейти к возврату | Переход спора по SM-09 проверяет статус |
<!-- catalog:end -->

## 3. Основные цепочки событий

Таблицы показывают порядок событий в сценариях, которые держат деньги и ключи. Синхронные вызовы (резерв, подтверждение резерва, платёжная сессия) в таблицы не входят, они описаны в [SEQ-01](../../05-architecture/sequence-purchase.md).

### 3.1. Успешная покупка

| Шаг | Событие | Кто публикует | Кто реагирует | Результат |
| --- | --- | --- | --- | --- |
| 1 | `stock.changed` | `inventory-service` | `catalog-service` | Резерв уменьшил остаток на витрине |
| 2 | `order.created` | `order-service` | `platform-service` | Заказ ждёт оплаты (T2), покупатель получает письмо |
| 3 | `payment.confirmed` | `payment-service` | `order-service` | Оркестратор подтверждает резерв синхронно |
| 4 | `order.paid` | `order-service` | `delivery-service`, `platform-service` | Заказ оплачен (T4), создана первичная выдача и контроль 30 минут |
| 5 | `delivery.accepted` | `delivery-service` | `order-service`, `platform-service` | Провайдер принял письмо с ключами |
| 6 | `order.issued` | `order-service` | `platform-service` | Заказ выдан (T9), в очереди поддержки видно время выдачи |
| 7 | `delivery.delivered` | `delivery-service` | `platform-service` | Провайдер подтвердил доставку, открытое обращение закрывается |

### 3.2. Отказ в оплате и истечение резерва

| Шаг | Событие | Кто публикует | Кто реагирует | Результат |
| --- | --- | --- | --- | --- |
| 1 | `payment.rejected` или `reservation.expired` | `payment-service` или `inventory-service` | `order-service` | Заказ «отменён» (T5 или T6) |
| 2 | `order.cancelled` | `order-service` | `inventory-service` | Активный резерв снят, ключи свободны. При истечении по сроку резерв уже снят, повтор безвреден |
| 3 | `reservation.released` и `stock.changed` | `inventory-service` | `catalog-service` | Остаток на витрине восстановлен |

### 3.3. Поздняя оплата без ключей

| Шаг | Событие | Кто публикует | Кто реагирует | Результат |
| --- | --- | --- | --- | --- |
| 1 | `payment.confirmed` по отменённому заказу | `payment-service` | `order-service` | Оркестратор пробует зарезервировать ключи заново, ключей нет |
| 2 | `order.refund-requested` | `order-service` | `payment-service` | Возврат полной суммы запрошен у шлюза |
| 3 | `payment.refunded` | `payment-service` | `order-service` | Заказ «возвращён» (T8) |
| 4 | `order.refunded` | `order-service` | `platform-service` | Покупатель получает письмо о возврате |

### 3.4. Выдача не удалась

| Шаг | Событие | Кто публикует | Кто реагирует | Результат |
| --- | --- | --- | --- | --- |
| 1 | `delivery.failed` или `delivery.overdue` | `delivery-service` | `platform-service` | Создано обращение системы, администратору ушло оповещение |
| 2 | `order.address-updated` (если оператор сменил e-mail) | `order-service` | `delivery-service` | Создана повторная выдача на новый адрес |
| 3 | `delivery.accepted`, `delivery.delivered` | `delivery-service` | `platform-service` | Обращение закрыто |

## 4. Связанные документы

- [conventions.md](../../05-architecture/conventions.md): правила конверта, ключей, версий, повторов
- [c4-components.md](../../05-architecture/c4-components.md), раздел 5.3: события по компонентам
- [bounded-contexts.md](../../04-domain/bounded-contexts.md), раздел 4: что публикует каждый контекст
- [ADR-003](../../05-architecture/adr/ADR-003-kafka-events.md), [ADR-005](../../05-architecture/adr/ADR-005-transactional-outbox.md), [ADR-006](../../05-architecture/adr/ADR-006-idempotency.md), [ADR-011](../../05-architecture/adr/ADR-011-guaranteed-delivery.md)
