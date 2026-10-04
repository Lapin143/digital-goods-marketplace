import test, { after, afterEach, before } from 'node:test';
import assert from 'node:assert/strict';
import { SECRETS, newPayment, startHarness, wait } from './harness.mjs';
import { verify } from '../src/signing.mjs';

let h;
before(async () => {
  h = await startHarness();
});
after(() => h.stop());
afterEach(() => h.reset());

const HOOK = '/payment-gateway';
const checkSig = (hook, secret = SECRETS.paymentWebhookSecret) =>
  verify({
    secrets: [secret],
    timestamp: hook.headers['x-webhook-timestamp'],
    body: hook.body,
    signature: hook.headers['x-webhook-signature'],
  });

test('без ключа шлюза или с чужим ключом запросы отвергаются (401)', async () => {
  const body = { orderId: 'o1', amount: { amount: 100, currency: 'RUB' } };
  const noKey = await h.api(undefined)('POST', '/payment/v1/payments', { body, headers: { 'Idempotency-Key': 'o1' } });
  const wrong = await h.api('wrong-key')('POST', '/payment/v1/payments', { body, headers: { 'Idempotency-Key': 'o1' } });
  assert.equal(noKey.status, 401);
  assert.equal(wrong.status, 401);
  assert.equal(h.state.payments.size, 0);
});

test('создание без Idempotency-Key и с неверным телом отвергается (400) с описанием причин', async () => {
  const noKey = await h.gw('POST', '/payment/v1/payments', { body: { orderId: 'o1', amount: { amount: 100, currency: 'RUB' } } });
  assert.equal(noKey.status, 400);
  assert.equal(noKey.json.error.code, 'idempotency_key_required');
  const bad = await h.gw('POST', '/payment/v1/payments', { headers: { 'Idempotency-Key': 'k' }, body: { orderId: '', amount: { amount: 1.5, currency: 'rub' }, returnUrl: 'ftp://x' } });
  assert.equal(bad.status, 400);
  assert.equal(bad.json.error.details.length, 4);
});

test('режим «успех»: платёж создан, затем приходит подписанный вебхук payment.paid', async () => {
  await h.setMode('payment', { delayMs: 0 });
  const r = await newPayment(h, { orderId: 'order-42', amount: 299800 });
  assert.equal(r.status, 201);
  assert.equal(r.json.status, 'pending');
  assert.match(r.json.payUrl, /^http:\/\/stubs\.test\/payment\/pay\/pay_t_\d{6}$/);
  assert.ok(Date.parse(r.json.expiresAt) - Date.parse(r.json.createdAt) === 720_000, 'срок сессии 12 минут по ADR-015');
  await h.idle();
  const hooks = h.hooksOf(HOOK);
  assert.equal(hooks.length, 1);
  assert.deepEqual(checkSig(hooks[0]), { ok: true });
  const event = JSON.parse(hooks[0].body);
  assert.deepEqual(Object.keys(event).sort(), ['data', 'eventId', 'occurredAt', 'type']);
  assert.equal(event.type, 'payment.paid');
  assert.deepEqual(event.data, { paymentId: r.json.paymentId, orderId: 'order-42', amount: { amount: 299800, currency: 'RUB' } });
  assert.equal((await h.gw('GET', `/payment/v1/payments/${r.json.paymentId}`)).json.status, 'paid');
});

test('повтор запроса с тем же ключом возвращает тот же платёж, а другое тело с тем же ключом даёт 409', async () => {
  await h.setMode('payment', { scenario: 'hold' });
  const a = await newPayment(h, { orderId: 'o1' });
  const b = await newPayment(h, { orderId: 'o1' });
  assert.equal(b.status, 201);
  assert.equal(b.json.paymentId, a.json.paymentId);
  assert.equal(b.headers.get('idempotent-replayed'), 'true');
  assert.equal(h.state.payments.size, 1);
  const c = await newPayment(h, { orderId: 'o1', amount: 1 });
  assert.equal(c.status, 409);
  assert.equal(c.json.error.code, 'idempotency_conflict');
});

test('режим «отказ»: вебхук payment.declined с причиной', async () => {
  await h.setMode('payment', { scenario: 'reject', failureReason: 'insufficient_funds', delayMs: 0 });
  const r = await newPayment(h);
  await h.idle();
  const event = JSON.parse(h.hooksOf(HOOK)[0].body);
  assert.equal(event.type, 'payment.declined');
  assert.equal(event.data.failureReason, 'insufficient_funds');
  assert.equal((await h.gw('GET', `/payment/v1/payments/${r.json.paymentId}`)).json.status, 'declined');
});

