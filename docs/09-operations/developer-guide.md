# Руководство разработчика

| Поле | Содержание |
| --- | --- |
| Документ | Как поставить среду на Windows 11, поднять стенд Docker Compose, войти под тестовым пользователем, проверить стенд и запустить сервис из IDE при инфраструктуре в Compose |
| Статус | Версия 1. Команды сверяет с `Makefile` и `compose.debug.yaml` скрипт `check_guide_commands.py` (раздел 12), рецепты раздела 3, 7 и 10 выполняет CI (`make demo`, `make ide-check`). Установка Windows, WSL и Docker Desktop описана по документации поставщиков (у автора они стоят с Ф0, на чистой машине порядок не проверялся) |
| Фаза | Ф3, шаг 19 |
| Основание | [memory-budget.md](memory-budget.md) (наборы и лимиты), [c4-deployment.md](../05-architecture/c4-deployment.md) (профили, порты, сети), [ADR-022](../05-architecture/adr/ADR-022-internal-traffic-encryption.md) (центр сертификации), [infra/pki/README.md](../../infra/pki/README.md), [infra/keycloak/README.md](../../infra/keycloak/README.md) |
| Читатель | Разработчик проекта: аналитик, который ведёт проект, и любой, кто клонирует репозиторий. Операционные сбои и порядок действий при них в [runbook.md](runbook.md) |

Руководство отвечает на вопрос «как работать с проектом на своём ноутбуке». Если стенд уже работает и что-то пошло не так, нужен [runbook.md](runbook.md): он устроен по признакам сбоя, а руководство по порядку действий.

## 1. Что нужно

| Компонент | Версия | Где нужен |
| --- | --- | --- |
| Windows 11 | Home подходит, 8 ГБ памяти | Основная система |
| WSL2 с Ubuntu | Ubuntu 24.04 или новее (у автора 26.04) | Все команды `make` выполняются в Ubuntu, а не в PowerShell |
| Docker Desktop | Движок WSL 2, интеграция с Ubuntu | Контейнеры стенда |
| `make`, `git`, `curl`, `openssl` 3 и `python3` 3.12 или новее | Из пакетов Ubuntu 24.04 | Команды проекта, сертификаты, проверки (внешние пакеты Python не нужны, кроме проверок документов) |
| JDK 25 (Temurin) | 25 | Только для наборов с Java: `purchase`, `platform`, `gateway`, и для `make build`, `make test`, `make kit-test`. Наборы `dev-min` и `dev-auth` без шлюза собираются без Java. В Ф1 записано «JDK локально не ставим»: с Ф3 это не так, Gradle запускается на хосте (в отчёте Ф3 предложена сборка в контейнере для Ф4) |
| Node.js | Не нужен | Заглушки внешних систем собираются из образа Node 24 |
| AWS CLI v2 | Любая | Только для `make storage-check` |

Версии образов и зависимостей записаны в [versions.md](versions.md) и меняются только там.

## 2. Установка на Windows 11

### 2.1. WSL2 и Ubuntu

Если WSL2 и Ubuntu уже стоят (`wsl -l -v` показывает Ubuntu с версией 2), этот пункт пропускается. Иначе в PowerShell от имени администратора:

```powershell
wsl --install -d Ubuntu
```

После перезагрузки Ubuntu попросит имя пользователя и пароль. Проверка: `wsl -l -v` показывает Ubuntu с версией 2.

### 2.2. Лимит памяти WSL2

Бюджет памяти ([memory-budget.md](memory-budget.md)) рассчитан на 3 ГБ для WSL2: остальное остаётся Windows, браузеру и IDE. Файл `C:\Users\<имя>\.wslconfig`:

```ini
[wsl2]
memory=3GB
processors=4
swap=4GB
```

Число процессоров подберите по машине (у автора 3 ГБ памяти и 4 ГБ подкачки). Подкачка WSL общая для всех процессов, а контейнеры стенда свопа не используют (`memswap_limit` равен `mem_limit`). После правки в PowerShell: `wsl --shutdown`, затем открыть Ubuntu заново. Лимит относится ко всем контейнерам вместе: Docker Desktop с движком WSL 2 работает внутри этой же виртуальной машины.

