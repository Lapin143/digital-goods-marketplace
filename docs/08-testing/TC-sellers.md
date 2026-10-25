# Тест-кейсы: продавцы и модерация заявок (TC-201 – TC-222)

| Поле | Содержание |
| --- | --- |
| Документ | Тест-кейсы эпика 2 «Продавцы и модерация заявок», область TC-201 – TC-299 ([стратегия](test-strategy.md), раздел 13). Заполнено TC-201 – TC-222, остальные номера свободны |
| Источники | [US-02](../02-requirements/US-02-sellers.md) (US-2.1 – US-2.5), [требования v1.6](../02-requirements/requirements_v1.6.md) (FT-1.3, FT-2.0 – FT-2.2, FT-2.4, NFT-3.0), [модель угроз](../05-architecture/threat-model.md) (ST-02, ST-12, ST-13), [ADR-024](../05-architecture/adr/ADR-024-object-storage.md), [ADR-025](../05-architecture/adr/ADR-025-identity-provisioning.md) |
| Срез | Ф4, срез 1: анкета, документы, подача, очередь и решения модератора, роль продавца с обязательным вторым фактором, письма заявителю. US-2.6 (блокировка продавца) относится к R2 и не входит |
| Состояние | Все тест-кейсы автоматизированы, ручных нет. Стенд: `make sellers-check`, `make seller-approval-check`, `make ui-smoke`, `make e2e` (набор `full`). Сверка с кодом: `tools/docs-checks/check_testcases.py` |

## 1. Как читать

Оформление и поля те же, что в [TC-accounts.md](TC-accounts.md) (раздел 1) и [шаблоне](_template-test-case.md). Тест-кейсы этой области проверяются на шести уровнях:

- модульные тесты (`*Test.java`, задание `build`): правила анкеты, статусы и переходы SM-05, настройки сроков, службы заявки, документов и модерации на подменном хранилище, контроллеры на `MockMvc`, обработчики и письма платформы;
- интеграционные тесты на стенде (`*IT.java`, задание `kit`): `SellerOnboardingIT` и `SellerModerationIT` в каталоге, `PlatformServiceIT` в платформе; настоящие PostgreSQL, Kafka, Redis и объектное хранилище, запросы идут по HTTPS через клиент шлюза;
- Newman (`make e2e`): сценарии `documents.json` и `moderation.json` и матрица прав ST-02;
- скрипты стенда (`tools/stand-checks`): `sellers_checks.py`, `seller_approval_checks.py` (задание `e2e`) и `keycloak_checks.py` (задание `keycloak`);
- экраны (Vitest, задание `web`): анкета, документы и рабочее место модератора на тестовом сервере API;
- дымовой сценарий в браузере (`ui_smoke.py`, задание `e2e`): модератор входит с кодом TOTP и решает заявку щелчками.

Сроки в тестах стенда настоящие, поэтому проверки, которым нужны сутки или трое суток (пауза после отказа, срок проверки), выполняются на уровне JUnit с управляемыми часами.

## 2. Сводка

| TC | Название | История | Уровень | Чем проверяется |
| --- | --- | --- | --- | --- |
| TC-201 | Черновик анкеты: частичное сохранение, ETag, номер версии | US-2.1 | L3 | JUnit, JUnit на стенде, `sellers_checks.py` |
| TC-202 | Формат анкеты и ошибки с указателями полей | US-2.1 | L3 | JUnit, JUnit на стенде, `sellers_checks.py` |
| TC-203 | Подача заявки: очередь и срок 3 суток | US-2.1 | L3 | JUnit, JUnit на стенде, `sellers_checks.py` |
| TC-204 | Неполная анкета или нет документа: 422 и целый черновик | US-2.1 | L3 | JUnit, JUnit на стенде, `sellers_checks.py` |
| TC-205 | Заявка на проверке или решена: изменения закрыты | US-2.1 | L3 | JUnit, JUnit на стенде, `sellers_checks.py` |
| TC-206 | Документы: тип по содержимому, 2 МБ, 10 файлов (ST-12) | US-2.1 | L7 | JUnit, JUnit на стенде, Newman, `sellers_checks.py` |
| TC-207 | Хранение документов: ссылка на 5 минут, удаление, сбои | US-2.1 | L3 | JUnit, JUnit на стенде, Newman, `sellers_checks.py` |
| TC-208 | Очередь заявок: порядок, курсор, срок, просрочка | US-2.2 | L3 | JUnit, JUnit на стенде, `seller_approval_checks.py` |
| TC-209 | Просрочка заявки помечается один раз | US-2.2 | L3 | JUnit, JUnit на стенде |
| TC-210 | Карточка заявки: анкета, документы, просмотр без изменений | US-2.2 | L3 | JUnit, JUnit на стенде, `seller_approval_checks.py` |
| TC-211 | Одобрение: статус, событие и аудит вместе | US-2.2 | L3 | JUnit, JUnit на стенде, `seller_approval_checks.py` |
| TC-212 | Роль «Продавец» по событию: seller_id и обязательный TOTP | US-2.2 | L3 | JUnit, JUnit на стенде, `seller_approval_checks.py` |
| TC-213 | Повторное решение по заявке: 409 и ничего не записывается | US-2.2, US-2.3 | L3 | JUnit, JUnit на стенде, `seller_approval_checks.py` |
| TC-214 | Отказ с причиной: пауза 24 часа, причина видна заявителю | US-2.3, US-2.5 | L3 | JUnit, JUnit на стенде, `seller_approval_checks.py` |
| TC-215 | Возврат на доработку: комментарий, без паузы, срок заново | US-2.3, US-2.1 | L3 | JUnit, JUnit на стенде, `seller_approval_checks.py` |
| TC-216 | Повторная заявка после отказа: пауза 24 часа | US-2.5 | L3 | JUnit, JUnit на стенде, `seller_approval_checks.py` |
| TC-217 | Права на решения по заявкам: ST-02 и ST-13 | US-2.2, US-2.3 | L7 | JUnit на стенде, Newman |
| TC-218 | Кабинет только со вторым фактором (ST-13, INV-37) | US-2.2, US-1.5 | L7 | `keycloak_checks.py`, `seller_approval_checks.py` |
| TC-219 | Письмо заявителю о решении по заявке | US-2.4 | L3 | JUnit, JUnit на стенде |
| TC-220 | Отправка писем: повторы, отказ провайдера, нет получателя | US-2.4 | L3 | JUnit, JUnit на стенде |
| TC-221 | Экран заявки продавца в браузере | US-2.1, US-2.5 | L5 | Vitest |
| TC-222 | Рабочее место модератора: заявки и решения в браузере | US-2.2, US-2.3 | L5 | Vitest, `ui_smoke.py` |

