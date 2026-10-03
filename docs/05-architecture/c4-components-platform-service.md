# C4, уровень 3: компоненты служебного сервиса (таблица)

| Поле | Содержание |
| --- | --- |
| Документ | C4 Component: устройство `platform-service`, описание таблицей без диаграммы |
| Фаза | Ф2, шаг 6 |
| Контейнер | `platform-service`, Java, Spring Boot, база `platform_db` ([c4-containers.md](c4-containers.md)) |
| Нотация | [c4-notation.md](c4-notation.md) |
| Основание | [decomposition.md](decomposition.md), раздел 4.6, [ADR-010](adr/ADR-010-keycloak-sms-codes.md) (коды и Keycloak), [ADR-014](adr/ADR-014-audit-log-no-pii.md) (журнал аудита), [SM-07](../03-processes/SM-07-support-ticket.md), [BPMN-04](../03-processes/BPMN-04-support-request.md), [BPMN-07](../03-processes/BPMN-07-phone-change.md) |
| Общие компоненты | [c4-components.md](c4-components.md): каркас `service-kit`, правила транзакций, соглашения об именах |
| Предыдущий уровень | [c4-containers.md](c4-containers.md) |

Служебный сервис объединяет четыре модуля (`identity`, `support`, `notification`, `audit_admin`), у каждого своя схема базы и своя роль ([decomposition.md](decomposition.md), раздел 7). Он не лежит на критическом пути покупки и не держит таймеров с жёсткими сроками, кроме одноразовых кодов, которые живут в Redis. Диаграмма компонентов отдельно не рисуется (требование шага 6), устройство описано таблицами по модулям.

Ключей покупок сервис не видит ни в каком виде (INV-20, INV-23): письма с ключом отправляет только `delivery-service`, а `notification` отправляет остальные письма.

## 1. Компоненты по модулям

### 1.1. Модуль `identity`

| Компонент | Алиас | Spring-слой | Ответственность | Вызывает | События |
| --- | --- | --- | --- | --- | --- |
| Контроллер учётных записей | `identity-controller` | `@RestController` | Подтверждение телефона (запросить код, ввести код), профиль, администрирование ролей и деактивации, запуск анонимизации. Области `account.manage` (нет у сессий по SMS), `staff.admin` с 2FA | `user-service`, `otp-service` | Нет |
| Контроллер кодов для Keycloak | `otp-controller` | `@RestController`, внутренний | «Отправить код» и «проверить код» для входа по SMS и подтверждения телефона. Вызывающий только `keycloak` (список по сертификату, [ADR-022](adr/ADR-022-internal-traffic-encryption.md)) | `otp-service` | Нет |
| Одноразовые коды | `otp-service` | `@Service` | Код из 6 цифр, в Redis хранится HMAC с секретом `otp_pepper`, TTL 5 минут, 5 попыток. Ограничения: 3 запроса за 10 минут и 10 в сутки на учётную запись и телефон, 10 в час с IP, блокировка после 3 исчерпанных кодов за час, суточный потолок 1000 SMS, только номера +7. При недоступности Redis отказывает (fail-closed). Цели кодов: `phone_confirm`, `sms_login`, `email_change_phone`, `email_change_new_email`, `phone_change_email`, `phone_change_sms` | `sms-client`, `redis`, `secret-store` | Нет |
| Клиент SMS-провайдера | `sms-client` | `@Component`, адаптер | Передаёт код провайдеру, переводит ошибки провайдера в исходы. Ключ провайдера из секретов | `sms-provider`, `secret-store` | Нет |
| Пользователь | `user-service` | `@Service` домена, `@Transactional` | Расширение пользователя (телефон, признак подтверждения, признак анонимизации), уникальность e-mail и телефона (INV-34), роль «Продавец» только по событию одобрения (INV-36), включённая 2FA для сотрудников (INV-37), всегда хотя бы один активный администратор (INV-38), анонимизация (INV-44: удаление соли пользователя, очистка телефона и имён, `user.anonymized`). Хеши персональных полей для аудита считает владелец данных, то есть этот модуль | `identity-repository`, `keycloak-admin-client` | Пишет в Outbox: `user.registered`, `user.role-assigned`, `user.email-changed`, `user.phone-confirmed`, `user.deactivated`, `user.anonymized`, `audit.recorded` |
| Клиент Keycloak | `keycloak-admin-client` | `@Component`, адаптер | Admin REST: назначить роль, сменить e-mail, деактивировать, выставить признак подтверждения телефона (атрибут для токена, [ADR-010](adr/ADR-010-keycloak-sms-codes.md)) | `keycloak` | Нет |
| Обработчик одобрения продавца | `seller-role-handler` | Метод `event-consumer` | По `seller.approved` назначает роль «Продавец» (INV-36), по `seller.rejected` ничего не делает | `user-service` | Читает: `seller.approved` |
| Репозиторий идентификации | `identity-repository` | Spring Data JDBC | Запись в схему `identity` и в `outbox`, `processed_event`. Роль базы с правами только на свою схему | `platform-db` | Нет |

