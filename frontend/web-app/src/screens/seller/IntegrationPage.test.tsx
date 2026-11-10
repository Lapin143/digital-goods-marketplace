import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { t } from '../../i18n';
import { renderScreen, respond } from '../../test/harness';
import type { Handler, Rendered } from '../../test/harness';
import { formatMoment } from '../../ui/format';
import { IntegrationPage } from './IntegrationPage';

const SCREEN = (
  <Routes>
    <Route path="/seller/integration" element={<IntegrationPage />} />
  </Routes>
);
const ROUTE = '/seller/integration';
const GET_INTEGRATION = 'GET /api/v1/seller/integration';
const PUT_INTEGRATION = 'PUT /api/v1/seller/integration';
const ROTATE = 'POST /api/v1/seller/integration/secret-rotation';
const DELIVERIES = 'GET /api/v1/seller/webhook-deliveries';
const SECRET_FIRST = 'a1'.repeat(32);
const SECRET_NEW = 'b2'.repeat(32);

function integration(overrides: Record<string, unknown> = {}) {
  return { endpointUrl: null, status: 'disabled', secretVersion: 0, version: 0, updatedAt: null, ...overrides };
}

const CONNECTED = { endpointUrl: 'https://shop.example/dgm', status: 'active', secretVersion: 1, version: 1, updatedAt: '2026-11-02T10:00:00Z' };

function delivery(id: string, overrides: Record<string, unknown> = {}) {
  return {
    deliveryId: `d-${id}`,
    eventId: `e-${id}`,
    type: 'order.issued',
    orderNumber: 1042,
    status: 'failed',
    attempts: 6,
    lastResponseCode: 503,
    lastAttemptAt: '2026-11-02T12:00:00Z',
    nextAttemptAt: null,
    createdAt: '2026-11-02T10:00:00Z',
    ...overrides,
  };
}

interface State {
  integration: ReturnType<typeof integration>;
  deliveries: Array<ReturnType<typeof delivery>>;
}

function etag(version: unknown): Record<string, string> {
  return { ETag: `"${String(version)}"` };
}

/** Сервер подключения: PUT применяет изменения (первое подключение выдаёт секрет), смена секрета поднимает версию, список по фильтру статуса. */
function server(state: State, overrides: Record<string, Handler> = {}): Handler {
  return (url, init) => {
    const route = `${init.method ?? 'GET'} ${url.pathname}`;
    const custom = overrides[route];
    if (custom) {
      return custom(url, init);
    }
    if (route === GET_INTEGRATION) {
      return respond(200, state.integration, etag(state.integration.version));
    }
    if (route === PUT_INTEGRATION) {
      const body = JSON.parse(String(init.body)) as { endpointUrl: string; status: string };
      const first = state.integration.secretVersion === 0;
      state.integration = { ...state.integration, endpointUrl: body.endpointUrl, status: body.status, secretVersion: first ? 1 : state.integration.secretVersion, version: state.integration.version + 1, updatedAt: '2026-11-03T10:00:00Z' };
      return respond(200, first ? { integration: state.integration, secret: SECRET_FIRST } : { integration: state.integration }, etag(state.integration.version));
    }
    if (route === ROTATE) {
      state.integration = { ...state.integration, secretVersion: state.integration.secretVersion + 1, version: state.integration.version + 1 };
      return respond(200, { secretVersion: state.integration.secretVersion, secret: SECRET_NEW, shownOnce: true });
    }
    if (route === DELIVERIES) {
      const status = url.searchParams.get('status');
      const items = status ? state.deliveries.filter((item) => status.split(',').includes(item.status)) : state.deliveries;
      return respond(200, { items, page: { limit: 20, nextCursor: null } });
    }
    return respond(404, { code: 'not-found' });
  };
}

function calls_of(calls: Rendered['calls'], route: string) {
  return calls.filter((call) => `${call.init.method ?? 'GET'} ${call.url.pathname}` === route);
}

function storageText(): string {
  return JSON.stringify([{ ...window.localStorage }, { ...window.sessionStorage }]);
}

