import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { t } from '../../i18n';
import { renderScreen, respond } from '../../test/harness';
import type { Handler } from '../../test/harness';
import { formatMoment } from '../../ui/format';
import { SellerProductPage } from './SellerProductPage';

function product(overrides: Record<string, unknown> = {}) {
  return {
    productId: 'p-1',
    sellerId: 's-1',
    title: 'Игра',
    description: 'Ключ активации',
    productType: 'game_key',
    platform: 'Steam',
    activationRegions: ['RU', 'KZ'],
    price: { amount: 149950, currency: 'RUB' },
    issuanceMethod: 'key_pool',
    status: 'draft',
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

const SCREEN = (
  <Routes>
    <Route path="/seller/products/new" element={<SellerProductPage />} />
    <Route path="/seller/products/:productId" element={<SellerProductPage />} />
  </Routes>
);

function server(state: { current: ReturnType<typeof product>; etag?: string }, overrides: Record<string, Handler> = {}): Handler {
  return (url, init) => {
    const route = `${init.method ?? 'GET'} ${url.pathname}`;
    const custom = overrides[route];
    if (custom) {
      return custom(url, init);
    }
    if (route === 'GET /api/v1/seller/products/p-1') {
      return respond(200, state.current, { ETag: state.etag ?? `"${state.current.version}"` });
    }
    return respond(404, { code: 'not-found' });
  };
}

async function fillNew(user: ReturnType<typeof userEvent.setup>) {
  await user.type(await screen.findByLabelText(t('seller.product.title')), 'Игра');
  await user.type(screen.getByLabelText(t('seller.product.description')), 'Ключ активации');
  await user.selectOptions(screen.getByLabelText(t('seller.product.type')), 'game_key');
  await user.type(screen.getByLabelText(t('seller.product.platform')), 'Steam');
  await user.type(screen.getByLabelText(t('seller.product.regions')), 'ru, kz');
  await user.type(screen.getByLabelText(t('seller.product.price')), '1499,50');
}

describe('товар продавца: создание', () => {
  it('пустая форма не уходит: ошибки у обязательных полей, запросов нет', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(SCREEN, { route: '/seller/products/new' });
    await user.click(await screen.findByRole('button', { name: t('seller.product.save') }));
    expect(screen.getAllByText(t('field.required')).length).toBeGreaterThanOrEqual(5);
    expect(calls).toHaveLength(0);
  });

  it('цена ноль и неверный регион: ошибки у полей, запросов нет', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(SCREEN, { route: '/seller/products/new' });
    await fillNew(user);
    await user.clear(screen.getByLabelText(t('seller.product.price')));
    await user.type(screen.getByLabelText(t('seller.product.price')), '0');
    await user.clear(screen.getByLabelText(t('seller.product.regions')));
    await user.type(screen.getByLabelText(t('seller.product.regions')), 'RUS');
    await user.click(screen.getByRole('button', { name: t('seller.product.save') }));
    expect(await screen.findByText(t('product.error.price'))).toBeInTheDocument();
    expect(screen.getByText(t('product.error.regions'))).toBeInTheDocument();
    expect(calls).toHaveLength(0);
  });

  it('создание: цена в копейках, регионы списком, Idempotency-Key, переход к карточке товара', async () => {
    const user = userEvent.setup();
    const created = product();
    const handler = server(
      { current: created },
      { 'POST /api/v1/seller/products': () => respond(201, created, { ETag: '"1"' }) },
    );
    const { calls } = renderScreen(SCREEN, { route: '/seller/products/new', handler });
    await fillNew(user);
    await user.click(screen.getByRole('button', { name: t('seller.product.save') }));

    await waitFor(() => expect(calls.some((call) => call.init.method === 'POST')).toBe(true));
    const post = calls.find((call) => call.init.method === 'POST');
    expect(post?.headers['Idempotency-Key']).toBe('key-1');
    expect(JSON.parse(String(post?.init.body))).toEqual({
      title: 'Игра',
      description: 'Ключ активации',
      productType: 'game_key',
      platform: 'Steam',
      activationRegions: ['RU', 'KZ'],
      price: { amount: 149950, currency: 'RUB' },
      issuanceMethod: 'key_pool',
    });
    // После создания открывается карточка с кнопками перехода
    expect(await screen.findByRole('button', { name: t('seller.product.submit') })).toBeInTheDocument();
  });

  it('сбой сети при создании: повтор с тем же ключом и тем же телом', async () => {
    const user = userEvent.setup();
    let attempt = 0;
    const created = product();
    const handler = server(
      { current: created },
      {
        'POST /api/v1/seller/products': () => {
          attempt += 1;
          return attempt === 1 ? { fail: new TypeError('network') } : respond(201, created, { ETag: '"1"' });
        },
      },
    );
    const { calls } = renderScreen(SCREEN, { route: '/seller/products/new', handler });
    await fillNew(user);
    await user.click(screen.getByRole('button', { name: t('seller.product.save') }));
    expect(await screen.findByText(t('problem.network'))).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: t('seller.product.save') }));
    await waitFor(() => expect(calls.filter((call) => call.init.method === 'POST')).toHaveLength(2));

    const posts = calls.filter((call) => call.init.method === 'POST');
    expect(posts[0]?.headers['Idempotency-Key']).toBe(posts[1]?.headers['Idempotency-Key']);
    expect(posts[0]?.init.body).toBe(posts[1]?.init.body);
  });

  it('ошибка поля от сервера показывается у поля (цена)', async () => {
    const user = userEvent.setup();
    const handler: Handler = () => respond(422, { code: 'validation-failed', errors: [{ pointer: '/price/amount', code: 'out_of_range' }] });
    renderScreen(SCREEN, { route: '/seller/products/new', handler });
    await fillNew(user);
    await user.click(screen.getByRole('button', { name: t('seller.product.save') }));
    await waitFor(() => expect(screen.getByLabelText(t('seller.product.price'))).toHaveAccessibleDescription(expect.stringContaining(t('field.out_of_range'))));
  });
});

