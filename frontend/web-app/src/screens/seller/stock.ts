export const STOCK_MAX = 1_000_000;

export type QuantityParse = { kind: 'empty' } | { kind: 'invalid' } | { kind: 'value'; value: number };

/** Остаток из поля ввода: целое от 0 до 1 000 000 (контракт `ApiStockRequest`). Пробелы внутри числа допустимы («1 000»). */
export function parseQuantity(text: string): QuantityParse {
  const clean = text.replace(/[\s ]/g, '');
  if (clean === '') {
    return { kind: 'empty' };
  }
  if (!/^\d{1,7}$/.test(clean)) {
    return { kind: 'invalid' };
  }
  const value = Number(clean);
  return value > STOCK_MAX ? { kind: 'invalid' } : { kind: 'value', value };
}

/** Значение для `If-Match`: версия записи остатка в кавычках, как отдаёт `ETag`. */
export function stockEtag(version: number): string {
  return `"${version}"`;
}
