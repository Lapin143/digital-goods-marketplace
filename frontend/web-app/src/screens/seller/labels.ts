import { hasText, t } from '../../i18n';

/** Статус профиля продавца (SM-05). Незнакомое значение (перечисление открытое) показывается без перевода. */
export function sellerStatusLabel(status: string): string {
  const key = `sellerStatus.${status}`;
  return hasText(key) ? t(key) : status;
}

export type Tone = 'neutral' | 'good' | 'warn' | 'bad';

export function sellerStatusTone(status: string): Tone {
  switch (status) {
    case 'approved':
      return 'good';
    case 'on_review':
      return 'warn';
    case 'rejected':
    case 'blocked':
      return 'bad';
    default:
      return 'neutral';
  }
}

/** Статус товара (SM-04). */
export function productStatusLabel(status: string): string {
  const key = `productStatus.${status}`;
  return hasText(key) ? t(key) : status;
}

export function productStatusTone(status: string): Tone {
  switch (status) {
    case 'published':
      return 'good';
    case 'on_moderation':
      return 'warn';
    case 'rejected':
    case 'blocked':
      return 'bad';
    default:
      return 'neutral';
  }
}

export function sellerTypeLabel(type: string): string {
  const key = `sellerType.${type}`;
  return hasText(key) ? t(key) : type;
}

/** Почему строка списка ключей отклонена (значения ключей в ответ не попадают). */
export function rejectReasonLabel(reason: string): string {
  const key = `keys.reason.${reason}`;
  return hasText(key) ? t(key) : reason;
}
