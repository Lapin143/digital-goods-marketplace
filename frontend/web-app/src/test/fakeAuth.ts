import { vi } from 'vitest';
import type { User } from 'oidc-client-ts';
import type { AuthManager } from '../auth/manager';

function base64Url(text: string): string {
  // btoa принимает только Latin-1, поэтому текст сначала переводится в байты UTF-8
  let binary = '';
  for (const byte of new TextEncoder().encode(text)) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** JWT для тестов: подпись не проверяется, интерфейс читает только полезную нагрузку. */
export function makeJwt(payload: Record<string, unknown>): string {
  return `${base64Url(JSON.stringify({ alg: 'none' }))}.${base64Url(JSON.stringify(payload))}.${base64Url('signature')}`;
}

export function claimsFor(roles: string[], amr: string[] = ['pwd'], extra: Record<string, unknown> = {}): Record<string, unknown> {
  return { sub: 'user-1', exp: Math.floor(Date.now() / 1000) + 300, realm_access: { roles }, amr, ...extra };
}

export function fakeUser(payload: Record<string, unknown>, state?: unknown): User {
  return { access_token: makeJwt(payload), expired: false, state } as unknown as User;
}

export interface FakeManager extends AuthManager {
  signinRedirect: ReturnType<typeof vi.fn<AuthManager['signinRedirect']>>;
  signoutRedirect: ReturnType<typeof vi.fn<AuthManager['signoutRedirect']>>;
  signinSilent: ReturnType<typeof vi.fn<AuthManager['signinSilent']>>;
  signinRedirectCallback: ReturnType<typeof vi.fn<AuthManager['signinRedirectCallback']>>;
}

/** Менеджер входа без Keycloak: `user` это то, что он вернёт из getUser (сессия уже есть) или null (входа нет). */
export function fakeManager(user: User | null, callbackUser?: User, refreshedUser?: User): FakeManager {
  return {
    getUser: vi.fn(() => Promise.resolve(user)),
    signinRedirect: vi.fn<AuthManager['signinRedirect']>(() => Promise.resolve()),
    signinRedirectCallback: vi.fn<AuthManager['signinRedirectCallback']>(() =>
      callbackUser ? Promise.resolve(callbackUser) : Promise.reject(new Error('нет ответа')),
    ),
    signoutRedirect: vi.fn<AuthManager['signoutRedirect']>(() => Promise.resolve()),
    signinSilent: vi.fn<AuthManager['signinSilent']>(() => (refreshedUser ? Promise.resolve(refreshedUser) : Promise.reject(new Error('нет refresh-токена')))),
    removeUser: vi.fn(() => Promise.resolve()),
    events: {
      addUserLoaded: vi.fn(),
      removeUserLoaded: vi.fn(),
      addUserUnloaded: vi.fn(),
      removeUserUnloaded: vi.fn(),
      addAccessTokenExpired: vi.fn(),
      removeAccessTokenExpired: vi.fn(),
    },
  };
}