test('режим «оплата не завершена» и поздняя оплата: вебхука нет, пока тест не подтвердит платёж командой', async () => {
  await h.setMode('payment', { scenario: 'hold', delayMs: 0 });
  const r = await newPayment(h);
  await h.idle();
  assert.equal(h.hooks.length, 0);
  const cmd = await h.admin('POST', `/admin/payments/${r.json.paymentId}/confirm`);
  assert.equal(cmd.status, 200);
  assert.deepEqual(cmd.json.deliveries.map((d) => d.status), [200]);
  assert.equal(JSON.parse(h.hooksOf(HOOK)[0].body).type, 'payment.paid');
  assert.equal((await h.gw('GET', `/payment/v1/payments/${r.json.paymentId}`)).json.status, 'paid');
});

test('сессия истекла, затем шлюз подтверждает платёж (поздняя оплата)', async () => {
  await h.setMode('payment', { scenario: 'expire', delayMs: 0 });
  const r = await newPayment(h);
  await h.idle();
  assert.equal(JSON.parse(h.hooks[0].body).type, 'payment.session-expired');
  assert.equal((await h.gw('GET', `/payment/v1/payments/${r.json.paymentId}`)).json.status, 'expired');
  await h.admin('POST', `/admin/payments/${r.json.paymentId}/confirm`);
  assert.deepEqual(h.hooks.map((x) => JSON.parse(x.body).type), ['payment.session-expired', 'payment.paid']);
});

test('«сессия не открывается»: первые N вызовов получают 503, затем платёж создаётся', async () => {
  await h.setMode('payment', { scenario: 'hold', create: { failFirst: 2 } });
  assert.equal((await newPayment(h)).status, 503);
  assert.equal((await newPayment(h)).status, 503);
  assert.equal(h.state.payments.size, 0);
  assert.equal((await newPayment(h)).status, 201);
  assert.equal(h.state.payments.size, 1);
});

test('«сессия не открывается»: failFirst=-1 отказывает всегда, 429 несёт Retry-After', async () => {
  await h.setMode('payment', { create: { failFirst: -1, failure: 'http_429' } });
  for (let i = 0; i < 3; i++) {
    const r = await newPayment(h);
    assert.equal(r.status, 429);
    assert.equal(r.headers.get('retry-after'), '1');
  }
});

test('«сессия не открывается»: тайм-аут — соединение держится, затем закрывается без ответа', async () => {
  await h.setMode('payment', { create: { failFirst: 1, failure: 'timeout', timeoutMs: 80 } });
  const started = Date.now();
  await assert.rejects(newPayment(h));
  assert.ok(Date.now() - started >= 70);
  assert.equal((await newPayment(h)).status, 201);
  assert.equal(h.state.journal.find((e) => e.path === '/payment/v1/payments' && e.status === 0)?.note, 'соединение закрыто без ответа');
});

test('«потерянный ответ»: платёж создан, клиент ответа не получил, повтор по ключу находит тот же платёж', async () => {
  await h.setMode('payment', { scenario: 'hold', create: { loseResponseFirst: 1 } });
  await assert.rejects(newPayment(h, { orderId: 'lost' }));
  assert.equal(h.state.payments.size, 1, 'платёж у шлюза уже создан');
  const retry = await newPayment(h, { orderId: 'lost' });
  assert.equal(retry.status, 201);
  assert.equal(retry.headers.get('idempotent-replayed'), 'true');
  assert.equal(h.state.payments.size, 1, 'второго платежа нет');
});

test('«повторное уведомление»: одно и то же уведомление N раз, по очереди и одновременно', async () => {
  await h.setMode('payment', { delayMs: 0, webhook: { repeat: 3 } });
  await newPayment(h, { orderId: 'o-seq' });
  await h.idle();
  assert.equal(h.hooks.length, 3);
  assert.equal(new Set(h.hooks.map((x) => x.body)).size, 1, 'тело одинаковое: тот же eventId');
  await h.reset();
  await h.setMode('payment', { delayMs: 0, webhook: { repeat: 4, parallel: true } });
  await newPayment(h, { orderId: 'o-par' });
  await h.idle();
  assert.equal(h.hooks.length, 4);
});

