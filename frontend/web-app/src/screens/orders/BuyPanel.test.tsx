import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { redirectTo } from '../../api/redirect';
import { t } from '../../i18n';
import { claimsFor, fakeManager, fakeUser } from '../../test/fakeAuth';
import { renderScreen, respond } from '../../test/harness';
import type { Handler, RenderOptions } from '../../test/harness';
import { BuyPanel } from './BuyPanel';

vi.mock('../../api/redirect', () => ({ redirectTo: vi.fn() }));

const PAY_URL = 'https://pay.gateway.example/session/abc123';
const buyer = (extra: Record<string, unknown> = { phone_verified: true }) => fakeUser(claimsFor(['buyer'], ['pwd'], extra));

function createdOrder(overrides: Record<string, unknown> = {}) {
  return {
    orderId: 'o-1',
    orderNumber: 1042,
    productId: 'p-1',
    productTitle: 'Лунная тропа: ключ Steam',
    quantity: 2,
    unitPrice: { amount: 149900, currency: 'RUB' },
    total: { amount: 299800, currency: 'RUB' },
    status: 'awaiting_payment',
    cancelReason: null,
    delivery: { channel: 'email', address: 'buyer@mail.example', masked: false },
    reserveExpiresAt: new Date(Date.now() + 15 * 60_000).toISOString(),
    paymentSessionExpiresAt: new Date(Date.now() + 12 * 60_000).toISOString(),
    createdAt: new Date().toISOString(),
    paidAt: null,
    issuedAt: null,
    paymentSession: { url: PAY_URL, expiresAt: new Date(Date.now() + 12 * 60_000).toISOString() },
    ...overrides,
  };
}

/** Сервер, на котором меняются ответы на создание заказа по порядку обращений; повторяет последний, когда они кончились. */
function orderServer(...answers: Array<() => Response | Promise<Response>>): Handler {
  let attempt = 0;
  return (url) => {
    if (url.pathname === '/api/v1/orders') {
      const answer = answers[Math.min(attempt, answers.length - 1)];
      attempt += 1;
      return answer ? answer() : respond(500, { code: 'internal-error' });
    }
    return respond(404, { code: 'not-found' });
  };
}

function panel(handler: Handler, options: { orderUpTo?: number; managers?: RenderOptions['managers'] } = {}) {
  const onStockChanged = vi.fn();
  const rendered = renderScreen(
    <BuyPanel productId="p-1" price={{ amount: 149900, currency: 'RUB' }} orderUpTo={options.orderUpTo ?? 3} onStockChanged={onStockChanged} />,
    { handler, managers: options.managers ?? { web: fakeManager(buyer()) } },
  );
  return { ...rendered, onStockChanged };
}

/** Кнопка появляется после того, как менеджеры входа ответили, поэтому её ждут. */
const buyButton = () => screen.findByRole('button', { name: t('purchase.buy') });

const orderCalls = (calls: ReturnType<typeof renderScreen>['calls']) => calls.filter((call) => call.url.pathname === '/api/v1/orders');

async function setQuantity(user: ReturnType<typeof userEvent.setup>, value: string) {
  const field = await screen.findByLabelText(t('purchase.quantity'));
  await user.clear(field);
  if (value !== '') {
    await user.type(field, value);
  }
}

beforeEach(() => {
  vi.mocked(redirectTo).mockClear();
});

describe('покупка: количество и итог (FT-5.1, INV-10)', () => {
  it('показывает количество по умолчанию 1 и итог из цены карточки, итог растёт с количеством', async () => {
    const user = userEvent.setup();
    panel(orderServer(() => respond(201, createdOrder())));
    expect(await screen.findByLabelText(t('purchase.quantity'))).toHaveValue('1');
    expect(screen.getByText(/Итого:\s1\s499\s₽/)).toBeInTheDocument();
    await setQuantity(user, '2');
    expect(screen.getByText(/Итого:\s2\s998\s₽/)).toBeInTheDocument();
  });

  it('количество вне диапазона не отправляется: ошибка у поля', async () => {
    const user = userEvent.setup();
    const { calls } = panel(orderServer(() => respond(201, createdOrder())));
    for (const bad of ['0', '4', '', '1,5', 'два']) {
      await setQuantity(user, bad);
      await user.click(await buyButton());
      expect(await screen.findByText(t('purchase.quantity.error', { max: 3 })), bad).toBeInTheDocument();
    }
    expect(orderCalls(calls)).toHaveLength(0);
    expect(redirectTo).not.toHaveBeenCalled();
  });

  it('предел поля не больше десяти, даже если на складе больше', async () => {
    const user = userEvent.setup();
    const { calls } = panel(orderServer(() => respond(201, createdOrder())), { orderUpTo: 57 });
    expect(await screen.findByText(t('purchase.quantity.hint', { max: 10 }))).toBeInTheDocument();
    await setQuantity(user, '11');
    await user.click(await buyButton());
    expect(await screen.findByText(t('purchase.quantity.error', { max: 10 }))).toBeInTheDocument();
    expect(orderCalls(calls)).toHaveLength(0);
  });

  it('товара нет в наличии: формы нет, есть пояснение', async () => {
    panel(orderServer(() => respond(201, createdOrder())), { orderUpTo: 0 });
    expect(await screen.findByText(t('purchase.soldOut'))).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('purchase.buy') })).not.toBeInTheDocument();
  });
});