### 2.3. Docker Desktop

1. Установить Docker Desktop, в настройках General включить «Use the WSL 2 based engine».
2. Settings, Resources, WSL integration: включить для вашего дистрибутива Ubuntu.
3. В Ubuntu проверить: `docker version` и `docker compose version` отвечают без `sudo`.
4. Автозапуск Docker Desktop при входе в Windows лучше выключить (Settings, General, «Start Docker Desktop when you sign in»): он держит память, пока стенд не нужен.

Если `docker` отвечает «permission denied» на `/var/run/docker.sock`, интеграция с Ubuntu выключена (пункт 2) или Docker Desktop не запущен.

### 2.4. Инструменты в Ubuntu

```bash
sudo apt update
sudo apt install -y make git curl openssl python3 unzip
```

JDK 25 нужен для наборов с Java и сборки. Проще всего поставить Temurin 25 через SDKMAN (`sdk list java`, выбрать строку 25.x с `tem`, `sdk install java <идентификатор>`) или из пакетов Adoptium. Сборка Gradle сама JDK не скачивает: в `dgm.java-conventions` требуется ровно 25. Если JDK 25 нет, Gradle отвечает «No matching toolchains found» (runbook, раздел 4).

### 2.5. Клон внутри файловой системы WSL

```bash
mkdir -p ~/dev && cd ~/dev
git clone https://github.com/Lapin143/digital-goods-marketplace.git
cd digital-goods-marketplace
```

Клонировать нужно в домашний каталог Ubuntu (`~/dev`), а не в `/mnt/c/...`. Причины: на диске Windows не соблюдаются права файлов, которыми защищён каталог секретов (`secrets/` с правами 0700 и ключи, [infra/pki/README.md](../../infra/pki/README.md)); обращения Docker к файлам через `/mnt/c` в несколько раз медленнее; окончания строк у проекта всегда LF (`.gitattributes`), а Git для Windows может их изменить. Папка `C:\dev\digital-goods-marketplace`, которую поддерживает синхронизация с проектом, это копия для чтения, стенд из неё не запускают.

### 2.6. Проверка установки

```bash
docker compose version
make --version | head -1
python3 --version
openssl version
java -version      # только если нужен Java
make help          # список команд проекта
```

## 3. Первый запуск

```bash
make certs secrets          # центр сертификации и секреты, создаются один раз
make up SET=dev-min         # PostgreSQL, Kafka, Redis, заглушки внешних систем
make stand-check            # состав, готовность, порты, сети, секреты, память
make down                   # остановить, данные сохраняются
```

`make up` сам выполняет `make certs secrets`, поэтому явный вызов нужен, только чтобы увидеть, что создаётся. Затем он собирает образы (для наборов с Java сначала jar и образы сервисов, `make images`), запускает `docker compose up -d --build` и ждёт состояния healthy у всех контейнеров набора (`WAIT_TIMEOUT` секунд, по умолчанию 420). Первый запуск скачивает образы и поэтому самый долгий.

Что появилось на диске, и что в Git не попадает (всё это в `.gitignore`):

| Что | Где |
| --- | --- |
| Закрытый ключ и сертификат центра сертификации | `.pki/ca.key`, `.pki/ca.crt` |
| Сертификаты и ключи контейнеров, пароли, ключи шифрования | `secrets/` |
| Тестовые пользователи Keycloak (раздел 7) | `secrets/test_users.json` |

Сертификаты контейнеров действуют 90 дней. `make pki-status` показывает остаток, `make pki-verify` проверяет цепочку, имена, ключи и срок (падает за 14 дней до его конца), `make certs` перевыпускает истекающие (подробнее в [runbook.md](runbook.md), раздел 3).

## 4. Какие наборы запускать на ноутбуке

Набор выбирается параметром `SET`. Состав наборов и расчёт описаны в [memory-budget.md](memory-budget.md), раздел 3.

