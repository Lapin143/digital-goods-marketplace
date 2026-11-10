import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { t } from '../../i18n';
import { renderScreen, respond } from '../../test/harness';
import type { Handler, Rendered } from '../../test/harness';
import { formatMoment } from '../../ui/format';
import { ApiKeysPage } from './ApiKeysPage';

const SCREEN = (
  <Routes>
    <Route path="/seller/api-keys" element={<ApiKeysPage />} />
  </Routes>
);
const ROUTE = '/seller/api-keys';
const LIST = 'GET /api/v1/seller/api-keys';
const ISSUE = 'POST /api/v1/seller/api-keys';
const SECRET = `dgm_${'A'.repeat(40)}`;

function apiKey(id: string, overrides: Record<string, unknown> = {}) {
  return { id, name: `Ключ ${id}`, fingerprint: 'kJ3x9Zt0', status: 'active', createdAt: '2026-11-01T09:00:00Z', revokedAt: null, ...overrides };
}

type Key = ReturnType<typeof apiKey>;

function issued(key: Key, withValue = true) {
  return { apiKey: key, ...(withValue ? { value: SECRET } : {}), shownOnce: true };
}

/** Сервер ключей: список из `state.keys`, выпуск добавляет ключ, отзыв переводит его в «отозван». */
function server(state: { keys: Key[] }, overrides: Record<string, Handler> = {}): Handler {
  return (url, init) => {
    const route = `${init.method ?? 'GET'} ${url.pathname}`;
    const custom = overrides[route];
    if (custom) {
      return custom(url, init);
    }
    if (route === LIST) {
      return respond(200, { items: state.keys, page: { limit: 50, nextCursor: null } });
    }
    if (route === ISSUE) {
      const body = JSON.parse(String(init.body)) as { name: string };
      const key = apiKey('k-new', { name: body.name });
      state.keys = [key, ...state.keys];
      return respond(201, issued(key));
    }
    const revoke = /^POST \/api\/v1\/seller\/api-keys\/([\w-]+)\/revocation$/.exec(route);
    if (revoke) {
      state.keys = state.keys.map((key) => (key.id === revoke[1] ? { ...key, status: 'revoked', revokedAt: '2026-11-03T09:00:00Z' } : key));
      return respond(200, state.keys.find((key) => key.id === revoke[1]));
    }
    return respond(404, { code: 'not-found' });
  };
}

function lists(calls: Rendered['calls']) {
  return calls.filter((call) => call.url.pathname === '/api/v1/seller/api-keys' && (call.init.method ?? 'GET') === 'GET');
}

function storageText(): string {
  return JSON.stringify([{ ...window.localStorage }, { ...window.sessionStorage }]);
}

async function issueKey(user: ReturnType<typeof userEvent.setup>, name: string) {
  await user.type(await screen.findByLabelText(t('apiKeys.issue.name')), name);
  await user.click(screen.getByRole('button', { name: t('apiKeys.issue') }));
}

describe('список ключей', () => {
  it('имя, отпечаток, статус и даты; у отозванного нет кнопки отзыва', async () => {
    const state = {
      keys: [
        apiKey('k-1', { name: 'Склад', fingerprint: 'kJ3x9Zt0', createdAt: '2026-11-01T09:00:00Z' }),
        apiKey('k-2', { name: 'Старый', fingerprint: 'Zz9yX8w7', status: 'revoked', createdAt: '2026-10-01T09:00:00Z', revokedAt: '2026-10-05T09:00:00Z' }),
      ],
    };
    renderScreen(SCREEN, { route: ROUTE, handler: server(state) });
    const table = await screen.findByRole('table');
    expect(within(table).getByText('Склад')).toBeInTheDocument();
    expect(within(table).getByText('dgm_kJ3x9Zt0…')).toBeInTheDocument();
    expect(within(table).getByText('dgm_Zz9yX8w7…')).toBeInTheDocument();
    expect(within(table).getByText(t('apiKeys.status.active'))).toBeInTheDocument();
    expect(within(table).getByText(t('apiKeys.status.revoked'))).toBeInTheDocument();
    expect(within(table).getByText(formatMoment('2026-11-01T09:00:00Z'))).toBeInTheDocument();
    expect(within(table).getByText(formatMoment('2026-10-05T09:00:00Z'))).toBeInTheDocument();
    expect(within(table).getByRole('button', { name: t('apiKeys.revoke.label', { name: 'Склад' }) })).toBeInTheDocument();
    expect(within(table).queryByRole('button', { name: t('apiKeys.revoke.label', { name: 'Старый' }) })).not.toBeInTheDocument();
  });

  it('ключей нет: пустое состояние', async () => {
    renderScreen(SCREEN, { route: ROUTE, handler: server({ keys: [] }) });
    expect(await screen.findByText(t('apiKeys.empty'))).toBeInTheDocument();
  });

  it('ошибка списка: сообщение и повтор', async () => {
    const user = userEvent.setup();
    let fail = true;
    const { calls } = renderScreen(SCREEN, {
      route: ROUTE,
      handler: server({ keys: [] }, { [LIST]: () => (fail ? respond(500, { code: 'internal-error' }) : respond(200, { items: [], page: { limit: 50, nextCursor: null } })) }),
    });
    expect(await screen.findByText(t('problem.internal-error'))).toBeInTheDocument();
    fail = false;
    await user.click(screen.getByRole('button', { name: t('state.retry') }));
    expect(await screen.findByText(t('apiKeys.empty'))).toBeInTheDocument();
    expect(lists(calls)).toHaveLength(2);
  });
});