## 3. Тест-кейсы

### TC-201. Черновик анкеты: частичное сохранение, ETag, `If-Match` и номер версии

| Поле | Содержание |
| --- | --- |
| ID | TC-201 |
| User story | US-2.1 (сценарий 1) |
| Требования | FT-2.0 |
| Уровень | L3 |
| Тип | API |
| Приоритет | Высокий |
| Автоматизация | JUnit; JUnit на стенде; Python на стенде |
| Тег в коде | TC-201 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/SellerOnboardingIT.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/controller/SellerApplicationControllerTest.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/domain/SellerFormTest.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/domain/SellerProfileTest.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/service/SellerApplicationServiceTest.java`, `tools/stand-checks/sellers_checks.py` |

**Предусловия.** Заявитель: зарегистрированный пользователь с ролью `buyer` (вход `dgm-web`, e-mail подтверждён), заявки у него нет.

**Шаги.**

1. `POST /api/v1/seller-applications` с неполной анкетой (только тип и наименование).
2. `GET /api/v1/seller-applications/current`.
3. `PUT /api/v1/seller-applications/current` без `If-Match`, с неверным ETag, с ETag прошлой версии и с текущим ETag; в теле нет одного из прежних полей.
4. Загрузить документ и снова прочитать заявку.
5. Повторить создание под тем же ключом идемпотентности; создать заявку от имени второго пользователя и прочитать «текущую» заявку первого.

**Ожидаемый результат.** Шаг 1: 201, статус `draft`, версия 1, заголовки `ETag` и `Location`, заявки нет в очереди модератора. Шаг 2: 200 с той же анкетой и тем же ETag, до первой заявки 404. Шаг 3: без `If-Match` 428, ETag неверной формы (слабый, нечисловой, список, `*`) 400, прошлая версия 412 и ничего не меняется, текущая 200 с новым ETag и версией 2; поле, которого нет в новом теле, очищается. Шаг 4: ETag анкеты не меняется (документы не часть версии формы). Шаг 5: повтор отдаёт первый ответ и новую запись не создаёт, пользователь видит только свою заявку.

### TC-202. Формат анкеты: длина текстов в кодовых точках, БИК из 9 цифр, счёт из 20 цифр, ошибки с указателями полей

| Поле | Содержание |
| --- | --- |
| ID | TC-202 |
| User story | US-2.1 (сценарии 1, 3) |
| Требования | FT-2.0 |
| Уровень | L3 |
| Тип | API |
| Приоритет | Средний |
| Автоматизация | JUnit; JUnit на стенде; Python на стенде |
| Тег в коде | TC-202 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/SellerOnboardingIT.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/controller/SellerApplicationControllerTest.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/domain/SellerFormTest.java`, `tools/stand-checks/sellers_checks.py` |

**Предусловия.** Как в TC-201.

**Шаги.**

1. Создать заявку с БИК из 8 и из 10 цифр, с буквой и с пробелами, со счётом из 19 и из 21 цифры и со счётом с буквой.
2. Создать заявку с пустыми строками в наименовании, получателе, банке и описании ассортимента; с наименованием из 201 символа; с описанием из 2 001 символа; с наименованием из 200 эмодзи; с описанием ровно в 2 000 символов.
3. Создать заявку с неизвестным полем (в том числе внутри `payoutDetails`), полем неверного типа и неизвестным типом продавца.
4. Отправить тело не JSON, JSON-массив, обрезанный JSON и запрос с `Content-Type: text/plain`.

**Ожидаемый результат.** Шаг 1: 422, у БИК и счёта ошибка `invalid_format` с указателями `/payoutDetails/bik` и `/payoutDetails/accountNumber`. Шаг 2: пустая строка это `too_short`, а не «поле отсутствует», 201 символ даёт `too_long`; длина считается в кодовых точках (200 эмодзи принимаются, хотя это 400 единиц UTF-16), описание до 2 000 символов принимается. Шаг 3: неизвестные поля названы указателями (`unknown_value`), неверный тип даёт `invalid_format`, неизвестный тип продавца называет допустимые значения. Шаг 4: 400 для тела не из JSON-объекта, 415 для другого `Content-Type`. Все ошибки собираются в один ответ 422, ничего не сохраняется. Пробелы по краям текста обрезаются, `null` равен отсутствующему полю.

### TC-203. Подача заявки: переход в очередь модератора, срок рассмотрения 3 суток

