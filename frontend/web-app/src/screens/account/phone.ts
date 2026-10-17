/**
 * Номер из поля ввода в формат API (`+7` и десять цифр). Принимает пробелы, скобки, дефисы и «8» вместо «+7».
 * Другой номер даёт `undefined`: в R1 подтверждаются только российские номера.
 */
export function normalizePhone(input: string): string | undefined {
  const compact = input.replace(/[\s\-()]/g, '');
  let value = compact;
  if (/^8\d{10}$/.test(value)) {
    value = `+7${value.slice(1)}`;
  } else if (/^7\d{10}$/.test(value)) {
    value = `+${value}`;
  }
  return /^\+7\d{10}$/.test(value) ? value : undefined;
}

export function isCode(input: string): boolean {
  return /^\d{6}$/.test(input);
}
