import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { RequireAccess } from '../../../app/Guards';
import { OPERATOR_ROLES } from '../../../auth/claims';
import { t } from '../../../i18n';
import { renderScreen, respond } from '../../../test/harness';
import type { Handler } from '../../../test/harness';
import { OPERATOR_ID, operatorManager, ownTicket, staffTicket } from '../../../test/staffSupport';
import { formatMoment } from '../../../ui/format';
import { SupportQueuePage } from './SupportQueuePage';
import { QUEUE_REFRESH_MS } from './useStaffSupport';

const LIST = '/api/v1/staff/support-tickets';

const SCREEN = (
  <RequireAccess kind="staff" roles={OPERATOR_ROLES} secondFactor>
    <Routes>
      <Route path="/staff/support" element={<SupportQueuePage />} />
      <Route path="/staff/support/:ticketId" element={<p>card-stub</p>} />
    </Routes>
  </RequireAccess>
);

function renderQueue(handler: Handler) {
  return renderScreen(SCREEN, { route: '/staff/support', handler, managers: { staff: operatorManager() }, tokens: { staff: 'staff-token' } });
}

const queuePage = (items: unknown[], nextCursor: string | null = null) => respond(200, { items, page: { limit: 20, nextCursor } });

function rowAt(table: HTMLElement, index: number): HTMLElement {
  const row = within(table).getAllByRole('row')[index];
  if (!row) {
    throw new Error(`В таблице нет строки ${index}`);
  }
  return row;
}

describe('очередь обращений оператора (TC-704)', () => {
  it('строки со ссылкой на карточку: заказ, источник, причина, выдача, время и статус, порядок как у сервера (старые выше)', async () => {
    const items = [
      staffTicket({ ticketId: 't-1', orderNumber: 1042, source: 'buyer', reason: 'key_not_received', deliveryStatus: 'sent', createdAt: '2026-10-17T09:00:00.000Z' }),
      staffTicket({ ticketId: 't-2', orderNumber: 1043, source: 'system', reason: 'delivery_overdue', deliveryStatus: 'failed', createdAt: '2026-10-17T10:00:00.000Z' }),
    ];
    const { calls } = renderQueue(() => queuePage(items));
    const table = await screen.findByRole('table');
    expect(within(table).getAllByRole('row')).toHaveLength(3);

    const first = rowAt(table, 1);
    expect(within(first).getByRole('link', { name: '1042' })).toHaveAttribute('href', '/staff/support/t-1');
    expect(within(first).getByText(t('supportCard.source.buyer'))).toBeInTheDocument();
    expect(within(first).getByText(t('ticket.reason.key_not_received'))).toBeInTheDocument();
    expect(within(first).getByText(t('supportCard.delivery.sent'))).toBeInTheDocument();
    expect(within(first).getByText(formatMoment('2026-10-17T09:00:00.000Z'))).toBeInTheDocument();
    expect(within(first).getByText(t('supportCard.status.created'))).toBeInTheDocument();

    const second = rowAt(table, 2);
    expect(within(second).getByRole('link', { name: '1043' })).toHaveAttribute('href', '/staff/support/t-2');
    expect(within(second).getByText(t('supportCard.source.system'))).toBeInTheDocument();
    expect(within(second).getByText(t('supportCard.delivery.failed'))).toBeInTheDocument();

    expect(calls[0]?.url.pathname).toBe(LIST);
    expect(calls[0]?.url.searchParams.get('status')).toBe('created');
    expect(calls[0]?.url.searchParams.get('limit')).toBe('20');
    expect(calls[0]?.headers.Authorization).toBe('Bearer staff-token');
  });

  it.each([
    [1, t('supportQueue.count.one', { count: 1 })],
    [3, t('supportQueue.count.few', { count: 3 })],
    [5, t('supportQueue.count.many', { count: 5 })],
  ])('счётчик очереди для %i обращений', async (count, text) => {
    const items = Array.from({ length: count }, (_, index) => staffTicket({ ticketId: `t-${index + 1}`, orderNumber: 1000 + index + 1 }));
    renderQueue(() => queuePage(items));
    expect(await screen.findByText(text)).toBeInTheDocument();
  });

  it('в очереди нет ни идентификатора покупателя, ни адресов, ни ключей', async () => {
    renderQueue(() => queuePage([staffTicket()]));
    await screen.findByRole('table');
    expect(document.body).not.toHaveTextContent('buyer-secret-id');
    expect(screen.queryAllByText(/@/)).toHaveLength(0);
  });

  it('очередь пуста: короткий текст', async () => {
    renderQueue(() => queuePage([]));
    expect(await screen.findByText(t('supportQueue.empty'))).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('фильтр «Мои в работе» просит обращения в работе у этого оператора', async () => {
    const user = userEvent.setup();
    const handler: Handler = (url) => (url.searchParams.get('operatorId') ? queuePage([ownTicket({ ticketId: 't-9', orderNumber: 1099 })]) : queuePage([staffTicket()]));
    const { calls } = renderQueue(handler);
    await screen.findByRole('link', { name: '1042' });
    await user.selectOptions(screen.getByLabelText(t('supportQueue.filter')), 'mine');
    expect(await screen.findByRole('link', { name: '1099' })).toBeInTheDocument();
    const mine = calls.find((call) => call.url.searchParams.get('operatorId') !== null);
    expect(mine?.url.searchParams.get('operatorId')).toBe(OPERATOR_ID);
    expect(mine?.url.searchParams.get('status')).toBe('in_progress');
    expect(within(rowAt(screen.getByRole('table'), 1)).getByText(t('supportCard.status.in_progress'))).toBeInTheDocument();
  });

  it('у «моих» нет обращений: свой текст', async () => {
    const user = userEvent.setup();
    renderQueue((url) => queuePage(url.searchParams.get('operatorId') ? [] : [staffTicket()]));
    await screen.findByRole('link', { name: '1042' });
    await user.selectOptions(screen.getByLabelText(t('supportQueue.filter')), 'mine');
    expect(await screen.findByText(t('supportQueue.empty.mine'))).toBeInTheDocument();
  });

  it('сбой загрузки показывает ошибку с повтором', async () => {
    const user = userEvent.setup();
    let attempts = 0;
    renderQueue(() => {
      attempts += 1;
      return attempts === 1 ? respond(500, { code: 'internal-error' }) : queuePage([staffTicket()]);
    });
    await user.click(await screen.findByRole('button', { name: t('state.retry') }));
    expect(await screen.findByRole('link', { name: '1042' })).toBeInTheDocument();
  });

  it('«Показать ещё» берёт следующую страницу по курсору', async () => {
    const user = userEvent.setup();
    const handler: Handler = (url) =>
      url.searchParams.get('cursor') === 'c2' ? queuePage([staffTicket({ ticketId: 't-21', orderNumber: 1121 })]) : queuePage([staffTicket()], 'c2');
    const { calls } = renderQueue(handler);
    expect(await screen.findByText(t('supportQueue.count.more', { count: 1 }))).toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: t('catalog.more') }));
    expect(await screen.findByRole('link', { name: '1121' })).toBeInTheDocument();
    expect(calls.some((call) => call.url.searchParams.get('cursor') === 'c2')).toBe(true);
  });

  it('очередь обновляется сама каждые 15 секунд', () => {
    expect(QUEUE_REFRESH_MS).toBe(15_000);
  });
});

