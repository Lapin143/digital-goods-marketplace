import { Link } from 'react-router-dom';
import { problemMessage } from '../../api/problem';
import { t } from '../../i18n';
import { Badge } from '../../ui/Badge';
import { Button } from '../../ui/Button';
import { formatMoment } from '../../ui/format';
import { StatePanel } from '../../ui/StatePanel';
import { Table } from '../../ui/Table';
import type { Column } from '../../ui/Table';
import { formatMoney } from '../storefront/money';
import { orderStatusLabel, orderStatusTone } from './labels';
import type { Order } from './types';
import { useOrderList } from './useOrders';
import styles from './Orders.module.css';

const COLUMNS: Array<Column<Order>> = [
  {
    key: 'number',
    header: t('orders.col.number'),
    render: (order) => <Link to={`/account/orders/${encodeURIComponent(order.orderId)}`}>{order.orderNumber}</Link>,
  },
  {
    key: 'product',
    header: t('orders.col.product'),
    render: (order) => `${order.productTitle}, ${t('orders.quantity', { count: order.quantity })}`,
  },
  { key: 'total', header: t('orders.col.total'), render: (order) => formatMoney(order.total) },
  { key: 'created', header: t('orders.col.created'), render: (order) => formatMoment(order.createdAt) },
  {
    key: 'status',
    header: t('orders.col.status'),
    render: (order) => <Badge tone={orderStatusTone(order.status)}>{orderStatusLabel(order.status)}</Badge>,
  },
];

/** История заказов покупателя (US-5.9): новые выше, подгрузка по курсору, переход к заказу, пустое состояние. Значений ключей в списке нет. */
export function OrderHistoryPage() {
  const list = useOrderList();
  const orders = list.data?.pages.flatMap((page) => page.items) ?? [];

  return (
    <section>
      <h1>{t('orders.title')}</h1>
      {list.isPending ? <StatePanel kind="loading" /> : null}
      {list.isError && !list.data ? <StatePanel kind="error" message={problemMessage(list.error)} onRetry={() => void list.refetch()} /> : null}
      {list.data && orders.length === 0 ? (
        <>
          <StatePanel kind="empty" message={t('orders.empty')} />
          <p>
            <Link to="/products">{t('orders.empty.link')}</Link>
          </p>
        </>
      ) : null}
      {orders.length > 0 ? (
        <>
          <Table caption={t('orders.caption')} columns={COLUMNS} rows={orders} rowKey={(order) => order.orderId} />
          {list.isFetchNextPageError ? <StatePanel kind="error" message={problemMessage(list.error)} onRetry={() => void list.fetchNextPage()} /> : null}
          {list.hasNextPage && !list.isFetchNextPageError ? (
            <div className={styles.more}>
              <Button variant="secondary" busy={list.isFetchingNextPage} onClick={() => void list.fetchNextPage()}>
                {t('catalog.more')}
              </Button>
            </div>
          ) : null}
        </>
      ) : null}
    </section>
  );
}
