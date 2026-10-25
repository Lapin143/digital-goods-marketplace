import { describe, expect, it } from 'vitest';
import { ApiError } from '../../api/problem';
import { classifyPurchaseError, orderTotal, parseQuantity, purchaseFingerprint, quantityLimit, safePaymentUrl } from './purchase';

describe('количество в заказе (INV-10)', () => {
  it('предел это меньшее из остатка и десяти, отрицательный остаток даёт ноль', () => {
    expect(quantityLimit(3)).toBe(3);
    expect(quantityLimit(10)).toBe(10);
    expect(quantityLimit(57)).toBe(10);
    expect(quantityLimit(0)).toBe(0);
    expect(quantityLimit(-4)).toBe(0);
    expect(quantityLimit(2.9)).toBe(2);
  });

  it('принимает целые числа от 1 до предела', () => {
    expect(parseQuantity('1', 3)).toEqual({ kind: 'ok', quantity: 1 });
    expect(parseQuantity(' 3 ', 3)).toEqual({ kind: 'ok', quantity: 3 });
    expect(parseQuantity('10', 10)).toEqual({ kind: 'ok', quantity: 10 });
  });

  it('отклоняет ноль, больше предела, дроби, знаки, буквы и пустое поле', () => {
    for (const text of ['', ' ', '0', '4', '11', '-1', '+1', '1.5', '1,5', 'два', '1e1', '0x2', '00000']) {
      expect(parseQuantity(text, 3).kind, text).toBe('invalid');
    }
  });
});

describe('итог до оформления', () => {
  it('цена за штуку на количество в копейках, валюта прежняя', () => {
    expect(orderTotal({ amount: 149900, currency: 'RUB' }, 2)).toEqual({ amount: 299800, currency: 'RUB' });
  });
});

describe('ключ идемпотентности привязан к содержимому запроса', () => {
  it('другое количество или другой товар дают другой отпечаток', () => {
    expect(purchaseFingerprint('p-1', 2)).toBe(purchaseFingerprint('p-1', 2));
    expect(purchaseFingerprint('p-1', 2)).not.toBe(purchaseFingerprint('p-1', 3));
    expect(purchaseFingerprint('p-1', 2)).not.toBe(purchaseFingerprint('p-2', 2));
  });
});

describe('разбор ответа на создание заказа', () => {
  const failure = (status: number, code: string, extra: Record<string, unknown> = {}) => new ApiError({ status, code, ...extra });

  it('403 phone-not-confirmed: нужно подтвердить телефон', () => {
    expect(classifyPurchaseError(failure(403, 'phone-not-confirmed'))).toEqual({ kind: 'phone' });
  });

  it('409 insufficient-stock: сколько осталось, отрицательное число считается нулём', () => {
    expect(classifyPurchaseError(failure(409, 'insufficient-stock', { available: 1 }))).toEqual({ kind: 'stock', available: 1 });
    expect(classifyPurchaseError(failure(409, 'insufficient-stock', { available: -3 }))).toEqual({ kind: 'stock', available: 0 });
    expect(classifyPurchaseError(failure(409, 'insufficient-stock'))).toEqual({ kind: 'stock', available: 0 });
  });

  it('409 product-unavailable и unpaid-orders-limit', () => {
    expect(classifyPurchaseError(failure(409, 'product-unavailable'))).toEqual({ kind: 'unavailable' });
    expect(classifyPurchaseError(failure(409, 'unpaid-orders-limit', { limit: 3 }))).toEqual({ kind: 'limit', limit: 3 });
  });

  it('503 payment-session-unavailable отличается от других сбоев', () => {
    expect(classifyPurchaseError(failure(503, 'payment-session-unavailable'))).toEqual({ kind: 'session' });
    expect(classifyPurchaseError(failure(503, 'dependency-unavailable')).kind).toBe('other');
  });

  it('те же коды с другим статусом и посторонние ошибки получают общий текст', () => {
    expect(classifyPurchaseError(failure(500, 'insufficient-stock')).kind).toBe('other');
    expect(classifyPurchaseError(failure(0, 'network-error')).kind).toBe('other');
    expect(classifyPurchaseError(new Error('сбой')).kind).toBe('other');
  });
});

describe('адрес страницы оплаты', () => {
  it('пускает только http и https', () => {
    expect(safePaymentUrl('https://pay.gateway.example/session/abc123')).toBe('https://pay.gateway.example/session/abc123');
    expect(safePaymentUrl('http://localhost:8443/payment/pay/1')).toBe('http://localhost:8443/payment/pay/1');
  });

  it('отклоняет чужие схемы, относительные адреса и мусор', () => {
    for (const url of ['javascript:alert(1)', 'data:text/html,<b>x</b>', 'ftp://host/file', '/payment/pay/1', '', 'не адрес']) {
      expect(safePaymentUrl(url), url).toBeUndefined();
    }
  });
});
