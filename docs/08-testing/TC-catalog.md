# Тест-кейсы: каталог, модерация товаров и витрина (TC-301 – TC-329)

| Поле | Содержание |
| --- | --- |
| Документ | Тест-кейсы эпика 3 «Каталог», область TC-301 – TC-399 ([стратегия](test-strategy.md), раздел 13). Заполнено TC-301 – TC-329, остальные номера свободны |
| Источники | [US-03](../02-requirements/US-03-catalog.md) (US-3.1 – US-3.6, US-3.8 – US-3.11), US-4.6 в части остатка на витрине, [требования v1.6](../02-requirements/requirements_v1.6.md) (FT-3.0 – FT-3.4, FT-4.4, NFT-1.0), [модель угроз](../05-architecture/threat-model.md) (ST-02, ST-13), SM-04 |
| Срез | Ф4, срез 1: товар продавца, модерация, витрина, поиск, фильтры, копия остатка по `stock.changed`. Письма продавцу о решениях по товару (US-3.7, FT-11.4) относятся к срезу 3 |
| Состояние | Все тест-кейсы автоматизированы, ручных нет. Стенд: `make seller-approval-check` (TC-329), `make ui-smoke`, `make e2e`, `make kit-test` (набор `dev-kit`). Сверка с кодом: `tools/docs-checks/check_testcases.py` |

## 1. Как читать

Оформление и поля те же, что в [TC-accounts.md](TC-accounts.md) (раздел 1) и [шаблоне](_template-test-case.md). Тест-кейсы этой области проверяются на шести уровнях:

- модульные тесты (`*Test.java`, задание `build`): форма товара, переходы SM-04 (в том числе вся матрица «статус × действие»), службы продавца и модератора на подменном хранилище, контроллеры витрины и кабинета на `MockMvc`, копия остатка;
- интеграционные тесты на стенде (`*IT.java`, задание `kit`): `SellerProductIT`, `ProductModerationIT`, `CatalogServiceIT`; настоящие PostgreSQL, Kafka и Redis, запросы идут по HTTPS через клиент шлюза, события читаются из Outbox;
- Newman (`make e2e`): `storefront.json`, `moderation.json` и матрица прав ST-02 для операций каталога;
- скрипт стенда `seller_approval_checks.py`: товар от заявки до витрины с настоящим вводом (TC-329);
- экраны (Vitest, задание `web`): каталог, карточка, кабинет продавца и рабочее место модератора на тестовом сервере API;
- дымовой сценарий в браузере (`ui_smoke.py`): модератор решает товары щелчками, гость находит опубликованный товар.

Деньги в API хранятся в копейках, на экране показываются в рублях («1 499 ₽»). Значения ключей в тестах каталога не участвуют: ключи относятся к области [TC-keys.md](TC-keys.md).

## 2. Сводка

| TC | Название | История | Уровень | Чем проверяется |
| --- | --- | --- | --- | --- |
| TC-301 | Создание товара: черновик, ETag, событие product.created | US-3.1 | L3 | JUnit, JUnit на стенде |
| TC-302 | Форма товара: поля, длины, цена, регионы | US-3.1 | L3 | JUnit, JUnit на стенде |
| TC-303 | Только одобренный продавец работает с товарами | US-3.1 | L7 | JUnit, JUnit на стенде, Newman |
| TC-304 | Свои и чужие товары, список с курсором | US-3.1 | L3 | JUnit, JUnit на стенде |
| TC-305 | Правка товара: If-Match, версия, параллельные правки | US-3.1, US-3.6 | L3 | JUnit, JUnit на стенде |
| TC-306 | Отправка товара на модерацию, срок 3 суток | US-3.2 | L3 | JUnit, JUnit на стенде, `seller_approval_checks.py` |
| TC-307 | Продавец не публикует сам: матрица статус × действие | US-3.2 | L1 | JUnit |
| TC-308 | Правка опубликованного товара: что идёт на модерацию | US-3.6 | L3 | JUnit, JUnit на стенде |
| TC-309 | Архив и восстановление товара | US-3.8 | L3 | JUnit, JUnit на стенде |
| TC-310 | Очередь и карточка товара у модератора | US-3.3 | L3 | JUnit, JUnit на стенде, `seller_approval_checks.py` |
| TC-311 | Одобрение товара: статус, событие, аудит, кэш | US-3.3 | L3 | JUnit, JUnit на стенде, `seller_approval_checks.py` |
| TC-312 | Отклонение товара с причиной | US-3.4 | L3 | JUnit, JUnit на стенде, `seller_approval_checks.py` |
| TC-313 | Блокировка товара: причина, витрина, кэш | US-3.5 | L3 | JUnit, JUnit на стенде, `seller_approval_checks.py` |
| TC-314 | Исправление и повторная подача после отклонения или блокировки | US-3.4, US-3.5 | L3 | JUnit, JUnit на стенде, `seller_approval_checks.py` |
| TC-315 | Права на решения по товарам: ST-02 и ST-13 | US-3.3, US-3.4, US-3.5 | L7 | JUnit на стенде, Newman |
| TC-316 | Идемпотентность создания, подачи и решений | US-3.1, US-3.2, US-3.3 | L3 | JUnit на стенде |
| TC-317 | Сбой записи события откатывает изменение | US-3.2, US-3.3 | L3 | JUnit, JUnit на стенде |
| TC-318 | Карточка опубликованного товара на витрине | US-3.9 | L3 | JUnit, JUnit на стенде, Newman, `seller_approval_checks.py` |
| TC-319 | Кэш карточки: TTL, сброс после коммита, сбой Redis | US-3.9 | L3 | JUnit, JUnit на стенде, `seller_approval_checks.py` |
| TC-320 | Нулевой остаток: карточка видна, купить нельзя | US-3.9 | L3 | JUnit, JUnit на стенде |
| TC-321 | Остаток на витрине по событию stock.changed | US-4.6, US-3.9 | L3 | JUnit, JUnit на стенде |
| TC-322 | Список витрины: порядок, курсор, ETag | US-3.9 | L3 | JUnit, JUnit на стенде, Newman |
| TC-323 | Поиск по части названия | US-3.10 | L3 | JUnit, JUnit на стенде, Newman, `seller_approval_checks.py` |
| TC-324 | Фильтры каталога и ошибки параметров | US-3.11 | L3 | JUnit, JUnit на стенде, Newman |
| TC-325 | Внутренняя карточка товара для order-service | US-3.9 | L3 | JUnit, JUnit на стенде |
| TC-326 | Каталог и карточка товара в браузере | US-3.9, US-3.10, US-3.11 | L5 | Vitest, `ui_smoke.py` |
| TC-327 | Кабинет продавца в браузере | US-3.1, US-3.2, US-3.6, US-3.8 | L5 | Vitest |
| TC-328 | Рабочее место модератора: товары и решения в браузере | US-3.3, US-3.4, US-3.5 | L5 | Vitest, `ui_smoke.py` |
| TC-329 | Сквозной путь товара до витрины (К2) | US-3.3, US-3.5, US-3.9 | L5 | `seller_approval_checks.py` |

