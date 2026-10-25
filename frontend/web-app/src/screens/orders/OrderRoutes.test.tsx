import { screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { App } from '../../app/App';
import { t } from '../../i18n';
import { claimsFor, fakeManager, fakeUser } from '../../test/fakeAuth';
import { renderScreen, respond } from '../../test/harness';
import type { Handler } from '../../test/harness';
import { orderFixture } from '../../test/orders';

const buyer = () => fakeManager(fakeUser(claimsFor(['buyer'], ['pwd'], { phone_verified: true })));

const server: Handler = (url) =>
  url.pathname === '/api/v1/orders/o-1'
    ? respond(200, orderFixture({ status: 'issued', issued: true, paidMinutesAgo: 2, reserveInMinutes: null }))
    : url.pathname === '/api/v1/orders'
      ? respond(200, { items: [], page: { limit: 20, nextCursor: null } })
      : respond(404, { code: 'not-found' });

describe('маршруты заказов (Р-136, Р-153)', () => {
  it('возврат со страницы шлюза /orders/<id> открывает экран заказа', async () => {
    const { calls } = renderScreen(<App />, { route: '/orders/o-1', handler: server, managers: { web: buyer() } });
    expect(await screen.findByRole('heading', { level: 1, name: t('order.title', { number: 1042 }) })).toBeInTheDocument();
    expect(calls.some((call) => call.url.pathname === '/api/v1/orders/o-1')).toBe(true);
  });

  it('ссылка из письма /account/orders/<id> открывает тот же экран', async () => {
    renderScreen(<App />, { route: '/account/orders/o-1', handler: server, managers: { web: buyer() } });
    expect(await screen.findByRole('heading', { level: 1, name: t('order.title', { number: 1042 }) })).toBeInTheDocument();
  });

  it('незнакомого покупателя сначала просят войти и возвращают на этот же адрес', async () => {
    const web = fakeManager(null);
    const { calls } = renderScreen(<App />, { route: '/account/orders/o-1', handler: server, managers: { web } });
    await screen.findByText(t('auth.redirecting'));
    await waitFor(() => expect(web.signinRedirect).toHaveBeenCalled());
    expect(web.signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/account/orders/o-1' }, prompt: undefined });
    expect(calls.filter((call) => call.url.pathname.startsWith('/api/v1/orders'))).toHaveLength(0);
  });

  it('у вошедшего покупателя в меню есть «Мои заказы», история открывается', async () => {
    renderScreen(<App />, { route: '/account/orders', handler: server, managers: { web: buyer() } });
    expect(await screen.findByRole('link', { name: t('nav.orders') })).toHaveAttribute('href', '/account/orders');
    expect(await screen.findByRole('heading', { level: 1, name: t('orders.title') })).toBeInTheDocument();
    expect(await screen.findByText(t('orders.empty'))).toBeInTheDocument();
  });
});
