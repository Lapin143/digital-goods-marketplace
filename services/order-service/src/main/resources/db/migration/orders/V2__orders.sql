-- СОЗДАН СКРИПТОМ tools/docs-checks/db/gen_migrations.py ИЗ tools/docs-checks/db/parts/order.sql. РУКАМИ НЕ ПРАВИТЬ.
-- Сервис order-service, база order_db. Миграция V2, схема orders.
-- Применённую миграцию править нельзя: Flyway сверяет контрольную сумму, правка вызовет ошибку проверки при старте.
-- Полная модель одним файлом: docs/07-data/ddl/order-service.sql

-- ============================================================================
-- Схема orders: заказы (SM-01). Таблица называется orders, потому что order зарезервировано в SQL
-- ============================================================================
create schema orders;
comment on schema orders is 'Модуль orders: заказ и его снимки (цена, комиссия, название товара, адрес доставки). Своя роль app_orders';

create table orders.orders (
    id                  uuid        not null,
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

-- Снимки неизменны (INV-12, INV-13), первая выдача фиксируется один раз (INV-17)
create function orders.orders_immutable() returns trigger
language plpgsql as
$fn$
begin
    if new.id is distinct from old.id
       or new.buyer_id is distinct from old.buyer_id
       or new.seller_id is distinct from old.seller_id
       or new.product_id is distinct from old.product_id
       or new.product_title is distinct from old.product_title
       or new.quantity is distinct from old.quantity
       or new.unit_price is distinct from old.unit_price
       or new.amount is distinct from old.amount
       or new.commission_rate_bp is distinct from old.commission_rate_bp
       or new.commission is distinct from old.commission
       or new.currency is distinct from old.currency
       or new.created_at is distinct from old.created_at then
        raise exception 'снимки заказа (товар, цена, количество, комиссия) не меняются' using errcode = '23514', constraint = 'ck_orders_snapshot_immutable';
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