async function closeSecret(user: ReturnType<typeof userEvent.setup>) {
  const panel = await screen.findByRole('alertdialog');
  await user.click(within(panel).getByRole('checkbox', { name: t('secret.saved') }));
  await user.click(within(panel).getByRole('button', { name: t('secret.close') }));
  await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument());
}

describe('подключение', () => {
  it('подключения нет: «Не подключено», секрета нет, кнопки смены секрета нет, статус по умолчанию «Действует»', async () => {
    renderScreen(SCREEN, { route: ROUTE, handler: server({ integration: integration(), deliveries: [] }) });
    expect(await screen.findByText(t('integration.state.none'))).toBeInTheDocument();
    expect(screen.getByText(t('integration.state.noSecret'))).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('integration.rotate') })).not.toBeInTheDocument();
    expect(screen.getByLabelText(t('integration.status'))).toHaveValue('active');
    expect(screen.getByLabelText(t('integration.endpoint'))).toHaveValue('');
  });

  it('подключение есть: адрес в поле, статус, версия секрета, время изменения', async () => {
    renderScreen(SCREEN, { route: ROUTE, handler: server({ integration: integration(CONNECTED), deliveries: [] }) });
    expect(await screen.findByDisplayValue('https://shop.example/dgm')).toBeInTheDocument();
    expect(screen.getByLabelText(t('integration.status'))).toHaveValue('active');
    expect(screen.getByText(t('integration.state.secretVersion')).nextElementSibling).toHaveTextContent('1');
    expect(screen.getByText(formatMoment('2026-11-02T10:00:00Z'))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: t('integration.rotate') })).toBeInTheDocument();
  });

  it('ошибка загрузки подключения: сообщение и повтор', async () => {
    renderScreen(SCREEN, { route: ROUTE, handler: server({ integration: integration(), deliveries: [] }, { [GET_INTEGRATION]: () => respond(500, { code: 'internal-error' }) }) });
    expect(await screen.findByText(t('problem.internal-error'))).toBeInTheDocument();
    expect(screen.queryByLabelText(t('integration.endpoint'))).not.toBeInTheDocument();
  });
});

