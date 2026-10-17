import type { TextKey } from '../../../i18n';

/** Предел причины и комментария: те же 1000 символов, что в контракте (`ReasonRequest`, `CommentRequest`). */
export const REASON_MAX = 1000;

/** Проверка причины до запроса: не пустая после обрезки пробелов и не длиннее предела (считаются символы, а не единицы UTF-16). */
export function checkReason(text: string): TextKey | undefined {
  const trimmed = text.trim();
  if (trimmed === '') {
    return 'moderation.error.reasonRequired';
  }
  if ([...trimmed].length > REASON_MAX) {
    return 'moderation.error.reasonTooLong';
  }
  return undefined;
}

/** Время до конца действия ссылок на документы в виде «4:05». */
export function formatCountdown(seconds: number): string {
  const whole = Math.max(0, Math.floor(seconds));
  const minutes = Math.floor(whole / 60);
  const rest = whole % 60;
  return `${minutes}:${String(rest).padStart(2, '0')}`;
}