| Поле | Содержание |
| --- | --- |
| ID | TC-203 |
| User story | US-2.1 (сценарий 2) |
| Требования | FT-2.0, NFT-5.0 |
| Уровень | L3 |
| Тип | API |
| Приоритет | Высокий |
| Автоматизация | JUnit; JUnit на стенде; Python на стенде |
| Тег в коде | TC-203 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/SellerOnboardingIT.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/controller/SellerApplicationControllerTest.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/domain/SellerProfileTest.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/domain/SellerSettingsTest.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/service/SellerApplicationServiceTest.java`, `tools/stand-checks/sellers_checks.py` |

**Предусловия.** Заявитель с полностью заполненной анкетой и одним загруженным документом (TC-201, TC-206).

**Шаги.**

1. `POST /api/v1/seller-applications/current/submit`.
2. Открыть очередь модератора (`GET /api/v1/staff/seller-applications`).
3. Прочитать заявку заявителем.
4. Повторить подачу.

**Ожидаемый результат.** Шаг 1: 200, статус `on_review`, `reviewDeadlineAt` ровно на 3 суток позже `submittedAt`. На стенде с быстрыми часами (параметр платформы) срок равен 10 секундам. Шаг 2: заявка в очереди, `documentsCount = 1`, `overdue = false`. Шаг 3: заявитель видит статус «на проверке». Срок рассмотрения берётся из параметра платформы, а не из константы (границы параметра от 1 секунды до 14 суток, проверяет `SellerSettingsTest`).

### TC-204. Подача неполной анкеты или без документа: 422 называет всё недостающее, черновик не меняется

| Поле | Содержание |
| --- | --- |
| ID | TC-204 |
| User story | US-2.1 (сценарий 3) |
| Требования | FT-2.0 |
| Уровень | L3 |
| Тип | API |
| Приоритет | Высокий |
| Автоматизация | JUnit; JUnit на стенде; Python на стенде |
| Тег в коде | TC-204 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/SellerOnboardingIT.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/controller/SellerApplicationControllerTest.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/domain/SellerProfileTest.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/service/SellerApplicationServiceTest.java`, `tools/stand-checks/sellers_checks.py` |

**Предусловия.** Заявитель с пустым черновиком (TC-201).

**Шаги.**

1. Подать заявку с пустой анкетой.
2. Заполнить анкету, документ не загружать, подать.
3. Вернуть заявку на доработку (TC-215), стереть документ и подать снова.

**Ожидаемый результат.** Шаг 1: 422 с перечнем всех недостающих полей (тип, наименование, получатель, банк, БИК, счёт, описание ассортимента и документ), по одному указателю на поле. Шаг 2: 422 с указателем недостающей части (документы). Шаг 3: для возвращённой заявки документ нужен так же. Во всех случаях статус остаётся прежним, версия не растёт, в очередь заявка не попадает.

### TC-205. Заявка на проверке или решена: вторая не создаётся, анкета и документы не меняются

| Поле | Содержание |
| --- | --- |
| ID | TC-205 |
| User story | US-2.1 (сценарий 4) |
| Требования | FT-2.0 |
| Уровень | L3 |
| Тип | API |
| Приоритет | Высокий |
| Автоматизация | JUnit; JUnit на стенде; Python на стенде |
| Тег в коде | TC-205 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/SellerOnboardingIT.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/controller/SellerApplicationControllerTest.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/controller/SellerDocumentControllerTest.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/domain/SellerProfileTest.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/domain/SellerStatusTest.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/service/SellerApplicationServiceTest.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/service/SellerDocumentServiceTest.java`, `tools/stand-checks/sellers_checks.py` |

**Предусловия.** Заявка в статусе `on_review` (TC-203); для второй половины результата также `approved`.

**Шаги.**

1. `POST /api/v1/seller-applications` ещё раз.
2. `PUT /api/v1/seller-applications/current` с верной версией.
3. `POST …/current/documents` и `DELETE …/current/documents/{documentId}`.
4. `POST …/current/submit` ещё раз.
5. Создание заявки, когда запись того же пользователя успела появиться между проверкой и вставкой.

**Ожидаемый результат.** Шаги 1, 4: 409 `state-conflict` с текущим статусом, вторая заявка не создаётся. Шаги 2, 3: 409, анкета и документы не меняются (формы и документы можно править только в статусах `draft`, `returned` и `rejected`, это проверяют матрица переходов SM-05 и `SellerStatusTest`). Шаг 5: проигравший получает 409 `state-conflict` с текущим статусом, а не 500 (уникальный индекс профиля, проверяется подменой хранилища).

### TC-206. Документы заявки: тип по содержимому, размер до 2 МБ, не более 10 файлов (ST-12)

| Поле | Содержание |
| --- | --- |
| ID | TC-206 |
| User story | US-2.1 (сценарий 6) |
| Требования | FT-2.0, NFT-5.0 |
| Уровень | L7 |
| Тип | безопасность |
| Приоритет | Высокий |
| Автоматизация | JUnit; JUnit на стенде; Newman; Python на стенде |
| Тег в коде | TC-206 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/SellerOnboardingIT.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/controller/SellerDocumentControllerTest.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/domain/DocumentTypeTest.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/service/SellerDocumentServiceTest.java`, `tools/api-tests/scenarios/documents.json`, `tools/stand-checks/sellers_checks.py` |

**Предусловия.** Заявитель с черновиком (TC-201). Файлы: настоящие PDF, JPEG, PNG; текст с именем `scan.pdf`; HTML с именем `scan.png`; SVG с именем `scan.jpg`; пустой файл.

**Шаги.**

1. `POST /api/v1/seller-applications/current/documents` (форма `multipart/form-data` с одной частью `file`) с каждым из настоящих файлов.
2. Загрузить текст под видом PDF, HTML под видом PNG, SVG под видом JPEG и пустой файл.
3. Загрузить файл ровно в 2 МБ и файл на 1 байт больше.
4. Загрузить десять документов, затем одиннадцатый; удалить один и загрузить снова.
5. Отправить запрос не формой, форму без части `file`, с двумя файлами, с лишним полем, с именем файла длиннее 255 символов.

**Ожидаемый результат.** Шаг 1: 201, тип в ответе определён по началу файла (`application/pdf`, `image/jpeg`, `image/png`), не по имени и не по заявленному типу. Шаг 2: 415 `unsupported-media-type` (ответ `application/problem+json`) для первых трёх, 422 для пустого; в хранилище ничего не записано. Шаг 3: 2 МБ принимаются, на байт больше 413 до записи в хранилище. Шаг 4: одиннадцатый отклонён 422 и ничего не пишет, после удаления место освобождается. Шаг 5: 415, 422 или 400 по кодам контракта, ранее приложенные документы сохраняются.

