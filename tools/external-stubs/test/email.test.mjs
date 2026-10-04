import test, { after, afterEach, before } from 'node:test';
import assert from 'node:assert/strict';
import { SECRETS, startHarness } from './harness.mjs';
import { verify } from '../src/signing.mjs';

let h;
before(async () => {
  h = await startHarness();
});
after(() => h.stop());
afterEach(() => h.reset());

const send = (sender, body = {}, headers = {}) =>
  h.api(sender === 'delivery' ? SECRETS.deliveryEmailKey : SECRETS.platformEmailKey)('POST', '/email/v1/messages', {
    headers,
    body: { to: 'buyer@example.test', subject: 'Ваш ключ', text: 'KEY-1\nKEY-2', ...body },
  });
const sigOk = (hook, secret) =>
  verify({ secrets: [secret], timestamp: hook.headers['x-webhook-timestamp'], body: hook.body, signature: hook.headers['x-webhook-signature'] }).ok;

test('режим «приём»: 202, затем вебхук «доставлено» на адрес и секретом отправителя', async () => {
  await h.setMode('email', { statusDelayMs: 0 });
  const a = await send('delivery', { messageId: 'dlv-1' });
  assert.equal(a.status, 202);
  assert.deepEqual(a.json, { messageId: 'dlv-1', status: 'accepted' });
  const b = await send('platform');
  assert.match(b.json.messageId, /^msg_t_/);
  await h.idle();
  const fromDelivery = h.hooksOf('/delivery-email');
  const fromPlatform = h.hooksOf('/platform-email');
  assert.equal(fromDelivery.length, 1);
  assert.equal(fromPlatform.length, 1);
  assert.equal(sigOk(fromDelivery[0], SECRETS.deliveryWebhookSecret), true);
  assert.equal(sigOk(fromPlatform[0], SECRETS.platformWebhookSecret), true);
  assert.equal(sigOk(fromDelivery[0], SECRETS.platformWebhookSecret), false, 'секреты источников разные');
  const event = JSON.parse(fromDelivery[0].body);
  assert.deepEqual(Object.keys(event).sort(), ['messageId', 'occurredAt', 'status']);
  assert.deepEqual([event.messageId, event.status], ['dlv-1', 'delivered']);
});

test('ключи проверяются, тело проверяется', async () => {
  assert.equal((await h.api('x')('POST', '/email/v1/messages', { body: {} })).status, 401);
  const bad = await send('delivery', { to: 'не адрес', subject: '', text: undefined, html: undefined });
  assert.equal(bad.status, 400);
  assert.ok(bad.json.error.details.length >= 3);
  assert.equal(h.state.emails.length, 0);
});

test('временная ошибка: первые N писем получают 503, затем письмо принимается', async () => {
  await h.setMode('email', { behavior: 'temp_error', failFirst: 2 });
  assert.equal((await send('delivery')).status, 503);
  assert.equal((await send('delivery')).status, 503);
  assert.equal((await send('delivery')).status, 202);
  assert.equal(h.state.emails.length, 1);
});

test('временная ошибка: 429 с Retry-After и тайм-аут', async () => {
  await h.setMode('email', { behavior: 'temp_error', tempKind: 'http_429' });
  const r = await send('delivery');
  assert.equal(r.status, 429);
  assert.equal(r.headers.get('retry-after'), '1');
  await h.setMode('email', { behavior: 'temp_error', tempKind: 'timeout', timeoutMs: 60, failFirst: 1 });
  await assert.rejects(send('delivery'));
  assert.equal((await send('delivery')).status, 202);
});

test('доля неудач детерминирована: одинаковая последовательность отказов после каждого сброса', async () => {
  const run = async () => {
    await h.setMode('email', { behavior: 'temp_error', failRate: 0.5 });
    const codes = [];
    for (let i = 0; i < 12; i++) codes.push((await send('delivery', { messageId: `m${i}` })).status);
    return codes;
  };
  await h.reset();
  const first = await run();
  await h.reset();
  const second = await run();
  assert.deepEqual(first, second);
  assert.ok(first.includes(503) && first.includes(202), `ожидались и отказы, и приёмы: ${first}`);
});

test('окончательная ошибка: 422 или 400 «адрес отклонён», письмо не хранится', async () => {
  await h.setMode('email', { behavior: 'perm_error' });
  const r = await send('delivery');
  assert.equal(r.status, 422);
  assert.equal(r.json.error.code, 'address_rejected');
  await h.setMode('email', { behavior: 'perm_error', permStatus: 400 });
  assert.equal((await send('delivery')).status, 400);
  assert.equal(h.state.emails.length, 0);
});

