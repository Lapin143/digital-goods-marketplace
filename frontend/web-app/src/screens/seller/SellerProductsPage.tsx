import { useInfiniteQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useApi } from '../../api/ApiContext';
import { problemMessage } from '../../api/problem';
import { plural, t } from '../../i18n';
import { Badge } from '../../ui/Badge';
import { Button } from '../../ui/Button';
import { SelectField } from '../../ui/SelectField';
import { StatePanel } from '../../ui/StatePanel';
import { Table } from '../../ui/Table';
import type { Column } from '../../ui/Table';
import { formatMoment } from '../../ui/format';
import { productTypeLabel } from '../storefront/labels';
import { formatMoney } from '../storefront/money';
import { productStatusLabel, productStatusTone } from './labels';
import type { SellerProduct, SellerProductPage } from './types';
import styles from './SellerProducts.module.css';

const STATUSES = ['draft', 'on_moderation', 'published', 'rejected', 'blocked', 'archived'] as const;
const PAGE_SIZE = 20;

/** Товары продавца: список страницами по курсору с фильтром по статусу (OP-20), ссылка на создание и на правку. */
export function SellerProductsPage() {
  const api = useApi();
  const [status, setStatus] = useState('');
  const list = useInfiniteQuery({
    queryKey: ['seller-products', status],
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam, signal }) => {
      const response = await api.get<SellerProductPage>('/api/v1/seller/products', {
        auth: 'staff',
        query: { status: status === '' ? undefined : status, limit: PAGE_SIZE, cursor: pageParam },
        signal,
      });
      return response.data;
    },
    getNextPageParam: (last) => last.page.nextCursor ?? undefined,
  });
  const items: SellerProduct[] = list.data?.pages.flatMap((page) => page.items) ?? [];

  const columns: Array<Column<SellerProduct>> = [
    {
      key: 'title',
      header: t('seller.col.title'),
      render: (row) => <Link to={`/seller/products/${encodeURIComponent(row.productId)}`}>{row.title}</Link>,
    },
    { key: 'type', header: t('seller.col.type'), render: (row) => productTypeLabel(row.productType) },
    { key: 'price', header: t('seller.col.price'), render: (row) => formatMoney(row.price) },
    {
      key: 'status',
      header: t('seller.col.status'),
      render: (row) => <Badge tone={productStatusTone(row.status)}>{productStatusLabel(row.status)}</Badge>,
    },
    { key: 'updated', header: t('seller.col.updated'), render: (row) => formatMoment(row.updatedAt) },
  ];

  return (
    <section>
      <h1>{t('seller.products.title')}</h1>
      <div className={styles.toolbar}>
        <div className={styles.filter}>
          <SelectField
            label={t('seller.products.filter')}
            placeholder={t('seller.products.all')}
            options={STATUSES.map((value) => ({ value, label: productStatusLabel(value) }))}
            value={status}
            onChange={(event) => setStatus(event.target.value)}
          />
        </div>
        <p>
          <Link to="/seller/products/new">{t('seller.products.new')}</Link>
        </p>
      </div>

      <div aria-live="polite" aria-busy={list.isFetching}>
        {list.isPending ? <StatePanel kind="loading" /> : null}
        {list.isError && !list.data ? <StatePanel kind="error" message={problemMessage(list.error)} onRetry={() => void list.refetch()} /> : null}
        {list.data && items.length === 0 ? <StatePanel kind="empty" message={status === '' ? t('seller.products.empty') : t('seller.products.emptyFiltered')} /> : null}
        {items.length > 0 ? (
          <>
            <p>{plural(items.length, { one: 'seller.products.count.one', few: 'seller.products.count.few', many: 'seller.products.count.many' })}</p>
            <Table caption={t('seller.products.list')} columns={columns} rows={items} rowKey={(row) => row.productId} />
            {list.isFetchNextPageError ? <StatePanel kind="error" message={problemMessage(list.error)} onRetry={() => void list.fetchNextPage()} /> : null}
            {list.hasNextPage && !list.isFetchNextPageError ? (
              <Button variant="secondary" busy={list.isFetchingNextPage} onClick={() => void list.fetchNextPage()}>
                {t('catalog.more')}
              </Button>
            ) : null}
          </>
        ) : null}
      </div>
    </section>
  );
}
