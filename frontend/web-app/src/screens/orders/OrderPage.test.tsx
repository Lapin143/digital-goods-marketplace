import { act, cleanup, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { redirectTo } from '../../api/redirect';
import { t } from '../../i18n';
import { renderScreen, respond } from '../../test/harness';
import type { Handler, NetworkFailure } from '../../test/harness';
import { orderFixture } from '../../test/orders';
import type { OrderFixtureOptions } from '../../test/orders';
import { OrderPage } from './OrderPage';

vi.mock('../../api/redirect', () => ({ redirectTo: vi.fn() }));

const ADDRESS = 'buyer@mail.example';
const PAY_URL = 'https://pay.gateway.example/session/abc123';
const KEY_LIKE = /[A-Z0-9]{4,6}(-[A-Z0-9]{4,6}){2,}/;

function page(handler: Handler) {
  return renderScreen(
    <Routes>
      <Route path="/account/orders/:orderId" element={<OrderPage />} />
    </Routes>,
    { route: '/account/orders/o-1', handler },
  );
}

const orderPath = (url: URL) => url.pathname === '/api/v1/orders/o-1';
const orderCalls = (calls: ReturnType<typeof renderScreen>['calls']) => calls.filter((call) => orderPath(call.url));

/** Сервер, отдающий заказ с заданными свойствами; платёжную сессию отдаёт `session`. */
function server(options: OrderFixtureOptions, session: Handler = () => respond(200, { orderId: 'o-1', url: PAY_URL, expiresAt: '2026-10-25T12:12:00.000Z' })): Handler {
  return (url, init) => {
    if (orderPath(url)) {
      return respond(200, orderFixture(options));
    }
    if (url.pathname === '/api/v1/orders/o-1/payment-session') {
      return session(url, init);
    }
    return respond(404, { code: 'not-found' });
  };
}

beforeEach(() => {
  vi.mocked(redirectTo).mockClear();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('экран заказа: статусы по SM-01 (US-5.9)', () => {
  it('ждёт оплаты: номер, товар, сумма, остаток резерва и кнопка «Оплатить»', async () => {
    page(server({ status: 'awaiting_payment', reserveInMinutes: 12 }));
    expect(await screen.findByRole('heading', { level: 1, name: t('order.title', { number: 1042 }) })).toBeInTheDocument();
    expect(screen.getByText(/Ключи зарезервированы за вами ещё на 1[12]:\d\d\./)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Лунная тропа: ключ Steam' })).toHaveAttribute('href', '/products/p-1');
    expect(screen.getByText(/2\s998\s₽/)).toBeInTheDocument();
    expect(screen.getByText(t('order.status.awaiting_payment'))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: t('order.pay') })).toBeInTheDocument();
    expect(screen.getByText(t('order.autoRefresh'))).toBeInTheDocument();
  });

  it('резерв закончился, а статус ещё прежний: говорит про проверку оплаты, кнопки оплаты нет', async () => {
    page(server({ status: 'awaiting_payment', reserveInMinutes: -1 }));
    expect(await screen.findByText(t('order.state.awaiting_payment.expired'))).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('order.pay') })).not.toBeInTheDocument();
  });

  it('оплачен: ключ придёт на адрес из заказа, а через полчаса говорит, что ключ задерживается', async () => {
    page(server({ status: 'paid', paidMinutesAgo: 2, reserveInMinutes: null }));
    expect(await screen.findByText(t('order.state.paid', { address: ADDRESS }))).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('order.pay') })).not.toBeInTheDocument();
  });

  it('оплачен давно, а письма нет: «ключ задерживается, мы уже занимаемся этим»', async () => {
    page(server({ status: 'paid', paidMinutesAgo: 31, reserveInMinutes: null }));
    expect(await screen.findByText(t('order.state.paid.late', { address: ADDRESS }))).toBeInTheDocument();
  });

  it('выдан: письмо отправлено на адрес, опроса и подсказки про обновление нет', async () => {
    page(server({ status: 'issued', paidMinutesAgo: 3, issued: true, reserveInMinutes: null }));
    expect(await screen.findByText(t('order.state.issued', { address: ADDRESS }))).toBeInTheDocument();
    expect(screen.queryByText(t('order.autoRefresh'))).not.toBeInTheDocument();
    expect(screen.getByText(t('order.issuedAt'))).toBeInTheDocument();
  });

  it('отменён: причина словами и ссылка «Оформить заново» на товар', async () => {
    page(server({ status: 'cancelled', cancelReason: 'reservation_expired', reserveInMinutes: null }));
    expect(await screen.findByText(t('order.state.cancelled', { reason: t('order.cancel.reservation_expired') }))).toBeInTheDocument();
    expect(screen.getByRole('link', { name: t('order.buyAgain') })).toHaveAttribute('href', '/products/p-1');
    expect(screen.queryByRole('button', { name: t('order.pay') })).not.toBeInTheDocument();
  });

  it('возвращён: деньги возвращены', async () => {
    page(server({ status: 'refunded', paidMinutesAgo: 40, reserveInMinutes: null }));
    expect(await screen.findByText(t('order.state.refunded'))).toBeInTheDocument();
  });

  it('статус, которого интерфейс не знает, показывается как «уточняется»', async () => {
    page(server({ status: 'arbitration', reserveInMinutes: null }));
    expect(await screen.findByText(t('order.state.unknown'))).toBeInTheDocument();
  });

  it('значений ключей на экране нет ни в одном статусе, даже если сервер прислал их по ошибке (FT-5.4, INV-20)', async () => {
    const leaked = { keys: ['ABCDE-FGHIJ-KLMNO'], key: 'ABCDE-FGHIJ-KLMNO', value: 'ABCDE-FGHIJ-KLMNO' };
    for (const status of ['created', 'awaiting_payment', 'paid', 'issued', 'cancelled', 'refunded']) {
      page(server({ status, issued: status === 'issued', paidMinutesAgo: status === 'awaiting_payment' ? null : 3, ...leaked }));
      await screen.findByRole('heading', { level: 1, name: t('order.title', { number: 1042 }) });
      expect(document.body.textContent ?? '', status).not.toMatch(KEY_LIKE);
      expect(document.body.textContent ?? '', status).not.toContain('ABCDE');
      cleanup();
    }
  });
});

