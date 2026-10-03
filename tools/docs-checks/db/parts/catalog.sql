-- ============================================================================
-- Схема seller_onboarding: подключение продавцов (SM-05)
-- ============================================================================
create schema seller_onboarding;
comment on schema seller_onboarding is 'Модуль seller_onboarding: профиль продавца и документы заявки. Своя роль app_seller_onboarding, чужих схем не читает';

create table seller_onboarding.seller_profile (
    id                    uuid        not null,
    user_id               uuid        not null,
    seller_type           text,
    name                  text,
    payout_recipient_name text,
    payout_bank_name      text,
    payout_bik            text,
    payout_account_number text,
    assortment_description text,
    decision_reason       text,
    last_rejected_at      timestamptz,
    submitted_at          timestamptz,
    review_deadline_at    timestamptz,
    overdue_marked_at     timestamptz,
    status                text        not null default 'draft',
    version               integer     not null default 1,
    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now(),
    constraint pk_seller_profile primary key (id),
    constraint uq_seller_profile_user_id unique (user_id),
    constraint ck_seller_profile_status check (status in ('draft', 'on_review', 'returned', 'approved', 'rejected', 'blocked')),
    constraint ck_seller_profile_seller_type check (seller_type in ('sole_proprietor', 'self_employed', 'legal_entity')),
    constraint ck_seller_profile_name_len check (char_length(name) between 1 and 200),
    constraint ck_seller_profile_payout_recipient_name_len check (char_length(payout_recipient_name) between 1 and 200),
    constraint ck_seller_profile_payout_bank_name_len check (char_length(payout_bank_name) between 1 and 200),
    constraint ck_seller_profile_payout_bik check (payout_bik ~ '^[0-9]{9}$'),
    constraint ck_seller_profile_payout_account_number check (payout_account_number ~ '^[0-9]{20}$'),
    constraint ck_seller_profile_assortment_len check (char_length(assortment_description) between 1 and 2000),
    constraint ck_seller_profile_version check (version >= 1),
    -- вне черновика анкета заполнена полностью (US-2.1: частично сохранять можно только черновик)
    constraint ck_seller_profile_complete check (
        status = 'draft'
        or (seller_type is not null and name is not null and payout_recipient_name is not null
            and payout_bank_name is not null and payout_bik is not null and payout_account_number is not null
            and assortment_description is not null)
    ),
    -- очередь модератора: срок рассмотрения записывается при постановке в очередь (FT-2.1)
    constraint ck_seller_profile_review_state check (
        status <> 'on_review' or (submitted_at is not null and review_deadline_at is not null)
    ),
    constraint ck_seller_profile_review_deadline check (review_deadline_at is null or submitted_at is null or review_deadline_at > submitted_at),
    -- отказ и доработка всегда объяснены (US-2.3)
    constraint ck_seller_profile_decision_reason check (
        status not in ('rejected', 'returned') or char_length(btrim(coalesce(decision_reason, ''))) > 0
    ),
    constraint ck_seller_profile_rejected_at check (status <> 'rejected' or last_rejected_at is not null)
);
comment on table seller_onboarding.seller_profile is 'Профиль продавца, один на пользователя (INV-39). Идентификатор профиля это SellerID во всех контекстах';
comment on column seller_onboarding.seller_profile.created_at is 'Время создания строки';
comment on column seller_onboarding.seller_profile.updated_at is 'Время последнего изменения строки';
comment on column seller_onboarding.seller_profile.id is 'SellerID';
comment on column seller_onboarding.seller_profile.user_id is 'Владелец профиля (sub из Keycloak), чужая база, внешнего ключа нет';
comment on column seller_onboarding.seller_profile.seller_type is 'ИП, самозанятый или юрлицо (conventions 7.3). Пусто, пока черновик не заполнен';
comment on column seller_onboarding.seller_profile.name is 'Наименование продавца';
comment on column seller_onboarding.seller_profile.payout_recipient_name is 'Реквизиты выплат: получатель платежа (часть атрибута «Реквизиты»)';
comment on column seller_onboarding.seller_profile.payout_bank_name is 'Реквизиты выплат: банк';
comment on column seller_onboarding.seller_profile.payout_bik is 'Реквизиты выплат: БИК, 9 цифр';
comment on column seller_onboarding.seller_profile.payout_account_number is 'Реквизиты выплат: расчётный счёт, 20 цифр';
comment on column seller_onboarding.seller_profile.assortment_description is 'Что продавец собирается продавать';
comment on column seller_onboarding.seller_profile.decision_reason is 'Причина отказа или комментарий доработки, в R2 причина блокировки';
comment on column seller_onboarding.seller_profile.last_rejected_at is 'Дата последнего отказа: от неё считается пауза перед новой заявкой (FT-2.4)';
comment on column seller_onboarding.seller_profile.submitted_at is 'Когда заявка поставлена в очередь модератора';
comment on column seller_onboarding.seller_profile.review_deadline_at is 'Срок рассмотрения: постановка в очередь плюс 3 суток (FT-2.1)';
comment on column seller_onboarding.seller_profile.overdue_marked_at is 'Когда задание контроля сроков пометило просрочку, помечается один раз (US-8.8)';
comment on column seller_onboarding.seller_profile.status is 'SM-05: draft, on_review, returned, approved, rejected, blocked';
comment on column seller_onboarding.seller_profile.version is 'Версия для ETag и If-Match при правке анкеты';

