import { ApiError, problemMessage } from '../../api/problem';
import type { MoneyValue } from '../storefront/money';

/** Предел количества в одном заказе (INV-10); у товара он может быть ниже из-за остатка. */
export const MAX_ORDER_QUANTITY = 10;

export type QuantityCheck = { kind: 'ok'; quantity: number } | { kind: 'invalid' };

/** Предел для поля: не больше десяти и не больше остатка товара (`maxOrderQuantity` из карточки). */
export function quantityLimit(orderUpTo: number): number {
  return Math.max(0, Math.min(MAX_ORDER_QUANTITY, Math.floor(orderUpTo)));
}

/** Количество из поля ввода: целое число от 1 до `max`, без знака, дробей и лишних символов. */
export function parseQuantity(text: string, max: number): QuantityCheck {
  const clean = text.trim();
  if (!/^\d{1,3}$/.test(clean)) {
    return { kind: 'invalid' };
  }
  const quantity = Number(clean);
  return quantity >= 1 && quantity <= max ? { kind: 'ok', quantity } : { kind: 'invalid' };
}

/** Сумма заказа для показа до оформления: цена за штуку на количество. Окончательную сумму фиксирует сервер (INV-12). */
export function orderTotal(unit: MoneyValue, quantity: number): MoneyValue {
  return { amount: unit.amount * quantity, currency: unit.currency };
}

/**
 * Ключ идемпотентности привязан к содержимому запроса: тот же товар и то же количество (повтор после сбоя сети, двойное нажатие)
 * получают тот же ключ, а другое количество новый (иначе сервер ответил бы 422 о повторном использовании ключа).
 */
export function purchaseFingerprint(productId: string, quantity: number): string {
  return `order:${productId}:${quantity}`;
}

/** Что покупатель видит вместо перехода к оплате. */
export type PurchaseProblem =
  | { kind: 'phone' }
  | { kind: 'stock'; available: number }
  | { kind: 'unavailable' }
  | { kind: 'limit'; limit: number | undefined }
  | { kind: 'session' }
  | { kind: 'other'; message: string };

/** Разбор ответа на создание заказа (OP-50): коды из контракта получают свои состояния, остальное общий текст. */
export function classifyPurchaseError(error: unknown): PurchaseProblem {
  if (error instanceof ApiError) {
    if (error.status === 403 && error.code === 'phone-not-confirmed') {
      return { kind: 'phone' };
    }
    if (error.status === 409 && error.code === 'insufficient-stock') {
      return { kind: 'stock', available: Math.max(0, error.problem.available ?? 0) };
    }
    if (error.status === 409 && error.code === 'product-unavailable') {
      return { kind: 'unavailable' };
    }
    if (error.status === 409 && error.code === 'unpaid-orders-limit') {
      return { kind: 'limit', limit: error.problem.limit };
    }
    if (error.status === 503 && error.code === 'payment-session-unavailable') {
      return { kind: 'session' };
    }
  }
  return { kind: 'other', message: problemMessage(error) };
}

/**
 * Адрес страницы оплаты от сервера пускается в переход только с протоколом http или https: любая другая схема
 * (`javascript:`, `data:`) была бы выполнением чужого кода на нашей странице.
 */
export function safePaymentUrl(url: string): string | undefined {
  try {
    const parsed = new URL(url);
    return parsed.protocol === 'https:' || parsed.protocol === 'http:' ? parsed.toString() : undefined;
  } catch {
    return undefined;
  }
}
