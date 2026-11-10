import { focusManager } from '@tanstack/react-query';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import { t } from '../../i18n';
import { renderScreen, respond } from '../../test/harness';
import type { Handler, Rendered } from '../../test/harness';
import { ApiStockSection } from './ApiStockSection';

const STOCK_GET = 'GET /api/v1/seller/products/p-1/stock';
const STOCK_PUT = 'PUT /api/v1/seller/products/p-1/api-stock';

function stock(overrides: Record<string, unknown> = {}) {
  return { productId: 'p-1', available: 10, reserved: 2, issued: 0, issuanceMethod: 'seller_api', version: 4, updatedAt: '2026-11-02T12:00:00Z', ...overrides };
}

type Stock = ReturnType<typeof stock>;

/** Сервер остатка: текущее состояние в `state.stock`, PUT по умолчанию принимает значение и поднимает версию. */
function server(state: { stock: Stock }, overrides: Record<string, Handler> = {}): Handler {
  return (url, init) => {
    const route = `${init.method ?? 'GET'} ${url.pathname}`;
    const custom = overrides[route];
    if (custom) {
      return custom(url, init);
    }
    if (route === STOCK_GET) {
      return respond(200, state.stock);
    }
    if (route === STOCK_PUT) {
      const body = JSON.parse(String(init.body)) as { quantity: number };
      state.stock = stock({ available: body.quantity, version: state.stock.version + 1 });
      return respond(200, { productId: 'p-1', quantity: body.quantity, version: state.stock.version, updatedAt: '2026-11-03T12:00:00Z' }, { ETag: `"${state.stock.version}"` });
    }
    return respond(404, { code: 'not-found' });
  };
}

function of(calls: Rendered['calls'], route: string) {
  return calls.filter((call) => `${call.init.method ?? 'GET'} ${call.url.pathname}` === route);
}

function field(): HTMLInputElement {
  return screen.getByLabelText(t('apiStock.quantity')) as HTMLInputElement;
}

function value(label: string): string | null | undefined {
  return screen.getByText(label).nextElementSibling?.textContent;
}

async function typeQuantity(user: ReturnType<typeof userEvent.setup>, text: string) {
  const input = await screen.findByLabelText(t('apiStock.quantity'));
  await user.clear(input);
  await user.type(input, text);
}

/** Сценарий конфликта: на экране остаток 10 (версия 4), пока человек вводит 15, остаток на сервере стал 7 (версия 5). */
function conflictServer(state: { stock: Stock }, onPut?: (attempt: number) => Response | undefined): Handler {
  let attempt = 0;
  return server(state, {
    [STOCK_PUT]: (_url, init) => {
      attempt += 1;
      const custom = onPut?.(attempt);
      if (custom) {
        return custom;
      }
      if (attempt === 1) {
        state.stock = stock({ available: 7, version: 5 });
        return respond(412, { code: 'version-conflict' });
      }
      const body = JSON.parse(String(init.body)) as { quantity: number };
      state.stock = stock({ available: body.quantity, version: state.stock.version + 1 });
      return respond(200, { productId: 'p-1', quantity: body.quantity, version: state.stock.version, updatedAt: '2026-11-03T12:00:00Z' }, { ETag: `"${state.stock.version}"` });
    },
  });
}

afterEach(() => {
  focusManager.setFocused(undefined);
});

describe('остаток по API: показ', () => {
  it('доступно к заказу, резерв, версия; в поле текущий остаток', async () => {
    renderScreen(<ApiStockSection productId="p-1" readOnly={false} />, { handler: server({ stock: stock() }) });
    expect(await screen.findByText(t('apiStock.available'))).toBeInTheDocument();
    expect(value(t('apiStock.available'))).toBe('10');
    expect(value(t('apiStock.reserved'))).toBe('2');
    expect(value(t('apiStock.version'))).toBe('4');
    expect(field().value).toBe('10');
  });

  it('ошибка загрузки: сообщение и повтор', async () => {
    renderScreen(<ApiStockSection productId="p-1" readOnly={false} />, { handler: server({ stock: stock() }, { [STOCK_GET]: () => respond(404, { code: 'not-found' }) }) });
    expect(await screen.findByText(t('problem.not-found'))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: t('state.retry') })).toBeInTheDocument();
  });

  it('товар в архиве: поле и кнопка недоступны', async () => {
    renderScreen(<ApiStockSection productId="p-1" readOnly />, { handler: server({ stock: stock() }) });
    await screen.findByText(t('apiStock.available'));
    expect(field()).toBeDisabled();
    expect(screen.getByRole('button', { name: t('apiStock.save') })).toBeDisabled();
  });
});

