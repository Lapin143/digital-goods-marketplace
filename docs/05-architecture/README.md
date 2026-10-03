# 05. Архитектура

Архитектурные схемы нарисованы в нотации C4 в виде кода Mermaid (`flowchart` с оформлением C4, см. [c4-notation.md](c4-notation.md)). Пошаговый план фазы: [00-phase2-guide.md](../00-phase2-guide.md).

| Артефакт | Содержимое | Шаг Ф2 |
| --- | --- | --- |
| [c4-notation.md](c4-notation.md) | Правила рисования C4, легенда, ограничения Mermaid | 1 |
| [c4-context.md](c4-context.md) | C4 уровень 1: система, люди, внешние системы (R1 и расширения R2) | 1 |
| [decomposition.md](decomposition.md) | Декомпозиция на сервисы и укрупнение для R1, правила модульности, R2 и R3 | 2 |
| [c4-containers.md](c4-containers.md) | C4 уровень 2: контейнеры R1 (7 диаграмм), расширения R2 (2 диаграммы), события | 2 |
| [adr/](adr/README.md) | Записи архитектурных решений | 2–5 |
| [c4-components.md](c4-components.md) | C4 уровень 3: обзор, общий каркас `service-kit`, матрицы «связь контейнера → компонент», «событие → компонент», «инвариант → компонент» | 6 |
| [c4-components-order-service.md](c4-components-order-service.md) | Компоненты сервиса заказов: оркестратор саги, домен, обработка событий, сторож, Outbox (2 диаграммы) | 6 |
| [c4-components-inventory-service.md](c4-components-inventory-service.md) | Компоненты сервиса остатков и ключей: резерв, шифрование, выдача значений, таймер Redis (2 диаграммы) | 6 |
| [c4-components-delivery-service.md](c4-components-delivery-service.md) | Компоненты сервиса выдачи: очередь, отправка, повторы, статус письма, контроль 30 минут (2 диаграммы) | 6 |
| [c4-components-payment-service.md](c4-components-payment-service.md) | Компоненты сервиса платежей: сессия, подпись вебхуков, сверка, возвраты (2 диаграммы) | 6 |
| [c4-components-catalog-service.md](c4-components-catalog-service.md) | Компоненты сервиса каталога (таблицы) | 6 |
| [c4-components-platform-service.md](c4-components-platform-service.md) | Компоненты служебного сервиса по модулям (таблицы) | 6 |
| [sequence-purchase.md](sequence-purchase.md) | SEQ-01: покупка, успешный путь, динамика C4 и три sequence-диаграммы, исключения E1 – E12 | 7 |
| [sequence-late-payment.md](sequence-late-payment.md) | SEQ-02: поздняя оплата, перерезервирование, автовозврат и очередь администратора | 7 |
| [sequence-guaranteed-delivery.md](sequence-guaranteed-delivery.md) | SEQ-03: повторы отправки, контроль 30 минут, DLQ, передача в поддержку | 7 |
| [sequence-email-change.md](sequence-email-change.md) | SEQ-04: обращение в поддержку, повторная отправка, смена e-mail по двум кодам | 7 |
| [sequence-phone-sms-login.md](sequence-phone-sms-login.md) | SEQ-05: подтверждение телефона и вход по коду из SMS | 7 |
| [sequence-seller-approval.md](sequence-seller-approval.md) | SEQ-06: заявка продавца, решение модератора, роль и обязательная 2FA | 7 |
| [c4-deployment.md](c4-deployment.md) | Развёртывание: среды, профили Compose, сети и порты, наблюдаемость и копирование, секреты, переход на k3s (5 диаграмм) | 8 |
| [time-budgets.md](time-budgets.md) | Бюджеты времени: сессия, резерв, выдача, повторы, автовозврат, тест-режим | 8 |
| [../09-operations/memory-budget.md](../09-operations/memory-budget.md) | Бюджеты памяти: лимиты контейнеров, профили, наборы для ноутбука, CI и сервера (предварительные, замеры в Ф3) | 8 |
| [roles-permissions.md](roles-permissions.md) | Матрица ролей и прав: 55 операций R1, области токена, условия доступа, сессия по SMS, второй фактор, права вызовов между сервисами | 9 |
| [threat-model.md](threat-model.md) | Модель угроз STRIDE: 6 границ доверия, 12 активов, 44 угрозы, остаточные риски, процедуры при компрометации | 9 |
| [conventions.md](conventions.md) | Сквозные соглашения: идентификаторы, деньги, время, словарь статусов, REST (пути, версии, заголовки), ошибки RFC 9457 и реестр типов проблем, пагинация, конверт событий, темы, версии схем, потребитель и DLQ, журналы | 10 |
| [../06-api/asyncapi/asyncapi.yaml](../06-api/asyncapi/asyncapi.yaml) | Контракт событий AsyncAPI 3.0: 55 событий (R1 37, R2 18), 11 тем, потребители и ключи идемпотентности. Каталог: [events](../06-api/events/README.md) | 10 |
