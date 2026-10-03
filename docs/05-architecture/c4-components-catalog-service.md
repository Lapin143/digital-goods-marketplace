# C4, уровень 3: компоненты сервиса каталога (таблица)

| Поле | Содержание |
| --- | --- |
| Документ | C4 Component: устройство `catalog-service`, описание таблицей без диаграммы |
| Фаза | Ф2, шаг 6 |
| Контейнер | `catalog-service`, Java, Spring Boot, база `catalog_db` ([c4-containers.md](c4-containers.md)) |
| Нотация | [c4-notation.md](c4-notation.md) |
| Основание | [decomposition.md](decomposition.md), раздел 4.1, [SM-04](../03-processes/SM-04-product.md), [SM-05](../03-processes/SM-05-seller-profile.md), [BPMN-02](../03-processes/BPMN-02-seller-onboarding.md), [BPMN-03](../03-processes/BPMN-03-product-moderation.md) |
| Общие компоненты | [c4-components.md](c4-components.md): каркас `service-kit`, правила транзакций, соглашения об именах |
| Предыдущий уровень | [c4-containers.md](c4-containers.md) |

Каталог не лежит на критическом пути покупки и не содержит разветвлённой логики с таймерами (есть одно плановое задание контроля сроков модерации), поэтому диаграмма компонентов отдельно не рисуется (требование шага 6: диаграммы обязательны для заказов, остатков, выдачи и платежей). Устройство описано таблицами: компоненты, связи с соседями, инварианты. Правило модульности: два модуля (`seller_onboarding`, `catalog`) в одном процессе, у каждого своя схема базы и своя роль ([decomposition.md](decomposition.md), раздел 7).

## 1. Компоненты

| Компонент | Алиас | Модуль | Spring-слой | Ответственность | Вызывает | События |
| --- | --- | --- | --- | --- | --- | --- |
| Контроллер продавцов | `seller-controller` | `seller_onboarding` | `@RestController` | Заявка продавца (анкета, документы), просмотр и исправление отклонённой заявки (FT-2.4), очередь модератора и его решения (одобрить, отклонить с причиной, вернуть на доработку), блокировка продавца (R2). Проверка областей `seller.apply`, `staff.moderation` | `seller-profile-service`, `document-store` | Нет |
| Профиль продавца | `seller-profile-service` | `seller_onboarding` | `@Service` домена, `@Transactional` | Переходы SM-05, правило «один профиль, пока на проверке вторая заявка не принимается» (INV-39), решение модератора с событием аудита в той же транзакции (INV-43). Через интерфейс модуля сообщает модулю `catalog` статус продавца | `seller-repository` | Пишет в Outbox: `seller.approved`, `seller.rejected`, `seller.returned`, `audit.recorded` |
| Хранилище документов | `document-store` | `seller_onboarding` | `@Component`, адаптер S3 API | Загрузка документов продавца в закрытый бакет объектного хранилища, проверка типа и размера, выдача короткоживущей ссылки только модератору и самому продавцу. Хранилище в РФ (NFT-5.0) | `object-storage` | Нет |
| Репозиторий продавцов | `seller-repository` | `seller_onboarding` | Spring Data JDBC | Запись в схему `seller_onboarding` и в `outbox`, `processed_event`. Роль базы с правами только на свою схему | `catalog-db` | Нет |
| Контроллер товаров | `product-controller` | `catalog` | `@RestController` | Создание и правка товара продавцом, отправка на модерацию, архив, решение модератора по товару (одобрить, отклонить, заблокировать). Проверка областей `seller.catalog`, `staff.moderation` | `product-service` | Нет |
| Товар | `product-service` | `catalog` | `@Service` домена, `@Transactional` | Переходы SM-04, правило видимости (на витрине только «опубликован», у блокированного заполнены «статус до блокировки» и «причина», INV-40), решение модератора с событием аудита одной транзакцией (INV-43). Проверяет у модуля `seller_onboarding`, что продавец одобрен и не заблокирован | `catalog-repository` | Пишет в Outbox: `product.created`, `product.updated`, `product.published`, `product.rejected`, `product.blocked`, `audit.recorded` |
| Контроллер витрины | `storefront-controller` | `catalog` | `@RestController` | Публичный каталог, карточка товара, поиск и фильтры без токена. Отдаёт только опубликованные товары и признак доступности (нулевой остаток означает «недоступен для заказа», INV-09) | `search-service`, `catalog-cache` | Нет |
| Поиск | `search-service` | `catalog` | `@Service` | Поиск по названию и фильтры по типу, платформе, региону, цене средствами PostgreSQL (полнотекстовый поиск, ADR-019 в Ф6). Цель 500 мс (NFT-1.1) | `catalog-repository` | Нет |
| Кэш витрины | `catalog-cache` | `catalog` | `@Component`, Redis | Кэш карточек и результатов поиска с коротким временем жизни, сброс по событиям товара. Недоступность Redis не нарушает работу: чтение идёт из базы | `redis` | Нет |
| Представление остатка | `stock-view-service` | `catalog` | `@Service` | Хранит признак доступности и число свободных ключей для витрины, обновляется событием `stock.changed` | `catalog-repository`, `catalog-cache` | Читает: `stock.changed` |
| Карточка товара для заказов | `product-card-controller` | `catalog` | `@RestController`, внутренний | «Карточка товара» для `order-service`: статус, цена, продавец, способ выдачи. Вызывающий проверяется по списку (`order-service`), ответ за 500 мс | `catalog-repository` | Нет |
| Репозиторий каталога | `catalog-repository` | `catalog` | Spring Data JDBC | Запись в схему `catalog` и в `outbox`, `processed_event`. Роль базы с правами только на свою схему | `catalog-db` | Нет |
| Потребитель событий | `event-consumer` | оба | Слушатель Kafka из каркаса | Читает `inventory.events` (`stock.changed`). Пропускает обработанные, повторы 1, 5, 25 с, DLQ | `stock-view-service`, `catalog-repository` | Читает: `stock.changed` |
| Слушатель параметров | `config-listener` | оба | Слушатель Kafka без группы | Читает `platform.config` с начала: комиссию, пределы размера документов и карточки | Нет | Читает: `config.changed` |
| Контроль сроков модерации | `moderation-deadline-job` | оба | `@Scheduled`, из каркаса | Раз в 5 минут находит заявки продавцов «на проверке» и товары «на модерации» старше 3 суток (срок записывается при постановке в очередь), помечает каждую один раз и обновляет метрики сроков модерации. Оповещение администратору даёт правило мониторинга по метрике, события и письма нет, автоматического решения нет (BPMN-02 E3, BPMN-03 E5, FT-2.1, FT-3.2, NFT-6.1, [SEQ-06](sequence-seller-approval.md)) | `seller-repository`, `catalog-repository` | Нет |
| Публикатор Outbox | `outbox-relay` | оба | `@Scheduled`, из каркаса | Публикует записи Outbox обоих модулей в `catalog.events` и `audit.events` ([ADR-005](adr/ADR-005-transactional-outbox.md)) | `catalog-db`, `kafka` | Публикует записанное |

