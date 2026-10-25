import { hasText, t } from '../../i18n';
import type { TextKey } from '../../i18n';
import { formatCountdown } from '../../ui/format';
import type { Order, OrderStatus } from './types';

type Tone = 'neutral' | 'good' | 'warn' | 'bad';

const TONES: Record<OrderStatus, Tone> = {
  created: 'neutral',
  awaiting_payment: 'warn',
  paid: 'warn',
  issued: 'good',
  cancelled: 'bad',
  refunded: 'neutral',
};

/** Статус заказа словами по SM-01. Статус, которого интерфейс не знает (перечисление может вырасти), показывается нейтрально. */
export function orderStatusLabel(status: string): string {
  const key = `order.status.${status}`;
  return hasText(key) ? t(key) : t('order.status.unknown');
}

export function orderStatusTone(status: string): Tone {
  return status in TONES ? TONES[status as OrderStatus] : 'neutral';
}

/** Причина отмены понятными словами; неизвестная причина (перечисление открытое) получает общий текст. */
export function cancelReasonText(reason: string | null | undefined): string {
  const key = `order.cancel.${reason ?? ''}`;
  return reason && hasText(key) ? t(key) : t('order.cancel.unknown');
}

/** Срок получения ключа после оплаты, мс: через полчаса письмо уже считается задержанным и по нему работает поддержка (FT-7.3). */
export const DELIVERY_WINDOW_MS = 30 * 60_000;

export interface StateContext {
  /** Секунд до конца резерва; `undefined`, если срок неизвестен. */
  reserveSeconds: number | undefined;
  /** Сколько миллисекунд прошло с оплаты; `undefined`, если оплаты нет. */
  paidAgoMs: number | undefined;
}

/** Что сказать покупателю о заказе: текст зависит от статуса, остатка резерва и времени, прошедшего с оплаты. */
export function orderStateText(order: Order, context: StateContext): { key: TextKey; text: string } {
  const address = order.delivery.address;
  switch (order.status) {
    case 'created':
      return { key: 'order.state.created', text: t('order.state.created') };
    case 'awaiting_payment':
      if (context.reserveSeconds === undefined) {
        return { key: 'order.state.awaiting_payment.noTime', text: t('order.state.awaiting_payment.noTime') };
      }
      return context.reserveSeconds <= 0
        ? { key: 'order.state.awaiting_payment.expired', text: t('order.state.awaiting_payment.expired') }
        : { key: 'order.state.awaiting_payment', text: t('order.state.awaiting_payment', { time: formatCountdown(context.reserveSeconds) }) };
    case 'paid':
      return context.paidAgoMs !== undefined && context.paidAgoMs > DELIVERY_WINDOW_MS
        ? { key: 'order.state.paid.late', text: t('order.state.paid.late', { address }) }
        : { key: 'order.state.paid', text: t('order.state.paid', { address }) };
    case 'issued':
      return { key: 'order.state.issued', text: t('order.state.issued', { address }) };
    case 'cancelled':
      return { key: 'order.state.cancelled', text: t('order.state.cancelled', { reason: cancelReasonText(order.cancelReason) }) };
    case 'refunded':
      return { key: 'order.state.refunded', text: t('order.state.refunded') };
    default:
      return { key: 'order.state.unknown', text: t('order.state.unknown') };
  }
}
