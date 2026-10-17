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

describe('маршруты кабинета продавца', () => {
  const noApplication = () => respond(404, { code: 'not-found' });

  it('заявка продавца без входа отправляет на Keycloak (dgm-web) и возвращает на /apply', async () => {
    const web = fakeManager(null);
    renderScreen(<App />, { route: '/apply', managers: { web } });
    await waitFor(() => expect(web.signinRedirect).toHaveBeenCalledTimes(1));
    expect(web.signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/apply' }, prompt: undefined });
  });

  it('заявка продавца для покупателя открыта, в навигации есть ссылка на неё', async () => {
    const web = fakeManager(fakeUser(claimsFor(['buyer'])));
    renderScreen(<App />, { route: '/apply', managers: { web }, handler: noApplication });
    expect(await screen.findByRole('heading', { level: 1, name: t('application.title') })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: t('nav.apply') })).toHaveAttribute('href', '/apply');
  });

  it('кабинет без входа отправляет на Keycloak (dgm-staff) и возвращает в кабинет', async () => {
    const staff = fakeManager(null);
    renderScreen(<App />, { route: '/seller/products', managers: { staff } });
    await waitFor(() => expect(staff.signinRedirect).toHaveBeenCalledTimes(1));
    expect(staff.signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/seller/products' }, prompt: undefined });
  });

  it('кабинет с входом без одноразового кода не открывается: предлагается войти с кодом', async () => {
    const staff = fakeManager(fakeUser(claimsFor(['seller'], ['pwd'])));
    const { calls } = renderScreen(<App />, { route: '/seller/products', managers: { staff }, handler: () => respond(200, { items: [], page: { limit: 20, nextCursor: null } }) });
    expect(await screen.findByRole('heading', { level: 1, name: t('auth.secondFactor.title') })).toBeInTheDocument();
    expect(calls).toHaveLength(0);
  });

  it('кабинет покупателю без роли продавца закрыт', async () => {
    const staff = fakeManager(fakeUser(claimsFor(['buyer'], ['pwd', 'otp'])));
    renderScreen(<App />, { route: '/seller', managers: { staff } });
    expect(await screen.findByRole('heading', { level: 1, name: t('auth.forbidden.title') })).toBeInTheDocument();
  });

  it('продавец с кодом видит кабинет и список товаров', async () => {
    const staff = fakeManager(fakeUser(claimsFor(['seller'], ['pwd', 'otp'])));
    renderScreen(<App />, { route: '/seller/products', managers: { staff }, handler: () => respond(200, { items: [], page: { limit: 20, nextCursor: null } }) });
    expect(await screen.findByRole('heading', { level: 1, name: t('seller.products.title') })).toBeInTheDocument();
    expect(await screen.findByText(t('seller.products.empty'))).toBeInTheDocument();
  });

  it('главная кабинета ведёт к товарам и к созданию товара', async () => {
    const staff = fakeManager(fakeUser(claimsFor(['seller'], ['pwd', 'otp'])));
    renderScreen(<App />, { route: '/seller', managers: { staff } });
    expect(await screen.findByRole('link', { name: t('seller.home.products') })).toHaveAttribute('href', '/seller/products');
    expect(screen.getByRole('link', { name: t('seller.home.newProduct') })).toHaveAttribute('href', '/seller/products/new');
  });

  it('страница ключей товара открывается по адресу товара', async () => {
    const staff = fakeManager(fakeUser(claimsFor(['seller'], ['pwd', 'otp'])));
    renderScreen(<App />, {
      route: '/seller/products/p-1/keys',
      managers: { staff },
      handler: () => respond(200, { productId: 'p-1', available: 0, reserved: 0, issued: 0, poolSize: 0, poolLimit: 100000, updatedAt: '2026-10-17T09:00:00Z' }),
    });
    expect(await screen.findByRole('heading', { level: 1, name: t('keys.title') })).toBeInTheDocument();
  });

  it('неизвестный адрес внутри кабинета даёт страницу «не найдено»', async () => {
    const staff = fakeManager(fakeUser(claimsFor(['seller'], ['pwd', 'otp'])));
    renderScreen(<App />, { route: '/seller/unknown', managers: { staff } });
    expect(await screen.findByRole('heading', { level: 1, name: t('notFound.title') })).toBeInTheDocument();
  });
});
