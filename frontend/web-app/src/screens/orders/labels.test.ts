import { describe, expect, it } from 'vitest';
import { t } from '../../i18n';
import { orderFixture } from '../../test/orders';
import { DELIVERY_WINDOW_MS, cancelReasonText, orderStateText, orderStatusLabel, orderStatusTone } from './labels';
import type { Order } from './types';

const order = (options: Parameters<typeof orderFixture>[0]) => orderFixture(options) as unknown as Order;

describe('статусы по SM-01', () => {
  it('каждый статус назван словами, неизвестный получает нейтральную подпись', () => {
    expect(orderStatusLabel('created')).toBe(t('order.status.created'));
    expect(orderStatusLabel('awaiting_payment')).toBe(t('order.status.awaiting_payment'));
    expect(orderStatusLabel('paid')).toBe(t('order.status.paid'));
    expect(orderStatusLabel('issued')).toBe(t('order.status.issued'));
    expect(orderStatusLabel('cancelled')).toBe(t('order.status.cancelled'));
    expect(orderStatusLabel('refunded')).toBe(t('order.status.refunded'));
    expect(orderStatusLabel('arbitration')).toBe(t('order.status.unknown'));
  });

  it('цвет метки: выдан хороший, отменён плохой, остальное нейтральное или ожидание', () => {
    expect(orderStatusTone('issued')).toBe('good');
    expect(orderStatusTone('cancelled')).toBe('bad');
    expect(orderStatusTone('awaiting_payment')).toBe('warn');
    expect(orderStatusTone('paid')).toBe('warn');
    expect(orderStatusTone('refunded')).toBe('neutral');
    expect(orderStatusTone('arbitration')).toBe('neutral');
  });
});

describe('причины отмены', () => {
  it('причины, при которых деньги не берутся, говорят об этом, остальные обещают автоматический возврат', () => {
    for (const reason of ['out_of_stock', 'payment_declined', 'payment_session_failed']) {
      expect(cancelReasonText(reason), reason).toContain('Деньги не списаны');
    }
    for (const reason of ['reservation_expired', 'system_error', 'seller_api_timeout']) {
      expect(cancelReasonText(reason), reason).toContain('вернутся автоматически');
    }
  });

  it('неизвестная и пустая причина получают общий текст', () => {
    expect(cancelReasonText('новая_причина')).toBe(t('order.cancel.unknown'));
    expect(cancelReasonText(null)).toBe(t('order.cancel.unknown'));
    expect(cancelReasonText(undefined)).toBe(t('order.cancel.unknown'));
  });
});

describe('что сказать о заказе', () => {
  const none = { reserveSeconds: undefined, paidAgoMs: undefined };

  it('ждёт оплаты: остаток резерва, по нулю резерв закончился, без срока общий текст', () => {
    const waiting = order({ status: 'awaiting_payment' });
    expect(orderStateText(waiting, { ...none, reserveSeconds: 125 }).text).toBe(t('order.state.awaiting_payment', { time: '2:05' }));
    expect(orderStateText(waiting, { ...none, reserveSeconds: 0 }).key).toBe('order.state.awaiting_payment.expired');
    expect(orderStateText(waiting, none).key).toBe('order.state.awaiting_payment.noTime');
  });

  it('оплачен: до получаса ключ придёт, дольше ключ задерживается', () => {
    const paid = order({ status: 'paid' });
    expect(orderStateText(paid, { ...none, paidAgoMs: 60_000 }).key).toBe('order.state.paid');
    expect(orderStateText(paid, { ...none, paidAgoMs: DELIVERY_WINDOW_MS }).key).toBe('order.state.paid');
    expect(orderStateText(paid, { ...none, paidAgoMs: DELIVERY_WINDOW_MS + 1 }).key).toBe('order.state.paid.late');
    expect(orderStateText(paid, none).key).toBe('order.state.paid');
  });

  it('выдан: адрес письма, ключей в тексте нет', () => {
    const text = orderStateText(order({ status: 'issued' }), none).text;
    expect(text).toContain('buyer@mail.example');
    expect(text).toContain('только в письме');
  });

  it('отменён: причина, возвращён: про деньги, неизвестный статус: уточняется', () => {
    expect(orderStateText(order({ status: 'cancelled', cancelReason: 'out_of_stock' }), none).text).toBe(t('order.state.cancelled', { reason: cancelReasonText('out_of_stock') }));
    expect(orderStateText(order({ status: 'refunded' }), none).key).toBe('order.state.refunded');
    expect(orderStateText(order({ status: 'arbitration' }), none).key).toBe('order.state.unknown');
  });
});