## 3. Тест-кейсы

### TC-301. Создание товара: черновик версии 1, ETag, `Location` и событие `product.created` в одной транзакции

| Поле | Содержание |
| --- | --- |
| ID | TC-301 |
| User story | US-3.1 (сценарий 1) |
| Требования | FT-3.0, FT-3.1 |
| Уровень | L3 |
| Тип | API |
| Приоритет | Высокий |
| Автоматизация | JUnit; JUnit на стенде |
| Тег в коде | TC-301 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/SellerProductIT.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/seller/controller/SellerProductControllerTest.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/seller/domain/ProductTransitionsTest.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/seller/service/SellerProductServiceTest.java` |

**Предусловия.** Токен: продавец с одобренной заявкой (роль `seller`, область `seller.catalog`, `amr = pwd, otp`, claim `seller_id`). Форма: название, описание, тип `game_key`, платформа Steam, регионы RU и KZ, цена 1 499 ₽ (149 900 копеек), способ выдачи `key_pool`.

**Шаги.**

1. `POST /api/v1/seller/products` с формой.
2. Прочитать товар (`GET /api/v1/seller/products/{productId}`) и список своих товаров.
3. Создать товар с пустым списком регионов.

**Ожидаемый результат.** Шаг 1: 201, статус `draft`, версия 1, заголовки `ETag` (`"1"`) и `Location`, `Cache-Control: no-store`, тексты обрезаны по краям. В той же транзакции записано одно событие `product.created`. Черновика нет ни на витрине (404), ни в очереди модератора (404). Шаг 2: товар первым в списке, ETag тот же. Шаг 3: пустой список регионов означает «все регионы» и допустим, отсутствие поля нет.

### TC-302. Форма товара: обязательные поля, длины в кодовых точках, цена, регионы, способ выдачи; все ошибки сразу

| Поле | Содержание |
| --- | --- |
| ID | TC-302 |
| User story | US-3.1 (сценарий 2) |
| Требования | FT-3.0 |
| Уровень | L3 |
| Тип | API |
| Приоритет | Высокий |
| Автоматизация | JUnit; JUnit на стенде |
| Тег в коде | TC-302 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/SellerProductIT.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/seller/controller/SellerProductControllerTest.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/seller/domain/ProductFormTest.java` |

**Предусловия.** Токен продавца (TC-301).

**Шаги.**

1. Создать товар с пустым телом `{}`.
2. Название из 201 символа, описание из 5 001 символа, платформа из 65 символов; те же поля на границе (200, 5 000, 64) и 200 эмодзи.
3. Цена 0, –1, 1,5 копейки, 1 копейка, 9 007 199 254 740 991 копеек (максимальная целая цена JSON) и на единицу больше.
4. Регионы `ru`, `RUS`, повтор `RU, KZ, RU`, 251 и 250 кодов; поле `activationRegions` строкой вместо списка.
5. Способ выдачи `seller_api`, неизвестное поле (в том числе внутри `price`), валюта USD, название числом, неизвестный тип товара.
6. Тело `[]` и обрезанный JSON, тело больше предела, `Content-Type: text/plain`.

**Ожидаемый результат.** Шаг 1: 422, перечислены все поля сразу в порядке контракта (`/title`, `/description`, `/productType`, `/platform`, `/activationRegions`, `/price/amount`, `/issuanceMethod`), код `required`. Шаг 2: `too_long` за границей, `too_short` для пустой строки, граница принимается, длина считается в кодовых точках. Шаг 3: цена ниже 1 или выше предела безопасного целого JSON даёт `out_of_range`, дробное число `invalid_format`, 1 копейка и максимум принимаются. Шаг 4: `invalid_format` для кода не из двух заглавных латинских букв или для строки вместо списка, `not_unique` для повтора, 251 код `too_long`, 250 принимаются. Шаг 5: способ выдачи `seller_api` в R1 недоступен (`unknown_value`), неизвестное поле, валюта и тип товара тоже `unknown_value`, неверный тип значения `invalid_format`, каждое поле названо один раз. Шаг 6: `[]` и обрезанный JSON дают 400, слишком большое тело 413, `text/plain` 415. При любой ошибке ничего не записывается.

### TC-303. Товар создаёт и меняет только одобренный продавец: нет `seller_id`, профиль не одобрен, чужая роль, нет токена

| Поле | Содержание |
| --- | --- |
| ID | TC-303 |
| User story | US-3.1 (сценарий 3) |
| Требования | FT-3.0, NFT-3.0 |
| Уровень | L7 |
| Тип | безопасность |
| Приоритет | Высокий |
| Автоматизация | JUnit; JUnit на стенде; Newman |
| Тег в коде | TC-303 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/SellerProductIT.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/seller/controller/SellerProductControllerTest.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/seller/service/SellerProductServiceTest.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/service/ApprovedSellerDirectoryTest.java`, `tools/api-tests/scenarios/moderation.json` |

**Предусловия.** Токены: продавец с одобренным профилем; продавец без claim `seller_id`; продавец, чей профиль не одобрен (на проверке, отклонён); покупатель; модератор; администратор; гость.

**Шаги.**

1. Вызвать каждую операцию кабинета товаров (`createSellerProduct`, `listSellerProducts`, `getSellerProduct`, `replaceSellerProduct`, `submitSellerProduct`, `archiveSellerProduct`, `restoreSellerProduct`) всеми токенами.
2. Создать товар токеном продавца, чей профиль не одобрен, и токеном без `seller_id`.

**Ожидаемый результат.** Шаг 1: одобренный продавец получает ответ по существу; гость 401; покупатель, модератор и администратор 403; продавец без профиля или с неодобренным профилем 403. Шаг 2: 403, в базе ничего не появляется и события не пишутся (проверка идёт по справочнику одобренных продавцов каталога, а не по токену).

### TC-304. Свои и чужие товары: список (новые выше, фильтр по статусам, курсор), чужой и неизвестный товар неразличимы

| Поле | Содержание |
| --- | --- |
| ID | TC-304 |
| User story | US-3.1 |
| Требования | FT-3.0 |
| Уровень | L3 |
| Тип | API |
| Приоритет | Средний |
| Автоматизация | JUnit; JUnit на стенде |
| Тег в коде | TC-304 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/SellerProductIT.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/seller/controller/SellerProductControllerTest.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/seller/service/SellerProductServiceTest.java` |

