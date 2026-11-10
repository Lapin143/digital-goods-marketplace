import { hasText, t } from '../../i18n';
import { formatMoment } from '../../ui/format';
import type { SellerOrder } from './types';

/** Начало суток выбранной даты («2026-10-17») по местному времени, ISO для `createdFrom` (включительно). */
export function dayStartIso(date: string): string | undefined {
  const start = new Date(`${date}T00:00:00`);
  return Number.isNaN(start.getTime()) ? undefined : start.toISOString();
}

/** Начало следующих суток: `createdTo` не включает границу, поэтому выбранный день входит в выборку целиком. */
export function nextDayStartIso(date: string): string | undefined {
  const start = new Date(`${date}T00:00:00`);
  if (Number.isNaN(start.getTime())) {
    return undefined;
  }
  start.setDate(start.getDate() + 1);
  return start.toISOString();
}

/** Причина отмены глазами продавца: что произошло, без подробностей про покупателя. Неизвестная причина получает общий текст. */
export function sellerCancelReasonText(reason: string | null | undefined): string {
  const key = `sellerOrders.cancel.${reason ?? ''}`;
  return reason && hasText(key) ? t(key) : t('sellerOrders.cancel.unknown');
}

/** Пояснение к статусу заказа для продавца: причина отмены словами или время оплаты, выдачи, возврата. */
export function orderNote(order: Pick<SellerOrder, 'status' | 'cancelReason' | 'paidAt' | 'issuedAt' | 'refundedAt'>): string | undefined {
  switch (order.status) {
    case 'cancelled':
      return t('sellerOrders.note.cancelled', { reason: sellerCancelReasonText(order.cancelReason) });
    case 'refunded':
      return order.refundedAt ? t('sellerOrders.note.refunded', { time: formatMoment(order.refundedAt) }) : undefined;
    case 'issued':
      return order.issuedAt ? t('sellerOrders.note.issued', { time: formatMoment(order.issuedAt) }) : undefined;
    case 'paid':
      return order.paidAt ? t('sellerOrders.note.paid', { time: formatMoment(order.paidAt) }) : undefined;
    default:
      return undefined;
  }
}
