import { useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { useApi } from '../../api/ApiContext';
import { serverClockOffset, toLocalMoment } from '../../api/clock';
import { redirectTo } from '../../api/redirect';
import { useRequestKeys } from '../../api/requestKey';
import { useAuth } from '../../auth/AuthContext';
import { hasAnyRole } from '../../auth/claims';
import { t } from '../../i18n';
import { Button } from '../../ui/Button';
import { formatCountdown } from '../../ui/format';
import { Notice } from '../../ui/Notice';
import { TextField } from '../../ui/TextField';
import { useRemainingSeconds } from '../../ui/useCountdown';
import { PhoneConfirmationFlow } from '../account/PhonePage';
import { formatMoney } from '../storefront/money';
import type { MoneyValue } from '../storefront/money';
import { classifyPurchaseError, orderTotal, parseQuantity, purchaseFingerprint, quantityLimit, safePaymentUrl } from './purchase';
import type { PurchaseProblem } from './purchase';
import type { OrderWithPaymentSession } from './types';
import styles from './BuyPanel.module.css';

export interface BuyPanelProps {
  productId: string;
  /** Цена за штуку из карточки. */
  price: MoneyValue;
  /** Сколько можно заказать сейчас (`maxOrderQuantity` карточки); ноль значит «нет в наличии». */
  orderUpTo: number;
  /** Ответ 409 говорит, что остаток или доступность изменились: карточка перечитывает товар. */
  onStockChanged: () => void;
}

type PhoneStep = 'confirm' | 'working' | 'stale' | 'signIn';

type Phase =
  | { kind: 'form' }
  | { kind: 'submitting'; afterPhone: boolean }
  | { kind: 'phone'; step: PhoneStep }
  | { kind: 'created'; order: OrderWithPaymentSession; payUrl: string | undefined; reserveUntil: number | undefined }
  | { kind: 'problem'; problem: PurchaseProblem };

/**
 * Покупка на карточке товара (US-5.1, FT-5.1; шаг 19 Ф4). Количество от 1 до остатка (не больше десяти), итог считается из цены карточки,
 * окончательную сумму фиксирует сервер. Один `Idempotency-Key` на попытку: пока товар и количество те же, а исход неизвестен
 * или нужно подтвердить телефон, ключ прежний, поэтому двойное нажатие и повтор после сбоя сети не создают второй заказ.
 * Нет подтверждённого телефона (403): тут же шаги подтверждения, затем токен обновляется и тот же заказ отправляется сам.
 * Успех: переход на страницу оплаты шлюза, возврат с неё ничего не решает, статус заказа покажет его экран (ADR-015).
 */
export function BuyPanel({ productId, price, orderUpTo, onStockChanged }: BuyPanelProps) {
  const api = useApi();
  const auth = useAuth();
  const keys = useRequestKeys();
  const location = useLocation();
  const [phase, setPhase] = useState<Phase>({ kind: 'form' });
  const [quantityText, setQuantityText] = useState('1');
  const [quantityError, setQuantityError] = useState<string | undefined>();
  // Защита от двойного щелчка до того, как кнопка успеет стать недоступной
  const inFlight = useRef(false);

  const limit = quantityLimit(orderUpTo);
  const session = auth.session('web');
  const returnTo = `${location.pathname}${location.search}`;

  if (!auth.ready) {
    return null;
  }
  if (phase.kind === 'created') {
    return <Created order={phase.order} payUrl={phase.payUrl} reserveUntil={phase.reserveUntil} />;
  }
  if (limit === 0) {
    return (
      <section className={styles.panel}>
        <Notice kind="info">{t('purchase.soldOut')}</Notice>
      </section>
    );
  }
  if (!session) {
    return (
      <section className={styles.panel}>
        <h2>{t('purchase.title')}</h2>
        <p>{t('purchase.signIn.lead')}</p>
        <Button onClick={() => void auth.signIn('web', returnTo)}>{t('purchase.signIn')}</Button>
      </section>
    );
  }
  if (!hasAnyRole(session.claims, ['buyer'])) {
    return (
      <section className={styles.panel}>
        <Notice kind="info">{t('purchase.notBuyer')}</Notice>
      </section>
    );
  }

  async function submit(afterPhone: boolean) {
    if (inFlight.current) {
      return;
    }
    const parsed = parseQuantity(quantityText, limit);
    if (parsed.kind !== 'ok') {
      setQuantityError(t('purchase.quantity.error', { max: limit }));
      return;
    }
    setQuantityError(undefined);
    inFlight.current = true;
    setPhase({ kind: 'submitting', afterPhone });
    try {
      const response = await api.post<OrderWithPaymentSession>(
        '/api/v1/orders',
        { productId, quantity: parsed.quantity },
        { idempotencyKey: keys.keyFor(purchaseFingerprint(productId, parsed.quantity)) },
      );
      keys.settle();
      const order = response.data;
      const payUrl = safePaymentUrl(order.paymentSession.url);
      const reserveUntil = toLocalMoment(order.reserveExpiresAt, serverClockOffset(response.headers, Date.now()));
      setPhase({ kind: 'created', order, payUrl, reserveUntil });
      if (payUrl) {
        redirectTo(payUrl);
      }
    } catch (error) {
      const problem = classifyPurchaseError(error);
      if (problem.kind === 'phone') {
        // Заказ не создан, ключ остаётся: после подтверждения телефона уйдёт тот же запрос
        setPhase({ kind: 'phone', step: afterPhone ? 'stale' : 'confirm' });
        return;
      }
      keys.settle(error);
      if (problem.kind === 'stock') {
        if (problem.available > 0) {
          setQuantityText(String(Math.min(problem.available, parsed.quantity)));
        }
        onStockChanged();
      } else if (problem.kind === 'unavailable') {
        onStockChanged();
      }
      setPhase({ kind: 'problem', problem });
    } finally {
      inFlight.current = false;
    }
  }

  async function continueAfterPhone() {
    setPhase({ kind: 'phone', step: 'working' });
    const refreshed = await auth.refresh('web');
    if (!refreshed) {
      setPhase({ kind: 'phone', step: 'signIn' });
      return;
    }
    await submit(true);
  }

  const submitting = phase.kind === 'submitting';
  const preview = parseQuantity(quantityText, limit);

  if (phase.kind === 'phone') {
    return (
      <section className={styles.panel}>
        <h2>{t('purchase.phone.title')}</h2>
        <p>{t('purchase.phone.lead')}</p>
        {phase.step === 'confirm' ? <PhoneConfirmationFlow embedded onConfirmed={() => void continueAfterPhone()} /> : null}
        {phase.step === 'working' ? <Notice kind="info">{t('purchase.phone.working')}</Notice> : null}
        {phase.step === 'stale' ? (
          <>
            <Notice kind="error">{t('purchase.phone.stale')}</Notice>
            <div className={styles.actions}>
              <Button onClick={() => void continueAfterPhone()}>{t('purchase.phone.continue')}</Button>
            </div>
          </>
        ) : null}
        {phase.step === 'signIn' ? (
          <>
            <Notice kind="error">{t('purchase.phone.refreshFailed')}</Notice>
            <div className={styles.actions}>
              <Button onClick={() => void auth.signIn('web', returnTo)}>{t('purchase.phone.signInAgain')}</Button>
            </div>
          </>
        ) : null}
      </section>
    );
  }

  return (
    <section className={styles.panel}>
      <h2>{t('purchase.title')}</h2>
      {phase.kind === 'problem' ? <ProblemNotice problem={phase.problem} /> : null}
      <form
        noValidate
        onSubmit={(event: FormEvent) => {
          event.preventDefault();
          void submit(false);
        }}
      >
        <TextField
          label={t('purchase.quantity')}
          hint={t('purchase.quantity.hint', { max: limit })}
          error={quantityError}
          inputMode="numeric"
          maxLength={3}
          disabled={submitting}
          value={quantityText}
          onChange={(event) => {
            setQuantityText(event.target.value);
            setQuantityError(undefined);
          }}
        />
        {preview.kind === 'ok' ? <p className={styles.total}>{t('purchase.totalLine', { amount: formatMoney(orderTotal(price, preview.quantity)) })}</p> : null}
        <div className={styles.actions}>
          <Button type="submit" busy={submitting}>
            {t('purchase.buy')}
          </Button>
          {submitting ? (
            <span role="status" className={styles.muted}>
              {t('purchase.working')}
            </span>
          ) : null}
        </div>
      </form>
    </section>
  );
}

function ProblemNotice({ problem }: { problem: PurchaseProblem }) {
  switch (problem.kind) {
    case 'stock':
      return <Notice kind="error">{problem.available > 0 ? t('purchase.problem.stock', { available: problem.available }) : t('purchase.problem.stockNone')}</Notice>;
    case 'unavailable':
      return <Notice kind="error">{t('purchase.problem.unavailable')}</Notice>;
    case 'limit':
      return (
        <Notice kind="error">
          <p>{t('purchase.problem.limit', { limit: problem.limit ?? '' })}</p>
          <Link to="/account/orders">{t('purchase.problem.limit.link')}</Link>
        </Notice>
      );
    case 'session':
      return <Notice kind="error">{t('purchase.problem.session')}</Notice>;
    case 'other':
      return <Notice kind="error">{problem.message}</Notice>;
    case 'phone':
      return null;
  }
}

interface CreatedProps {
  order: OrderWithPaymentSession;
  payUrl: string | undefined;
  reserveUntil: number | undefined;
}

/** Заказ оформлен: браузер уходит на страницу оплаты, а здесь остаются отсчёт резерва и ссылки на случай, если переход не случился. */
function Created({ order, payUrl, reserveUntil }: CreatedProps) {
  const seconds = useRemainingSeconds(reserveUntil);
  const expired = reserveUntil !== undefined && seconds === 0;
  return (
    <section className={styles.panel} aria-live="polite">
      <h2>{t('purchase.created.title', { number: order.orderNumber })}</h2>
      {!payUrl ? <Notice kind="error">{t('purchase.created.badUrl')}</Notice> : null}
      {payUrl && expired ? <Notice kind="info">{t('purchase.created.expired')}</Notice> : null}
      {payUrl && !expired ? <p>{reserveUntil === undefined ? t('purchase.created.textNoTime') : t('purchase.created.text', { time: formatCountdown(seconds) })}</p> : null}
      <div className={styles.actions}>
        {payUrl && !expired ? <a href={payUrl}>{t('purchase.created.pay')}</a> : null}
        <Link to={`/account/orders/${encodeURIComponent(order.orderId)}`}>{t('purchase.created.order')}</Link>
      </div>
    </section>
  );
}
