import { useAuth } from '../../auth/AuthContext';
import { t } from '../../i18n';
import { Button } from '../../ui/Button';

/** Ограниченная сессия по коду из SMS. Заказы и обращения в поддержку появятся в следующих срезах; сейчас экран объясняет границы сессии. */
export function SmsHome() {
  const auth = useAuth();
  return (
    <section>
      <h1>{t('sms.title')}</h1>
      <p>{t('sms.lead')}</p>
      <Button onClick={() => void auth.signIn('web', '/')}>{t('sms.signInFull')}</Button>
    </section>
  );
}
