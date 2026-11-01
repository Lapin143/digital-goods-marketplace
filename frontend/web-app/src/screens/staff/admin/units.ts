import { hasText, t } from '../../../i18n';
import type { PlatformParameter } from './types';

const DAY = 86_400;
const HOUR = 3_600;
const MINUTE = 60;

/** Срок словами для чтения: «3 сут», «15 мин», «1 ч 30 мин», «45 с». Сохраняется всегда значение в единицах параметра, а не это. */
export function formatSeconds(total: number): string {
  let rest = Math.max(0, Math.floor(total));
  const parts: string[] = [];
  const days = Math.floor(rest / DAY);
  rest -= days * DAY;
  const hours = Math.floor(rest / HOUR);
  rest -= hours * HOUR;
  const minutes = Math.floor(rest / MINUTE);
  rest -= minutes * MINUTE;
  if (days > 0) {
    parts.push(t('admin.unit.day', { count: days }));
  }
  if (hours > 0) {
    parts.push(t('admin.unit.hour', { count: hours }));
  }
  if (minutes > 0) {
    parts.push(t('admin.unit.minute', { count: minutes }));
  }
  if (rest > 0 || parts.length === 0) {
    parts.push(t('admin.unit.second', { count: rest }));
  }
  return parts.join(' ');
}

/** Базисные пункты в процентах: 200 это «2 %», 250 это «2,5 %». */
export function formatPercent(basisPoints: number): string {
  const percent = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 2 }).format(basisPoints / 100);
  return t('admin.unit.percent', { value: percent });
}

function formatCount(value: number): string {
  return new Intl.NumberFormat('ru-RU').format(value);
}

/** Значение параметра так, как его читает администратор (секунды в минутах и часах, базисные пункты в процентах). */
export function formatParameterValue(unit: string, value: number): string {
  switch (unit) {
    case 'seconds':
      return formatSeconds(value);
    case 'basis_points':
      return formatPercent(value);
    case 'kopecks':
      return new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB' }).format(value / 100);
    default:
      return formatCount(value);
  }
}

/** Значение в единицах параметра, как оно хранится и уходит на сервер: «900 с», «200 б. п.». Для счётчиков пусто, они и так без пересчёта. */
export function formatRawValue(unit: string, value: number): string {
  switch (unit) {
    case 'seconds':
      return t('admin.unit.rawSeconds', { value });
    case 'basis_points':
      return t('admin.unit.rawBasisPoints', { value });
    case 'kopecks':
      return t('admin.unit.rawKopecks', { value });
    default:
      return '';
  }
}

/** «15 мин (900 с)»: читаемая форма и значение в единицах параметра; если они совпадают, показывается одно. */
export function describeValue(unit: string, value: number): string {
  const readable = formatParameterValue(unit, value);
  const raw = formatRawValue(unit, value);
  return raw === '' || raw === readable ? readable : `${readable} (${raw})`;
}

/** Название единицы для подписи поля ввода: значение вводится в единицах параметра, а не в минутах. */
export function unitName(unit: string): string {
  const key = `admin.unitName.${unit}`;
  return hasText(key) ? t(key) : t('admin.unitName.unknown');
}

/** Границы для подсказки у поля: числа в единицах параметра и, если нужно, читаемая форма. */
export function boundsText(parameter: Pick<PlatformParameter, 'unit' | 'minValue' | 'maxValue'>): string {
  const params = { min: parameter.minValue, max: parameter.maxValue };
  if (parameter.unit === 'count') {
    return t('admin.params.bounds', params);
  }
  return t('admin.params.bounds.readable', {
    ...params,
    minText: formatParameterValue(parameter.unit, parameter.minValue),
    maxText: formatParameterValue(parameter.unit, parameter.maxValue),
  });
}

/** Целое число от 1 до 15 цифр из поля ввода; всё остальное (знак, дробь, пробел внутри, буквы) не число. */
export function parseWholeNumber(text: string): number | undefined {
  const value = text.trim();
  if (!/^\d{1,15}$/.test(value)) {
    return undefined;
  }
  const number = Number(value);
  return number >= 1 ? number : undefined;
}

export type ValueCheck = { ok: true; value: number } | { ok: false; message: string };

/** Проверка введённого значения до отправки: положительное целое в границах параметра. Окончательно решает сервер. */
export function checkValue(parameter: Pick<PlatformParameter, 'unit' | 'minValue' | 'maxValue'>, text: string): ValueCheck {
  const value = parseWholeNumber(text);
  if (value === undefined) {
    return { ok: false, message: t('admin.params.error.notNumber') };
  }
  if (value < parameter.minValue || value > parameter.maxValue) {
    return { ok: false, message: `${t('admin.params.error.range')} ${boundsText(parameter)}` };
  }
  return { ok: true, value };
}
