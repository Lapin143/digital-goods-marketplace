import { describe, expect, it } from 'vitest';
import { EMPTY_PRODUCT, isLocked, needsRemoderation, parseRegions, rublesText, toProductInput, validateProduct, valuesFromProduct } from './product';
import type { ProductValues } from './product';
import type { SellerProduct } from './types';

const VALUES: ProductValues = {
  title: ' Игра ',
  description: 'Ключ активации',
  productType: 'game_key',
  platform: 'Steam',
  regions: 'ru, kz',
  price: '1499,50',
};

function product(overrides: Partial<SellerProduct> = {}): SellerProduct {
  return {
    productId: 'p-1',
    sellerId: 's-1',
    title: 'Игра',
    description: 'Ключ активации',
    productType: 'game_key',
    platform: 'Steam',
    activationRegions: ['RU', 'KZ'],
    price: { amount: 149950, currency: 'RUB' },
    issuanceMethod: 'key_pool',
    status: 'published',
    rejectionReason: null,
    blockReason: null,
    submittedAt: null,
    reviewDeadlineAt: null,
    version: 3,
    createdAt: '2026-10-17T09:00:00Z',
    updatedAt: '2026-10-17T09:00:00Z',
    ...overrides,
  } as SellerProduct;
}

describe('регионы активации', () => {
  it('разделители запятая, точка с запятой и пробел, регистр и повторы нормализуются', () => {
    expect(parseRegions('ru, kz;by  RU')).toEqual(['RU', 'KZ', 'BY']);
  });

  it('пустая строка значит «без ограничений»', () => {
    expect(parseRegions('')).toEqual([]);
    expect(parseRegions('  ,  ')).toEqual([]);
  });

  it('неверный код даёт undefined', () => {
    expect(parseRegions('RUS')).toBeUndefined();
    expect(parseRegions('R1')).toBeUndefined();
    expect(parseRegions('ru, k')).toBeUndefined();
  });
});

describe('цена', () => {
  it('рубли показываются без копеек, если копеек нет', () => {
    expect(rublesText(149900)).toBe('1499');
    expect(rublesText(149950)).toBe('1499,50');
    expect(rublesText(149905)).toBe('1499,05');
    expect(rublesText(1)).toBe('0,01');
  });

  it('в тело цена уходит в копейках целым числом', () => {
    expect(toProductInput(VALUES).price).toEqual({ amount: 149950, currency: 'RUB' });
    expect(toProductInput({ ...VALUES, price: '1 499' }).price.amount).toBe(149900);
    expect(toProductInput({ ...VALUES, price: '0.07' }).price.amount).toBe(7);
  });
});

describe('проверка товара до запроса', () => {
  it('полная форма проходит', () => {
    expect(validateProduct(VALUES)).toEqual({});
  });

  it('пустая форма: обязательные поля, цена обязательна', () => {
    const errors = validateProduct(EMPTY_PRODUCT);
    expect(Object.keys(errors).sort()).toEqual(['description', 'platform', 'price', 'productType', 'title']);
    expect(errors.price).toBe('field.required');
  });

  it('цена ноль, отрицательная и с мусором отвергаются', () => {
    expect(validateProduct({ ...VALUES, price: '0' }).price).toBe('product.error.price');
    expect(validateProduct({ ...VALUES, price: '-5' }).price).toBe('product.error.price');
    expect(validateProduct({ ...VALUES, price: '12abc' }).price).toBe('product.error.price');
    expect(validateProduct({ ...VALUES, price: '1.234' }).price).toBe('product.error.price');
  });

  it('неверный регион отвергается', () => {
    expect(validateProduct({ ...VALUES, regions: 'RUS' }).regions).toBe('product.error.regions');
  });
});

describe('тело запроса товара', () => {
  it('поля обрезаются, регионы в верхнем регистре, способ выдачи пул ключей', () => {
    expect(toProductInput(VALUES)).toEqual({
      title: 'Игра',
      description: 'Ключ активации',
      productType: 'game_key',
      platform: 'Steam',
      activationRegions: ['RU', 'KZ'],
      price: { amount: 149950, currency: 'RUB' },
      issuanceMethod: 'key_pool',
    });
  });

  it('значения формы из товара возвращаются без потерь', () => {
    const back = valuesFromProduct(product());
    expect(back).toEqual({ title: 'Игра', description: 'Ключ активации', productType: 'game_key', platform: 'Steam', regions: 'RU, KZ', price: '1499,50' });
    expect(toProductInput(back).price.amount).toBe(149950);
  });
});

describe('повторная модерация и блокировка полей', () => {
  it('название, описание и тип возвращают опубликованный товар на модерацию, цена и платформа нет', () => {
    const before = product();
    const same = valuesFromProduct(before);
    expect(needsRemoderation(before, same)).toBe(false);
    expect(needsRemoderation(before, { ...same, price: '1' })).toBe(false);
    expect(needsRemoderation(before, { ...same, platform: 'Epic' })).toBe(false);
    expect(needsRemoderation(before, { ...same, title: 'Другое' })).toBe(true);
    expect(needsRemoderation(before, { ...same, description: 'Другое' })).toBe(true);
    expect(needsRemoderation(before, { ...same, productType: 'gift_card' })).toBe(true);
  });

  it('пробелы по краям не считаются изменением', () => {
    const before = product();
    expect(needsRemoderation(before, { ...valuesFromProduct(before), title: ' Игра ' })).toBe(false);
  });

  it('в проверке и в архиве поля закрыты', () => {
    expect(isLocked('on_moderation')).toBe(true);
    expect(isLocked('archived')).toBe(true);
    expect(isLocked('draft')).toBe(false);
    expect(isLocked('published')).toBe(false);
    expect(isLocked(undefined)).toBe(false);
  });
});
