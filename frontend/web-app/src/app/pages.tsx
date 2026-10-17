import { useEffect, useRef, useState } from 'react';
import { Link, Navigate, useNavigate, useParams } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { SELLER_ROLES, STAFF_ROLES, hasAnyRole } from '../auth/claims';
import { isClientKind } from '../auth/clients';
import { t } from '../i18n';
import { Button } from '../ui/Button';
import { StatePanel } from '../ui/StatePanel';

export function HomePage() {
  return (
    <section>
      <h1>{t('home.title')}</h1>
      <p>{t('home.lead')}</p>
      <p>
        <Link to="/work">{t('home.workLink')}</Link>
      </p>
    </section>
  );
}

export function NotFoundPage() {
  return (
    <section>
      <h1>{t('notFound.title')}</h1>
      <p>{t('notFound.text')}</p>
      <p>
        <Link to="/">{t('notFound.toHome')}</Link>
      </p>
    </section>
  );
}

/** Страница возврата из Keycloak: завершает вход и отправляет пользователя туда, откуда он пришёл. */
export function CallbackPage() {
  const { kind } = useParams();
  const auth = useAuth();
  const navigate = useNavigate();
  const [failed, setFailed] = useState(false);
  const started = useRef(false);
  const { completeSignIn, signIn } = auth;

  useEffect(() => {
    if (started.current || !isClientKind(kind)) {
      return;
    }
    started.current = true;
    completeSignIn(kind).then(
      (returnTo) => navigate(returnTo, { replace: true }),
      () => setFailed(true),
    );
  }, [kind, completeSignIn, navigate]);

  if (!isClientKind(kind)) {
    return <NotFoundPage />;
  }
  if (failed) {
    return (
      <section>
        <h1>{t('auth.callback.failedTitle')}</h1>
        <p>{t('auth.callback.failedText')}</p>
        <Button onClick={() => void signIn(kind, '/')}>{t('auth.callback.retry')}</Button>
      </section>
    );
  }
  return <StatePanel kind="loading" message={t('auth.callback.working')} />;
}

/** Вход для продавцов и сотрудников: после входа отправляет в раздел по роли. */
export function WorkEntry() {
  const auth = useAuth();
  const session = auth.session('staff');
  if (!session) {
    return <StatePanel kind="loading" message={t('auth.redirecting')} />;
  }
  if (hasAnyRole(session.claims, STAFF_ROLES)) {
    return <Navigate to="/staff" replace />;
  }
  if (hasAnyRole(session.claims, SELLER_ROLES)) {
    return <Navigate to="/seller" replace />;
  }
  return (
    <section>
      <h1>{t('work.title')}</h1>
      <p>{t('work.noRole')}</p>
    </section>
  );
}

export function SellerHome() {
  return (
    <section>
      <h1>{t('work.seller.title')}</h1>
      <p>{t('work.seller.lead')}</p>
    </section>
  );
}

export function StaffHome() {
  return (
    <section>
      <h1>{t('work.staff.title')}</h1>
      <p>{t('work.staff.lead')}</p>
    </section>
  );
}
