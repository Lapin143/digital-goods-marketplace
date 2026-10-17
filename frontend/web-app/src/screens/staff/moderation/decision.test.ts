import { describe, expect, it } from 'vitest';
import { REASON_MAX, checkReason, formatCountdown } from './decision';

describe('причина решения', () => {
  it('пустая строка и одни пробелы не подходят', () => {
    expect(checkReason('')).toBe('moderation.error.reasonRequired');
    expect(checkReason('   \n\t ')).toBe('moderation.error.reasonRequired');
  });

  it('1000 символов подходят, 1001 нет, пробелы по краям не считаются', () => {
    expect(checkReason('а'.repeat(REASON_MAX))).toBeUndefined();
    expect(checkReason(`  ${'а'.repeat(REASON_MAX)}  `)).toBeUndefined();
    expect(checkReason('а'.repeat(REASON_MAX + 1))).toBe('moderation.error.reasonTooLong');
  });

  it('символ вне основной плоскости считается за один, как в контракте', () => {
    expect(checkReason('😀'.repeat(REASON_MAX))).toBeUndefined();
    expect(checkReason('😀'.repeat(REASON_MAX + 1))).toBe('moderation.error.reasonTooLong');
  });
});

describe('обратный отсчёт срока ссылок', () => {
  it('минуты и секунды с ведущим нулём', () => {
    expect(formatCountdown(245)).toBe('4:05');
    expect(formatCountdown(300)).toBe('5:00');
    expect(formatCountdown(59)).toBe('0:59');
  });

  it('отрицательное и дробное значение приводятся к целому не меньше нуля', () => {
    expect(formatCountdown(-3)).toBe('0:00');
    expect(formatCountdown(61.9)).toBe('1:01');
  });
});