describe('сохранение адреса', () => {
  it('первое подключение: If-Match "0", секрет показан один раз, после закрытия панели секрета нигде нет, фокус на «Сохранить»', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler: server({ integration: integration(), deliveries: [] }) });
    await user.type(await screen.findByLabelText(t('integration.endpoint')), 'https://shop.example/dgm');
    await user.click(screen.getByRole('button', { name: t('integration.save') }));

    const panel = await screen.findByRole('alertdialog', { name: t('integration.secret.first') });
    expect(within(panel).getByText(SECRET_FIRST)).toBeInTheDocument();
    expect(within(panel).getByText(t('integration.secret.warning'))).toBeInTheDocument();
    expect(screen.getByText(t('integration.saved.first'))).toBeInTheDocument();
    const put = calls_of(calls, PUT_INTEGRATION)[0];
    expect(put?.headers['If-Match']).toBe('"0"');
    expect(JSON.parse(String(put?.init.body))).toEqual({ endpointUrl: 'https://shop.example/dgm', status: 'active' });
    // Пока секрет на экране, подключение не меняется и не сменяется ещё раз
    expect(screen.getByRole('button', { name: t('integration.save') })).toBeDisabled();
    expect(screen.getByRole('button', { name: t('integration.rotate') })).toBeDisabled();
    expect(storageText()).not.toContain(SECRET_FIRST);

    await closeSecret(user);
    expect(document.body.textContent).not.toContain(SECRET_FIRST);
    expect(storageText()).not.toContain(SECRET_FIRST);
    await waitFor(() => expect(screen.getByRole('button', { name: t('integration.save') })).toHaveFocus());
  });

  it('следующее сохранение идёт с версией из ответа и без секрета: панели нет', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler: server({ integration: integration(), deliveries: [] }) });
    await user.type(await screen.findByLabelText(t('integration.endpoint')), 'https://shop.example/dgm');
    await user.click(screen.getByRole('button', { name: t('integration.save') }));
    await closeSecret(user);

    await user.selectOptions(screen.getByLabelText(t('integration.status')), 'disabled');
    await user.click(screen.getByRole('button', { name: t('integration.save') }));
    expect(await screen.findByText(t('integration.saved'))).toBeInTheDocument();
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    const puts = calls_of(calls, PUT_INTEGRATION);
    expect(puts).toHaveLength(2);
    expect(puts[1]?.headers['If-Match']).toBe('"1"');
    expect(JSON.parse(String(puts[1]?.init.body)).status).toBe('disabled');
  });

  it('адрес без https не уходит на сервер: подсказка у поля', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler: server({ integration: integration(), deliveries: [] }) });
    await user.type(await screen.findByLabelText(t('integration.endpoint')), 'http://shop.example');
    await user.click(screen.getByRole('button', { name: t('integration.save') }));
    expect(await screen.findByText(t('integration.error.https'))).toBeInTheDocument();
    expect(calls_of(calls, PUT_INTEGRATION)).toHaveLength(0);
  });

  it('пустой адрес не уходит на сервер', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler: server({ integration: integration(), deliveries: [] }) });
    await user.click(await screen.findByRole('button', { name: t('integration.save') }));
    expect(await screen.findByText(t('field.required'))).toBeInTheDocument();
    expect(calls_of(calls, PUT_INTEGRATION)).toHaveLength(0);
  });

  it('422 endpoint-not-allowed: правило объяснено у поля, подробностей сервера на экране нет, введённый адрес остаётся', async () => {
    const user = userEvent.setup();
    renderScreen(SCREEN, {
      route: ROUTE,
      handler: server(
        { integration: integration(), deliveries: [] },
        { [PUT_INTEGRATION]: () => respond(422, { code: 'endpoint-not-allowed', detail: 'Адрес 10.0.0.5 относится к внутренней сети' }) },
      ),
    });
    await user.type(await screen.findByLabelText(t('integration.endpoint')), 'https://intranet.example/dgm');
    await user.click(screen.getByRole('button', { name: t('integration.save') }));
    expect(await screen.findByText(t('problem.endpoint-not-allowed'))).toBeInTheDocument();
    expect(document.body.textContent).not.toContain('10.0.0.5');
    expect(screen.getByLabelText(t('integration.endpoint'))).toHaveValue('https://intranet.example/dgm');
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
  });

  it('412: введённое остаётся в поле, версия перечитывается, повторное сохранение идёт с новой версией', async () => {
    const user = userEvent.setup();
    const state: State = { integration: integration(CONNECTED), deliveries: [] };
    let attempt = 0;
    const { calls } = renderScreen(SCREEN, {
      route: ROUTE,
      handler: server(state, {
        [PUT_INTEGRATION]: () => {
          attempt += 1;
          if (attempt === 1) {
            // Пока человек правил форму, подключение изменили в другом окне
            state.integration = integration({ ...CONNECTED, endpointUrl: 'https://other.example/x', version: 2 });
            return respond(412, { code: 'version-conflict' });
          }
          state.integration = integration({ ...state.integration, endpointUrl: 'https://mine.example/dgm', version: 3 });
          return respond(200, { integration: state.integration }, etag(3));
        },
      }),
    });
    const field = await screen.findByDisplayValue('https://shop.example/dgm');
    await user.clear(field);
    await user.type(field, 'https://mine.example/dgm');
    await user.click(screen.getByRole('button', { name: t('integration.save') }));

    expect(await screen.findByText(t('integration.conflict'))).toBeInTheDocument();
    await waitFor(() => expect(calls_of(calls, GET_INTEGRATION)).toHaveLength(2));
    expect(screen.getByLabelText(t('integration.endpoint'))).toHaveValue('https://mine.example/dgm');

    await user.click(screen.getByRole('button', { name: t('integration.save') }));
    expect(await screen.findByText(t('integration.saved'))).toBeInTheDocument();
    const puts = calls_of(calls, PUT_INTEGRATION);
    expect(puts).toHaveLength(2);
    expect(puts[0]?.headers['If-Match']).toBe('"1"');
    expect(puts[1]?.headers['If-Match']).toBe('"2"');
  });

  it('403 без полного входа: сообщение из словаря', async () => {
    const user = userEvent.setup();
    renderScreen(SCREEN, {
      route: ROUTE,
      handler: server({ integration: integration(), deliveries: [] }, { [PUT_INTEGRATION]: () => respond(403, { code: 'second-factor-required' }) }),
    });
    await user.type(await screen.findByLabelText(t('integration.endpoint')), 'https://shop.example/dgm');
    await user.click(screen.getByRole('button', { name: t('integration.save') }));
    expect(await screen.findByText(t('problem.second-factor-required'))).toBeInTheDocument();
  });
});