describe('покупка: создание заказа и переход к оплате (US-5.1)', () => {
  it('отправляет товар и количество с Idempotency-Key, уходит на страницу оплаты и показывает отсчёт резерва', async () => {
    const user = userEvent.setup();
    const { calls } = panel(orderServer(() => respond(201, createdOrder())));
    await setQuantity(user, '2');
    await user.click(await buyButton());

    expect(await screen.findByRole('heading', { name: t('purchase.created.title', { number: 1042 }) })).toBeInTheDocument();
    const [call] = orderCalls(calls);
    expect(call?.init.method).toBe('POST');
    expect(call?.init.body).toBe(JSON.stringify({ productId: 'p-1', quantity: 2 }));
    expect(call?.headers['Idempotency-Key']).toBe('key-1');
    expect(redirectTo).toHaveBeenCalledExactlyOnceWith(PAY_URL);
    expect(screen.getByText(/Ключи зарезервированы за вами ещё на 1[45]:\d\d\./)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: t('purchase.created.pay') })).toHaveAttribute('href', PAY_URL);
    expect(screen.getByRole('link', { name: t('purchase.created.order') })).toHaveAttribute('href', '/account/orders/o-1');
  });

  it('двойной щелчок создаёт один заказ', async () => {
    const user = userEvent.setup();
    const slow = async () => {
      await new Promise((resolve) => setTimeout(resolve, 30));
      return respond(201, createdOrder());
    };
    const { calls } = panel(orderServer(slow));
    await user.dblClick(await buyButton());
    expect(await screen.findByRole('heading', { name: t('purchase.created.title', { number: 1042 }) })).toBeInTheDocument();
    expect(orderCalls(calls)).toHaveLength(1);
    expect(redirectTo).toHaveBeenCalledTimes(1);
  });

  it('пока запрос идёт, кнопка занята и поле недоступно', async () => {
    const user = userEvent.setup();
    let release: () => void = () => undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    panel(orderServer(async () => {
      await gate;
      return respond(201, createdOrder());
    }));
    await user.click(await buyButton());
    expect(await screen.findByText(t('purchase.working'))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: t('purchase.buy') })).toBeDisabled();
    expect(screen.getByLabelText(t('purchase.quantity'))).toBeDisabled();
    release();
    expect(await screen.findByRole('heading', { name: t('purchase.created.title', { number: 1042 }) })).toBeInTheDocument();
  });

  it('адрес оплаты с чужой схемой не открывается: сообщение и ссылка на заказ', async () => {
    const user = userEvent.setup();
    panel(orderServer(() => respond(201, createdOrder({ paymentSession: { url: 'javascript:alert(1)', expiresAt: '2026-10-25T12:12:00.000Z' } }))));
    await user.click(await buyButton());
    expect(await screen.findByText(t('purchase.created.badUrl'))).toBeInTheDocument();
    expect(redirectTo).not.toHaveBeenCalled();
    expect(screen.getByRole('link', { name: t('purchase.created.order') })).toBeInTheDocument();
  });
});