**Предусловия.** Два одобренных продавца, у каждого товары в разных статусах.

**Шаги.**

1. `GET /api/v1/seller/products` с `limit = 2`, затем по курсору.
2. Тот же список с фильтром по статусам.
3. Прочитать, изменить и подать чужой товар; прочитать неизвестный; передать идентификатор не в нижнем регистре.
4. Передать одновременно неверные параметры списка: `status=draft,unknown`, `limit=0`, `cursor` не из ответа, `sort=price`.

**Ожидаемый результат.** Шаг 1: только свои товары, новые выше, страницы не пропускают и не повторяют записи, у последней полной страницы без следующей записи курсора нет. Шаг 2: в списке только выбранные статусы. Шаг 3: чужой и неизвестный товар дают одинаковый 404 (продавец не узнаёт, что товар существует), идентификатор не в формате UUID в нижнем регистре 400. Шаг 4: 422 `validation-failed`, в ответе названы все четыре параметра (`cursor`, `limit`, `sort`, `status`).

### TC-305. Правка товара: `If-Match`, 428/400/412, параллельные правки, закрытые статусы

| Поле | Содержание |
| --- | --- |
| ID | TC-305 |
| User story | US-3.1; US-3.6 (сценарий 3) |
| Требования | FT-3.0, FT-3.2 |
| Уровень | L3 |
| Тип | API |
| Приоритет | Высокий |
| Автоматизация | JUnit; JUnit на стенде |
| Тег в коде | TC-305 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/SellerProductIT.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/seller/controller/SellerProductControllerTest.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/seller/domain/ProductTransitionsTest.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/seller/service/SellerProductServiceTest.java` |

**Предусловия.** Товар продавца в статусе `draft`; для закрытых статусов товары на модерации, в архиве.

**Шаги.**

1. `PUT /api/v1/seller/products/{productId}` без `If-Match`, с ETag неверной формы, со старой версией и с текущей.
2. Две параллельные правки с одной и той же версией.
3. Правка товара на модерации и товара в архиве.
4. Правка товара, чей ETag прочитан до чужой правки.

**Ожидаемый результат.** Шаг 1: 428, 400, 412 (ничего не меняется) и 200 с новым ETag и версией плюс один. Шаг 2: одна правка применена, вторая получает 412, событие `product.updated` записано один раз. Шаг 3: 409 `state-conflict` с текущим статусом, поля не меняются (модератор одобряет именно то, что проверял). Шаг 4: 412. Порядок проверок: профиль продавца (403), заголовок `If-Match` (428, 400), тело (422), товар (404), статус (409), версия (412).

### TC-306. Отправка на модерацию: `draft`, `rejected`, `blocked` → `on_moderation`, срок 3 суток, повтор 409 без сброса срока

| Поле | Содержание |
| --- | --- |
| ID | TC-306 |
| User story | US-3.2 (сценарии 1 – 3) |
| Требования | FT-3.1, FT-3.2 |
| Уровень | L3 |
| Тип | API |
| Приоритет | Высокий |
| Автоматизация | JUnit; JUnit на стенде; Python на стенде |
| Тег в коде | TC-306 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/SellerProductIT.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/seller/controller/SellerProductControllerTest.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/seller/domain/ProductTransitionsTest.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/seller/service/SellerProductServiceTest.java`, `tools/stand-checks/seller_approval_checks.py` |

**Предусловия.** Товары продавца в статусах `draft`, `rejected`, `blocked`, `on_moderation`, `published`, `archived`; пул ключей пуст.

**Шаги.**

1. `POST /api/v1/seller/products/{productId}/submit` для товара в `draft`, `rejected`, `blocked`.
2. Повторить подачу для товара `on_moderation`; подать товары в `published` и `archived`.
3. Прочитать очередь модератора.

**Ожидаемый результат.** Шаг 1: 200, статус `on_moderation`, `submittedAt` равен времени подачи, `reviewDeadlineAt` на 3 суток позже, прежняя причина отказа или блокировки очищена, форма не изменилась, записано одно событие `product.updated` (`changedFields = ["status"]`). Ключи для подачи не нужны. Шаг 2: 409 `state-conflict` с текущим статусом, срок первой подачи не сбрасывается, второй записи в очереди нет. Шаг 3: товар в очереди.

### TC-307. Публикация без модератора невозможна: матрица «статус × действие продавца» из 30 пар

| Поле | Содержание |
| --- | --- |
| ID | TC-307 |
| User story | US-3.2 (сценарий 4) |
| Требования | FT-3.1, FT-3.2 |
| Уровень | L1 |
| Тип | API |
| Приоритет | Высокий |
| Автоматизация | JUnit |
| Тег в коде | TC-307 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/catalog/seller/domain/ProductTransitionsTest.java` |

**Предусловия.** Товары во всех шести статусах (`draft`, `on_moderation`, `published`, `rejected`, `blocked`, `archived`).

**Шаги.**

1. Для каждого статуса применить правку цены, правку названия, подачу, архивацию и восстановление.
2. Проверить, что матрица содержит каждое действие в каждом статусе.

**Ожидаемый результат.** Шаг 1: итоговый статус или 409 строго по SM-04: правка цены не меняет статус нигде, где она разрешена; правка названия опубликованного даёт `on_moderation`; подача из `draft`, `rejected`, `blocked` даёт `on_moderation`; архивация из `draft`, `on_moderation`, `published`, `rejected`; восстановление только из `archived` в `draft`. Ни одно действие продавца не приводит к `published`. Шаг 2: 30 пар (6 статусов × 5 действий), ни одной не пропущено.

### TC-308. Правка опубликованного товара: название, описание и тип идут на модерацию, цена, платформа, регион и способ выдачи нет

| Поле | Содержание |
| --- | --- |
| ID | TC-308 |
| User story | US-3.6 (сценарии 1, 2) |
| Требования | FT-3.2 |
| Уровень | L3 |
| Тип | API |
| Приоритет | Высокий |
| Автоматизация | JUnit; JUnit на стенде |
| Тег в коде | TC-308 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/SellerProductIT.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/seller/controller/SellerProductControllerTest.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/seller/domain/ProductFormTest.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/seller/domain/ProductTransitionsTest.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/seller/service/SellerProductServiceTest.java` |

**Предусловия.** Опубликованный товар, его карточка в кэше витрины.

**Шаги.**

1. Изменить цену; платформу, регион и способ выдачи по отдельности.
2. Изменить название; описание; тип товара.
3. Изменить одновременно цену и название.
4. Отправить правку без отличий от сохранённой формы.