describe('смена секрета', () => {
  it('нужно подтверждение: «Не менять» закрывает панель без запроса, фокус возвращается на кнопку смены', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler: server({ integration: integration(CONNECTED), deliveries: [] }) });
    const open = await screen.findByRole('button', { name: t('integration.rotate') });
    await user.click(open);

    const dialog = await screen.findByRole('alertdialog', { name: t('integration.rotate.heading') });
    expect(within(dialog).getByText(t('integration.rotate.text'))).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: t('integration.rotate.cancel') })).toHaveFocus();
    await user.click(within(dialog).getByRole('button', { name: t('integration.rotate.cancel') }));

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(calls_of(calls, ROTATE)).toHaveLength(0);
    await waitFor(() => expect(screen.getByRole('button', { name: t('integration.rotate') })).toHaveFocus());
  });

  it('подтверждение: запрос с Idempotency-Key, новый секрет показан один раз, версия секрета обновлена, после закрытия секрета нет', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler: server({ integration: integration(CONNECTED), deliveries: [] }) });
    await user.click(await screen.findByRole('button', { name: t('integration.rotate') }));
    await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: t('integration.rotate.confirm') }));

    const panel = await screen.findByRole('alertdialog', { name: t('integration.secret.rotated', { version: 2 }) });
    expect(within(panel).getByText(SECRET_NEW)).toBeInTheDocument();
    expect(calls_of(calls, ROTATE)[0]?.headers['Idempotency-Key']).toBe('key-1');
    await waitFor(() => expect(screen.getByText(t('integration.state.secretVersion')).nextElementSibling).toHaveTextContent('2'));
    expect(storageText()).not.toContain(SECRET_NEW);

    await closeSecret(user);
    expect(document.body.textContent).not.toContain(SECRET_NEW);
    expect(storageText()).not.toContain(SECRET_NEW);
    await waitFor(() => expect(screen.getByRole('button', { name: t('integration.rotate') })).toHaveFocus());
  });

  it('повтор вернул версию без секрета: объяснение, панели нет', async () => {
    const user = userEvent.setup();
    renderScreen(SCREEN, {
      route: ROUTE,
      handler: server({ integration: integration(CONNECTED), deliveries: [] }, { [ROTATE]: () => respond(200, { secretVersion: 2, shownOnce: true }) }),
    });
    await user.click(await screen.findByRole('button', { name: t('integration.rotate') }));
    await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: t('integration.rotate.confirm') }));
    expect(await screen.findByText(t('integration.rotate.lost'))).toBeInTheDocument();
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
  });

  it('сбой сети: повтор смены уходит с тем же Idempotency-Key', async () => {
    const user = userEvent.setup();
    let attempt = 0;
    const { calls } = renderScreen(SCREEN, {
      route: ROUTE,
      handler: server(
        { integration: integration(CONNECTED), deliveries: [] },
        {
          [ROTATE]: () => {
            attempt += 1;
            return attempt === 1 ? { fail: new TypeError('network') } : respond(200, { secretVersion: 2, secret: SECRET_NEW, shownOnce: true });
          },
        },
      ),
    });
    await user.click(await screen.findByRole('button', { name: t('integration.rotate') }));
    await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: t('integration.rotate.confirm') }));
    expect(await screen.findByText(`${t('problem.network')} ${t('integration.rotate.retryHint')}`)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: t('integration.rotate') }));
    await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: t('integration.rotate.confirm') }));
    expect(await screen.findByText(SECRET_NEW)).toBeInTheDocument();
    const posts = calls_of(calls, ROTATE);
    expect(posts).toHaveLength(2);
    expect(posts[0]?.headers['Idempotency-Key']).toBe('key-1');
    expect(posts[1]?.headers['Idempotency-Key']).toBe('key-1');
  });
});

