import type { Query } from '../../api/client';
import type { TextKey } from '../../i18n';
import { parseRubles } from './money';

/** Типы товара, которые умеет выбирать фильтр. Перечисление в API открытое, поэтому чужие значения из адреса отбрасываются. */
export const PRODUCT_TYPES = ['game_key', 'software_license', 'gift_card', 'subscription'] as const;

export const PAGE_SIZE = 20;
export const MAX_QUERY_LENGTH = 100;
export const MAX_PLATFORM_LENGTH = 64;

/** Фильтры каталога в том виде, в каком они лежат в полях и в адресе страницы (цены в рублях, текстом). */
export interface Filters {
  q: string;
  types: string[];
  platform: string;
  region: string;
  priceFrom: string;
  priceTo: string;
}

export const EMPTY_FILTERS: Filters = { q: '', types: [], platform: '', region: '', priceFrom: '', priceTo: '' };

export function filtersFromParams(params: URLSearchParams): Filters {
  const known: readonly string[] = PRODUCT_TYPES;
  const types = (params.get('type') ?? '')
    .split(',')
    .map((item) => item.trim())
    .filter((item, index, all) => known.includes(item) && all.indexOf(item) === index);
  return {
    q: (params.get('q') ?? '').slice(0, MAX_QUERY_LENGTH),
    types,
    platform: (params.get('platform') ?? '').slice(0, MAX_PLATFORM_LENGTH),
    region: params.get('region') ?? '',
    priceFrom: params.get('priceFrom') ?? '',
    priceTo: params.get('priceTo') ?? '',
  };
}

/** Адрес страницы хранит фильтры, поэтому выборкой можно поделиться, а кнопка «назад» возвращает прежний поиск. Пустое не пишется. */
export function filtersToParams(filters: Filters): URLSearchParams {
  const params = new URLSearchParams();
  const entries: Array<[string, string]> = [
    ['q', filters.q.trim()],
    ['type', filters.types.join(',')],
    ['platform', filters.platform.trim()],
    ['region', filters.region.trim().toUpperCase()],
    ['priceFrom', filters.priceFrom.trim()],
    ['priceTo', filters.priceTo.trim()],
  ];
  for (const [name, value] of entries) {
    if (value !== '') {
      params.set(name, value);
    }
  }
  return params;
}

export type FilterErrors = Partial<Record<'region' | 'priceFrom' | 'priceTo', TextKey>>;

/** Проверка до отправки: те же правила, что у API (регион из двух букв, цена в копейках, нижняя граница не больше верхней). */
export function validateFilters(filters: Filters): FilterErrors {
  const errors: FilterErrors = {};
  if (filters.region.trim() !== '' && !/^[A-Za-z]{2}$/.test(filters.region.trim())) {
    errors.region = 'catalog.filter.error.region';
  }
  const from = parseRubles(filters.priceFrom);
  const to = parseRubles(filters.priceTo);
  if (from.kind === 'invalid') {
    errors.priceFrom = 'catalog.filter.error.price';
  }
  if (to.kind === 'invalid') {
    errors.priceTo = 'catalog.filter.error.price';
  }
  if (from.kind === 'value' && to.kind === 'value' && from.kopecks > to.kopecks) {
    errors.priceFrom = 'catalog.filter.error.range';
  }
  return errors;
}

/** Параметры запроса `GET /api/v1/products` (без курсора и размера страницы): пустые не отправляются. Цены уходят в копейках. */
export function toApiQuery(filters: Filters): Query {
  const from = parseRubles(filters.priceFrom);
  const to = parseRubles(filters.priceTo);
  const q = filters.q.trim();
  const platform = filters.platform.trim();
  const region = filters.region.trim().toUpperCase();
  return {
    q: q === '' ? undefined : q,
    productType: filters.types.length === 0 ? undefined : filters.types.join(','),
    platform: platform === '' ? undefined : platform,
    region: region === '' ? undefined : region,
    priceFrom: from.kind === 'value' ? from.kopecks : undefined,
    priceTo: to.kind === 'value' ? to.kopecks : undefined,
  };
}

export function hasFilters(filters: Filters): boolean {
  return (
    filters.q.trim() !== '' ||
    filters.types.length > 0 ||
    filters.platform.trim() !== '' ||
    filters.region.trim() !== '' ||
    filters.priceFrom.trim() !== '' ||
    filters.priceTo.trim() !== ''
  );
}