Расширение релиза R2 (на диаграмме не показано, правило 8 нотации):

| Компонент | Алиас | Модуль | Spring-слой | Ответственность | События |
| --- | --- | --- | --- | --- | --- |
| API-ключи | `api-key-service` | `seller_onboarding` | `@Service` | Выпуск и отзыв API-ключа в кабинете. Хранится только хеш SHA-256, значение показывается один раз (INV-41, NFT-3.4) | `api-key.issued`, `api-key.revoked` |
| Проверка API-ключа | `api-key-check-controller` | `seller_onboarding` | `@RestController`, внутренний | «Проверить API-ключ» для `api-gateway`: по хешу возвращает продавца и права. Ответ кэширует шлюз на 30 секунд ([ADR-021](adr/ADR-021-api-gateway.md)) | Нет |
| Реквизиты продавца | `seller-payout-controller` | `seller_onboarding` | `@RestController`, внутренний | «Реквизиты и статус продавца» для `finance-service` | Нет |
| Блокировка продавца | `seller-block-handler` | `catalog` | Метод `product-service` | По `seller.blocked` блокирует товары продавца, по `seller.unblocked` возвращает. Внутри сервиса через интерфейс модуля, наружу не выходит | `product.blocked` |

## 2. Связи с соседями

| Связь контейнера ([c4-containers.md](c4-containers.md)) | Откуда | Куда (компонент) |
| --- | --- | --- |
| Раздел 2, связь 5: заявки, товары, витрина, решения модератора | `api-gateway` | `seller-controller`, `product-controller`, `storefront-controller` |
| Раздел 3, связь 1: «карточка товара» | `order-service` | `product-card-controller` |
| Раздел 6: чтение и запись баз | `seller-repository`, `catalog-repository` | `catalog-db` |
| Раздел 7, связь 2: кэш карточек и поиска | `catalog-cache` | `redis` |
| Раздел 7, связь 5: документы продавцов | `document-store` | `object-storage` |
| Раздел 8: «остаток изменился» | `inventory-service` | `event-consumer` → `stock-view-service` |
| Раздел 8: профиль одобрен, отклонён, возвращён | `seller-profile-service` | `outbox-relay` → `catalog.events` |
| Раздел 8: товар создан, изменён, опубликован, отклонён, заблокирован | `product-service` | `outbox-relay` → `catalog.events` |
| Раздел 8: события аудита решений модератора | `seller-profile-service`, `product-service` | `outbox-relay` → `audit.events` |
| Раздел 8: параметры изменены | `platform-service` | `config-listener` |
| Раздел 9.1, связь 2 (R2): проверка API-ключа | `api-gateway` | `api-key-check-controller` |
| Раздел 9.2, связь 2 (R2): реквизиты продавца | `finance-service` | `seller-payout-controller` |

