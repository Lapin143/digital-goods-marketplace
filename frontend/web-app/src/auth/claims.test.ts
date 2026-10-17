import { describe, expect, it } from 'vitest';
import { claimsFor, makeJwt } from '../test/fakeAuth';
import { decodeClaims, hasAnyRole, hasSecondFactor } from './claims';
import { callbackPath, clientKindForPath, safeReturnTo } from './clients';

describe('токен', () => {
  it('читает роли, способы входа и признаки учётной записи', () => {
    const claims = decodeClaims(
      makeJwt(claimsFor(['seller', 'default-roles-dgm'], ['otp', 'pwd'], { seller_id: 'sel-1', phone_verified: 'true', email: 'a@example.com' })),
    );
    expect(claims).toBeDefined();
    expect(claims?.roles).toEqual(['seller', 'default-roles-dgm']);
    expect(claims?.sellerId).toBe('sel-1');
    expect(claims?.phoneVerified).toBe(true);
    expect(claims?.email).toBe('a@example.com');
    expect(claims && hasSecondFactor(claims)).toBe(true);
    expect(claims && hasAnyRole(claims, ['moderator', 'seller'])).toBe(true);
    expect(claims && hasAnyRole(claims, ['moderator'])).toBe(false);
  });

  it('без otp в amr второго фактора нет', () => {
    const claims = decodeClaims(makeJwt(claimsFor(['seller'], ['pwd'])));
    expect(claims && hasSecondFactor(claims)).toBe(false);
  });

  it('читает текст в UTF-8', () => {
    const claims = decodeClaims(makeJwt(claimsFor(['buyer'], ['pwd'], { email: 'покупатель@example.com' })));
    expect(claims?.email).toBe('покупатель@example.com');
  });

  it('мусор вместо токена даёт undefined', () => {
    expect(decodeClaims('не токен')).toBeUndefined();
    expect(decodeClaims('a.b.c')).toBeUndefined();
  });
});

describe('клиент входа по адресу', () => {
  it('кабинет и рабочее место идут через dgm-staff, остальное через dgm-web, вход по SMS через dgm-sms', () => {
    expect(clientKindForPath('/seller')).toBe('staff');
    expect(clientKindForPath('/seller/products/1')).toBe('staff');
    expect(clientKindForPath('/staff/moderation')).toBe('staff');
    expect(clientKindForPath('/work')).toBe('staff');
    expect(clientKindForPath('/sms/orders')).toBe('sms');
    expect(clientKindForPath('/')).toBe('web');
    expect(clientKindForPath('/products/1')).toBe('web');
    expect(clientKindForPath('/sellers-list')).toBe('web');
  });

  it('адрес возврата из Keycloak свой для каждого клиента', () => {
    expect(callbackPath('staff')).toBe('/oidc/callback/staff');
  });

  it('возврат после входа только на свой сайт', () => {
    expect(safeReturnTo('/seller/products')).toBe('/seller/products');
    expect(safeReturnTo('//evil.example/x')).toBe('/');
    expect(safeReturnTo('https://evil.example/')).toBe('/');
    expect(safeReturnTo('/\\evil.example')).toBe('/');
    expect(safeReturnTo(undefined)).toBe('/');
  });
});
