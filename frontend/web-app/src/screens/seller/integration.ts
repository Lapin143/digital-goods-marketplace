import { hasText, t } from '../../i18n';
import type { TextKey } from '../../i18n';
import { ApiError } from '../../api/problem';
import type { Tone } from './labels';

export const ENDPOINT_MAX = 2048;
export const INTEGRATION_STATUSES = ['active', 'disabled'] as const;
export const DELIVERY_FILTERS = ['failed', 'queued', 'sent', 'skipped'] as const;

/** Адрес системы продавца из поля ввода: только `https` (Р-187). Остальные правила (порт, внутренние адреса) проверяет платформа. */
export function validateEndpoint(text: string): TextKey | undefined {
  const value = text.trim();
  if (value === '') {
    return 'field.required';
  }
  if (value.length > ENDPOINT_MAX) {
    return 'field.too_long';
  }
  return /^https:\/\/\S+$/i.test(value) ? undefined : 'integration.error.https';
}

/** Платформа отвергла адрес по правилам защиты от SSRF: объяснение общее, без подробностей проверки. */
export function isEndpointRejected(error: unknown): boolean {
  return error instanceof ApiError && error.status === 422 && error.code === 'endpoint-not-allowed';
}

export function integrationStatusLabel(status: string): string {
  const key = `integration.status.${status}`;
  return hasText(key) ? t(key) : status;
}

export function integrationStatusTone(status: string): Tone {
  return status === 'active' ? 'good' : 'neutral';
}

export function deliveryStatusLabel(status: string): string {
  const key = `integration.delivery.status.${status}`;
  return hasText(key) ? t(key) : status;
}

export function deliveryStatusTone(status: string): Tone {
  switch (status) {
    case 'sent':
      return 'good';
    case 'queued':
      return 'warn';
    case 'failed':
      return 'bad';
    default:
      return 'neutral';
  }
}

/** Тип события уведомления; незнакомый тип (перечисление открытое) показывается как есть. */
export function deliveryTypeLabel(type: string): string {
  const key = `integration.delivery.type.${type}`;
  return hasText(key) ? t(key) : type;
}