### TC-207. Хранение и выдача документов: файл без изменений, ссылка на 5 минут через `/files`, удаление, сбой хранилища

| Поле | Содержание |
| --- | --- |
| ID | TC-207 |
| User story | US-2.1 (сценарии 2, 6) |
| Требования | FT-2.0, NFT-5.0 |
| Уровень | L3 |
| Тип | API |
| Приоритет | Высокий |
| Автоматизация | JUnit; JUnit на стенде; Newman; Python на стенде |
| Тег в коде | TC-207 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/SellerOnboardingIT.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/controller/SellerDocumentControllerTest.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/domain/SellerSettingsTest.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/service/SellerDocumentServiceTest.java`, `tools/api-tests/scenarios/documents.json`, `tools/stand-checks/sellers_checks.py` |

**Предусловия.** Заявка с загруженным PDF (TC-206).

**Шаги.**

1. `GET /api/v1/seller-applications/current/documents/{documentId}/link`.
2. Открыть адрес из ответа без токена сразу и после срока ссылки.
3. Запросить ссылку на документ другого пользователя и с неверным идентификатором.
4. Удалить документ, повторить удаление, запросить ссылку на удалённый.
5. Сбой хранилища при загрузке и при удалении; сбой записи в базе после записи файла.

**Ожидаемый результат.** Шаг 1: 200, `url` ведёт на `/files/seller-documents/…` шлюза, срок равен 5 минутам (параметр платформы, граница от 1 до 15 минут). Шаг 2: файл отдаётся без изменений (байт в байт) с заголовками «только скачать»; после срока ссылка не работает. Шаг 3: 404, ответ не выдаёт, существует ли документ; неверный идентификатор 400. Шаг 4: 204, повтор 404, ссылка 404; объект удалён из хранилища. Шаг 5: загрузка при недоступном хранилище 503 без записи в базе, сбой базы после записи файла удаляет файл, сбой удаления сироты не скрывает исходную ошибку. В ключе объекта нет имени файла, имя теряет путь и управляющие символы.

### TC-208. Очередь заявок модератора: только «на проверке», старые выше, страницы по курсору, срок и просрочка

| Поле | Содержание |
| --- | --- |
| ID | TC-208 |
| User story | US-2.2 |
| Требования | FT-2.1 |
| Уровень | L3 |
| Тип | API |
| Приоритет | Высокий |
| Автоматизация | JUnit; JUnit на стенде; Python на стенде |
| Тег в коде | TC-208 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/SellerModerationIT.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/controller/SellerModerationControllerTest.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/domain/SellerProfileTest.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/service/SellerModerationServiceTest.java`, `tools/stand-checks/seller_approval_checks.py` |

**Предусловия.** Заявки в статусах `draft`, `on_review`, `approved`, `rejected`, `returned`; несколько заявок, поданных в одну и ту же миллисекунду. Токен: moderator-1 (роль `moderator`, вход `dgm-staff` с кодом TOTP, `amr = pwd, otp`).

**Шаги.**

1. `GET /api/v1/staff/seller-applications` без параметров.
2. Пройти очередь страницами по курсору при `limit = 2`.
3. Передать `limit = 0`, `limit = 101`, `limit = abc`, курсор не из этой очереди и неизвестный параметр.
4. Открыть очередь, когда заявок на проверке нет.

**Ожидаемый результат.** Шаг 1: 200, `Cache-Control: no-store`, в списке только `on_review`, самые старые выше, у каждой `sellerType`, `documentsCount`, `submittedAt`, `reviewDeadlineAt`, `overdue`; черновиков нет. Шаг 2: каждая заявка встречается ровно один раз, без пропусков и повторов, порядок при равном времени подачи стабилен (по идентификатору). Шаг 3: 422 со всеми ошибками сразу (`limit:out_of_range`, `cursor:invalid_format`, `color:unknown_value`), допустимо от 1 до 100. Шаг 4: пустая страница, не ошибка.

### TC-209. Просрочка: задание помечает заявку один раз, повторная подача считается заново

| Поле | Содержание |
| --- | --- |
| ID | TC-209 |
| User story | US-2.2 |
| Требования | FT-2.1 |
| Уровень | L3 |
| Тип | интеграционный |
| Приоритет | Средний |
| Автоматизация | JUnit; JUnit на стенде |
| Тег в коде | TC-209 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/SellerModerationIT.java`, `services/catalog-service/src/test/java/dgm/catalog/SellerOnboardingIT.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/domain/SellerProfileTest.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/service/ModerationDeadlineJobTest.java` |

**Предусловия.** Заявка `on_review` со сроком, который можно сдвинуть (часы теста или быстрый срок стенда).

**Шаги.**

1. Проверить очередь до наступления срока.
2. Дождаться момента срока и запустить проход задания.
3. Повторить проход задания.
4. Решить просроченную заявку; вернуть другую на доработку и подать снова.

**Ожидаемый результат.** Шаг 1: `overdue = false`, задание ничего не помечает. Шаг 2: в момент срока (граница включена) заявка помечается, `overdue = true`, помечаются сразу все просроченные, обрабатываются только заявки `on_review`. Шаг 3: вторая отметка не ставится. Шаг 4: просроченную заявку модератор решает как обычную, второе решение невозможно (TC-213); после возврата и новой подачи срок считается заново и заявка снова может быть помечена.

### TC-210. Карточка заявки: анкета, реквизиты, ссылки на документы на 5 минут, просмотр ничего не меняет

| Поле | Содержание |
| --- | --- |
| ID | TC-210 |
| User story | US-2.2 |
| Требования | FT-2.1 |
| Уровень | L3 |
| Тип | API |
| Приоритет | Высокий |
| Автоматизация | JUnit; JUnit на стенде; Python на стенде |
| Тег в коде | TC-210 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/SellerModerationIT.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/controller/SellerModerationControllerTest.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/service/SellerModerationServiceTest.java`, `tools/stand-checks/seller_approval_checks.py` |

