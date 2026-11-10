import type { TextKey } from '../../i18n';
import type { ApiKey } from './types';

/** Предел активных ключей на продавца (US-11.1): шестой выпуск сервер отклонит с 409 `api-key-limit`, экран объясняет это заранее. */
export const API_KEYS_MAX = 5;
export const API_KEY_NAME_MAX = 60;

export function activeKeyCount(keys: ApiKey[]): number {
  return keys.filter((key) => key.status === 'active').length;
}

/** Имя ключа из поля ввода: обязательное, не длиннее 60 знаков. Возвращает ключ текста ошибки или `undefined`. */
export function validateKeyName(name: string): TextKey | undefined {
  const trimmed = name.trim();
  if (trimmed === '') {
    return 'field.required';
  }
  return trimmed.length > API_KEY_NAME_MAX ? 'field.too_long' : undefined;
}

/** Как ключ узнают в списке: префикс и отпечаток (первые восемь знаков). Сам ключ из них не восстановить. */
export function keyMark(fingerprint: string): string {
  return `dgm_${fingerprint}`;
}