test('режим затрагивает только выбранного отправителя', async () => {
  await h.setMode('email', { behavior: 'temp_error', sender: 'delivery' });
  assert.equal((await send('delivery')).status, 503);
  assert.equal((await send('platform')).status, 202);
});

test('недоставка: статус «отказ» с причиной приходит вебхуком', async () => {
  await h.setMode('email', { status: 'failed', failReason: 'address_rejected', statusDelayMs: 0 });
  const r = await send('delivery', { messageId: 'f1' });
  await h.idle();
  const event = JSON.parse(h.hooksOf('/delivery-email')[0].body);
  assert.deepEqual(event, { messageId: 'f1', status: 'failed', occurredAt: event.occurredAt, reason: 'address_rejected' });
  const poll = await h.api(SECRETS.deliveryEmailKey)('GET', `/email/v1/messages/${r.json.messageId}`);
  assert.deepEqual(poll.json, { messageId: 'f1', status: 'failed', reason: 'address_rejected' });
});

test('недоставка: статуса нет вовсе, затем тест присылает «доставлено» командой', async () => {
  await h.setMode('email', { status: 'none' });
  const r = await send('delivery', { messageId: 'n1' });
  await h.idle();
  assert.equal(h.hooks.length, 0);
  const poll = await h.api(SECRETS.deliveryEmailKey)('GET', '/email/v1/messages/n1');
  assert.equal(poll.json.status, 'accepted');
  const late = await h.admin('POST', '/admin/emails/n1/status', { body: { status: 'delivered' } });
  assert.equal(late.status, 200);
  assert.equal(JSON.parse(h.hooksOf('/delivery-email')[0].body).status, 'delivered');
  assert.equal(r.status, 202);
});

test('вебхук статуса потерян, но опрос письма показывает итог (контроль доставки по ADR-011)', async () => {
  await h.setMode('email', { statusDelayMs: 0, webhook: { send: false } });
  await send('delivery', { messageId: 'p1' });
  await h.idle();
  assert.equal(h.hooks.length, 0);
  assert.equal((await h.api(SECRETS.deliveryEmailKey)('GET', '/email/v1/messages/p1')).json.status, 'delivered');
});

test('опрос видит только письма своего отправителя', async () => {
  await send('platform', { messageId: 'plat-1' });
  assert.equal((await h.api(SECRETS.deliveryEmailKey)('GET', '/email/v1/messages/plat-1')).status, 404);
  assert.equal((await h.api(SECRETS.platformEmailKey)('GET', '/email/v1/messages/plat-1')).status, 200);
});

test('повтор с тем же messageId: по умолчанию одно письмо, при dedupe=false второе (редкий дубль ADR-011)', async () => {
  await h.setMode('email', { statusDelayMs: 0 });
  await send('delivery', { messageId: 'dup' });
  const again = await send('delivery', { messageId: 'dup' });
  assert.equal(again.headers.get('idempotent-replayed'), 'true');
  assert.equal(h.state.emails.length, 1);
  assert.equal(h.state.emails[0].receivedCount, 2, 'повторный запрос виден в счётчике');
  await h.reset();
  await h.setMode('email', { statusDelayMs: 0, dedupe: false });
  await send('delivery', { messageId: 'dup' });
  await send('delivery', { messageId: 'dup' });
  await h.idle();
  assert.equal(h.state.emails.length, 2);
  assert.equal(h.hooksOf('/delivery-email').length, 2);
});

test('список писем для теста: фильтр по адресу и теме, число ключей видно в тексте', async () => {
  await send('delivery', { to: 'a@example.test', subject: 'Ключи заказа 1', text: 'K1\nK2\nK3' });
  await send('delivery', { to: 'b@example.test', subject: 'Другое', text: 'x' });
  const list = (await h.admin('GET', '/admin/emails?to=a@example.test&subject=Ключи')).json.items;
  assert.equal(list.length, 1);
  assert.equal(list[0].text.split('\n').length, 3);
  assert.equal(list[0].plan, undefined);
});

test('просмотр писем: список и страница письма экранируют содержимое', async () => {
  await send('delivery', { subject: '<b>Тема</b>', text: '<script>alert(1)</script>' });
  const list = await h.api()('GET', '/mail');
  assert.equal(list.status, 200);
  assert.match(list.text, /&lt;b&gt;Тема&lt;\/b&gt;/);
  assert.equal(list.text.includes('<script>'), false);
  const page = await h.api()('GET', '/mail/1');
  assert.match(page.text, /&lt;script&gt;alert\(1\)&lt;\/script&gt;/);
  assert.equal((await h.api()('GET', '/mail/99')).status, 404);
});
