import { claimsFor, fakeManager, fakeUser } from './fakeAuth';

/** Идентификатор оператора в токене тестов: по нему экран отличает «мои» обращения и свои действия. */
export const OPERATOR_ID = 'op-1';
export const MASKED_EMAIL = 'n***@mail.example';

/** Менеджер входа оператора поддержки с одноразовым кодом (второй фактор есть). */
export function operatorManager(roles: string[] = ['support-operator'], amr: string[] = ['pwd', 'otp']) {
  return fakeManager(fakeUser(claimsFor(roles, amr, { sub: OPERATOR_ID })));
}

/** Обращение в очереди оператора так, как его отдаёт `StaffSupportTicket`: ключей и адреса покупателя в нём нет. */
export function staffTicket(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    ticketId: 't-1',
    orderId: 'o-1',
    orderNumber: 1042,
    buyerId: 'buyer-secret-id',
    source: 'buyer',
    reason: 'key_not_received',
    status: 'created',
    operatorId: null,
    orderStatus: 'issued',
    issuedAt: '2026-10-17T10:00:00.000Z',
    deliveryStatus: 'sent',
    createdAt: '2026-10-17T12:00:00.000Z',
    takenAt: null,
    resolvedAt: null,
    emailChange: null,
    ...overrides,
  };
}

/** Обращение, которое ведёт текущий оператор. */
export function ownTicket(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return staffTicket({ status: 'in_progress', operatorId: OPERATOR_ID, takenAt: '2026-10-17T12:05:00.000Z', ...overrides });
}

export function emailChangeFixture(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    status: 'awaiting_phone_code',
    phoneCheck: 'pending',
    newEmailCheck: 'not_started',
    newEmailMasked: MASKED_EMAIL,
    startedAt: '2026-10-17T12:10:00.000Z',
    updatedAt: '2026-10-17T12:10:00.000Z',
    ...overrides,
  };
}
