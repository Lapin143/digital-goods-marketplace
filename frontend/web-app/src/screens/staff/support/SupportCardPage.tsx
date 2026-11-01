import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { problemMessage } from '../../../api/problem';
import { useAuth } from '../../../auth/AuthContext';
import { t } from '../../../i18n';
import { Badge } from '../../../ui/Badge';
import { Notice } from '../../../ui/Notice';
import { StatePanel } from '../../../ui/StatePanel';
import { formatMoment } from '../../../ui/format';
import { orderStatusLabel } from '../../orders/labels';
import { ticketReasonLabel } from '../../support/labels';
import { TicketActions } from './TicketActions';
import { checkLabel, checkTone, deliveryStatusLabel, deliveryStatusTone, emailChangeStatusLabel, sourceLabel, ticketStatusLabel, ticketStatusTone } from './labels';
import type { EmailChangeState, StaffSupportTicket } from './types';
import { SUPPORT_CARD_KEY, SUPPORT_QUEUE_KEY, useStaffTicket } from './useStaffSupport';
import styles from './StaffSupport.module.css';

function EmailChangePanel({ state }: { state: EmailChangeState }) {
  return (
    <section aria-labelledby="email-change-title" className={styles.panel}>
      <h2 id="email-change-title">{t('supportCard.change.title')}</h2>
      <dl className={styles.facts}>
        <dt>{t('supportCard.change.status')}</dt>
        <dd>{emailChangeStatusLabel(state.status)}</dd>
        <dt>{t('supportCard.change.phone')}</dt>
        <dd>
          <Badge tone={checkTone(state.phoneCheck)}>{checkLabel(state.phoneCheck)}</Badge>
        </dd>
        <dt>{t('supportCard.change.email')}</dt>
        <dd>
          <Badge tone={checkTone(state.newEmailCheck)}>{checkLabel(state.newEmailCheck)}</Badge>
        </dd>
        <dt>{t('supportCard.change.address')}</dt>
        <dd>{state.newEmailMasked}</dd>
        <dt>{t('supportCard.change.updatedAt')}</dt>
        <dd>{formatMoment(state.updatedAt)}</dd>
      </dl>
      <p className={styles.muted}>{t('supportCard.change.note')}</p>
    </section>
  );
}

function Facts({ ticket, mine }: { ticket: StaffSupportTicket; mine: boolean }) {
  const operator = ticket.operatorId === null ? t('supportCard.operator.none') : mine ? t('supportCard.operator.me') : t('supportCard.operator.other');
  return (
    <dl className={styles.facts}>
      <dt>{t('supportCard.source')}</dt>
      <dd>{sourceLabel(ticket.source)}</dd>
      <dt>{t('supportCard.reason')}</dt>
      <dd>{ticketReasonLabel(ticket.reason)}</dd>
      <dt>{t('supportCard.order')}</dt>
      <dd>{orderStatusLabel(ticket.orderStatus)}</dd>
      <dt>{t('supportCard.issuedAt')}</dt>
      <dd>{ticket.issuedAt ? formatMoment(ticket.issuedAt) : t('supportCard.none')}</dd>
      <dt>{t('supportCard.delivery')}</dt>
      <dd>
        <Badge tone={deliveryStatusTone(ticket.deliveryStatus)}>{deliveryStatusLabel(ticket.deliveryStatus)}</Badge>
      </dd>
      <dt>{t('supportCard.createdAt')}</dt>
      <dd>{formatMoment(ticket.createdAt)}</dd>
      <dt>{t('supportCard.operator')}</dt>
      <dd>{operator}</dd>
      <dt>{t('supportCard.takenAt')}</dt>
      <dd>{ticket.takenAt ? formatMoment(ticket.takenAt) : t('supportCard.none')}</dd>
      {ticket.resolvedAt ? (
        <>
          <dt>{t('supportCard.resolvedAt')}</dt>
          <dd>{formatMoment(ticket.resolvedAt)}</dd>
        </>
      ) : null}
    </dl>
  );
}

/**
 * Карточка обращения оператора (US-7.2 – US-7.4): заказ, статус последней выдачи, ход смены e-mail и действия. Значения ключа и кодов в ответах нет,
 * адрес покупателя не показывается совсем, новый адрес только с маской. Пока выдача «в очереди», карточка обновляется чаще, и результат повторной
 * отправки появляется без нажатий. Статус заказа и выдачи берутся из модели чтения поддержки, поэтому экран строится вокруг обращения, а не заказа.
 */
export function SupportCardPage() {
  const { ticketId } = useParams();
  const id = ticketId ?? '';
  const auth = useAuth();
  const queryClient = useQueryClient();
  const operatorId = auth.session('staff')?.claims.subject ?? '';
  const query = useStaffTicket(id);
  const [notice, setNotice] = useState<{ kind: 'success' | 'error'; text: string } | undefined>();

  async function reload(next: { kind: 'success' | 'error'; text: string }) {
    setNotice(next);
    await queryClient.invalidateQueries({ queryKey: [...SUPPORT_CARD_KEY, id] });
    void queryClient.invalidateQueries({ queryKey: SUPPORT_QUEUE_KEY });
  }

  if (query.isPending) {
    return <StatePanel kind="loading" />;
  }
  if (query.isError) {
    return <StatePanel kind="error" message={problemMessage(query.error)} onRetry={() => void query.refetch()} />;
  }
  const ticket = query.data;
  const mine = ticket.operatorId === operatorId;
  return (
    <section>
      <p>
        <Link to="/staff/support">{t('supportCard.back')}</Link>
      </p>
      <h1>{t('supportCard.title', { number: ticket.orderNumber })}</h1>
      <div className={styles.state}>
        <Badge tone={ticketStatusTone(ticket.status)}>{ticketStatusLabel(ticket.status)}</Badge>
      </div>
      {notice ? <Notice kind={notice.kind}>{notice.text}</Notice> : null}
      <Facts ticket={ticket} mine={mine} />
      {ticket.emailChange ? <EmailChangePanel state={ticket.emailChange} /> : null}
      <section aria-labelledby="actions-title" className={styles.panel}>
        <h2 id="actions-title">{t('supportCard.actions')}</h2>
        <TicketActions
          ticket={ticket}
          operatorId={operatorId}
          onDone={(text) => void reload({ kind: 'success', text })}
          onStale={(text) => void reload({ kind: 'error', text })}
        />
      </section>
    </section>
  );
}