| Набор | Что поднимает | Лимиты, МБ | Замер, МБ | Для чего |
| --- | --- | --- | --- | --- |
| `dev-min` | PostgreSQL, Kafka, Redis, заглушки | 1344 | 590 | Работа с базой, миграциями и событиями, интеграционные тесты каркаса (`make kit-test`), сервис из IDE (раздел 10) |
| `dev-platform` | `dev-min`, `platform-service`, объектное хранилище | 1920 | 886 | Служебный сервис, файлы и подписанные ссылки |
| `dev-auth` | `dev-min`, Keycloak, шлюз, веб-интерфейс | 2432 | 1464 | Вход, токены, страница через шлюз; запросы API без сервисов шлюз отклонит ошибкой |
| `dev-purchase` | `dev-min`, пять сервисов покупки | 3008 | 1783 | Контейнеры сервисов (`make services-check S="catalog-service inventory-service order-service payment-service delivery-service"`, без `S` проверка ждёт ещё и `platform-service`). На пределе по лимитам: закройте лишнее в Windows, Gradle запускайте без демона (раздел 9) |
| `full` | Все профили без наблюдаемости | 4672 | около 2400 в конце проверок (оценка: снимок `full-obs` без шести контейнеров `obs`) | Показ цели вехи M3 (`make demo`, `make smoke`). На ноутбуке с лимитом 3 ГБ не проверялся |
| `full-obs` | `full` и стек наблюдаемости | 5936 | 3667 (сумма пиков), около 3000 в конце проверок | Сервер и CI |

«Лимиты» это сумма `mem_limit` контейнеров, «замер» это сумма наибольших значений контейнеров за время проверок CI ([memory-measurements.md](memory-measurements.md), раздел 4). Решение о том, помещается ли набор, принимается по лимитам: так он гарантированно не выйдет за память.

Что запускать, по задачам:

| Задача | Набор |
| --- | --- |
| Написать миграцию, запрос, обработчик события | `dev-min` с `DEBUG=1` |
| Проверить вход и токены | `dev-auth` с `DEBUG=1` |
| Показать цель вехи M3 целиком (токен, шлюз, сервис, Outbox, Kafka) | `full` с `DEBUG=1`. На ноутбуке на время показа поднимите `memory=4GB` в `.wslconfig` и закройте браузер и IDE. По замеру CI набор занимает около 2,4 ГБ, но на ноутбуке это не измерялось |
| Посмотреть метрики, журналы и трассы | `full-obs` на сервере или в CI. На ноутбуке стек наблюдаемости поднимают отдельно (`infra/obs/README.md`) |

Параметр `DEBUG=1` добавляет `compose.debug.yaml`: порты баз, Keycloak, заглушек и стека наблюдаемости публикуются на `127.0.0.1` (раздел 5). Без него на хосте открыт только шлюз (8443). Выбор `DEBUG` нужно повторять в каждой команде, которая ходит к контейнерам: `make up SET=dev-min DEBUG=1`, `make stand-check DEBUG=1`.

## 5. Адреса и порты

На ноутбуке без `DEBUG=1` с хоста доступен только шлюз: `https://localhost:8443` (на сервере 443). Это единственный контейнер с портом на всех интерфейсах. С `DEBUG=1` добавляются порты на `127.0.0.1` (источник: `compose.debug.yaml`, сверяется скриптом раздела 12):

| Порт | Контейнер | Что там | Как подключаться |
| --- | --- | --- | --- |
| 15432 | `postgres` | PostgreSQL по TLS | `psql "host=localhost port=15432 dbname=order_db user=app_orders sslmode=verify-full sslrootcert=secrets/tls_ca.crt"`, пароль в `secrets/db_app_orders` (`psql` из пакета `postgresql-client`) |
| 19093 | `kafka` | Kafka по TLS, права по `CN` сертификата | Клиент с сертификатом сервиса: `Stand.kafkaSecurity` в тестах каркаса |
| 16379 | `redis` | Redis по TLS с пользователями | [infra/redis/README.md](../../infra/redis/README.md) |
| 19000 | `object-storage` | S3 API по TLS | `make storage-check` |
| 18445 | `keycloak` | Keycloak по TLS, в том числе консоль администратора `/auth/admin/` | Браузер: `https://localhost:18445/auth/admin/` (раздел 6) |
| 18446 | `api-gateway` | Порт управления шлюза (метрики и здоровье, mTLS) | Только с клиентским сертификатом |
| 18447 | `web-app` | Страница по mTLS | Только с клиентским сертификатом шлюза |
| 18443, 18444, 11025 | `external-stubs` | Заглушки: HTTPS API, административный порт, SMTP | `make stubs-check` |
| 3000 | `grafana` | Grafana, HTTP | Пользователь `admin`, пароль в `secrets/grafana_admin` |
| 9090 | `prometheus` | Prometheus, HTTP | Браузер |
| 19094 | `alertmanager` | Alertmanager, HTTP | Браузер |
| 13100 | `loki` | Loki, HTTP | Запросы через Grafana |
| 13200 | `tempo` | Tempo, HTTP | Запросы через Grafana |
| 14318 | `alloy` | Приём OTLP по HTTP, mTLS | Только с клиентским сертификатом |

