import { describe, expect, it } from 'vitest';
import { t } from '../../i18n';
import { formatMoment } from '../../ui/format';
import { issuanceMethodLabel } from './labels';
import { dayStartIso, nextDayStartIso, orderNote, sellerCancelReasonText } from './sellerOrders';

describe('границы дат фильтра', () => {
  it('начало выбранных суток по местному времени', () => {
    expect(dayStartIso('2026-10-17')).toBe(new Date(2026, 9, 17, 0, 0, 0).toISOString());
  });

  it('верхняя граница это начало следующих суток: выбранный день входит целиком, переход через месяц и год', () => {
    expect(nextDayStartIso('2026-10-17')).toBe(new Date(2026, 9, 18, 0, 0, 0).toISOString());
    expect(nextDayStartIso('2026-10-31')).toBe(new Date(2026, 10, 1, 0, 0, 0).toISOString());
    expect(nextDayStartIso('2026-12-31')).toBe(new Date(2027, 0, 1, 0, 0, 0).toISOString());
  });

  it('нечитаемая дата даёт undefined', () => {
    expect(dayStartIso('вчера')).toBeUndefined();
    expect(nextDayStartIso('')).toBeUndefined();
  });
});

describe('причина отмены для продавца', () => {
  it('молчание продавца названо словами с сроком', () => {
    expect(sellerCancelReasonText('seller_api_timeout')).toBe(t('sellerOrders.cancel.seller_api_timeout'));
    expect(sellerCancelReasonText('seller_api_timeout')).toContain('10 минут');
  });

  it('все известные причины имеют текст, неизвестная и пустая получают общий', () => {
    for (const reason of ['out_of_stock', 'payment_declined', 'reservation_expired', 'payment_session_failed', 'system_error']) {
      expect(sellerCancelReasonText(reason), reason).toBe(t(`sellerOrders.cancel.${reason}` as 'sellerOrders.cancel.out_of_stock'));
    }
    expect(sellerCancelReasonText('новая_причина')).toBe(t('sellerOrders.cancel.unknown'));
    expect(sellerCancelReasonText(null)).toBe(t('sellerOrders.cancel.unknown'));
    expect(sellerCancelReasonText(undefined)).toBe(t('sellerOrders.cancel.unknown'));
  });
});

describe('пояснение к статусу заказа', () => {
  const base = { cancelReason: null, paidAt: null, issuedAt: null, refundedAt: null };

  it('отменённый: причина словами', () => {
    expect(orderNote({ ...base, status: 'cancelled', cancelReason: 'seller_api_timeout' })).toBe(
      t('sellerOrders.note.cancelled', { reason: t('sellerOrders.cancel.seller_api_timeout') }),
    );
  });

  it('оплаченный, выданный и возвращённый: время события', () => {
    expect(orderNote({ ...base, status: 'paid', paidAt: '2026-10-17T09:00:00Z' })).toBe(t('sellerOrders.note.paid', { time: formatMoment('2026-10-17T09:00:00Z') }));
    expect(orderNote({ ...base, status: 'issued', issuedAt: '2026-10-17T09:05:00Z' })).toBe(t('sellerOrders.note.issued', { time: formatMoment('2026-10-17T09:05:00Z') }));
    expect(orderNote({ ...base, status: 'refunded', refundedAt: '2026-10-17T10:00:00Z' })).toBe(t('sellerOrders.note.refunded', { time: formatMoment('2026-10-17T10:00:00Z') }));
  });

  it('создаётся и ждёт оплаты: пояснения нет; нет времени события: пояснения нет', () => {
    expect(orderNote({ ...base, status: 'created' })).toBeUndefined();
    expect(orderNote({ ...base, status: 'awaiting_payment' })).toBeUndefined();
    expect(orderNote({ ...base, status: 'issued' })).toBeUndefined();
  });
});

describe('способ выдачи', () => {
  it('подписи двух способов, незнакомый способ как есть', () => {
    expect(issuanceMethodLabel('key_pool')).toBe(t('issuance.key_pool'));
    expect(issuanceMethodLabel('seller_api')).toBe(t('issuance.seller_api'));
    expect(issuanceMethodLabel('courier')).toBe('courier');
  });
});