describe('выпуск ключа', () => {
  it('запрос уходит с Idempotency-Key, значение показано в панели, имя очищено, список обновлён', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler: server({ keys: [] }) });
    await issueKey(user, 'Склад');

    const panel = await screen.findByRole('alertdialog', { name: t('apiKeys.issued.heading', { name: 'Склад' }) });
    expect(within(panel).getByText(SECRET)).toBeInTheDocument();
    expect(within(panel).getByText(t('apiKeys.issued.warning'))).toBeInTheDocument();
    const post = calls.find((call) => call.init.method === 'POST');
    expect(post?.headers['Idempotency-Key']).toBe('key-1');
    expect(JSON.parse(String(post?.init.body))).toEqual({ name: 'Склад' });
    expect(screen.getByLabelText(t('apiKeys.issue.name'))).toHaveValue('');
    await waitFor(() => expect(lists(calls)).toHaveLength(2));
    // В списке только метаданные: значения ключа в таблице нет
    expect(within(await screen.findByRole('table')).queryByText(SECRET)).not.toBeInTheDocument();
  });

  it('пока панель открыта, выпустить второй ключ нельзя', async () => {
    const user = userEvent.setup();
    renderScreen(SCREEN, { route: ROUTE, handler: server({ keys: [] }) });
    await issueKey(user, 'Склад');
    await screen.findByRole('alertdialog');
    expect(screen.getByRole('button', { name: t('apiKeys.issue') })).toBeDisabled();
  });

  it('панель закрывается только явным подтверждением, Escape её не закрывает', async () => {
    const user = userEvent.setup();
    renderScreen(SCREEN, { route: ROUTE, handler: server({ keys: [] }) });
    await issueKey(user, 'Склад');
    const panel = await screen.findByRole('alertdialog');
    expect(panel).toHaveFocus();

    await user.keyboard('{Escape}');
    expect(screen.getByRole('alertdialog')).toBeInTheDocument();
    const close = within(panel).getByRole('button', { name: t('secret.close') });
    expect(close).toBeDisabled();

    await user.click(within(panel).getByRole('checkbox', { name: t('secret.saved') }));
    expect(close).toBeEnabled();
    await user.click(close);
    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument());
  });

  it('после закрытия значения ключа нет ни на странице, ни в хранилище браузера, фокус возвращается на кнопку выпуска', async () => {
    const user = userEvent.setup();
    renderScreen(SCREEN, { route: ROUTE, handler: server({ keys: [] }) });
    await issueKey(user, 'Склад');
    const panel = await screen.findByRole('alertdialog');
    // Пока панель открыта, значение видно, но в хранилище оно не уходит
    expect(document.body.textContent).toContain(SECRET);
    expect(storageText()).not.toContain(SECRET);

    await user.click(within(panel).getByRole('checkbox', { name: t('secret.saved') }));
    await user.click(within(panel).getByRole('button', { name: t('secret.close') }));
    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument());

    expect(document.body.textContent).not.toContain(SECRET);
    expect(storageText()).not.toContain(SECRET);
    await waitFor(() => expect(screen.getByRole('button', { name: t('apiKeys.issue') })).toHaveFocus());
  });

  it('«Скопировать» кладёт значение в буфер обмена', async () => {
    const user = userEvent.setup();
    const writeText = vi.fn<(text: string) => Promise<void>>().mockResolvedValue(undefined);
    Object.defineProperty(window.navigator, 'clipboard', { value: { writeText }, configurable: true });
    renderScreen(SCREEN, { route: ROUTE, handler: server({ keys: [] }) });
    await issueKey(user, 'Склад');
    await user.click(await screen.findByRole('button', { name: t('secret.copy') }));
    expect(await screen.findByText(t('secret.copied'))).toBeInTheDocument();
    expect(writeText).toHaveBeenCalledWith(SECRET);
  });

  it('буфер обмена недоступен: подсказка выделить значение вручную, панель остаётся', async () => {
    const user = userEvent.setup();
    const writeText = vi.fn<(text: string) => Promise<void>>().mockRejectedValue(new Error('denied'));
    Object.defineProperty(window.navigator, 'clipboard', { value: { writeText }, configurable: true });
    renderScreen(SCREEN, { route: ROUTE, handler: server({ keys: [] }) });
    await issueKey(user, 'Склад');
    await user.click(await screen.findByRole('button', { name: t('secret.copy') }));
    expect(await screen.findByText(t('secret.copyFailed'))).toBeInTheDocument();
    expect(screen.getByText(SECRET)).toBeInTheDocument();
  });

  it('пустое имя: ошибка поля, запроса нет', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler: server({ keys: [] }) });
    await user.click(await screen.findByRole('button', { name: t('apiKeys.issue') }));
    expect(await screen.findByText(t('field.required'))).toBeInTheDocument();
    expect(calls.every((call) => call.init.method !== 'POST')).toBe(true);
  });

  it('422 по полю name: ошибка под полем и общее сообщение', async () => {
    const user = userEvent.setup();
    renderScreen(SCREEN, {
      route: ROUTE,
      handler: server({ keys: [] }, { [ISSUE]: () => respond(422, { code: 'validation-failed', errors: [{ pointer: '/name', code: 'too_long' }] }) }),
    });
    await issueKey(user, 'Склад');
    expect(await screen.findByText(t('field.too_long'))).toBeInTheDocument();
    expect(screen.getByText(t('problem.validation-failed'))).toBeInTheDocument();
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
  });

  it('сбой сети: повтор с тем же именем уходит с тем же Idempotency-Key', async () => {
    const user = userEvent.setup();
    let attempt = 0;
    const { calls } = renderScreen(SCREEN, {
      route: ROUTE,
      handler: server(
        { keys: [] },
        {
          [ISSUE]: () => {
            attempt += 1;
            return attempt === 1 ? { fail: new TypeError('network') } : respond(201, issued(apiKey('k-new', { name: 'Склад' })));
          },
        },
      ),
    });
    await issueKey(user, 'Склад');
    expect(await screen.findByText(`${t('problem.network')} ${t('apiKeys.retryHint')}`)).toBeInTheDocument();
    expect(screen.getByLabelText(t('apiKeys.issue.name'))).toHaveValue('Склад');

    await user.click(screen.getByRole('button', { name: t('apiKeys.issue') }));
    expect(await screen.findByRole('alertdialog', { name: t('apiKeys.issued.heading', { name: 'Склад' }) })).toBeInTheDocument();
    const posts = calls.filter((call) => call.init.method === 'POST');
    expect(posts).toHaveLength(2);
    expect(posts[0]?.headers['Idempotency-Key']).toBe('key-1');
    expect(posts[1]?.headers['Idempotency-Key']).toBe('key-1');
  });

  it('повтор вернул ключ без значения: объяснение, что делать, панели нет', async () => {
    const user = userEvent.setup();
    renderScreen(SCREEN, { route: ROUTE, handler: server({ keys: [] }, { [ISSUE]: () => respond(201, issued(apiKey('k-new', { name: 'Склад' }), false)) }) });
    await issueKey(user, 'Склад');
    expect(await screen.findByText(t('apiKeys.lost', { name: 'Склад' }))).toBeInTheDocument();
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
  });
});

