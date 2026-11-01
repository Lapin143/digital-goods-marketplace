import { screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { t } from '../i18n';
import { claimsFor, fakeManager, fakeUser } from '../test/fakeAuth';
import { renderScreen, respond } from '../test/harness';
import { OPERATOR_ID, operatorManager, ownTicket } from '../test/staffSupport';
import { App } from './App';

const emptyQueue = () => respond(200, { items: [], page: { limit: 20, nextCursor: null } });

describe('маршруты оператора поддержки (TC-704)', () => {
  it('оператор с кодом видит в рабочем месте ссылку на очередь обращений', async () => {
    renderScreen(<App />, { route: '/staff', managers: { staff: operatorManager() } });
    expect(await screen.findByRole('link', { name: t('supportQueue.home') })).toHaveAttribute('href', '/staff/support');
    expect(screen.queryByRole('link', { name: t('moderation.home.applications') })).not.toBeInTheDocument();
  });

  it('модератор ссылки на очередь обращений не видит', async () => {
    const staff = fakeManager(fakeUser(claimsFor(['moderator'], ['pwd', 'otp'])));
    renderScreen(<App />, { route: '/staff', managers: { staff } });
    expect(await screen.findByRole('link', { name: t('moderation.home.applications') })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: t('supportQueue.home') })).not.toBeInTheDocument();
  });

  it('очередь обращений открывается оператору и просит данные токеном сотрудника', async () => {
    const { calls } = renderScreen(<App />, { route: '/staff/support', managers: { staff: operatorManager() }, tokens: { staff: 'staff-token' }, handler: emptyQueue });
    expect(await screen.findByRole('heading', { level: 1, name: t('supportQueue.title') })).toBeInTheDocument();
    expect(await screen.findByText(t('supportQueue.empty'))).toBeInTheDocument();
    expect(calls[0]?.url.pathname).toBe('/api/v1/staff/support-tickets');
    expect(calls[0]?.headers.Authorization).toBe('Bearer staff-token');
  });

  it('карточка обращения открывается по адресу', async () => {
    const { calls } = renderScreen(<App />, {
      route: '/staff/support/t-1',
      managers: { staff: operatorManager() },
      handler: () => respond(200, ownTicket()),
    });
    expect(await screen.findByRole('heading', { level: 1, name: t('supportCard.title', { number: 1042 }) })).toBeInTheDocument();
    expect(calls[0]?.url.pathname).toBe('/api/v1/staff/support-tickets/t-1');
  });

  it('модератору очередь обращений закрыта, запросов нет', async () => {
    const staff = fakeManager(fakeUser(claimsFor(['moderator'], ['pwd', 'otp'])));
    const { calls } = renderScreen(<App />, { route: '/staff/support', managers: { staff }, handler: emptyQueue });
    expect(await screen.findByRole('heading', { level: 1, name: t('auth.forbidden.title') })).toBeInTheDocument();
    expect(calls).toHaveLength(0);
  });

  it('администратору очередь обращений закрыта, запросов нет', async () => {
    const staff = fakeManager(fakeUser(claimsFor(['admin'], ['pwd', 'otp'])));
    const { calls } = renderScreen(<App />, { route: '/staff/support/t-1', managers: { staff }, handler: emptyQueue });
    expect(await screen.findByRole('heading', { level: 1, name: t('auth.forbidden.title') })).toBeInTheDocument();
    expect(calls).toHaveLength(0);
  });

  it('оператор без одноразового кода получает предложение войти с кодом, запросов нет', async () => {
    const staff = fakeManager(fakeUser(claimsFor(['support-operator'], ['pwd'], { sub: OPERATOR_ID })));
    const { calls } = renderScreen(<App />, { route: '/staff/support', managers: { staff }, handler: emptyQueue });
    expect(await screen.findByRole('heading', { level: 1, name: t('auth.secondFactor.title') })).toBeInTheDocument();
    expect(calls).toHaveLength(0);
  });

  it('очередь без входа отправляет на Keycloak (dgm-staff) и возвращает на тот же адрес', async () => {
    const staff = fakeManager(null);
    renderScreen(<App />, { route: '/staff/support', managers: { staff } });
    await waitFor(() => expect(staff.signinRedirect).toHaveBeenCalledTimes(1));
    expect(staff.signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/staff/support' }, prompt: undefined });
  });

  it('покупатель с обычным входом очереди не видит: вход сотрудника не открыт', async () => {
    const web = fakeManager(fakeUser(claimsFor(['buyer'])));
    const staff = fakeManager(null);
    const { calls } = renderScreen(<App />, { route: '/staff/support', managers: { web, staff }, handler: emptyQueue });
    await waitFor(() => expect(staff.signinRedirect).toHaveBeenCalledTimes(1));
    expect(calls).toHaveLength(0);
  });

  it('неизвестный адрес внутри очереди даёт страницу «не найдено»', async () => {
    renderScreen(<App />, { route: '/staff/support/t-1/лишнее', managers: { staff: operatorManager() } });
    expect(await screen.findByRole('heading', { level: 1, name: t('notFound.title') })).toBeInTheDocument();
  });
});