describe('остаток по API: сохранение', () => {
  it('значение заменяет остаток: PUT с If-Match по версии, сообщение, остаток перечитан', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(<ApiStockSection productId="p-1" readOnly={false} />, { handler: server({ stock: stock() }) });
    await typeQuantity(user, '25');
    await user.click(screen.getByRole('button', { name: t('apiStock.save') }));

    expect(await screen.findByText(t('apiStock.saved', { quantity: 25 }))).toBeInTheDocument();
    const put = of(calls, STOCK_PUT)[0];
    expect(put?.headers['If-Match']).toBe('"4"');
    expect(JSON.parse(String(put?.init.body))).toEqual({ quantity: 25 });
    await waitFor(() => expect(value(t('apiStock.available'))).toBe('25'));
    expect(value(t('apiStock.version'))).toBe('5');
    expect(field().value).toBe('25');
  });

  it('ноль допустим: остаток можно обнулить', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(<ApiStockSection productId="p-1" readOnly={false} />, { handler: server({ stock: stock() }) });
    await typeQuantity(user, '0');
    await user.click(screen.getByRole('button', { name: t('apiStock.save') }));
    await screen.findByText(t('apiStock.saved', { quantity: 0 }));
    expect(JSON.parse(String(of(calls, STOCK_PUT)[0]?.init.body))).toEqual({ quantity: 0 });
  });

  it('пусто, минус, дробь, буквы и больше миллиона не уходят на сервер', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(<ApiStockSection productId="p-1" readOnly={false} />, { handler: server({ stock: stock() }) });
    await screen.findByText(t('apiStock.available'));
    for (const bad of ['-1', '1,5', 'abc', '1000001']) {
      await typeQuantity(user, bad);
      await user.click(screen.getByRole('button', { name: t('apiStock.save') }));
      expect(await screen.findByText(t('apiStock.error.range')), bad).toBeInTheDocument();
    }
    await user.clear(field());
    await user.click(screen.getByRole('button', { name: t('apiStock.save') }));
    expect(await screen.findByText(t('field.required'))).toBeInTheDocument();
    expect(of(calls, STOCK_PUT)).toHaveLength(0);
  });

  it('409 «способ выдачи не подходит» и 422 с сервера: сообщение из словаря', async () => {
    const user = userEvent.setup();
    let answer: Response = respond(409, { code: 'issuance-method-mismatch' });
    renderScreen(<ApiStockSection productId="p-1" readOnly={false} />, { handler: server({ stock: stock() }, { [STOCK_PUT]: () => answer }) });
    await typeQuantity(user, '5');
    await user.click(screen.getByRole('button', { name: t('apiStock.save') }));
    expect(await screen.findByText(t('problem.issuance-method-mismatch'))).toBeInTheDocument();

    answer = respond(422, { code: 'validation-failed', errors: [{ pointer: '/quantity', code: 'out_of_range' }] });
    await user.click(screen.getByRole('button', { name: t('apiStock.save') }));
    expect(await screen.findByText(t('field.out_of_range'))).toBeInTheDocument();
    expect(screen.getByText(t('problem.validation-failed'))).toBeInTheDocument();
  });
});