**Ожидаемый результат.** Шаг 1: 200, статус `published`, модерация не запускается, в событии `product.updated` перечислены изменённые поля, карточка в кэше сброшена. Шаг 2: товар сразу снимается с витрины, статус `on_moderation`, срок проверки 3 суток отсчитывается заново. Шаг 3: правка уходит на модерацию, ответ называет оба поля и статус. Шаг 4: ничего не меняется, версия не растёт, событие не пишется, кэш остаётся. Различия считаются в порядке контракта, порядок регионов не важен.

### TC-309. Архив и восстановление: переходы T12 и T13, заблокированный товар в архив не уходит, возврат только через черновик

| Поле | Содержание |
| --- | --- |
| ID | TC-309 |
| User story | US-3.8 (сценарии 1 – 4) |
| Требования | FT-3.1 |
| Уровень | L3 |
| Тип | API |
| Приоритет | Средний |
| Автоматизация | JUnit; JUnit на стенде |
| Тег в коде | TC-309 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/SellerProductIT.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/seller/controller/SellerProductControllerTest.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/seller/domain/ProductTransitionsTest.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/seller/service/SellerProductServiceTest.java` |

**Предусловия.** Товары продавца во всех статусах; один заблокирован модератором.

**Шаги.**

1. `POST …/archive` для `draft`, `on_moderation`, `published`, `rejected`.
2. `POST …/archive` для `blocked` и `archived`.
3. `POST …/restore` для архивного товара и для товаров в других статусах.
4. Подать восстановленный товар на модерацию.

**Ожидаемый результат.** Шаг 1: 200, статус `archived`, версия растёт, товар уходит из очереди и с витрины, последнее время публикации сохраняется, пишется одно событие `product.updated`. Шаг 2: 409. Шаг 3: из архива товар возвращается в `draft`, из других статусов 409. Шаг 4: публикация из архива только через обычную модерацию.

### TC-310. Очередь и карточка товара у модератора: только поданные, старые выше, курсор, просрочка; ключей в карточке нет

| Поле | Содержание |
| --- | --- |
| ID | TC-310 |
| User story | US-3.3 (сценарий 3) |
| Требования | FT-3.2 |
| Уровень | L3 |
| Тип | API |
| Приоритет | Высокий |
| Автоматизация | JUnit; JUnit на стенде; Python на стенде |
| Тег в коде | TC-310 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/ProductModerationIT.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/moderation/controller/ProductModerationControllerTest.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/moderation/service/ProductModerationServiceTest.java`, `tools/stand-checks/seller_approval_checks.py` |

**Предусловия.** Токен: moderator-1 (роль `moderator`, область `staff.moderation`, `amr = pwd, otp`). Товары продавцов в разных статусах, у одного пул из нескольких ключей.

**Шаги.**

1. `GET /api/v1/staff/products`.
2. Пройти очередь страницами по курсору; передать неверные параметры и курсор не из этой очереди.
3. `GET /api/v1/staff/products/{productId}` для товара на модерации, опубликованного, отклонённого.
4. Открыть карточку черновика и неизвестного товара; передать неверный идентификатор.

**Ожидаемый результат.** Шаг 1: 200, `Cache-Control: no-store`, в очереди только товары `on_moderation`, самые старые выше, у записи поля `productId`, `sellerId`, `title`, `productType`, `price`, `submittedAt`, `reviewDeadlineAt` и признак просрочки `overdue`. Шаг 2: каждая запись один раз, без пропусков, ошибки параметров собраны в один 422. Шаг 3: 200, вся форма, статус, продавец, ETag равен версии; значений ключей и остатка в ответе нет. Шаг 4: черновик и неизвестный товар 404, неверный идентификатор 400 до обращения к базе. В браузере то же видно в очереди и на карточке (TC-328).

### TC-311. Одобрение товара: `published`, событие, запись аудита и сброс кэша витрины после коммита

| Поле | Содержание |
| --- | --- |
| ID | TC-311 |
| User story | US-3.3 (сценарии 1, 2) |
| Требования | FT-3.2, FT-11.2 |
| Уровень | L3 |
| Тип | API |
| Приоритет | Высокий |
| Автоматизация | JUnit; JUnit на стенде; Python на стенде |
| Тег в коде | TC-311 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/ProductModerationIT.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/moderation/controller/ProductModerationControllerTest.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/moderation/domain/ProductDecisionsTest.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/moderation/service/ProductModerationServiceTest.java`, `tools/stand-checks/seller_approval_checks.py` |

**Предусловия.** Товар `on_moderation`, токен модератора, карточка товара ещё не в кэше.

**Шаги.**

1. `POST /api/v1/staff/products/{productId}/approve` с `Idempotency-Key`.
2. Прочитать карточку на витрине без токена.
3. Повторить одобрение под новым ключом.

**Ожидаемый результат.** Шаг 1: 200, статус `published`, время публикации заполнено, срок проверки закрыт; в одной транзакции записаны статус, событие `product.published` (идентификатор товара, продавец, название) и запись аудита `audit.recorded` с модератором, его ролью и адресом. Кэш карточки сбрасывается только после коммита. Шаг 2: 200, цена и название верны, `inStock = false` при пустом пуле. Шаг 3: 409 `state-conflict` с текущим статусом, новых событий и записей нет.

### TC-312. Отклонение товара: причина 1 – 1 000 символов, статус `rejected`, причина видна продавцу, отклонить можно только товар на модерации

| Поле | Содержание |
| --- | --- |
| ID | TC-312 |
| User story | US-3.4 (сценарии 1, 2, 4) |
| Требования | FT-3.2 |
| Уровень | L3 |
| Тип | API |
| Приоритет | Высокий |
| Автоматизация | JUnit; JUnit на стенде; Python на стенде |
| Тег в коде | TC-312 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/ProductModerationIT.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/moderation/controller/ProductModerationControllerTest.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/moderation/domain/ProductDecisionsTest.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/moderation/service/ProductModerationServiceTest.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/seller/controller/SellerProductControllerTest.java`, `tools/stand-checks/seller_approval_checks.py` |

**Предусловия.** Товар `on_moderation`, токен модератора.

**Шаги.**

1. `POST …/reject` без тела, с пустой причиной и причиной из пробелов, с лишним полем, с причиной в 1 001 символ.
2. `POST …/reject` с причиной в 1 000 символов и с обычной причиной.
3. Продавец читает свой товар.
4. Отклонить товар в статусах `draft`, `published`, `rejected`, `blocked`, `archived`.

**Ожидаемый результат.** Шаг 1: 422 (причина обязательна, непустая, не длиннее 1 000 символов, других полей нет), тело не JSON даёт 400 или 415, статус не меняется. Шаг 2: 200, `rejected`, причина обрезана по краям и записана в товар, событие `product.rejected` и аудит. Шаг 3: продавец видит `rejectionReason`. Шаг 4: 409 `state-conflict` для любого другого статуса; черновика для модератора не существует (404).

