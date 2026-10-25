import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { t } from '../../i18n';
import { claimsFor, fakeManager, fakeUser } from '../../test/fakeAuth';
import { renderScreen, respond } from '../../test/harness';
import type { Handler, RenderOptions } from '../../test/harness';
import { ProductPage } from './ProductPage';

const product = {
  productId: 'p-1',
  sellerId: 's-1',
  title: 'Лунная тропа: ключ Steam',
  description: 'Приключение на Луне.\nКлюч активируется в Steam.',
  productType: 'game_key',
  platform: 'Steam',
  activationRegions: ['RU', 'KZ'],
  price: { amount: 149900, currency: 'RUB' },
  inStock: true,
  maxOrderQuantity: 3,
};

function screenFor(handler: Handler, id = 'p-1', managers?: RenderOptions['managers']) {
  return renderScreen(
    <Routes>
      <Route path="/products/:productId" element={<ProductPage />} />
    </Routes>,
    { route: `/products/${id}`, handler, managers },
  );
}

describe('карточка товара (TC-326)', () => {
  it('показывает название, цену, платформу, регионы и сколько можно заказать', async () => {
    const { calls } = screenFor(() => respond(200, product));
    expect(await screen.findByRole('heading', { level: 1, name: product.title })).toBeInTheDocument();
    expect(screen.getByText(/1\s499\s₽/)).toBeInTheDocument();
    expect(screen.getByText(t('productType.game_key'))).toBeInTheDocument();
    expect(screen.getByText('RU, KZ')).toBeInTheDocument();
    expect(screen.getByText(t('product.orderUpTo', { count: 3 }))).toBeInTheDocument();
    expect(calls[0]?.url.pathname).toBe('/api/v1/products/p-1');
    expect(calls[0]?.headers.Authorization).toBeUndefined();
  });

  it('нет в наличии: ноль к заказу', async () => {
    screenFor(() => respond(200, { ...product, inStock: false, maxOrderQuantity: 0 }));
    expect(await screen.findByText(t('catalog.outOfStock'))).toBeInTheDocument();
    expect(screen.queryByText(/можно заказать/)).not.toBeInTheDocument();
  });

  it('товар без ограничений по регионам', async () => {
    screenFor(() => respond(200, { ...product, activationRegions: [] }));
    expect(await screen.findByText(t('product.regions.none'))).toBeInTheDocument();
  });

  it('описание продавца показывается текстом, а не разметкой', async () => {
    screenFor(() => respond(200, { ...product, description: '<img src=x onerror=alert(1)><b>жирный</b>' }));
    expect(await screen.findByText('<img src=x onerror=alert(1)><b>жирный</b>')).toBeInTheDocument();
    expect(document.querySelector('img')).toBeNull();
  });

  it('«Обновить наличие» повторяет запрос', async () => {
    const user = userEvent.setup();
    let stock = 3;
    const { calls } = screenFor(() => respond(200, { ...product, maxOrderQuantity: stock }));
    expect(await screen.findByText(t('product.orderUpTo', { count: 3 }))).toBeInTheDocument();
    stock = 1;
    await user.click(screen.getByRole('button', { name: t('product.refresh') }));
    expect(await screen.findByText(t('product.orderUpTo', { count: 1 }))).toBeInTheDocument();
    expect(calls).toHaveLength(2);
  });

  it('товара нет или он не опубликован: страница «не найден» со ссылкой на каталог', async () => {
    screenFor(() => respond(404, { code: 'not-found' }), 'нет-такого');
    expect(await screen.findByRole('heading', { name: t('product.notFound.title') })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: t('product.back') })).toHaveAttribute('href', '/products');
  });

  it('503: нейтральный текст и повтор', async () => {
    const user = userEvent.setup();
    let failing = true;
    screenFor(() => (failing ? respond(503, { code: 'dependency-unavailable' }) : respond(200, product)));
    expect(await screen.findByRole('alert')).toHaveTextContent(t('problem.dependency-unavailable'));
    failing = false;
    await user.click(screen.getByRole('button', { name: t('state.retry') }));
    expect(await screen.findByRole('heading', { level: 1, name: product.title })).toBeInTheDocument();
  });

  it('гость видит приглашение войти вместо формы покупки', async () => {
    screenFor(() => respond(200, product));
    expect(await screen.findByRole('button', { name: t('purchase.signIn') })).toBeInTheDocument();
    expect(screen.queryByLabelText(t('purchase.quantity'))).not.toBeInTheDocument();
  });

  it('покупатель видит форму покупки: количество не больше остатка', async () => {
    const web = fakeManager(fakeUser(claimsFor(['buyer'], ['pwd'], { phone_verified: true })));
    screenFor(() => respond(200, product), 'p-1', { web });
    expect(await screen.findByLabelText(t('purchase.quantity'))).toHaveValue('1');
    expect(screen.getByText(t('purchase.quantity.hint', { max: 3 }))).toBeInTheDocument();
  });

  it('нет в наличии: вместо формы пояснение', async () => {
    const web = fakeManager(fakeUser(claimsFor(['buyer'], ['pwd'], { phone_verified: true })));
    screenFor(() => respond(200, { ...product, inStock: false, maxOrderQuantity: 0 }), 'p-1', { web });
    expect(await screen.findByText(t('purchase.soldOut'))).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('purchase.buy') })).not.toBeInTheDocument();
  });

  it('ответ «ключей не хватает» перечитывает карточку и показывает новый остаток', async () => {
    const user = userEvent.setup();
    const web = fakeManager(fakeUser(claimsFor(['buyer'], ['pwd'], { phone_verified: true })));
    let stock = 3;
    const handler: Handler = (url) =>
      url.pathname === '/api/v1/orders' ? respond(409, { code: 'insufficient-stock', available: 1 }) : respond(200, { ...product, maxOrderQuantity: stock });
    const { calls } = screenFor(handler, 'p-1', { web });
    await screen.findByText(t('product.orderUpTo', { count: 3 }));
    stock = 1;
    await user.clear(await screen.findByLabelText(t('purchase.quantity')));
    await user.type(screen.getByLabelText(t('purchase.quantity')), '2');
    await user.click(screen.getByRole('button', { name: t('purchase.buy') }));
    expect(await screen.findByText(t('product.orderUpTo', { count: 1 }))).toBeInTheDocument();
    expect(screen.getByText(t('purchase.problem.stock', { available: 1 }))).toBeInTheDocument();
    expect(calls.filter((call) => call.url.pathname === '/api/v1/products/p-1')).toHaveLength(2);
  });
});
