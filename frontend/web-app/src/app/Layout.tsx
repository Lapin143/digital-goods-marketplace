import { Link, NavLink, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { SELLER_ROLES, STAFF_ROLES, hasAnyRole } from '../auth/claims';
import { t } from '../i18n';
import { Button } from '../ui/Button';
import styles from './Layout.module.css';

function AuthControls() {
  const auth = useAuth();
  const location = useLocation();
  const returnTo = `${location.pathname}${location.search}`;
  const buyer = auth.session('web');
  const worker = auth.session('staff');
  return (
    <div className={styles.controls}>
      {buyer ? (
        <Button variant="secondary" onClick={() => void auth.signOut('web')}>
          {t('nav.signOut')}
        </Button>
      ) : (
        <>
          <Button variant="secondary" onClick={() => void auth.signIn('web', returnTo)}>
            {t('nav.signIn')}
          </Button>
          <Button variant="secondary" onClick={() => void auth.signIn('web', returnTo, { prompt: 'create' })}>
            {t('nav.register')}
          </Button>
        </>
      )}
      {worker ? (
        <Button variant="secondary" onClick={() => void auth.signOut('staff')}>
          {t('nav.signOutStaff')}
        </Button>
      ) : null}
    </div>
  );
}

/** Общая раскладка: ссылка пропуска навигации, шапка с меню и входом, область содержимого. */
export function Layout() {
  const auth = useAuth();
  const worker = auth.session('staff');
  const buyer = auth.session('web');
  return (
    <>
      <a className="skip-link" href="#content">
        {t('app.skipToContent')}
      </a>
      <header className={styles.header}>
        <Link to="/" className={styles.brand}>
          {t('app.title')}
        </Link>
        <nav aria-label={t('nav.main')} className={styles.nav}>
          <NavLink to="/" end>
            {t('nav.home')}
          </NavLink>
          <NavLink to="/products">{t('nav.catalog')}</NavLink>
          {buyer ? <NavLink to="/account/orders">{t('nav.orders')}</NavLink> : null}
          {buyer ? <NavLink to="/account/phone">{t('nav.phone')}</NavLink> : null}
          {buyer ? <NavLink to="/apply">{t('nav.apply')}</NavLink> : null}
          {worker && hasAnyRole(worker.claims, SELLER_ROLES) ? <NavLink to="/seller">{t('nav.seller')}</NavLink> : null}
          {worker && hasAnyRole(worker.claims, STAFF_ROLES) ? <NavLink to="/staff">{t('nav.staff')}</NavLink> : null}
          {worker ? null : <NavLink to="/work">{t('nav.work')}</NavLink>}
        </nav>
        <AuthControls />
      </header>
      <main id="content" className={styles.content} tabIndex={-1}>
        <Outlet />
      </main>
      <footer className={styles.footer}>
        <Link to="/help/email">{t('footer.noEmail')}</Link>
      </footer>
    </>
  );
}
