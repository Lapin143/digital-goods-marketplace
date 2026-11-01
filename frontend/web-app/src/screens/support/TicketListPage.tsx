import { Link } from 'react-router-dom';
import { problemMessage } from '../../api/problem';
import { useBuyerScope } from '../../app/SessionScope';
import { t } from '../../i18n';
import { Badge } from '../../ui/Badge';
import { Button } from '../../ui/Button';
import { formatMoment } from '../../ui/format';
import { StatePanel } from '../../ui/StatePanel';
import { Table } from '../../ui/Table';
import type { Column } from '../../ui/Table';
import { ticketReasonLabel, ticketStatusLabel, ticketStatusTone } from './labels';
import type { SupportTicket } from './types';
import { useTicketList } from './useSupport';
import styles from './Support.module.css';

/** Мои обращения (US-7.1): новые выше, подгрузка по курсору, переход к карточке, пустое состояние. Ключей в списке нет. */
export function TicketListPage() {
  const { base } = useBuyerScope();
  const list = useTicketList();
  const tickets = list.data?.pages.flatMap((page) => page.items) ?? [];

  const columns: Array<Column<SupportTicket>> = [
    {
      key: 'created',
      header: t('tickets.col.created'),
      render: (ticket) => <Link to={`${base}/tickets/${encodeURIComponent(ticket.ticketId)}`}>{formatMoment(ticket.createdAt)}</Link>,
    },
    { key: 'reason', header: t('tickets.col.reason'), render: (ticket) => ticketReasonLabel(ticket.reason) },
    {
      key: 'status',
      header: t('tickets.col.status'),
      render: (ticket) => <Badge tone={ticketStatusTone(ticket.status)}>{ticketStatusLabel(ticket.status)}</Badge>,
    },
  ];

  return (
    <section>
      <h1>{t('tickets.title')}</h1>
      {list.isPending ? <StatePanel kind="loading" /> : null}
      {list.isError && !list.data ? <StatePanel kind="error" message={problemMessage(list.error)} onRetry={() => void list.refetch()} /> : null}
      {list.data && tickets.length === 0 ? (
        <>
          <StatePanel kind="empty" message={t('tickets.empty')} />
          <p>
            <Link to={`${base}/orders`}>{t('tickets.empty.link')}</Link>
          </p>
        </>
      ) : null}
      {tickets.length > 0 ? (
        <>
          <Table caption={t('tickets.caption')} columns={columns} rows={tickets} rowKey={(ticket) => ticket.ticketId} />
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