describe('поиск обращений по номеру заказа (TC-707)', () => {
  it('ищет по короткому номеру в любом статусе, статус очереди не передаётся, выбор очереди недоступен', async () => {
    const user = userEvent.setup();
    const handler: Handler = (url) =>
      url.searchParams.get('orderNumber') === '1042' ? queuePage([staffTicket({ status: 'resolved', resolvedAt: '2026-10-17T13:00:00.000Z' })]) : queuePage([]);
    const { calls } = renderQueue(handler);
    await screen.findByText(t('supportQueue.empty'));
    await user.type(screen.getByLabelText(t('supportQueue.search')), ' 1042 ');
    await user.click(screen.getByRole('button', { name: t('supportQueue.search.submit') }));
    const table = await screen.findByRole('table');
    expect(within(rowAt(table, 1)).getByText(t('supportCard.status.resolved'))).toBeInTheDocument();
    expect(within(rowAt(table, 1)).getByRole('link', { name: t('supportQueue.open') })).toHaveAttribute('href', '/staff/support/t-1');
    const search = calls.find((call) => call.url.searchParams.has('orderNumber'));
    expect(search?.url.searchParams.get('orderNumber')).toBe('1042');
    expect(search?.url.searchParams.has('status')).toBe(false);
    expect(screen.getByLabelText(t('supportQueue.filter'))).toBeDisabled();
  });

  it('неизвестный номер: пустой результат без подсказок и без таблицы', async () => {
    const user = userEvent.setup();
    renderQueue((url) => queuePage(url.searchParams.has('orderNumber') ? [] : [staffTicket()]));
    await screen.findByRole('table');
    await user.type(screen.getByLabelText(t('supportQueue.search')), '9999');
    await user.click(screen.getByRole('button', { name: t('supportQueue.search.submit') }));
    const empty = await screen.findByText(t('supportQueue.empty.search'));
    expect(empty).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(empty.closest('[role="status"]')).toHaveTextContent(new RegExp(`^${t('supportQueue.empty.search')}$`));
  });

  it('не число: ошибка у поля, запрос поиска не уходит', async () => {
    const user = userEvent.setup();
    const { calls } = renderQueue(() => queuePage([staffTicket()]));
    await screen.findByRole('table');
    await user.type(screen.getByLabelText(t('supportQueue.search')), '10a2');
    await user.click(screen.getByRole('button', { name: t('supportQueue.search.submit') }));
    expect(await screen.findByText(t('supportQueue.search.error'))).toBeInTheDocument();
    expect(calls.some((call) => call.url.searchParams.has('orderNumber'))).toBe(false);
  });

  it('«Сбросить поиск» возвращает очередь и очищает поле', async () => {
    const user = userEvent.setup();
    const handler: Handler = (url) => (url.searchParams.has('orderNumber') ? queuePage([]) : queuePage([staffTicket()]));
    renderQueue(handler);
    await screen.findByRole('table');
    await user.type(screen.getByLabelText(t('supportQueue.search')), '1042{Enter}');
    await screen.findByText(t('supportQueue.empty.search'));
    await user.click(screen.getByRole('button', { name: t('supportQueue.search.reset') }));
    expect(await screen.findByRole('link', { name: '1042' })).toBeInTheDocument();
    expect(screen.getByLabelText(t('supportQueue.search'))).toHaveValue('');
    expect(screen.getByLabelText(t('supportQueue.filter'))).toBeEnabled();
  });
});

