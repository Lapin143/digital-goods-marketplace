-- ============================================================================
-- order-service: база order_db (модуль orders)
-- Физическая модель данных R1, PostgreSQL 16. Файл собран из общего служебного блока и части сервиса.
-- Выполняется с нуля одним проходом: psql -v ON_ERROR_STOP=1 -d order_db -f order-service.sql
-- Нужны права создания схем, ролей и расширений. В проекте файл разбит на миграции Flyway по схемам (decomposition.md, правило 7),
-- здесь они сведены вместе, чтобы модель читалась и проверялась целиком.
-- Описание: docs/07-data/order-service-schema.md
-- ============================================================================

-- ============================================================================
-- Служебные таблицы и функции (одинаковы во всех базах сервисов)
-- ============================================================================

-- Исходящие события (Transactional Outbox, ADR-005).
-- Пишет прикладной код в той же транзакции, что и изменение данных. Публикует один экземпляр сервиса
-- (консультативная блокировка pg_try_advisory_lock), порядок по id.
create table public.outbox (
    id             bigint generated always as identity,
    event_id       uuid        not null,
    aggregate_type text        not null,
    aggregate_id   text        not null,
    event_type     text        not null,
    topic          text        not null,
    payload        jsonb       not null,
    headers        jsonb       not null,
    created_at     timestamptz not null default now(),
    published_at   timestamptz,
    attempts       integer     not null default 0,
    failed_at      timestamptz,
    constraint pk_outbox primary key (id),
    constraint uq_outbox_event_id unique (event_id),
    constraint ck_outbox_attempts check (attempts >= 0),
    constraint ck_outbox_published_or_failed check (published_at is null or failed_at is null),
    constraint ck_outbox_payload_object check (jsonb_typeof(payload) = 'object')
);
comment on table public.outbox is 'Исходящие события. Запись в одной транзакции с изменением данных (ADR-005, INV-43)';
comment on column public.outbox.id is 'Порядковый номер: публикатор читает по возрастанию, порядок событий агрегата сохраняется';
comment on column public.outbox.event_id is 'Идентификатор события (UUIDv7), он же заголовок и ключ дедупликации у потребителя (ADR-006)';
comment on column public.outbox.aggregate_type is 'Тип агрегата: order, payment, key, product и другие';
comment on column public.outbox.aggregate_id is 'Идентификатор агрегата, он же ключ сообщения Kafka (порядок по заказу или товару)';
comment on column public.outbox.event_type is 'Тип события, например order.paid';
comment on column public.outbox.topic is 'Тема Kafka, в которую публикуется событие';
comment on column public.outbox.payload is 'Тело события по схеме AsyncAPI, без персональных данных и без значений ключей (INV-20)';
comment on column public.outbox.headers is 'Заголовки сообщения: версия схемы, correlation id, время события';
comment on column public.outbox.created_at is 'Время записи в одной транзакции с изменением данных';
comment on column public.outbox.published_at is 'Время подтверждённой публикации в Kafka, пусто пока не отправлено';
comment on column public.outbox.attempts is 'Число неудачных попыток публикации';
comment on column public.outbox.failed_at is 'Время парковки после исчерпания попыток, разбор вручную, пусто в норме';
-- опрос публикатора: пачка неотправленных по возрастанию id
create index ix_outbox_unpublished on public.outbox (id) where published_at is null and failed_at is null;
-- ежедневная очистка отправленных строк старше 3 суток
create index ix_outbox_published_at on public.outbox (published_at) where published_at is not null;
-- припаркованные строки: разбор вручную и оповещение
create index ix_outbox_failed_at on public.outbox (failed_at) where failed_at is not null;

-- Обработанные события потребителя (уровень 2 идемпотентности, ADR-006). Хранение 14 суток.
create table public.processed_event (
    consumer     text        not null,
    event_id     uuid        not null,
    processed_at timestamptz not null default now(),
    constraint pk_processed_event primary key (consumer, event_id)
);
comment on table public.processed_event is 'Обработанные события: повтор распознаётся по паре (потребитель, event_id), хранение 14 суток';
comment on column public.processed_event.consumer is 'Имя потребителя: сервис и обработчик, у каждого свой набор обработанных событий';
comment on column public.processed_event.event_id is 'Идентификатор обработанного события из заголовка';
comment on column public.processed_event.processed_at is 'Время обработки, по нему идёт очистка через 14 суток';
create index ix_processed_event_processed_at on public.processed_event (processed_at);