describe('товар продавца: правка', () => {
  it('правка уходит с If-Match из ETag', async () => {
    const user = userEvent.setup();
    const handler = server(
      { current: product(), etag: '"1"' },
      { 'PUT /api/v1/seller/products/p-1': () => respond(200, product({ title: 'Новое название', version: 2 }), { ETag: '"2"' }) },
    );
    const { calls } = renderScreen(SCREEN, { route: '/seller/products/p-1', handler });
    const title = await screen.findByLabelText(t('seller.product.title'));
    await user.clear(title);
    await user.type(title, 'Новое название');
    await user.click(screen.getByRole('button', { name: t('seller.product.save') }));

    expect(await screen.findByText(t('seller.product.saved'))).toBeInTheDocument();
    const put = calls.find((call) => call.init.method === 'PUT');
    expect(put?.headers['If-Match']).toBe('"1"');
    expect(JSON.parse(String(put?.init.body)).title).toBe('Новое название');
  });

  it('товар изменился в другом месте (412): сообщение о конфликте версий', async () => {
    const user = userEvent.setup();
    const handler = server({ current: product() }, { 'PUT /api/v1/seller/products/p-1': () => respond(412, { code: 'version-conflict' }) });
    renderScreen(SCREEN, { route: '/seller/products/p-1', handler });
    await user.click(await screen.findByRole('button', { name: t('seller.product.save') }));
    expect(await screen.findByText(t('problem.version-conflict'))).toBeInTheDocument();
  });

  it('опубликованный товар: правка названия предупреждает о повторной модерации, правка цены нет', async () => {
    const user = userEvent.setup();
    const handler = server({ current: product({ status: 'published' }) });
    renderScreen(SCREEN, { route: '/seller/products/p-1', handler });
    const price = await screen.findByLabelText(t('seller.product.price'));
    await user.clear(price);
    await user.type(price, '999');
    expect(screen.queryAllByText(t('seller.product.remoderation'))).toHaveLength(0);

    const title = screen.getByLabelText(t('seller.product.title'));
    await user.type(title, '!');
    expect(await screen.findAllByText(t('seller.product.remoderation'))).not.toHaveLength(0);
  });

  it('после правки опубликованного с переходом в модерацию сообщение об этом', async () => {
    const user = userEvent.setup();
    const handler = server(
      { current: product({ status: 'published' }) },
      { 'PUT /api/v1/seller/products/p-1': () => respond(200, product({ status: 'on_moderation', title: 'Игра!', version: 2 }), { ETag: '"2"' }) },
    );
    renderScreen(SCREEN, { route: '/seller/products/p-1', handler });
    await user.type(await screen.findByLabelText(t('seller.product.title')), '!');
    await user.click(screen.getByRole('button', { name: t('seller.product.save') }));
    expect(await screen.findByText(t('seller.product.savedToModeration'))).toBeInTheDocument();
    expect(screen.getByLabelText(t('seller.product.title'))).toBeDisabled();
  });

  it('товар на модерации: поля закрыты, срок проверки показан, «отправить» нет', async () => {
    const deadline = '2026-10-20T09:00:00Z';
    const handler = server({ current: product({ status: 'on_moderation', submittedAt: '2026-10-17T09:00:00Z', reviewDeadlineAt: deadline }) });
    renderScreen(SCREEN, { route: '/seller/products/p-1', handler });
    expect(await screen.findByText(t('seller.product.reviewUntil', { time: formatMoment(deadline) }))).toBeInTheDocument();
    expect(screen.getByLabelText(t('seller.product.title'))).toBeDisabled();
    expect(screen.queryByRole('button', { name: t('seller.product.save') })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('seller.product.submit') })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: t('seller.product.archive') })).toBeInTheDocument();
  });

  it('отклонён: причина видна, можно править и отправить снова', async () => {
    const handler = server({ current: product({ status: 'rejected', rejectionReason: 'Описание не соответствует' }) });
    renderScreen(SCREEN, { route: '/seller/products/p-1', handler });
    expect(await screen.findByText(t('seller.product.rejectedBecause', { reason: 'Описание не соответствует' }))).toBeInTheDocument();
    expect(screen.getByLabelText(t('seller.product.title'))).toBeEnabled();
    expect(screen.getByRole('button', { name: t('seller.product.submit') })).toBeInTheDocument();
  });

  it('в архиве: поля закрыты, есть только «вернуть из архива»', async () => {
    const handler = server({ current: product({ status: 'archived' }) });
    renderScreen(SCREEN, { route: '/seller/products/p-1', handler });
    expect(await screen.findByRole('button', { name: t('seller.product.restore') })).toBeInTheDocument();
    expect(screen.getByLabelText(t('seller.product.title'))).toBeDisabled();
    expect(screen.queryByRole('button', { name: t('seller.product.archive') })).not.toBeInTheDocument();
  });

  it('товара нет: сообщение об ошибке и повтор', async () => {
    renderScreen(SCREEN, { route: '/seller/products/p-1', handler: () => respond(404, { code: 'not-found' }) });
    expect(await screen.findByText(t('problem.not-found'))).toBeInTheDocument();
  });
});

