import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { plural, t } from '../../i18n';
import { renderScreen, respond } from '../../test/harness';
import type { Handler } from '../../test/harness';
import { ProductListPage } from './ProductListPage';

function item(id: string, title: string, amount: number, inStock: boolean) {
  return { productId: id, title, productType: 'game_key', platform: 'Steam', price: { amount, currency: 'RUB' }, inStock };
}

const first = { items: [item('p-1', 'Лунная тропа: ключ Steam', 149900, true), item('p-2', 'Астероиды', 49900, false)], page: { limit: 20, nextCursor: 'c-2' } };
const second = { items: [item('p-3', 'Третий товар', 10000, true)], page: { limit: 20, nextCursor: null } };

const catalog: Handler = (url) => respond(200, url.searchParams.get('cursor') === 'c-2' ? second : first);

function params(call: { url: URL }): Record<string, string> {
  return Object.fromEntries(call.url.searchParams);
}

describe('каталог', () => {
  it('показывает товары с ценой и наличием, первый запрос идёт без фильтров и без токена', async () => {
    const { calls } = renderScreen(<ProductListPage />, { route: '/products', handler: catalog });
    expect(await screen.findByRole('link', { name: 'Лунная тропа: ключ Steam' })).toHaveAttribute('href', '/products/p-1');
    expect(screen.getByText(/1\s499\s₽/)).toBeInTheDocument();
    expect(screen.getByText(t('catalog.outOfStock'))).toBeInTheDocument();
    expect(screen.getByText(t('catalog.inStock'))).toBeInTheDocument();
    expect(screen.getByText(plural(2, { one: 'catalog.count.one', few: 'catalog.count.few', many: 'catalog.count.many' }))).toBeInTheDocument();
    expect(calls).toHaveLength(1);
    expect(calls[0]?.url.pathname).toBe('/api/v1/products');
    expect(params(calls[0])).toEqual({ limit: '20' });
    expect(calls[0]?.headers.Authorization).toBeUndefined();
  });

  it('поиск и фильтры собирают нужный запрос: типы, платформа, регион, цены в копейках', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(<ProductListPage />, { route: '/products', handler: catalog });
    await screen.findByRole('link', { name: 'Астероиды' });

    await user.type(screen.getByLabelText(t('catalog.filter.q')), 'тропа');
    await user.click(screen.getByRole('checkbox', { name: t('productType.game_key') }));
    await user.click(screen.getByRole('checkbox', { name: t('productType.gift_card') }));
    await user.type(screen.getByLabelText(t('catalog.filter.platform')), 'Steam');
    await user.type(screen.getByLabelText(t('catalog.filter.region')), 'ru');
    await user.type(screen.getByLabelText(t('catalog.filter.priceFrom')), '1000');
    await user.type(screen.getByLabelText(t('catalog.filter.priceTo')), '2 000,50');
    await user.click(screen.getByRole('button', { name: t('catalog.filter.apply') }));

    await waitFor(() => expect(calls).toHaveLength(2));
    expect(params(calls[1])).toEqual({
      q: 'тропа',
      productType: 'game_key,gift_card',
      platform: 'Steam',
      region: 'RU',
      priceFrom: '100000',
      priceTo: '200050',
      limit: '20',
    });
  });

  it('запрос не уходит на каждую букву: только по кнопке', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(<ProductListPage />, { route: '/products', handler: catalog });
    await screen.findByRole('link', { name: 'Астероиды' });
    await user.type(screen.getByLabelText(t('catalog.filter.q')), 'лун');
    expect(calls).toHaveLength(1);
  });

  it('нижняя граница цены больше верхней: ошибка у поля, запроса нет', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(<ProductListPage />, { route: '/products', handler: catalog });
    await screen.findByRole('link', { name: 'Астероиды' });
    await user.type(screen.getByLabelText(t('catalog.filter.priceFrom')), '5000');
    await user.type(screen.getByLabelText(t('catalog.filter.priceTo')), '100');
    await user.click(screen.getByRole('button', { name: t('catalog.filter.apply') }));
    expect(await screen.findByText(t('catalog.filter.error.range'))).toBeInTheDocument();
    expect(calls).toHaveLength(1);
  });

  it('фильтры из адреса попадают в поля и в запрос', async () => {
    const { calls } = renderScreen(<ProductListPage />, { route: '/products?type=game_key&region=RU&q=%D0%BB%D1%83%D0%BD%D0%B0', handler: catalog });
    await screen.findByRole('link', { name: 'Астероиды' });
    expect(screen.getByRole('checkbox', { name: t('productType.game_key') })).toBeChecked();
    expect(screen.getByLabelText(t('catalog.filter.region'))).toHaveValue('RU');
    expect(screen.getByLabelText(t('catalog.filter.q'))).toHaveValue('луна');
    expect(params(calls[0])).toEqual({ q: 'луна', productType: 'game_key', region: 'RU', limit: '20' });
  });

  it('«Показать ещё» запрашивает следующую страницу по курсору и добавляет товары', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(<ProductListPage />, { route: '/products', handler: catalog });
    await screen.findByRole('link', { name: 'Астероиды' });
    await user.click(screen.getByRole('button', { name: t('catalog.more') }));
    expect(await screen.findByRole('link', { name: 'Третий товар' })).toBeInTheDocument();
    expect(params(calls[1]).cursor).toBe('c-2');
    expect(screen.getByRole('link', { name: 'Лунная тропа: ключ Steam' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('catalog.more') })).not.toBeInTheDocument();
  });

  it('«Сбросить» очищает поля и возвращает полный список', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(<ProductListPage />, { route: '/products?q=%D0%BB%D1%83%D0%BD%D0%B0', handler: catalog });
    await screen.findByRole('link', { name: 'Астероиды' });
    await user.click(screen.getByRole('button', { name: t('catalog.filter.reset') }));
    await waitFor(() => expect(calls).toHaveLength(2));
    expect(params(calls[1])).toEqual({ limit: '20' });
    expect(screen.getByLabelText(t('catalog.filter.q'))).toHaveValue('');
  });
});