describe('предел в пять ключей', () => {
  it('пять действующих: сообщение с объяснением, кнопка выпуска недоступна', async () => {
    const state = { keys: ['1', '2', '3', '4', '5'].map((id) => apiKey(`k-${id}`)) };
    renderScreen(SCREEN, { route: ROUTE, handler: server(state) });
    expect(await screen.findByText(t('apiKeys.limitReached', { limit: 5 }))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: t('apiKeys.issue') })).toBeDisabled();
  });

  it('отозванные в предел не входят', async () => {
    const state = {
      keys: [apiKey('k-1'), apiKey('k-2'), apiKey('k-3'), apiKey('k-4'), apiKey('k-5', { status: 'revoked', revokedAt: '2026-11-02T09:00:00Z' }), apiKey('k-6', { status: 'revoked', revokedAt: '2026-11-02T09:00:00Z' })],
    };
    renderScreen(SCREEN, { route: ROUTE, handler: server(state) });
    await screen.findByRole('table');
    expect(screen.queryByText(t('apiKeys.limitReached', { limit: 5 }))).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: t('apiKeys.issue') })).toBeEnabled();
  });

  it('409 api-key-limit от сервера (шестой при гонке): понятное сообщение, введённое имя остаётся', async () => {
    const user = userEvent.setup();
    renderScreen(SCREEN, { route: ROUTE, handler: server({ keys: [apiKey('k-1')] }, { [ISSUE]: () => respond(409, { code: 'api-key-limit', limit: 5 }) }) });
    await issueKey(user, 'Шестой');
    expect(await screen.findByText(t('problem.api-key-limit'))).toBeInTheDocument();
    expect(screen.getByLabelText(t('apiKeys.issue.name'))).toHaveValue('Шестой');
  });
});

