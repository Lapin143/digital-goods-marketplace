import { describe, expect, it } from 'vitest';
import { STOCK_MAX, parseQuantity, stockEtag } from './stock';

describe('остаток из поля ввода', () => {
  it('целое от нуля до миллиона принимается, пробелы внутри числа убираются', () => {
    expect(parseQuantity('0')).toEqual({ kind: 'value', value: 0 });
    expect(parseQuantity(' 25 ')).toEqual({ kind: 'value', value: 25 });
    expect(parseQuantity('1 000')).toEqual({ kind: 'value', value: 1000 });
    expect(parseQuantity(String(STOCK_MAX))).toEqual({ kind: 'value', value: 1_000_000 });
  });

  it('пусто это «пусто», остальное неверное значение', () => {
    expect(parseQuantity('')).toEqual({ kind: 'empty' });
    expect(parseQuantity('   ')).toEqual({ kind: 'empty' });
    for (const bad of ['-1', '1,5', '1.5', 'abc', '1e3', '1000001', '00000001000000000']) {
      expect(parseQuantity(bad), bad).toEqual({ kind: 'invalid' });
    }
  });

  it('версия для If-Match в кавычках', () => {
    expect(stockEtag(4)).toBe('"4"');
    expect(stockEtag(0)).toBe('"0"');
  });
});
