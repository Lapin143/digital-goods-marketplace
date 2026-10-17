import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import type { ReactElement } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { vi } from 'vitest';
import { ApiProvider } from '../api/ApiContext';
import { createApiClient } from '../api/client';
import { AuthProvider } from '../auth/AuthContext';
import type { ClientKind } from '../auth/clients';
import { fakeManager } from './fakeAuth';
import type { FakeManager } from './fakeAuth';

/** Ответ сервера для тестов: JSON, а при ошибке `application/problem+json`. */
export function respond(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  const type = status >= 400 ? 'application/problem+json' : 'application/json';
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': type, ...headers } });
}

/** Сбой сети: fetch отклонён, ответа нет. */
export interface NetworkFailure {
  fail: unknown;
}

export type Handler = (url: URL, init: RequestInit) => Response | NetworkFailure | Promise<Response | NetworkFailure>;

export interface Rendered {
  /** Все запросы к API в порядке отправки. */
  calls: Array<{ url: URL; init: RequestInit; headers: Record<string, string> }>;
  managers: Partial<Record<ClientKind, FakeManager>>;
}

export interface RenderOptions {
  route?: string;
  handler?: Handler;
  managers?: Partial<Record<ClientKind, FakeManager>>;
}

/**
 * Экран с настоящим клиентом API, но без сети: запросы попадают в `handler`. Ключи идемпотентности предсказуемы (`key-1`, `key-2`),
 * повторы запросов выключены, чтобы ошибка сразу видна в тесте.
 */
export function renderScreen(ui: ReactElement, options: RenderOptions = {}): Rendered {
  const managers = options.managers ?? {};
  const calls: Rendered['calls'] = [];
  const handler: Handler = options.handler ?? (() => respond(404, { code: 'not-found' }));
  const fetchImpl = vi.fn<typeof fetch>(async (input, init) => {
    const url = new URL(String(input), 'http://localhost');
    calls.push({ url, init: init ?? {}, headers: (init?.headers ?? {}) as Record<string, string> });
    const outcome = await handler(url, init ?? {});
    if ('fail' in outcome) {
      throw outcome.fail;
    }
    return outcome;
  });
  let counter = 0;
  const client = createApiClient({
    token: () => undefined,
    fetchImpl,
    newKey: () => {
      counter += 1;
      return `key-${counter}`;
    },
  });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  render(
    <MemoryRouter initialEntries={[options.route ?? '/']}>
      <QueryClientProvider client={queryClient}>
        <AuthProvider managerFactory={(kind) => managers[kind] ?? fakeManager(null)}>
          <ApiProvider client={client}>{ui}</ApiProvider>
        </AuthProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
  return { calls, managers };
}
