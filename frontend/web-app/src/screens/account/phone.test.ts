import { describe, expect, it } from 'vitest';
import { isCode, normalizePhone } from './phone';

describe('номер телефона', () => {
  it('приводит привычные записи к формату API', () => {
    for (const input of ['+79001234567', '8 900 123-45-67', '8(900)1234567', '7 900 123 45 67', ' +7 (900) 123-45-67 ']) {
      expect(normalizePhone(input)).toBe('+79001234567');
    }
  });

  it('чужие и неполные номера отклоняются', () => {
    for (const input of ['', '123', '+7900123456', '+790012345678', '+380501234567', '89001234ab7', '+1 202 555 0100']) {
      expect(normalizePhone(input)).toBeUndefined();
    }
  });

  it('код это ровно шесть цифр', () => {
    expect(isCode('123456')).toBe(true);
    for (const input of ['', '12345', '1234567', '12345a', ' 12345']) {
      expect(isCode(input)).toBe(false);
    }
  });
});