**Предусловия.** Заявка `on_review` с одним документом, токен модератора (TC-208).

**Шаги.**

1. `GET /api/v1/staff/seller-applications/{sellerId}`.
2. Открыть адрес документа из карточки.
3. Открыть карточку решённой заявки; открыть карточку черновика, неизвестной заявки и с неверным идентификатором.
4. Сравнить заявку до и после просмотра.

**Ожидаемый результат.** Шаг 1: 200, `Cache-Control: no-store`, анкета, `userId` заявителя, реквизиты выплат и список документов, у каждого `link.url` на `/files/seller-documents/…` и `link.expiresAt` (5 минут). Шаг 2: файл открывается без токена и совпадает с загруженным. Шаг 3: решённая заявка остаётся доступной для истории, черновик и неизвестная заявка 404, неверный идентификатор 400. Шаг 4: открытие карточки статус, версию и документы не меняет и решений не добавляет; выданные ссылки записываются в журнал аудита отдельно (TC-811, Р-166 среза 3). В браузере то же видно на экране карточки (TC-222).

### TC-211. Одобрение: статус, событие `seller.approved` и запись аудита в одной транзакции

| Поле | Содержание |
| --- | --- |
| ID | TC-211 |
| User story | US-2.2 (сценарии 1, 3) |
| Требования | FT-2.1, FT-11.2 |
| Уровень | L3 |
| Тип | API |
| Приоритет | Высокий |
| Автоматизация | JUnit; JUnit на стенде; Python на стенде |
| Тег в коде | TC-211 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/SellerModerationIT.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/controller/SellerModerationControllerTest.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/domain/SellerProfileTest.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/domain/SellerStatusTest.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/service/SellerModerationServiceTest.java`, `tools/stand-checks/seller_approval_checks.py` |

**Предусловия.** Заявка `on_review`, токен модератора. Роль заявителя в Keycloak пока `buyer`.

**Шаги.**

1. `POST /api/v1/staff/seller-applications/{sellerId}/approve` с `Idempotency-Key`.
2. Повторить запрос с тем же ключом.
3. Читать статус заявки и роль заявителя до и сразу после решения.
4. Вызвать запись аудита с искусственным сбоем.

**Ожидаемый результат.** Шаг 1: 200, статус `approved`, в одной транзакции записаны статус, событие `seller.approved` в Outbox (с идентификаторами заявки, заявителя и модератора) и запись аудита с адресом клиента (первый адрес заголовка `X-Forwarded-For` шлюза, без него адрес соединения). Шаг 2: тот же ответ, события и аудита по-прежнему по одному. Шаг 3: до решения роль не выдаётся, после решения роль меняет платформа по событию (TC-212), а не каталог. Шаг 4: при сбое записи аудита откатываются и статус, и событие. В браузере решение принимается кнопкой «Одобрить» с подтверждением (TC-222).

### TC-212. Роль «Продавец» по событию `seller.approved`: роль вместо `buyer`, `seller_id`, обязательный TOTP

| Поле | Содержание |
| --- | --- |
| ID | TC-212 |
| User story | US-2.2 (сценарий 1) |
| Требования | FT-1.3, FT-2.1 |
| Уровень | L3 |
| Тип | интеграционный |
| Приоритет | Высокий |
| Автоматизация | JUnit; JUnit на стенде; Python на стенде |
| Тег в коде | TC-212 |
| Где в коде | `services/platform-service/src/test/java/dgm/platform/PlatformServiceIT.java`, `services/platform-service/src/test/java/dgm/platform/identity/sellerrole/SellerRoleHandlerTest.java`, `tools/stand-checks/seller_approval_checks.py` |

**Предусловия.** Одобренная заявка (TC-211); запись заявителя на платформе создана заранее, обработчик события работает.

**Шаги.**

1. Событие `seller.approved` доходит до `platform-service`.
2. Прочитать роли и атрибуты заявителя в Keycloak и запись платформы.
3. Доставить то же событие повторно, затем событие с битыми данными и с лишними полями.

**Ожидаемый результат.** Шаг 2: в Keycloak роль `seller` вместо `buyer`, атрибут `seller_id` равен идентификатору профиля, в обязательных действиях `CONFIGURE_TOTP`; на платформе роль `seller`, одно событие `user.role-assigned` и одна запись аудита. Шаг 3: повтор ничего не добавляет, лишние поля игнорируются, битые данные уходят в очередь недоставленных без значения в сообщении. Обработчик объявлен на событие темы каталога и модуль идентификации.

### TC-213. Повторное и конкурирующее решение: 409 `state-conflict` с текущим статусом, ничего не записывается

| Поле | Содержание |
| --- | --- |
| ID | TC-213 |
| User story | US-2.2 (сценарий 2); US-2.3 |
| Требования | FT-2.1 |
| Уровень | L3 |
| Тип | API |
| Приоритет | Высокий |
| Автоматизация | JUnit; JUnit на стенде; Python на стенде |
| Тег в коде | TC-213 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/SellerModerationIT.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/controller/SellerModerationControllerTest.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/service/SellerModerationServiceTest.java`, `tools/stand-checks/seller_approval_checks.py` |

**Предусловия.** Заявка уже решена (одобрена, отклонена или возвращена); для сценария гонки две заявки в `on_review`.

**Шаги.**

1. Повторить то же решение под новым ключом идемпотентности.
2. Принять другое решение по той же заявке.
3. Принять решение по черновику, по неизвестной заявке и по заявке с неверным идентификатором.
4. Два модератора одновременно решают одну заявку.

**Ожидаемый результат.** Шаги 1, 2: 409 `state-conflict`, в теле `currentStatus`, новых событий и записей аудита нет. Шаг 3: черновик и неизвестная заявка 404, неверный идентификатор 400. Шаг 4: одно решение применено, второе получает 409. В браузере 409 «уже решено» обновляет карточку и показывает сообщение со статусом (TC-222).

