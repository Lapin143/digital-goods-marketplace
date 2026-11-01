import { describe, expect, it } from 'vitest';
import { boundsText, checkValue, describeValue, formatParameterValue, formatPercent, formatSeconds, parseWholeNumber, unitName } from './units';

describe('единицы параметров (TC-806)', () => {
  it.each([
    [0, '0 с'],
    [45, '45 с'],
    [60, '1 мин'],
    [720, '12 мин'],
    [3600, '1 ч'],
    [5400, '1 ч 30 мин'],
    [3599, '59 мин 59 с'],
    [259200, '3 сут'],
    [90061, '1 сут 1 ч 1 мин 1 с'],
  ])('%i секунд читаются как «%s»', (seconds, expected) => {
    expect(formatSeconds(seconds)).toBe(expected);
  });

  it.each([
    [200, '2 %'],
    [250, '2,5 %'],
    [1, '0,01 %'],
    [3000, '30 %'],
  ])('%i базисных пунктов читаются как «%s»', (basisPoints, expected) => {
    expect(formatPercent(basisPoints)).toBe(expected);
  });

  it('счётчик с разделителем тысяч, незнакомая единица как число', () => {
    expect(formatParameterValue('count', 3)).toBe('3');
    expect(formatParameterValue('count', 1000)).toMatch(/^1\s000$/);
    expect(formatParameterValue('flag', 1)).toBe('1');
  });

  it('значение показано читаемо и в единицах параметра, повтор не пишется', () => {
    expect(describeValue('seconds', 900)).toBe('15 мин (900 с)');
    expect(describeValue('seconds', 45)).toBe('45 с');
    expect(describeValue('basis_points', 200)).toBe('2 % (200 б. п.)');
    expect(describeValue('count', 3)).toBe('3');
  });

  it('единица в подписи поля называет единицу параметра', () => {
    expect(unitName('seconds')).toBe('секунд');
    expect(unitName('basis_points')).toContain('базисных пунктов');
    expect(unitName('count')).toBe('штук');
    expect(unitName('иная')).toBe('единиц');
  });

  it('границы в подсказке: числа в единицах параметра и читаемая форма, у счётчика только числа', () => {
    expect(boundsText({ unit: 'seconds', minValue: 300, maxValue: 3600 })).toBe('От 300 до 3600 (5 мин – 1 ч)');
    expect(boundsText({ unit: 'basis_points', minValue: 1, maxValue: 3000 })).toBe('От 1 до 3000 (0,01 % – 30 %)');
    expect(boundsText({ unit: 'count', minValue: 1, maxValue: 20 })).toBe('От 1 до 20');
  });
});

describe('проверка введённого значения (TC-807)', () => {
  const seconds = { unit: 'seconds', minValue: 300, maxValue: 3600 } as const;

  it.each([
    ['300', 300],
    [' 3600 ', 3600],
    ['900', 900],
    ['1', 1],
    ['999999999999999', 999999999999999],
  ])('«%s» это число %i', (text, expected) => {
    expect(parseWholeNumber(text)).toBe(expected);
  });

  it.each(['', '  ', '0', '00', '-5', '+5', '1.5', '1,5', '1 000', '12a', '1e3', '9999999999999999'])('«%s» не положительное целое', (text) => {
    expect(parseWholeNumber(text)).toBeUndefined();
  });

  it('значение в границах принимается, вне границ и не число отклоняются с названием границ', () => {
    expect(checkValue(seconds, '1200')).toEqual({ ok: true, value: 1200 });
    expect(checkValue(seconds, '300')).toEqual({ ok: true, value: 300 });
    expect(checkValue(seconds, '3600')).toEqual({ ok: true, value: 3600 });

    const low = checkValue(seconds, '299');
    expect(low.ok).toBe(false);
    expect(!low.ok && low.message).toContain('От 300 до 3600');
    const high = checkValue(seconds, '3601');
    expect(high.ok).toBe(false);
    expect(!high.ok && high.message).toContain('От 300 до 3600');

    const text = checkValue(seconds, 'пятнадцать');
    expect(text.ok).toBe(false);
    expect(!text.ok && text.message).not.toContain('От 300');
  });
});
