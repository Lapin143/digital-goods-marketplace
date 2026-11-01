import { useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { useApi } from '../../api/ApiContext';
import { ApiError, problemMessage } from '../../api/problem';
import { useRequestKeys } from '../../api/requestKey';
import { SUPPORT_TICKETS } from '../../api/support';
import { useBuyerScope } from '../../app/SessionScope';
import { t } from '../../i18n';
import { Button } from '../../ui/Button';
import { Notice } from '../../ui/Notice';
import { useRemainingSeconds } from '../../ui/useCountdown';
import { SUPPORT_QUERY_KEY, useOpenTicketForOrder } from './useSupport';
import type { CreateSupportTicketRequest, SupportTicket } from './types';
import { SUPPORT_WINDOW_HOURS, windowClosed } from './window';
import styles from './Support.module.css';

export interface ReportMissingKeyProps {
  orderId: string;
  /** Первичная выдача в местном времени (мс); неизвестна, если у заказа нет момента выдачи. */
  issuedAtMs: number | undefined;
}

/**
 * Кнопка «Не получил ключ» в карточке выданного заказа (US-7.1, BPMN-04 шаг 2). Пока с первичной выдачи не прошло 72 часа, а открытого обращения нет,
 * кнопка создаёт обращение; при открытом обращении вместо неё ссылка на него; после срока текст «Срок обращения истёк». Ключ идемпотентности
 * держится, пока исход неизвестен (обрыв связи), поэтому повтор не создаст второго обращения. Двойное нажатие закрыто флагом запроса и занятой кнопкой.
 * Предел частоты (5 обращений в час) и закрытое окно приходят от сервера и показываются словами, а не кодами. Ключей на экране нет.
 */
export function ReportMissingKey({ orderId, issuedAtMs }: ReportMissingKeyProps) {
  const api = useApi();
  const keys = useRequestKeys();
  const queryClient = useQueryClient();
  const { base } = useBuyerScope();
  const open = useOpenTicketForOrder(orderId);
  const inFlight = useRef(false);
  const [busy, setBusy] = useState(false);
  const [created, setCreated] = useState<SupportTicket | undefined>();
  const [error, setError] = useState<string | undefined>();
  const [windowReported, setWindowReported] = useState(false);
  const [blockedUntil, setBlockedUntil] = useState<number | undefined>();
  const waitSeconds = useRemainingSeconds(blockedUntil);

  async function report() {
    if (inFlight.current) {
      return;
    }
    inFlight.current = true;
    setBusy(true);
    setError(undefined);
    try {
      const body: CreateSupportTicketRequest = { orderId, reason: 'key_not_received' };
      const response = await api.post<SupportTicket>(SUPPORT_TICKETS, body, { idempotencyKey: keys.keyFor(`ticket:${orderId}`) });
      keys.settle();
      setCreated(response.data);
      void queryClient.invalidateQueries({ queryKey: SUPPORT_QUERY_KEY });
    } catch (failure) {
      keys.settle(failure);
      if (failure instanceof ApiError && failure.code === 'ticket-window-closed') {
        setWindowReported(true);
      } else {
        if (failure instanceof ApiError && failure.status === 429 && failure.retryAfterMs !== undefined) {
          setBlockedUntil(Date.now() + failure.retryAfterMs);
        }
        if (failure instanceof ApiError && failure.code === 'ticket-already-open') {
          void open.refetch();
        }
        setError(problemMessage(failure));
      }
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  // Пока запрос свежих данных идёт, показывается только что созданное обращение; потом верно то, что ответил сервер (обращение могли успеть решить)
  const settled = open.isSuccess && !open.isFetching;
  const ticket = (settled ? open.data : (created ?? open.data)) ?? undefined;
  if (ticket && ticket.status !== 'resolved') {
    return (
      <div className={styles.report}>
        <Notice kind="info">{created?.ticketId === ticket.ticketId ? t('ticket.report.created') : t('ticket.report.exists')}</Notice>
        <p>
          <Link to={`${base}/tickets/${encodeURIComponent(ticket.ticketId)}`}>{t('ticket.report.open')}</Link>
        </p>
      </div>
    );
  }

  if (windowReported || windowClosed(issuedAtMs, Date.now())) {
    return (
      <div className={styles.report}>
        <Notice kind="info">{t('ticket.report.windowClosed', { hours: SUPPORT_WINDOW_HOURS })}</Notice>
      </div>
    );
  }

  return (
    <div className={styles.report}>
      <p className={styles.muted}>{t('ticket.report.hint')}</p>
      {error ? <Notice kind="error">{error}</Notice> : null}
      <Button variant="secondary" busy={busy} disabled={waitSeconds > 0} onClick={() => void report()}>
        {busy ? t('ticket.report.sending') : t('ticket.report')}
      </Button>
      {waitSeconds > 0 ? <p className={styles.muted}>{t('ticket.report.retryIn', { seconds: waitSeconds })}</p> : null}
    </div>
  );
}
