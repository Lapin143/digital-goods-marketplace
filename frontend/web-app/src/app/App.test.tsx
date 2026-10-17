import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { AuthProvider } from '../auth/AuthContext';
import type { ClientKind } from '../auth/clients';
import { t } from '../i18n';
import { claimsFor, fakeManager, fakeUser } from '../test/fakeAuth';
import type { FakeManager } from '../test/fakeAuth';
import { App } from './App';

function renderApp(path: string, managers: Partial<Record<ClientKind, FakeManager>> = {}) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider managerFactory={(kind) => managers[kind] ?? fakeManager(null)}>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe('приложение', () => {
  it('главная открыта без входа, в шапке есть вход и ссылка для продавцов и сотрудников', async () => {
    renderApp('/');
    expect(await screen.findByRole('heading', { name: t('home.title') })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: t('nav.signIn') })).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: t('nav.work') }).length).toBeGreaterThan(0);
  });

  it('неизвестный адрес даёт страницу «не найдено»', async () => {
    renderApp('/нет-такой-страницы');
    expect(await screen.findByRole('heading', { name: t('notFound.title') })).toBeInTheDocument();
  });

  it('первая ссылка страницы ведёт к содержимому', async () => {
    renderApp('/');
    expect(await screen.findByRole('link', { name: t('app.skipToContent') })).toHaveAttribute('href', '#content');
  });

  it('сессия сотрудника показывает разделы по ролям и выход', async () => {
    const staff = fakeManager(fakeUser(claimsFor(['moderator'], ['otp', 'pwd'])));
    renderApp('/', { staff });
    await waitFor(() => expect(screen.getByRole('link', { name: t('nav.staff') })).toBeInTheDocument());
    expect(screen.queryByRole('link', { name: t('nav.seller') })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: t('nav.signOutStaff') })).toBeInTheDocument();
  });

  it('страница возврата из Keycloak завершает вход и ведёт на сохранённый адрес', async () => {
    const callbackUser = fakeUser(claimsFor(['seller'], ['otp', 'pwd']), { returnTo: '/seller' });
    const staff = fakeManager(null, callbackUser);
    renderApp('/oidc/callback/staff', { staff });
    await waitFor(() => expect(staff.signinRedirectCallback).toHaveBeenCalledTimes(1));
    expect(await screen.findByRole('heading', { name: t('work.seller.title') })).toBeInTheDocument();
  });

  it('ошибка на странице возврата: понятное сообщение и повторный вход', async () => {
    const staff = fakeManager(null);
    renderApp('/oidc/callback/staff', { staff });
    expect(await screen.findByText(t('auth.callback.failedTitle'))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: t('auth.callback.retry') })).toBeInTheDocument();
  });
});