test('«дефектный вебхук»: неверная подпись не проходит проверку получателя', async () => {
  await h.setMode('payment', { delayMs: 0, webhook: { badSignature: true } });
  await newPayment(h);
  await h.idle();
  assert.equal(checkSig(h.hooks[0]).reason, 'bad_signature');
});

test('«дефектный вебхук»: устаревшая метка времени подписана верно, но старше пяти минут', async () => {
  await h.setMode('payment', { delayMs: 0, webhook: { timestampOffsetSeconds: -600 } });
  await newPayment(h);
  await h.idle();
  assert.equal(checkSig(h.hooks[0]).reason, 'stale');
});

test('«дефектный вебхук»: расхождение суммы и неизвестный платёж', async () => {
  await h.setMode('payment', { delayMs: 0, webhook: { amountDelta: 100 } });
  await newPayment(h, { amount: 5000 });
  await h.idle();
  assert.equal(JSON.parse(h.hooks[0].body).data.amount.amount, 5100);
  await h.reset();
  await h.setMode('payment', { delayMs: 0, webhook: { unknownPayment: true } });
  const r = await newPayment(h, { orderId: 'known' });
  await h.idle();
  const data = JSON.parse(h.hooks[0].body).data;
  assert.notEqual(data.orderId, 'known');
  assert.notEqual(data.paymentId, r.json.paymentId);
  assert.match(data.orderId, /^[0-9a-f]{8}-[0-9a-f]{4}-/);
});

test('получатель отвечает 5xx: заглушка повторяет, как настоящий шлюз; 4xx не повторяет', async () => {
  const local = await startHarness({ webhookStatuses: [500, 503, 200] });
  try {
    await local.setMode('payment', { delayMs: 0 });
    await newPayment(local);
    await local.idle();
    assert.equal(local.hooks.length, 3);
    const attempts = local.state.journal.filter((e) => e.kind === 'webhook').map((e) => e.status);
    assert.deepEqual(attempts, [500, 503, 200]);
  } finally {
    await local.stop();
  }
  const four = await startHarness({ webhookStatuses: [401, 200] });
  try {
    await four.setMode('payment', { delayMs: 0 });
    await newPayment(four);
    await four.idle();
    assert.equal(four.hooks.length, 1);
  } finally {
    await four.stop();
  }
});

test('send=false: вебхук потерян, но статус у шлюза изменился (сверка находит оплату)', async () => {
  await h.setMode('payment', { delayMs: 0, webhook: { send: false } });
  const r = await newPayment(h);
  await h.idle();
  assert.equal(h.hooks.length, 0);
  assert.equal((await h.gw('GET', `/payment/v1/payments/${r.json.paymentId}`)).json.status, 'paid');
  assert.equal(h.state.journal.find((e) => e.kind === 'webhook').outcome, 'lost');
});

test('режим платежа фиксируется при создании: переключение режима не меняет исход уже созданного платежа', async () => {
  await h.setMode('payment', { scenario: 'success', delayMs: 40 });
  await newPayment(h, { orderId: 'first' });
  await h.setMode('payment', { scenario: 'reject', delayMs: 0 });
  await newPayment(h, { orderId: 'second' });
  await h.idle();
  const byOrder = Object.fromEntries(h.hooks.map((x) => JSON.parse(x.body)).map((e) => [e.data.orderId, e.type]));
  assert.deepEqual(byOrder, { first: 'payment.paid', second: 'payment.declined' });
});

test('возврат: мгновенный режим даёт «выполнен», платёж «возвращён» и вебхук refund.completed', async () => {
  await h.setMode('payment', { delayMs: 0, refund: { delayMs: 0 } });
  const p = await newPayment(h, { amount: 1000 });
  await h.idle();
  const body = { amount: { amount: 1000, currency: 'RUB' } };
  const r = await h.gw('POST', `/payment/v1/payments/${p.json.paymentId}/refunds`, { headers: { 'Idempotency-Key': `refund-${p.json.paymentId}` }, body });
  assert.equal(r.status, 201);
  assert.equal(r.json.status, 'completed');
  assert.match(r.json.refundId, /^ref_t_/);
  await h.idle();
  const last = JSON.parse(h.hooks.at(-1).body);
  assert.equal(last.type, 'refund.completed');
  assert.equal(last.data.refundId, r.json.refundId);
  assert.equal((await h.gw('GET', `/payment/v1/payments/${p.json.paymentId}`)).json.status, 'refunded');
  const again = await h.gw('POST', `/payment/v1/payments/${p.json.paymentId}/refunds`, { headers: { 'Idempotency-Key': `refund-${p.json.paymentId}` }, body });
  assert.equal(again.json.refundId, r.json.refundId);
  assert.equal(again.headers.get('idempotent-replayed'), 'true');
});

