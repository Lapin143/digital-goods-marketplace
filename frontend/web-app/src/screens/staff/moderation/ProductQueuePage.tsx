import { Link } from 'react-router-dom';
import { t } from '../../../i18n';
import { Badge } from '../../../ui/Badge';
import type { Column } from '../../../ui/Table';
import { formatMoment } from '../../../ui/format';
import { productTypeLabel } from '../../storefront/labels';
import { formatMoney } from '../../storefront/money';
import { QueueList } from './QueueList';
import type { ProductQueueItem } from './types';
import { useQueue } from './useModeration';

/** Очередь товаров на модерации (OP-22): старые выше, просроченные помечены. */
export function ProductQueuePage() {
  const query = useQueue<ProductQueueItem>('products', '/api/v1/staff/products');
  const columns: Array<Column<ProductQueueItem>> = [
    {
      key: 'title',
      header: t('moderation.col.product'),
      render: (row) => <Link to={`/staff/products/${encodeURIComponent(row.productId)}`}>{row.title}</Link>,
    },
    { key: 'type', header: t('moderation.col.productType'), render: (row) => productTypeLabel(row.productType) },
    { key: 'price', header: t('moderation.col.price'), render: (row) => formatMoney(row.price) },
    { key: 'submitted', header: t('moderation.col.submitted'), render: (row) => formatMoment(row.submittedAt) },
    {
      key: 'deadline',
      header: t('moderation.col.deadline'),
      render: (row) => (
        <>
          {formatMoment(row.reviewDeadlineAt)} {row.overdue ? <Badge tone="bad">{t('moderation.overdue')}</Badge> : null}
        </>
      ),
    },
  ];
  return (
    <section>
      <p>
        <Link to="/staff">{t('moderation.back')}</Link>
      </p>
      <h1>{t('moderation.products.title')}</h1>
      <QueueList query={query} caption={t('moderation.products.list')} columns={columns} rowKey={(row) => row.productId} emptyText={t('moderation.products.empty')} />
    </section>
  );
}
