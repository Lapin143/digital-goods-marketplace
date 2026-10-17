import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { t } from '../../i18n';
import { fakeManager } from '../../test/fakeAuth';
import { renderScreen } from '../../test/harness';
import { EmailHelpPage } from './EmailHelpPage';
import { SmsHome } from './SmsHome';

describe('нет доступа к e-mail', () => {
  it('объясняет, что делать, если письмо не пришло или почта недоступна', () => {
    renderScreen(<EmailHelpPage />);
    expect(screen.getByRole('heading', { level: 1, name: t('emailHelp.title') })).toBeInTheDocument();
    expect(screen.getByText(t('emailHelp.noLetter.text'))).toBeInTheDocument();
    expect(screen.getByText(t('emailHelp.noAccess.text'))).toBeInTheDocument();
  });

  it('вход по SMS идёт через клиент dgm-sms и возвращает на /sms', async () => {
    const user = userEvent.setup();
    const sms = fakeManager(null);
    const web = fakeManager(null);
    renderScreen(<EmailHelpPage />, { managers: { sms, web } });
    await user.click(screen.getByRole('button', { name: t('emailHelp.smsSignIn') }));
    expect(sms.signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/sms' }, prompt: undefined });
    expect(web.signinRedirect).not.toHaveBeenCalled();
  });
});

describe('ограниченная сессия по SMS', () => {
  it('говорит о границах сессии и предлагает полный вход', async () => {
    const user = userEvent.setup();
    const web = fakeManager(null);
    renderScreen(<SmsHome />, { managers: { web } });
    expect(screen.getByText(t('sms.lead'))).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: t('sms.signInFull') }));
    expect(web.signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/' }, prompt: undefined });
  });
});
