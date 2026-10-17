import { useAuth } from '../../auth/AuthContext';
import { t } from '../../i18n';
import { Button } from '../../ui/Button';

/**
 * «Не пришло письмо / нет доступа к e-mail» (BPMN-04). Единственное место, откуда открывается вход по коду из SMS
 * (клиент `dgm-sms`, решение Р-24): обычного входа он не заменяет и открывает ограниченную сессию.
 */
export function EmailHelpPage() {
  const auth = useAuth();
  return (
    <section>
      <h1>{t('emailHelp.title')}</h1>
      <h2>{t('emailHelp.noLetter.title')}</h2>
      <p>{t('emailHelp.noLetter.text')}</p>
      <h2>{t('emailHelp.noAccess.title')}</h2>
      <p>{t('emailHelp.noAccess.text')}</p>
      <Button onClick={() => void auth.signIn('sms', '/sms')}>{t('emailHelp.smsSignIn')}</Button>
    </section>
  );
}