-- очередь модератора: на проверке, старые выше
create index ix_seller_profile_review_queue on seller_onboarding.seller_profile (submitted_at, id) where status = 'on_review';
comment on index seller_onboarding.ix_seller_profile_review_queue is 'GET /api/v1/staff/seller-applications (US-2.2): заявки on_review, старые выше';
-- задание контроля сроков: ещё не помеченные просроченные
create index ix_seller_profile_overdue on seller_onboarding.seller_profile (review_deadline_at) where status = 'on_review' and overdue_marked_at is null;
comment on index seller_onboarding.ix_seller_profile_overdue is 'Задание контроля сроков раз в 5 минут (US-8.8): заявки on_review с наступившим сроком без пометки';

create trigger trg_seller_profile_status_initial before insert on seller_onboarding.seller_profile
    for each row execute function public.enforce_status_transition('initial:draft');
create trigger trg_seller_profile_status_transition before update of status on seller_onboarding.seller_profile
    for each row when (old.status is distinct from new.status)
    execute function public.enforce_status_transition(
        'draft>on_review', 'on_review>approved', 'on_review>rejected', 'on_review>returned',
        'rejected>draft', 'returned>on_review');
-- R1: T1 .. T7. R2 добавит T8 и T9 (блокировка продавца) миграцией

create table seller_onboarding.seller_document (
    id           uuid        not null,
    seller_id    uuid        not null,
    object_key   text        not null,
    file_name    text        not null,
    content_type text        not null,
    size_bytes   bigint      not null,
    uploaded_at  timestamptz not null default now(),
    constraint pk_seller_document primary key (id),
    constraint fk_seller_document_seller_id foreign key (seller_id) references seller_onboarding.seller_profile (id),
    constraint uq_seller_document_object_key unique (object_key),
    constraint ck_seller_document_content_type check (content_type in ('application/pdf', 'image/jpeg', 'image/png')),
    constraint ck_seller_document_file_name_len check (char_length(file_name) between 1 and 255),
    constraint ck_seller_document_size check (size_bytes > 0 and size_bytes <= 10485760)
);
comment on table seller_onboarding.seller_document is 'Ссылка на документ заявки. Сам файл лежит в S3-хранилище в РФ (NFT-5.0), в базе только ключ объекта';
comment on column seller_onboarding.seller_document.id is 'Идентификатор документа';
comment on column seller_onboarding.seller_document.seller_id is 'Профиль продавца, которому принадлежит документ';
comment on column seller_onboarding.seller_document.file_name is 'Имя файла для показа модератору, не путь в хранилище';
comment on column seller_onboarding.seller_document.content_type is 'Тип содержимого файла, проверяется при загрузке';
comment on column seller_onboarding.seller_document.size_bytes is 'Размер файла в байтах, лимит проверяется при загрузке';
comment on column seller_onboarding.seller_document.uploaded_at is 'Время загрузки документа';
comment on column seller_onboarding.seller_document.object_key is 'Ключ объекта в бакете MinIO, ссылка выдаётся подписанной на 5 минут';
create index ix_seller_document_seller_id on seller_onboarding.seller_document (seller_id, uploaded_at);
comment on index seller_onboarding.ix_seller_document_seller_id is 'Документы заявки продавца: карточка заявки и список документов (US-2.1, US-2.2), а также проверка внешнего ключа';