### TC-214. Отказ: причина обязательна (1 – 1 000 символов), пауза 24 часа, заявитель видит причину и срок новой заявки

| Поле | Содержание |
| --- | --- |
| ID | TC-214 |
| User story | US-2.3 (сценарии 1, 3); US-2.5 (сценарий 2) |
| Требования | FT-2.1, FT-2.4 |
| Уровень | L3 |
| Тип | API |
| Приоритет | Высокий |
| Автоматизация | JUnit; JUnit на стенде; Python на стенде |
| Тег в коде | TC-214 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/SellerModerationIT.java`, `services/catalog-service/src/test/java/dgm/catalog/SellerOnboardingIT.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/controller/SellerModerationControllerTest.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/domain/SellerProfileTest.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/service/SellerModerationServiceTest.java`, `tools/stand-checks/seller_approval_checks.py` |

**Предусловия.** Заявка `on_review`, токен модератора.

**Шаги.**

1. `POST …/reject` с пустым телом, с причиной из пробелов, с причиной в 1 001 символ.
2. `POST …/reject` с причиной в 1 000 символов и с обычной причиной.
3. Заявитель читает свою заявку.
4. Модератор передаёт тело не JSON и неверный `Content-Type`.

**Ожидаемый результат.** Шаг 1: 422 с указателем `/reason`, статус не меняется. Шаг 2: 200, статус `rejected`, причина сохранена, в ответе `nextApplicationAt` (24 часа после отказа, параметр платформы от 1 часа до 7 суток), в Outbox и аудите причина записана. Шаг 3: заявитель видит статус, причину и дату новой заявки. Шаг 4: 415 или 400, ничего не меняется.

### TC-215. Возврат на доработку: комментарий обязателен, паузы нет, повторная подача сразу, срок 3 суток заново

| Поле | Содержание |
| --- | --- |
| ID | TC-215 |
| User story | US-2.3 (сценарии 2, 3); US-2.1 (сценарий 5) |
| Требования | FT-2.1, FT-2.0 |
| Уровень | L3 |
| Тип | API |
| Приоритет | Высокий |
| Автоматизация | JUnit; JUnit на стенде; Python на стенде |
| Тег в коде | TC-215 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/SellerModerationIT.java`, `services/catalog-service/src/test/java/dgm/catalog/SellerOnboardingIT.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/controller/SellerApplicationControllerTest.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/controller/SellerModerationControllerTest.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/domain/SellerProfileTest.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/service/SellerApplicationServiceTest.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/service/SellerModerationServiceTest.java`, `tools/stand-checks/seller_approval_checks.py` |

**Предусловия.** Заявка `on_review`, токен модератора.

**Шаги.**

1. `POST …/return` с пустым телом и с комментарием из пробелов.
2. `POST …/return` с комментарием «Уточните БИК банка получателя».
3. Заявитель правит анкету (нужна полная анкета и документ) и подаёт заявку.
4. Заявитель пытается подать заявку без документа.

**Ожидаемый результат.** Шаг 1: 422 с указателем `/comment`. Шаг 2: 200, статус `returned`, комментарий сохранён, паузы нет (`nextApplicationAt` пуст). Шаг 3: правка 200, подача сразу, без ожидания, статус `on_review`, срок рассмотрения 3 суток отсчитывается заново, заявка снова в очереди, прежний отказ или возврат её не задерживает. Шаг 4: 422, как для первой подачи (TC-204).

### TC-216. Повторная заявка после отказа: до конца паузы 409 с датой и `Retry-After`, правки разрешены, после паузы подача принята

