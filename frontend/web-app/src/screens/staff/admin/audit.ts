import { t } from '../../../i18n';
import type { AuditFilters } from './useAdmin';
import { NO_FILTERS } from './useAdmin';
import type { AuditRecord } from './types';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Значения полей формы фильтра, как их ввёл администратор (время в формате поля `datetime-local`). */
export interface AuditForm {
  actorId: string;
  objectType: string;
  objectId: string;
  action: string;
  from: string;
  to: string;
}

export const EMPTY_FORM: AuditForm = { actorId: '', objectType: '', objectId: '', action: '', from: '', to: '' };

export type FormErrors = Partial<Record<keyof AuditForm, string>>;

/** Местное время поля `datetime-local` в RFC 3339 (UTC) для запроса; нечитаемое значение даёт `undefined`. */
export function toRfc3339(local: string): string | undefined {
  if (local.trim() === '') {
    return undefined;
  }
  const date = new Date(local);
  return Number.isNaN(date.getTime()) ? undefined : date.toISOString();
}

/** Проверка формы до запроса. Ошибок нет: условия для запроса; есть ошибки: текст у каждого поля. */
export function readForm(form: AuditForm): { filters: AuditFilters } | { errors: FormErrors } {
  const errors: FormErrors = {};
  const actorId = form.actorId.trim();
  if (actorId !== '' && !UUID.test(actorId)) {
    errors.actorId = t('audit.filter.actorId.error');
  }
  const from = toRfc3339(form.from);
  const to = toRfc3339(form.to);
  if (form.from.trim() !== '' && from === undefined) {
    errors.from = t('audit.filter.time.error');
  }
  if (form.to.trim() !== '' && to === undefined) {
    errors.to = t('audit.filter.time.error');
  }
  if (from !== undefined && to !== undefined && from >= to) {
    errors.to = t('audit.filter.period.error');
  }
  if (Object.keys(errors).length > 0) {
    return { errors };
  }
  return {
    filters: {
      ...NO_FILTERS,
      actorId: actorId.toLowerCase(),
      objectType: form.objectType.trim(),
      objectId: form.objectId.trim(),
      action: form.action.trim(),
      occurredFrom: from ?? '',
      occurredTo: to ?? '',
    },
  };
}

/** Начало идентификатора для строки таблицы; полное значение остаётся в подсказке и в тексте для чтения с экрана. */
export function shortId(id: string): string {
  return id.length > 8 ? id.slice(0, 8) : id;
}

/** Хеш персонального поля сокращённо: восемь знаков и многоточие (полное значение администратору не нужно, сравнивать хеши можно по началу). */
export function shortHash(hash: string): string {
  return hash.length > 8 ? `${hash.slice(0, 8)}…` : hash;
}

type Change = AuditRecord['changes'][number];

/** Значение изменения для показа: пустое словами, персональное хешем сокращённо, остальное как есть (значения параметров и статусы). */
export function changeValue(change: Change, side: 'before' | 'after'): string {
  const value = change[side];
  if (value === null || value === undefined) {
    return t('audit.change.empty');
  }
  return change.personal ? shortHash(value) : value;
}

/** Время записи с секундами: в журнале важен порядок действий внутри минуты. */
export function formatPrecise(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  return new Intl.DateTimeFormat('ru-RU', { dateStyle: 'short', timeStyle: 'medium' }).format(date);
}
