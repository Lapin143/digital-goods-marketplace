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
| `sequence-<scenario>.md` | Sequence-диаграммы SEQ-01…SEQ-06 | 7 |
| `c4-deployment.md` | Диаграмма развёртывания: Compose и сервер | 8 |
| `time-budgets.md` | Бюджеты времени: сессия, резерв, выдача, повторы | 8 |
| `roles-permissions.md` | Матрица ролей и прав | 9 |
| `threat-model.md` | Модель угроз STRIDE | 9 |
| `conventions.md` | Сквозные соглашения для API, событий и данных | 10 |
