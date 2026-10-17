import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { t } from '../../i18n';
import { renderScreen, respond } from '../../test/harness';
import type { Handler } from '../../test/harness';
import { KeysPage } from './KeysPage';

const SCREEN = (
  <Routes>
    <Route path="/seller/products/:productId/keys" element={<KeysPage />} />
  </Routes>
);
const ROUTE = '/seller/products/p-1/keys';

const STOCK = { productId: 'p-1', available: 12, reserved: 3, issued: 5, poolSize: 20, poolLimit: 100000, updatedAt: '2026-10-17T09:00:00Z' };

function server(overrides: Record<string, Handler> = {}): Handler {
  return (url, init) => {
    const route = `${init.method ?? 'GET'} ${url.pathname}`;
    const custom = overrides[route];
    if (custom) {
      return custom(url, init);
    }
    if (route === 'GET /api/v1/seller/products/p-1/stock') {
      return respond(200, STOCK);
    }
    return respond(404, { code: 'not-found' });
  };
}

const REPORT = { accepted: 2, rejectedTotal: 1, rejected: [{ line: 3, reason: 'duplicate_in_upload' }], available: 14, poolLimit: 100000 };
const BATCH = 'POST /api/v1/seller/products/p-1/key-batches';
const FILES = 'POST /api/v1/seller/products/p-1/key-files';

function area(): HTMLTextAreaElement {
  return screen.getByLabelText(t('keys.list.label')) as HTMLTextAreaElement;
}

function value(label: string): string | null | undefined {
  return screen.getByText(label).nextElementSibling?.textContent;
}

describe('остаток', () => {
  it('показывает свободные, резерв, выданные и размер пула', async () => {
    renderScreen(SCREEN, { route: ROUTE, handler: server() });
    expect(await screen.findByText(t('keys.stock.available'))).toBeInTheDocument();
    expect(value(t('keys.stock.available'))).toBe('12');
    expect(value(t('keys.stock.reserved'))).toBe('3');
    expect(value(t('keys.stock.issued'))).toBe('5');
    expect(value(t('keys.stock.poolSize'))).toBe(t('keys.stock.ofLimit', { size: 20, limit: 100000 }));
  });

  it('«Обновить остаток» запрашивает данные заново', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler: server() });
    await user.click(await screen.findByRole('button', { name: t('keys.stock.refresh') }));
    await waitFor(() => expect(calls.filter((call) => call.url.pathname.endsWith('/stock'))).toHaveLength(2));
  });

  it('ошибка остатка: сообщение и повтор, загрузка ключей остаётся доступной', async () => {
    renderScreen(SCREEN, { route: ROUTE, handler: server({ 'GET /api/v1/seller/products/p-1/stock': () => respond(404, { code: 'not-found' }) }) });
    expect(await screen.findByText(t('problem.not-found'))).toBeInTheDocument();
    expect(area()).toBeInTheDocument();
  });
});

