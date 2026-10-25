import { describe, expect, it } from 'vitest';
import { formatCountdown } from './format';

describe('обратный отсчёт', () => {
  it('минуты и секунды с ведущим нулём у секунд', () => {
    expect(formatCountdown(0)).toBe('0:00');
    expect(formatCountdown(5)).toBe('0:05');
    expect(formatCountdown(59)).toBe('0:59');
    expect(formatCountdown(60)).toBe('1:00');
    expect(formatCountdown(905)).toBe('15:05');
  });

  it('отрицательное число и дробная часть не ломают показ', () => {
    expect(formatCountdown(-3)).toBe('0:00');
    expect(formatCountdown(61.9)).toBe('1:01');
  });
});
