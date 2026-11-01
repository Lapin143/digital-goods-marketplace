/** Адреса обращений покупателя (docs/06-api/openapi/platform-service.yaml, раздел «Обращения покупателя»). Все методы доступны и сессии по SMS. */
export const SUPPORT_TICKETS = '/api/v1/support-tickets';

export function ticketPath(ticketId: string): string {
  return `${SUPPORT_TICKETS}/${encodeURIComponent(ticketId)}`;
}

/** Повторная отправка кода смены e-mail (`requestEmailChangeCode`). */
export function emailChangeCodesPath(ticketId: string): string {
  return `${ticketPath(ticketId)}/email-change/codes`;
}

/** Ввод кода смены e-mail (`verifyEmailChangeCode`). */
export function emailChangeVerificationsPath(ticketId: string): string {
  return `${ticketPath(ticketId)}/email-change/verifications`;
}
