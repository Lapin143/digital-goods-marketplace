import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { t } from '../../../i18n';
import { renderScreen, respond } from '../../../test/harness';
import type { Handler } from '../../../test/harness';
import { ApplicationQueuePage } from './ApplicationQueuePage';
import { ProductQueuePage } from './ProductQueuePage';

function application(index: number, overrides: Record<string, unknown> = {}) {
  return {
    sellerId: `s-${index}`,
    name: `Продавец ${index}`,
    sellerType: 'self_employed',
    documentsCount: 2,
    submittedAt: '2026-10-14T09:00:00Z',
    reviewDeadlineAt: '2026-10-17T09:00:00Z',
    overdue: false,
    ...overrides,
  };
}

function queuedProduct(index: number, overrides: Record<string, unknown> = {}) {
  return {
    productId: `p-${index}`,
    sellerId: 's-1',
    title: `Товар ${index}`,
    productType: 'gift_card',
    price: { amount: 50000, currency: 'RUB' },
    submittedAt: '2026-10-16T09:00:00Z',
    reviewDeadlineAt: '2026-10-19T09:00:00Z',
    overdue: false,
    ...overrides,
  };
}

const page = (items: unknown[], nextCursor: string | null = null) => respond(200, { items, page: { limit: 20, nextCursor } });

describe('очередь заявок (TC-222)', () => {
  it('строки со ссылкой на карточку, просроченная помечена', async () => {
    const { calls } = renderScreen(<ApplicationQueuePage />, { handler: () => page([application(1), application(2, { overdue: true })]) });
    const table = await screen.findByRole('table');
    expect(within(table).getByRole('link', { name: 'Продавец 1' })).toHaveAttribute('href', '/staff/applications/s-1');
    expect(within(table).getAllByText(t('moderation.overdue'))).toHaveLength(1);
    expect(within(table).getAllByText(t('sellerType.self_employed'))).toHaveLength(2);
    expect(calls[0]?.url.pathname).toBe('/api/v1/staff/seller-applications');
    expect(calls[0]?.url.searchParams.get('limit')).toBe('20');
  });

  it('«Показать ещё» берёт следующую страницу по курсору', async () => {
    const user = userEvent.setup();
    const handler: Handler = (url) => (url.searchParams.get('cursor') === 'c2' ? page([application(21)]) : page([application(1)], 'c2'));
    const { calls } = renderScreen(<ApplicationQueuePage />, { handler });
    await user.click(await screen.findByRole('button', { name: t('catalog.more') }));
    expect(await screen.findByRole('link', { name: 'Продавец 21' })).toBeInTheDocument();
    expect(calls[1]?.url.searchParams.get('cursor')).toBe('c2');
  });

  it('очередь пуста: отдельный текст', async () => {
    renderScreen(<ApplicationQueuePage />, { handler: () => page([]) });
    expect(await screen.findByText(t('moderation.applications.empty'))).toBeInTheDocument();
  });

  it('нет права (403) и повтор после ошибки', async () => {
    const user = userEvent.setup();
    let attempt = 0;
    renderScreen(<ApplicationQueuePage />, {
      handler: () => {
        attempt += 1;
        return attempt === 1 ? respond(403, { code: 'forbidden' }) : page([application(1)]);
      },
    });
    expect(await screen.findByText(t('problem.forbidden'))).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: t('state.retry') }));
    expect(await screen.findByRole('table')).toBeInTheDocument();
  });
});

describe('очередь товаров (TC-328)', () => {
  it('строки со ссылкой на карточку, типом, ценой и пометкой просрочки', async () => {
    const { calls } = renderScreen(<ProductQueuePage />, { handler: () => page([queuedProduct(1), queuedProduct(2, { overdue: true })]) });
    const table = await screen.findByRole('table');
    expect(within(table).getByRole('link', { name: 'Товар 1' })).toHaveAttribute('href', '/staff/products/p-1');
    expect(within(table).getAllByText(/500\s₽/)).toHaveLength(2);
    expect(within(table).getAllByText(t('moderation.overdue'))).toHaveLength(1);
    expect(calls[0]?.url.pathname).toBe('/api/v1/staff/products');
  });

  it('очередь пуста: отдельный текст', async () => {
    renderScreen(<ProductQueuePage />, { handler: () => page([]) });
    expect(await screen.findByText(t('moderation.products.empty'))).toBeInTheDocument();
  });
});
