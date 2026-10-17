/** Дата и время для показа, в часовом поясе пользователя: «17.10.2026, 12:00». Нечитаемое значение возвращается как есть. */
export function formatMoment(iso: string | null | undefined): string {
  if (!iso) {
    return '';
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  return new Intl.DateTimeFormat('ru-RU', { dateStyle: 'short', timeStyle: 'short' }).format(date);
}

/** Размер файла: «1,5 МБ», «340 КБ». Единицы берутся из словаря, поэтому функция принимает подписи. */
export function formatSize(bytes: number, units: { kb: string; mb: string }): string {
  if (bytes >= 1024 * 1024) {
    return `${(bytes / (1024 * 1024)).toFixed(1).replace('.', ',')} ${units.mb}`;
  }
  return `${Math.max(1, Math.round(bytes / 1024))} ${units.kb}`;
}
