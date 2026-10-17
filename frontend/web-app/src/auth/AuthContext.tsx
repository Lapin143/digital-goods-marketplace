import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import type { User } from 'oidc-client-ts';
import { decodeClaims } from './claims';
import type { Claims } from './claims';
import { safeReturnTo } from './clients';
import type { ClientKind } from './clients';
import { createManager } from './manager';
import type { AuthManager, ManagerFactory } from './manager';

/** Вход по одному из клиентов: токен и то, что из него прочитано. */
export interface Session {
  kind: ClientKind;
  accessToken: string;
  claims: Claims;
}

export interface SignInOptions {
  /** `login` заставляет Keycloak спросить пароль и код заново (повторный вход после настройки второго фактора). */
  prompt?: 'login';
}

export interface AuthApi {
  /** Менеджеры входа уже опрошены: до этого момента «нет сессии» ещё не значит «не вошёл». */
  ready: boolean;
  session: (kind: ClientKind) => Session | undefined;
  /** Токен для запросов API; читается в момент запроса, поэтому всегда свежий после обновления. */
  token: (kind: ClientKind) => string | undefined;
  signIn: (kind: ClientKind, returnTo?: string, options?: SignInOptions) => Promise<void>;
  signOut: (kind: ClientKind) => Promise<void>;
  /** Завершает вход на странице возврата и отдаёт адрес, куда вернуть пользователя. */
  completeSignIn: (kind: ClientKind) => Promise<string>;
  /** Забыть сессию без перехода на Keycloak (сервер ответил 401). */
  forget: (kind: ClientKind) => void;
}

const KINDS: readonly ClientKind[] = ['web', 'staff', 'sms'];

type Sessions = Partial<Record<ClientKind, Session>>;

function toSession(kind: ClientKind, user: User | null): Session | undefined {
  if (!user || user.expired || !user.access_token) {
    return undefined;
  }
  const claims = decodeClaims(user.access_token);
  return claims ? { kind, accessToken: user.access_token, claims } : undefined;
}

const AuthContext = createContext<AuthApi | undefined>(undefined);

export interface AuthProviderProps {
  children: ReactNode;
  /** Для тестов: свои менеджеры входа вместо oidc-client-ts. */
  managerFactory?: ManagerFactory;
}

export function AuthProvider({ children, managerFactory }: AuthProviderProps) {
  const managers = useRef<Partial<Record<ClientKind, AuthManager>>>({});
  const sessionsRef = useRef<Sessions>({});
  const [sessions, setSessions] = useState<Sessions>({});
  const [ready, setReady] = useState(false);

  const manager = useCallback(
    (kind: ClientKind): AuthManager => {
      let found = managers.current[kind];
      if (!found) {
        found = managerFactory ? managerFactory(kind) : createManager(kind);
        managers.current[kind] = found;
      }
      return found;
    },
    [managerFactory],
  );

  const store = useCallback((kind: ClientKind, session: Session | undefined) => {
    const next = { ...sessionsRef.current };
    if (session) {
      next[kind] = session;
    } else {
      delete next[kind];
    }
    sessionsRef.current = next;
    setSessions(next);
  }, []);

  useEffect(() => {
    let alive = true;
    const unsubscribe: Array<() => void> = [];
    for (const kind of KINDS) {
      const current = manager(kind);
      const loaded = (user: User) => store(kind, toSession(kind, user));
      const gone = () => store(kind, undefined);
      current.events.addUserLoaded(loaded);
      current.events.addUserUnloaded(gone);
      current.events.addAccessTokenExpired(gone);
      unsubscribe.push(() => {
        current.events.removeUserLoaded(loaded);
        current.events.removeUserUnloaded(gone);
        current.events.removeAccessTokenExpired(gone);
      });
    }
    void Promise.all(
      KINDS.map(async (kind) => {
        const session = toSession(kind, await manager(kind).getUser());
        if (alive && session) {
          store(kind, session);
        }
      }),
    ).then(() => {
      if (alive) {
        setReady(true);
      }
    });
    return () => {
      alive = false;
      unsubscribe.forEach((off) => off());
    };
  }, [manager, store]);

  // token и forget не меняются между отрисовками: клиент API создаётся один раз и всегда читает свежий токен
  const token = useCallback((kind: ClientKind) => sessionsRef.current[kind]?.accessToken, []);
  const forget = useCallback(
    (kind: ClientKind) => {
      store(kind, undefined);
      void manager(kind).removeUser();
    },
    [manager, store],
  );

  const api = useMemo<AuthApi>(
    () => ({
      ready,
      session: (kind) => sessions[kind],
      token,
      signIn: async (kind, returnTo, options) => {
        await manager(kind).signinRedirect({ state: { returnTo: safeReturnTo(returnTo) }, prompt: options?.prompt });
      },
      signOut: async (kind) => {
        await manager(kind).signoutRedirect();
      },
      completeSignIn: async (kind) => {
        const user = await manager(kind).signinRedirectCallback();
        store(kind, toSession(kind, user));
        const state: unknown = user.state;
        const returnTo = typeof state === 'object' && state !== null ? (state as Record<string, unknown>).returnTo : undefined;
        return safeReturnTo(returnTo);
      },
      forget,
    }),
    [ready, sessions, token, forget, manager, store],
  );

  return <AuthContext.Provider value={api}>{children}</AuthContext.Provider>;
}

// Хук лежит рядом с провайдером намеренно: так связаны контекст и его потребители
export function useAuth(): AuthApi {
  const value = useContext(AuthContext);
  if (!value) {
    throw new Error('useAuth вызван вне AuthProvider');
  }
  return value;
}
