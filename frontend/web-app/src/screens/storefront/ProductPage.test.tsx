import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { t } from '../../i18n';
import { renderScreen, respond } from '../../test/harness';
import type { Handler } from '../../test/harness';
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

function screenFor(handler: Handler, id = 'p-1') {
  return renderScreen(
    <Routes>
      <Route path="/products/:productId" element={<ProductPage />} />
    </Routes>,
    { route: `/products/${id}`, handler },
  );
}

describe('карточка товара', () => {
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
});
