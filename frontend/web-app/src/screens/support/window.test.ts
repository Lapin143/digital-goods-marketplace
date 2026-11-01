import { describe, expect, it } from 'vitest';
import { SUPPORT_WINDOW_HOURS, SUPPORT_WINDOW_MS, windowClosed } from './window';

const NOW = Date.parse('2026-10-20T12:00:00.000Z');
const HOUR = 3_600_000;

describe('TC-701. Окно обращения 72 часа на экране', () => {
  it('срок равен 72 часам, как параметр платформы по умолчанию', () => {
    expect(SUPPORT_WINDOW_HOURS).toBe(72);
    expect(SUPPORT_WINDOW_MS).toBe(259_200_000);
  });

  it('считается от первичной выдачи: ровно 72 часа ещё можно, на миллисекунду больше уже нельзя', () => {
    expect(windowClosed(NOW - 72 * HOUR, NOW)).toBe(false);
    expect(windowClosed(NOW - 72 * HOUR - 1, NOW)).toBe(true);
    expect(windowClosed(NOW - HOUR, NOW)).toBe(false);
  });

  it('времени выдачи нет: экран не закрывает кнопку сам, решает сервер', () => {
    expect(windowClosed(undefined, NOW)).toBe(false);
  });
});
