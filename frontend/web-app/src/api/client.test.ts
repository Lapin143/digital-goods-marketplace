import { describe, expect, it, vi } from 'vitest';
import { buildUrl, createApiClient } from './client';
import { ApiError } from './problem';

function respond(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  const json = body !== undefined && typeof body !== 'string';
  return new Response(body === undefined ? null : json ? JSON.stringify(body) : body, {
    status,
    headers: { ...(json ? { 'Content-Type': 'application/json' } : {}), ...headers },
  });
}

/** Ответ сервера или сбой (`{ fail }`: fetch отклонён, например нет сети). */
function setup(outcome: Response | { fail: unknown }, extra: { onUnauthorized?: (kind: string) => void } = {}) {
  const fetchImpl = vi.fn<typeof fetch>(() => ('fail' in outcome ? Promise.reject(outcome.fail) : Promise.resolve(outcome)));
  const client = createApiClient({
    token: (kind) => (kind === 'web' ? 'web-token' : kind === 'staff' ? 'staff-token' : undefined),
    onUnauthorized: extra.onUnauthorized,
    fetchImpl,
    now: () => Date.parse('2026-10-17T12:00:00.000Z'),
    newKey: () => 'generated-key',
  });
  const call = () => {
    const [url, init] = fetchImpl.mock.calls[0] ?? [];
    return { url: String(url), init: init ?? {}, headers: (init?.headers ?? {}) as Record<string, string> };
  };
  return { client, fetchImpl, call };
}

describe('сборка адреса', () => {
  it('пропускает пустые значения и кодирует остальные', () => {
    expect(buildUrl('/api/v1/products', { q: 'лунная тропа', limit: 20, platform: undefined, region: '' })).toBe(
      '/api/v1/products?q=%D0%BB%D1%83%D0%BD%D0%BD%D0%B0%D1%8F+%D1%82%D1%80%D0%BE%D0%BF%D0%B0&limit=20',
    );
    expect(buildUrl('/api/v1/products', {})).toBe('/api/v1/products');
  });
});

describe('запрос', () => {
  it('подписывает токеном выбранного клиента входа', async () => {
    const { client, call } = setup(respond(200, { ok: true }));
    await client.get('/api/v1/seller/products', { auth: 'staff' });
    expect(call().headers.Authorization).toBe('Bearer staff-token');
  });

  it('открытый запрос идёт без токена', async () => {
    const { client, call } = setup(respond(200, { items: [] }));
    await client.get('/api/v1/products', { auth: 'none' });
    expect(call().headers.Authorization).toBeUndefined();
  });

  it('добавляет Idempotency-Key и If-Match, тело уходит JSON', async () => {
    const { client, call } = setup(respond(200, { status: 'published' }, { ETag: '"8"' }));
    const response = await client.post('/api/v1/staff/products/1/approve', { reason: 'ок' }, { auth: 'staff', idempotent: true, ifMatch: '"7"' });
    const { headers, init } = call();
    expect(headers['Idempotency-Key']).toBe('generated-key');
    expect(headers['If-Match']).toBe('"7"');
    expect(headers['Content-Type']).toBe('application/json');
    expect(init.body).toBe(JSON.stringify({ reason: 'ок' }));
    expect(response.etag).toBe('"8"');
    expect(response.data).toEqual({ status: 'published' });
  });

  it('держит ключ идемпотентности, который дали снаружи (двойное нажатие)', async () => {
    const { client, call } = setup(respond(200, {}));
    await client.post('/api/v1/x', {}, { idempotencyKey: 'dialog-key' });
    expect(call().headers['Idempotency-Key']).toBe('dialog-key');
  });

  it('файл уходит как есть с типом из Blob', async () => {
    const { client, call } = setup(respond(200, { accepted: 1 }));
    const file = new Blob(['A-1\n'], { type: 'text/csv' });
    await client.post('/api/v1/seller/products/1/key-files', file, { auth: 'staff', idempotent: true });
    expect(call().headers['Content-Type']).toBe('text/csv');
    expect(call().init.body).toBe(file);
  });

  it('204 даёт пустые данные', async () => {
    const { client } = setup(respond(204, undefined));
    const response = await client.del('/api/v1/x', { auth: 'staff' });
    expect(response.status).toBe(204);
    expect(response.data).toBeUndefined();
  });
});

describe('ошибки', () => {
  it('разбирает problem+json и сообщает о 401', async () => {
    const onUnauthorized = vi.fn();
    const { client } = setup(respond(401, { code: 'unauthenticated', status: 401 }), { onUnauthorized });
    await expect(client.get('/api/v1/seller/products', { auth: 'staff' })).rejects.toMatchObject({ status: 401, code: 'unauthenticated' });
    expect(onUnauthorized).toHaveBeenCalledWith('staff');
  });

  it('открытый запрос с 401 сессию не трогает', async () => {
    const onUnauthorized = vi.fn();
    const { client } = setup(respond(401, { code: 'unauthenticated' }), { onUnauthorized });
    await expect(client.get('/api/v1/x', { auth: 'none' })).rejects.toBeInstanceOf(ApiError);
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it('429 несёт время ожидания из Retry-After', async () => {
    const { client } = setup(respond(429, { code: 'rate-limited' }, { 'Retry-After': '17' }));
    await expect(client.get('/api/v1/x', { auth: 'none' })).rejects.toMatchObject({ status: 429, retryAfterMs: 17_000 });
  });

  it('тело не JSON не ломает разбор', async () => {
    const { client } = setup(respond(502, '<html>Bad Gateway</html>'));
    await expect(client.get('/api/v1/x', { auth: 'none' })).rejects.toMatchObject({ status: 502, code: 'internal-error' });
  });

  it('сбой сети становится ошибкой со статусом 0', async () => {
    const { client } = setup({ fail: new TypeError('Failed to fetch') });
    await expect(client.get('/api/v1/x', { auth: 'none' })).rejects.toMatchObject({ status: 0, code: 'network-error' });
  });

  it('отмена запроса остаётся отменой', async () => {
    const { client } = setup({ fail: new DOMException('aborted', 'AbortError') });
    await expect(client.get('/api/v1/x', { auth: 'none' })).rejects.toMatchObject({ name: 'AbortError' });
  });
});