-- Ключи идемпотентности HTTP-запросов (уровень 1, ADR-006). Хранение 24 часа.
create table public.idempotency_key (
    scope         text        not null,
    key           uuid        not null,
    endpoint      text        not null,
    request_hash  bytea       not null,
    state         text        not null,
    response_code integer,
    response_body jsonb,
    created_at    timestamptz not null default now(),
    constraint pk_idempotency_key primary key (scope, key),
    constraint ck_idempotency_key_state check (state in ('in_progress', 'completed')),
    constraint ck_idempotency_key_request_hash check (octet_length(request_hash) = 32),
    constraint ck_idempotency_key_response check (
        (state = 'in_progress' and response_code is null and response_body is null)
        or (state = 'completed' and response_code between 100 and 599)
    )
);
comment on table public.idempotency_key is 'Ключи идемпотентности запросов, хранение 24 часа (ADR-006, уровень 1)';
comment on column public.idempotency_key.scope is 'Область ключа: пользователь и метод, у разных пользователей одинаковые ключи не пересекаются';
comment on column public.idempotency_key.key is 'Ключ из заголовка Idempotency-Key (UUID)';
comment on column public.idempotency_key.endpoint is 'Метод и путь первого запроса, повтор с другим адресом отклоняется';
comment on column public.idempotency_key.request_hash is 'SHA-256 тела первого запроса: повтор с другим телом даёт 422 вместо ответа из кэша';
comment on column public.idempotency_key.state is 'in_progress: запрос выполняется, completed: ответ сохранён';
comment on column public.idempotency_key.response_code is 'Код ответа первого запроса, пусто пока in_progress';
comment on column public.idempotency_key.response_body is 'Тело ответа первого запроса, повтор возвращает его без выполнения';
comment on column public.idempotency_key.created_at is 'Время первого запроса, очистка через 24 часа';
create index ix_idempotency_key_created_at on public.idempotency_key (created_at);

-- Контроль допустимых переходов статуса (диаграммы SM). Аргументы триггера: 'initial:<статус>' и '<из>><в>'.
-- Статус хранится в столбце status. Нарушение даёт check_violation с именем ck_<таблица>_status_transition.
create function public.enforce_status_transition() returns trigger
language plpgsql as
$fn$
declare
    rule text;
begin
    if tg_op = 'INSERT' then
        if ('initial:' || new.status) = any (tg_argv) then
            return new;
        end if;
        raise exception 'недопустимый начальный статус % в таблице %', new.status, tg_table_name
            using errcode = '23514', constraint = 'ck_' || tg_table_name || '_status_transition';
    end if;
    if old.status = new.status then
        return new;
    end if;
    if (old.status || '>' || new.status) = any (tg_argv) then
        return new;
    end if;
    raise exception 'недопустимый переход статуса % -> % в таблице %', old.status, new.status, tg_table_name
        using errcode = '23514', constraint = 'ck_' || tg_table_name || '_status_transition';
end
$fn$;

comment on function public.enforce_status_transition() is 'Допустимые переходы статуса по диаграммам SM. Аргументы триггера: initial:<статус> для вставки и <из>><в> для изменения. Нарушение даёт ошибку 23514 с ограничением ck_<таблица>_status_transition';

-- ============================================================================
-- Схема orders: заказы (SM-01). Таблица называется orders, потому что order зарезервировано в SQL
-- ============================================================================
create schema orders;
comment on schema orders is 'Модуль orders: заказ и его снимки (цена, комиссия, название товара, адрес доставки). Своя роль app_orders';

