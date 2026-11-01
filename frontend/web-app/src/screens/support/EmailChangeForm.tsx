import { useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { useApi } from '../../api/ApiContext';
import { ApiError, problemMessage } from '../../api/problem';
import { useRequestKeys } from '../../api/requestKey';
import { emailChangeCodesPath, emailChangeVerificationsPath } from '../../api/support';
import { t } from '../../i18n';
import { Button } from '../../ui/Button';
import { Notice } from '../../ui/Notice';
import { TextField } from '../../ui/TextField';
import { useRemainingSeconds } from '../../ui/useCountdown';
import { isCode } from '../account/phone';
import type { EmailChangeState, EmailChangeStep, RequestEmailChangeCodeRequest, VerifyEmailChangeCodeRequest } from './types';
import styles from './Support.module.css';

export interface EmailChangeFormProps {
  ticketId: string;
  state: EmailChangeState;
  /** Сервер принял или отклонил ввод: карточка обращения перечитывает состояние смены. */
  onChanged: () => void;
}

/** Ответы, после которых состояние смены на сервере изменилось или экран его устарел: карточка перечитывается. */
const REFRESH_AFTER = new Set(['otp-locked', 'email-not-allowed', 'state-conflict', 'not-found']);

/** Какой код сейчас ждёт сервер: сначала из SMS, затем из письма на новый адрес. Верный код из письма до завершения смены ничего не ждёт. */
export function awaitedStep(state: EmailChangeState): EmailChangeStep | undefined {
  if (state.status === 'awaiting_phone_code') {
    return 'phone';
  }
  return state.status === 'awaiting_email_code' && state.newEmailCheck !== 'passed' ? 'new_email' : undefined;
}

function failureText(error: unknown): string {
  // Занятый и собственный адрес получают один нейтральный текст без причины: так нельзя выяснить, чей адрес уже есть в системе (F4-3, E7)
  return error instanceof ApiError && error.code === 'email-not-allowed' ? t('emailChange.notAllowed') : problemMessage(error);
}

/**
 * Форма кодов смены e-mail (US-7.4, BPMN-04, SEQ-04). Коды вводит сам покупатель, оператор их не видит и не принимает: у оператора нет такого метода,
 * а здесь их вводят в сессии покупателя (обычной или по SMS). Сначала код из SMS, затем код из письма на новый адрес (виден только с маской).
 * Введённое значение не сохраняется после ответа сервера (в поле пусто и при успехе, и при ошибке), в адрес страницы и в консоль не попадает.
 * Ключ идемпотентности привязан к шагу и коду: обрыв связи и повтор того же кода не тратят попытку дважды, новый код получает новый ключ.
 * После ошибки фокус возвращается в поле кода. Неверный код показывает остаток попыток из ответа, лимит запросов кода, время ожидания.
 */
export function EmailChangeForm({ ticketId, state, onChanged }: EmailChangeFormProps) {
  const api = useApi();
  const keys = useRequestKeys();
  const inFlight = useRef(false);
  const input = useRef<HTMLInputElement>(null);
  const [code, setCode] = useState('');
  const [codeError, setCodeError] = useState<string | undefined>();
  const [error, setError] = useState<string | undefined>();
  const [info, setInfo] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);
  const [blockedUntil, setBlockedUntil] = useState<number | undefined>();
  const waitSeconds = useRemainingSeconds(blockedUntil);
  const step = awaitedStep(state);

  async function verify(event: FormEvent) {
    event.preventDefault();
    if (!step || inFlight.current) {
      return;
    }
    if (!isCode(code)) {
      setCodeError(t('emailChange.error.code'));
      input.current?.focus();
      return;
    }
    inFlight.current = true;
    setBusy(true);
    setCodeError(undefined);
    setError(undefined);
    setInfo(undefined);
    const body: VerifyEmailChangeCodeRequest = { step, code };
    try {
      await api.post<EmailChangeState>(emailChangeVerificationsPath(ticketId), body, { idempotencyKey: keys.keyFor(`verify:${ticketId}:${step}:${code}`) });
      keys.settle();
      setCode('');
      onChanged();
    } catch (failure) {
      keys.settle(failure);
      setCode('');
      setError(failureText(failure));
      if (failure instanceof ApiError && REFRESH_AFTER.has(failure.code)) {
        onChanged();
      }
      input.current?.focus();
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  async function resend() {
    if (!step || inFlight.current) {
      return;
    }
    inFlight.current = true;
    setBusy(true);
    setCodeError(undefined);
    setError(undefined);
    setInfo(undefined);
    const body: RequestEmailChangeCodeRequest = { step };
    try {
      await api.post<EmailChangeState>(emailChangeCodesPath(ticketId), body, { idempotencyKey: keys.keyFor(`request:${ticketId}:${step}`) });
      keys.settle();
      setCode('');
      setInfo(step === 'phone' ? t('emailChange.resent.phone') : t('emailChange.resent.email', { address: state.newEmailMasked }));
    } catch (failure) {
      keys.settle(failure);
      if (failure instanceof ApiError && failure.status === 429 && failure.retryAfterMs !== undefined) {
        setBlockedUntil(Date.now() + failure.retryAfterMs);
      }
      setError(failureText(failure));
      if (failure instanceof ApiError && REFRESH_AFTER.has(failure.code)) {
        onChanged();
      }
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  const errorNotice = error ? <Notice kind="error">{error}</Notice> : null;

  if (state.status === 'completed') {
    return (
      <section>
        <h2>{t('emailChange.title')}</h2>
        {errorNotice}
        <Notice kind="success">{t('emailChange.completed', { address: state.newEmailMasked })}</Notice>
      </section>
    );
  }
  if (state.status === 'failed') {
    return (
      <section>
        <h2>{t('emailChange.title')}</h2>
        {errorNotice}
        <Notice kind="error">{t('emailChange.failed')}</Notice>
      </section>
    );
  }
  if (!step) {
    return (
      <section>
        <h2>{t('emailChange.title')}</h2>
        {errorNotice}
        <Notice kind="info">{t('emailChange.confirming')}</Notice>
      </section>
    );
  }

  const label = step === 'phone' ? t('emailChange.code.phone') : t('emailChange.code.email');
  return (
    <section>
      <h2>{t('emailChange.title')}</h2>
      <p>{t('emailChange.lead', { address: state.newEmailMasked })}</p>
      <p>{step === 'phone' ? t('emailChange.step.phone') : t('emailChange.step.email', { address: state.newEmailMasked })}</p>
      <form className={styles.form} noValidate onSubmit={(event) => void verify(event)}>
        {errorNotice}
        {info ? <Notice kind="info">{info}</Notice> : null}
        <TextField
          label={label}
          hint={t('emailChange.code.hint')}
          inputRef={input}
          inputMode="numeric"
          autoComplete="one-time-code"
          maxLength={6}
          error={codeError}
          value={code}
          onChange={(event) => setCode(event.target.value.replace(/\D/g, ''))}
        />
        <div className={styles.actions}>
          <Button type="submit" busy={busy}>
            {t('emailChange.verify')}
          </Button>
          <Button variant="secondary" disabled={busy || waitSeconds > 0} onClick={() => void resend()}>
            {t('emailChange.resend')}
          </Button>
        </div>
        {waitSeconds > 0 ? <p className={styles.muted}>{t('emailChange.resend.in', { seconds: waitSeconds })}</p> : null}
      </form>
    </section>
  );
}