### 1.2. Модуль `support`

| Компонент | Алиас | Spring-слой | Ответственность | Вызывает | События |
| --- | --- | --- | --- | --- | --- |
| Контроллер обращений | `ticket-controller` | `@RestController` | Обращение покупателя по заказу (в течение 72 часов, INV-22), очередь оператора, действия: взять в работу, повторно отправить ключ, сменить адрес доставки, решить. Области `support.write`, `staff.operator` с 2FA | `ticket-service` | Нет |
| Обращение | `ticket-service` | `@Service` домена, `@Transactional` | Переходы SM-07, не больше одного открытого обращения на заказ (INV-21), окно 72 часа только для обращений покупателя (INV-22), оператор видит статусы, но не значение ключа (INV-23), действия оператора с событиями аудита одной транзакцией (INV-43). Смена e-mail: проверки через интерфейс модуля `identity` | `ticket-repository`, `order-client`, интерфейсы модулей `identity` и `notification` | Пишет в Outbox: `ticket.created`, `ticket.resolved`, `audit.recorded` |
| Обработчик событий поддержки | `support-event-handler` | Метод `event-consumer` | По `delivery.failed` и `delivery.overdue` создаёт обращение системы и не создаёт второе при открытом (INV-21), по `delivery.delivered` закрывает обращение системы (SM-07/T4). По `order.issued` записывает в модель чтения поддержки заказ, покупателя и время первичной выдачи (для окна 72 часа, INV-22), по `delivery.accepted`, `delivery.delivered`, `delivery.failed` и `delivery.overdue` обновляет в ней статус выдачи, который видит оператор | `ticket-service` | Читает: `order.issued`, `delivery.accepted`, `delivery.failed`, `delivery.overdue`, `delivery.delivered` |
| Клиент заказов | `order-client` | `@Component`, REST-клиент mTLS | «Обновить адрес доставки» у `order-service`, тайм-аут 2 с, ключ идемпотентности | `order-service` | Нет |
| Репозиторий обращений | `ticket-repository` | Spring Data JDBC | Запись в схему `support` и в `outbox`, `processed_event` | `platform-db` | Нет |

### 1.3. Модуль `notification`