create table orders.orders (
    id                  uuid        not null,
    number              bigint      generated always as identity (start with 1001),
    buyer_id            uuid        not null,
    seller_id           uuid        not null,
    product_id          uuid        not null,
    product_title       text        not null,
    quantity            integer     not null,
    unit_price          bigint      not null,
    amount              bigint      not null,
    commission_rate_bp  integer     not null,
    commission          bigint      not null,
    currency            char(3)     not null default 'RUB',
    delivery_channel    text        not null default 'email',
    delivery_address    text        not null,
    reserve_until       timestamptz,
    session_until       timestamptz,
    payment_session_url text,
    cancel_reason       text,
    paid_at             timestamptz,
    issued_at           timestamptz,
    status              text        not null default 'created',
    version             integer     not null default 1,
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    constraint pk_orders primary key (id),
    -- FT-5.0: короткий номер для людей, уникален; пропуски допустимы (откат транзакции не возвращает номер)
    constraint uq_orders_number unique (number),
    constraint ck_orders_status check (status in ('created', 'awaiting_payment', 'paid', 'issued', 'cancelled', 'refunded')),
    -- INV-10
    constraint ck_orders_quantity check (quantity between 1 and 10),
    constraint ck_orders_unit_price check (unit_price > 0 and unit_price <= 9007199254740991),
    -- INV-12: сумма равна цене-снимку, умноженной на количество
    constraint ck_orders_amount check (amount = unit_price * quantity and amount <= 9007199254740991),
    constraint ck_orders_commission_rate_bp check (commission_rate_bp between 0 and 10000),
    -- INV-13: комиссия в копейках, округление half-up по правилу conventions 4.6 (расчёт в numeric: без переполнения bigint)
    constraint ck_orders_commission check (
        commission::numeric = div(amount::numeric * commission_rate_bp + 5000, 10000) and commission >= 0
    ),
    constraint ck_orders_currency check (currency = 'RUB'),
    constraint ck_orders_delivery_channel check (delivery_channel = 'email'),
    constraint ck_orders_product_title_len check (char_length(product_title) between 1 and 200),
    constraint ck_orders_delivery_address_len check (char_length(delivery_address) between 3 and 254),
    constraint ck_orders_cancel_reason check (
        cancel_reason in ('out_of_stock', 'payment_declined', 'reservation_expired', 'payment_session_failed', 'system_error', 'seller_api_timeout')
    ),
    -- INV-07: срок платёжной сессии строго короче срока резерва, оба срока задаются вместе
    constraint ck_orders_deadlines check (
        (reserve_until is null) = (session_until is null)
        and (reserve_until is null or (session_until < reserve_until and reserve_until > created_at))
    ),
    constraint ck_orders_payment_session_url_len check (char_length(payment_session_url) between 1 and 2048),
    -- состояния и обязательные поля
    constraint ck_orders_awaiting_payment check (
        status <> 'awaiting_payment' or (reserve_until is not null and session_until is not null and payment_session_url is not null)
    ),
    constraint ck_orders_cancelled check (status <> 'cancelled' or cancel_reason is not null),
    constraint ck_orders_paid_at check (status not in ('paid', 'issued') or paid_at is not null),
    constraint ck_orders_issued_at check (status <> 'issued' or issued_at is not null),
    constraint ck_orders_early_state check (
        status not in ('created', 'awaiting_payment') or (paid_at is null and issued_at is null and cancel_reason is null)
    ),
    constraint ck_orders_version check (version >= 1)
);
comment on table orders.orders is 'Заказ: покупка одного товара одного продавца в количестве 1..10, оплачивается одним платежом (INV-11). Хранит снимки цены, комиссии, названия, адреса';
comment on column orders.orders.id is 'OrderID';
comment on column orders.orders.number is 'Короткий номер заказа для людей (FT-5.0, F10-5): целое число из последовательности базы, растёт по порядку, начинается с 1001, уникален; пропуски возможны. Его называют покупатель и оператор поддержки. Раскрывает общее число заказов, поэтому доступ к заказу проверяется по владельцу, а не по номеру; в адресах API остаётся OrderID';
comment on column orders.orders.buyer_id is 'BuyerID (пользователь, sub из Keycloak), чужая база, внешнего ключа нет';
comment on column orders.orders.seller_id is 'SellerID (профиль продавца)';
comment on column orders.orders.product_id is 'ProductID';
comment on column orders.orders.product_title is 'Название товара на момент оформления (снимок, F11-3): нужно истории и письмам, пока товар могут переименовать или архивировать';
comment on column orders.orders.quantity is 'Количество от 1 до 10 (INV-10)';
comment on column orders.orders.unit_price is 'Цена за единицу в копейках на момент оформления (снимок)';
comment on column orders.orders.amount is 'Сумма заказа в копейках: цена, умноженная на количество (INV-12)';
comment on column orders.orders.commission_rate_bp is 'Ставка комиссии в базисных пунктах на момент оформления (200 это 2%). Техническая колонка для проверки INV-13, в событиях и API не передаётся';
comment on column orders.orders.commission is 'Сумма комиссии в копейках: (amount * rate + 5000) / 10000, фиксируется при создании (INV-13)';
comment on column orders.orders.currency is 'Валюта, в R1 только RUB';
comment on column orders.orders.delivery_channel is 'Канал доставки, в R1 только email';
comment on column orders.orders.delivery_address is 'Снимок e-mail покупателя на момент оформления (персональные данные, INV-44). Меняется только повторной отправкой по обращению и анонимизацией';
comment on column orders.orders.reserve_until is 'Срок резерва: копия из резерва для показа покупателю и сверки, источник истины inventory-service';
comment on column orders.orders.session_until is 'Срок платёжной сессии, строго короче срока резерва (INV-07)';
comment on column orders.orders.payment_session_url is 'Адрес страницы оплаты у шлюза: хранится в заказе, чтобы покупатель мог вернуться к оплате (getOrderPaymentSession). Ссылка не содержит данных карты';
comment on column orders.orders.cancel_reason is 'Причина отмены, объясняет покупателю, что произошло (SM-01, решение 4)';
comment on column orders.orders.paid_at is 'Когда платёж подтверждён и заказ стал paid';
comment on column orders.orders.issued_at is 'Первый переход в issued, один раз за жизнь заказа (INV-17): от него окна 72 часа и удержание 7 дней';
comment on column orders.orders.status is 'SM-01: created, awaiting_payment, paid, issued, cancelled, refunded';
comment on column orders.orders.version is 'Версия строки, увеличивается при каждом изменении';
comment on column orders.orders.created_at is 'Дата создания заказа';
comment on column orders.orders.updated_at is 'Последнее изменение';