Через шлюз консоль администратора Keycloak и его Admin API недоступны намеренно (шлюз отвечает 404, [ADR-021](../05-architecture/adr/ADR-021-api-gateway.md)). Поэтому консоль открывается только на отладочном порту 18445.

## 6. Доверие к корневому сертификату в браузере

Сертификаты стенда выданы собственным центром, браузер ему не доверяет и покажет «NET::ERR_CERT_AUTHORITY_INVALID» на `https://localhost:8443`. Чтобы предупреждения исчезли, нужно один раз добавить `secrets/tls_ca.crt` в доверенные корневые центры Windows. Chrome и Edge берут их из хранилища Windows, Firefox держит своё.

1. Скопировать файл в Windows: `cp secrets/tls_ca.crt /mnt/c/Users/<имя>/Downloads/dgm-ca.crt`.
2. Двойной щелчок по `dgm-ca.crt`, «Установить сертификат», «Текущий пользователь», «Поместить все сертификаты в следующее хранилище», «Доверенные корневые центры сертификации».
3. Firefox: Настройки, Приватность и защита, Просмотр сертификатов, Центры сертификации, Импортировать, отметить доверие для сайтов.
4. Для `curl` в Ubuntu браузер не нужен: `curl --cacert secrets/tls_ca.crt https://localhost:8443/...`.

Осторожность: пока сертификат центра в хранилище, Windows доверяет всему, что этот центр подписал, а ключ `.pki/ca.key` лежит на диске клона. Не копируйте `.pki/` и `secrets/` за пределы клона, не публикуйте диск и удалите сертификат из хранилища, когда стенд не нужен. После пересоздания центра (`runbook.md`, раздел 3) старый сертификат нужно убрать, а новый добавить.

Консоль администратора Keycloak на порту 18445: `https://localhost:18445/auth/admin/`, пользователь `dgm-admin`, пароль в `secrets/keycloak_admin`. Это администратор служебного realm `master`, не пользователь платформы.

## 7. Тестовые пользователи и вход

Пользователи создаются командой (нужен поднятый Keycloak, набор с профилем `auth`, и `DEBUG=1`):

```bash
make up SET=dev-auth DEBUG=1
make keycloak-users
```

`make keycloak-users` пересоздаёт 11 пользователей с одноразовыми случайными паролями и записывает их в `secrets/test_users.json` (права 0600):

| Имена | Роль | Второй фактор |
| --- | --- | --- |
| `buyer-1`, `buyer-2`, `buyer-3` | `buyer` | Нет |
| `seller-1`, `seller-2` | `seller` | Обязателен. У `seller-1` секрет TOTP в файле, у `seller-2` его нет: кабинет не должен открываться |
| `moderator-1`, `moderator-2` | `moderator` | Обязателен, секрет у номера 1 |
| `support-1`, `support-2` | `support-operator` | Обязателен, секрет у номера 1 |
| `admin-1`, `admin-2` | `admin` | Обязателен, секрет у номера 1 |

Адрес электронной почты пользователя `<имя>@test.dgm.local` (письма видны в заглушке). Посмотреть пароль: `python3 -c "import json;print([u['password'] for u in json.load(open('secrets/test_users.json'))['users'] if u['name']=='buyer-1'][0])"`.