describe('остаток по API: конфликт версий (412, ADR-008)', () => {
  it('показывает «сейчас 7, было 10», сохраняет введённое значение и не повторяет запрос без подтверждения', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(<ApiStockSection productId="p-1" readOnly={false} />, { handler: conflictServer({ stock: stock() }) });
    await typeQuantity(user, '15');
    await user.click(screen.getByRole('button', { name: t('apiStock.save') }));

    const dialog = await screen.findByRole('alertdialog', { name: t('apiStock.conflict.heading') });
    expect(within(dialog).getByText(t('apiStock.conflict.state', { now: 7, was: 10 }))).toBeInTheDocument();
    expect(within(dialog).getByText(t('apiStock.conflict.choice', { mine: 15 }))).toBeInTheDocument();
    // Введённое не потеряно, актуальный остаток показан, второго запроса нет
    expect(field().value).toBe('15');
    expect(value(t('apiStock.available'))).toBe('7');
    expect(of(calls, STOCK_PUT)).toHaveLength(1);
    // Пока выбор не сделан, поле и кнопка сохранения недоступны
    expect(field()).toBeDisabled();
    expect(screen.getByRole('button', { name: t('apiStock.save') })).toBeDisabled();
  });

  it('безопасный выбор по умолчанию: фокус на «Оставить текущее»', async () => {
    const user = userEvent.setup();
    renderScreen(<ApiStockSection productId="p-1" readOnly={false} />, { handler: conflictServer({ stock: stock() }) });
    await typeQuantity(user, '15');
    await user.click(screen.getByRole('button', { name: t('apiStock.save') }));
    const dialog = await screen.findByRole('alertdialog');
    expect(within(dialog).getByRole('button', { name: t('apiStock.conflict.keep', { now: 7 }) })).toHaveFocus();
  });

  it('«Применить моё значение»: новый PUT с версией актуального остатка и введённым числом', async () => {
    const user = userEvent.setup();
    const state = { stock: stock() };
    const { calls } = renderScreen(<ApiStockSection productId="p-1" readOnly={false} />, { handler: conflictServer(state) });
    await typeQuantity(user, '15');
    await user.click(screen.getByRole('button', { name: t('apiStock.save') }));
    const dialog = await screen.findByRole('alertdialog');
    await user.click(within(dialog).getByRole('button', { name: t('apiStock.conflict.apply', { mine: 15 }) }));

    expect(await screen.findByText(t('apiStock.saved', { quantity: 15 }))).toBeInTheDocument();
    const puts = of(calls, STOCK_PUT);
    expect(puts).toHaveLength(2);
    expect(puts[0]?.headers['If-Match']).toBe('"4"');
    expect(puts[1]?.headers['If-Match']).toBe('"5"');
    expect(JSON.parse(String(puts[1]?.init.body))).toEqual({ quantity: 15 });
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    await waitFor(() => expect(value(t('apiStock.available'))).toBe('15'));
  });

  it('«Оставить текущее»: второго запроса нет, в поле актуальный остаток', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(<ApiStockSection productId="p-1" readOnly={false} />, { handler: conflictServer({ stock: stock() }) });
    await typeQuantity(user, '15');
    await user.click(screen.getByRole('button', { name: t('apiStock.save') }));
    const dialog = await screen.findByRole('alertdialog');
    await user.click(within(dialog).getByRole('button', { name: t('apiStock.conflict.keep', { now: 7 }) }));

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(of(calls, STOCK_PUT)).toHaveLength(1);
    expect(field().value).toBe('7');
    await waitFor(() => expect(field()).toHaveFocus());
  });

  it('Escape равносилен «Оставить текущее»', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(<ApiStockSection productId="p-1" readOnly={false} />, { handler: conflictServer({ stock: stock() }) });
    await typeQuantity(user, '15');
    await user.click(screen.getByRole('button', { name: t('apiStock.save') }));
    await screen.findByRole('alertdialog');
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(of(calls, STOCK_PUT)).toHaveLength(1);
    expect(field().value).toBe('7');
  });

  it('остаток снова изменился, пока человек выбирал: второй конфликт с новыми числами, без молчаливой перезаписи', async () => {
    const user = userEvent.setup();
    const state = { stock: stock() };
    const handler = conflictServer(state, (attempt) => {
      if (attempt === 2) {
        state.stock = stock({ available: 3, version: 6 });
        return respond(412, { code: 'version-conflict' });
      }
      return undefined;
    });
    const { calls } = renderScreen(<ApiStockSection productId="p-1" readOnly={false} />, { handler });
    await typeQuantity(user, '15');
    await user.click(screen.getByRole('button', { name: t('apiStock.save') }));
    await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: t('apiStock.conflict.apply', { mine: 15 }) }));

    const second = await screen.findByText(t('apiStock.conflict.state', { now: 3, was: 7 }));
    expect(second).toBeInTheDocument();
    expect(of(calls, STOCK_PUT)).toHaveLength(2);
    expect(field().value).toBe('15');
  });

  it('конфликт, а перечитать остаток не удалось: сообщение об ошибке, введённое значение остаётся', async () => {
    const user = userEvent.setup();
    let reads = 0;
    const state = { stock: stock() };
    renderScreen(<ApiStockSection productId="p-1" readOnly={false} />, {
      handler: server(state, {
        [STOCK_GET]: () => {
          reads += 1;
          return reads === 1 ? respond(200, state.stock) : respond(500, { code: 'internal-error' });
        },
        [STOCK_PUT]: () => respond(412, { code: 'version-conflict' }),
      }),
    });
    await typeQuantity(user, '15');
    await user.click(screen.getByRole('button', { name: t('apiStock.save') }));
    expect(await screen.findByText(t('problem.internal-error'))).toBeInTheDocument();
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(field().value).toBe('15');
  });

  it('версия для If-Match берётся с момента начала правки, а не из фонового обновления: конфликт всё равно виден', async () => {
    const user = userEvent.setup();
    const state = { stock: stock() };
    const { calls } = renderScreen(<ApiStockSection productId="p-1" readOnly={false} />, {
      handler: server(state, {
        // Как настоящий сервис: версия в If-Match должна совпасть с текущей
        [STOCK_PUT]: (_url, init) => {
          const sent = (init.headers as Record<string, string>)['If-Match'];
          return sent === `"${state.stock.version}"` ? respond(200, { productId: 'p-1', quantity: 12, version: 6, updatedAt: '2026-11-03T12:00:00Z' }, { ETag: '"6"' }) : respond(412, { code: 'version-conflict' });
        },
      }),
    });
    await typeQuantity(user, '12');
    // Остаток на сервере изменился, и фоновое обновление страницы подтянуло новую версию
    state.stock = stock({ available: 8, version: 5 });
    focusManager.setFocused(false);
    focusManager.setFocused(true);
    await waitFor(() => expect(value(t('apiStock.version'))).toBe('5'));
    expect(field().value).toBe('12');

    await user.click(screen.getByRole('button', { name: t('apiStock.save') }));
    const dialog = await screen.findByRole('alertdialog', { name: t('apiStock.conflict.heading') });
    expect(within(dialog).getByText(t('apiStock.conflict.state', { now: 8, was: 10 }))).toBeInTheDocument();
    expect(of(calls, STOCK_PUT)[0]?.headers['If-Match']).toBe('"4"');
  });
});