test('возврат: первые N запросов получают 503, затем принимается; reject не принимает никогда (E8)', async () => {
  await h.setMode('payment', { delayMs: 0, refund: { failFirst: 2, delayMs: 0 } });
  const p = await newPayment(h, { amount: 500 });
  await h.idle();
  const refund = (key) => h.gw('POST', `/payment/v1/payments/${p.json.paymentId}/refunds`, { headers: { 'Idempotency-Key': key }, body: { amount: { amount: 500, currency: 'RUB' } } });
  assert.equal((await refund('r1')).status, 503);
  assert.equal((await refund('r1')).status, 503);
  assert.equal((await refund('r1')).status, 201);
  await h.reset();
  await h.setMode('payment', { delayMs: 0, refund: { mode: 'reject' } });
  const q = await newPayment(h, { orderId: 'o2', amount: 500 });
  await h.idle();
  for (let i = 0; i < 5; i++) {
    const r = await h.gw('POST', `/payment/v1/payments/${q.json.paymentId}/refunds`, { headers: { 'Idempotency-Key': 'r' }, body: { amount: { amount: 500, currency: 'RUB' } } });
    assert.equal(r.status, 422);
    assert.equal(r.json.error.code, 'refund_rejected');
  }
  assert.equal((await h.gw('GET', `/payment/v1/payments/${q.json.paymentId}`)).json.status, 'paid');
});

test('возврат в обработке и ручной возврат: вебхук приходит по команде «отметить возврат выполненным»', async () => {
  await h.setMode('payment', { delayMs: 0, refund: { mode: 'pending' } });
  const p = await newPayment(h, { amount: 700 });
  await h.idle();
  const r = await h.gw('POST', `/payment/v1/payments/${p.json.paymentId}/refunds`, { headers: { 'Idempotency-Key': 'r' }, body: { amount: { amount: 700, currency: 'RUB' } } });
  assert.equal(r.json.status, 'pending');
  await h.idle();
  assert.equal((await h.gw('GET', `/payment/v1/payments/${p.json.paymentId}`)).json.status, 'refund_pending');
  const hooksBefore = h.hooks.length;
  const done = await h.admin('POST', `/admin/payments/${p.json.paymentId}/refund-complete`);
  assert.equal(done.status, 200);
  assert.equal(h.hooks.length, hooksBefore + 1);
  assert.equal(JSON.parse(h.hooks.at(-1).body).data.refundId, r.json.refundId);
  assert.equal((await h.gw('GET', `/payment/v1/payments/${p.json.paymentId}`)).json.status, 'refunded');
});

test('возврат: чужая сумма, неоплаченный платёж и неизвестный платёж отвергаются', async () => {
  await h.setMode('payment', { scenario: 'hold' });
  const p = await newPayment(h, { amount: 900 });
  const refund = (id, amount) => h.gw('POST', `/payment/v1/payments/${id}/refunds`, { headers: { 'Idempotency-Key': `k-${amount}` }, body: { amount: { amount, currency: 'RUB' } } });
  assert.equal((await refund(p.json.paymentId, 900)).status, 409, 'платёж не оплачен');
  assert.equal((await refund('нет', 900)).status, 404);
  await h.admin('POST', `/admin/payments/${p.json.paymentId}/confirm`);
  assert.equal((await refund(p.json.paymentId, 100)).status, 422, 'возврат только на полную сумму');
});

test('команда «прислать уведомление»: любой тип без смены статуса — перестановка и неизвестный тип', async () => {
  await h.setMode('payment', { scenario: 'hold' });
  const p = await newPayment(h);
  const a = await h.admin('POST', `/admin/payments/${p.json.paymentId}/webhook`, { body: { type: 'refund.completed', refundId: 'ref_x' } });
  assert.equal(a.status, 200);
  await h.admin('POST', `/admin/payments/${p.json.paymentId}/webhook`, { body: { type: 'payment.future-type', repeat: 2 } });
  assert.deepEqual(h.hooks.map((x) => JSON.parse(x.body).type), ['refund.completed', 'payment.future-type', 'payment.future-type']);
  assert.equal(JSON.parse(h.hooks[0].body).data.refundId, 'ref_x');
  assert.equal((await h.gw('GET', `/payment/v1/payments/${p.json.paymentId}`)).json.status, 'pending');
});

