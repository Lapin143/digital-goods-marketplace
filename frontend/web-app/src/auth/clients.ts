/**
 * Три клиента входа в Keycloak (ADR-026, решение Р-24 руководства Ф4):
 * `web` покупатель, `staff` продавец и сотрудники (второй фактор обязателен), `sms` вход по коду из SMS (15 минут, без refresh-токена).
 * Клиент выбирается по префиксу маршрута, а не по роли: роль известна только после входа.
 */
export type ClientKind = 'web' | 'staff' | 'sms';

export const CLIENT_IDS: Record<ClientKind, string> = {
  web: 'dgm-web',
  staff: 'dgm-staff',
  sms: 'dgm-sms',
};

export const REALM = 'dgm';

export function clientKindForPath(pathname: string): ClientKind {
  if (/^\/(seller|staff|work)(\/|$)/.test(pathname)) {
    return 'staff';
  }
  if (/^\/sms(\/|$)/.test(pathname)) {
    return 'sms';
  }
  return 'web';
}

export function callbackPath(kind: ClientKind): string {
  return `/oidc/callback/${kind}`;
}

export function isClientKind(value: string | undefined): value is ClientKind {
  return value === 'web' || value === 'staff' || value === 'sms';
}

/** Возврат после входа только на адрес этого же сайта: `//чужой.адрес` и полные адреса отбрасываются (защита от открытого перенаправления). */
export function safeReturnTo(value: unknown): string {
  if (typeof value === 'string' && value.startsWith('/') && !value.startsWith('//') && !value.startsWith('/\\')) {
    return value;
  }
  return '/';
}
