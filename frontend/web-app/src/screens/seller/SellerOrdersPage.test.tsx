import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { t } from '../../i18n';
import { renderScreen, respond } from '../../test/harness';
import type { Handler, Rendered } from '../../test/harness';
import { formatMoment } from '../../ui/format';
import { SellerOrdersPage } from './SellerOrdersPage';
import { dayStartIso, nextDayStartIso } from './sellerOrders';

const SCREEN = (
  <Routes>
    <Route path="/seller/orders" element={<SellerOrdersPage />} />
  </Routes>
);
const ROUTE = '/seller/orders';
const LIST = 'GET /api/v1/seller/orders';

function order(n: number, overrides: Record<string, unknown> = {}) {
  return {
    orderId: `o-${n}`,
    orderNumber: 1000 + n,
    productId: 'p-1',
    productTitle: `Игра ${n}`,
    quantity: 1,
    unitPrice: { amount: 149900, currency: 'RUB' },
    total: { amount: 149900, currency: 'RUB' },
    commission: { amount: 14990, currency: 'RUB' },
    status: 'issued',
    cancelReason: null,
    issuanceMethod: 'seller_api',
    createdAt: '2026-10-17T09:00:00Z',
    paidAt: '2026-10-17T09:01:00Z',
    issuedAt: '2026-10-17T09:05:00Z',
    refundedAt: null,
    ...overrides,
  };
}

type Order = ReturnType<typeof order>;

/** Сервер заказов: фильтрует по `status` и отдаёт страницами по `cursor` (если заданы страницы). */
function server(orders: Order[], pages?: Record<string, { items: Order[]; next: string | null }>): Handler {
  return (url, init) => {
    if (`${init.method ?? 'GET'} ${url.pathname}` !== LIST) {
      return respond(404, { code: 'not-found' });
    }
    if (pages) {
      const page = pages[url.searchParams.get('cursor') ?? ''] ?? { items: [], next: null };
      return respond(200, { items: page.items, page: { limit: 20, nextCursor: page.next } });
    }
    const status = url.searchParams.get('status');
    const items = status ? orders.filter((item) => status.split(',').includes(String(item.status))) : orders;
    return respond(200, { items, page: { limit: 20, nextCursor: null } });
  };
}

function lists(calls: Rendered['calls']) {
  return calls.filter((call) => call.url.pathname === '/api/v1/seller/orders');
}

describe('заказы продавца: таблица', () => {
  it('номер, дата, товар, количество, сумма, комиссия, статус и способ выдачи', async () => {
    const orders = [order(1, { quantity: 2, total: { amount: 299800, currency: 'RUB' }, issuanceMethod: 'seller_api' }), order(2, { issuanceMethod: 'key_pool', status: 'paid', issuedAt: null })];
    renderScreen(SCREEN, { route: ROUTE, handler: server(orders) });
    const table = await screen.findByRole('table', { name: t('sellerOrders.caption') });
    const first = within(table).getByRole('row', { name: new RegExp('1001') });
    expect(within(first).getByText('Игра 1')).toBeInTheDocument();
    expect(within(first).getByText('2')).toBeInTheDocument();
    expect(within(first).getByText(/2\s998\s₽/)).toBeInTheDocument();
    expect(within(first).getByText(/149,90\s₽/)).toBeInTheDocument();
    expect(within(first).getByText(t('order.status.issued'))).toBeInTheDocument();
    expect(within(first).getByText(t('issuance.seller_api'))).toBeInTheDocument();
    expect(within(first).getByText(formatMoment('2026-10-17T09:00:00Z'))).toBeInTheDocument();
    expect(within(first).getByText(t('sellerOrders.note.issued', { time: formatMoment('2026-10-17T09:05:00Z') }))).toBeInTheDocument();

    const second = within(table).getByRole('row', { name: new RegExp('1002') });
    expect(within(second).getByText(t('issuance.key_pool'))).toBeInTheDocument();
    expect(within(second).getByText(t('order.status.paid'))).toBeInTheDocument();
    expect(screen.getByText(t('sellerOrders.count.few', { count: 2 }))).toBeInTheDocument();
  });

  it('отмена по молчанию продавца названа словами: «продавец не ответил за 10 минут»', async () => {
    const orders = [order(3, { status: 'cancelled', cancelReason: 'seller_api_timeout', issuedAt: null })];
    renderScreen(SCREEN, { route: ROUTE, handler: server(orders) });
    const table = await screen.findByRole('table', { name: t('sellerOrders.caption') });
    expect(within(table).getByText(t('order.status.cancelled'))).toBeInTheDocument();
    expect(within(table).getByText(t('sellerOrders.note.cancelled', { reason: 'продавец не ответил за 10 минут' }))).toBeInTheDocument();
  });

  it('неизвестная причина отмены и возврат: общий текст и время возврата', async () => {
    const orders = [order(4, { status: 'cancelled', cancelReason: 'новая_причина' }), order(5, { status: 'refunded', refundedAt: '2026-10-18T10:00:00Z' })];
    renderScreen(SCREEN, { route: ROUTE, handler: server(orders) });
    const table = await screen.findByRole('table', { name: t('sellerOrders.caption') });
    expect(within(table).getByText(t('sellerOrders.note.cancelled', { reason: t('sellerOrders.cancel.unknown') }))).toBeInTheDocument();
    expect(within(table).getByText(t('sellerOrders.note.refunded', { time: formatMoment('2026-10-18T10:00:00Z') }))).toBeInTheDocument();
  });

  it('данных покупателя на экране нет, даже если лишние поля пришли в ответе', async () => {
    const orders = [order(6, { buyerEmail: 'buyer@example.com', buyerId: 'u-777', deliveryAddress: 'secret@example.com' })];
    renderScreen(SCREEN, { route: ROUTE, handler: server(orders) });
    await screen.findByRole('table', { name: t('sellerOrders.caption') });
    expect(document.body.textContent).not.toContain('buyer@example.com');
    expect(document.body.textContent).not.toContain('u-777');
    expect(document.body.textContent).not.toContain('secret@example.com');
    const headers = screen.getAllByRole('columnheader').map((header) => header.textContent);
    expect(headers).toEqual([
      t('sellerOrders.col.number'),
      t('sellerOrders.col.created'),
      t('sellerOrders.col.product'),
      t('sellerOrders.col.quantity'),
      t('sellerOrders.col.total'),
      t('sellerOrders.col.commission'),
      t('sellerOrders.col.status'),
      t('sellerOrders.col.issuance'),
    ]);
  });
});

