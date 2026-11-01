import { screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { t } from '../i18n';
import { claimsFor, fakeManager, fakeUser } from '../test/fakeAuth';
import { ADMIN_ID, adminManager } from '../test/admin';
import { renderScreen, respond } from '../test/harness';
import type { Handler } from '../test/harness';
import { operatorManager } from '../test/staffSupport';
import { App } from './App';

const handler: Handler = (url) =>
  url.pathname === '/api/v1/staff/parameters' ? respond(200, { items: [] }) : respond(200, { items: [], page: { limit: 20, nextCursor: null } });

describe('маршруты администратора (TC-806, TC-810)', () => {
  it('администратор с кодом видит в рабочем месте ссылки на параметры и журнал, чужих ссылок нет', async () => {
    renderScreen(<App />, { route: '/staff', managers: { staff: adminManager() } });
    expect(await screen.findByRole('link', { name: t('admin.home.parameters') })).toHaveAttribute('href', '/staff/admin/parameters');
    expect(screen.getByRole('link', { name: t('admin.home.audit') })).toHaveAttribute('href', '/staff/admin/audit');
    expect(screen.queryByRole('link', { name: t('supportQueue.home') })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: t('moderation.home.applications') })).not.toBeInTheDocument();
  });

  it('оператор и модератор ссылок администратора не видят', async () => {
    renderScreen(<App />, { route: '/staff', managers: { staff: operatorManager() } });
    expect(await screen.findByRole('link', { name: t('supportQueue.home') })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: t('admin.home.parameters') })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: t('admin.home.audit') })).not.toBeInTheDocument();
  });

  it('параметры открываются администратору и просят данные токеном сотрудника', async () => {
    const { calls } = renderScreen(<App />, { route: '/staff/admin/parameters', managers: { staff: adminManager() }, tokens: { staff: 'staff-token' }, handler });
    expect(await screen.findByRole('heading', { level: 1, name: t('admin.params.title') })).toBeInTheDocument();
    expect(await screen.findByText(t('admin.params.empty'))).toBeInTheDocument();
    expect(calls[0]?.url.pathname).toBe('/api/v1/staff/parameters');
    expect(calls[0]?.headers.Authorization).toBe('Bearer staff-token');
  });

  it('журнал открывается администратору и просит данные токеном сотрудника', async () => {
    const { calls } = renderScreen(<App />, { route: '/staff/admin/audit', managers: { staff: adminManager() }, tokens: { staff: 'staff-token' }, handler });
    expect(await screen.findByRole('heading', { level: 1, name: t('audit.title') })).toBeInTheDocument();
    expect(await screen.findByText(t('audit.empty'))).toBeInTheDocument();
    expect(calls[0]?.url.pathname).toBe('/api/v1/staff/audit-records');
    expect(calls[0]?.headers.Authorization).toBe('Bearer staff-token');
  });

  it.each(['/staff/admin/parameters', '/staff/admin/audit'])('оператору %s закрыт, запросов нет', async (route) => {
    const { calls } = renderScreen(<App />, { route, managers: { staff: operatorManager() }, handler });
    expect(await screen.findByRole('heading', { level: 1, name: t('auth.forbidden.title') })).toBeInTheDocument();
    expect(calls).toHaveLength(0);
  });

  it.each(['/staff/admin/parameters', '/staff/admin/audit'])('модератору %s закрыт, запросов нет', async (route) => {
    const staff = fakeManager(fakeUser(claimsFor(['moderator'], ['pwd', 'otp'])));
    const { calls } = renderScreen(<App />, { route, managers: { staff }, handler });
    expect(await screen.findByRole('heading', { level: 1, name: t('auth.forbidden.title') })).toBeInTheDocument();
    expect(calls).toHaveLength(0);
  });

  it.each(['/staff/admin/parameters', '/staff/admin/audit'])('администратор без одноразового кода получает предложение войти с кодом на %s, запросов нет', async (route) => {
    const staff = fakeManager(fakeUser(claimsFor(['admin'], ['pwd'], { sub: ADMIN_ID })));
    const { calls } = renderScreen(<App />, { route, managers: { staff }, handler });
    expect(await screen.findByRole('heading', { level: 1, name: t('auth.secondFactor.title') })).toBeInTheDocument();
    expect(calls).toHaveLength(0);
  });

  it('без входа журнал отправляет на Keycloak (dgm-staff) и возвращает на тот же адрес', async () => {
    const staff = fakeManager(null);
    renderScreen(<App />, { route: '/staff/admin/audit', managers: { staff } });
    await waitFor(() => expect(staff.signinRedirect).toHaveBeenCalledTimes(1));
    expect(staff.signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/staff/admin/audit' }, prompt: undefined });
  });

  it('покупатель с обычным входом параметров не видит: вход сотрудника не открыт', async () => {
    const web = fakeManager(fakeUser(claimsFor(['buyer'])));
    const staff = fakeManager(null);
    const { calls } = renderScreen(<App />, { route: '/staff/admin/parameters', managers: { web, staff }, handler });
    await waitFor(() => expect(staff.signinRedirect).toHaveBeenCalledTimes(1));
    expect(calls).toHaveLength(0);
  });

  it('неизвестный адрес внутри раздела администратора даёт страницу «не найдено»', async () => {
    renderScreen(<App />, { route: '/staff/admin/лишнее', managers: { staff: adminManager() } });
    expect(await screen.findByRole('heading', { level: 1, name: t('notFound.title') })).toBeInTheDocument();
  });
});