describe('состояния списка', () => {
  const empty = respond(200, { items: [], page: { limit: 20, nextCursor: null } });

  it('пусто без фильтров', async () => {
    renderScreen(<ProductListPage />, { route: '/products', handler: () => empty.clone() });
    expect(await screen.findByText(t('catalog.empty.all'))).toBeInTheDocument();
  });

  it('пусто с фильтрами предлагает изменить фильтры', async () => {
    renderScreen(<ProductListPage />, { route: '/products?q=zzz', handler: () => empty.clone() });
    expect(await screen.findByText(t('catalog.empty.filtered'))).toBeInTheDocument();
  });

  it('пока идёт запрос, показана загрузка', async () => {
    renderScreen(<ProductListPage />, { route: '/products', handler: () => new Promise(() => undefined) });
    const region = screen.getByRole('region', { name: t('catalog.results') });
    expect(within(region).getByText(t('state.loading'))).toBeInTheDocument();
  });

  it('503 показывает нейтральный текст и время ожидания, «Повторить» загружает список', async () => {
    const user = userEvent.setup();
    let failing = true;
    renderScreen(<ProductListPage />, {
      route: '/products',
      handler: () => (failing ? respond(503, { code: 'dependency-unavailable' }, { 'Retry-After': '30' }) : respond(200, first)),
    });
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(`${t('problem.dependency-unavailable')} ${t('problem.retryAfter', { seconds: 30 })}`);
    expect(alert).not.toHaveTextContent('dependency-unavailable');
    failing = false;
    await user.click(screen.getByRole('button', { name: t('state.retry') }));
    expect(await screen.findByRole('link', { name: 'Астероиды' })).toBeInTheDocument();
  });

  it('429 показывает время ожидания', async () => {
    renderScreen(<ProductListPage />, { route: '/products', handler: () => respond(429, { code: 'rate-limited' }, { 'Retry-After': '45' }) });
    expect(await screen.findByRole('alert')).toHaveTextContent(`${t('problem.rate-limited')} ${t('problem.retryAfter', { seconds: 45 })}`);
  });

  it('сбой сети: нет связи с сервером', async () => {
    renderScreen(<ProductListPage />, { route: '/products', handler: () => ({ fail: new TypeError('Failed to fetch') }) });
    expect(await screen.findByRole('alert')).toHaveTextContent(t('problem.network'));
  });

  it('ошибка следующей страницы не прячет уже загруженные товары', async () => {
    const user = userEvent.setup();
    renderScreen(<ProductListPage />, {
      route: '/products',
      handler: (url) => (url.searchParams.get('cursor') ? respond(503, { code: 'dependency-unavailable' }) : respond(200, first)),
    });
    await screen.findByRole('link', { name: 'Астероиды' });
    await user.click(screen.getByRole('button', { name: t('catalog.more') }));
    expect(await screen.findByRole('alert')).toHaveTextContent(t('problem.dependency-unavailable'));
    expect(screen.getByRole('link', { name: 'Астероиды' })).toBeInTheDocument();
  });
});
