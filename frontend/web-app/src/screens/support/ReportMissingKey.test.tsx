import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { t } from '../../i18n';
import { renderScreen, respond } from '../../test/harness';
import type { Handler } from '../../test/harness';
import { ReportMissingKey } from './ReportMissingKey';
import { SUPPORT_WINDOW_MS } from './window';

const HOUR = 3_600_000;

function ticketFixture(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    ticketId: 't-1',
    orderId: 'o-1',
    source: 'buyer',
    reason: 'key_not_received',
    status: 'created',
    createdAt: '2026-10-17T12:00:00.000Z',
    resolvedAt: null,
    resolution: null,
    emailChange: null,
    ...overrides,
  };
}

const isList = (url: URL, init: RequestInit) => url.pathname === '/api/v1/support-tickets' && (init.method ?? 'GET') === 'GET';
const isCreate = (url: URL, init: RequestInit) => url.pathname === '/api/v1/support-tickets' && init.method === 'POST';

/** Сервер обращений: открытых обращений нет, пока не создано; создание отвечает 201 или тем, что подставил тест. */
function server(options: { create?: Handler; open?: Record<string, unknown> } = {}): { handler: Handler; created: () => boolean } {
  let created = false;
  const handler: Handler = (url, init) => {
    if (isList(url, init)) {
      return respond(200, { items: created || options.open ? [options.open ?? ticketFixture()] : [], page: { limit: 1, nextCursor: null } });
    }
    if (isCreate(url, init)) {
      const outcome = options.create ? options.create(url, init) : respond(201, ticketFixture());
      created = true;
      return outcome;
    }
    return respond(404, { code: 'not-found' });
  };
  return { handler, created: () => created };
}

