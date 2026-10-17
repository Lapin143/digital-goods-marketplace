/** Предел размера CSV и число ключей в одном запросе: те же, что у шлюза и сервиса остатков (2 МБ, 10 000). */
export const CSV_MAX_BYTES = 2 * 1024 * 1024;
export const KEYS_MAX = 10_000;

/**
 * Строки из поля ввода: по ключу в строке, номера строк совпадают с номерами в отчёте сервера.
 * Пустые строки внутри списка остаются (сервер отклонит их с причиной `empty` и номером), последний перевод строки не считается.
 */
export function splitKeys(text: string): string[] {
  if (text === '') {
    return [];
  }
  const lines = text.split('\n').map((line) => line.replace(/\r$/, ''));
  if (lines.length > 1 && lines[lines.length - 1] === '') {
    lines.pop();
  }
  return lines;
}

export type CsvCheck = { ok: true } | { ok: false; reason: 'keys.error.csvSize' | 'keys.error.csvType' | 'keys.error.csvEmpty' };

/** Проверка файла до отправки: размер (до 2 МБ), тип (CSV или текст), непустой. */
export function checkCsv(file: { name: string; type: string; size: number }): CsvCheck {
  if (file.size === 0) {
    return { ok: false, reason: 'keys.error.csvEmpty' };
  }
  if (file.size > CSV_MAX_BYTES) {
    return { ok: false, reason: 'keys.error.csvSize' };
  }
  const textual = file.type === '' || file.type === 'text/csv' || file.type === 'text/plain' || file.type === 'application/vnd.ms-excel';
  if (!textual || !/\.(csv|txt)$/i.test(file.name)) {
    return { ok: false, reason: 'keys.error.csvType' };
  }
  return { ok: true };
}
