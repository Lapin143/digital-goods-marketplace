import { describe, expect, it } from 'vitest';
import { isFinalStatus, pollDelay } from './polling';

describe('опрос статуса заказа', () => {
  it('конечные состояния останавливают опрос, остальные продолжают', () => {
    for (const status of ['issued', 'cancelled', 'refunded']) {
      expect(isFinalStatus(status), status).toBe(true);
    }
    for (const status of ['created', 'awaiting_payment', 'paid', 'неизвестный']) {
      expect(isFinalStatus(status), status).toBe(false);
    }
  });

  it('пауза растёт по ряду и упирается в 30 секунд', () => {
    const delays = [0, 1, 2, 3, 4, 5, 6, 7, 8, 50].map(pollDelay);
    expect(delays).toEqual([2000, 2000, 3000, 5000, 8000, 13000, 20000, 30000, 30000, 30000]);
  });

  it('пауза никогда не убывает', () => {
    let previous = 0;
    for (let updates = 0; updates < 30; updates += 1) {
      const delay = pollDelay(updates);
      expect(delay).toBeGreaterThanOrEqual(previous);
      previous = delay;
    }
  });
});
