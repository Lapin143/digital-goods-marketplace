# Отчёт проверки индексов запросов (EXPLAIN)

Отчёт собирает скрипт `check_explain.py`: данные загружаются в базы, выполняется `ANALYZE`, для каждого запроса истории берётся план `EXPLAIN (FORMAT JSON)`. Критерии успеха: в плане есть ожидаемый индекс, нет полного просмотра таблицы больше 5000 строк, у запросов со страницей «новые выше» нет сортировки больше 2000 строк (порядок берётся из индекса). Кроме того, каждый индекс базы (кроме обслуживающих только ограничения) должен встретиться хотя бы в одном плане, иначе он лишний. План показывает, что именно читает база, но не замеряет время: скорость зависит от оборудования.

## Объёмы тестовых данных

| База | Таблица | Строк |
| --- | --- | --- |
| `catalog_db` | `idempotency_key` | 50 000 |
| `catalog_db` | `outbox` | 100 000 |
| `catalog_db` | `processed_event` | 100 000 |
| `catalog_db` | `product` | 60 000 |
| `catalog_db` | `seller_document` | 8 000 |
| `catalog_db` | `seller_profile` | 5 000 |
| `catalog_db` | `stock_view` | 42 000 |
| `inventory_db` | `idempotency_key` | 50 000 |
| `inventory_db` | `key` | 300 000 |
| `inventory_db` | `outbox` | 100 000 |
| `inventory_db` | `processed_event` | 100 000 |
| `inventory_db` | `product_copy` | 60 000 |
| `inventory_db` | `reservation` | 150 000 |
| `order_db` | `idempotency_key` | 50 000 |
| `order_db` | `orders` | 150 000 |
| `order_db` | `outbox` | 100 000 |
| `order_db` | `processed_event` | 100 000 |
| `payment_db` | `idempotency_key` | 50 000 |
| `payment_db` | `outbox` | 100 000 |
| `payment_db` | `payment` | 150 000 |
| `payment_db` | `payment_event` | 145 500 |
| `payment_db` | `processed_event` | 100 000 |
| `payment_db` | `refund_attempt` | 3 003 |
| `payment_db` | `unmatched_notification` | 30 000 |
| `delivery_db` | `delivery` | 150 000 |
| `delivery_db` | `delivery_attempt` | 150 000 |
| `delivery_db` | `delivery_watch` | 150 000 |
| `delivery_db` | `idempotency_key` | 50 000 |
| `delivery_db` | `outbox` | 100 000 |
| `delivery_db` | `processed_event` | 100 000 |
| `delivery_db` | `provider_status_event` | 150 000 |
| `platform_db` | `audit_log` | 200 000 |
| `platform_db` | `idempotency_key` | 50 000 |
| `platform_db` | `notification` | 400 000 |
| `platform_db` | `order_view` | 150 000 |
| `platform_db` | `outbox` | 100 000 |
| `platform_db` | `processed_event` | 100 000 |
| `platform_db` | `provider_status_event` | 50 000 |
| `platform_db` | `ticket` | 20 000 |
| `platform_db` | `user_account` | 100 000 |

## Запросы