Для входа через браузер сотрудника нужен код TOTP. Он считается так же, как приложение-аутентификатор, командой:

```bash
make otp WHO=seller-1
```

Для запросов из командной строки токен получать не обязательно вручную: `make demo` показывает вход покупателя и запросы через шлюз, а `make token` печатает токен для `curl`:

```bash
make demo                                    # набор full с DEBUG=1: вход, три запроса, готовая команда curl
TOKEN=$(make -s token)                       # токен buyer-1, живёт 5 минут
curl --cacert secrets/tls_ca.crt -H "Authorization: Bearer $TOKEN" https://localhost:8443/api/v1/orders
make -s token WHO=seller-1                   # токен другого пользователя
```

`make demo` это цель вехи M3: без токена шлюз отвечает 401, с токеном запрос доходит до `order-service` по mTLS и возвращает 200. Сквозной идентификатор запроса (`X-Correlation-Id`) один и тот же в журналах шлюза и сервиса (`make logs S=order-service`).

## 8. Проверки: что и когда запускать

| Команда | Что проверяет | Набор | Время |
| --- | --- | --- | --- |
| `make stand-check` | Состав контейнеров, готовность, порты, сети, секреты, память | Любой поднятый (`SET=` тот же, что при запуске) | около минуты |
| `make stand-down-check` | Чистое состояние после остановки | После `make down` или `make reset` | около минуты |
| `make smoke` | Главный путь: токен, операции через шлюз, ST-03, mTLS, Outbox → Kafka, сквозной идентификатор | `full`, `DEBUG=1`, `make keycloak-users` | около 40 секунд |
| `make smoke-sabotage` | Проверка проверки: испорченные токен, область и сертификат делают дымовой тест красным | То же | около 2 минут |
| `make demo` | Показ цели вехи M3 | То же | около 10 секунд |
| `make gateway-check` | Маршруты, токены, лимиты частоты, отказ открытым | `dev-auth` и `dev-purchase`, `DEBUG=1` | несколько минут |
| `make stateless-check` | NFT-1.3: два экземпляра сервиса, перезапуск одного посреди серии | `dev-auth` и `dev-purchase`, `DEBUG=1` | несколько минут |
| `make keycloak-check` | Вход по ролям, второй фактор, сроки, перебор, VK ID, Argon2 | `dev-auth`, `DEBUG=1` | около 3 минут |
| `make web-check` | Веб-интерфейс: доступ только шлюзу, заголовки | Набор с профилем `gateway`, `DEBUG=1` | около минуты |
| `make services-check` | Контейнеры сервисов: здоровье, память, журнал JSON | `dev-purchase` и `dev-platform`; при одном наборе перечислите сервисы в `S="..."` | около минуты |
| `make kit-test` | Интеграционные тесты каркаса, каталога и заказов на стенде | `dev-min`, `DEBUG=1`, `make db-migrate S="order-service inventory-service"` | несколько минут |
| `make db-check` | Миграции Flyway с нуля, права ролей | Набор с `infra` | около минуты |
| `make obs-check` | Цели Prometheus, Grafana, журнал в Loki, трасса в Tempo, письмо оповещения | `full-obs`, `DEBUG=1` | несколько минут |
| `make load` | 60 секунд нагрузки на каталог и заказы для замеров памяти | `full`, `DEBUG=1`, `make keycloak-users` | минута |
| `make memory-start`, `make memory-stop` | Замер пиков памяти контейнеров против лимитов (правило 85 процентов) | Любой | пока идёт замер |
| `make ide-check` | Рецепт раздела 10: сервис из Gradle при инфраструктуре в Compose | `dev-min`, `DEBUG=1`, миграции `order-service` | около 2 минут |

Полное описание проверок и их результатов в CI: [tools/stand-checks/README.md](../../tools/stand-checks/README.md), [tools/ci/README.md](../../tools/ci/README.md). Если дымовой тест красный, дальше смотреть нечего: сначала `make stand-check`, потом [runbook.md](runbook.md).

## 9. Сборка и тесты

