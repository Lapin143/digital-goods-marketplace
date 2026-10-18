-- Засев каталога для нагрузочного ориентира NFT-1.0 и NFT-1.1 (шаг 21 Ф4).
--
--   make load-seed                       5000 опубликованных товаров, 100 продавцов по 50 товаров (принято в методике NFR)
--   make load-seed PRODUCTS=20000        другое число товаров
--
-- Запускается от суперпользователя базы, потому что товары вставляются сразу опубликованными: триггер переходов статуса обычной роли
-- разрешает только вставку черновика. Вставка идёт с session_replication_role = replica (триггеры не срабатывают), все ограничения CHECK
-- остаются. Строки узнаются по префиксу названия «[load] » и по идентификаторам из md5, повторный запуск сначала их удаляет.
-- Продавцов в таблице профилей нет: у товара seller_id это идентификатор без внешнего ключа, карточка и список продавца не читают.
\set ON_ERROR_STOP on
\if :{?products}
\else
\set products 5000
\endif
\if :{?sellers}
\else
\set sellers 100
\endif

begin;
set local session_replication_role = replica;

delete from catalog.stock_view where product_id in (select id from catalog.product where title like '[load] %');
delete from catalog.product where title like '[load] %';

insert into catalog.product (id, seller_id, title, description, product_type, platform, activation_regions, price, currency,
                             issuance_method, status, published_at, version, created_at, updated_at)
select md5('load-product-' || n)::uuid,
       md5('load-seller-' || (n % :sellers))::uuid,
       '[load] ' || (array['Сияющий','Тёмный','Забытый','Северный','Хрустальный','Багровый','Ледяной','Золотой','Тихий','Дикий'])[1 + n % 10]
           || ' ' || (array['рубеж','лес','город','клинок','океан','архив','орден','маяк','каньон','проход'])[1 + (n / 10) % 10]
           || ' ' || lpad(n::text, 5, '0') || ': ' || (array['ключ','лицензия','подарочная карта','подписка'])[1 + (case when n % 20 < 12 then 0 when n % 20 < 16 then 1 when n % 20 < 19 then 2 else 3 end)]
           || ' ' || (array['Steam','Epic Games','PlayStation','Xbox','Nintendo','GOG','Microsoft','Adobe'])[1 + n % 8],
       'Товар нагрузочного засева ' || n || '. Описание нужной длины, чтобы тело карточки было похоже на настоящее: условия активации, '
           || 'ограничения по региону и сроку действия, порядок получения ключа после оплаты.',
       (array['game_key','software_license','gift_card','subscription'])[1 + (case when n % 20 < 12 then 0 when n % 20 < 16 then 1 when n % 20 < 19 then 2 else 3 end)],
       (array['Steam','Epic Games','PlayStation','Xbox','Nintendo','GOG','Microsoft','Adobe'])[1 + n % 8],
       case when n % 5 < 3 then '{}'::text[] when n % 5 = 3 then array['RU','KZ','BY'] else array['RU'] end,
       9900 + (n::bigint * 7919) % 490000,
       'RUB', 'key_pool', 'published',
       now() - (n % 90) * interval '1 day' - (n % 1440) * interval '1 minute',
       1,
       now() - interval '100 days', now() - interval '100 days'
from generate_series(1, :products) as n;

insert into catalog.stock_view (product_id, available, last_event_at, updated_at)
select id, (abs(hashtext(id::text)) % 200), now(), now() from catalog.product where title like '[load] %';

commit;
analyze catalog.product;
analyze catalog.stock_view;
select count(*) as "опубликованных товаров засева" from catalog.product where title like '[load] %' and status = 'published';
