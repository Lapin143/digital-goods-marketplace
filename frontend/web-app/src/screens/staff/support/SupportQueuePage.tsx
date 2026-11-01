import { useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router-dom';
import { useApi } from '../../../api/ApiContext';
import { ApiError, problemMessage } from '../../../api/problem';
import { useRequestKeys } from '../../../api/requestKey';
import { staffTicketActionPath } from '../../../api/support';
import { useAuth } from '../../../auth/AuthContext';
import { plural, t } from '../../../i18n';
import { Badge } from '../../../ui/Badge';
import { Button } from '../../../ui/Button';
import { formatMoment } from '../../../ui/format';
import { Notice } from '../../../ui/Notice';
import { SelectField } from '../../../ui/SelectField';
import { StatePanel } from '../../../ui/StatePanel';
import { Table } from '../../../ui/Table';
import type { Column } from '../../../ui/Table';
import { TextField } from '../../../ui/TextField';
import { ticketReasonLabel } from '../../support/labels';
import { deliveryStatusLabel, deliveryStatusTone, parseOrderNumber, sourceLabel, ticketStatusLabel, ticketStatusTone } from './labels';
import type { StaffSupportTicket } from './types';
import { SUPPORT_QUEUE_KEY, useSupportQueue } from './useStaffSupport';
import type { QueueFilter } from './useStaffSupport';
import styles from './StaffSupport.module.css';

/**
 * Очередь обращений оператора (US-7.2, OP-71): обращения двух источников, старые выше; фильтры «очередь» и «мои», поиск по короткому номеру заказа,
 * который называет покупатель; счётчик; обновление по таймеру. «Взять в работу» открывает карточку; если обращение уже взял другой оператор
 * (409), экран говорит «Обращение уже взято» и обновляет очередь. Значений ключей и адресов в очереди нет. Пустой поиск ничего не подсказывает.
 */
export function SupportQueuePage() {
  const api = useApi();
  const auth = useAuth();
  const keys = useRequestKeys();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const operatorId = auth.session('staff')?.claims.subject ?? '';
  const [filter, setFilter] = useState<QueueFilter>('queue');
  const [searchText, setSearchText] = useState('');
  const [orderNumber, setOrderNumber] = useState<number | undefined>();
  const [searchError, setSearchError] = useState<string | undefined>();
  const inFlight = useRef(false);
  const [taking, setTaking] = useState<string | undefined>();
  const [takeError, setTakeError] = useState<string | undefined>();
  const query = useSupportQueue({ filter, orderNumber, operatorId });
  const tickets = query.data?.pages.flatMap((page) => page.items) ?? [];

  function search(event: FormEvent) {
    event.preventDefault();
    if (searchText.trim() === '') {
      setOrderNumber(undefined);
      setSearchError(undefined);
      return;
    }
    const parsed = parseOrderNumber(searchText);
    if (parsed === undefined) {
      setSearchError(t('supportQueue.search.error'));
      return;
    }
    setSearchError(undefined);
    setOrderNumber(parsed);
  }

  function resetSearch() {
    setSearchText('');
    setSearchError(undefined);
    setOrderNumber(undefined);
  }

  async function take(ticket: StaffSupportTicket) {
    if (inFlight.current) {
      return;
    }
    inFlight.current = true;
    setTaking(ticket.ticketId);
    setTakeError(undefined);
    try {
      await api.post(staffTicketActionPath(ticket.ticketId, 'take'), undefined, { auth: 'staff', idempotencyKey: keys.keyFor(`take:${ticket.ticketId}`) });
      keys.settle();
      void queryClient.invalidateQueries({ queryKey: SUPPORT_QUEUE_KEY });
      navigate(`/staff/support/${encodeURIComponent(ticket.ticketId)}`);
    } catch (failure) {
      keys.settle(failure);
      if (failure instanceof ApiError && failure.status === 409 && failure.code === 'state-conflict') {
        setTakeError(t('supportQueue.alreadyTaken'));
        void query.refetch();
      } else {
        setTakeError(problemMessage(failure));
      }
    } finally {
      inFlight.current = false;
      setTaking(undefined);
    }
  }

  const columns: Array<Column<StaffSupportTicket>> = [
    { key: 'order', header: t('supportQueue.col.order'), render: (ticket) => <Link to={`/staff/support/${encodeURIComponent(ticket.ticketId)}`}>{ticket.orderNumber}</Link> },
    { key: 'source', header: t('supportQueue.col.source'), render: (ticket) => sourceLabel(ticket.source) },
    { key: 'reason', header: t('supportQueue.col.reason'), render: (ticket) => ticketReasonLabel(ticket.reason) },
    {
      key: 'delivery',
      header: t('supportQueue.col.delivery'),
      render: (ticket) => <Badge tone={deliveryStatusTone(ticket.deliveryStatus)}>{deliveryStatusLabel(ticket.deliveryStatus)}</Badge>,
    },
    { key: 'created', header: t('supportQueue.col.created'), render: (ticket) => formatMoment(ticket.createdAt) },
    {
      key: 'status',
      header: t('supportQueue.col.status'),
      render: (ticket) => <Badge tone={ticketStatusTone(ticket.status)}>{ticketStatusLabel(ticket.status)}</Badge>,
    },
    {
      key: 'action',
      header: t('supportQueue.col.action'),
      render: (ticket) =>
        ticket.status === 'created' ? (
          <Button variant="secondary" busy={taking === ticket.ticketId} disabled={taking !== undefined} onClick={() => void take(ticket)}>
            {t('supportQueue.take')}
          </Button>
        ) : (
          <Link to={`/staff/support/${encodeURIComponent(ticket.ticketId)}`}>{t('supportQueue.open')}</Link>
        ),
    },
  ];

  const searching = orderNumber !== undefined;
  return (
    <section>
      <h1>{t('supportQueue.title')}</h1>
      <div className={styles.filters}>
        <SelectField
          label={t('supportQueue.filter')}
          value={filter}
          disabled={searching}
          options={[
            { value: 'queue', label: t('supportQueue.filter.queue') },
            { value: 'mine', label: t('supportQueue.filter.mine') },
          ]}
          onChange={(event) => setFilter(event.target.value === 'mine' ? 'mine' : 'queue')}
        />
        <form className={styles.search} noValidate onSubmit={search}>
          <TextField
            label={t('supportQueue.search')}
            hint={t('supportQueue.search.hint')}
            inputMode="numeric"
            autoComplete="off"
            error={searchError}
            value={searchText}
            onChange={(event) => setSearchText(event.target.value)}
          />
          <Button type="submit">{t('supportQueue.search.submit')}</Button>
          {searching ? (
            <Button variant="secondary" onClick={resetSearch}>
              {t('supportQueue.search.reset')}
            </Button>
          ) : null}
        </form>
      </div>
      {takeError ? <Notice kind="error">{takeError}</Notice> : null}
      <div aria-live="polite" aria-busy={query.isFetching}>
        {query.isPending ? <StatePanel kind="loading" /> : null}
        {query.isError && !query.data ? <StatePanel kind="error" message={problemMessage(query.error)} onRetry={() => void query.refetch()} /> : null}
        {query.data && tickets.length === 0 ? <StatePanel kind="empty" message={searching ? t('supportQueue.empty.search') : filter === 'mine' ? t('supportQueue.empty.mine') : t('supportQueue.empty')} /> : null}
        {tickets.length > 0 ? (
          <>
            <p>
              {query.hasNextPage
                ? t('supportQueue.count.more', { count: tickets.length })
                : plural(tickets.length, { one: 'supportQueue.count.one', few: 'supportQueue.count.few', many: 'supportQueue.count.many' })}
            </p>
            <Table caption={t('supportQueue.caption')} columns={columns} rows={tickets} rowKey={(ticket) => ticket.ticketId} />
            {query.isFetchNextPageError ? <StatePanel kind="error" message={problemMessage(query.error)} onRetry={() => void query.fetchNextPage()} /> : null}
            {query.hasNextPage && !query.isFetchNextPageError ? (
              <Button variant="secondary" busy={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()}>
                {t('catalog.more')}
              </Button>
            ) : null}
          </>
        ) : null}
      </div>
    </section>
  );
}
