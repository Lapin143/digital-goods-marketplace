import test, { after, afterEach, before } from 'node:test';
import assert from 'node:assert/strict';
import { SECRETS, startHarness } from './harness.mjs';
import { extractCode } from '../src/sms.mjs';
import { verify } from '../src/signing.mjs';

let h;
before(async () => {
  h = await startHarness();
});
after(() => h.stop());
afterEach(() => h.reset());

const sms = (body = {}) => h.api(SECRETS.platformSmsKey)('POST', '/sms/v1/messages', { body: { to: '+79991234567', text: 'Код подтверждения: 482913', ...body } });
const inbox = async (all = false) => (await h.admin('GET', `/admin/sms?to=%2B79991234567${all ? '&all=1' : ''}`)).json.items;

test('код достаётся из текста SMS', () => {
  assert.equal(extractCode('Код подтверждения: 482913'), '482913');
  assert.equal(extractCode('Ваш код 1234. Никому не сообщайте'), '1234');
  assert.equal(extractCode('Позвоните 8005553535353535'), null, 'слишком длинная цепочка цифр не код');
  assert.equal(extractCode('без цифр'), null);
});

test('режим «код приходит»: SMS на «телефоне» с готовым кодом, затем вебхук «доставлено»', async () => {
  await h.setMode('sms', { statusDelayMs: 0 });
  const r = await sms();
  assert.equal(r.status, 202);
  await h.idle();
  const items = await inbox();
  assert.equal(items.length, 1);
  assert.equal(items[0].code, '482913');
  const hook = h.hooksOf('/platform-sms')[0];
  assert.equal(verify({ secrets: [SECRETS.platformWebhookSecret], timestamp: hook.headers['x-webhook-timestamp'], body: hook.body, signature: hook.headers['x-webhook-signature'] }).ok, true);
  const event = JSON.parse(hook.body);
  assert.deepEqual(Object.keys(event).sort(), ['messageId', 'occurredAt', 'status']);
  assert.equal(event.status, 'delivered');
});

test('режим «код не приходит»: запрос принят, на «телефоне» пусто, вебхука нет', async () => {
  await h.setMode('sms', { behavior: 'drop' });
  assert.equal((await sms()).status, 202);
  await h.idle();
  assert.equal((await inbox()).length, 0);
  assert.equal((await inbox(true)).length, 1);
  assert.equal(h.hooks.length, 0);
});

test('режим «код приходит поздно»: сначала пусто, после задержки появляется и приходит вебхук', async () => {
  await h.setMode('sms', { behavior: 'late', lateMs: 80, statusDelayMs: 0 });
  await sms();
  assert.equal((await inbox()).length, 0);
  assert.equal((await inbox(true))[0].delivery, 'pending');
  await h.idle();
  const items = await inbox();
  assert.equal(items.length, 1);
  assert.ok(items[0].deliveredAt);
  assert.equal(h.hooksOf('/platform-sms').length, 1);
});

test('отказ провайдера по номеру (422) и сбой провайдера (503)', async () => {
  await h.setMode('sms', { behavior: 'reject' });
  const r = await sms();
  assert.equal(r.status, 422);
  assert.equal(r.json.error.code, 'invalid_phone');
  await h.setMode('sms', { behavior: 'error' });
  assert.equal((await sms()).status, 503);
  assert.equal((await inbox(true)).length, 0);
});

test('ключ и тело проверяются', async () => {
  assert.equal((await h.api('x')('POST', '/sms/v1/messages', { body: {} })).status, 401);
  assert.equal((await h.api(SECRETS.deliveryEmailKey)('POST', '/sms/v1/messages', { body: {} })).status, 401, 'ключ почты не подходит');
  const bad = await sms({ to: '89991234567', text: '' });
  assert.equal(bad.status, 400);
  assert.equal(bad.json.error.details.length, 2);
});

test('статус SMS командой: «не доставлено»', async () => {
  await h.setMode('sms', { behavior: 'drop' });
  const r = await sms();
  const cmd = await h.admin('POST', `/admin/sms/${r.json.messageId}/status`, { body: { status: 'failed' } });
  assert.equal(cmd.status, 200);
  assert.equal(JSON.parse(h.hooks[0].body).status, 'failed');
});