### TC-313. Блокировка опубликованного товара: причина, статус `blocked`, исчезает с витрины, продавец видит причину

| Поле | Содержание |
| --- | --- |
| ID | TC-313 |
| User story | US-3.5 (сценарии 1 – 3) |
| Требования | FT-3.2 |
| Уровень | L3 |
| Тип | API |
| Приоритет | Высокий |
| Автоматизация | JUnit; JUnit на стенде; Python на стенде |
| Тег в коде | TC-313 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/ProductModerationIT.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/moderation/controller/ProductModerationControllerTest.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/moderation/domain/ProductDecisionsTest.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/moderation/service/ProductModerationServiceTest.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/seller/controller/SellerProductControllerTest.java`, `tools/stand-checks/seller_approval_checks.py` |

**Предусловия.** Опубликованный товар, его карточка читалась с витрины (в кэше).

**Шаги.**

1. `POST …/block` без причины и с причиной из пробелов.
2. `POST …/block` с причиной «Жалоба правообладателя».
3. Прочитать карточку на витрине и товар в кабинете продавца.
4. Заблокировать товар в статусах, отличных от `published`.

**Ожидаемый результат.** Шаг 1: 422, статус не меняется. Шаг 2: 200, `blocked`, причина записана, событие `product.blocked` и аудит в той же транзакции, кэш карточки сброшен после коммита. Шаг 3: витрина отвечает 404 сразу (не по истечении TTL кэша), продавец видит `blockReason`. Шаг 4: 409 `state-conflict`.

### TC-314. Исправление и повторная подача после отклонения или блокировки; повторная модерация и возврат на витрину

| Поле | Содержание |
| --- | --- |
| ID | TC-314 |
| User story | US-3.4 (сценарий 3); US-3.5 (сценарий 4) |
| Требования | FT-3.2 |
| Уровень | L3 |
| Тип | API |
| Приоритет | Высокий |
| Автоматизация | JUnit; JUnit на стенде; Python на стенде |
| Тег в коде | TC-314 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/ProductModerationIT.java`, `services/catalog-service/src/test/java/dgm/catalog/SellerProductIT.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/moderation/domain/ProductDecisionsTest.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/seller/domain/ProductTransitionsTest.java`, `tools/stand-checks/seller_approval_checks.py` |

**Предусловия.** Товар отклонён (TC-312); второй товар заблокирован (TC-313).

**Шаги.**

1. Продавец правит отклонённый товар (`PUT`) и подаёт его.
2. Продавец правит заблокированный товар и подаёт его.
3. Модератор одобряет оба товара.
4. Прочитать карточки на витрине.

**Ожидаемый результат.** Шаг 1, 2: правка сохраняется без смены статуса, подача (T5 и T11) даёт `on_moderation` со свежим сроком, прежние причины очищены. Шаг 3: оба товара `published`. Шаг 4: товары снова на витрине, кэш обновлён.

### TC-315. Права на решения по товарам: только модератор со вторым фактором, остальные получают отказ (ST-02, ST-13)

| Поле | Содержание |
| --- | --- |
| ID | TC-315 |
| User story | US-3.3; US-3.4; US-3.5 |
| Требования | FT-3.2, NFT-3.0 |
| Уровень | L7 |
| Тип | безопасность |
| Приоритет | Высокий |
| Автоматизация | JUnit на стенде; Newman |
| Тег в коде | TC-315 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/ProductModerationIT.java`, `tools/api-tests/scenarios/moderation.json` |

**Предусловия.** Токены: модератор с TOTP, модератор без области `staff.moderation`, покупатель, продавец, оператор поддержки, модератор без `otp`, сессия по SMS.

**Шаги.**

1. Вызвать `listProductModerationQueue`, `getProductForModeration`, `approveProduct`, `rejectProduct`, `blockProduct` без токена.
2. Вызвать те же операции токенами покупателя, продавца, оператора поддержки и модератора с чужой областью.
3. Вызвать те же операции токеном модератора, вошедшего паролем (`amr = pwd`), и токеном сессии по SMS.

**Ожидаемый результат.** Шаг 1: 401 `unauthenticated`. Шаг 2: 403 `forbidden`. Шаг 3: 403 `second-factor-required` для токена без `otp`, 403 для сессии по SMS. Модератор с кодом TOTP получает ответ по существу. Шаги Newman добавляют проверку тела отказа (`application/problem+json`).

### TC-316. Идемпотентность операций над товаром: тот же `Idempotency-Key` возвращает первый ответ и не создаёт второго события

| Поле | Содержание |
| --- | --- |
| ID | TC-316 |
| User story | US-3.1; US-3.2; US-3.3 |
| Требования | FT-3.2, NFT-2.3 |
| Уровень | L3 |
| Тип | интеграционный |
| Приоритет | Высокий |
| Автоматизация | JUnit на стенде |
| Тег в коде | TC-316 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/ProductModerationIT.java`, `services/catalog-service/src/test/java/dgm/catalog/SellerProductIT.java` |

**Предусловия.** Одобренный продавец и модератор; Redis работает.

**Шаги.**

1. Создать товар дважды с одним ключом.
2. Отправить товар на модерацию дважды с одним ключом.
3. Одобрить товар дважды с одним ключом.

**Ожидаемый результат.** Во всех трёх случаях второй ответ равен первому (тот же статус и тело), второй товар не создан, второго события и второй записи аудита нет. Запрос с тем же ключом, пока первый выполняется, получает 409 `request-in-progress`.

### TC-317. Атомарность: сбой записи события или аудита откатывает правку, смену статуса и решение

| Поле | Содержание |
| --- | --- |
| ID | TC-317 |
| User story | US-3.2; US-3.3 |
| Требования | FT-3.2, NFT-2.3 |
| Уровень | L3 |
| Тип | интеграционный |
| Приоритет | Высокий |
| Автоматизация | JUnit; JUnit на стенде |
| Тег в коде | TC-317 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/ProductModerationIT.java`, `services/catalog-service/src/test/java/dgm/catalog/SellerProductIT.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/moderation/service/ProductModerationServiceTest.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/seller/service/SellerProductServiceTest.java` |

**Предусловия.** Хранилище Outbox и аудита, которое можно заставить отказать на записи.

**Шаги.**

1. Создать товар при сбое записи события `product.created`.
2. Изменить и подать товар при сбое записи события.
3. Принять решение модератора при сбое записи аудита.
4. Проверить кэш карточки после каждого сбоя.

**Ожидаемый результат.** Шаги 1 – 3: ответ 5xx, в базе нет ни нового товара, ни новой версии, ни нового статуса, ни события, ни записи аудита. Шаг 4: карточка в кэше остаётся прежней (сброс только после успешного коммита), недоступный Redis операцию не ломает.

### TC-318. Карточка опубликованного товара без токена: поля контракта, `Cache-Control: public, max-age=30`, ETag и 304, неопубликованный 404

| Поле | Содержание |
| --- | --- |
| ID | TC-318 |
| User story | US-3.9 (сценарии 1, 2) |
| Требования | FT-3.1, NFT-1.0 |
| Уровень | L3 |
| Тип | API |
| Приоритет | Высокий |
| Автоматизация | JUnit; JUnit на стенде; Newman; Python на стенде |
| Тег в коде | TC-318 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/CatalogServiceIT.java`, `services/catalog-service/src/test/java/dgm/catalog/ProductModerationIT.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/controller/StorefrontProductControllerTest.java`, `tools/api-tests/scenarios/storefront.json`, `tools/stand-checks/seller_approval_checks.py` |