| № | База | История и запрос | Что читает база | Итог |
| --- | --- | --- | --- | --- |
| C-01 | `catalog_db` | US-3.9 витрина без фильтров | Index Scan по ix_product_published; Index Scan по pk_stock_view | верно |
| C-02 | `catalog_db` | US-3.9 витрина, следующая страница по курсору | Index Scan по ix_product_published; Index Scan по pk_stock_view | верно |
| C-03 | `catalog_db` | US-3.10 фильтр по типу | Index Scan по ix_product_published; Index Scan по pk_stock_view | верно |
| C-03b | `catalog_db` | US-3.10 фильтр по редкому типу (подписки) | Index Scan по ix_product_published_type; Index Scan по pk_stock_view | верно |
| C-04 | `catalog_db` | US-3.10 фильтр по редкой платформе | Index Scan по ix_product_published_platform; Index Scan по pk_stock_view | верно |
| C-05 | `catalog_db` | US-3.10 фильтр по частой платформе | Index Scan по ix_product_published; Index Scan по pk_stock_view | верно |
| C-06 | `catalog_db` | US-3.11 узкий диапазон цены | Sort 47 строк; Bitmap Index Scan по ix_product_published_price; Index Scan по pk_stock_view | верно |
| C-07 | `catalog_db` | US-3.11 широкий диапазон цены | Index Scan по ix_product_published; Index Scan по pk_stock_view | верно |
| C-08 | `catalog_db` | US-3.10 регион, частый (РФ, плюс товары без ограничений) | Index Scan по ix_product_published; Index Scan по pk_stock_view | верно |
| C-09 | `catalog_db` | US-3.10 регион, редкий (Исландия, плюс товары без ограничений) | Index Scan по ix_product_published; Index Scan по pk_stock_view | верно |
| C-10 | `catalog_db` | US-3.9 поиск по части названия | Sort 4 строк; Bitmap Index Scan по ix_product_published_title_trgm; Index Scan по pk_stock_view | верно |
| C-11 | `catalog_db` | US-3.9 карточка товара с остатком | Index Scan по pk_product; Index Scan по pk_stock_view | верно |
| C-12 | `catalog_db` | US-3.1 товары продавца | Sort 12 строк; Bitmap Index Scan по ix_product_seller_id | верно |
| C-13 | `catalog_db` | US-3.1 товары продавца с фильтром статуса | Sort 1 строк; Bitmap Index Scan по ix_product_seller_id | верно |
| C-14 | `catalog_db` | US-3.3 очередь модерации товаров | Index Scan по ix_product_moderation_queue | верно |
| C-15 | `catalog_db` | US-8.8 просрочка модерации товаров (задание раз в 5 минут) | Index Scan по ix_product_overdue | верно |
| C-16 | `catalog_db` | US-2.2 очередь заявок продавцов | Index Scan по ix_seller_profile_review_queue | верно |
| C-17 | `catalog_db` | US-8.8 просрочка рассмотрения заявок | Index Scan по ix_seller_profile_overdue | верно |
| C-18 | `catalog_db` | US-2.1 профиль продавца по пользователю | Index Scan по uq_seller_profile_user_id | верно |
| C-19 | `catalog_db` | US-2.2 документы заявки | Sort 2 строк; Bitmap Index Scan по ix_seller_document_seller_id | верно |
| I-01 | `inventory_db` | ADR-007 выбор свободных ключей для резерва | Index Scan по ix_key_product_id_free | верно |
| I-02 | `inventory_db` | stock.changed число свободных ключей товара | Index Only Scan по ix_key_product_id_free | верно |
| I-03 | `inventory_db` | US-4.4 число ключей по статусам товара | Index Only Scan по ix_key_product_id_status | верно |
| I-04 | `inventory_db` | FT-7.1 ключи заказа для письма | Index Scan по ix_key_order_id | верно |
| I-05 | `inventory_db` | Снятие резерва: ключи резерва | Index Scan по ix_key_reservation_id | верно |
| I-06 | `inventory_db` | Подтверждение резерва по заказу | Index Scan по uq_reservation_order_id_active | верно |
| I-07 | `inventory_db` | Сверка раз в минуту: резервы с прошедшим сроком | Index Scan по ix_reservation_expires_at_active | верно |
| I-08 | `inventory_db` | ADR-013 последний резерв заказа | Index Scan по ix_reservation_order_id | верно |
| I-09 | `inventory_db` | ADR-009 перешифровка значений старой версии ключа данных | Sort 1630 строк; Index Scan по ix_key_dek_id | верно |
| I-10 | `inventory_db` | FT-4.1 проверка дублей при загрузке пачки | Index Only Scan по uq_key_product_id_hmac | верно |
| I-11 | `inventory_db` | Реестр товара: владелец и способ выдачи | Index Scan по pk_product_copy | верно |
| O-01 | `order_db` | US-5.9 история заказов покупателя | Sort 5 строк; Bitmap Index Scan по ix_orders_buyer_id | верно |
| O-02 | `order_db` | US-5.9 история заказов с фильтром статуса | Sort 4 строк; Bitmap Index Scan по ix_orders_buyer_id | верно |
| O-03 | `order_db` | US-5.3 заказ по идентификатору | Index Scan по pk_orders | верно |
| O-04 | `order_db` | Сторож: заказы created старше 60 секунд | Index Scan по ix_orders_created_watchdog | верно |
| O-05 | `order_db` | Сторож: заказы awaiting_payment с истёкшим резервом | Index Scan по ix_orders_awaiting_reserve_until | верно |
| O-06 | `order_db` | INV-44 замена адресов покупателя при анонимизации | Bitmap Index Scan по ix_orders_buyer_id | верно |
| P-01 | `payment_db` | FT-6.1 платёж по заказу | Index Scan по uq_payment_order_id | верно |
| P-02 | `payment_db` | FT-6.2 платёж по идентификатору из уведомления шлюза | Index Scan по uq_payment_gateway_payment_id | верно |
| P-03 | `payment_db` | FT-6.4 платёж по идентификатору возврата | Index Scan по uq_payment_gateway_refund_id | верно |
| P-04 | `payment_db` | Сверка: открытые платежи моложе 2 часов | Index Scan по ix_payment_created_open | верно |
| P-05 | `payment_db` | FT-6.4 очередь ручных возвратов | Index Only Scan по ix_payment_manual_refund_queue | верно |
| P-06 | `payment_db` | ADR-015 исполнитель возвратов | Index Scan по ix_refund_attempt_due | верно |
| P-07 | `payment_db` | ADR-015 открытая попытка возврата по платежу | Index Scan по uq_refund_attempt_payment_id_open | верно |
| P-08 | `payment_db` | ADR-015 сопоставление неопознанных уведомлений | Index Scan по ix_unmatched_notification_pending | верно |
| P-09 | `payment_db` | INV-14 дедупликация уведомления шлюза | Index Only Scan по pk_payment_event | верно |
| P-10 | `payment_db` | История уведомлений платежа | Index Scan по ix_payment_event_payment_id | верно |
| P-11 | `payment_db` | Очистка уведомлений старше 14 суток | Index Scan по ix_payment_event_received_at | верно |
| D-01 | `delivery_db` | ADR-011 диспетчер выдачи | Index Scan по ix_delivery_dispatch | верно |
| D-02 | `delivery_db` | Опрос статусов отправленных писем | Index Scan по ix_delivery_sent_poll | верно |
| D-03 | `delivery_db` | Выдачи заказа | Index Scan по ix_delivery_order_id | верно |
| D-04 | `delivery_db` | INV-18 первичная выдача заказа | Index Scan по uq_delivery_order_id_primary | верно |
| D-05 | `delivery_db` | INV-44 выдачи покупателя при анонимизации | Bitmap Index Scan по ix_delivery_buyer_id | верно |
| D-06 | `delivery_db` | NFT-2.4 контроль доставки 30 минут | Sort 25 строк; Bitmap Index Scan по ix_delivery_watch_due | верно |
| D-07 | `delivery_db` | Попытки отправки выдачи | Index Scan по pk_delivery_attempt | верно |
| D-08 | `delivery_db` | Статус письма от провайдера | Index Only Scan по pk_provider_status_event | верно |
| F-01 | `platform_db` | US-8.1 пользователи, фильтр роли и статуса | Index Scan по ix_user_account_role | верно |
| F-02 | `platform_db` | US-8.1 пользователи, только роль (покупатели) | Index Scan по ix_user_account_registered_at | верно |
| F-03 | `platform_db` | US-8.1 пользователи без фильтров | Index Scan по ix_user_account_registered_at | верно |
| F-04 | `platform_db` | US-8.1 пользователь по точному e-mail | Index Scan по uq_user_account_email | верно |
| F-05 | `platform_db` | INV-34 пользователь по номеру телефона | Index Scan по uq_user_account_phone | верно |
| F-06 | `platform_db` | Сверка identity-reconciler: телефоны не переданы в Keycloak | Bitmap Index Scan по ix_user_account_phone_unsynced | верно |
| F-07 | `platform_db` | Очистка прежних адресов e-mail | Index Scan по ix_user_account_previous_email_until | верно |
| F-08 | `platform_db` | US-7.2 очередь обращений | Index Scan по ix_ticket_status_created_at | верно |
| F-09 | `platform_db` | US-7.2 обращения оператора | Index Scan по ix_ticket_operator_id | верно |
| F-10 | `platform_db` | US-7.1 обращения покупателя | Index Scan по ix_ticket_buyer_id | верно |
| F-11 | `platform_db` | Обращения заказа | Index Scan по ix_ticket_order_id | верно |
| F-12 | `platform_db` | INV-21 открытое обращение по заказу | Index Scan по uq_ticket_order_id_open | верно |
| F-13 | `platform_db` | INV-22 заказ в модели чтения поддержки | Index Scan по pk_order_view | верно |
| F-14 | `platform_db` | Отправитель уведомлений | Sort 6 строк; Bitmap Index Scan по ix_notification_dispatch | верно |
| F-15 | `platform_db` | Недоставленные письма | Index Scan по ix_notification_dead_queue | верно |
| F-16 | `platform_db` | Уведомления пользователя | Index Scan по ix_notification_user_id | верно |
| F-17 | `platform_db` | ADR-006 одно событие, одно письмо | Index Only Scan по uq_notification_source_event_id_template | верно |
| F-18 | `platform_db` | US-8.7 журнал аудита без фильтров | Index Scan по audit_log_2026_10_occurred_at_id_idx; Index Scan по audit_log_2026_11_occurred_at_id_idx; Index Scan по audit_log_2026_12_occurred_at_id_idx; Index Scan по audit_log_2027_01_occurred_at_id_idx; Index Scan по audit_log_2027_02_occurred_at_id_idx; Index Scan по audit_log_2027_03_occurred_at_id_idx; Index Scan по audit_log_default_occurred_at_id_idx | верно |
| F-19 | `platform_db` | US-8.7 журнал аудита за период | Index Scan по audit_log_2026_11_occurred_at_id_idx | верно |
| F-20 | `platform_db` | US-8.7 журнал аудита по сотруднику | Index Scan по audit_log_2026_10_actor_id_occurred_at_id_idx; Index Scan по audit_log_2026_11_actor_id_occurred_at_id_idx; Index Scan по audit_log_2026_12_actor_id_occurred_at_id_idx; Index Scan по audit_log_2027_01_actor_id_occurred_at_id_idx; Index Scan по audit_log_2027_02_actor_id_occurred_at_id_idx; Index Scan по audit_log_2027_03_actor_id_occurred_at_id_idx; Index Scan по audit_log_default_actor_id_occurred_at_id_idx | верно |
| F-21 | `platform_db` | US-8.7 журнал аудита по объекту | Index Scan по audit_log_2026_10_object_type_object_id_occurred_at_id_idx; Index Scan по audit_log_2026_11_object_type_object_id_occurred_at_id_idx; Index Scan по audit_log_2026_12_object_type_object_id_occurred_at_id_idx; Index Scan по audit_log_2027_01_object_type_object_id_occurred_at_id_idx; Index Scan по audit_log_2027_02_object_type_object_id_occurred_at_id_idx; Index Scan по audit_log_2027_03_object_type_object_id_occurred_at_id_idx; Index Scan по audit_log_default_object_type_object_id_occurred_at_id_idx | верно |
| F-22 | `platform_db` | US-8.7 журнал аудита по виду действия | Index Scan по audit_log_2026_10_action_occurred_at_id_idx; Index Scan по audit_log_2026_11_action_occurred_at_id_idx; Index Scan по audit_log_2026_12_action_occurred_at_id_idx; Index Scan по audit_log_2027_01_action_occurred_at_id_idx; Index Scan по audit_log_2027_02_action_occurred_at_id_idx; Index Scan по audit_log_2027_03_action_occurred_at_id_idx; Index Scan по audit_log_default_action_occurred_at_id_idx | верно |
| F-23 | `platform_db` | ADR-014 дубль события аудита | Index Only Scan по audit_log_2026_10_event_id_occurred_at_key | верно |
| S-11 | `catalog_db` | ADR-005 публикатор outbox (catalog_db) | Index Scan по ix_outbox_unpublished | верно |
| S-12 | `catalog_db` | ADR-005 очистка отправленных событий старше 3 суток (catalog_db) | Index Scan по ix_outbox_published_at | верно |
| S-13 | `catalog_db` | ADR-006 очистка обработанных событий старше 14 суток (catalog_db) | Index Scan по ix_processed_event_processed_at | верно |
| S-14 | `catalog_db` | ADR-006 очистка ключей идемпотентности старше 24 часов (catalog_db) | Index Scan по ix_idempotency_key_created_at | верно |
| S-15 | `catalog_db` | ADR-005 припаркованные события (catalog_db) | Index Scan по ix_outbox_failed_at | верно |
| S-21 | `inventory_db` | ADR-005 публикатор outbox (inventory_db) | Index Scan по ix_outbox_unpublished | верно |
| S-22 | `inventory_db` | ADR-005 очистка отправленных событий старше 3 суток (inventory_db) | Index Scan по ix_outbox_published_at | верно |
| S-23 | `inventory_db` | ADR-006 очистка обработанных событий старше 14 суток (inventory_db) | Index Scan по ix_processed_event_processed_at | верно |
| S-24 | `inventory_db` | ADR-006 очистка ключей идемпотентности старше 24 часов (inventory_db) | Index Scan по ix_idempotency_key_created_at | верно |
| S-25 | `inventory_db` | ADR-005 припаркованные события (inventory_db) | Index Scan по ix_outbox_failed_at | верно |
| S-31 | `order_db` | ADR-005 публикатор outbox (order_db) | Index Scan по ix_outbox_unpublished | верно |
| S-32 | `order_db` | ADR-005 очистка отправленных событий старше 3 суток (order_db) | Index Scan по ix_outbox_published_at | верно |
| S-33 | `order_db` | ADR-006 очистка обработанных событий старше 14 суток (order_db) | Index Scan по ix_processed_event_processed_at | верно |
| S-34 | `order_db` | ADR-006 очистка ключей идемпотентности старше 24 часов (order_db) | Index Scan по ix_idempotency_key_created_at | верно |
| S-35 | `order_db` | ADR-005 припаркованные события (order_db) | Index Scan по ix_outbox_failed_at | верно |
| S-41 | `payment_db` | ADR-005 публикатор outbox (payment_db) | Index Scan по ix_outbox_unpublished | верно |
| S-42 | `payment_db` | ADR-005 очистка отправленных событий старше 3 суток (payment_db) | Index Scan по ix_outbox_published_at | верно |
| S-43 | `payment_db` | ADR-006 очистка обработанных событий старше 14 суток (payment_db) | Index Scan по ix_processed_event_processed_at | верно |
| S-44 | `payment_db` | ADR-006 очистка ключей идемпотентности старше 24 часов (payment_db) | Index Scan по ix_idempotency_key_created_at | верно |
| S-45 | `payment_db` | ADR-005 припаркованные события (payment_db) | Index Scan по ix_outbox_failed_at | верно |
| S-51 | `delivery_db` | ADR-005 публикатор outbox (delivery_db) | Index Scan по ix_outbox_unpublished | верно |
| S-52 | `delivery_db` | ADR-005 очистка отправленных событий старше 3 суток (delivery_db) | Index Scan по ix_outbox_published_at | верно |
| S-53 | `delivery_db` | ADR-006 очистка обработанных событий старше 14 суток (delivery_db) | Index Scan по ix_processed_event_processed_at | верно |
| S-54 | `delivery_db` | ADR-006 очистка ключей идемпотентности старше 24 часов (delivery_db) | Index Scan по ix_idempotency_key_created_at | верно |
| S-55 | `delivery_db` | ADR-005 припаркованные события (delivery_db) | Index Scan по ix_outbox_failed_at | верно |
| S-61 | `platform_db` | ADR-005 публикатор outbox (platform_db) | Index Scan по ix_outbox_unpublished | верно |
| S-62 | `platform_db` | ADR-005 очистка отправленных событий старше 3 суток (platform_db) | Index Scan по ix_outbox_published_at | верно |
| S-63 | `platform_db` | ADR-006 очистка обработанных событий старше 14 суток (platform_db) | Index Scan по ix_processed_event_processed_at | верно |
| S-64 | `platform_db` | ADR-006 очистка ключей идемпотентности старше 24 часов (platform_db) | Index Scan по ix_idempotency_key_created_at | верно |
| S-65 | `platform_db` | ADR-005 припаркованные события (platform_db) | Index Scan по ix_outbox_failed_at | верно |
| S-61 | `order_db` | ADR-006 ключ идемпотентности запроса | Index Scan по pk_idempotency_key | верно |
| S-62 | `order_db` | ADR-006 обработанное событие потребителя | Index Only Scan по pk_processed_event | верно |
| D-09 | `delivery_db` | Очистка статусов провайдера старше 14 суток | Index Scan по ix_provider_status_event_received_at | верно |
| F-24 | `platform_db` | Очистка статусов провайдера уведомлений старше 14 суток | Index Scan по ix_provider_status_event_received_at | верно |