-- история покупателя, анонимизация по buyer_id
create index ix_orders_buyer_id on orders.orders (buyer_id, created_at desc, id desc);
comment on index orders.ix_orders_buyer_id is 'GET /api/v1/orders (US-5.9): заказы покупателя, новые выше, курсор по (created_at, id). Фильтр status поверх. Он же находит заказы покупателя при user.anonymized (INV-44)';
-- сторож: события «создан» потерялись
create index ix_orders_created_watchdog on orders.orders (created_at) where status = 'created';
comment on index orders.ix_orders_created_watchdog is 'Сторож раз в минуту: заказы created старше 60 секунд (потеряно событие или ответ)';
-- сторож: резерв истёк, а отмена не пришла
create index ix_orders_awaiting_reserve_until on orders.orders (reserve_until) where status = 'awaiting_payment';
comment on index orders.ix_orders_awaiting_reserve_until is 'Сторож раз в минуту: заказы awaiting_payment с резервом, истёкшим больше 5 минут назад (SM-01/T6)';

create trigger trg_orders_status_initial before insert on orders.orders
    for each row execute function public.enforce_status_transition('initial:created');
-- переходы R1: T2 .. T9. R2 добавит T10 (paid>cancelled) и T11 (issued>refunded) миграцией
create trigger trg_orders_status_transition before update of status on orders.orders
    for each row when (old.status is distinct from new.status)
    execute function public.enforce_status_transition(
        'created>awaiting_payment', 'created>cancelled', 'awaiting_payment>paid', 'awaiting_payment>cancelled',
        'cancelled>paid', 'cancelled>refunded', 'paid>issued');

-- Снимки и номер неизменны (INV-12, INV-13), первая выдача фиксируется один раз (INV-17)
create function orders.orders_immutable() returns trigger
language plpgsql as
$fn$
begin
    if new.id is distinct from old.id
       or new.buyer_id is distinct from old.buyer_id
       or new.seller_id is distinct from old.seller_id
       or new.number is distinct from old.number
       or new.product_id is distinct from old.product_id
       or new.product_title is distinct from old.product_title
       or new.quantity is distinct from old.quantity
       or new.unit_price is distinct from old.unit_price
       or new.amount is distinct from old.amount
       or new.commission_rate_bp is distinct from old.commission_rate_bp
       or new.commission is distinct from old.commission
       or new.currency is distinct from old.currency
       or new.created_at is distinct from old.created_at then
        raise exception 'снимки заказа (номер, товар, цена, количество, комиссия) не меняются' using errcode = '23514', constraint = 'ck_orders_snapshot_immutable';
    end if;
    if old.issued_at is not null and new.issued_at is distinct from old.issued_at then
        raise exception 'дата первой выдачи фиксируется один раз' using errcode = '23514', constraint = 'ck_orders_issued_at_once';
    end if;
    new.version := old.version + 1;
    new.updated_at := now();
    return new;
end
$fn$;
create trigger trg_orders_immutable before update on orders.orders
    for each row execute function orders.orders_immutable();

-- ============================================================================
-- Роль и права
-- ============================================================================
do $$
begin
    if not exists (select from pg_roles where rolname = 'app_orders') then
        create role app_orders nologin;
    end if;
end
$$;
grant usage on schema orders to app_orders;
grant select, insert, update on orders.orders to app_orders;
grant usage on schema public to app_orders;
grant select, insert, update, delete on public.outbox, public.processed_event, public.idempotency_key to app_orders;
grant usage on all sequences in schema public to app_orders;

comment on function orders.orders_immutable() is 'Снимки заказа неизменны (INV-12, INV-13), дата первой выдачи записывается один раз (INV-17), версия растёт на единицу при каждом изменении';
