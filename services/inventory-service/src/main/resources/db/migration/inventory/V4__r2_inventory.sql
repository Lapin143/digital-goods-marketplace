-- СОЗДАН СКРИПТОМ tools/docs-checks/db/gen_migrations.py ИЗ tools/docs-checks/db/parts/inventory.r2.sql. РУКАМИ НЕ ПРАВИТЬ.
-- Сервис inventory-service, база inventory_db. Миграция V4, R2, остаток по API и возврат остатка при отмене (срез 1 Ф5, Р-183, Р-192).
-- Применённую миграцию править нельзя: Flyway сверяет контрольную сумму, правка вызовет ошибку проверки при старте.
-- Полная модель одним файлом: docs/07-data/ddl/inventory-service.sql

-- ============================================================================
-- R2, inventory: остаток по API и возврат остатка при отмене (срез 1 Ф5, Р-183, Р-192)
-- ============================================================================
create table inventory.api_stock (
    product_id uuid        not null,
    quantity   integer     not null default 0,
    version    integer     not null default 1,
    updated_at timestamptz not null default now(),
    constraint pk_api_stock primary key (product_id),
    constraint fk_api_stock_product_id foreign key (product_id) references inventory.product_copy (product_id),
    -- INV-09: остаток не уходит в минус, резерв это условный UPDATE (quantity >= :n)
    constraint ck_api_stock_quantity check (quantity >= 0),
    constraint ck_api_stock_version check (version >= 1)
);
comment on table inventory.api_stock is 'Остаток товара с выдачей по API (R2, Р-183, ADR-008): сколько единиц продавец готов выдать сейчас. Резерв уменьшает остаток, снятие резерва возвращает, продавец правит значение по версии. Ключей в хранилище до оплаты нет';
comment on column inventory.api_stock.product_id is 'ProductID товара с issuance_method = seller_api, одна строка на товар, создаётся вместе с копией товара';
comment on column inventory.api_stock.quantity is 'Сколько единиц можно заказать сейчас: единицы в активных и использованных резервах уже вычтены. Не меньше нуля (INV-09)';
comment on column inventory.api_stock.version is 'Версия записи для ETag и If-Match в setApiStock (ADR-008), растёт на единицу при каждом изменении количества, она же версия события stock.changed этого товара. Не путать с product_copy.stock_version: та версия пула ключей';
comment on column inventory.api_stock.updated_at is 'Последнее изменение количества';

-- Остаток по API есть только у товара с выдачей по API (Р-184); версия растёт на единицу при каждом изменении
create function inventory.api_stock_guard() returns trigger
language plpgsql as
$fn$
begin
    if tg_op = 'INSERT' then
        if not exists (select 1 from inventory.product_copy where product_id = new.product_id and issuance_method = 'seller_api') then
            raise exception 'остаток по API бывает только у товара с выдачей по API' using errcode = '23514', constraint = 'ck_api_stock_issuance_method';
        end if;
        return new;
    end if;
    if new.product_id is distinct from old.product_id then
        raise exception 'товар остатка по API не меняется' using errcode = '23514', constraint = 'ck_api_stock_immutable';
    end if;
    new.version := old.version + 1;
    new.updated_at := now();
    return new;
end
$fn$;
create trigger trg_api_stock_guard before insert or update on inventory.api_stock
    for each row execute function inventory.api_stock_guard();
comment on function inventory.api_stock_guard() is 'Остаток по API бывает только у товара с выдачей по API (Р-184), товар строки неизменен, версия растёт на единицу при каждом изменении (Р-183)';

-- SM-08/T5 (использован > снят): продавец не ответил или вернул ошибку, остаток товара по API возвращается (Р-192)
drop trigger trg_reservation_status_transition on inventory.reservation;
create trigger trg_reservation_status_transition before update of status on inventory.reservation
    for each row when (old.status is distinct from new.status)
    execute function public.enforce_status_transition('active>used', 'active>released', 'used>released');
-- у снятого после использования резерва время использования остаётся: оно показывает, что оплата была
alter table inventory.reservation drop constraint ck_reservation_used_state;
alter table inventory.reservation add constraint ck_reservation_used_state check (
    (status <> 'used' or used_at is not null) and (status <> 'active' or used_at is null)
);

-- T5 только для товара по API и только пока ключ не выдан: выданный ключ в оборот не возвращается (INV-04)
create function inventory.reservation_used_release_guard() returns trigger
language plpgsql as
$fn$
begin
    if not exists (select 1 from inventory.product_copy where product_id = new.product_id and issuance_method = 'seller_api') then
        raise exception 'использованный резерв снимается только у товара с выдачей по API' using errcode = '23514', constraint = 'ck_reservation_used_release_api';
    end if;
    if exists (select 1 from inventory.key where reservation_id = new.id and status = 'issued') then
        raise exception 'по резерву уже выдан ключ, снять его нельзя' using errcode = '23514', constraint = 'ck_reservation_used_release_issued';
    end if;
    return new;
end
$fn$;
create trigger trg_reservation_used_release before update of status on inventory.reservation
    for each row when (old.status = 'used' and new.status = 'released')
    execute function inventory.reservation_used_release_guard();
comment on function inventory.reservation_used_release_guard() is 'SM-08/T5: использованный резерв снимается только у товара с выдачей по API и только пока по нему не выдан ключ (INV-04)';