describe('отзыв ключа', () => {
  const REVOKE = 'POST /api/v1/seller/api-keys/k-1/revocation';

  it('отзыв требует подтверждения: «Не отзывать» закрывает панель, запроса нет, фокус на кнопке строки', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler: server({ keys: [apiKey('k-1', { name: 'Склад' })] }) });
    const open = await screen.findByRole('button', { name: t('apiKeys.revoke.label', { name: 'Склад' }) });
    await user.click(open);

    const dialog = await screen.findByRole('alertdialog', { name: t('apiKeys.revoke.heading', { name: 'Склад' }) });
    expect(within(dialog).getByText(t('apiKeys.revoke.text', { mark: 'dgm_kJ3x9Zt0' }))).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: t('apiKeys.revoke.cancel') })).toHaveFocus();

    await user.click(within(dialog).getByRole('button', { name: t('apiKeys.revoke.cancel') }));
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(calls.every((call) => call.init.method !== 'POST')).toBe(true);
    expect(open).toHaveFocus();
  });

  it('Escape отменяет подтверждение, фокус возвращается на кнопку строки', async () => {
    const user = userEvent.setup();
    renderScreen(SCREEN, { route: ROUTE, handler: server({ keys: [apiKey('k-1', { name: 'Склад' })] }) });
    const open = await screen.findByRole('button', { name: t('apiKeys.revoke.label', { name: 'Склад' }) });
    await user.click(open);
    await screen.findByRole('alertdialog');
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(open).toHaveFocus();
  });

  it('подтверждение: запрос отзыва с Idempotency-Key, сообщение, строка стала «отозван», кнопки отзыва нет', async () => {
    const user = userEvent.setup();
    const state = { keys: [apiKey('k-1', { name: 'Склад' })] };
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler: server(state) });
    await user.click(await screen.findByRole('button', { name: t('apiKeys.revoke.label', { name: 'Склад' }) }));
    const dialog = await screen.findByRole('alertdialog');
    await user.click(within(dialog).getByRole('button', { name: t('apiKeys.revoke.confirm') }));

    expect(await screen.findByText(t('apiKeys.revoked', { name: 'Склад' }))).toBeInTheDocument();
    const post = calls.find((call) => call.url.pathname === '/api/v1/seller/api-keys/k-1/revocation');
    expect(post?.init.method).toBe('POST');
    expect(post?.headers['Idempotency-Key']).toBe('key-1');
    await waitFor(() => expect(screen.queryByRole('button', { name: t('apiKeys.revoke.label', { name: 'Склад' }) })).not.toBeInTheDocument());
    expect(within(screen.getByRole('table')).getByText(t('apiKeys.status.revoked'))).toBeInTheDocument();
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
  });

  it('отказ сервера: сообщение, панель закрыта, ключ в списке остался действующим', async () => {
    const user = userEvent.setup();
    renderScreen(SCREEN, { route: ROUTE, handler: server({ keys: [apiKey('k-1', { name: 'Склад' })] }, { [REVOKE]: () => respond(404, { code: 'not-found' }) }) });
    await user.click(await screen.findByRole('button', { name: t('apiKeys.revoke.label', { name: 'Склад' }) }));
    await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: t('apiKeys.revoke.confirm') }));
    expect(await screen.findByText(t('problem.not-found'))).toBeInTheDocument();
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: t('apiKeys.revoke.label', { name: 'Склад' }) })).toBeInTheDocument();
  });
});
