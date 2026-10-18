import { describe, expect, it } from 'vitest';
import { EMPTY_FILTERS, filtersFromParams, filtersToParams, hasFilters, toApiQuery, validateFilters } from './filters';
import type { Filters } from './filters';

const full: Filters = { q: ' тропа ', types: ['game_key', 'gift_card'], platform: ' Steam', region: 'ru', priceFrom: '1000', priceTo: '2 000,50' };

describe('запрос к API (TC-326)', () => {
  it('собирает параметры: типы через запятую, регион в верхнем регистре, цены в копейках', () => {
    expect(toApiQuery(full)).toEqual({
      q: 'тропа',
      productType: 'game_key,gift_card',
      platform: 'Steam',
      region: 'RU',
      priceFrom: 100000,
      priceTo: 200050,
    });
  });

  it('пустые фильтры не дают параметров', () => {
    expect(toApiQuery(EMPTY_FILTERS)).toEqual({
      q: undefined,
      productType: undefined,
      platform: undefined,
      region: undefined,
      priceFrom: undefined,
      priceTo: undefined,
    });
  });

  it('цена ноль уходит как ноль, а не пропадает', () => {
    expect(toApiQuery({ ...EMPTY_FILTERS, priceFrom: '0' }).priceFrom).toBe(0);
  });
});

describe('фильтры в адресе страницы (TC-326)', () => {
  it('записываются и читаются без потерь, пустое не пишется', () => {
    const params = filtersToParams(full);
    expect(params.toString()).toBe('q=%D1%82%D1%80%D0%BE%D0%BF%D0%B0&type=game_key%2Cgift_card&platform=Steam&region=RU&priceFrom=1000&priceTo=2+000%2C50');
    expect(filtersFromParams(params)).toEqual({ q: 'тропа', types: ['game_key', 'gift_card'], platform: 'Steam', region: 'RU', priceFrom: '1000', priceTo: '2 000,50' });
    expect(filtersToParams(EMPTY_FILTERS).toString()).toBe('');
  });

  it('чужие типы, повторы и слишком длинные значения из адреса отбрасываются', () => {
    const params = new URLSearchParams({ type: 'game_key,hack,game_key', q: 'я'.repeat(300), platform: 'p'.repeat(100) });
    const filters = filtersFromParams(params);
    expect(filters.types).toEqual(['game_key']);
    expect(filters.q).toHaveLength(100);
    expect(filters.platform).toHaveLength(64);
  });

  it('hasFilters отличает пустой набор от заданного', () => {
    expect(hasFilters(EMPTY_FILTERS)).toBe(false);
    expect(hasFilters({ ...EMPTY_FILTERS, q: ' ' })).toBe(false);
    expect(hasFilters({ ...EMPTY_FILTERS, types: ['game_key'] })).toBe(true);
  });
});

describe('проверка перед отправкой (TC-326)', () => {
  it('верный набор проходит', () => {
    expect(validateFilters(full)).toEqual({});
    expect(validateFilters(EMPTY_FILTERS)).toEqual({});
  });

  it('регион не из двух латинских букв', () => {
    expect(validateFilters({ ...EMPTY_FILTERS, region: 'rus' }).region).toBe('catalog.filter.error.region');
    expect(validateFilters({ ...EMPTY_FILTERS, region: '12' }).region).toBe('catalog.filter.error.region');
  });

  it('цена не число', () => {
    const errors = validateFilters({ ...EMPTY_FILTERS, priceFrom: 'много', priceTo: '-1' });
    expect(errors.priceFrom).toBe('catalog.filter.error.price');
    expect(errors.priceTo).toBe('catalog.filter.error.price');
  });

  it('нижняя граница больше верхней (так же отвечает API: 422)', () => {
    expect(validateFilters({ ...EMPTY_FILTERS, priceFrom: '500', priceTo: '100' }).priceFrom).toBe('catalog.filter.error.range');
    expect(validateFilters({ ...EMPTY_FILTERS, priceFrom: '100', priceTo: '100' })).toEqual({});
  });
});