describe('товар продавца: переходы статуса', () => {
  it('«Отправить на модерацию»: POST с Idempotency-Key, статус обновляется', async () => {
    const user = userEvent.setup();
    const state = { current: product() };
    const handler = server(state, {
      'POST /api/v1/seller/products/p-1/submit': () => {
        state.current = product({ status: 'on_moderation', version: 2, submittedAt: '2026-10-17T10:00:00Z', reviewDeadlineAt: '2026-10-20T10:00:00Z' });
        return respond(200, state.current, { ETag: '"2"' });
      },
    });
    const { calls } = renderScreen(SCREEN, { route: '/seller/products/p-1', handler });
    await user.click(await screen.findByRole('button', { name: t('seller.product.submit') }));

    expect(await screen.findByText(t('seller.product.done.submit'))).toBeInTheDocument();
    const post = calls.find((call) => call.init.method === 'POST');
    expect(post?.headers['Idempotency-Key']).toBe('key-1');
    expect(post?.init.body).toBeUndefined();
    await waitFor(() => expect(screen.getByLabelText(t('seller.product.title'))).toBeDisabled());
  });

  it('409 «уже в другом статусе»: сообщение, данные не меняются локально', async () => {
    const user = userEvent.setup();
    const handler = server({ current: product() }, { 'POST /api/v1/seller/products/p-1/submit': () => respond(409, { code: 'state-conflict', currentStatus: 'on_moderation' }) });
    renderScreen(SCREEN, { route: '/seller/products/p-1', handler });
    await user.click(await screen.findByRole('button', { name: t('seller.product.submit') }));
    expect(await screen.findByText(t('problem.state-conflict'))).toBeInTheDocument();
  });

  it('архивация и возврат из архива', async () => {
    const user = userEvent.setup();
    const state = { current: product({ status: 'published' }) };
    const handler = server(state, {
      'POST /api/v1/seller/products/p-1/archive': () => {
        state.current = product({ status: 'archived', version: 2 });
        return respond(200, state.current, { ETag: '"2"' });
      },
      'POST /api/v1/seller/products/p-1/restore': () => {
        state.current = product({ status: 'draft', version: 3 });
        return respond(200, state.current, { ETag: '"3"' });
      },
    });
    renderScreen(SCREEN, { route: '/seller/products/p-1', handler });
    await user.click(await screen.findByRole('button', { name: t('seller.product.archive') }));
    expect(await screen.findByText(t('seller.product.done.archive'))).toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: t('seller.product.restore') }));
    expect(await screen.findByText(t('seller.product.done.restore'))).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: t('seller.product.submit') })).toBeInTheDocument();
  });

  it('сбой сети при переходе: повтор с тем же ключом', async () => {
    const user = userEvent.setup();
    let attempt = 0;
    const handler = server(
      { current: product() },
      {
        'POST /api/v1/seller/products/p-1/submit': () => {
          attempt += 1;
          return attempt === 1 ? { fail: new TypeError('network') } : respond(200, product({ status: 'on_moderation', version: 2 }), { ETag: '"2"' });
        },
      },
    );
    const { calls } = renderScreen(SCREEN, { route: '/seller/products/p-1', handler });
    await user.click(await screen.findByRole('button', { name: t('seller.product.submit') }));
    expect(await screen.findByText(t('problem.network'))).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: t('seller.product.submit') }));
    expect(await screen.findByText(t('seller.product.done.submit'))).toBeInTheDocument();
    const keys = calls.filter((call) => call.init.method === 'POST').map((call) => call.headers['Idempotency-Key']);
    expect(keys).toEqual(['key-1', 'key-1']);
  });

  it('ссылка на остаток и ключи ведёт на страницу ключей товара', async () => {
    renderScreen(SCREEN, { route: '/seller/products/p-1', handler: server({ current: product() }) });
    expect(await screen.findByRole('link', { name: t('seller.product.keysLink') })).toHaveAttribute('href', '/seller/products/p-1/keys');
  });
});
