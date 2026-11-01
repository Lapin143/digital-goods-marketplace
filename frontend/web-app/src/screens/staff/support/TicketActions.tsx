import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { useApi } from '../../../api/ApiContext';
import { ApiError, problemMessage } from '../../../api/problem';
import { isOutcomeUnknown, useRequestKeys } from '../../../api/requestKey';
import { staffTicketActionPath } from '../../../api/support';
import type { StaffTicketAction } from '../../../api/support';
import { t } from '../../../i18n';
import type { TextKey } from '../../../i18n';
import { Button } from '../../../ui/Button';
import { Notice } from '../../../ui/Notice';
import { TextField } from '../../../ui/TextField';
import { isPlausibleEmail } from './labels';
import type { StartEmailChangeRequest, StaffSupportTicket } from './types';
import styles from './StaffSupport.module.css';

export interface TicketActionsProps {
  ticket: StaffSupportTicket;
  /** Идентификатор текущего оператора (`sub` токена): действия доступны только по обращению, которое ведёт он сам. */
  operatorId: string;
  /** Действие принято: карточка перечитывается, над ней показывается сообщение. */
  onDone: (message: string) => void;
  /** Состояние на сервере изменилось (обращение взял другой, вернули в очередь): карточка перечитывается. */
  onStale: (message: string) => void;
}

/** Ответы, после которых карточка на экране устарела: обращение ушло к другому оператору или выдача уже запущена. */
const STALE_CODES = new Set(['ticket-not-in-progress', 'state-conflict', 'delivery-in-progress', 'not-found']);

type Choice = StaffTicketAction;

const CONFIRM: Record<Choice, { label: TextKey; question: TextKey; done: TextKey; variant: 'primary' | 'secondary' | 'danger' }> = {
  take: { label: 'supportCard.take', question: 'supportCard.take.confirm', done: 'supportCard.take.done', variant: 'primary' },
  release: { label: 'supportCard.release', question: 'supportCard.release.confirm', done: 'supportCard.release.done', variant: 'secondary' },
  resend: { label: 'supportCard.resend', question: 'supportCard.resend.confirm', done: 'supportCard.resend.done', variant: 'primary' },
  'email-change': { label: 'supportCard.emailChange', question: 'supportCard.emailChange.confirm', done: 'supportCard.emailChange.done', variant: 'primary' },
};

/** Почему «Повторно отправить ключ» недоступна. Нет причины значит кнопка доступна. Окончательно решает сервер (409), экран лишь не зовёт заведомо лишнее. */
export function resendBlockedReason(ticket: StaffSupportTicket): TextKey | undefined {
  if (ticket.orderStatus !== 'issued') {
    return 'supportCard.resend.blocked.order';
  }
  if (ticket.deliveryStatus === 'queued') {
    return 'supportCard.resend.blocked.queued';
  }
  return undefined;
}

/** Почему «Сменить e-mail» недоступна: завершённую смену заменить нельзя. */
export function emailChangeBlockedReason(ticket: StaffSupportTicket): TextKey | undefined {
  return ticket.emailChange?.status === 'completed' ? 'supportCard.emailChange.blocked.completed' : undefined;
}

/**
 * Действия оператора по обращению (US-7.2, US-7.3, US-7.4). Каждое проходит два шага: выбор и подтверждение, у смены e-mail на втором шаге ещё поле
 * нового адреса. От двойного нажатия защищает флаг запроса в `ref` и недоступная кнопка, от потери ответа защищает `Idempotency-Key`: пока исход
 * неизвестен (сбой сети, 5xx, «запрос выполняется»), повтор уходит с тем же ключом и не создаёт вторую выдачу. Ключа письма и кодов в ответах нет, а
 * новый адрес очищается из поля сразу после ответа сервера, кроме случая, когда исход неизвестен и нужен повтор с тем же значением.
 */