describe('«Взять в работу» в очереди (TC-705)', () => {
  const TAKE = `${LIST}/t-1/take`;

  function takeHandler(take: Handler): Handler {
    return (url, init) => (init.method === 'POST' && url.pathname === TAKE ? take(url, init) : queuePage([staffTicket()]));
  }

  it('берёт обращение с ключом идемпотентности и открывает карточку', async () => {
    const user = userEvent.setup();
    const { calls } = renderQueue(takeHandler(() => respond(200, ownTicket())));
    await user.click(await screen.findByRole('button', { name: t('supportQueue.take') }));
    expect(await screen.findByText('card-stub')).toBeInTheDocument();
    const posts = calls.filter((call) => call.init.method === 'POST');
    expect(posts).toHaveLength(1);
    expect(posts[0]?.url.pathname).toBe(TAKE);
    expect(posts[0]?.headers['Idempotency-Key']).toBe('key-1');
    expect(posts[0]?.headers.Authorization).toBe('Bearer staff-token');
  });

  it('обращение уже взял другой оператор (409): «Обращение уже взято», очередь обновляется, перехода нет', async () => {
    const user = userEvent.setup();
    const { calls } = renderQueue(takeHandler(() => respond(409, { code: 'state-conflict' })));
    await user.click(await screen.findByRole('button', { name: t('supportQueue.take') }));
    expect(await screen.findByText(t('supportQueue.alreadyTaken'))).toBeInTheDocument();
    await waitFor(() => expect(calls.filter((call) => call.init.method !== 'POST').length).toBeGreaterThanOrEqual(2));
    expect(screen.queryByText('card-stub')).not.toBeInTheDocument();
  });

  it('двойное нажатие даёт один запрос', async () => {
    const user = userEvent.setup();
    const { calls } = renderQueue(
      takeHandler(async () => {
        await new Promise((resolve) => setTimeout(resolve, 20));
        return respond(200, ownTicket());
      }),
    );
    await user.dblClick(await screen.findByRole('button', { name: t('supportQueue.take') }));
    expect(await screen.findByText('card-stub')).toBeInTheDocument();
    expect(calls.filter((call) => call.init.method === 'POST')).toHaveLength(1);
  });

  it('обрыв связи и повтор уходят с тем же ключом идемпотентности', async () => {
    const user = userEvent.setup();
    let attempts = 0;
    const { calls } = renderQueue(
      takeHandler(() => {
        attempts += 1;
        return attempts === 1 ? { fail: new TypeError('offline') } : respond(200, ownTicket());
      }),
    );
    await user.click(await screen.findByRole('button', { name: t('supportQueue.take') }));
    await screen.findByRole('alert');
    await user.click(screen.getByRole('button', { name: t('supportQueue.take') }));
    expect(await screen.findByText('card-stub')).toBeInTheDocument();
    const keys = calls.filter((call) => call.init.method === 'POST').map((call) => call.headers['Idempotency-Key']);
    expect(keys).toEqual(['key-1', 'key-1']);
  });
});
