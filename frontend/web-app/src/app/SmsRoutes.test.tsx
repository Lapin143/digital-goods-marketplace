import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { t } from '../i18n';
import { claimsFor, fakeManager, fakeUser } from '../test/fakeAuth';
import { renderScreen, respond } from '../test/harness';
import type { Handler } from '../test/harness';
import { orderFixture } from '../test/orders';
import { App } from './App';

const sms = () => fakeManager(fakeUser(claimsFor(['buyer'], ['sms'])));
const web = () => fakeManager(fakeUser(claimsFor(['buyer'], ['pwd'], { phone_verified: true })));
const TOKENS = { sms: 'sms-token', web: 'web-token' };

const ticket = {
  ticketId: 't-1',
  orderId: 'o-1',
  source: 'buyer',
  reason: 'key_not_received',
  status: 'created',
  createdAt: '2026-10-17T12:00:00.000Z',
  resolvedAt: null,
  resolution: null,
  emailChange: null,
};

function server(orderStatus: string): Handler {
  return (url) => {
    if (url.pathname === '/api/v1/orders/o-1') {
      return respond(200, orderFixture({ status: orderStatus, issued: orderStatus === 'issued', paidMinutesAgo: orderStatus === 'awaiting_payment' ? null : 3, reserveInMinutes: orderStatus === 'awaiting_payment' ? 12 : null }));
    }
    if (url.pathname === '/api/v1/orders') {
      return respond(200, { items: [orderFixture({ status: 'issued', issued: true, paidMinutesAgo: 3, reserveInMinutes: null })], page: { limit: 20, nextCursor: null } });
    }
    if (url.pathname === '/api/v1/support-tickets/t-1') {
      return respond(200, ticket);
    }
    if (url.pathname === '/api/v1/support-tickets') {
      return respond(200, { items: [ticket], page: { limit: 20, nextCursor: null } });
    }
    return respond(404, { code: 'not-found' });
  };
}

describe('TC-720. Экраны сессии по SMS: заказы и обращения', () => {
  it('главная сессии по SMS ведёт к заказам и обращениям, можно войти с паролем или завершить сессию', async () => {
    const manager = sms();
    const user = userEvent.setup();
    renderScreen(<App />, { route: '/sms', managers: { sms: manager } });
    expect(await screen.findByRole('link', { name: t('sms.orders') })).toHaveAttribute('href', '/sms/orders');
    expect(screen.getByRole('link', { name: t('sms.tickets') })).toHaveAttribute('href', '/sms/tickets');
    expect(screen.getByRole('button', { name: t('sms.signInFull') })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: t('sms.signOut') }));
    expect(manager.signoutRedirect).toHaveBeenCalledTimes(1);
  });

  it('заказы читаются токеном сессии по SMS, ссылки ведут на экраны этой же сессии', async () => {
    const { calls } = renderScreen(<App />, { route: '/sms/orders', handler: server('issued'), managers: { sms: sms() }, tokens: TOKENS });
    expect(await screen.findByRole('link', { name: '1042' })).toHaveAttribute('href', '/sms/orders/o-1');
    expect(calls.find((call) => call.url.pathname === '/api/v1/orders')?.headers.Authorization).toBe('Bearer sms-token');
  });

  it('выданный заказ: кнопка «Не получил ключ», запросы подписаны токеном сессии по SMS, ссылка назад в заказы сессии', async () => {
    const { calls } = renderScreen(<App />, { route: '/sms/orders/o-1', handler: server('issued'), managers: { sms: sms() }, tokens: TOKENS });
    expect(await screen.findByRole('button', { name: t('ticket.report') })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: t('order.back') })).toHaveAttribute('href', '/sms/orders');
    const support = calls.filter((call) => call.url.pathname === '/api/v1/support-tickets');
    expect(support.length).toBeGreaterThan(0);
    for (const call of calls) {
      expect(call.headers.Authorization, call.url.pathname).toBe('Bearer sms-token');
    }
  });

  it('заказ ждёт оплаты: в сессии по SMS кнопки «Оплатить» нет (платёжные сессии ей закрыты)', async () => {
    renderScreen(<App />, { route: '/sms/orders/o-1', handler: server('awaiting_payment'), managers: { sms: sms() }, tokens: TOKENS });
    await screen.findByRole('heading', { level: 1, name: t('order.title', { number: 1042 }) });
    expect(screen.queryByRole('button', { name: t('order.pay') })).not.toBeInTheDocument();
  });

  it('обращения: список и карточка в сессии по SMS, запросы подписаны её токеном', async () => {
    const { calls } = renderScreen(<App />, { route: '/sms/tickets/t-1', handler: server('issued'), managers: { sms: sms() }, tokens: TOKENS });
    expect(await screen.findByRole('heading', { level: 1, name: t('ticket.title') })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: t('ticket.back') })).toHaveAttribute('href', '/sms/tickets');
    expect(screen.getByRole('link', { name: t('ticket.order.open') })).toHaveAttribute('href', '/sms/orders/o-1');
    expect(calls[0]?.headers.Authorization).toBe('Bearer sms-token');
  });

  it('незнакомый адрес внутри сессии по SMS: страница «не найдено»', async () => {
    renderScreen(<App />, { route: '/sms/products', managers: { sms: sms() } });
    expect(await screen.findByRole('heading', { name: t('notFound.title') })).toBeInTheDocument();
  });

  it('без сессии по SMS экраны обращений отправляют на вход по коду из SMS, запросов к API нет', async () => {
    const manager = fakeManager(null);
    const { calls } = renderScreen(<App />, { route: '/sms/tickets', handler: server('issued'), managers: { sms: manager } });
    await screen.findByText(t('auth.redirecting'));
    await waitFor(() => expect(manager.signinRedirect).toHaveBeenCalledTimes(1));
    expect(manager.signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/sms/tickets' }, prompt: undefined });
    expect(calls).toHaveLength(0);
  });
});