| Команда | Что делает |
| --- | --- |
| `make build` | Gradle: все модули и модульные тесты (нужен JDK 25) |
| `make test` | Только тесты |
| `make jars` | Исполняемые jar сервисов без тестов |
| `make images` | Jar и образы всех сервисов (`dgm/<сервис>:dev`) |
| `make kit-test` | Интеграционные тесты на поднятом стенде (раздел 8) |
| `make contract-check` | Сверка ответов сервисов, записанных `make kit-test`, со схемами OpenAPI |
| `make stubs-test` | Модульные тесты заглушек внешних систем (нужен только Docker) |
| `make pki-test`, `make keycloak-test` | Тесты скриптов сертификатов и клиента Keycloak, сеть не нужна |
| `make clean` | Удалить результаты сборки |
| `bash tools/docs-checks/run_all.sh` | Проверки документов, контрактов и конфигурации (Python и `pyyaml`) |

Gradle берёт 1 ГБ кучи (`gradle.properties`). Если стенд уже занимает почти весь лимит WSL, остановите его (`make down`) перед `make build`.

На ноутбуке с лимитом WSL 3 ГБ Gradle запускайте без постоянного процесса (демона), иначе он останется в памяти рядом с контейнерами: `make up SET=dev-purchase GRADLEW="./gradlew --console=plain --no-daemon"` (то же для `make images`, `make build`). Замер на ноутбуке 7 октября: первая сборка с загрузкой Gradle 9.8.0 и зависимостей 1 мин 59 с, повторная 9 с, образы сервисов вместе со сборкой 2 мин 52 с; `dev-purchase` после запуска занимал 1492 МиБ в контейнерах и оставлял WSL около 780 МиБ доступной памяти, поэтому одновременно с ним сборку не запускайте.

## 10. Запуск сервиса из IDE при инфраструктуре в Compose

Идея: PostgreSQL, Kafka, Redis и заглушки работают в контейнерах, а сервис, над которым идёт работа, запускается из IDE или Gradle на хосте. Остальные сервисы не нужны, если работа идёт над одним ([memory-budget.md](memory-budget.md), принцип 6).

```bash
make up SET=dev-min DEBUG=1
```

Миграции сервис применяет сам при старте под ролью-мигратором, как в контейнере: пароль роли он берёт из `secrets/`. Отдельно `make db-migrate` для этого не нужен. Если в базе остались тестовые данные (профиль `testdata`, миграция 1000), а сервис запущен без профиля, это не ошибка: проверка миграций такие данные пропускает.

Сервис читает адреса и секреты из переменных окружения. Значения в таблице получены на стенде `dev-min` с отладочными портами:

| Переменная | Значение на ноутбуке | Что это |
| --- | --- | --- |
| `DGM_SECRETS_DIR` | Абсолютный путь к `secrets/` клона | Сертификат сервиса, доверенный центр, пароли баз |
| `DGM_PG_HOST`, `DGM_PG_PORT` | `localhost`, `15432` | PostgreSQL (в Compose `postgres`, `5432`) |
| `DGM_KAFKA_BOOTSTRAP` | `localhost:19093` | Kafka (в Compose `kafka:9093`) |
| `DGM_JWKS_URI` | `https://localhost:18445/auth/realms/dgm/protocol/openid-connect/certs` | Ключи токенов Keycloak, нужен только при поднятом наборе `auth` |
| `SERVER_PORT`, `MANAGEMENT_SERVER_PORT` | Свободные, например `18448` и `18449` | Вместо 8443 и 8444: шлюз занимает 8443, а у сервиса свои порты только в сети контейнеров |

Запуск из командной строки (то же делает IDE, выполняя задачу Gradle `bootRun` модуля):

```bash
DGM_SECRETS_DIR=$PWD/secrets DGM_PG_HOST=localhost DGM_PG_PORT=15432 DGM_KAFKA_BOOTSTRAP=localhost:19093 \
SERVER_PORT=18448 MANAGEMENT_SERVER_PORT=18449 ./gradlew :services:order-service:bootRun
```

Отладчик: добавить `--debug-jvm`, порт 5005. Сервис, как и в контейнере, слушает по mTLS, поэтому запросы идут с клиентским сертификатом (имя вызывающего берётся из `CN`):

