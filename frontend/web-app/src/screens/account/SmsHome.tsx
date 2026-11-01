import { Link } from 'react-router-dom';
import { useAuth } from '../../auth/AuthContext';
import { t } from '../../i18n';
import { Button } from '../../ui/Button';

/**
 * Ограниченная сессия по коду из SMS (FT-1.7): только заказы и обращения в поддержку. Отсюда покупатель, потерявший доступ к почте, находит заказ,
 * по которому не пришёл ключ, создаёт обращение и вводит коды смены e-mail, когда оператор начал смену. Оплаты, заявки продавца и остального здесь нет.
 */
export function SmsHome() {
  const auth = useAuth();
  return (
    <section>
      <h1>{t('sms.title')}</h1>
      <p>{t('sms.lead')}</p>
      <ul>
        <li>
          <Link to="/sms/orders">{t('sms.orders')}</Link>
        </li>
        <li>
          <Link to="/sms/tickets">{t('sms.tickets')}</Link>
        </li>
      </ul>
      <p>
        <Button onClick={() => void auth.signIn('web', '/')}>{t('sms.signInFull')}</Button>{' '}
        <Button variant="secondary" onClick={() => void auth.signOut('sms')}>
          {t('sms.signOut')}
        </Button>
      </p>
    </section>
  );
}
