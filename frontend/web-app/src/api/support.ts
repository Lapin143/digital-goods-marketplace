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

/** Очередь и карточка обращения для оператора (роль `support-operator`, второй фактор обязателен). */
export const STAFF_SUPPORT_TICKETS = '/api/v1/staff/support-tickets';

export type StaffTicketAction = 'take' | 'release' | 'resend' | 'email-change';

export function staffTicketPath(ticketId: string): string {
  return `${STAFF_SUPPORT_TICKETS}/${encodeURIComponent(ticketId)}`;
}

export function staffTicketActionPath(ticketId: string, action: StaffTicketAction): string {
  return `${staffTicketPath(ticketId)}/${action}`;
}