-- ============================================================================
-- Схема catalog: товары и витрина (SM-04)
-- ============================================================================
create extension if not exists pg_trgm;
create schema catalog;
comment on schema catalog is 'Модуль catalog: товар, витрина, представление остатка. Своя роль app_catalog';

create table catalog.product (
    id                 uuid        not null,
    seller_id          uuid        not null,
    title              text        not null,
    description        text        not null,
    product_type       text        not null,
    platform           text        not null,
    activation_regions text[]      not null default '{}',
    price              bigint      not null,
    currency           char(3)     not null default 'RUB',
    issuance_method    text        not null default 'key_pool',
    rejection_reason   text,
    status_before_block text,
    block_reason       text,
    submitted_at       timestamptz,
    review_deadline_at timestamptz,
    overdue_marked_at  timestamptz,
    published_at       timestamptz,
    status             text        not null default 'draft',
    version            integer     not null default 1,
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now(),
    constraint pk_product primary key (id),
    constraint ck_product_status check (status in ('draft', 'on_moderation', 'published', 'rejected', 'blocked', 'archived')),
    constraint ck_product_product_type check (product_type in ('game_key', 'software_license', 'gift_card', 'subscription')),
    constraint ck_product_issuance_method check (issuance_method in ('key_pool', 'seller_api')),
    constraint ck_product_title_len check (char_length(title) between 1 and 200),
    constraint ck_product_description_len check (char_length(description) between 1 and 5000),
    constraint ck_product_platform_len check (char_length(platform) between 1 and 64),
    constraint ck_product_activation_regions check (
        cardinality(activation_regions) <= 250
        and array_to_string(activation_regions, ',') ~ '^([A-Z]{2}(,[A-Z]{2})*)?$'
    ),
    constraint ck_product_price check (price > 0 and price <= 9007199254740991),
    constraint ck_product_currency check (currency = 'RUB'),
    constraint ck_product_version check (version >= 1),
    -- INV-40: у блокированного товара заполнены «статус до блокировки» и «причина блокировки», у остальных они пусты
    constraint ck_product_block_state check (
        (status = 'blocked' and status_before_block is not null and block_reason is not null)
        or (status <> 'blocked' and status_before_block is null and block_reason is null)
    ),
    constraint ck_product_status_before_block check (status_before_block in ('published', 'on_moderation')),
    constraint ck_product_block_reason check (block_reason in ('moderator_decision', 'seller_blocked')),
    constraint ck_product_moderation_state check (
        status <> 'on_moderation' or (submitted_at is not null and review_deadline_at is not null)
    ),
    constraint ck_product_review_deadline check (review_deadline_at is null or submitted_at is null or review_deadline_at > submitted_at),
    constraint ck_product_published_state check (status <> 'published' or published_at is not null),
    constraint ck_product_rejection_reason check (
        status <> 'rejected' or char_length(btrim(coalesce(rejection_reason, ''))) > 0
    )
);
comment on table catalog.product is 'Товар. На витрине только published (INV-40). Цена в заказе фиксируется снимком';
comment on column catalog.product.id is 'ProductID';
comment on column catalog.product.seller_id is 'SellerID (профиль продавца). Внешнего ключа на seller_onboarding нет: модули связаны только идентификатором';
comment on column catalog.product.title is 'Название. Правка опубликованного товара возвращает его на модерацию (SM-04/T6)';
comment on column catalog.product.description is 'Описание';
comment on column catalog.product.product_type is 'game_key, software_license, gift_card, subscription (conventions 7.3)';
comment on column catalog.product.platform is 'Платформа или сервис активации';
comment on column catalog.product.activation_regions is 'Регионы активации, коды ISO 3166-1 alpha-2. Пустой массив значит «без ограничений». Фильтр витрины «с этим регионом и без ограничений» читается по порядку публикации: отдельный индекс по регионам планировщик не выбирает, потому что товары без ограничений составляют заметную долю выборки (проверка EXPLAIN)';
comment on column catalog.product.price is 'Цена за единицу в копейках, положительная';
comment on column catalog.product.currency is 'Валюта, в R1 только RUB (conventions 4.3)';
comment on column catalog.product.issuance_method is 'key_pool в R1, seller_api в R2';
comment on column catalog.product.rejection_reason is 'Причина отклонения, видна продавцу (FT-11.4)';
comment on column catalog.product.status_before_block is 'Статус до блокировки: нужен, чтобы вернуть товар после снятия блокировки продавца (SM-04, решение 1)';
comment on column catalog.product.block_reason is 'moderator_decision или seller_blocked (SM-04, решение 1)';
comment on column catalog.product.submitted_at is 'Когда товар поставлен в очередь модерации';
comment on column catalog.product.review_deadline_at is 'Срок рассмотрения: постановка в очередь плюс 3 суток (FT-3.2)';
comment on column catalog.product.overdue_marked_at is 'Когда задание контроля сроков пометило просрочку, один раз (US-8.8)';
comment on column catalog.product.published_at is 'Последняя публикация: порядок витрины «новые выше»';
comment on column catalog.product.status is 'SM-04: draft, on_moderation, published, rejected, blocked, archived';
comment on column catalog.product.version is 'Версия для ETag и If-Match при правке (PUT)';
comment on column catalog.product.created_at is 'Время создания: порядок списка товаров продавца (US-3.1)';
comment on column catalog.product.updated_at is 'Время последнего изменения строки';

