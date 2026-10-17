import { hasText, t } from '../../i18n';

/** Название типа товара. Перечисление в API открытое: незнакомый тип получает общую подпись, а не код. */
export function productTypeLabel(type: string): string {
  const key = `productType.${type}`;
  return hasText(key) ? t(key) : t('productType.other');
}
