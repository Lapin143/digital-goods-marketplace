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
import { TextField } from '../../ui/TextField';
import { formatMoment } from '../../ui/format';
import { orderStatusLabel, orderStatusTone } from '../orders/labels';
import { formatMoney } from '../storefront/money';
import { issuanceMethodLabel } from './labels';
import { dayStartIso, nextDayStartIso, orderNote } from './sellerOrders';
import type { SellerOrder, SellerOrderPage } from './types';
import styles from './SellerProducts.module.css';

const STATUSES = ['created', 'awaiting_payment', 'paid', 'issued', 'cancelled', 'refunded'] as const;
const PAGE_SIZE = 20;

/**
 * Заказы продавца (US-11.2): таблица страницами по курсору с фильтром по статусу и датам создания. Покупателя, его e-mail
 * и адрес сервер не отдаёт (INV-44), экран их не показывает. Выбранный день входит в выборку целиком: верхняя граница
 * уходит как начало следующих суток, потому что `createdTo` границу не включает.
 */
export function SellerOrdersPage() {
  const api = useApi();
  const [status, setStatus] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const rangeInvalid = from !== '' && to !== '' && from > to;

  const list = useInfiniteQuery({
    queryKey: ['seller-orders', status, from, to],
    enabled: !rangeInvalid,
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam, signal }) => {
      const response = await api.get<SellerOrderPage>('/api/v1/seller/orders', {
        auth: 'staff',
        query: {
          status: status === '' ? undefined : status,
          createdFrom: from === '' ? undefined : dayStartIso(from),
          createdTo: to === '' ? undefined : nextDayStartIso(to),
          limit: PAGE_SIZE,
          cursor: pageParam,
        },
        signal,
      });
      return response.data;
    },
    getNextPageParam: (last) => last.page.nextCursor ?? undefined,
  });
  const items: SellerOrder[] = list.data?.pages.flatMap((page) => page.items) ?? [];
  const filtered = status !== '' || from !== '' || to !== '';

  const columns: Array<Column<SellerOrder>> = [
    { key: 'number', header: t('sellerOrders.col.number'), render: (row) => row.orderNumber },
    { key: 'created', header: t('sellerOrders.col.created'), render: (row) => formatMoment(row.createdAt) },
    { key: 'product', header: t('sellerOrders.col.product'), render: (row) => row.productTitle },
    { key: 'quantity', header: t('sellerOrders.col.quantity'), render: (row) => row.quantity },
    { key: 'total', header: t('sellerOrders.col.total'), render: (row) => formatMoney(row.total) },
    { key: 'commission', header: t('sellerOrders.col.commission'), render: (row) => formatMoney(row.commission) },
    {
      key: 'status',
      header: t('sellerOrders.col.status'),
      render: (row) => {
        const note = orderNote(row);
        return (
          <>
            <Badge tone={orderStatusTone(row.status)}>{orderStatusLabel(row.status)}</Badge>
            {note ? <small className={styles.cellNote}>{note}</small> : null}
          </>
        );
      },
    },
    { key: 'issuance', header: t('sellerOrders.col.issuance'), render: (row) => issuanceMethodLabel(row.issuanceMethod) },
  ];

  return (
    <section>
      <p>
        <Link to="/seller">{t('seller.cabinet.back')}</Link>
      </p>
      <h1>{t('sellerOrders.title')}</h1>
      <p>{t('sellerOrders.lead')}</p>
      <div className={styles.toolbar}>
        <div className={styles.filter}>
          <SelectField
            label={t('sellerOrders.filter.status')}
            placeholder={t('sellerOrders.filter.allStatuses')}
            options={STATUSES.map((value) => ({ value, label: orderStatusLabel(value) }))}
            value={status}
            onChange={(event) => setStatus(event.target.value)}
          />
        </div>
        <div className={styles.dates}>
          <TextField label={t('sellerOrders.filter.from')} type="date" value={from} onChange={(event) => setFrom(event.target.value)} />
          <TextField
            label={t('sellerOrders.filter.to')}
            type="date"
            value={to}
            error={rangeInvalid ? t('sellerOrders.error.range') : undefined}
            onChange={(event) => setTo(event.target.value)}
          />
        </div>
      </div>

      <div aria-live="polite" aria-busy={list.isFetching}>
        {!rangeInvalid && list.isPending ? <StatePanel kind="loading" /> : null}
        {list.isError && !list.data ? <StatePanel kind="error" message={problemMessage(list.error)} onRetry={() => void list.refetch()} /> : null}
        {list.data && items.length === 0 ? <StatePanel kind="empty" message={filtered ? t('sellerOrders.emptyFiltered') : t('sellerOrders.empty')} /> : null}
        {items.length > 0 ? (
          <>
            <p>{plural(items.length, { one: 'sellerOrders.count.one', few: 'sellerOrders.count.few', many: 'sellerOrders.count.many' })}</p>
            <Table caption={t('sellerOrders.caption')} columns={columns} rows={items} rowKey={(row) => row.orderId} />
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