describe('покупка: ответы 409 и 503 (E-коды оформления заказа)', () => {
  it('insufficient-stock: сколько осталось, количество подставлено, карточка перечитана, новая попытка с новым ключом', async () => {
    const user = userEvent.setup();
    const { calls, onStockChanged } = panel(
      orderServer(
        () => respond(409, { code: 'insufficient-stock', available: 1 }),
        () => respond(201, createdOrder({ quantity: 1 })),
      ),
    );
    await setQuantity(user, '2');
    await user.click(await buyButton());
    expect(await screen.findByText(t('purchase.problem.stock', { available: 1 }))).toBeInTheDocument();
    expect(screen.getByLabelText(t('purchase.quantity'))).toHaveValue('1');
    expect(onStockChanged).toHaveBeenCalledTimes(1);

    await user.click(await buyButton());
    expect(await screen.findByRole('heading', { name: t('purchase.created.title', { number: 1042 }) })).toBeInTheDocument();
    const [first, second] = orderCalls(calls);
    expect(first?.headers['Idempotency-Key']).toBe('key-1');
    expect(second?.headers['Idempotency-Key']).toBe('key-2');
    expect(second?.init.body).toBe(JSON.stringify({ productId: 'p-1', quantity: 1 }));
  });

  it('insufficient-stock при нуле: ключи закончились', async () => {
    const user = userEvent.setup();
    panel(orderServer(() => respond(409, { code: 'insufficient-stock', available: 0 })));
    await user.click(await buyButton());
    expect(await screen.findByText(t('purchase.problem.stockNone'))).toBeInTheDocument();
  });

  it('product-unavailable: товар недоступен, карточка перечитана', async () => {
    const user = userEvent.setup();
    const { onStockChanged } = panel(orderServer(() => respond(409, { code: 'product-unavailable' })));
    await user.click(await buyButton());
    expect(await screen.findByText(t('purchase.problem.unavailable'))).toBeInTheDocument();
    expect(onStockChanged).toHaveBeenCalledTimes(1);
  });

  it('unpaid-orders-limit: предел и ссылка на заказы', async () => {
    const user = userEvent.setup();
    panel(orderServer(() => respond(409, { code: 'unpaid-orders-limit', limit: 3 })));
    await user.click(await buyButton());
    expect(await screen.findByText(t('purchase.problem.limit', { limit: 3 }))).toBeInTheDocument();
    expect(screen.getByRole('link', { name: t('purchase.problem.limit.link') })).toHaveAttribute('href', '/account/orders');
  });

  it('503 payment-session-unavailable: заказ не оформлен, повтор уходит с тем же ключом', async () => {
    const user = userEvent.setup();
    const { calls } = panel(
      orderServer(
        () => respond(503, { code: 'payment-session-unavailable' }, { 'Retry-After': '5' }),
        () => respond(201, createdOrder()),
      ),
    );
    await user.click(await buyButton());
    expect(await screen.findByText(t('purchase.problem.session'))).toBeInTheDocument();
    expect(redirectTo).not.toHaveBeenCalled();

    await user.click(await buyButton());
    expect(await screen.findByRole('heading', { name: t('purchase.created.title', { number: 1042 }) })).toBeInTheDocument();
    expect(orderCalls(calls).map((call) => call.headers['Idempotency-Key'])).toEqual(['key-1', 'key-1']);
  });

  it('сбой сети: понятный текст, повтор с тем же ключом создаёт один заказ', async () => {
    const user = userEvent.setup();
    let attempt = 0;
    const handler: Handler = () => {
      attempt += 1;
      return attempt === 1 ? { fail: new TypeError('сеть') } : respond(201, createdOrder());
    };
    const { calls } = panel(handler);
    await user.click(await buyButton());
    expect(await screen.findByText(t('problem.network'))).toBeInTheDocument();
    await user.click(await buyButton());
    expect(await screen.findByRole('heading', { name: t('purchase.created.title', { number: 1042 }) })).toBeInTheDocument();
    expect(orderCalls(calls).map((call) => call.headers['Idempotency-Key'])).toEqual(['key-1', 'key-1']);
  });

  it('другое количество после неизвестного исхода получает новый ключ', async () => {
    const user = userEvent.setup();
    const { calls } = panel(orderServer(() => respond(500, { code: 'internal-error' }), () => respond(201, createdOrder())));
    await user.click(await buyButton());
    expect(await screen.findByText(t('problem.internal-error'))).toBeInTheDocument();
    await setQuantity(user, '2');
    await user.click(await buyButton());
    await screen.findByRole('heading', { name: t('purchase.created.title', { number: 1042 }) });
    expect(orderCalls(calls).map((call) => call.headers['Idempotency-Key'])).toEqual(['key-1', 'key-2']);
  });
});

describe('покупка: вход и роль', () => {
  it('гость: приглашение войти, после входа возврат на ту же страницу', async () => {
    const user = userEvent.setup();
    const web = fakeManager(null);
    const { calls } = panel(orderServer(() => respond(201, createdOrder())), { managers: { web } });
    await user.click(await screen.findByRole('button', { name: t('purchase.signIn') }));
    expect(web.signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/' }, prompt: undefined });
    expect(orderCalls(calls)).toHaveLength(0);
    expect(screen.queryByLabelText(t('purchase.quantity'))).not.toBeInTheDocument();
  });

  it('вошёл не покупатель: пояснение вместо формы', async () => {
    const web = fakeManager(fakeUser(claimsFor(['seller'])));
    panel(orderServer(() => respond(201, createdOrder())), { managers: { web } });
    expect(await screen.findByText(t('purchase.notBuyer'))).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('purchase.buy') })).not.toBeInTheDocument();
  });
});

