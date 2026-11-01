/**
 * Окно обращения покупателя: 72 часа с первичной выдачи (FT-7.2, параметр платформы `support.window-seconds`, по умолчанию 259200).
 * Покупателю параметры не отдаются, поэтому экран считает по значению по умолчанию, чтобы заранее не звать на кнопку, которая откажет.
 * Решает всё равно сервер: если параметр изменили, ответ 409 `ticket-window-closed` покажет тот же текст.
 */
export const SUPPORT_WINDOW_HOURS = 72;
export const SUPPORT_WINDOW_MS = SUPPORT_WINDOW_HOURS * 3_600_000;

/** Окно закрыто, если с первичной выдачи (местное время в мс) прошло больше срока. Выдачи нет или время неизвестно: не закрыто, решает сервер. */
export function windowClosed(issuedAtMs: number | undefined, nowMs: number): boolean {
  return issuedAtMs !== undefined && nowMs - issuedAtMs > SUPPORT_WINDOW_MS;
}