| Компонент | Алиас | Spring-слой | Ответственность | Вызывает | События |
| --- | --- | --- | --- | --- | --- |
| Уведомления | `notification-service` | `@Service` домена | Очередь писем и повторы отправки, шаблоны. Адрес получателя берётся у модуля `identity` по идентификатору пользователя в момент отправки и в таблице уведомлений не хранится (INV-44). Недоставленное письмо после повторов даёт `notification.failed` | `notification-repository`, `email-sender`, интерфейс модуля `identity` | Пишет в Outbox: `notification.failed` |
| Обработчики событий уведомлений | `notification-handler` | Методы `event-consumer` | Преобразует события в письма: заказ создан, оплачен и возвращён (FT-11.0; письмом «Ключ выдан» служит письмо с ключом от `delivery-service`, отмена заказа письма не вызывает, SM-01), решения по заявке и товару продавцу (FT-2.2, FT-11.4), возврат ждёт администратора (FT-6.4), выдача не удалась или нет подтверждения за 30 минут (оповещение администратора, NFT-2.4), смена e-mail и телефона, пользователь деактивирован | `notification-service` | Читает: `order.created`, `order.paid`, `order.refunded`, `seller.approved`, `seller.rejected`, `seller.returned`, `product.published`, `product.rejected`, `product.blocked`, `payment.refund-escalated`, `delivery.failed`, `delivery.overdue`, `user.email-changed`, `user.deactivated` |
| Отправитель писем | `email-sender` | `@Component`, адаптер | Передаёт письма e-mail-провайдеру. Ключей покупок не отправляет. Ключ доступа из секретов | `email-provider`, `secret-store` | Нет |
| Контроллер статусов провайдеров | `provider-webhook-controller` | `@RestController` | Статусы писем и SMS от провайдеров через `api-gateway` без токена. Проверка подписи и метки времени, дедупликация | `notification-service`, `otp-service` | Нет |
| Репозиторий уведомлений | `notification-repository` | Spring Data JDBC | Запись в схему `notification` и в `outbox`, `processed_event` | `platform-db` | Нет |

### 1.4. Модуль `audit_admin`

| Компонент | Алиас | Spring-слой | Ответственность | Вызывает | События |
| --- | --- | --- | --- | --- | --- |
| Приёмник аудита | `audit-consumer` | Метод `event-consumer` | Читает `audit.events` всех сервисов, дубли убирает по `event_id` (уникальный индекс) | `audit-service` | Читает: `audit.recorded` |
| Журнал аудита | `audit-service` | `@Service` | Добавляет запись в `audit_log` (только INSERT и SELECT). Персональные поля уже пришли в виде HMAC-хешей от владельца данных, открытых значений в журнале нет. Журнал не удаляется и не правится, даже при анонимизации (INV-42). Секрет HMAC аудита читает из секретов | `audit-repository`, `secret-store` | Нет |
| Контроллер журнала | `audit-controller` | `@RestController` | Просмотр и фильтрация журнала для администратора. Область `staff.admin` с 2FA | `audit-service` | Нет |
| Контроллер параметров | `parameter-controller` | `@RestController` | Просмотр и изменение параметров платформы (FT-11.1). Область `staff.admin` с 2FA | `parameter-service` | Нет |
| Параметры | `parameter-service` | `@Service` домена, `@Transactional` | Допустимые границы значений, соотношение срока платёжной сессии и срока резерва (INV-07), в R2 соотношение окна спора и срока удержания (INV-26), запись значений до и после в журнал (INV-43). Публикует новое значение в сжатую тему `platform.config` | `admin-repository` | Пишет в Outbox: `config.changed`, `audit.recorded` |
| Репозиторий администрирования | `admin-repository` | Spring Data JDBC | Запись в схему `audit_admin` и в `outbox`, `processed_event`. Для `audit_log` отдельная роль только с INSERT и SELECT, триггеры запрещают UPDATE и DELETE, монтируются месячные секции | `platform-db` | Нет |

### 1.5. Общие компоненты сервиса

| Компонент | Алиас | Модули | Ответственность |
| --- | --- | --- | --- |
| Потребитель событий | `event-consumer` | все | Читает `catalog.events`, `order.events`, `payment.events`, `delivery.events`, `audit.events`, пропускает обработанные, повторы 1, 5, 25 с, DLQ, передаёт обработчикам модулей |
| Публикатор Outbox | `outbox-relay` | все | Публикует записи Outbox всех модулей в `identity.events`, `support.events`, `notification.events`, `audit.events`, `platform.config` ([ADR-005](adr/ADR-005-transactional-outbox.md)) |

Модули вызывают друг друга только через интерфейсы своего подпакета `api` ([decomposition.md](decomposition.md), правило 3):

| Интерфейс модуля | Кто вызывает | Что предоставляет |
| --- | --- | --- |
| `identity.api` | `support`, `notification` | Контактные данные по идентификатору пользователя (адрес e-mail), проверка смены e-mail, статус пользователя |
| `notification.api` | `support`, `identity` | Поставить письмо в очередь: идентификатор шаблона, пользователь, параметры без персональных данных |