describe('заказы продавца: состояния списка', () => {
  it('заказов нет: пустое состояние; с фильтром свой текст', async () => {
    const user = userEvent.setup();
    renderScreen(SCREEN, { route: ROUTE, handler: server([]) });
    expect(await screen.findByText(t('sellerOrders.empty'))).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText(t('sellerOrders.filter.status')), 'paid');
    expect(await screen.findByText(t('sellerOrders.emptyFiltered'))).toBeInTheDocument();
  });

  it('ошибка списка: сообщение и повтор', async () => {
    const user = userEvent.setup();
    let fail = true;
    const { calls } = renderScreen(SCREEN, {
      route: ROUTE,
      handler: (url, init) => (fail ? respond(500, { code: 'internal-error' }) : server([order(1)])(url, init)),
    });
    expect(await screen.findByText(t('problem.internal-error'))).toBeInTheDocument();
    fail = false;
    await user.click(screen.getByRole('button', { name: t('state.retry') }));
    expect(await screen.findByRole('table', { name: t('sellerOrders.caption') })).toBeInTheDocument();
    expect(lists(calls)).toHaveLength(2);
  });

  it('страницы по курсору: «Показать ещё» добавляет строки, после последней страницы кнопки нет', async () => {
    const user = userEvent.setup();
    const pages = { '': { items: [order(1), order(2)], next: 'c1' }, c1: { items: [order(3)], next: null } };
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler: server([], pages) });
    const more = await screen.findByRole('button', { name: t('catalog.more') });
    expect(lists(calls)[0]?.url.searchParams.get('limit')).toBe('20');
    await user.click(more);
    await waitFor(() => expect(screen.getByText('Игра 3')).toBeInTheDocument());
    expect(screen.getByText('Игра 1')).toBeInTheDocument();
    expect(lists(calls)[1]?.url.searchParams.get('cursor')).toBe('c1');
    expect(screen.queryByRole('button', { name: t('catalog.more') })).not.toBeInTheDocument();
  });
});

describe('заказы продавца: фильтры', () => {
  it('статус уходит параметром, список сужается', async () => {
    const user = userEvent.setup();
    const orders = [order(1, { status: 'issued' }), order(2, { status: 'cancelled', cancelReason: 'seller_api_timeout' })];
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler: server(orders) });
    await screen.findByText('Игра 1');
    expect(lists(calls)[0]?.url.searchParams.get('status')).toBeNull();

    await user.selectOptions(screen.getByLabelText(t('sellerOrders.filter.status')), 'cancelled');
    await waitFor(() => expect(screen.queryByText('Игра 1')).not.toBeInTheDocument());
    expect(screen.getByText('Игра 2')).toBeInTheDocument();
    expect(lists(calls).some((call) => call.url.searchParams.get('status') === 'cancelled')).toBe(true);
  });

  it('даты: начало выбранного дня и начало следующего за последним днём (верхняя граница не включается)', async () => {
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler: server([order(1)]) });
    await screen.findByText('Игра 1');
    fireEvent.change(screen.getByLabelText(t('sellerOrders.filter.from')), { target: { value: '2026-10-01' } });
    fireEvent.change(screen.getByLabelText(t('sellerOrders.filter.to')), { target: { value: '2026-10-31' } });

    await waitFor(() =>
      expect(lists(calls).some((call) => call.url.searchParams.get('createdFrom') !== null && call.url.searchParams.get('createdTo') !== null)).toBe(true),
    );
    const both = lists(calls).find((call) => call.url.searchParams.get('createdFrom') !== null && call.url.searchParams.get('createdTo') !== null);
    expect(both?.url.searchParams.get('createdFrom')).toBe(dayStartIso('2026-10-01'));
    expect(both?.url.searchParams.get('createdTo')).toBe(nextDayStartIso('2026-10-31'));
  });

  it('начальная дата позже конечной: ошибка у поля, запрос с такими датами не уходит', async () => {
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler: server([order(1)]) });
    await screen.findByText('Игра 1');
    fireEvent.change(screen.getByLabelText(t('sellerOrders.filter.from')), { target: { value: '2026-10-31' } });
    await waitFor(() => expect(lists(calls)).toHaveLength(2));
    fireEvent.change(screen.getByLabelText(t('sellerOrders.filter.to')), { target: { value: '2026-10-01' } });

    expect(await screen.findByText(t('sellerOrders.error.range'))).toBeInTheDocument();
    expect(lists(calls)).toHaveLength(2);
    expect(lists(calls).some((call) => call.url.searchParams.get('createdTo') !== null)).toBe(false);
  });
});
