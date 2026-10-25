import { useState } from 'react';
import type { FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { useApi } from '../../api/ApiContext';
import { serverClockOffset, toLocalMoment } from '../../api/clock';
import { ApiError, fieldMessages, problemMessage } from '../../api/problem';
import { useRequestKeys } from '../../api/requestKey';
import { t } from '../../i18n';
import { Button } from '../../ui/Button';
import { Notice } from '../../ui/Notice';
import { TextField } from '../../ui/TextField';
import { useRemainingSeconds } from '../../ui/useCountdown';
import { isCode, normalizePhone } from './phone';
import type { PhoneConfirmation, PhoneConfirmed } from './types';
import styles from './PhonePage.module.css';

type Step = { kind: 'phone' } | { kind: 'code'; confirmationId: string; phoneMasked: string } | { kind: 'done'; phoneMasked: string };

/** Ответы, после которых введённый код уже не поможет: нужен новый код (или другой номер). */
const START_OVER = new Set(['otp-expired', 'otp-locked', 'phone-not-allowed', 'not-found']);

export interface PhoneConfirmationFlowProps {
  /** Шаги внутри другого экрана (покупка): заголовки второго уровня, по завершении короткое сообщение вместо страницы. */
  embedded?: boolean;
  /** Телефон подтверждён: родительский экран продолжает своё действие. */
  onConfirmed?: (phoneMasked: string) => void;
}

/** Страница `/account/phone`. */
export function PhonePage() {
  return <PhoneConfirmationFlow />;
}

/**
 * Подтверждение телефона (US-1.6): запрос кода, ввод кода, ошибки лимитов с временем ожидания.
 * Исход каждого запроса показывается текстом из словаря по коду ошибки. Повтор того же действия после сбоя сети
 * уходит с тем же `Idempotency-Key`, поэтому код не отправляется дважды, а двойное нажатие невозможно (кнопка занята).
 * Тот же набор шагов открывается внутри покупки (`embedded`): покупатель подтверждает номер, не уходя с карточки товара (US-5.1, шаг 19 Ф4).
 */
export function PhoneConfirmationFlow({ embedded = false, onConfirmed }: PhoneConfirmationFlowProps) {
  const Title = embedded ? 'h2' : 'h1';
  const api = useApi();
  const keys = useRequestKeys();
  const [step, setStep] = useState<Step>({ kind: 'phone' });
  const [phoneText, setPhoneText] = useState('');
  const [phone, setPhone] = useState('');
  const [code, setCode] = useState('');
  const [phoneError, setPhoneError] = useState<string | undefined>();
  const [codeError, setCodeError] = useState<string | undefined>();
  const [notice, setNotice] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);
  const [blockedUntil, setBlockedUntil] = useState<number | undefined>();
  const waitSeconds = useRemainingSeconds(blockedUntil);

  function blockFor(error: unknown) {
    if (error instanceof ApiError && error.retryAfterMs !== undefined) {
      setBlockedUntil(Date.now() + error.retryAfterMs);
    }
  }

  async function requestCode(text: string) {
    const normalized = normalizePhone(text);
    if (!normalized) {
      setPhoneError(t('phone.error.format'));
      return;
    }
    setBusy(true);
    setPhoneError(undefined);
    setNotice(undefined);
    try {
      const response = await api.post<PhoneConfirmation>('/api/v1/phone-confirmations', { phone: normalized }, { idempotencyKey: keys.keyFor(`create:${normalized}`) });
      keys.settle();
      setPhone(normalized);
      setCode('');
      setCodeError(undefined);
      setBlockedUntil(toLocalMoment(response.data.resendAvailableAt, serverClockOffset(response.headers, Date.now())));
      setStep({ kind: 'code', confirmationId: response.data.confirmationId, phoneMasked: response.data.phoneMasked });
    } catch (error) {
      keys.settle(error);
      blockFor(error);
      const fields = fieldMessages(error);
      if (fields.phone) {
        setPhoneError(fields.phone);
      } else {
        setNotice(problemMessage(error));
      }
    } finally {
      setBusy(false);
    }
  }

  async function verify(confirmationId: string, phoneMasked: string) {
    if (!isCode(code)) {
      setCodeError(t('phone.error.code'));
      return;
    }
    setBusy(true);
    setCodeError(undefined);
    setNotice(undefined);
    try {
      const response = await api.post<PhoneConfirmed>(
        `/api/v1/phone-confirmations/${encodeURIComponent(confirmationId)}/verify`,
        { code },
        { idempotencyKey: keys.keyFor(`verify:${confirmationId}:${code}`) },
      );
      keys.settle();
      const masked = response.data.phoneMasked || phoneMasked;
      setStep({ kind: 'done', phoneMasked: masked });
      onConfirmed?.(masked);
    } catch (error) {
      keys.settle(error);
      blockFor(error);
      setNotice(problemMessage(error));
      if (error instanceof ApiError && START_OVER.has(error.code)) {
        setStep({ kind: 'phone' });
        setPhoneText(phone);
      } else {
        setCode('');
      }
    } finally {
      setBusy(false);
    }
  }

  const waiting = waitSeconds > 0;
  const waitNote = waiting ? <p className={styles.wait}>{t('phone.resendIn', { seconds: waitSeconds })}</p> : null;

  if (step.kind === 'done' && embedded) {
    return <Notice kind="success">{t('phone.done.text', { phone: step.phoneMasked })}</Notice>;
  }

  if (step.kind === 'done') {
    return (
      <section>
        <h1>{t('phone.done.title')}</h1>
        <Notice kind="success">{t('phone.done.text', { phone: step.phoneMasked })}</Notice>
        <p>{t('phone.done.refresh')}</p>
        <Link to="/products">{t('product.back')}</Link>
      </section>
    );
  }

  if (step.kind === 'code') {
    const { confirmationId, phoneMasked } = step;
    return (
      <section>
        <Title>{t('phone.title')}</Title>
        <form
          className={styles.form}
          noValidate
          onSubmit={(event: FormEvent) => {
            event.preventDefault();
            void verify(confirmationId, phoneMasked);
          }}
        >
          <p>{t('phone.sentTo', { phone: phoneMasked })}</p>
          {notice ? <Notice kind="error">{notice}</Notice> : null}
          <TextField
            label={t('phone.code')}
            hint={t('phone.code.hint')}
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={6}
            error={codeError}
            value={code}
            onChange={(event) => setCode(event.target.value.replace(/\D/g, ''))}
          />
          <div className={styles.actions}>
            <Button type="submit" busy={busy}>
              {t('phone.verify')}
            </Button>
            <Button variant="secondary" disabled={busy || waiting} onClick={() => void requestCode(phone)}>
              {t('phone.resend')}
            </Button>
            <Button
              variant="secondary"
              disabled={busy}
              onClick={() => {
                setStep({ kind: 'phone' });
                setPhoneText(phone);
                setNotice(undefined);
              }}
            >
              {t('phone.change')}
            </Button>
          </div>
          {waitNote}
        </form>
      </section>
    );
  }

  return (
    <section>
      <Title>{t('phone.title')}</Title>
      <p>{t('phone.lead')}</p>
      <form
        className={styles.form}
        noValidate
        onSubmit={(event: FormEvent) => {
          event.preventDefault();
          void requestCode(phoneText);
        }}
      >
        {notice ? <Notice kind="error">{notice}</Notice> : null}
        <TextField
          label={t('phone.number')}
          hint={t('phone.number.hint')}
          type="tel"
          autoComplete="tel"
          error={phoneError}
          value={phoneText}
          onChange={(event) => setPhoneText(event.target.value)}
        />
        <div className={styles.actions}>
          <Button type="submit" busy={busy} disabled={waiting}>
            {t('phone.send')}
          </Button>
        </div>
        {waitNote}
      </form>
    </section>
  );
}