**Предусловия.** Опубликованный товар, гость без токена.

**Шаги.**

1. `GET /api/v1/products/{productId}`.
2. Повторить запрос с `If-None-Match` равным полученному ETag и с устаревшим ETag.
3. Запросить товар в статусах `draft`, `on_moderation`, `rejected`, `blocked`, `archived`, неизвестный и с неверным идентификатором.

**Ожидаемый результат.** Шаг 1: 200, поля `productId`, `sellerId`, `title`, `description`, `productType`, `platform`, `activationRegions`, `price` (`amount` в копейках, `currency = RUB`), `inStock`, `maxOrderQuantity`; заголовки `Cache-Control: public, max-age=30` и `ETag`. Шаг 2: 304 без тела с тем же ETag; устаревший ETag даёт полную карточку. Шаг 3: неопубликованные и неизвестные товары 404 `not-found`, идентификатор не в нижнем регистре UUID 400.

### TC-319. Кэш карточки в Redis: TTL 30 секунд, сброс после коммита правки, решения и изменения остатка, сбой Redis не ломает чтение

| Поле | Содержание |
| --- | --- |
| ID | TC-319 |
| User story | US-3.9 |
| Требования | FT-3.1, NFT-1.0 |
| Уровень | L3 |
| Тип | интеграционный |
| Приоритет | Средний |
| Автоматизация | JUnit; JUnit на стенде; Python на стенде |
| Тег в коде | TC-319 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/ProductModerationIT.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/controller/StorefrontProductControllerTest.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/moderation/service/ProductModerationServiceTest.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/seller/service/SellerProductServiceTest.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/service/StorefrontProductServiceTest.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/stock/StockViewTest.java`, `tools/stand-checks/seller_approval_checks.py` |

**Предусловия.** Redis работает; опубликованный товар.

**Шаги.**

1. Прочитать карточку дважды.
2. Изменить цену товара, затем принять решение модератора, затем доставить событие `stock.changed`; читать карточку после каждого шага.
3. Прочитать карточку неопубликованного товара.
4. Остановить Redis и прочитать карточку; запустить сервис без Redis.
5. Задать TTL 0 и 3 601 секунду.

**Ожидаемый результат.** Шаг 1: второе чтение из кэша, база не читается, TTL по умолчанию 30 секунд. Шаг 2: после каждого коммита ключ сброшен и следующее чтение видит новые данные; игнорируемое событие остатка кэш не сбрасывает. Шаг 3: 404 и в кэш ничего не кладётся. Шаг 4: карточка приходит из базы, ошибка Redis не фатальна; без настроенного Redis кэш выключен. Шаг 5: настройка отвергается, допустим диапазон от 1 до 3 600 секунд.

### TC-320. Нулевой остаток: карточка видна, `inStock = false`, `maxOrderQuantity = 0`; максимум 10

| Поле | Содержание |
| --- | --- |
| ID | TC-320 |
| User story | US-3.9 (сценарий 3) |
| Требования | FT-4.4, FT-3.1 |
| Уровень | L3 |
| Тип | API |
| Приоритет | Высокий |
| Автоматизация | JUnit; JUnit на стенде |
| Тег в коде | TC-320 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/CatalogServiceIT.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/controller/StorefrontProductControllerTest.java` |

**Предусловия.** Опубликованные товары с остатком 25, 3 и 0 ключей.

**Шаги.**

1. Прочитать карточки трёх товаров.
2. Прочитать список витрины.

**Ожидаемый результат.** Шаг 1: остаток 25 даёт `inStock = true`, `maxOrderQuantity = 10`; остаток 3 даёт `maxOrderQuantity = 3`; остаток 0 карточку не скрывает, но `inStock = false` и `maxOrderQuantity = 0`. Шаг 2: признак `inStock` в списке берётся из той же копии остатка. В браузере товар без ключей показан как «Нет в наличии» (TC-326).

### TC-321. Копия остатка по событию `stock.changed`: свежее событие применяется, старое не откатывает, неизвестный товар игнорируется

| Поле | Содержание |
| --- | --- |
| ID | TC-321 |
| User story | US-4.6; US-3.9 (сценарий 3) |
| Требования | FT-4.4 |
| Уровень | L3 |
| Тип | интеграционный |
| Приоритет | Высокий |
| Автоматизация | JUnit; JUnit на стенде |
| Тег в коде | TC-321 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/ProductModerationIT.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/stock/StockViewTest.java` |

**Предусловия.** Опубликованный товар; обработчик каталога на теме `inventory.events`.

**Шаги.**

1. Доставить `stock.changed` с остатком 7, затем с остатком 0.
2. Доставить событие с меткой времени старше уже применённого и событие с той же меткой.
3. Доставить событие по неизвестному товару.
4. Доставить битое событие.
5. Остановить Redis и доставить событие.

**Ожидаемый результат.** Шаг 1: число свободных ключей хранится как есть, ноль тоже. Шаг 2: старое событие остаток не откатывает, событие с той же меткой применяется. Шаг 3: событие игнорируется без повторов. Шаг 4: сразу в очередь недоставленных. Шаг 5: событие не падает. Кэш карточки сбрасывается после смены остатка и не сбрасывается для игнорируемого события.

### TC-322. Список витрины: новые выше, страницы по курсору без пропусков, `limit` 1 – 100 (по умолчанию 20), ETag и 304

| Поле | Содержание |
| --- | --- |
| ID | TC-322 |
| User story | US-3.9 |
| Требования | FT-3.1, NFT-1.0 |
| Уровень | L3 |
| Тип | API |
| Приоритет | Высокий |
| Автоматизация | JUnit; JUnit на стенде; Newman |
| Тег в коде | TC-322 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/CatalogServiceIT.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/controller/StorefrontControllerTest.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/service/StorefrontServiceTest.java`, `tools/api-tests/scenarios/storefront.json` |

**Предусловия.** Опубликованные товары, в том числе с одинаковым временем публикации; черновики, заблокированные и архивные товары.