export function TicketActions({ ticket, operatorId, onDone, onStale }: TicketActionsProps) {
  const api = useApi();
  const keys = useRequestKeys();
  const inFlight = useRef(false);
  const panel = useRef<HTMLDivElement>(null);
  const [chosen, setChosen] = useState<Choice | undefined>();
  const [email, setEmail] = useState('');
  const [emailError, setEmailError] = useState<string | undefined>();
  const [error, setError] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (chosen) {
      panel.current?.focus();
    }
  }, [chosen]);

  if (ticket.status === 'resolved') {
    return <p className={styles.muted}>{t('supportCard.resolved')}</p>;
  }
  const mine = ticket.operatorId === operatorId;
  if (ticket.status === 'in_progress' && !mine) {
    return <p className={styles.muted}>{t('supportCard.otherOperator')}</p>;
  }

  function choose(choice: Choice) {
    if (inFlight.current) {
      return;
    }
    setChosen(choice);
    setEmail('');
    setEmailError(undefined);
    setError(undefined);
  }

  function cancel() {
    if (inFlight.current) {
      return;
    }
    setChosen(undefined);
    setEmail('');
    setEmailError(undefined);
    setError(undefined);
  }

  async function confirm(event?: FormEvent) {
    event?.preventDefault();
    if (!chosen || inFlight.current) {
      return;
    }
    const newEmail = email.trim().toLowerCase();
    if (chosen === 'email-change' && !isPlausibleEmail(newEmail)) {
      setEmailError(t('supportCard.emailChange.invalid'));
      return;
    }
    inFlight.current = true;
    setBusy(true);
    setEmailError(undefined);
    setError(undefined);
    const body: StartEmailChangeRequest | undefined = chosen === 'email-change' ? { newEmail } : undefined;
    const fingerprint = chosen === 'email-change' ? `${chosen}:${ticket.ticketId}:${newEmail}` : `${chosen}:${ticket.ticketId}`;
    try {
      await api.post(staffTicketActionPath(ticket.ticketId, chosen), body, { auth: 'staff', idempotencyKey: keys.keyFor(fingerprint) });
      keys.settle();
      const done = CONFIRM[chosen].done;
      setChosen(undefined);
      setEmail('');
      onDone(t(done));
    } catch (failure) {
      keys.settle(failure);
      if (chosen === 'email-change' && !isOutcomeUnknown(failure)) {
        setEmail('');
      }
      if (failure instanceof ApiError && failure.code === 'email-not-allowed') {
        setError(t('supportCard.emailChange.notAllowed'));
      } else if (chosen === 'take' && failure instanceof ApiError && failure.status === 409 && failure.code === 'state-conflict') {
        setChosen(undefined);
        onStale(t('supportQueue.alreadyTaken'));
      } else if (failure instanceof ApiError && STALE_CODES.has(failure.code)) {
        setChosen(undefined);
        onStale(problemMessage(failure));
      } else {
        setError(problemMessage(failure));
      }
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  const resendBlocked = resendBlockedReason(ticket);
  const changeBlocked = emailChangeBlockedReason(ticket);
  if (!chosen) {
    return (
      <div>
        <div className={styles.actions}>
          {ticket.status === 'created' ? (
            <Button onClick={() => choose('take')}>{t('supportCard.take')}</Button>
          ) : (
            <>
              <Button disabled={resendBlocked !== undefined} aria-describedby={resendBlocked ? 'resend-blocked' : undefined} onClick={() => choose('resend')}>
                {t('supportCard.resend')}
              </Button>
              <Button variant="secondary" disabled={changeBlocked !== undefined} aria-describedby={changeBlocked ? 'change-blocked' : undefined} onClick={() => choose('email-change')}>
                {t('supportCard.emailChange')}
              </Button>
              <Button variant="secondary" onClick={() => choose('release')}>
                {t('supportCard.release')}
              </Button>
            </>
          )}
        </div>
        {resendBlocked && ticket.status === 'in_progress' ? (
          <p id="resend-blocked" className={styles.muted}>
            {t(resendBlocked)}
          </p>
        ) : null}
        {changeBlocked && ticket.status === 'in_progress' ? (
          <p id="change-blocked" className={styles.muted}>
            {t(changeBlocked)}
          </p>
        ) : null}
      </div>
    );
  }

  const option = CONFIRM[chosen];
  return (
    <div ref={panel} tabIndex={-1} role="group" aria-label={t(option.label)} className={styles.confirm}>
      <form noValidate onSubmit={(event) => void confirm(event)}>
        <p>{t(option.question)}</p>
        {chosen === 'email-change' ? (
          <TextField
            label={t('supportCard.emailChange.field')}
            hint={t('supportCard.emailChange.hint')}
            type="email"
            autoComplete="off"
            autoCapitalize="off"
            spellCheck={false}
            value={email}
            error={emailError}
            disabled={busy}
            onChange={(event) => setEmail(event.target.value)}
          />
        ) : null}
        {error ? <Notice kind="error">{error}</Notice> : null}
        <div className={styles.actions}>
          <Button type="submit" variant={option.variant} busy={busy}>
            {t('supportCard.confirm')}
          </Button>
          <Button variant="secondary" disabled={busy} onClick={cancel}>
            {t('supportCard.cancel')}
          </Button>
        </div>
      </form>
    </div>
  );
}
