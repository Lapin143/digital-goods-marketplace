import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { t } from '../../i18n';
import { formatMoment } from '../../ui/format';
import { renderScreen, respond } from '../../test/harness';
import { TicketListPage } from './TicketListPage';

function ticket(id: string, createdAt: string, overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    ticketId: id,
    orderId: 'o-1',
    source: 'buyer',
    reason: 'key_not_received',
    status: 'created',
    createdAt,
    resolvedAt: null,
    resolution: null,
    emailChange: null,
    ...overrides,
  };
}

describe('TC-701. Мои обращения', () => {
  it('новые выше, у каждого статус и ссылка на карточку; запрос идёт без фильтров по двадцать', async () => {
    const { calls } = renderScreen(<TicketListPage />, {
      handler: () =>
        respond(200, {
          items: [
            ticket('t-2', '2026-10-18T12:00:00.000Z', { status: 'in_progress' }),
            ticket('t-1', '2026-10-17T12:00:00.000Z', { status: 'resolved', resolvedAt: '2026-10-17T12:10:00.000Z' }),
          ],
          page: { limit: 20, nextCursor: null },
        }),
    });

    const first = await screen.findByRole('link', { name: formatMoment('2026-10-18T12:00:00.000Z') });
    expect(first).toHaveAttribute('href', '/account/tickets/t-2');
    expect(screen.getByRole('link', { name: formatMoment('2026-10-17T12:00:00.000Z') })).toHaveAttribute('href', '/account/tickets/t-1');
    expect(screen.getByText(t('ticket.status.in_progress'))).toBeInTheDocument();
    expect(screen.getByText(t('ticket.status.resolved'))).toBeInTheDocument();
    expect(calls[0]?.url.pathname).toBe('/api/v1/support-tickets');
    expect(calls[0]?.url.searchParams.get('limit')).toBe('20');
    expect(calls[0]?.url.searchParams.get('status')).toBeNull();
  });

  it('обращений нет: пояснение и ссылка к заказам', async () => {
    renderScreen(<TicketListPage />, { handler: () => respond(200, { items: [], page: { limit: 20, nextCursor: null } }) });
    expect(await screen.findByText(t('tickets.empty'))).toBeInTheDocument();
    expect(screen.getByRole('link', { name: t('tickets.empty.link') })).toHaveAttribute('href', '/account/orders');
  });

  it('следующая страница подгружается по курсору из ответа', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(<TicketListPage />, {
      handler: (url) =>
        url.searchParams.get('cursor') === 'c-2'
          ? respond(200, { items: [ticket('t-1', '2026-10-17T12:00:00.000Z')], page: { limit: 20, nextCursor: null } })
          : respond(200, { items: [ticket('t-2', '2026-10-18T12:00:00.000Z')], page: { limit: 20, nextCursor: 'c-2' } }),
    });

    await user.click(await screen.findByRole('button', { name: t('catalog.more') }));

    expect(await screen.findByRole('link', { name: formatMoment('2026-10-17T12:00:00.000Z') })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: formatMoment('2026-10-18T12:00:00.000Z') })).toBeInTheDocument();
    expect(calls[1]?.url.searchParams.get('cursor')).toBe('c-2');
    expect(screen.queryByRole('button', { name: t('catalog.more') })).not.toBeInTheDocument();
  });

  it('сбой сервера: сообщение и «Повторить»', async () => {
    const user = userEvent.setup();
    let failing = true;
    renderScreen(<TicketListPage />, {
      handler: () => (failing ? respond(503, { code: 'dependency-unavailable' }) : respond(200, { items: [], page: { limit: 20, nextCursor: null } })),
    });
    expect(await screen.findByRole('alert')).toHaveTextContent(t('problem.dependency-unavailable'));
    failing = false;
    await user.click(screen.getByRole('button', { name: t('state.retry') }));
    expect(await screen.findByText(t('tickets.empty'))).toBeInTheDocument();
  });
});