describe('недоставленные уведомления', () => {
  const FAILED = delivery('1', { orderNumber: 1042, type: 'order.issued', lastResponseCode: 503, attempts: 6 });
  const NO_ANSWER = delivery('2', { orderNumber: 1043, type: 'order.paid', lastResponseCode: null, attempts: 6 });
  const QUEUED = delivery('3', { orderNumber: 1044, type: 'order.cancelled', status: 'queued', attempts: 2, lastResponseCode: 500, nextAttemptAt: '2026-11-02T13:00:00Z' });

  it('по умолчанию только недоставленные: номер заказа, событие, статус, попытки, код ответа или «нет ответа»', async () => {
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler: server({ integration: integration(CONNECTED), deliveries: [FAILED, NO_ANSWER, QUEUED] }) });
    const table = await screen.findByRole('table', { name: t('integration.deliveries.caption') });
    expect(calls_of(calls, DELIVERIES)[0]?.url.searchParams.get('status')).toBe('failed');
    expect(within(table).getByText('1042')).toBeInTheDocument();
    expect(within(table).getByText(t('integration.delivery.type.order.issued'))).toBeInTheDocument();
    expect(within(table).getByText('503')).toBeInTheDocument();
    expect(within(table).getByText('1043')).toBeInTheDocument();
    expect(within(table).getByText(t('integration.delivery.noResponse'))).toBeInTheDocument();
    expect(within(table).getAllByText(t('integration.delivery.status.failed'))).toHaveLength(2);
    expect(within(table).queryByText('1044')).not.toBeInTheDocument();
  });

  it('фильтр «Ждёт отправки» показывает время следующей попытки, «Все» снимает фильтр', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler: server({ integration: integration(CONNECTED), deliveries: [FAILED, QUEUED] }) });
    await screen.findByRole('table', { name: t('integration.deliveries.caption') });
    const filter = screen.getByLabelText(t('integration.deliveries.filter'));

    await user.selectOptions(filter, 'queued');
    const table = await screen.findByRole('table', { name: t('integration.deliveries.caption') });
    await waitFor(() => expect(within(table).getByText('1044')).toBeInTheDocument());
    expect(within(table).getByText(formatMoment('2026-11-02T13:00:00Z'))).toBeInTheDocument();
    expect(calls_of(calls, DELIVERIES).some((call) => call.url.searchParams.get('status') === 'queued')).toBe(true);

    await user.selectOptions(filter, '');
    await waitFor(() => expect(calls_of(calls, DELIVERIES).some((call) => call.url.searchParams.get('status') === null)).toBe(true));
    await waitFor(() => expect(within(screen.getByRole('table', { name: t('integration.deliveries.caption') })).getByText('1042')).toBeInTheDocument());
  });

  it('недоставленных нет: пустое состояние; для другого фильтра свой текст', async () => {
    const user = userEvent.setup();
    renderScreen(SCREEN, { route: ROUTE, handler: server({ integration: integration(CONNECTED), deliveries: [] }) });
    expect(await screen.findByText(t('integration.deliveries.emptyFailed'))).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText(t('integration.deliveries.filter')), 'sent');
    expect(await screen.findByText(t('integration.deliveries.empty'))).toBeInTheDocument();
  });

  it('ошибка списка не мешает подключению: поле адреса на месте, у списка сообщение и повтор', async () => {
    renderScreen(SCREEN, {
      route: ROUTE,
      handler: server({ integration: integration(CONNECTED), deliveries: [] }, { [DELIVERIES]: () => respond(500, { code: 'internal-error' }) }),
    });
    expect(await screen.findByText(t('problem.internal-error'))).toBeInTheDocument();
    expect(screen.getByLabelText(t('integration.endpoint'))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: t('state.retry') })).toBeInTheDocument();
  });

  it('объяснение лестницы повторов и отсутствия ручного повтора', async () => {
    renderScreen(SCREEN, { route: ROUTE, handler: server({ integration: integration(CONNECTED), deliveries: [] }) });
    expect(await screen.findByText(t('integration.deliveries.lead'))).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /повтор/i })).not.toBeInTheDocument();
  });
});