## 2. Связи с соседями

| Связь контейнера ([c4-containers.md](c4-containers.md)) | Откуда | Куда (компонент) |
| --- | --- | --- |
| Раздел 2, связь 10: подтверждение телефона, обращения, роли, параметры, журнал | `api-gateway` | `identity-controller`, `ticket-controller`, `parameter-controller`, `audit-controller` |
| Раздел 3, связь 5: «обновить адрес доставки» | `order-client` | `order-service` |
| Раздел 3, связь 6: роль, e-mail, деактивация | `keycloak-admin-client` | `keycloak` |
| Раздел 3, связь 7: «отправить код», «проверить код» | `keycloak` | `otp-controller` |
| Раздел 4, связь 5: письма | `email-sender` | `email-provider` |
| Раздел 4, связь 6: статусы писем | `email-provider` через `api-gateway` | `provider-webhook-controller` |
| Раздел 4, связь 7: коды | `sms-client` | `sms-provider` |
| Раздел 4, связь 8: статус SMS | `sms-provider` через `api-gateway` | `provider-webhook-controller` |
| Раздел 6: чтение и запись базы | пять репозиториев | `platform-db` |
| Раздел 7, связь 4: коды и счётчики | `otp-service` | `redis` |
| Раздел 7.1, связь 4: секреты | `sms-client`, `email-sender`, `otp-service`, `audit-service` | `secret-store` |
| Раздел 8: события | `event-consumer` (чтение), `outbox-relay` (публикация), `config.changed` читают остальные сервисы | `kafka` |

## 3. Компонент → инварианты и требования

| Компонент | Инварианты | Требования и решения | Как обеспечивает |
| --- | --- | --- | --- |
| `identity-controller` | INV-34 (проверка на входе) | FT-1.4, FT-1.5, FT-1.6, FT-1.7, NFT-3.5 | Проверка DTO, областей токена и 2FA |
| `otp-controller` | | FT-1.5, FT-1.7, NFT-3.7, [ADR-010](adr/ADR-010-keycloak-sms-codes.md), [ADR-022](adr/ADR-022-internal-traffic-encryption.md) | Только `keycloak`, mTLS |
| `otp-service` | | FT-1.5, FT-1.6, FT-1.7, NFT-3.5, NFT-3.7, [ADR-010](adr/ADR-010-keycloak-sms-codes.md) | HMAC кода, TTL 5 мин, 5 попыток, ограничения частоты, fail-closed |
| `sms-client`, `email-sender` | INV-20, INV-23 (письма с ключом и значения ключей здесь отсутствуют) | NFT-4.3 | Адаптеры без данных ключей |
| `user-service` | INV-34, INV-35 (признак подтверждения телефона для токена), INV-36, INV-37, INV-38, INV-43, INV-44 | FT-1.0, FT-1.3, FT-1.4, FT-1.5, FT-11.2, NFT-3.0, NFT-3.6, NFT-5.0 | Уникальные индексы e-mail и телефона, проверка последнего администратора, роль продавца по событию, анонимизация |
| `keycloak-admin-client` | INV-37 | FT-1.3, NFT-3.0 | Включённое обязательное действие 2FA при назначении роли сотрудника |
| `seller-role-handler` | INV-36 | FT-2.1, US-8.1 | Роль только по `seller.approved`, вручную не назначается |
| `ticket-controller` | INV-22 (проверка окна на входе) | FT-7.2, FT-7.5 | Проверка окна и областей токена |
| `ticket-service` | INV-21, INV-22, INV-23, INV-43 | FT-7.2, FT-7.3, FT-7.5, NFT-2.4, [SM-07](../03-processes/SM-07-support-ticket.md) | Частичный уникальный индекс открытого обращения на заказ, таблица переходов SM-07, оператор получает только статусы |
| `support-event-handler` | INV-21, INV-22 | FT-7.2, FT-7.3, NFT-2.4, [ADR-011](adr/ADR-011-guaranteed-delivery.md) | Не создаёт второе открытое обращение по заказу, ведёт модель чтения для проверки окна 72 часа |
| `order-client` | | FT-7.5 | Обновление снимка адреса делает владелец заказа |
| `notification-service` | INV-44 (адрес из `identity` в момент отправки) | FT-11.0, FT-11.4, NFT-4.3, NFT-6.1 | Повторы отправки, событие `notification.failed` |
| `notification-handler` | | FT-2.2, FT-6.4, FT-11.0, FT-11.4 | Соответствие события и шаблона письма |
| `provider-webhook-controller` | | FT-11.0, NFT-3.3, [ADR-006](adr/ADR-006-idempotency.md) | Подпись, метка времени, дедупликация |
| `audit-consumer` | INV-42, INV-43 | FT-11.2, NFT-3.6 | Дубль по `event_id` не создаёт запись |
| `audit-service` | INV-42 | FT-11.2, NFT-5.2, NFT-5.3, [ADR-014](adr/ADR-014-audit-log-no-pii.md) | Только INSERT и SELECT, триггеры, секции, хеши вместо значений |
| `audit-controller` | INV-42 | FT-11.2 | Только чтение |
| `parameter-controller` | | FT-11.1 | Проверка областей токена и 2FA |
| `parameter-service` | INV-07, INV-26 (R2), INV-43 | FT-11.1, FT-5.2, [ADR-013](adr/ADR-013-late-payment.md) | Проверка при сохранении, значения до и после в журнале |
| `admin-repository` | INV-42 | NFT-5.2 | Отдельная роль и триггеры на `audit_log` |
| `event-consumer`, `outbox-relay` | INV-43 | NFT-2.3, NFT-6.0, [ADR-003](adr/ADR-003-kafka-events.md), [ADR-005](adr/ADR-005-transactional-outbox.md), [ADR-006](adr/ADR-006-idempotency.md) | Общий каркас |

