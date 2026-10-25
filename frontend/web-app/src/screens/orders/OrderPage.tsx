import { useRef, useState } from 'react';
import { Link, Navigate, useParams } from 'react-router-dom';
import { useApi } from '../../api/ApiContext';
import { toLocalMoment } from '../../api/clock';
import { ApiError, problemMessage } from '../../api/problem';
import { redirectTo } from '../../api/redirect';
import { t } from '../../i18n';
import { Badge } from '../../ui/Badge';
import { Button } from '../../ui/Button';
import { formatMoment } from '../../ui/format';
import { Notice } from '../../ui/Notice';
import { StatePanel } from '../../ui/StatePanel';
import { useRemainingSeconds } from '../../ui/useCountdown';
import { formatMoney } from '../storefront/money';
import { orderStateText, orderStatusLabel, orderStatusTone } from './labels';
import { isFinalStatus } from './polling';
import { safePaymentUrl } from './purchase';
import type { OrderView } from './useOrders';
import { useOrder } from './useOrders';
import type { PaymentSession } from './types';
import styles from './Orders.module.css';

/**
 * Адрес возврата со страницы шлюза (`/orders/<id>`, Р-136) и ссылки из старых писем ведут на тот же экран заказа:
 * маршрут один, `/account/orders/<id>`, на него указывает письмо «Заказ создан» (Р-153).
 */
export function OrderReturnRedirect() {
  const { orderId = '' } = useParams();
  return <Navigate to={`/account/orders/${encodeURIComponent(orderId)}`} replace />;
}

/**
 * Экран заказа (US-5.9, US-5.3; шаг 20 Ф4). Статус по SM-01 понятными словами, обратный отсчёт резерва, «Оплатить» для заказа,
 * который ждёт оплаты, причина отмены. Значений ключей здесь нет ни в каком состоянии: сервис заказов их не отдаёт (FT-5.4),
 * ключи приходят только письмом (INV-20). Возврат со страницы шлюза ничего не решает: статус меняет вебхук, экран узнаёт о нём опросом.
 * Чужой и несуществующий заказ одинаково дают 404 и одинаковую страницу: существование чужого заказа не раскрывается.
 */
export function OrderPage() {
  const { orderId = '' } = useParams();
  const query = useOrder(orderId);

  if (query.isPending) {
    return <StatePanel kind="loading" />;
  }
  const view = query.data;
  if (!view) {
    if (query.error instanceof ApiError && query.error.status === 404) {
      return (
        <section>
          <h1>{t('order.notFound.title')}</h1>
          <p>{t('order.notFound.text')}</p>
          <Link to="/account/orders">{t('order.back')}</Link>
        </section>
      );
    }
    return <StatePanel kind="error" message={problemMessage(query.error)} onRetry={() => void query.refetch()} />;
  }
  return <OrderDetails view={view} connectionLost={query.isRefetchError} onRefresh={() => void query.refetch()} />;
}

interface DetailsProps {
  view: OrderView;
  connectionLost: boolean;
  onRefresh: () => void;
}

function OrderDetails({ view, connectionLost, onRefresh }: DetailsProps) {
  const api = useApi();
  const { order } = view;
  const awaiting = order.status === 'awaiting_payment';
  const reserveUntil = toLocalMoment(order.reserveExpiresAt, view.clockOffset);
  const remaining = useRemainingSeconds(awaiting ? reserveUntil : undefined);
  const paidAt = toLocalMoment(order.paidAt, view.clockOffset);
  const reserveSeconds = awaiting && reserveUntil !== undefined ? remaining : undefined;
  const state = orderStateText(order, { reserveSeconds, paidAgoMs: paidAt === undefined ? undefined : Date.now() - paidAt });
  const payable = awaiting && !(reserveSeconds !== undefined && reserveSeconds <= 0);

  const [paying, setPaying] = useState(false);
  const [payError, setPayError] = useState<string | undefined>();
  const inFlight = useRef(false);

  async function pay() {
    if (inFlight.current) {
      return;
    }
    inFlight.current = true;
    setPaying(true);
    setPayError(undefined);
    try {
      const response = await api.get<PaymentSession>(`/api/v1/orders/${encodeURIComponent(order.orderId)}/payment-session`);
      const url = safePaymentUrl(response.data.url);
      if (url) {
        redirectTo(url);
      } else {
        setPayError(t('purchase.created.badUrl'));
      }
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        // Сессия закончилась или заказ уже не ждёт оплаты: показываем свежий статус
        setPayError(t('order.pay.expired'));
        onRefresh();
      } else {
        setPayError(problemMessage(error));
      }
    } finally {
      inFlight.current = false;
      setPaying(false);
    }
  }

  const stateKind = order.status === 'issued' ? 'success' : order.status === 'cancelled' ? 'error' : 'info';
  return (
    <article>
      <Link to="/account/orders">{t('order.back')}</Link>
      <h1>{t('order.title', { number: order.orderNumber })}</h1>
      {connectionLost ? <Notice kind="error">{t('order.connectionLost')}</Notice> : null}
      <div className={styles.state}>
        <Notice kind={stateKind}>{state.text}</Notice>
      </div>
      <dl className={styles.facts}>
        <dt>{t('order.status')}</dt>
        <dd>
          <Badge tone={orderStatusTone(order.status)}>{orderStatusLabel(order.status)}</Badge>
        </dd>
        <dt>{t('order.product')}</dt>
        <dd>
          <Link to={`/products/${encodeURIComponent(order.productId)}`}>{order.productTitle}</Link>
        </dd>
        <dt>{t('order.quantity')}</dt>
        <dd>{order.quantity}</dd>
        <dt>{t('order.unitPrice')}</dt>
        <dd>{formatMoney(order.unitPrice)}</dd>
        <dt>{t('order.total')}</dt>
        <dd>{formatMoney(order.total)}</dd>
        <dt>{t('order.createdAt')}</dt>
        <dd>{formatMoment(order.createdAt)}</dd>
        {order.paidAt ? (
          <>
            <dt>{t('order.paidAt')}</dt>
            <dd>{formatMoment(order.paidAt)}</dd>
          </>
        ) : null}
        {order.issuedAt ? (
          <>
            <dt>{t('order.issuedAt')}</dt>
            <dd>{formatMoment(order.issuedAt)}</dd>
          </>
        ) : null}
      </dl>
      {payError ? <Notice kind="error">{payError}</Notice> : null}
      <div className={styles.actions}>
        {payable ? (
          <Button busy={paying} onClick={() => void pay()}>
            {paying ? t('order.pay.opening') : t('order.pay')}
          </Button>
        ) : null}
        {order.status === 'cancelled' ? <Link to={`/products/${encodeURIComponent(order.productId)}`}>{t('order.buyAgain')}</Link> : null}
      </div>
      {isFinalStatus(order.status) ? null : <p className={styles.muted}>{t('order.autoRefresh')}</p>}
    </article>
  );
}
