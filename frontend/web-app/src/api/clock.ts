/**
 * Расхождение часов сервера и браузера, мс (серверное время минус местное) по заголовку `Date` ответа.
 * Нужно, чтобы момент из ответа («повторить после 12:00:30») не зависел от неверных часов на устройстве.
 * Точность до секунды, как у самого заголовка. Нет заголовка или он нечитаем: расхождения нет.
 */
export function serverClockOffset(headers: Headers, localNow: number): number {
  const header = headers.get('Date');
  if (!header) {
    return 0;
  }
  const server = Date.parse(header);
  return Number.isNaN(server) ? 0 : server - localNow;
}

/** Момент из ответа сервера (RFC 3339) в местном времени; мусор даёт `undefined`. */
export function toLocalMoment(serverMoment: string | null | undefined, offset: number): number | undefined {
  if (!serverMoment) {
    return undefined;
  }
  const parsed = Date.parse(serverMoment);
  return Number.isNaN(parsed) ? undefined : parsed - offset;
}
