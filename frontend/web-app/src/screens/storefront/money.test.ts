import { describe, expect, it } from 'vitest';
import { formatMoney, parseRubles } from './money';

/** Intl ставит неразрывные пробелы; для сравнения приводим их к обычным. */
function plain(text: string): string {
  return text.replace(/[\s\u00a0\u202f]/g, ' ');
}

describe('формат суммы (TC-326)', () => {
  it('целые рубли без копеек, с разделителем тысяч', () => {
    expect(plain(formatMoney({ amount: 149900, currency: 'RUB' }))).toBe('1 499 ₽');
  });

  it('копейки показываются двумя знаками', () => {
    expect(plain(formatMoney({ amount: 149950, currency: 'RUB' }))).toBe('1 499,50 ₽');
    expect(plain(formatMoney({ amount: 5, currency: 'RUB' }))).toBe('0,05 ₽');
  });

  it('незнакомая валюта не ломает показ', () => {
    expect(formatMoney({ amount: 1000, currency: 'не-валюта' })).toBe('10 не-валюта');
  });
});

describe('рубли из поля ввода (TC-326)', () => {
  it('целое, запятая и точка, пробелы внутри числа', () => {
    expect(parseRubles('1499')).toEqual({ kind: 'value', kopecks: 149900 });
    expect(parseRubles('1 499,5')).toEqual({ kind: 'value', kopecks: 149950 });
    expect(parseRubles('1499.05')).toEqual({ kind: 'value', kopecks: 149905 });
    expect(parseRubles('0')).toEqual({ kind: 'value', kopecks: 0 });
  });

  it('пусто это не ошибка', () => {
    expect(parseRubles('')).toEqual({ kind: 'empty' });
    expect(parseRubles('  ')).toEqual({ kind: 'empty' });
  });

  it('буквы, минус, три знака после запятой и слишком большие суммы отклоняются', () => {
    for (const text of ['abc', '-5', '1,234', '1,', '1e5', '99999999999', '200000000']) {
      expect(parseRubles(text)).toEqual({ kind: 'invalid' });
    }
  });

  it('точное значение без ошибок дробной арифметики', () => {
    expect(parseRubles('19.99')).toEqual({ kind: 'value', kopecks: 1999 });
    expect(parseRubles('0.29')).toEqual({ kind: 'value', kopecks: 29 });
  });
});