-- витрина без фильтров: новые публикации выше (US-3.9)
create index ix_product_published on catalog.product (published_at desc, id desc) where status = 'published';
comment on index catalog.ix_product_published is 'GET /api/v1/products без фильтров (US-3.9): порядок «новые публикации выше», курсор по (published_at, id)';
-- фильтр по типу
create index ix_product_published_type on catalog.product (product_type, published_at desc, id desc) where status = 'published';
comment on index catalog.ix_product_published_type is 'GET /api/v1/products?productType= (US-3.10): фильтр по типу с тем же порядком';
-- фильтр по платформе
create index ix_product_published_platform on catalog.product (platform, published_at desc, id desc) where status = 'published';
comment on index catalog.ix_product_published_platform is 'GET /api/v1/products?platform= (US-3.10): фильтр по платформе с тем же порядком';
-- фильтр по цене
create index ix_product_published_price on catalog.product (price, id) where status = 'published';
comment on index catalog.ix_product_published_price is 'GET /api/v1/products?priceFrom=&priceTo= (US-3.11): диапазон цены';
-- фильтр по региону: либо без ограничений, либо регион в списке
-- поиск по части названия без учёта регистра
create index ix_product_published_title_trgm on catalog.product using gin (lower(title) gin_trgm_ops) where status = 'published';
comment on index catalog.ix_product_published_title_trgm is 'GET /api/v1/products?q= (US-3.9): часть названия без учёта регистра, триграммы';
-- кабинет продавца: свои товары, новые выше
create index ix_product_seller_id on catalog.product (seller_id, created_at desc, id desc);
comment on index catalog.ix_product_seller_id is 'GET /api/v1/seller/products (US-3.1): товары продавца, новые выше, фильтр status поверх';
-- очередь модерации
create index ix_product_moderation_queue on catalog.product (submitted_at, id) where status = 'on_moderation';
comment on index catalog.ix_product_moderation_queue is 'GET /api/v1/staff/products (US-3.3): очередь on_moderation, старые выше';
-- контроль сроков модерации
create index ix_product_overdue on catalog.product (review_deadline_at) where status = 'on_moderation' and overdue_marked_at is null;
comment on index catalog.ix_product_overdue is 'Задание контроля сроков раз в 5 минут (US-8.8): товары on_moderation с наступившим сроком без пометки';

