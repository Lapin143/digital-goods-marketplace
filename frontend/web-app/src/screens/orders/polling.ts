import type { OrderStatus } from './types';

/** Состояния, после которых заказ сам уже не меняется: опрос на них останавливается. */
const FINAL: readonly string[] = ['issued', 'cancelled', 'refunded'] satisfies readonly OrderStatus[];

export function isFinalStatus(status: string): boolean {
  return FINAL.includes(status);
}

/** Паузы между опросами, мс: сначала часто (оплата и выдача занимают секунды), потом реже, чтобы не нагружать шлюз (ADR-021). */
const DELAYS = [2000, 3000, 5000, 8000, 13000, 20000, 30000] as const;

/**
 * Пауза до следующего опроса по числу уже полученных ответов (`dataUpdateCount`): растёт по ряду до 30 секунд и там остаётся.
 * Постоянный короткий интервал нагружал бы сервис тысячами одинаковых запросов от тех, кто оставил вкладку открытой.
 */
export function pollDelay(updates: number): number {
  const index = Math.min(Math.max(updates - 1, 0), DELAYS.length - 1);
  return DELAYS[index] ?? 30000;
}