**Шаги.**

1. `GET /api/v1/products` без параметров и без токена.
2. Пройти витрину страницами по курсору при `limit = 2`.
3. Повторить запрос с `If-None-Match`; изменить данные и повторить.
4. Запросить `limit = 0` и `limit = 101`.
5. Открыть витрину без товаров.

**Ожидаемый результат.** Шаг 1: 200, `Cache-Control: public, max-age=30`, `ETag`, `page.limit = 20`, только опубликованные товары, самые новые выше, цена в копейках с валютой RUB. Шаг 2: каждый товар ровно один раз, при равном времени публикации порядок стабилен (курсор по паре время и идентификатор), у последней страницы курсора нет. Шаг 3: тот же ETag при тех же данных и 304 без тела, после изменения 200 с новым ETag. Шаг 4: 422 `out_of_range`. Шаг 5: пустая последняя страница.

### TC-323. Поиск по части названия без учёта регистра; неопубликованные товары не находятся, пустой результат не ошибка

| Поле | Содержание |
| --- | --- |
| ID | TC-323 |
| User story | US-3.10 (сценарии 1 – 3) |
| Требования | FT-3.3, NFT-1.1 |
| Уровень | L3 |
| Тип | API |
| Приоритет | Высокий |
| Автоматизация | JUnit; JUnit на стенде; Newman; Python на стенде |
| Тег в коде | TC-323 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/CatalogServiceIT.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/controller/StorefrontControllerTest.java`, `tools/api-tests/scenarios/storefront.json`, `tools/stand-checks/seller_approval_checks.py` |

**Предусловия.** Опубликованные товары «Лунная тропа», «Лунный свет», «Звёздный путь»; черновик и заблокированный товар с тем же словом в названии.

**Шаги.**

1. `GET /api/v1/products?q=лунн`, затем `q=ЛУННАЯ ТРОПА`.
2. `q` со словом, которого нет ни в одном названии.
3. Найти только что опубликованный товар по полному названию через витрину (стенд).

**Ожидаемый результат.** Шаг 1: найдены оба «лунных» товара независимо от регистра, неопубликованных в выдаче нет. Шаг 2: 200 и пустой список. Шаг 3: товар находится, как только публикация закоммичена. В браузере поиск запускается кнопкой «Показать» (TC-326).

### TC-324. Фильтры каталога: тип, платформа, регион, цена вместе и с поиском; неверные значения дают 422 со всеми ошибками

| Поле | Содержание |
| --- | --- |
| ID | TC-324 |
| User story | US-3.11 (сценарии 1 – 4) |
| Требования | FT-3.4, NFT-1.1 |
| Уровень | L3 |
| Тип | API |
| Приоритет | Высокий |
| Автоматизация | JUnit; JUnit на стенде; Newman |
| Тег в коде | TC-324 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/CatalogServiceIT.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/controller/StorefrontControllerTest.java`, `tools/api-tests/scenarios/storefront.json` |

**Предусловия.** Опубликованные товары разных типов, платформ, регионов и цен.

**Шаги.**

1. `GET /api/v1/products?productType=game_key,gift_card&platform=Steam&region=RU&priceFrom=10000&priceTo=200000` (границы в копейках).
2. Тот же запрос вместе с `q`.
3. Фильтры, под которые ничего не подходит.
4. `priceFrom` больше `priceTo`; `region=ru`; `productType=toaster`; неизвестный параметр; курсор с мусором; всё вместе.

**Ожидаемый результат.** Шаг 1: фильтры разбираются (список типов, платформа из 1 – 64 знаков, код региона из двух заглавных букв, границы цены в копейках) и применяются вместе по «и». Шаг 2: поиск и фильтры вместе. Шаг 3: 200 и пустой список. Шаг 4: 422 `validation-failed`; `priceFrom` больше `priceTo` даёт `out_of_range`, строчный регион `invalid_format`, неизвестный тип и параметр `unknown_value`, мусорный курсор `invalid_format`; при нескольких ошибках они приходят в одном ответе.

### TC-325. Внутренняя карточка товара для заказов: любой статус, только `order-service`, другим сервисам закрыто

