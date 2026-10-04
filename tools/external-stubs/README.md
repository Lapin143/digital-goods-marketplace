# external-stubs: управляемые заглушки внешних систем

Один процесс Node.js без внешних пакетов (`package.json` без блока `dependencies`, только `http`, `https`, `net`, `crypto` стандартной библиотеки). Играет:

| Система | Интерфейс заглушки | Кто вызывает |
| --- | --- | --- |
| Платёжный шлюз | REST `/payment/v1/...` (ключ `payment_gateway_key`), страница оплаты `/payment/pay/:id`, вебхуки на `api-gateway` | `payment-service` |
| E-mail-провайдер | REST `/email/v1/messages` (ключи `delivery_email_key`, `platform_email_key`), SMTP на порту 1025, статусы вебхуком, просмотр писем `/mail` | `delivery-service`, `platform-service`, Keycloak (SMTP) |
| SMS-провайдер | REST `/sms/v1/messages` (ключ `platform_sms_key`), «телефон» (доставленные SMS) в списке команд управления, статус вебхуком | `platform-service` (SPI Keycloak) |
| VK ID | OpenID Connect под `/vkid`: описание, `authorize`, `token` (PKCE), `userinfo`, `jwks` (RS256) | Keycloak (брокер идентификации) |

Порты: **8443** прикладной интерфейс по TLS, **8444** команды управления по TLS (в контуре сервера наружу не публикуется, [ADR-015](../../docs/05-architecture/adr/ADR-015-payment-gateway-integration.md)), **1025** SMTP без TLS и AUTH. С отладочным файлом на ноутбуке: `18443`, `18444`, `11025` на 127.0.0.1 (`make up DEBUG=1`).

## Принципы

- **Ответ определяется режимом, а не случаем.** Режим системы это один JSON. `PUT /admin/modes/<система>` заменяет его целиком: пропущенное поле возвращается к значению по умолчанию, поэтому состояние определяется последним запросом, а не историей. «Доля неудач» берётся из детерминированного генератора, одинаковая после каждого сброса.
- **Режим платежа, письма и SMS фиксируется при приёме запроса.** Переключение режима не меняет исход уже созданного платежа и ещё не отправленного статуса письма.
- **Сброс между тестами.** `POST /admin/reset` дожидается завершения отложенных исходов прошлого теста, очищает платежи, письма, SMS, журнал и возвращает режимы по умолчанию. Идентификаторы не повторяются (в них входит метка запуска и сквозной номер), поэтому записи платформы от прошлого теста не конфликтуют с новыми.
- **Журнал принятых вызовов.** `GET /admin/journal?since=<номер>&system=&kind=` отдаёт запросы, вебхуки (каждая попытка), записи SMTP. Секреты не пишутся: заголовок `Authorization` в журнал не попадает, поля `token`, `secret`, `code` в телах маскируются.
- **Состояние в памяти.** Перезапуск контейнера возвращает всё по умолчанию.
- **Подпись вебхуков** по ADR-015: заголовки `X-Webhook-Timestamp` (RFC 3339 с миллисекундами) и `X-Webhook-Signature` (hex HMAC-SHA-256 от `метка.тело`, секрет своего источника). Заглушка предъявляет получателю клиентский сертификат `tls_external-stubs`.
- **Контрактный тест.** Схемы тел вебхуков не пишутся по памяти: `tools/docs-checks/check_stub_contract.py` вынимает их из OpenAPI в `test/contract/webhook-schemas.json`, `test/contract.test.mjs` проверяет по ним каждое уведомление заглушки. Изменили OpenAPI: проверка документов падает, пока файл не пересобран (`--write`) и заглушка не перечитана.

## Режимы

Значения по умолчанию в `src/modes.mjs`; запрос с неверным полем получает 400 со списком причин.

### `payment` (платёжный шлюз)

