import { claimsFor, fakeManager, fakeUser } from './fakeAuth';

/** Идентификатор администратора в токене тестов: по нему экран отличает «вы» в столбце «Изменён». */
export const ADMIN_ID = '0199e0a0-0000-7000-8000-0000000000a1';
export const OTHER_ADMIN_ID = 'c3d4e5f6-0718-4293-a4b5-c6d7e8f90a1b';

/** Менеджер входа администратора с одноразовым кодом (второй фактор есть). */
export function adminManager(roles: string[] = ['admin'], amr: string[] = ['pwd', 'otp']) {
  return fakeManager(fakeUser(claimsFor(roles, amr, { sub: ADMIN_ID })));
}

/** Параметр платформы так, как его отдаёт `PlatformParameter`. */
export function parameter(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    key: 'reservation.ttl-seconds',
    description: 'Время резерва ключей',
    unit: 'seconds',
    value: 900,
    defaultValue: 900,
    minValue: 300,
    maxValue: 3600,
    version: 3,
    updatedAt: '2026-10-17T09:00:00.000Z',
    updatedBy: null,
    ...overrides,
  };
}

export const COMMISSION = parameter({
  key: 'commission.default-rate-bp',
  description: 'Комиссия по умолчанию, базисные пункты',
  unit: 'basis_points',
  value: 200,
  defaultValue: 200,
  minValue: 1,
  maxValue: 3000,
});
export const RESERVE = parameter();
export const SESSION = parameter({
  key: 'payment-session.ttl-seconds',
  description: 'Срок платёжной сессии, строго короче резерва',
  value: 720,
  defaultValue: 720,
  minValue: 60,
  maxValue: 3599,
});
export const MAX_UNPAID = parameter({
  key: 'orders.max-unpaid',
  description: 'Число одновременно неоплаченных заказов',
  unit: 'count',
  value: 3,
  defaultValue: 3,
  minValue: 1,
  maxValue: 20,
  version: 4,
  updatedBy: OTHER_ADMIN_ID,
});
export const SUPPORT_WINDOW = parameter({
  key: 'support.window-seconds',
  description: 'Окно обращения «Не получил ключ»',
  value: 172800,
  defaultValue: 259200,
  minValue: 3600,
  maxValue: 2592000,
  version: 5,
  updatedBy: ADMIN_ID,
});

/** Запись журнала аудита так, как её отдаёт `AuditRecord`. */
export function auditRecord(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    recordId: '0199e0a0-0000-7000-8000-000000000b01',
    occurredAt: '2026-10-17T12:00:00.123Z',
    actorId: OTHER_ADMIN_ID,
    actorRole: 'admin',
    action: 'parameter.changed',
    objectType: 'parameter',
    objectId: 'reservation.ttl-seconds',
    changes: [{ field: 'value', personal: false, before: '900', after: '1200' }],
    actorIp: '203.0.113.7',
    actorIpHashed: false,
    correlationId: '6f1c2b3a-4d5e-4f60-8a7b-9c0d1e2f3a4b',
    ...overrides,
  };
}