## 3. Компонент → инварианты и требования

| Компонент | Инварианты | Требования и решения | Как обеспечивает |
| --- | --- | --- | --- |
| `seller-controller` | INV-39 (первая проверка на входе) | FT-2.0, FT-2.1, FT-2.4, NFT-5.0 | Проверка DTO и областей токена |
| `seller-profile-service` | INV-36 (роль «Продавец» только при одобренном профиле: событие `seller.approved` единственное основание), INV-39, INV-43 | FT-2.1, FT-2.2, FT-2.3, NFT-3.6, [SM-05](../03-processes/SM-05-seller-profile.md) | Таблица переходов SM-05, уникальность активного профиля на пользователя, аудит одной транзакцией |
| `document-store` | | FT-2.0, NFT-5.0 | Закрытый бакет, короткоживущие ссылки |
| `product-controller` | | FT-3.0, FT-3.2 | Проверка DTO и областей токена |
| `product-service` | INV-40, INV-43 | FT-3.0, FT-3.1, FT-3.2, FT-4.0, NFT-3.6, [SM-04](../03-processes/SM-04-product.md) | Таблица переходов SM-04, условие `CHECK` на поля блокировки, аудит одной транзакцией |
| `storefront-controller` | INV-09 (витрина), INV-40 | FT-3.3, FT-3.4, FT-4.4, NFT-1.0, NFT-1.1 | Только опубликованные товары, признак доступности |
| `search-service` | INV-40 | FT-3.3, FT-3.4, NFT-1.1 | Запросы только по опубликованным товарам |
| `catalog-cache` | | NFT-1.0, NFT-1.1 | Кэш с коротким TTL, сброс по событиям |
| `stock-view-service` | INV-09 | FT-4.3, FT-4.4 | Признак доступности по `stock.changed` |
| `product-card-controller` | | FT-5.0, NFT-1.2 | Ответ из индекса по идентификатору, список разрешённых вызывающих |
| `api-key-service` (R2) | INV-41 | FT-10.0, NFT-3.4 | Хранится только хеш, значение один раз |
| `moderation-deadline-job` | | FT-2.1, FT-3.2, NFT-6.1 | Только пометка и метрика, статус профиля и товара не меняет (SM-05 и SM-04: перехода по таймеру нет) |
| `event-consumer`, `config-listener`, `outbox-relay` | INV-43 (публикация аудита) | NFT-2.3, NFT-6.0, [ADR-003](adr/ADR-003-kafka-events.md), [ADR-005](adr/ADR-005-transactional-outbox.md), [ADR-006](adr/ADR-006-idempotency.md) | Общий каркас |

Инварианты, которые относятся к `catalog-service` по [domain-model.md](../04-domain/domain-model.md), раздел 6: INV-09 (сторона витрины), INV-36 (сторона источника события), INV-39, INV-40, INV-41 (R2), INV-43. Все закреплены за компонентом.

## 4. Проверка и решения

| Проверка | Результат |
| --- | --- |
| Каждый вызов и событие каталога из [c4-containers.md](c4-containers.md) отображён на компонент | Выполнено, см. раздел 2 |
| Модули не обращаются к таблицам друг друга | Выполнено: у модулей отдельные схемы и роли, общение через интерфейс модуля и события |
| Документы продавцов не попадают в запросы витрины | Выполнено: `document-store` отделён от `storefront-controller`, витрина не имеет доступа к бакету |

| № | Вопрос | Решение | Причина |
| --- | --- | --- | --- |
| 1 | Диаграмма или таблица | Таблица | Одно плановое задание контроля сроков и нет сложных потоков, сервис не на критическом пути |
| 2 | Где кэш витрины | В компоненте `catalog-cache`, не в контроллере | Правила сброса по событиям в одном месте |
| 3 | Как заказы получают карточку товара | Отдельный внутренний маршрут `product-card-controller`, не публичная витрина | Разные требования к безопасности и ответу (статус, цена, продавец, способ выдачи) |
