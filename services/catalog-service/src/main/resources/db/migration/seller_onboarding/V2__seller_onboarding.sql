-- СОЗДАН СКРИПТОМ tools/docs-checks/db/gen_migrations.py ИЗ tools/docs-checks/db/parts/catalog.sql. РУКАМИ НЕ ПРАВИТЬ.
-- Сервис catalog-service, база catalog_db. Миграция V2, схема seller_onboarding.
-- Применённую миграцию править нельзя: Flyway сверяет контрольную сумму, правка вызовет ошибку проверки при старте.
-- Полная модель одним файлом: docs/07-data/ddl/catalog-service.sql

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