describe('TC-701. Обращения в обычной сессии покупателя', () => {
  it('в меню есть «Обращения», список открывается и подписан токеном обычного входа', async () => {
    const { calls } = renderScreen(<App />, { route: '/account/tickets', handler: server('issued'), managers: { web: web() }, tokens: TOKENS });
    expect(await screen.findByRole('link', { name: t('nav.tickets') })).toHaveAttribute('href', '/account/tickets');
    expect(await screen.findByRole('heading', { level: 1, name: t('tickets.title') })).toBeInTheDocument();
    expect(calls.find((call) => call.url.pathname === '/api/v1/support-tickets')?.headers.Authorization).toBe('Bearer web-token');
  });

  it('карточка обращения по ссылке из письма открывается в обычной сессии', async () => {
    renderScreen(<App />, { route: '/account/tickets/t-1', handler: server('issued'), managers: { web: web() }, tokens: TOKENS });
    expect(await screen.findByRole('heading', { level: 1, name: t('ticket.title') })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: t('ticket.order.open') })).toHaveAttribute('href', '/account/orders/o-1');
  });

  it('выданный заказ в обычной сессии: кнопка есть, ссылка назад ведёт в /account/orders', async () => {
    renderScreen(<App />, { route: '/account/orders/o-1', handler: server('issued'), managers: { web: web() }, tokens: TOKENS });
    expect(await screen.findByRole('button', { name: t('ticket.report') })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: t('order.back') })).toHaveAttribute('href', '/account/orders');
  });

  it('гостя просят войти и возвращают на тот же адрес, запросов к API нет', async () => {
    const manager = fakeManager(null);
    const { calls } = renderScreen(<App />, { route: '/account/tickets/t-1', handler: server('issued'), managers: { web: manager } });
    await screen.findByText(t('auth.redirecting'));
    await waitFor(() => expect(manager.signinRedirect).toHaveBeenCalledTimes(1));
    expect(manager.signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/account/tickets/t-1' }, prompt: undefined });
    expect(calls).toHaveLength(0);
  });
});