test('команды принимают переопределения для одного вызова: метка времени, подпись, сумма', async () => {
  await h.setMode('payment', { scenario: 'hold' });
  const p = await newPayment(h, { amount: 1000 });
  await h.admin('POST', `/admin/payments/${p.json.paymentId}/confirm`, { body: { timestamp: '2020-01-01T00:00:00.000Z', badSignature: true, amountDelta: -1 } });
  assert.equal(h.hooks[0].headers['x-webhook-timestamp'], '2020-01-01T00:00:00.000Z');
  assert.equal(checkSig(h.hooks[0]).reason, 'bad_signature');
  assert.equal(JSON.parse(h.hooks[0].body).data.amount.amount, 999);
  const bad = await h.admin('POST', `/admin/payments/${p.json.paymentId}/confirm`, { body: { repeat: 'много' } });
  assert.equal(bad.status, 400);
});

test('страница оплаты: покупатель выбирает исход, затем возвращается на return_url', async () => {
  await h.setMode('payment', { scenario: 'hold' });
  const p = await newPayment(h, { amount: 12345 });
  const page = await h.api()('GET', `/payment/pay/${p.json.paymentId}`);
  assert.equal(page.status, 200);
  assert.match(page.text, /123\.45 RUB/);
  assert.match(page.text, /name="action" value="pay"/);
  const pay = await h.api()('POST', `/payment/pay/${p.json.paymentId}`, { raw: 'action=pay', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, redirect: 'manual' });
  assert.equal(pay.status, 303);
  assert.equal(pay.headers.get('location'), `https://shop.test/return?paymentId=${p.json.paymentId}`);
  assert.equal(JSON.parse(h.hooks[0].body).type, 'payment.paid');
  const bad = await h.api()('POST', `/payment/pay/${p.json.paymentId}`, { raw: 'action=hack', headers: { 'Content-Type': 'application/x-www-form-urlencoded' } });
  assert.equal(bad.status, 400);
});

test('сброс: платежи и режимы очищены, идентификаторы не повторяются', async () => {
  await h.setMode('payment', { scenario: 'hold' });
  const first = await newPayment(h);
  await h.reset();
  assert.equal(h.state.payments.size, 0);
  assert.equal(h.state.modes.payment.scenario, 'success');
  await h.setMode('payment', { scenario: 'hold' });
  const second = await newPayment(h);
  assert.notEqual(first.json.paymentId, second.json.paymentId);
});

test('журнал: запросы записаны без секрета, повтор с тем же ключом виден, есть фильтр по номеру записи', async () => {
  await h.setMode('payment', { scenario: 'hold' });
  const last = (await h.admin('GET', '/admin/journal')).json.last;
  await newPayment(h, { orderId: 'j1' });
  await newPayment(h, { orderId: 'j1' });
  const j = (await h.admin('GET', `/admin/journal?since=${last}&system=payment`)).json;
  assert.equal(j.items.length, 2);
  assert.deepEqual(j.items.map((e) => e.status), [201, 201]);
  assert.equal(j.items[0].idempotencyKey, 'j1');
  assert.equal(JSON.stringify(j).includes(SECRETS.paymentGatewayKey), false);
});

test('неизвестный платёж и неизвестный маршрут', async () => {
  assert.equal((await h.gw('GET', '/payment/v1/payments/нет')).status, 404);
  assert.equal((await h.gw('GET', '/payment/v1/nothing')).status, 404);
  const wrongMethod = await h.gw('DELETE', '/payment/v1/payments');
  assert.equal(wrongMethod.status, 405);
  assert.equal(wrongMethod.headers.get('allow'), 'POST');
});

test('тело больше мегабайта отвергается (413), неразборчивый JSON даёт 400', async () => {
  const big = await h.gw('POST', '/payment/v1/payments', { headers: { 'Idempotency-Key': 'b', 'Content-Type': 'application/json' }, raw: 'x'.repeat(1_100_000) });
  assert.equal(big.status, 413);
  const bad = await h.gw('POST', '/payment/v1/payments', { headers: { 'Idempotency-Key': 'b', 'Content-Type': 'application/json' }, raw: '{' });
  assert.equal(bad.status, 400);
  assert.equal(bad.json.error.code, 'invalid_json');
  await wait(1);
});
