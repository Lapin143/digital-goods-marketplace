// Контрактный тест заглушки: каждое уведомление, которое она отправляет, обязано проходить схему из OpenAPI проекта.
// Схемы лежат в test/contract/webhook-schemas.json; их собирает и сверяет с OpenAPI tools/docs-checks/check_stub_contract.py.
import test, { after, afterEach, before } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { SECRETS, newPayment, startHarness } from './harness.mjs';
import { validate } from './contract/validate.mjs';
import { verify } from '../src/signing.mjs';

const contract = JSON.parse(readFileSync(new URL('./contract/webhook-schemas.json', import.meta.url), 'utf8'));
let h;
before(async () => {
  h = await startHarness();
});
after(() => h.stop());
afterEach(() => h.reset());

const PATHS = { payment: '/payment-gateway', deliveryEmail: '/delivery-email', platformEmail: '/platform-email', platformSms: '/platform-sms' };
const SECRET = { payment: SECRETS.paymentWebhookSecret, deliveryEmail: SECRETS.deliveryWebhookSecret, platformEmail: SECRETS.platformWebhookSecret, platformSms: SECRETS.platformWebhookSecret };

/** Все уведомления получателя проходят схему, заголовки и подпись соответствуют контракту. */
function assertConforms(key, count) {
  const hooks = h.hooksOf(PATHS[key]);
  assert.ok(hooks.length >= count, `${key}: ожидалось уведомлений не меньше ${count}, пришло ${hooks.length}`);
  for (const hook of hooks) {
    const errors = validate(contract.webhooks[key].schema, JSON.parse(hook.body));
    assert.deepEqual(errors, [], `${key}: ${hook.body}`);
    assert.ok(hook.headers[contract.signature.timestampHeader.toLowerCase()], 'метка времени в заголовке из контракта');
    assert.ok(hook.headers[contract.signature.header.toLowerCase()], 'подпись в заголовке из контракта');
    assert.equal(hook.headers['content-type'], 'application/json');
    assert.equal(
      verify({ secrets: [SECRET[key]], timestamp: hook.headers['x-webhook-timestamp'], body: hook.body, signature: hook.headers['x-webhook-signature'], toleranceMs: contract.signature.toleranceSeconds * 1000 }).ok,
      true,
    );
  }
  return hooks.map((x) => JSON.parse(x.body));
}

test('сам валидатор не пропускает дефекты: лишнее поле, чужой тип, плохая сумма, плохая метка времени', () => {
  const schema = contract.webhooks.payment.schema;
  const good = { eventId: 'e', type: 'payment.paid', occurredAt: '2026-10-03T12:00:00.123Z', data: { paymentId: 'p', orderId: '0199e0a0-0000-7000-8000-000000000301', amount: { amount: 1, currency: 'RUB' } } };
  assert.deepEqual(validate(schema, good), []);
  assert.ok(validate(schema, { ...good, extra: 1 }).length, 'лишнее поле');
  assert.ok(validate(schema, { ...good, type: 'payment.exploded' }).length, 'тип не из перечня');
  assert.ok(validate(schema, { ...good, occurredAt: '2026-10-03 12:00:00' }).length, 'метка времени не RFC 3339 с миллисекундами');
  assert.ok(validate(schema, { ...good, data: { ...good.data, amount: { amount: 1.5, currency: 'RUB' } } }).length, 'дробная сумма');
  assert.ok(validate(schema, { ...good, data: { ...good.data, amount: { amount: 1, currency: 'USD' } } }).length, 'валюта не RUB');
  assert.ok(validate(schema, { ...good, data: { ...good.data, orderId: 'не-uuid' } }).length, 'идентификатор заказа не UUID');
  assert.ok(validate(schema, { type: 'payment.paid' }).length, 'нет обязательных полей');
});

test('контракт описывает подпись так, как её делает заглушка', () => {
  assert.deepEqual(contract.signature, { algorithm: 'HMAC-SHA-256', header: 'X-Webhook-Signature', timestampHeader: 'X-Webhook-Timestamp', toleranceSeconds: 300 });
  assert.deepEqual(Object.keys(contract.webhooks).sort(), ['deliveryEmail', 'payment', 'platformEmail', 'platformSms']);
});

test('платёжный шлюз: оплата, отказ, истечение сессии и возврат соответствуют схеме', async () => {
  await h.setMode('payment', { delayMs: 0, refund: { delayMs: 0 } });
  const a = await newPayment(h, { orderId: '0199e0a0-0000-7000-8000-000000000301', amount: 299800 });
  await h.idle();
  await h.gw('POST', `/payment/v1/payments/${a.json.paymentId}/refunds`, { headers: { 'Idempotency-Key': 'r1' }, body: { amount: { amount: 299800, currency: 'RUB' } } });
  await h.setMode('payment', { scenario: 'reject', failureReason: 'authentication_failed', delayMs: 0 });
  await newPayment(h, { orderId: '0199e0a0-0000-7000-8000-000000000302' });
  await h.setMode('payment', { scenario: 'expire', delayMs: 0 });
  await newPayment(h, { orderId: '0199e0a0-0000-7000-8000-000000000303' });
  await h.idle();
  const events = assertConforms('payment', 4);
  assert.deepEqual(events.map((e) => e.type).sort(), ['payment.declined', 'payment.paid', 'payment.session-expired', 'refund.completed']);
});

test('платёжный шлюз: дефектные режимы остаются в рамках схемы (сломаны подпись или метка времени, а не форма тела)', async () => {
  await h.setMode('payment', { delayMs: 0, webhook: { amountDelta: 50, unknownPayment: true } });
  await newPayment(h, { orderId: '0199e0a0-0000-7000-8000-000000000304' });
  await h.idle();
  const [event] = h.hooksOf('/payment-gateway').map((x) => JSON.parse(x.body));
  assert.deepEqual(validate(contract.webhooks.payment.schema, event), []);
});

test('e-mail: статусы писем с ключом и без ключа соответствуют схемам своих получателей', async () => {
  await h.setMode('email', { statusDelayMs: 0 });
  await h.api(SECRETS.deliveryEmailKey)('POST', '/email/v1/messages', { body: { to: 'a@example.test', subject: 'Ключ', text: 'K' } });
  await h.api(SECRETS.platformEmailKey)('POST', '/email/v1/messages', { body: { to: 'a@example.test', subject: 'Оповещение', text: 'T' } });
  await h.setMode('email', { statusDelayMs: 0, status: 'failed', failReason: 'other' });
  await h.api(SECRETS.deliveryEmailKey)('POST', '/email/v1/messages', { body: { to: 'b@example.test', subject: 'Ключ 2', text: 'K' } });
  await h.api(SECRETS.platformEmailKey)('POST', '/email/v1/messages', { body: { to: 'b@example.test', subject: 'Оповещение 2', text: 'T' } });
  await h.idle();
  assert.deepEqual(assertConforms('deliveryEmail', 2).map((e) => e.status), ['delivered', 'failed']);
  assert.deepEqual(assertConforms('platformEmail', 2).map((e) => e.status), ['delivered', 'failed']);
});

test('SMS: статусы доставки соответствуют схеме', async () => {
  await h.setMode('sms', { statusDelayMs: 0 });
  const r = await h.api(SECRETS.platformSmsKey)('POST', '/sms/v1/messages', { body: { to: '+79991234567', text: 'Код 123456' } });
  await h.idle();
  await h.admin('POST', `/admin/sms/${r.json.messageId}/status`, { body: { status: 'failed' } });
  assert.deepEqual(assertConforms('platformSms', 2).map((e) => e.status), ['delivered', 'failed']);
});