| Поле | По умолчанию | Смысл |
| --- | --- | --- |
| `scenario` | `success` | Исход платежа: `success` (вебхук `payment.paid`), `reject` (`payment.declined`), `hold` (вебхука нет, пока не придёт команда), `expire` (`payment.session-expired`) |
| `failureReason` | `declined` | Причина для `payment.declined` |
| `delayMs` | 1000 | Через сколько после создания платежа наступает исход |
| `sessionTtlSeconds` | 720 | Срок платёжной сессии, `expiresAt` в ответе |
| `create.failFirst` | 0 | Первые N вызовов создания отказывают (-1: все) |
| `create.failure` | `http_503` | Как отказывают: `http_503`, `http_429` (с `Retry-After`), `timeout` (соединение держится `create.timeoutMs`, затем закрывается без ответа) |
| `create.loseResponseFirst` | 0 | Платёж создан, ответ не дошёл: соединение рвётся. Повтор с тем же `Idempotency-Key` находит тот же платёж |
| `webhook.send` | true | false: статус у шлюза меняется, вебхук теряется (проверка сверки: `GET /payment/v1/payments/:id`) |
| `webhook.repeat`, `webhook.parallel` | 1, false | Сколько раз прислать одно уведомление, по очереди или одновременно |
| `webhook.badSignature` | false | Подпись неверна |
| `webhook.timestampOffsetSeconds` | 0 | Сдвиг метки времени: `-600` даёт устаревшее уведомление с верной подписью |
| `webhook.amountDelta` | 0 | Расхождение суммы в уведомлении |
| `webhook.unknownPayment` | false | Уведомление о платеже, которого платформа не знает |
| `webhook.retries`, `webhook.retryDelayMs` | 3, 500 | Повторы при 5xx, 429 и сетевой ошибке получателя, как у настоящего шлюза; 4xx не повторяются |
| `refund.mode` | `instant` | `instant`: возврат выполнен сразу, затем вебхук `refund.completed`; `pending`: «в обработке» до команды; `reject`: возврат не принимается никогда (422) |
| `refund.failFirst` | 0 | Первые N запросов возврата получают 503 |
| `refund.delayMs` | 1000 | Задержка вебхука `refund.completed` |

### `email`

| Поле | По умолчанию | Смысл |
| --- | --- | --- |
| `sender` | `any` | Чьи письма затрагивает режим: `any`, `delivery`, `platform`, `smtp` |
| `behavior` | `accept` | `accept`: 202; `temp_error`: 503, 429 или тайм-аут (`tempKind`); `perm_error`: 400 или 422 (`permStatus`), по SMTP 451 и 550 |
| `failFirst`, `failRate` | -1, 1 | Сколько вызовов отказывают (-1: все) и какая доля из подходящих |
| `dedupe` | true | Повтор с тем же `messageId` не создаёт второе письмо; false создаёт (редкий дубль, ADR-011) |
| `status`, `statusDelayMs`, `failReason` | `delivered`, 500, `address_rejected` | Итоговый статус вебхуком: `delivered`, `failed` или `none` (статуса нет вовсе) |
| `webhook.*` | как у платежа | Дефекты и потери вебхука статуса. Опрос `GET /email/v1/messages/:id` показывает итог и при потерянном вебхуке |

### `sms`

`behavior`: `deliver` (SMS «на телефоне», затем вебхук «доставлено»), `drop` (принято, не доходит), `late` (доходит через `lateMs`), `reject` (422), `error` (503). Код из текста достаётся полем `code`.

### `vkid`

`behavior`: `success`, `deny` (возврат `error=access_denied`), `error` (`server_error`); `delayMs`; `profile` (`sub`, `name`, `email`, ...) для следующих входов.

## Команды управления (порт 8444)