describe('TC-701. «Не получил ключ» в карточке заказа: создание обращения', () => {
  it('создаёт обращение с причиной «не получил ключ» и ключом идемпотентности, потом показывает ссылку на него', async () => {
    const user = userEvent.setup();
    const { handler } = server();
    const { calls } = renderScreen(<ReportMissingKey orderId="o-1" issuedAtMs={Date.now() - HOUR} />, { handler });

    await user.click(await screen.findByRole('button', { name: t('ticket.report') }));

    expect(await screen.findByText(t('ticket.report.created'))).toBeInTheDocument();
    expect(screen.getByRole('link', { name: t('ticket.report.open') })).toHaveAttribute('href', '/account/tickets/t-1');
    expect(screen.queryByRole('button', { name: t('ticket.report') })).not.toBeInTheDocument();
    const post = calls.find((call) => call.init.method === 'POST');
    expect(post?.url.pathname).toBe('/api/v1/support-tickets');
    expect(post?.init.body).toBe(JSON.stringify({ orderId: 'o-1', reason: 'key_not_received' }));
    expect(post?.headers['Idempotency-Key']).toBe('key-1');
  });

  it('открытое обращение по заказу заменяет кнопку ссылкой на него, запрос ищет создано и в работе', async () => {
    const { handler } = server({ open: ticketFixture({ ticketId: 't-9', status: 'in_progress' }) });
    const { calls } = renderScreen(<ReportMissingKey orderId="o-1" issuedAtMs={Date.now() - HOUR} />, { handler });

    expect(await screen.findByText(t('ticket.report.exists'))).toBeInTheDocument();
    expect(screen.getByRole('link', { name: t('ticket.report.open') })).toHaveAttribute('href', '/account/tickets/t-9');
    expect(screen.queryByRole('button', { name: t('ticket.report') })).not.toBeInTheDocument();
    expect(calls[0]?.url.searchParams.get('orderId')).toBe('o-1');
    expect(calls[0]?.url.searchParams.get('status')).toBe('created,in_progress');
    expect(calls.some((call) => call.init.method === 'POST')).toBe(false);
  });

  it('с первичной выдачи прошло больше 72 часов: вместо кнопки текст «Срок обращения истёк», запроса на создание нет', async () => {
    const { handler } = server();
    const { calls } = renderScreen(<ReportMissingKey orderId="o-1" issuedAtMs={Date.now() - SUPPORT_WINDOW_MS - HOUR} />, { handler });

    expect(await screen.findByText(t('ticket.report.windowClosed', { hours: 72 }))).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('ticket.report') })).not.toBeInTheDocument();
    expect(calls.some((call) => call.init.method === 'POST')).toBe(false);
  });

  it('71 час с выдачи: кнопка ещё доступна', async () => {
    renderScreen(<ReportMissingKey orderId="o-1" issuedAtMs={Date.now() - 71 * HOUR} />, { handler: server().handler });
    expect(await screen.findByRole('button', { name: t('ticket.report') })).toBeEnabled();
  });

  it('время выдачи неизвестно: кнопка доступна, срок проверит сервер', async () => {
    renderScreen(<ReportMissingKey orderId="o-1" issuedAtMs={undefined} />, { handler: server().handler });
    expect(await screen.findByRole('button', { name: t('ticket.report') })).toBeEnabled();
  });

  it('сервер считает срок истёкшим (409 ticket-window-closed): показывает тот же текст и убирает кнопку', async () => {
    const user = userEvent.setup();
    const { handler } = server({ create: () => respond(409, { code: 'ticket-window-closed' }) });
    renderScreen(<ReportMissingKey orderId="o-1" issuedAtMs={Date.now() - HOUR} />, { handler });

    await user.click(await screen.findByRole('button', { name: t('ticket.report') }));

    expect(await screen.findByText(t('ticket.report.windowClosed', { hours: 72 }))).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('ticket.report') })).not.toBeInTheDocument();
  });

  it('обращение уже есть (409 ticket-already-open): понятный текст и ссылка на него', async () => {
    const user = userEvent.setup();
    let existing = false;
    const handler: Handler = (url, init) => {
      if (isCreate(url, init)) {
        existing = true;
        return respond(409, { code: 'ticket-already-open' });
      }
      return respond(200, { items: existing ? [ticketFixture({ ticketId: 't-5' })] : [], page: { limit: 1, nextCursor: null } });
    };
    renderScreen(<ReportMissingKey orderId="o-1" issuedAtMs={Date.now() - HOUR} />, { handler });

    await user.click(await screen.findByRole('button', { name: t('ticket.report') }));

    expect(await screen.findByRole('link', { name: t('ticket.report.open') })).toHaveAttribute('href', '/account/tickets/t-5');
    expect(screen.getByText(t('ticket.report.exists'))).toBeInTheDocument();
  });

  it('предел обращений в час (429): сообщение со временем ожидания, кнопка недоступна до его конца', async () => {
    const user = userEvent.setup();
    const { handler } = server({ create: () => respond(429, { code: 'rate-limited' }, { 'Retry-After': '30' }) });
    renderScreen(<ReportMissingKey orderId="o-1" issuedAtMs={Date.now() - HOUR} />, { handler });

    await user.click(await screen.findByRole('button', { name: t('ticket.report') }));

    expect(await screen.findByText(`${t('problem.rate-limited')} ${t('problem.retryAfter', { seconds: 30 })}`)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: t('ticket.report') })).toBeDisabled();
    expect(await screen.findByText(/Новое обращение можно создать через (29|30|31) с\./)).toBeInTheDocument();
  });

  it('обрыв связи: понятная ошибка, повтор уходит с тем же ключом идемпотентности и создаёт одно обращение', async () => {
    const user = userEvent.setup();
    let attempts = 0;
    const { handler } = server({
      create: () => {
        attempts += 1;
        return attempts === 1 ? { fail: new TypeError('Failed to fetch') } : respond(201, ticketFixture());
      },
    });
    const { calls } = renderScreen(<ReportMissingKey orderId="o-1" issuedAtMs={Date.now() - HOUR} />, { handler });

    await user.click(await screen.findByRole('button', { name: t('ticket.report') }));
    expect(await screen.findByText(t('problem.network'))).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: t('ticket.report') }));

    expect(await screen.findByText(t('ticket.report.created'))).toBeInTheDocument();
    const posts = calls.filter((call) => call.init.method === 'POST');
    expect(posts).toHaveLength(2);
    expect(posts[0]?.headers['Idempotency-Key']).toBe('key-1');
    expect(posts[1]?.headers['Idempotency-Key']).toBe('key-1');
  });

  it('двойное нажатие создаёт один запрос', async () => {
    const user = userEvent.setup();
    const { handler } = server();
    const { calls } = renderScreen(<ReportMissingKey orderId="o-1" issuedAtMs={Date.now() - HOUR} />, { handler });

    await user.dblClick(await screen.findByRole('button', { name: t('ticket.report') }));

    expect(await screen.findByText(t('ticket.report.created'))).toBeInTheDocument();
    expect(calls.filter((call) => call.init.method === 'POST')).toHaveLength(1);
  });

  it('на экране нет значений ключей', async () => {
    renderScreen(<ReportMissingKey orderId="o-1" issuedAtMs={Date.now() - HOUR} />, { handler: server().handler });
    await screen.findByRole('button', { name: t('ticket.report') });
    expect(document.body.textContent ?? '').not.toMatch(/[A-Z0-9]{4,6}(-[A-Z0-9]{4,6}){2,}/);
  });
});
