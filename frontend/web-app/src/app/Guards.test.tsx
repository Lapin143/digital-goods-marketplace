import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { AuthProvider } from '../auth/AuthContext';
import { SELLER_ROLES } from '../auth/claims';
import type { ClientKind } from '../auth/clients';
import { t } from '../i18n';
import { claimsFor, fakeManager, fakeUser } from '../test/fakeAuth';
import type { FakeManager } from '../test/fakeAuth';
import { RequireAccess } from './Guards';

function renderGuarded(managers: Partial<Record<ClientKind, FakeManager>>, path = '/seller/products?page=2') {
  const factory = (kind: ClientKind) => managers[kind] ?? fakeManager(null);
  render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider managerFactory={factory}>
        <Routes>
          <Route
            path="/seller/*"
            element={
              <RequireAccess kind="staff" roles={SELLER_ROLES} secondFactor>
                <p>закрытое содержимое</p>
              </RequireAccess>
            }
          />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe('RequireAccess', () => {
  it('без входа отправляет на Keycloak с возвратом на тот же адрес', async () => {
    const staff = fakeManager(null);
    renderGuarded({ staff });
    await waitFor(() => expect(staff.signinRedirect).toHaveBeenCalledTimes(1));
    expect(staff.signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/seller/products?page=2' }, prompt: undefined });
    expect(screen.queryByText('закрытое содержимое')).not.toBeInTheDocument();
    expect(screen.getByText(t('auth.redirecting'))).toBeInTheDocument();
  });

  it('вход есть, роли нет: страница «нет доступа», на Keycloak не уходит', async () => {
    const staff = fakeManager(fakeUser(claimsFor(['buyer'], ['otp', 'pwd'])));
    renderGuarded({ staff });
    expect(await screen.findByText(t('auth.forbidden.title'))).toBeInTheDocument();
    expect(staff.signinRedirect).not.toHaveBeenCalled();
    expect(screen.queryByText('закрытое содержимое')).not.toBeInTheDocument();
  });

  it('токен без otp: пояснение, повторный вход просит пароль и код заново', async () => {
    const staff = fakeManager(fakeUser(claimsFor(['seller'], ['pwd'])));
    renderGuarded({ staff });
    expect(await screen.findByText(t('auth.secondFactor.title'))).toBeInTheDocument();
    expect(screen.queryByText('закрытое содержимое')).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: t('auth.secondFactor.action') }));
    expect(staff.signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/seller/products?page=2' }, prompt: 'login' });
  });

  it('роль и второй фактор на месте: содержимое открыто', async () => {
    const staff = fakeManager(fakeUser(claimsFor(['seller'], ['otp', 'pwd'])));
    renderGuarded({ staff });
    expect(await screen.findByText('закрытое содержимое')).toBeInTheDocument();
    expect(staff.signinRedirect).not.toHaveBeenCalled();
  });

  it('сессия покупателя кабинет продавца не открывает: нужен вход через dgm-staff', async () => {
    const web = fakeManager(fakeUser(claimsFor(['seller'], ['otp', 'pwd'])));
    const staff = fakeManager(null);
    renderGuarded({ web, staff });
    await waitFor(() => expect(staff.signinRedirect).toHaveBeenCalledTimes(1));
    expect(web.signinRedirect).not.toHaveBeenCalled();
    expect(screen.queryByText('закрытое содержимое')).not.toBeInTheDocument();
  });
});
