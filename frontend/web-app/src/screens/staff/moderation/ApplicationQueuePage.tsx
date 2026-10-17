import { Link } from 'react-router-dom';
import { t } from '../../../i18n';
import { Badge } from '../../../ui/Badge';
import type { Column } from '../../../ui/Table';
import { formatMoment } from '../../../ui/format';
import { sellerTypeLabel } from '../../seller/labels';
import { QueueList } from './QueueList';
import type { ApplicationQueueItem } from './types';
import { useQueue } from './useModeration';

/** Очередь заявок продавцов (OP-11): старые выше, просроченные (больше 3 суток) помечены. */
export function ApplicationQueuePage() {
  const query = useQueue<ApplicationQueueItem>('applications', '/api/v1/staff/seller-applications');
  const columns: Array<Column<ApplicationQueueItem>> = [
    {
      key: 'name',
      header: t('moderation.col.seller'),
      render: (row) => <Link to={`/staff/applications/${encodeURIComponent(row.sellerId)}`}>{row.name}</Link>,
    },
    { key: 'type', header: t('moderation.col.sellerType'), render: (row) => sellerTypeLabel(row.sellerType) },
    { key: 'documents', header: t('moderation.col.documents'), render: (row) => row.documentsCount },
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
      <h1>{t('moderation.applications.title')}</h1>
      <QueueList query={query} caption={t('moderation.applications.list')} columns={columns} rowKey={(row) => row.sellerId} emptyText={t('moderation.applications.empty')} />
    </section>
  );
}