create trigger trg_product_status_initial before insert on catalog.product
    for each row execute function public.enforce_status_transition('initial:draft');
create trigger trg_product_status_transition before update of status on catalog.product
    for each row when (old.status is distinct from new.status)
    execute function public.enforce_status_transition(
        'draft>on_moderation', 'on_moderation>published', 'on_moderation>rejected', 'rejected>on_moderation',
        'published>on_moderation', 'published>blocked', 'blocked>on_moderation', 'draft>archived', 'on_moderation>archived',
        'published>archived', 'rejected>archived', 'archived>draft');
-- R1: T2 .. T8, T11, T12, T13. R2 добавит T9 и T10 (блокировка продавца) миграцией

-- Представление остатка для витрины: копия числа свободных ключей, обновляется событием stock.changed (NFT-1.0)
create table catalog.stock_view (
    product_id    uuid        not null,
    available     integer     not null,
    in_stock      boolean     generated always as (available > 0) stored,
    last_event_at timestamptz not null,
    updated_at    timestamptz not null default now(),
    constraint pk_stock_view primary key (product_id),
    constraint fk_stock_view_product_id foreign key (product_id) references catalog.product (id),
    constraint ck_stock_view_available check (available >= 0)
);
comment on table catalog.stock_view is 'Копия остатка для витрины и карточки товара. Источник истины: inventory-service (INV-09)';
comment on column catalog.stock_view.product_id is 'Товар, к которому относится остаток. Связь внутри модуля, одна строка на товар';
comment on column catalog.stock_view.updated_at is 'Время последнего изменения строки';
comment on column catalog.stock_view.available is 'Число свободных ключей из последнего применённого события stock.changed';
comment on column catalog.stock_view.in_stock is 'Признак наличия: available больше нуля';
comment on column catalog.stock_view.last_event_at is 'Время последнего применённого события: более раннее событие после сбоя порядка игнорируется';

-- ============================================================================
-- Роли и права (Flyway выдаёт их в миграциях, правило модульности 2)
-- ============================================================================
do $$
begin
    if not exists (select from pg_roles where rolname = 'app_seller_onboarding') then
        create role app_seller_onboarding nologin;
    end if;
    if not exists (select from pg_roles where rolname = 'app_catalog') then
        create role app_catalog nologin;
    end if;
end
$$;

grant usage on schema seller_onboarding to app_seller_onboarding;
grant select, insert, update on seller_onboarding.seller_profile to app_seller_onboarding;
grant select, insert, update, delete on seller_onboarding.seller_document to app_seller_onboarding;

grant usage on schema catalog to app_catalog;
grant select, insert, update on catalog.product, catalog.stock_view to app_catalog;

grant usage on schema public to app_seller_onboarding, app_catalog;
grant select, insert, update, delete on public.outbox, public.processed_event, public.idempotency_key
    to app_seller_onboarding, app_catalog;
grant usage on all sequences in schema public to app_seller_onboarding, app_catalog;