describe('экран заказа: оплата из заказа (US-5.3)', () => {
  it('«Оплатить» берёт платёжную сессию заказа и уходит на страницу шлюза без создания второго платежа', async () => {
    const user = userEvent.setup();
    const { calls } = page(server({ status: 'awaiting_payment' }));
    await user.click(await screen.findByRole('button', { name: t('order.pay') }));
    await vi.waitFor(() => expect(redirectTo).toHaveBeenCalledExactlyOnceWith(PAY_URL));
    const session = calls.filter((call) => call.url.pathname === '/api/v1/orders/o-1/payment-session');
    expect(session).toHaveLength(1);
    expect(session[0]?.init.method).toBe('GET');
  });

  it('сессия оплаты закончилась (409): понятный текст и свежий статус', async () => {
    const user = userEvent.setup();
    const { calls } = page(server({ status: 'awaiting_payment' }, () => respond(409, { code: 'state-conflict' })));
    await user.click(await screen.findByRole('button', { name: t('order.pay') }));
    expect(await screen.findByText(t('order.pay.expired'))).toBeInTheDocument();
    expect(redirectTo).not.toHaveBeenCalled();
    await vi.waitFor(() => expect(orderCalls(calls).length).toBeGreaterThanOrEqual(2));
  });

  it('адрес оплаты с чужой схемой не открывается', async () => {
    const user = userEvent.setup();
    page(server({ status: 'awaiting_payment' }, () => respond(200, { orderId: 'o-1', url: 'javascript:alert(1)', expiresAt: '2026-10-25T12:12:00.000Z' })));
    await user.click(await screen.findByRole('button', { name: t('order.pay') }));
    expect(await screen.findByText(t('purchase.created.badUrl'))).toBeInTheDocument();
    expect(redirectTo).not.toHaveBeenCalled();
  });

  it('другая ошибка шлюза платформы показывается словами по коду', async () => {
    const user = userEvent.setup();
    page(server({ status: 'awaiting_payment' }, () => respond(429, { code: 'rate-limited' }, { 'Retry-After': '7' })));
    await user.click(await screen.findByRole('button', { name: t('order.pay') }));
    expect(await screen.findByText(`${t('problem.rate-limited')} ${t('problem.retryAfter', { seconds: 7 })}`)).toBeInTheDocument();
  });
});

describe('экран заказа: ошибки (US-5.9, сценарий 2)', () => {
  it('чужой или несуществующий заказ: 404 без подробностей, ссылка к своим заказам', async () => {
    page(() => respond(404, { code: 'not-found' }));
    expect(await screen.findByRole('heading', { name: t('order.notFound.title') })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: t('order.back') })).toHaveAttribute('href', '/account/orders');
  });

  it('сбой сервера: сообщение и «Повторить», после повтора заказ показан', async () => {
    const user = userEvent.setup();
    let failing = true;
    page(() => (failing ? respond(503, { code: 'dependency-unavailable' }) : respond(200, orderFixture({ status: 'issued', issued: true, paidMinutesAgo: 1, reserveInMinutes: null }))));
    expect(await screen.findByRole('alert')).toHaveTextContent(t('problem.dependency-unavailable'));
    failing = false;
    await user.click(screen.getByRole('button', { name: t('state.retry') }));
    expect(await screen.findByRole('heading', { level: 1, name: t('order.title', { number: 1042 }) })).toBeInTheDocument();
  });
});

describe('экран заказа: опрос статуса (ADR-015: возврат со шлюза ничего не решает)', () => {
  it('статус меняется сам: ждёт оплаты, оплачен, выдан; на конечном состоянии опрос останавливается', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const sequence = [
      orderFixture({ status: 'awaiting_payment' }),
      orderFixture({ status: 'paid', paidMinutesAgo: 0, reserveInMinutes: null }),
      orderFixture({ status: 'issued', paidMinutesAgo: 0, issued: true, reserveInMinutes: null }),
    ];
    let served = 0;
    const { calls } = page(() => {
      const body = sequence[Math.min(served, sequence.length - 1)];
      served += 1;
      return respond(200, body);
    });

    expect(await screen.findByRole('button', { name: t('order.pay') })).toBeInTheDocument();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2100);
    });
    expect(await screen.findByText(t('order.state.paid', { address: ADDRESS }))).toBeInTheDocument();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3500);
    });
    expect(await screen.findByText(t('order.state.issued', { address: ADDRESS }))).toBeInTheDocument();
    expect(orderCalls(calls)).toHaveLength(3);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(120_000);
    });
    expect(orderCalls(calls)).toHaveLength(3);
  });

  it('связь пропала во время опроса: последний статус остаётся, сказано, что связь потеряна', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let served = 0;
    page((): Response | NetworkFailure => {
      served += 1;
      return served === 1 ? respond(200, orderFixture({ status: 'awaiting_payment' })) : { fail: new TypeError('сеть') };
    });
    expect(await screen.findByRole('button', { name: t('order.pay') })).toBeInTheDocument();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2100);
    });
    expect(await screen.findByText(t('order.connectionLost'))).toBeInTheDocument();
    expect(screen.getByText(t('order.status.awaiting_payment'))).toBeInTheDocument();
  });
});
