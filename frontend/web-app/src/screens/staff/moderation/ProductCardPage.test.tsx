import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { t } from '../../../i18n';
import { renderScreen, respond } from '../../../test/harness';
import type { Handler } from '../../../test/harness';
import { ProductCardPage } from './ProductCardPage';

const SCREEN = (
  <Routes>
    <Route path="/staff/products/:productId" element={<ProductCardPage />} />
  </Routes>
);
const ROUTE = '/staff/products/p-1';
const BASE = '/api/v1/staff/products/p-1';

function product(overrides: Record<string, unknown> = {}) {
  return {
    productId: 'p-1',
    sellerId: 's-1',
    title: 'Игра',
    description: 'Ключ активации\nдля Steam',
    productType: 'game_key',
    platform: 'Steam',
    activationRegions: ['RU', 'KZ'],
    price: { amount: 149900, currency: 'RUB' },
    issuanceMethod: 'key_pool',
    status: 'on_moderation',
    rejectionReason: null,
    blockReason: null,
    submittedAt: '2026-10-17T09:30:00Z',
    reviewDeadlineAt: '2026-10-20T09:30:00Z',
    version: 2,
    createdAt: '2026-10-17T09:00:00Z',
    updatedAt: '2026-10-17T09:30:00Z',
    ...overrides,
  };
}

function server(state: { product: ReturnType<typeof product> }, overrides: Record<string, Handler> = {}): Handler {
  return (url, init) => {
    const route = `${init.method ?? 'GET'} ${url.pathname}`;
    const custom = overrides[route];
    if (custom) {
      return custom(url, init);
    }
    return route === `GET ${BASE}` ? respond(200, state.product) : respond(404, { code: 'not-found' });
  };
}

describe('карточка товара на модерации (TC-328)', () => {
  it('показывает поля товара, на модерации доступны «опубликовать» и «отклонить», блокировки нет', async () => {
    renderScreen(SCREEN, { route: ROUTE, handler: server({ product: product() }) });
    expect(await screen.findByRole('heading', { level: 1, name: 'Игра' })).toBeInTheDocument();
    expect(screen.getByText('Steam')).toBeInTheDocument();
    expect(screen.getByText('RU, KZ')).toBeInTheDocument();
    expect(screen.getByText(/1\s499\s₽/)).toBeInTheDocument();
    expect(screen.getByText(/Ключ активации/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: t('moderation.product.approve') })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: t('moderation.product.reject') })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('moderation.product.block') })).not.toBeInTheDocument();
  });

  it('опубликованный товар можно только заблокировать', async () => {
    renderScreen(SCREEN, { route: ROUTE, handler: server({ product: product({ status: 'published' }) }) });
    expect(await screen.findByRole('button', { name: t('moderation.product.block') })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('moderation.product.approve') })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('moderation.product.reject') })).not.toBeInTheDocument();
  });

  it('заблокированный и отклонённый товар: решений нет, причина видна', async () => {
    renderScreen(SCREEN, { route: ROUTE, handler: server({ product: product({ status: 'blocked', blockReason: 'Нарушение правил' }) }) });
    expect(await screen.findByText(t('moderation.product.finished', { status: t('productStatus.blocked') }))).toBeInTheDocument();
    expect(screen.getByText('Нарушение правил')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('moderation.product.block') })).not.toBeInTheDocument();
  });

  it('опубликовать: подтверждение, POST с Idempotency-Key, карточка обновляется', async () => {
    const user = userEvent.setup();
    const state = { product: product() };
    const handler = server(state, {
      [`POST ${BASE}/approve`]: () => {
        state.product = product({ status: 'published' });
        return respond(200, state.product);
      },
    });
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler });
    await user.click(await screen.findByRole('button', { name: t('moderation.product.approve') }));
    expect(screen.getByText(t('moderation.product.approve.confirm'))).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: t('moderation.decision.confirm') }));

    expect(await screen.findByText(t('moderation.product.approve.done'))).toBeInTheDocument();
    const post = calls.find((call) => call.init.method === 'POST');
    expect(post?.headers['Idempotency-Key']).toBe('key-1');
    expect(post?.init.body).toBeUndefined();
    expect(await screen.findByRole('button', { name: t('moderation.product.block') })).toBeInTheDocument();
  });

  it('отклонение без причины не отправляется, с причиной уходит', async () => {
    const user = userEvent.setup();
    const state = { product: product() };
    const handler = server(state, {
      [`POST ${BASE}/reject`]: () => {
        state.product = product({ status: 'rejected', rejectionReason: 'Описание пустое' });
        return respond(200, state.product);
      },
    });
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler });
    await user.click(await screen.findByRole('button', { name: t('moderation.product.reject') }));
    await user.click(screen.getByRole('button', { name: t('moderation.decision.confirm') }));
    expect(await screen.findByText(t('moderation.error.reasonRequired'))).toBeInTheDocument();
    expect(calls.every((call) => call.init.method !== 'POST')).toBe(true);

    await user.type(screen.getByLabelText(t('moderation.product.reject.reason')), 'Описание пустое');
    await user.click(screen.getByRole('button', { name: t('moderation.decision.confirm') }));
    expect(await screen.findByText(t('moderation.product.reject.done'))).toBeInTheDocument();
    expect(JSON.parse(String(calls.find((call) => call.init.method === 'POST')?.init.body))).toEqual({ reason: 'Описание пустое' });
  });

  it('блокировка опубликованного: причина обязательна', async () => {
    const user = userEvent.setup();
    const state = { product: product({ status: 'published' }) };
    const handler = server(state, {
      [`POST ${BASE}/block`]: () => {
        state.product = product({ status: 'blocked', blockReason: 'Жалоба правообладателя' });
        return respond(200, state.product);
      },
    });
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler });
    await user.click(await screen.findByRole('button', { name: t('moderation.product.block') }));
    await user.type(screen.getByLabelText(t('moderation.product.block.reason')), 'Жалоба правообладателя');
    await user.click(screen.getByRole('button', { name: t('moderation.decision.confirm') }));
    expect(await screen.findByText(t('moderation.product.block.done'))).toBeInTheDocument();
    expect(calls.find((call) => call.init.method === 'POST')?.url.pathname).toBe(`${BASE}/block`);
    expect(await screen.findByText('Жалоба правообладателя')).toBeInTheDocument();
  });

  it('409 «товар уже решён»: сообщение со статусом и обновлённая карточка', async () => {
    const user = userEvent.setup();
    const state = { product: product() };
    const handler = server(state, {
      [`POST ${BASE}/approve`]: () => {
        state.product = product({ status: 'rejected', rejectionReason: 'Другая причина' });
        return respond(409, { code: 'state-conflict', currentStatus: 'rejected' });
      },
    });
    renderScreen(SCREEN, { route: ROUTE, handler });
    await user.click(await screen.findByRole('button', { name: t('moderation.product.approve') }));
    await user.click(screen.getByRole('button', { name: t('moderation.decision.confirm') }));
    expect(await screen.findByText(t('moderation.alreadyDecided', { status: t('productStatus.rejected') }))).toBeInTheDocument();
    expect(await screen.findByText(t('moderation.product.finished', { status: t('productStatus.rejected') }))).toBeInTheDocument();
  });

  it('товара нет: сообщение об ошибке', async () => {
    renderScreen(SCREEN, { route: ROUTE, handler: () => respond(404, { code: 'not-found' }) });
    expect(await screen.findByText(t('problem.not-found'))).toBeInTheDocument();
  });
});
