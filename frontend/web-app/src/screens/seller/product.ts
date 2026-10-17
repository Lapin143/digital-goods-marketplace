import type { TextKey } from '../../i18n';
import { parseRubles } from '../storefront/money';
import type { ProductInput, SellerProduct } from './types';

export const PRODUCT_TYPE_VALUES = ['game_key', 'software_license', 'gift_card', 'subscription'] as const;

/** Товар в полях ввода: всё текстом, цена в рублях, регионы через запятую. */
export interface ProductValues {
  title: string;
  description: string;
  productType: string;
  platform: string;
  regions: string;
  price: string;
}

export const EMPTY_PRODUCT: ProductValues = { title: '', description: '', productType: '', platform: '', regions: '', price: '' };

export type ProductErrors = Partial<Record<keyof ProductValues, TextKey>>;

/** Статусы, в которых поля правятся: в проверке и в архиве правка запрещена (409). */
const LOCKED = new Set(['on_moderation', 'archived']);

export function isLocked(status: string | undefined): boolean {
  return status !== undefined && LOCKED.has(status);
}

export function rublesText(kopecks: number): string {
  const rubles = Math.floor(kopecks / 100);
  const rest = kopecks % 100;
  return rest === 0 ? String(rubles) : `${rubles},${String(rest).padStart(2, '0')}`;
}

export function valuesFromProduct(product: SellerProduct | undefined): ProductValues {
  if (!product) {
    return EMPTY_PRODUCT;
  }
  return {
    title: product.title,
    description: product.description,
    productType: product.productType,
    platform: product.platform,
    regions: product.activationRegions.join(', '),
    price: rublesText(product.price.amount),
  };
}

/** Коды стран из строки «RU, kz BY»: разделители запятая, точка с запятой и пробел, повторы убираются. Неверный код даёт `undefined`. */
export function parseRegions(text: string): string[] | undefined {
  const parts = text
    .split(/[\s,;]+/)
    .map((part) => part.trim().toUpperCase())
    .filter((part) => part !== '');
  if (parts.some((part) => !/^[A-Z]{2}$/.test(part))) {
    return undefined;
  }
  return parts.filter((part, index) => parts.indexOf(part) === index);
}

export function validateProduct(values: ProductValues): ProductErrors {
  const errors: ProductErrors = {};
  for (const field of ['title', 'description', 'productType', 'platform'] as const) {
    if (values[field].trim() === '') {
      errors[field] = 'field.required';
    }
  }
  if (parseRegions(values.regions) === undefined) {
    errors.regions = 'product.error.regions';
  }
  const price = parseRubles(values.price);
  if (price.kind === 'empty') {
    errors.price = 'field.required';
  } else if (price.kind === 'invalid' || (price.kind === 'value' && price.kopecks <= 0)) {
    errors.price = 'product.error.price';
  }
  return errors;
}

/** Тело запроса. Вызывать после `validateProduct` без ошибок. В R1 способ выдачи только пул ключей. */
export function toProductInput(values: ProductValues): ProductInput {
  const price = parseRubles(values.price);
  return {
    title: values.title.trim(),
    description: values.description.trim(),
    productType: values.productType as ProductInput['productType'],
    platform: values.platform.trim(),
    activationRegions: parseRegions(values.regions) ?? [],
    price: { amount: price.kind === 'value' ? price.kopecks : 0, currency: 'RUB' },
    issuanceMethod: 'key_pool',
  };
}

/** Что меняется для опубликованного товара: название, описание и тип возвращают его на модерацию (T6), остальное нет (T7). */
export function needsRemoderation(before: SellerProduct, values: ProductValues): boolean {
  return before.title !== values.title.trim() || before.description !== values.description.trim() || before.productType !== values.productType;
}
