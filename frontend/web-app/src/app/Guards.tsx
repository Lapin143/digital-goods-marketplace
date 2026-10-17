import { useEffect, useRef } from 'react';
import type { ReactNode } from 'react';
import { useLocation } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { hasAnyRole, hasSecondFactor } from '../auth/claims';
import type { ClientKind } from '../auth/clients';
import { t } from '../i18n';
import { Button } from '../ui/Button';
import { StatePanel } from '../ui/StatePanel';

export interface RequireAccessProps {
  kind: ClientKind;
  /** Достаточно любой из ролей. */
  roles: readonly string[];
  /** Раздел открывается только токеном со вторым фактором (amr содержит otp), ADR-010. */
  secondFactor?: boolean;
  children: ReactNode;
}

/**
 * Защита маршрута (шаг 16 Ф4). Нет входа: переход на Keycloak с возвратом на этот же адрес. Вход есть, а роли нет: страница «нет доступа».
 * Нужен второй фактор, а токен выдан без него: пояснение и повторный вход с запросом пароля и кода. Права проверяет сервер,
 * эта защита лишь не показывает пользователю разделы, которые всё равно закрыты.
 */
export function RequireAccess({ kind, roles, secondFactor = false, children }: RequireAccessProps) {
  const auth = useAuth();
  const location = useLocation();
  const session = auth.session(kind);
  const returnTo = `${location.pathname}${location.search}`;

  // Переход на Keycloak запускается один раз: пока браузер уходит со страницы, состояние входа может обновиться ещё раз
  const redirected = useRef(false);
  const { ready, signIn } = auth;
  const missing = ready && !session;
  useEffect(() => {
    if (missing && !redirected.current) {
      redirected.current = true;
      void signIn(kind, returnTo);
    }
  }, [missing, signIn, kind, returnTo]);

  if (!auth.ready || !session) {
    return <StatePanel kind="loading" message={t('auth.redirecting')} />;
  }
  if (!hasAnyRole(session.claims, roles)) {
    return (
      <section>
        <h1>{t('auth.forbidden.title')}</h1>
        <p>{t('auth.forbidden.text')}</p>
      </section>
    );
  }
  if (secondFactor && !hasSecondFactor(session.claims)) {
    return (
      <section>
        <h1>{t('auth.secondFactor.title')}</h1>
        <p>{t('auth.secondFactor.text')}</p>
        <Button onClick={() => void auth.signIn(kind, returnTo, { prompt: 'login' })}>{t('auth.secondFactor.action')}</Button>
      </section>
    );
  }
  return <>{children}</>;
}