| Поле | Содержание |
| --- | --- |
| ID | TC-216 |
| User story | US-2.5 (сценарии 1 – 3) |
| Требования | FT-2.4 |
| Уровень | L3 |
| Тип | API |
| Приоритет | Высокий |
| Автоматизация | JUnit; JUnit на стенде; Python на стенде |
| Тег в коде | TC-216 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/SellerOnboardingIT.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/controller/SellerApplicationControllerTest.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/domain/SellerProfileTest.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/domain/SellerSettingsTest.java`, `services/catalog-service/src/test/java/dgm/catalog/sellers/service/SellerApplicationServiceTest.java`, `tools/stand-checks/seller_approval_checks.py` |

**Предусловия.** Заявка отклонена (TC-214), прошло меньше 24 часов.

**Шаги.**

1. `PUT …/current` с исправленной анкетой.
2. `POST …/current/submit` сразу после правки.
3. Подать заявку без правки.
4. Сдвинуть часы на 24 часа и повторить подачу.

**Ожидаемый результат.** Шаг 1: 200, заявка возвращается в `draft`, причина и дата отказа остаются видны. Шаг 2: 409 `application-pause-active`, в теле дата, когда подача станет возможной, заголовок `Retry-After` в целых секундах (округление вверх). Шаг 3: без правки подать нельзя. Шаг 4: подача принята, статус `on_review`. Пауза отсчитывается от отказа и хранится как параметр; у профиля, которому никогда не отказывали, паузы нет; после возврата на доработку старый отказ не мешает.

### TC-217. Права на решения по заявкам: только модератор со вторым фактором, остальные получают отказ (ST-02, ST-13)

| Поле | Содержание |
| --- | --- |
| ID | TC-217 |
| User story | US-2.2; US-2.3 |
| Требования | FT-2.1, NFT-3.0 |
| Уровень | L7 |
| Тип | безопасность |
| Приоритет | Высокий |
| Автоматизация | JUnit на стенде; Newman |
| Тег в коде | TC-217 |
| Где в коде | `services/catalog-service/src/test/java/dgm/catalog/SellerModerationIT.java`, `services/catalog-service/src/test/java/dgm/catalog/SellerOnboardingIT.java`, `tools/api-tests/scenarios/moderation.json` |

**Предусловия.** Токены: модератор с кодом TOTP, модератор без области `staff.moderation`, покупатель, продавец, оператор поддержки, модератор без `otp` в `amr`, модератор с сессией по SMS; заявка `on_review`.

**Шаги.**

1. Вызвать `listSellerApplicationQueue`, `getSellerApplicationCard`, `approveSellerApplication`, `rejectSellerApplication`, `returnSellerApplication` без токена.
2. Вызвать те же операции токенами покупателя, продавца, оператора поддержки и модератора с чужой областью.
3. Вызвать те же операции токеном модератора, вошедшего только паролем (`amr = pwd`), и токеном сессии по SMS.
4. Вызвать операции заявителя (`createSellerApplication`, `submitSellerApplication`) токеном модератора.

**Ожидаемый результат.** Шаг 1: 401 `unauthenticated`. Шаг 2: 403 `forbidden`. Шаг 3: 403 `second-factor-required` для токена без `otp`, 403 для сессии по SMS. Шаг 4: 403 (роль не `buyer`). Модератор с кодом TOTP получает ответ по существу (200, 404, 409 или 422). Матрица ST-02 (`docs/05-architecture/roles-permissions.md`, раздел 4.2) для этих операций проверяется шагами Newman; тело отказа это `application/problem+json`, значений заявки в нём нет.

### TC-218. Кабинет продавца только со вторым фактором: настройка TOTP при первом входе, токен без `otp` получает 403 (ST-13, INV-37)

| Поле | Содержание |
| --- | --- |
| ID | TC-218 |
| User story | US-2.2 (сценарий 1); US-1.5 |
| Требования | FT-1.3, NFT-3.0 |
| Уровень | L7 |
| Тип | безопасность |
| Приоритет | Высокий |
| Автоматизация | Python на стенде |
| Тег в коде | TC-218 |
| Где в коде | `tools/stand-checks/keycloak_checks.py`, `tools/stand-checks/seller_approval_checks.py` |

**Предусловия.** Заявитель с одобренной заявкой (TC-212): роль `seller`, атрибут `seller_id`, обязательное действие `CONFIGURE_TOTP`.

**Шаги.**

1. Войти через `dgm-staff` паролем.
2. Настроить TOTP на странице Keycloak и получить токен сразу после настройки.
3. Вызвать `GET /api/v1/seller/products` токеном без `otp` в `amr`.
4. Войти заново с паролем и кодом TOTP и вызвать тот же адрес.
5. Вызвать `GET /api/v1/seller-applications/current` токеном продавца.

**Ожидаемый результат.** Шаг 1: Keycloak ведёт на настройку TOTP, кабинет недоступен. Шаг 2: токен с ролью `seller`, областью `seller.catalog` и claim `seller_id`, но `amr = [pwd]`. Шаг 3: 403 `second-factor-required`. Шаг 4: `amr = [otp, pwd]`, шлюз пропускает запрос (ответ не 401 и не 403). Шаг 5: 403, продавец заявку на статус продавца больше не подаёт. Второй фактор у продавца без устройства (seller-2) проверяет `keycloak_checks.py`.

### TC-219. Письмо заявителю о решении: три шаблона, текст модератора, срок новой заявки по Москве, без идентификаторов

| Поле | Содержание |
| --- | --- |
| ID | TC-219 |
| User story | US-2.4 (сценарий 1) |
| Требования | FT-2.2, FT-11.4 |
| Уровень | L3 |
| Тип | интеграционный |
| Приоритет | Высокий |
| Автоматизация | JUnit; JUnit на стенде |
| Тег в коде | TC-219 |
| Где в коде | `services/platform-service/src/test/java/dgm/platform/PlatformServiceIT.java`, `services/platform-service/src/test/java/dgm/platform/notification/handler/SellerDecisionHandlerTest.java`, `services/platform-service/src/test/java/dgm/platform/notification/letter/LettersTest.java` |

**Предусловия.** Платформа принимает события `seller.approved`, `seller.rejected`, `seller.returned`; у заявителя есть адрес в Keycloak.

**Шаги.**

1. Доставить событие об одобрении, об отказе (причина, срок новой заявки) и о возврате (комментарий).
2. Прочитать очередь писем и текст каждого письма.
3. Доставить то же событие дважды.
4. Доставить событие без обязательного поля, с неверным типом и с текстом длиннее схемы.

**Ожидаемый результат.** Шаг 1, 2: по одному письму на событие; одобрение называет получателя, тип профиля и второй фактор, отказ несёт причину модератора и время, когда можно подать заново (по Москве), возврат несёт комментарий. В очереди нет ни адреса, ни имени, ни идентификаторов заявки и модератора. Шаг 3: письмо одно (ключ дедупликации: событие и шаблон). Шаг 4: событие уходит сразу в очередь недоставленных, сообщение об ошибке называет поле, но не текст модератора. Знаки `{` и `}` в тексте модератора ничего не подставляют.

### TC-220. Отправка писем: повторы через 1, 5 и 25 секунд, отказ провайдера сразу, получателя нет

| Поле | Содержание |
| --- | --- |
| ID | TC-220 |
| User story | US-2.4 (сценарий 2) |
| Требования | FT-2.2, NFT-2.3 |
| Уровень | L3 |
| Тип | интеграционный |
| Приоритет | Средний |
| Автоматизация | JUnit; JUnit на стенде |
| Тег в коде | TC-220 |
| Где в коде | `services/platform-service/src/test/java/dgm/platform/PlatformServiceIT.java`, `services/platform-service/src/test/java/dgm/platform/notification/domain/RetryPolicyTest.java`, `services/platform-service/src/test/java/dgm/platform/notification/service/NotificationDispatcherTest.java`, `services/platform-service/src/test/java/dgm/platform/notification/service/NotificationServiceTest.java` |

**Предусловия.** Письмо в очереди, срок отправки наступил, почтовый клиент заменён записывающим.

**Шаги.**

1. Провайдер отвечает временным сбоем на каждую попытку.
2. Провайдер отвечает временным сбоем один раз и затем принимает письмо.
3. Провайдер отклоняет письмо окончательно.
4. Получатель деактивирован или удалён до отправки; адрес изменился после постановки в очередь.
5. Экземпляр платформы падает, захватив письмо; в одном проходе больше писем, чем размер пачки.

**Ожидаемый результат.** Шаг 1: повторы через 1, 5 и 25 секунд, затем статус `failed` и событие; решение по заявке не откатывается. Шаг 2: письмо отправлено, событий нет. Шаг 3: статус `failed` сразу, признак очереди недоставленных и событие об отклонённом адресе, в данных события адреса нет. Шаг 4: письма нет и сообщать некому; адрес берётся актуальный в момент отправки, в очереди его не хранят. Шаг 5: по окончании аренды письмо возвращается в очередь, проход отправляет не больше пачки, письма уходят в порядке срока. Идентификатор сообщения совпадает с идентификатором записи очереди, в журнал адрес и текст не попадают.

### TC-221. Экран заявки продавца: анкета, документы, подача, пауза после отказа

| Поле | Содержание |
| --- | --- |
| ID | TC-221 |
| User story | US-2.1 (сценарии 1 – 6); US-2.5 |
| Требования | FT-2.0, FT-2.4 |
| Уровень | L5 |
| Тип | UI |
| Приоритет | Средний |
| Автоматизация | Vitest |
| Тег в коде | TC-221 |
| Где в коде | `frontend/web-app/src/screens/seller/ApplicationPage.test.tsx`, `frontend/web-app/src/screens/seller/application.test.ts` |

**Предусловия.** Собранное приложение `frontend/web-app`; запросы к API подменены тестовым сервером (Vitest, `jsdom`).

**Шаги.**

1. Открыть `/apply` покупателем, заполнить анкету частично и сохранить.
2. Загрузить документ не того типа, слишком большой и допустимый; удалить документ.
3. Подать неполную анкету, затем полную.
4. Открыть экран при заявке на проверке, после отказа и после возврата.

**Ожидаемый результат.** Сохранение идёт с `If-Match`, поля анкеты не сбрасываются при загрузке документа. Неверный тип и размер отклоняются до отправки с понятной причиной, ошибки сервера показываются у своих полей по коду проблемы. Заявка на проверке показывается без форм, после отказа видны причина и дата новой заявки, после возврата комментарий и форма. Тексты берутся из словаря `ru.json`, кириллицы в разметке нет (правило ESLint).

### TC-222. Рабочее место модератора: очередь и карточка заявок, решения с подтверждением, вход с TOTP в браузере

| Поле | Содержание |
| --- | --- |
| ID | TC-222 |
| User story | US-2.2; US-2.3 |
| Требования | FT-2.1, NFT-3.0 |
| Уровень | L5 |
| Тип | UI |
| Приоритет | Высокий |
| Автоматизация | Vitest; Python на стенде |
| Тег в коде | TC-222 |
| Где в коде | `frontend/web-app/src/screens/staff/moderation/ApplicationCardPage.test.tsx`, `frontend/web-app/src/screens/staff/moderation/QueuePages.test.tsx`, `frontend/web-app/src/screens/staff/moderation/decision.test.ts`, `tools/stand-checks/ui_smoke.py` |

**Предусловия.** Vitest: тестовый сервер API. Дымовой сценарий: стенд `full`, браузер Chromium (Playwright), moderator-1 с секретом TOTP, заявка на проверке.

**Шаги.**

1. Открыть `/staff` без входа и войти через Keycloak (пароль, затем код TOTP).
2. Открыть очередь заявок, найти заявку, открыть карточку; проверить ссылку на документ.
3. Нажать «Одобрить», подтвердить.
4. Отказ и возврат: причина или комментарий обязательны; нажать подтверждение дважды подряд.
5. Открыть карточку, пока другой модератор принимает решение (ответ 409).

**Ожидаемый результат.** Шаг 1: переход на Keycloak и возврат на `/staff`, виден раздел «Заявки продавцов». Шаг 2: очередь показывает срок и признак просрочки, карточка анкету, реквизиты и документы по ссылкам на 5 минут с обратным отсчётом по часам сервера и кнопкой «Обновить карточку». Шаг 3: сообщение «Заявка одобрена.», по API статус `approved`. Шаг 4: без текста решение не отправляется, двойное нажатие даёт один запрос, при сбое сети повтор идёт с тем же `Idempotency-Key`. Шаг 5: карточка обновляется и показывает «уже решено» со статусом. Разделы `/staff` открыты только роли `moderator` со вторым фактором.

## 4. Тесты безопасности среза

| Тест | Что закрыто в срезе 1 | Чем |
| --- | --- | --- |
| ST-02 | Операции анкеты и документов (`OP-10`, `OP-14`), очереди и решений модератора (`OP-11`, `OP-12`): гость 401, остальные роли без права 403, второй фактор модератора | TC-217, `moderation.json`, матрица Newman, `SellerOnboardingIT`, `SellerModerationIT` |
| ST-12 | Документы продавца: тип по содержимому, а не по заголовку и имени, границы 2 МБ и одиннадцатый файл, заголовки скачивания, ссылка на 5 минут | TC-206, TC-207 |
| ST-13 | Роль продавца выдаётся только по решению модератора, кабинет открывается токеном со вторым фактором, роль вручную не назначается | TC-212, TC-218 |

## 5. Что не проверяется в срезе 1

| Что | Почему | Куда перенесено |
| --- | --- | --- |
| Блокировка продавца и снятие блокировки (US-2.6) | Релиз R2 | Ф5 |
| Решение по заявке модератором без второго фактора через браузер | Токен без `otp` проверяют TC-217 и TC-218 запросами, вход через браузер без TOTP не нужен | Не переносится |
| Повторная доставка письма при сбое провайдера в течение суток | На стенде столько ждать нельзя | JUnit с управляемыми часами (TC-220) |