describe('загрузка ключей списком', () => {
  it('ключи уходят одним запросом с Idempotency-Key, поле очищается, в отчёте только номера строк и причины', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler: server({ [BATCH]: () => respond(200, REPORT) }) });
    await screen.findByText(t('keys.stock.available'));
    await user.type(area(), 'SECRET-AAA{Enter}SECRET-BBB{Enter}SECRET-AAA');
    await user.click(screen.getByRole('button', { name: t('keys.list.send') }));

    expect(await screen.findByText(t('keys.report.summary', { accepted: 2, rejected: 1, available: 14, limit: 100000 }))).toBeInTheDocument();
    const post = calls.find((call) => call.init.method === 'POST');
    expect(post?.headers['Idempotency-Key']).toBe('key-1');
    expect(JSON.parse(String(post?.init.body))).toEqual({ keys: ['SECRET-AAA', 'SECRET-BBB', 'SECRET-AAA'] });

    // Значения ключей не остаются на странице: поле пустое, в отчёте нет значений
    expect(area().value).toBe('');
    expect(document.body.textContent).not.toContain('SECRET');
    const table = screen.getByRole('table');
    expect(within(table).getByText('3')).toBeInTheDocument();
    expect(within(table).getByText(t('keys.reason.duplicate_in_upload'))).toBeInTheDocument();
  });

  it('после успешной загрузки остаток запрашивается заново', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler: server({ [BATCH]: () => respond(200, REPORT) }) });
    await screen.findByText(t('keys.stock.available'));
    await user.type(area(), 'K1');
    await user.click(screen.getByRole('button', { name: t('keys.list.send') }));
    await screen.findByText(t('keys.report.summary', { accepted: 2, rejected: 1, available: 14, limit: 100000 }));
    await waitFor(() => expect(calls.filter((call) => call.url.pathname.endsWith('/stock'))).toHaveLength(2));
  });

  it('пустое поле: сообщение, запроса нет', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler: server() });
    await user.click(await screen.findByRole('button', { name: t('keys.list.send') }));
    expect(await screen.findByText(t('keys.error.empty'))).toBeInTheDocument();
    expect(calls.every((call) => call.init.method !== 'POST')).toBe(true);
  });

  it('больше 10 000 строк: сообщение, запроса нет, текст остаётся в поле', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler: server() });
    await screen.findByText(t('keys.stock.available'));
    fireEvent.change(area(), { target: { value: Array.from({ length: 10_001 }, (_, index) => `K${index}`).join('\n') } });
    await user.click(screen.getByRole('button', { name: t('keys.list.send') }));
    expect(await screen.findByText(t('keys.error.tooMany', { limit: 10_000 }))).toBeInTheDocument();
    expect(calls.every((call) => call.init.method !== 'POST')).toBe(true);
    expect(area().value).not.toBe('');
  });

  it('ровно 10 000 строк уходят', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler: server({ [BATCH]: () => respond(200, { ...REPORT, accepted: 10_000, rejectedTotal: 0, rejected: [] }) }) });
    await screen.findByText(t('keys.stock.available'));
    fireEvent.change(area(), { target: { value: Array.from({ length: 10_000 }, (_, index) => `K${index}`).join('\n') } });
    await user.click(screen.getByRole('button', { name: t('keys.list.send') }));
    await screen.findByText(t('keys.report.summary', { accepted: 10_000, rejected: 0, available: 14, limit: 100000 }));
    const post = calls.find((call) => call.init.method === 'POST');
    expect(JSON.parse(String(post?.init.body)).keys).toHaveLength(10_000);
  });

  it('сбой сети: повтор уходит с тем же Idempotency-Key и тем же телом, хотя поле уже пустое', async () => {
    const user = userEvent.setup();
    let attempt = 0;
    const { calls } = renderScreen(SCREEN, {
      route: ROUTE,
      handler: server({
        [BATCH]: () => {
          attempt += 1;
          return attempt === 1 ? { fail: new TypeError('network') } : respond(200, REPORT);
        },
      }),
    });
    await screen.findByText(t('keys.stock.available'));
    await user.type(area(), 'SECRET-AAA{Enter}SECRET-BBB');
    await user.click(screen.getByRole('button', { name: t('keys.list.send') }));

    expect(await screen.findByText(t('problem.network'))).toBeInTheDocument();
    expect(area().value).toBe('');
    expect(document.body.textContent).not.toContain('SECRET');
    await user.click(screen.getByRole('button', { name: t('keys.retry') }));
    await screen.findByText(t('keys.report.summary', { accepted: 2, rejected: 1, available: 14, limit: 100000 }));

    const posts = calls.filter((call) => call.init.method === 'POST');
    expect(posts).toHaveLength(2);
    expect(posts[0]?.headers['Idempotency-Key']).toBe('key-1');
    expect(posts[1]?.headers['Idempotency-Key']).toBe('key-1');
    expect(posts[1]?.init.body).toBe(posts[0]?.init.body);
    expect(screen.queryByRole('button', { name: t('keys.retry') })).not.toBeInTheDocument();
  });

  it('окончательный отказ (предел пула): повтора нет, просьба вставить ключи заново', async () => {
    const user = userEvent.setup();
    renderScreen(SCREEN, { route: ROUTE, handler: server({ [BATCH]: () => respond(409, { code: 'pool-limit-exceeded', limit: 100000 }) }) });
    await screen.findByText(t('keys.stock.available'));
    await user.type(area(), 'SECRET-AAA');
    await user.click(screen.getByRole('button', { name: t('keys.list.send') }));

    expect(await screen.findByText(`${t('problem.pool-limit-exceeded', { limit: 100000 })} ${t('keys.error.pasteAgain')}`)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('keys.retry') })).not.toBeInTheDocument();
    expect(document.body.textContent).not.toContain('SECRET');
  });

  it('очередная загрузка после отказа получает новый ключ', async () => {
    const user = userEvent.setup();
    let attempt = 0;
    const { calls } = renderScreen(SCREEN, {
      route: ROUTE,
      handler: server({
        [BATCH]: () => {
          attempt += 1;
          return attempt === 1 ? respond(422, { code: 'validation-failed' }) : respond(200, REPORT);
        },
      }),
    });
    await screen.findByText(t('keys.stock.available'));
    await user.type(area(), 'A');
    await user.click(screen.getByRole('button', { name: t('keys.list.send') }));
    await screen.findByText(new RegExp(t('problem.validation-failed')));
    await user.type(area(), 'B');
    await user.click(screen.getByRole('button', { name: t('keys.list.send') }));
    await screen.findByText(t('keys.report.summary', { accepted: 2, rejected: 1, available: 14, limit: 100000 }));
    expect(calls.filter((call) => call.init.method === 'POST').map((call) => call.headers['Idempotency-Key'])).toEqual(['key-1', 'key-2']);
  });

  it('длинный отчёт: сообщается, что показаны только первые строки', async () => {
    const user = userEvent.setup();
    const rejected = Array.from({ length: 100 }, (_, index) => ({ line: index + 1, reason: 'empty' }));
    renderScreen(SCREEN, { route: ROUTE, handler: server({ [BATCH]: () => respond(200, { accepted: 0, rejectedTotal: 150, rejected, available: 0, poolLimit: 100000 }) }) });
    await screen.findByText(t('keys.stock.available'));
    await user.type(area(), 'x');
    await user.click(screen.getByRole('button', { name: t('keys.list.send') }));
    expect(await screen.findByText(t('keys.report.firstOnly', { shown: 100, total: 150 }))).toBeInTheDocument();
    expect(within(screen.getByRole('table')).getAllByText(t('keys.reason.empty'))).toHaveLength(100);
  });
});

