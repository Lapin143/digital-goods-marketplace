-- СОЗДАН СКРИПТОМ tools/docs-checks/db/gen_migrations.py ИЗ tools/docs-checks/db/parts/delivery.r2.sql. РУКАМИ НЕ ПРАВИТЬ.
-- Сервис delivery-service, база delivery_db. Миграция V4, R2, запрос ключа у системы продавца и причина сбоя «продавец недоступен» (срез 1 Ф5, Р-190, Р-191).
-- Применённую миграцию править нельзя: Flyway сверяет контрольную сумму, правка вызовет ошибку проверки при старте.
-- Полная модель одним файлом: docs/07-data/ddl/delivery-service.sql

-- ============================================================================
-- R2, delivery: запрос ключа у системы продавца и причина сбоя «продавец недоступен» (срез 1 Ф5, Р-190, Р-191)
-- ============================================================================
-- сбой выдачи по товару с выдачей по API: продавец не ответил или отказал, письма не было, delivery.failed не публикуется
alter table delivery.delivery drop constraint ck_delivery_fail_reason;
alter table delivery.delivery add constraint ck_delivery_fail_reason check (fail_reason in ('attempts_exhausted', 'address_rejected', 'seller_unavailable'));
comment on column delivery.delivery.fail_reason is 'attempts_exhausted, address_rejected или seller_unavailable (продавец не ответил или отказал, только у товара с выдачей по API, письма не было), только у статуса failed';

create table delivery.seller_request (
    delivery_id     uuid        not null,
    request_id      uuid        not null,
    seller_id       uuid        not null,
    product_id      uuid        not null,
    order_number    bigint      not null,
    status          text        not null default 'requested',
    deadline_at     timestamptz not null,
    attempts        integer     not null default 0,
    next_attempt_at timestamptz,
    lease_until     timestamptz,
    error_code      text,
    error_message   text,
    accepted_at     timestamptz,
    answered_at     timestamptz,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    constraint pk_seller_request primary key (delivery_id),
    constraint fk_seller_request_delivery_id foreign key (delivery_id) references delivery.delivery (id),
    constraint uq_seller_request_request_id unique (request_id),
    constraint ck_seller_request_status check (status in ('requested', 'accepted', 'answered', 'failed', 'timeout')),
    constraint ck_seller_request_order_number check (order_number >= 1001),
    constraint ck_seller_request_deadline check (deadline_at > created_at),
    -- три попытки запроса внутри окна (Р-191)
    constraint ck_seller_request_attempts check (attempts between 0 and 3),
    -- следующая попытка есть только у запроса, который ещё не доставлен; после третьей неудачи его ждёт тайм-аут
    constraint ck_seller_request_next_attempt check (next_attempt_at is null or (status = 'requested' and attempts < 3)),
    -- аренда держит ответ продавца, начатый до конца окна, пока ключ не сохранён (Р-190)
    constraint ck_seller_request_lease check (lease_until is null or status in ('requested', 'accepted')),
    constraint ck_seller_request_error_code check (error_code ~ '^[a-z0-9_.-]{1,64}$'),
    constraint ck_seller_request_error_message_len check (char_length(error_message) between 1 and 500),
    constraint ck_seller_request_failed check ((status = 'failed') = (error_code is not null)),
    constraint ck_seller_request_error_message check (error_message is null or status = 'failed'),
    constraint ck_seller_request_accepted_at check (status <> 'accepted' or accepted_at is not null),
    constraint ck_seller_request_answered_at check ((status = 'answered') = (answered_at is not null))
);
comment on table delivery.seller_request is 'Запрос ключа у системы продавца по заказу с выдачей по API (R2, Р-190, ADR-028): состояние ожидания перед первичной выдачей. Значений ключей нет (INV-20): ключ уходит в inventory-service сразу при ответе';
comment on column delivery.seller_request.delivery_id is 'Первичная выдача заказа, на одну выдачу один запрос. Пока запрос не answered, диспетчер выдачу в отправку не берёт';
comment on column delivery.seller_request.request_id is 'Идентификатор запроса: продавец возвращает его в ответе, повтор ответа с тем же значением идемпотентен (Р-191)';
comment on column delivery.seller_request.seller_id is 'SellerID из order.paid: ответ принимается только от ключа этого продавца, чужой ключ даёт 404';
comment on column delivery.seller_request.product_id is 'ProductID из order.paid, уходит в тело запроса продавцу';
comment on column delivery.seller_request.order_number is 'Короткий номер заказа из order.paid для запроса продавцу и для людей, не идентификатор';
comment on column delivery.seller_request.status is 'requested (запрос не доставлен), accepted (продавец ответил 2xx), answered (ключи получены и сохранены), failed (продавец отказал или система не подключена), timeout (окно закрылось без ответа)';
comment on column delivery.seller_request.deadline_at is 'Срок ответа: время оплаты плюс параметр delivery.seller-response-seconds (по умолчанию 600 секунд, US-6.7)';
comment on column delivery.seller_request.attempts is 'Сколько раз платформа отправила запрос, от 0 до 3 (паузы 5 и 30 секунд внутри окна)';
comment on column delivery.seller_request.next_attempt_at is 'Срок следующей отправки запроса, есть только у ещё не доставленного запроса с попытками в запасе';
comment on column delivery.seller_request.lease_until is 'Аренда на приём ответа: ответ, начатый до конца окна, имеет право завершиться, тайм-аут запрос с арендой не трогает (Р-190)';
comment on column delivery.seller_request.error_code is 'Код отказа, только у failed: out_of_stock, cannot_issue, other из ответа продавца или seller_rejected, integration_disabled, integration_missing, endpoint_rejected от платформы';
comment on column delivery.seller_request.error_message is 'Пояснение продавца для оператора поддержки, до 500 знаков, только у failed. В журналы и события не попадает';
comment on column delivery.seller_request.accepted_at is 'Когда продавец ответил 2xx на запрос';
comment on column delivery.seller_request.answered_at is 'Когда ключи сохранены в inventory-service';
comment on column delivery.seller_request.created_at is 'Когда запрос создан (по order.paid)';
comment on column delivery.seller_request.updated_at is 'Последнее изменение';
-- отправка запросов: пачка ещё не доставленных, срок которых наступил
create index ix_seller_request_dispatch on delivery.seller_request (next_attempt_at, delivery_id) where status = 'requested' and next_attempt_at is not null;
comment on index delivery.ix_seller_request_dispatch is 'SellerRequestJob: WHERE status = ''requested'' AND next_attempt_at <= now() ORDER BY next_attempt_at LIMIT 20 FOR UPDATE SKIP LOCKED (образец диспетчера выдач)';
-- тайм-аут: запросы без ответа, срок которых вышел
create index ix_seller_request_deadline on delivery.seller_request (deadline_at) where status in ('requested', 'accepted');
comment on index delivery.ix_seller_request_deadline is 'SellerTimeoutJob: запросы requested и accepted со сроком в прошлом и без действующей аренды, FOR UPDATE SKIP LOCKED';

