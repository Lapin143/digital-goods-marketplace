import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { t } from '../i18n';
import { claimsFor, fakeManager, fakeUser } from '../test/fakeAuth';
import { renderScreen, respond } from '../test/harness';
import { App } from './App';

const emptyCatalog = () => respond(200, { items: [], page: { limit: 20, nextCursor: null } });

describe('маршруты витрины и аккаунта', () => {
  it('гость видит каталог без входа', async () => {
    const { calls } = renderScreen(<App />, { route: '/products', handler: emptyCatalog });
    expect(await screen.findByRole('heading', { level: 1, name: t('catalog.title') })).toBeInTheDocument();
    expect(await screen.findByText(t('catalog.empty.all'))).toBeInTheDocument();
    expect(calls[0]?.headers.Authorization).toBeUndefined();
  });

  it('«Войти» возвращает на ту же страницу с теми же фильтрами', async () => {
    const user = userEvent.setup();
    const web = fakeManager(null);
    renderScreen(<App />, { route: '/products?type=game_key', handler: emptyCatalog, managers: { web } });
    await user.click(await screen.findByRole('button', { name: t('nav.signIn') }));
    expect(web.signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/products?type=game_key' }, prompt: undefined });
  });

  it('«Регистрация» открывает форму регистрации Keycloak (prompt=create)', async () => {
    const user = userEvent.setup();
    const web = fakeManager(null);
    renderScreen(<App />, { route: '/', managers: { web } });
    await user.click(await screen.findByRole('button', { name: t('nav.register') }));
    expect(web.signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/' }, prompt: 'create' });
  });

  it('вошедший покупатель видит «Телефон» и «Выйти», кнопок входа нет', async () => {
    const web = fakeManager(fakeUser(claimsFor(['buyer'])));
    renderScreen(<App />, { route: '/', managers: { web } });
    expect(await screen.findByRole('link', { name: t('nav.phone') })).toHaveAttribute('href', '/account/phone');
    expect(screen.getByRole('button', { name: t('nav.signOut') })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('nav.register') })).not.toBeInTheDocument();
  });

  it('подтверждение телефона без входа отправляет на Keycloak и возвращает на ту же страницу', async () => {
    const web = fakeManager(null);
    renderScreen(<App />, { route: '/account/phone', managers: { web } });
    await waitFor(() => expect(web.signinRedirect).toHaveBeenCalledTimes(1));
    expect(web.signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/account/phone' }, prompt: undefined });
  });

  it('подтверждение телефона для покупателя открыто', async () => {
    const web = fakeManager(fakeUser(claimsFor(['buyer'])));
    renderScreen(<App />, { route: '/account/phone', managers: { web } });
    expect(await screen.findByRole('heading', { level: 1, name: t('phone.title') })).toBeInTheDocument();
  });

  it('ссылка «не пришло письмо» есть на каждой странице и ведёт на справку со входом по SMS', async () => {
    const user = userEvent.setup();
    renderScreen(<App />, { route: '/products', handler: emptyCatalog });
    await user.click(await screen.findByRole('link', { name: t('footer.noEmail') }));
    expect(await screen.findByRole('heading', { level: 1, name: t('emailHelp.title') })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: t('emailHelp.smsSignIn') })).toBeInTheDocument();
  });

  it('раздел /sms без сессии по SMS отправляет на клиент dgm-sms', async () => {
    const sms = fakeManager(null);
    renderScreen(<App />, { route: '/sms', managers: { sms } });
    await waitFor(() => expect(sms.signinRedirect).toHaveBeenCalledTimes(1));
    expect(sms.signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/sms' }, prompt: undefined });
  });

  it('раздел /sms с сессией по SMS показывает границы сессии', async () => {
    const sms = fakeManager(fakeUser(claimsFor(['buyer'], ['sms'])));
    renderScreen(<App />, { route: '/sms', managers: { sms } });
    expect(await screen.findByRole('heading', { level: 1, name: t('sms.title') })).toBeInTheDocument();
  });
});
