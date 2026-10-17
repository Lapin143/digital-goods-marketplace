import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { plural, t } from '../../i18n';
import { renderScreen, respond } from '../../test/harness';
import type { Handler } from '../../test/harness';
import { SellerProductsPage } from './SellerProductsPage';

function item(index: number, overrides: Record<string, unknown> = {}) {
  return {
    productId: `p-${index}`,
    sellerId: 's-1',
    title: `Товар ${index}`,
    description: 'Описание',
    productType: 'game_key',
    platform: 'Steam',
    activationRegions: ['RU'],
    price: { amount: 100000 + index, currency: 'RUB' },
    issuanceMethod: 'key_pool',
    status: 'published',
    rejectionReason: null,
    blockReason: null,
    submittedAt: null,
    reviewDeadlineAt: null,
    version: 1,
    createdAt: '2026-10-17T09:00:00Z',
    updatedAt: '2026-10-17T09:00:00Z',
    ...overrides,
  };
}

const page = (items: unknown[], nextCursor: string | null = null) => respond(200, { items, page: { limit: 20, nextCursor } });

describe('товары продавца: список', () => {
  it('строки таблицы со ссылкой на правку, типом, ценой и статусом', async () => {
    const { calls } = renderScreen(<SellerProductsPage />, { handler: () => page([item(1), item(2, { status: 'on_moderation' })]) });
    const table = await screen.findByRole('table');
    expect(within(table).getByRole('link', { name: 'Товар 1' })).toHaveAttribute('href', '/seller/products/p-1');
    expect(within(table).getByText(/1\s000,01\s₽/)).toBeInTheDocument();
    expect(within(table).getByText(t('productStatus.on_moderation'))).toBeInTheDocument();
    expect(screen.getByText(plural(2, { one: 'seller.products.count.one', few: 'seller.products.count.few', many: 'seller.products.count.many' }))).toBeInTheDocument();
    expect(calls[0]?.url.searchParams.get('limit')).toBe('20');
    expect(calls[0]?.url.searchParams.has('status')).toBe(false);
    expect(screen.getByRole('link', { name: t('seller.products.new') })).toHaveAttribute('href', '/seller/products/new');
  });

  it('фильтр по статусу уходит в запрос', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(<SellerProductsPage />, { handler: () => page([item(1)]) });
    await screen.findByRole('table');
    await user.selectOptions(screen.getByLabelText(t('seller.products.filter')), 'rejected');
    await screen.findByRole('table');
    expect(calls.some((call) => call.url.searchParams.get('status') === 'rejected')).toBe(true);
  });

  it('«Показать ещё» берёт следующую страницу по курсору', async () => {
    const user = userEvent.setup();
    const handler: Handler = (url) => (url.searchParams.get('cursor') === 'c2' ? page([item(21)]) : page([item(1)], 'c2'));
    const { calls } = renderScreen(<SellerProductsPage />, { handler });
    await user.click(await screen.findByRole('button', { name: t('catalog.more') }));
    expect(await screen.findByRole('link', { name: 'Товар 21' })).toBeInTheDocument();
    expect(calls[1]?.url.searchParams.get('cursor')).toBe('c2');
    expect(screen.queryByRole('button', { name: t('catalog.more') })).not.toBeInTheDocument();
  });

  it('товаров нет: приглашение добавить первый', async () => {
    renderScreen(<SellerProductsPage />, { handler: () => page([]) });
    expect(await screen.findByText(t('seller.products.empty'))).toBeInTheDocument();
  });

  it('по фильтру ничего нет: отдельный текст', async () => {
    const user = userEvent.setup();
    renderScreen(<SellerProductsPage />, { handler: (url) => (url.searchParams.has('status') ? page([]) : page([item(1)])) });
    await screen.findByRole('table');
    await user.selectOptions(screen.getByLabelText(t('seller.products.filter')), 'blocked');
    expect(await screen.findByText(t('seller.products.emptyFiltered'))).toBeInTheDocument();
  });

  it('ошибка загрузки: сообщение и повтор', async () => {
    const user = userEvent.setup();
    let attempt = 0;
    renderScreen(<SellerProductsPage />, {
      handler: () => {
        attempt += 1;
        return attempt === 1 ? respond(403, { code: 'second-factor-required' }) : page([item(1)]);
      },
    });
    expect(await screen.findByText(t('problem.second-factor-required'))).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: t('state.retry') }));
    expect(await screen.findByRole('table')).toBeInTheDocument();
  });
});
