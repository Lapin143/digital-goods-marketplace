import { InMemoryWebStorage, UserManager, WebStorageStateStore } from 'oidc-client-ts';
import type { SigninRedirectArgs, User } from 'oidc-client-ts';
import { CLIENT_IDS, REALM, callbackPath } from './clients';
import type { ClientKind } from './clients';

/** Часть UserManager, которой пользуется приложение; в тестах её заменяет заглушка. */
export interface AuthManager {
  getUser(): Promise<User | null>;
  signinRedirect(args?: SigninRedirectArgs): Promise<void>;
  signinRedirectCallback(): Promise<User>;
  signoutRedirect(): Promise<void>;
  removeUser(): Promise<void>;
  events: {
    addUserLoaded(callback: (user: User) => void): void;
    removeUserLoaded(callback: (user: User) => void): void;
    addUserUnloaded(callback: () => void): void;
    removeUserUnloaded(callback: () => void): void;
    addAccessTokenExpired(callback: () => void): void;
    removeAccessTokenExpired(callback: () => void): void;
  };
}

export type ManagerFactory = (kind: ClientKind) => AuthManager;

/**
 * Менеджер входа клиента Keycloak: код авторизации с PKCE (S256). Токены хранятся только в памяти страницы
 * (угроза T-07: кража из localStorage). Состояние входа (`state`, проверочный код PKCE) на время перехода на Keycloak
 * лежит в sessionStorage, без токенов. Обновление токена идёт по refresh-токену, поэтому для `sms`, у которого его нет, оно выключено.
 */
export function createManager(kind: ClientKind, origin: string = window.location.origin): UserManager {
  return new UserManager({
    authority: `${origin}/auth/realms/${REALM}`,
    client_id: CLIENT_IDS[kind],
    redirect_uri: `${origin}${callbackPath(kind)}`,
    post_logout_redirect_uri: `${origin}/`,
    response_type: 'code',
    scope: 'openid',
    userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
    stateStore: new WebStorageStateStore({ store: window.sessionStorage }),
    automaticSilentRenew: kind !== 'sms',
    monitorSession: false,
    loadUserInfo: false,
  });
}