| Команда | Что делает |
| --- | --- |
| `GET/PUT /admin/modes[/<система>]` | Прочитать режимы, заменить режим системы |
| `POST /admin/reset` | Сброс между тестами |
| `GET /admin/state`, `GET/DELETE /admin/journal` | Сводка, журнал |
| `POST /admin/idle` | Дождаться отложенных исходов |
| `GET /admin/payments[/:id]` | Платежи со снимком режима и историей статусов |
| `POST /admin/payments/:id/confirm`, `/decline`, `/expire` | Исход платежа по команде: поздняя оплата, оплата после отказа (аномалия) |
| `POST /admin/payments/:id/refund-complete` | Отметить возврат выполненным («ручной возврат в кабинете шлюза») |
| `POST /admin/payments/:id/webhook` | Прислать уведомление заданного типа без смены статуса: перестановка, неизвестный тип |
| `GET /admin/emails[?to=&sender=&subject=]`, `GET /admin/emails/:id` | Письма (число ключей видно в тексте) |
| `POST /admin/emails/:id/status` | Прислать статус письма: `{"status":"delivered"}` |
| `GET /admin/sms[?to=&all=1]`, `POST /admin/sms/:id/status` | «Телефон» (доставленные SMS с кодом), статус SMS |

Командам платежа и статуса можно передать переопределения для одного вызова: `timestamp`, `badSignature`, `repeat`, `parallel`, `timestampOffsetSeconds`, `amountDelta`, `unknownPayment`, `retries`, `send`.

Пример (из теста позднего платежа):

```bash
curl --cacert secrets/tls_ca.crt -X PUT https://localhost:18444/admin/modes/payment -d '{"scenario":"hold"}'
# ... создать заказ, дождаться истечения резерва ...
curl --cacert secrets/tls_ca.crt -X POST https://localhost:18444/admin/payments/<paymentId>/confirm
```

## Настройка (переменные окружения)

`STUBS_API_PORT` (8443), `STUBS_ADMIN_PORT` (8444), `STUBS_SMTP_PORT` (1025), `STUBS_PUBLIC_URL` (адрес страницы оплаты и издатель VK ID), `STUBS_TLS_CERT_FILE` / `STUBS_TLS_KEY_FILE` / `STUBS_TLS_CA_FILE` (без них процесс слушает обычный HTTP, так работают модульные тесты), пути к секретам `PAYMENT_GATEWAY_KEY_FILE`, `PAYMENT_WEBHOOK_SECRET_FILE`, `DELIVERY_EMAIL_KEY_FILE`, `DELIVERY_WEBHOOK_SECRET_FILE`, `PLATFORM_EMAIL_KEY_FILE`, `PLATFORM_SMS_KEY_FILE`, `PLATFORM_WEBHOOK_SECRET_FILE`, адреса вебхуков `STUBS_PAYMENT_WEBHOOK_URL`, `STUBS_DELIVERY_EMAIL_WEBHOOK_URL`, `STUBS_PLATFORM_EMAIL_WEBHOOK_URL`, `STUBS_PLATFORM_SMS_WEBHOOK_URL`. Секреты читаются из файлов, значений в окружении нет.

## Тесты

```bash
make stubs-test            # в образе Node стенда, нужен только Docker
cd tools/external-stubs && npm test   # или напрямую, нужен Node 22 и выше
make up SET=dev-min DEBUG=1 && make stubs-check   # контейнер: TLS, ключи, SMTP, вебхуки настоящему получателю
```

Модульные тесты поднимают заглушку на свободных портах без TLS и «получателя вебхуков» на обычном HTTP; таймеры короткие, ожидание через `app.idle()`, поэтому прогон занимает секунды. `tools/stand-checks/stubs_checks.sh` проверяет то, что модульными тестами не проверить: TLS и цепочку, ограничения контейнера, разделение портов, секреты файлами, доставку вебхуков по HTTPS на получателя под именем `api-gateway` с независимой проверкой подписи.

## Риск расхождения с настоящим провайдером

Заглушка, как любая имитация, знает о провайдере то, что мы о нём записали. Три меры против расхождения: формат вебхуков берётся из OpenAPI (контрактный тест), интерфейс вызовов клиента описан в ADR-015 и ADR-011 и закреплён тестами заглушки, а перед подключением настоящего провайдера (R2) его песочница прогоняется теми же сквозными сценариями, что и заглушка.
