import { hasText, t } from '../../../i18n';
import type { EmailChangeState } from './types';

type Tone = 'neutral' | 'good' | 'warn' | 'bad';

/** Источник обращения словами оператора (покупателю экран говорит «Вы», оператору нужно назвать, кто создал). */
export function sourceLabel(source: string): string {
  const key = `supportCard.source.${source}`;
  return hasText(key) ? t(key) : t('supportCard.source.unknown');
}

/** Статус обращения по SM-07 словами оператора: «в очереди», «в работе», «решено». */
export function ticketStatusLabel(status: string): string {
  const key = `supportCard.status.${status}`;
  return hasText(key) ? t(key) : t('supportCard.status.unknown');
}

export function ticketStatusTone(status: string): Tone {
  return status === 'resolved' ? 'good' : status === 'created' || status === 'in_progress' ? 'warn' : 'neutral';
}

/** Статус выдачи по SM-06 словами оператора; выдачи ещё нет (`null`) или статус незнаком: нейтральный текст. */
export function deliveryStatusLabel(status: string | null | undefined): string {
  if (!status) {
    return t('supportCard.delivery.none');
  }
  const key = `supportCard.delivery.${status}`;
  return hasText(key) ? t(key) : t('supportCard.delivery.unknown');
}

export function deliveryStatusTone(status: string | null | undefined): Tone {
  switch (status) {
    case 'delivered':
      return 'good';
    case 'failed':
      return 'bad';
    case 'queued':
    case 'sent':
      return 'warn';
    default:
      return 'neutral';
  }
}

/** Результат проверки кода: оператору виден только исход, самих кодов нет (FT-7.5). */
export function checkLabel(check: string): string {
  const key = `supportCard.check.${check}`;
  return hasText(key) ? t(key) : t('supportCard.check.unknown');
}

export function checkTone(check: string): Tone {
  return check === 'passed' ? 'good' : check === 'failed' ? 'bad' : check === 'pending' ? 'warn' : 'neutral';
}

export function emailChangeStatusLabel(status: EmailChangeState['status'] | string): string {
  const key = `supportCard.change.${status}`;
  return hasText(key) ? t(key) : t('supportCard.change.unknown');
}

/** Адрес из поля ввода: проверка формы до отправки (полную проверку делает сервер). */
export function isPlausibleEmail(text: string): boolean {
  const value = text.trim();
  return value.length <= 254 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value);
}

/** Короткий номер заказа из поля поиска: целое положительное число, без знаков и пробелов внутри. */
export function parseOrderNumber(text: string): number | undefined {
  const value = text.trim();
  if (!/^\d{1,15}$/.test(value)) {
    return undefined;
  }
  const number = Number(value);
  return number >= 1 ? number : undefined;
}