create trigger trg_seller_request_status_initial before insert on delivery.seller_request
    for each row execute function public.enforce_status_transition('initial:requested');
-- ответ продавца может прийти раньше, чем завершится наш запрос к нему: requested > answered допустим
create trigger trg_seller_request_status_transition before update of status on delivery.seller_request
    for each row when (old.status is distinct from new.status)
    execute function public.enforce_status_transition(
        'requested>accepted', 'requested>answered', 'requested>failed', 'requested>timeout',
        'accepted>answered', 'accepted>failed', 'accepted>timeout');

create function delivery.seller_request_immutable() returns trigger
language plpgsql as
$fn$
begin
    if new.delivery_id is distinct from old.delivery_id or new.request_id is distinct from old.request_id
       or new.seller_id is distinct from old.seller_id or new.product_id is distinct from old.product_id
       or new.order_number is distinct from old.order_number or new.deadline_at is distinct from old.deadline_at
       or new.created_at is distinct from old.created_at then
        raise exception 'выдача, идентификатор запроса, продавец, товар и срок запроса не меняются' using errcode = '23514', constraint = 'ck_seller_request_immutable';
    end if;
    new.updated_at := now();
    return new;
end
$fn$;
create trigger trg_seller_request_immutable before update on delivery.seller_request
    for each row execute function delivery.seller_request_immutable();
comment on function delivery.seller_request_immutable() is 'Выдача, идентификатор запроса, продавец, товар и срок запроса неизменны, время изменения обновляется';
