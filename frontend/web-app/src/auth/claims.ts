/** Что интерфейс знает о пользователе из токена доступа. Только для показа: права проверяют шлюз и сервисы. */
export interface Claims {
  subject: string;
  roles: string[];
  /** Способы входа (amr): `pwd`, `otp`, `sms`. */
  amr: string[];
  sellerId: string | undefined;
  phoneVerified: boolean;
  email: string | undefined;
  /** Момент окончания токена, мс с начала эпохи. */
  expiresAt: number;
}

function decodeBase64Url(part: string): string {
  const base64 = part.replace(/-/g, '+').replace(/_/g, '/');
  const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4);
  const binary = atob(padded);
  const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

function strings(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : [];
}

/** Разбирает полезную нагрузку JWT без проверки подписи (подпись проверяет шлюз). Неразборчивый токен даёт `undefined`. */
export function decodeClaims(accessToken: string): Claims | undefined {
  const parts = accessToken.split('.');
  if (parts.length !== 3 || parts[1] === undefined) {
    return undefined;
  }
  let payload: Record<string, unknown>;
  try {
    const parsed: unknown = JSON.parse(decodeBase64Url(parts[1]));
    if (typeof parsed !== 'object' || parsed === null) {
      return undefined;
    }
    payload = parsed as Record<string, unknown>;
  } catch {
    return undefined;
  }
  const realmAccess = payload.realm_access;
  const roles = typeof realmAccess === 'object' && realmAccess !== null ? strings((realmAccess as Record<string, unknown>).roles) : [];
  const phone = payload.phone_verified;
  return {
    subject: typeof payload.sub === 'string' ? payload.sub : '',
    roles,
    amr: strings(payload.amr),
    sellerId: typeof payload.seller_id === 'string' && payload.seller_id !== '' ? payload.seller_id : undefined,
    phoneVerified: phone === true || phone === 'true',
    email: typeof payload.email === 'string' ? payload.email : undefined,
    expiresAt: typeof payload.exp === 'number' ? payload.exp * 1000 : 0,
  };
}

export const SELLER_ROLES = ['seller'];
export const STAFF_ROLES = ['moderator', 'support-operator', 'admin'];
/** Очереди и решения модерации доступны только модератору (OP-11, OP-12, OP-22 – OP-24). */
export const MODERATOR_ROLES = ['moderator'];

export function hasAnyRole(claims: Claims, roles: readonly string[]): boolean {
  return roles.some((role) => claims.roles.includes(role));
}

/** Токен выдан после входа со вторым фактором (одноразовый код приложения). */
export function hasSecondFactor(claims: Claims): boolean {
  return claims.amr.includes('otp');
}