describe('загрузка ключей файлом CSV', () => {
  function csv(name = 'keys.csv', size = 64): File {
    const file = new File(['KEY-1\nKEY-2\n'], name, { type: 'text/csv' });
    Object.defineProperty(file, 'size', { value: size });
    return file;
  }

  it('файл уходит как есть с типом text/csv и Idempotency-Key, поле выбора очищается', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler: server({ [FILES]: () => respond(200, REPORT) }) });
    const file = csv();
    await user.upload(await screen.findByLabelText(t('keys.file.label')), file);

    expect(await screen.findByText(t('keys.report.summary', { accepted: 2, rejected: 1, available: 14, limit: 100000 }))).toBeInTheDocument();
    const post = calls.find((call) => call.init.method === 'POST');
    expect(post?.init.body).toBe(file);
    expect(post?.headers['Content-Type']).toBe('text/csv');
    expect(post?.headers['Idempotency-Key']).toBe('key-1');
    expect((screen.getByLabelText(t('keys.file.label')) as HTMLInputElement).value).toBe('');
  });

  it('файл больше 2 МБ отвергается до запроса', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler: server() });
    await user.upload(await screen.findByLabelText(t('keys.file.label')), csv('big.csv', 2 * 1024 * 1024 + 1));
    expect(await screen.findByText(t('keys.error.csvSize', { limit: 2 }))).toBeInTheDocument();
    expect(calls.every((call) => call.init.method !== 'POST')).toBe(true);
  });

  it('пустой файл отвергается до запроса', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler: server() });
    await user.upload(await screen.findByLabelText(t('keys.file.label')), new File([], 'empty.csv', { type: 'text/csv' }));
    expect(await screen.findByText(t('keys.error.csvEmpty'))).toBeInTheDocument();
    expect(calls.every((call) => call.init.method !== 'POST')).toBe(true);
  });

  it('файл другого типа отвергается до запроса', async () => {
    const user = userEvent.setup({ applyAccept: false });
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler: server() });
    await user.upload(await screen.findByLabelText(t('keys.file.label')), new File(['x'], 'keys.pdf', { type: 'application/pdf' }));
    expect(await screen.findByText(t('keys.error.csvType'))).toBeInTheDocument();
    expect(calls.every((call) => call.init.method !== 'POST')).toBe(true);
  });

  it('сбой сети при отправке файла: повтор с тем же ключом', async () => {
    const user = userEvent.setup();
    let attempt = 0;
    const { calls } = renderScreen(SCREEN, {
      route: ROUTE,
      handler: server({
        [FILES]: () => {
          attempt += 1;
          return attempt === 1 ? { fail: new TypeError('network') } : respond(200, REPORT);
        },
      }),
    });
    await user.upload(await screen.findByLabelText(t('keys.file.label')), csv());
    await user.click(await screen.findByRole('button', { name: t('keys.retry') }));
    await screen.findByText(t('keys.report.summary', { accepted: 2, rejected: 1, available: 14, limit: 100000 }));
    const keys = calls.filter((call) => call.init.method === 'POST').map((call) => call.headers['Idempotency-Key']);
    expect(keys).toEqual(['key-1', 'key-1']);
  });
});