```bash
curl --cacert secrets/tls_ca.crt --cert secrets/tls_api-gateway.crt --key secrets/tls_api-gateway.key https://localhost:18449/actuator/health
```

`make ide-check` выполняет ровно эту последовательность на `dev-min` и проверяет здоровье, ответ 401 без токена и отказ без клиентского сертификата. Он выполняется в CI.

Варианты IDE:

| Вариант | Как | Что учесть |
| --- | --- | --- |
| IDE подключается к WSL | IntelliJ IDEA: открыть проект по пути `\\wsl$\<дистрибутив>\home\<имя>\dev\digital-goods-marketplace`, JDK 25 внутри WSL; VS Code: расширение WSL | JVM сервиса работает в WSL и занимает около 250–300 МБ из лимита WSL. Путь к секретам обычный, как в командах выше |
| JVM на стороне Windows | JDK 25 в Windows, проект открыт по сетевому пути `\\wsl$\...` | JVM не занимает память WSL (принцип 6 бюджета). `DGM_SECRETS_DIR` задаётся путём `\\wsl$\<дистрибутив>\home\<имя>\dev\digital-goods-marketplace\secrets`, порты контейнеров Docker Desktop публикует и для Windows на `localhost`. Этот вариант в Ф3 не проверялся |

Один сервис из IDE работает вместе с остальным стендом, только если им нужен общий набор: шлюз в Compose обращается к сервисам по именам контейнеров и сервис на хосте не найдёт. Поэтому запуск сервиса из IDE это режим разработки и интеграционных тестов, а не сквозной показ: сквозной путь проверяет набор `full` в контейнерах.

## 11. Остановка, очистка, место на диске

| Команда | Результат |
| --- | --- |
| `make down` | Контейнеры остановлены и удалены, данные баз и Kafka сохраняются в томах |
| `make reset` | То же и тома удалены: базы и Kafka пусты, следующий `make up` создаёт всё заново, миграции применяются снова |
| `make ps` | Состояние контейнеров |
| `make logs S=kafka` | Последние 100 строк журнала контейнера |
| `docker system df` | Сколько места занимают образы, тома и кэш сборки |
| `docker builder prune` | Очистка кэша сборки, если диск WSL заполнен |

Данные тестового стенда не ценные: `make reset` безопасен. Секреты (`secrets/`) при этом остаются, поэтому пароли баз и сертификаты не меняются.

## 12. Что в руководстве проверено автоматически

| Утверждение | Чем проверено | Где |
| --- | --- | --- |
| Все `make`-цели из руководства и runbook существуют, значения `SET` существуют, пути к файлам верны, переменные `DGM_*` читаются кодом, порты раздела 5 совпадают с `compose.debug.yaml`, ключевые цели упомянуты | `tools/docs-checks/check_guide_commands.py` | Задание `docs` |
| Мутации: пропавшая цель, неверный набор, лишний порт, несуществующий файл ловятся | `tools/ci/selftest.sh docs` | Задание `docs` |
| `make demo` и `make token`, команда `curl` из раздела 7 | Шаг «Показ запроса через шлюз с токеном» | Задание `stand` |
| `make ide-check`: запуск сервиса из Gradle с переменными раздела 10 | Шаг «Сервис из Gradle при инфраструктуре в Compose» | Задание `kit` |
| Установка Windows, WSL, Docker Desktop, доверие сертификату в Windows и Firefox, вариант IDE с JVM на стороне Windows | Не автоматизируется: описано по документации поставщиков | Проверяется при первом запуске на ноутбуке; расхождения вносятся сюда |

## 13. Связанные документы

- [runbook.md](runbook.md): первый администратор, потеря второго фактора, перевыпуск сертификатов, типовые сбои.
- [memory-budget.md](memory-budget.md), [memory-measurements.md](memory-measurements.md): лимиты, наборы, замеры.
- [versions.md](versions.md): версии всего, что используется.
- [c4-deployment.md](../05-architecture/c4-deployment.md): профили, сети, порты, секреты.
- [tools/stand-checks/README.md](../../tools/stand-checks/README.md), [tools/ci/README.md](../../tools/ci/README.md): проверки стенда и CI.