describe('покупка: подтверждение телефона по месту (U2, US-1.6)', () => {
  const CONFIRMATION = { confirmationId: 'c-1', phoneMasked: '+7900***4567', expiresAt: '2026-10-25T12:05:00.000Z', attemptsLeft: 5, resendAvailableAt: null };

  /** Заказ сначала отклоняется из-за телефона, а подтверждение телефона отвечает как обычно. */
  function withPhoneFlow(...orderAnswers: Array<() => Response>): Handler {
    const orders = orderServer(...orderAnswers);
    return (url, init) => {
      if (url.pathname === '/api/v1/phone-confirmations') {
        return respond(201, CONFIRMATION);
      }
      if (url.pathname === '/api/v1/phone-confirmations/c-1/verify') {
        return respond(200, { phoneConfirmed: true, phoneMasked: '+7900***4567' });
      }
      return orders(url, init);
    };
  }

  async function confirmPhone(user: ReturnType<typeof userEvent.setup>) {
    await user.type(await screen.findByLabelText(t('phone.number')), '8 900 123-45-67');
    await user.click(screen.getByRole('button', { name: t('phone.send') }));
    await user.type(await screen.findByLabelText(t('phone.code')), '123456');
    await user.click(screen.getByRole('button', { name: t('phone.verify') }));
  }

  const phoneRequired = () => respond(403, { code: 'phone-not-confirmed' });

  it('403: шаги подтверждения на месте, после кода токен обновляется и тот же заказ уходит с тем же ключом', async () => {
    const user = userEvent.setup();
    const web = fakeManager(buyer({ phone_verified: false }), undefined, buyer({ phone_verified: true }));
    const { calls } = panel(withPhoneFlow(phoneRequired, () => respond(201, createdOrder())), { managers: { web } });

    await setQuantity(user, '2');
    await user.click(await buyButton());
    expect(await screen.findByRole('heading', { level: 2, name: t('purchase.phone.title') })).toBeInTheDocument();
    expect(screen.queryByLabelText(t('purchase.quantity'))).not.toBeInTheDocument();

    await confirmPhone(user);
    expect(await screen.findByRole('heading', { name: t('purchase.created.title', { number: 1042 }) })).toBeInTheDocument();
    expect(web.signinSilent).toHaveBeenCalledTimes(1);
    expect(redirectTo).toHaveBeenCalledExactlyOnceWith(PAY_URL);
    const orders = orderCalls(calls);
    expect(orders).toHaveLength(2);
    expect(orders.map((call) => call.headers['Idempotency-Key'])).toEqual(['key-1', 'key-1']);
    expect(orders.map((call) => call.init.body)).toEqual([JSON.stringify({ productId: 'p-1', quantity: 2 }), JSON.stringify({ productId: 'p-1', quantity: 2 })]);
  });

  it('обновить вход не удалось: предложение войти заново, заказ не отправляется', async () => {
    const user = userEvent.setup();
    const web = fakeManager(buyer({ phone_verified: false }));
    const { calls } = panel(withPhoneFlow(phoneRequired, () => respond(201, createdOrder())), { managers: { web } });

    await user.click(await buyButton());
    await screen.findByRole('heading', { level: 2, name: t('purchase.phone.title') });
    await confirmPhone(user);

    expect(await screen.findByText(t('purchase.phone.refreshFailed'))).toBeInTheDocument();
    expect(orderCalls(calls)).toHaveLength(1);
    await user.click(screen.getByRole('button', { name: t('purchase.phone.signInAgain') }));
    expect(web.signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/' }, prompt: undefined });
  });

  it('признак ещё не дошёл до токена: сообщение и кнопка «Продолжить покупку», затем заказ оформляется', async () => {
    const user = userEvent.setup();
    const web = fakeManager(buyer({ phone_verified: false }), undefined, buyer({ phone_verified: true }));
    const { calls } = panel(withPhoneFlow(phoneRequired, phoneRequired, () => respond(201, createdOrder())), { managers: { web } });

    await user.click(await buyButton());
    await screen.findByRole('heading', { level: 2, name: t('purchase.phone.title') });
    await confirmPhone(user);

    expect(await screen.findByText(t('purchase.phone.stale'))).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: t('purchase.phone.continue') }));
    expect(await screen.findByRole('heading', { name: t('purchase.created.title', { number: 1042 }) })).toBeInTheDocument();
    await waitFor(() => expect(web.signinSilent).toHaveBeenCalledTimes(2));
    expect(orderCalls(calls).map((call) => call.headers['Idempotency-Key'])).toEqual(['key-1', 'key-1', 'key-1']);
  });
});
