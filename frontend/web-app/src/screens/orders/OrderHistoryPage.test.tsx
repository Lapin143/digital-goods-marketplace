import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { t } from '../../i18n';
import { renderScreen, respond } from '../../test/harness';
import type { Handler } from '../../test/harness';
import { orderFixture } from '../../test/orders';
import { OrderHistoryPage } from './OrderHistoryPage';

function page(handler: Handler) {
  return renderScreen(<OrderHistoryPage />, { route: '/account/orders', handler });
}

const list = (items: Array<Record<string, unknown>>, nextCursor: string | null = null) => respond(200, { items, page: { limit: 20, nextCursor } });

describe('история заказов (US-5.9)', () => {
  it('список с номером, товаром, количеством, суммой и статусом; номер ведёт на заказ', async () => {
    const { calls } = page(() =>
      list([
        orderFixture({ orderId: 'o-2', orderNumber: 1043, status: 'issued', issued: true, paidMinutesAgo: 5, reserveInMinutes: null }),
        orderFixture({ orderId: 'o-1', orderNumber: 1042, status: 'cancelled', cancelReason: 'out_of_stock', reserveInMinutes: null }),
      ]),
    );
    const table = await screen.findByRole('region', { name: t('orders.caption') });
    const rows = within(table).getAllByRole('row');
    expect(rows).toHaveLength(3);
    expect(within(rows[1] as HTMLElement).getByRole('link', { name: '1043' })).toHaveAttribute('href', '/account/orders/o-2');
    expect(within(rows[1] as HTMLElement).getByText(t('order.status.issued'))).toBeInTheDocument();
    expect(within(rows[2] as HTMLElement).getByText(t('order.status.cancelled'))).toBeInTheDocument();
    expect(within(rows[1] as HTMLElement).getByText(/Лунная тропа: ключ Steam, 2 шт\./)).toBeInTheDocument();
    expect(within(rows[1] as HTMLElement).getByText(/2\s998\s₽/)).toBeInTheDocument();
    expect(calls[0]?.url.pathname).toBe('/api/v1/orders');
    expect(calls[0]?.url.searchParams.get('limit')).toBe('20');
    expect(calls[0]?.url.searchParams.has('cursor')).toBe(false);
  });

  it('пустая история: пояснение и ссылка на каталог', async () => {
    page(() => list([]));
    expect(await screen.findByText(t('orders.empty'))).toBeInTheDocument();
    expect(screen.getByRole('link', { name: t('orders.empty.link') })).toHaveAttribute('href', '/products');
    expect(screen.queryByRole('region', { name: t('orders.caption') })).not.toBeInTheDocument();
  });

  it('«Показать ещё» подгружает следующую страницу по курсору и пропадает на последней', async () => {
    const user = userEvent.setup();
    const handler: Handler = (url) =>
      url.searchParams.get('cursor') === 'c2'
        ? list([orderFixture({ orderId: 'o-1', orderNumber: 1042, status: 'refunded', paidMinutesAgo: 60, reserveInMinutes: null })])
        : list([orderFixture({ orderId: 'o-2', orderNumber: 1043, status: 'paid', paidMinutesAgo: 1, reserveInMinutes: null })], 'c2');
    const { calls } = page(handler);
    await screen.findByRole('link', { name: '1043' });
    await user.click(screen.getByRole('button', { name: t('catalog.more') }));
    expect(await screen.findByRole('link', { name: '1042' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '1043' })).toBeInTheDocument();
    expect(calls[1]?.url.searchParams.get('cursor')).toBe('c2');
    expect(screen.queryByRole('button', { name: t('catalog.more') })).not.toBeInTheDocument();
  });

  it('сбой загрузки: сообщение и «Повторить»', async () => {
    const user = userEvent.setup();
    let failing = true;
    page(() => (failing ? respond(503, { code: 'dependency-unavailable' }) : list([orderFixture({ status: 'paid', paidMinutesAgo: 1, reserveInMinutes: null })])));
    expect(await screen.findByRole('alert')).toHaveTextContent(t('problem.dependency-unavailable'));
    failing = false;
    await user.click(screen.getByRole('button', { name: t('state.retry') }));
    expect(await screen.findByRole('link', { name: '1042' })).toBeInTheDocument();
  });

  it('значений ключей в списке нет, даже если сервер прислал их по ошибке', async () => {
    page(() => list([orderFixture({ status: 'issued', issued: true, paidMinutesAgo: 2, reserveInMinutes: null, keys: ['ABCDE-FGHIJ-KLMNO'] })]));
    await screen.findByRole('link', { name: '1042' });
    expect(document.body.textContent ?? '').not.toContain('ABCDE');
  });
});
