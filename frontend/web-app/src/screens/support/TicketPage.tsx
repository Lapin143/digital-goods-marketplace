import { Link, useParams } from 'react-router-dom';
import { ApiError, problemMessage } from '../../api/problem';
import { useBuyerScope } from '../../app/SessionScope';
import { t } from '../../i18n';
import { Badge } from '../../ui/Badge';
import { formatMoment } from '../../ui/format';
import { Notice } from '../../ui/Notice';
import { StatePanel } from '../../ui/StatePanel';
import { EmailChangeForm } from './EmailChangeForm';
import { isOpenTicket, resolutionText, ticketReasonLabel, ticketSourceLabel, ticketStateText, ticketStatusLabel, ticketStatusTone } from './labels';
import type { SupportTicket } from './types';
import { useTicket } from './useSupport';
import styles from './Support.module.css';

/**
 * Карточка обращения (US-7.1, US-7.4): статус по SM-07 словами, причина, источник, время, итог, ссылка на заказ и, когда оператор начал смену e-mail,
 * форма кодов. Выдачи и ключей здесь нет ни в каком виде: о ключе говорит только письмо (INV-20). Чужое и несуществующее обращение одинаково дают 404
 * и одинаковую страницу: существование чужого обращения не раскрывается (U1). Пока обращение не решено, статус обновляется опросом.
 */
export function TicketPage() {
  const { ticketId = '' } = useParams();
  const { base } = useBuyerScope();
  const query = useTicket(ticketId);

  if (query.isPending) {
    return <StatePanel kind="loading" />;
  }
  const ticket = query.data;
  if (!ticket) {
    if (query.error instanceof ApiError && query.error.status === 404) {
      return (
        <section>
          <h1>{t('ticket.notFound.title')}</h1>
          <p>{t('ticket.notFound.text')}</p>
          <Link to={`${base}/tickets`}>{t('ticket.back')}</Link>
        </section>
      );
    }
    return <StatePanel kind="error" message={problemMessage(query.error)} onRetry={() => void query.refetch()} />;
  }
  return <TicketDetails ticket={ticket} connectionLost={query.isRefetchError} onChanged={() => void query.refetch()} />;
}

interface DetailsProps {
  ticket: SupportTicket;
  connectionLost: boolean;
  onChanged: () => void;
}

function TicketDetails({ ticket, connectionLost, onChanged }: DetailsProps) {
  const { base } = useBuyerScope();
  const resolved = ticket.status === 'resolved';
  return (
    <article>
      <Link to={`${base}/tickets`}>{t('ticket.back')}</Link>
      <h1>{t('ticket.title')}</h1>
      {connectionLost ? <Notice kind="error">{t('ticket.connectionLost')}</Notice> : null}
      <div className={styles.state}>
        <Notice kind={resolved ? 'success' : 'info'}>{ticketStateText(ticket.status)}</Notice>
      </div>
      <dl className={styles.facts}>
        <dt>{t('ticket.status')}</dt>
        <dd>
          <Badge tone={ticketStatusTone(ticket.status)}>{ticketStatusLabel(ticket.status)}</Badge>
        </dd>
        <dt>{t('ticket.reason')}</dt>
        <dd>{ticketReasonLabel(ticket.reason)}</dd>
        <dt>{t('ticket.source')}</dt>
        <dd>{ticketSourceLabel(ticket.source)}</dd>
        <dt>{t('ticket.order')}</dt>
        <dd>
          <Link to={`${base}/orders/${encodeURIComponent(ticket.orderId)}`}>{t('ticket.order.open')}</Link>
        </dd>
        <dt>{t('ticket.createdAt')}</dt>
        <dd>{formatMoment(ticket.createdAt)}</dd>
        {ticket.resolvedAt ? (
          <>
            <dt>{t('ticket.resolvedAt')}</dt>
            <dd>{formatMoment(ticket.resolvedAt)}</dd>
            <dt>{t('ticket.resolution')}</dt>
            <dd>{resolutionText(ticket.resolution)}</dd>
          </>
        ) : null}
      </dl>
      {ticket.emailChange ? <EmailChangeForm ticketId={ticket.ticketId} state={ticket.emailChange} onChanged={onChanged} /> : null}
      {isOpenTicket(ticket.status) ? <p className={styles.muted}>{t('ticket.autoRefresh')}</p> : null}
    </article>
  );
}