Инварианты, которые относятся к `platform-service` по [domain-model.md](../04-domain/domain-model.md), раздел 6: INV-07, INV-21, INV-22, INV-23, INV-26 (R2), INV-34 – INV-38, INV-42, INV-43, INV-44, а также INV-20 и INV-23 как запрет (ключей в сервисе нет). Все закреплены за компонентом.

## 4. Проверка и решения

| Проверка | Результат |
| --- | --- |
| Каждый вызов и событие `platform-service` из [c4-containers.md](c4-containers.md) отображён на компонент | Выполнено, см. раздел 2 |
| Модули не читают таблицы друг друга | Выполнено: пять репозиториев по схемам, модули общаются через `identity.api` и `notification.api` и события |
| Персональные данные не уходят в Kafka и журнал в открытом виде | Выполнено: адрес для письма берётся у `identity` в момент отправки, журнал содержит хеши, событие аудита приходит с хешами |
| Ключи покупок не проходят через сервис | Выполнено: компонентов с доступом к значениям ключей нет |

| № | Вопрос | Решение | Причина |
| --- | --- | --- | --- |
| 1 | Где хранить адрес получателя уведомления | Не хранить: брать у `identity` по идентификатору в момент отправки | Персональные данные лежат в пользователе и в снимках заказа и выдачи (INV-44), анонимизация затрагивает все места одновременно |
| 2 | Кто считает хеш персонального поля для аудита | Владелец данных: `user-service` для телефона и e-mail | Открытое значение не покидает владельца, в Kafka идёт только хеш ([ADR-014](adr/ADR-014-audit-log-no-pii.md)) |
| 3 | Как параметры доходят до сервисов | `parameter-service` пишет в сжатую тему `platform.config`, сервисы читают с начала | Новый экземпляр сервиса получает актуальные значения без запроса к `platform-service` |
| 4 | Что делает `otp-service` при недоступном Redis | Отказывает во входе по коду и подтверждении телефона | Безопасность важнее доступности для кодов ([ADR-010](adr/ADR-010-keycloak-sms-codes.md)) |