| Поле | Содержание |
| --- | --- |
| ID | TC-325 |
| User story | US-3.9 |
| Требования | FT-3.1 |
| Уровень | L3 |
| Тип | API |
| Приоритет | Средний |
| Автоматизация | JUnit; JUnit на стенде |
| Тег в коде | TC-325 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/CatalogServiceIT.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/controller/ProductCardControllerTest.java`, `services/catalog-service/src/test/java/dgm/catalog/catalog/service/ProductCardServiceTest.java` |

**Предусловия.** Товары во всех статусах; клиентские сертификаты сервисов стенда.

**Шаги.**

1. `GET /internal/v1/products/{productId}` с сертификатом `order-service`.
2. Тот же запрос с сертификатом другого сервиса и без клиентского сертификата.
3. Запросить неизвестный товар и товар с неверным идентификатором.

**Ожидаемый результат.** Шаг 1: 200 для товара в любом статусе (заказ должен видеть и снятый товар), `Cache-Control: no-store`. Шаг 2: 403 для другого сервиса, соединение без сертификата отклоняется; через шлюз маршрут недоступен. Шаг 3: 404 и 400.

### TC-326. Каталог и карточка товара в браузере: поиск, фильтры, страницы, состояния, «Нет в наличии»

| Поле | Содержание |
| --- | --- |
| ID | TC-326 |
| User story | US-3.9; US-3.10; US-3.11 |
| Требования | FT-3.3, FT-3.4 |
| Уровень | L5 |
| Тип | UI |
| Приоритет | Высокий |
| Автоматизация | Vitest; Python на стенде |
| Тег в коде | TC-326 |
| Где в коде | `frontend/web-app/src/screens/storefront/ProductListPage.test.tsx`, `frontend/web-app/src/screens/storefront/ProductPage.test.tsx`, `frontend/web-app/src/screens/storefront/filters.test.ts`, `frontend/web-app/src/screens/storefront/money.test.ts`, `tools/stand-checks/ui_smoke.py` |

**Предусловия.** Vitest: тестовый сервер API. Дымовой сценарий: стенд `full`, гость в Chromium, опубликованный товар без ключей.

**Шаги.**

1. Открыть `/products`, ввести часть названия и нажать «Показать».
2. Применить фильтры и сбросить их; ввести неверный регион и обратную границу цены.
3. Нажать «Показать ещё» на длинном списке; открыть пустой результат и сбой сети.
4. Открыть карточку товара и страницу товара, которого нет.

**Ожидаемый результат.** Шаг 1: запрос уходит по кнопке, а не на каждую букву; параметры лежат в адресе страницы, поэтому «назад» и ссылка работают. Шаг 2: ошибки полей показываются до запроса. Шаг 3: список достраивается по курсору, пустой результат и ошибка показаны с повтором. Шаг 4: карточка показывает название, цену «1 499 ₽» и «Нет в наличии» при нулевом остатке, для отсутствующего товара «Товар не найден».

### TC-327. Кабинет продавца в браузере: список товаров, форма, подача, архив, ошибки сервера у полей

| Поле | Содержание |
| --- | --- |
| ID | TC-327 |
| User story | US-3.1; US-3.2; US-3.6; US-3.8 |
| Требования | FT-3.0, FT-3.2 |
| Уровень | L5 |
| Тип | UI |
| Приоритет | Средний |
| Автоматизация | Vitest |
| Тег в коде | TC-327 |
| Где в коде | `frontend/web-app/src/screens/seller/SellerProductPage.test.tsx`, `frontend/web-app/src/screens/seller/SellerProductsPage.test.tsx`, `frontend/web-app/src/screens/seller/product.test.ts` |

**Предусловия.** Vitest: тестовый сервер API; токен продавца в памяти страницы.

**Шаги.**

1. Открыть `/seller/products`, отфильтровать по статусам, пройти страницы.
2. Создать товар: пустая форма, ошибки полей, успешное сохранение.
3. Изменить опубликованный товар: цену, затем название.
4. Подать, архивировать и восстановить товар; получить 409, 412 и 428 от сервера.

**Ожидаемый результат.** Деньги вводятся в рублях и уходят в копейках; ошибки сервера показываются у полей по коду проблемы; правка названия опубликованного товара сопровождается предупреждением, что товар уйдёт на модерацию. Версия и ETag обновляются после каждого сохранения, устаревшая версия предлагает перечитать товар. Действия недоступны в закрытых статусах. Раздел открыт только роли `seller` со вторым фактором.

### TC-328. Рабочее место модератора: очередь и карточка товаров, «Опубликовать», «Отклонить» и «Заблокировать» с подтверждением

| Поле | Содержание |
| --- | --- |
| ID | TC-328 |
| User story | US-3.3; US-3.4; US-3.5 |
| Требования | FT-3.2, NFT-3.0 |
| Уровень | L5 |
| Тип | UI |
| Приоритет | Высокий |
| Автоматизация | Vitest; Python на стенде |
| Тег в коде | TC-328 |
| Где в коде | `frontend/web-app/src/screens/staff/moderation/ProductCardPage.test.tsx`, `frontend/web-app/src/screens/staff/moderation/QueuePages.test.tsx`, `frontend/web-app/src/screens/staff/moderation/decision.test.ts`, `tools/stand-checks/ui_smoke.py` |

**Предусловия.** Vitest: тестовый сервер API. Дымовой сценарий: стенд `full`, moderator-1 в Chromium, два товара на модерации.

**Шаги.**

1. Войти через Keycloak (пароль и код TOTP) и открыть «Товары на модерации».
2. Открыть карточку, нажать «Опубликовать», подтвердить.
3. Открыть второй товар, нажать «Отклонить», вписать причину, подтвердить.
4. Открыть опубликованный товар и нажать «Заблокировать» без причины и с причиной.
5. Получить от сервера 409 «уже решено».

**Ожидаемый результат.** Шаг 2: сообщение «Товар опубликован.», у карточки появляется кнопка «Заблокировать», по API статус `published`. Шаг 3: сообщение «Товар отклонён.», по API причина видна продавцу. Шаг 4: без причины решение не отправляется, подтверждение дважды подряд даёт один запрос. Шаг 5: карточка обновляется и показывает статус. Решения идут с `Idempotency-Key`, при сбое сети повтор уходит с тем же ключом.

### TC-329. Товар от заявки до витрины на стенде: подача, решения модератора, карточка с кэшем, поиск, блокировка и возврат

| Поле | Содержание |
| --- | --- |
| ID | TC-329 |
| User story | US-3.3; US-3.5; US-3.9 |
| Требования | FT-3.1, FT-3.2, FT-3.3 |
| Уровень | L5 |
| Тип | API |
| Приоритет | Высокий |
| Автоматизация | Python на стенде |
| Тег в коде | TC-329 |
| Где в коде | `tools/stand-checks/seller_approval_checks.py` |

**Предусловия.** Стенд `full` (шлюз, Keycloak, каталог, Kafka, Redis), продавец, прошедший заявку и вход с TOTP, moderator-1.

**Шаги.**

1. Продавец заводит товар и подаёт его; черновика нет ни на витрине, ни у модератора.
2. Модератор открывает очередь и карточку, отклоняет без причины (422), затем с причиной; продавец видит причину и подаёт снова.
3. Модератор одобряет; повторное одобрение даёт 409.
4. Гость читает карточку, повторяет запрос с `If-None-Match`, ищет товар по части названия.
5. Модератор блокирует товар; продавец исправляет и подаёт снова; модератор одобряет.

**Ожидаемый результат.** Шаг 3: карточка на витрине без токена, `inStock = false` и `maxOrderQuantity = 0`, `ETag` и публичный `Cache-Control`. Шаг 4: 304 без тела, поиск находит товар. Шаг 5: после блокировки витрина отвечает 404 в течение 20 секунд (кэш сброшен решением, а не истёк по TTL), продавец видит причину блокировки, после повторной модерации товар снова на витрине. Сценарий входит в `make seller-approval-check` и служит контрольной точкой К2.

## 4. Тесты безопасности среза

| Тест | Что закрыто в срезе 1 | Чем |
| --- | --- | --- |
| ST-02 | Операции кабинета товаров (`OP-20`, `OP-21`, `OP-25`, `OP-26`), очереди и решений по товарам (`OP-22` – `OP-24`), витрины (`OP-28`) | TC-303, TC-315, `moderation.json`, `storefront.json`, матрица Newman, `SellerProductIT`, `ProductModerationIT` |
| ST-13 | Решения по товарам принимает модератор со вторым фактором, токен без `otp` получает 403 `second-factor-required` | TC-315, TC-328 |

## 5. Что не проверяется в срезе 1

| Что | Почему | Куда перенесено |
| --- | --- | --- |
| Письма продавцу о решениях по товару (US-3.7) | FT-11.4 входит в срез 3 вместе с сервисом уведомлений для товаров | Срез 3 |
| Блокировка товаров при блокировке продавца (переходы T9, T10) | Блокировка продавца относится к R2 | Ф5 |
| Нагрузка на витрину и поиск (NFT-1.0, NFT-1.1) | Нагрузочный ориентир снимается отдельным шагом среза, зачёт идёт на сервере | Шаг 21, затем сервер |
| Оформление заказа на товар с остатком | `createOrder` появляется в срезе 2 | Срез 2 |
