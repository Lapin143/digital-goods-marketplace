import test, { after, afterEach, before } from 'node:test';
import assert from 'node:assert/strict';
import { newPayment, startHarness } from './harness.mjs';

let h;
before(async () => {
  h = await startHarness();
});
after(() => h.stop());
afterEach(() => h.reset());

test('здоровье: оба порта отвечают на /health, у административного порта нет маршрутов прикладного', async () => {
  assert.deepEqual((await h.api()('GET', '/health')).json, { status: 'UP' });
  assert.deepEqual((await h.admin('GET', '/health')).json, { status: 'UP' });
  assert.equal((await h.admin('POST', '/payment/v1/payments')).status, 404, 'прикладной интерфейс не виден на административном порту');
  assert.equal((await h.api()('GET', '/admin/state')).status, 404, 'команды управления не видны на прикладном порту (ADR-015)');
  assert.equal((await h.api()('PUT', '/admin/modes/payment', { body: {} })).status, 404);
});

test('режим читается и заменяется целиком; пропущенное поле возвращается к значению по умолчанию', async () => {
  const set = await h.admin('PUT', '/admin/modes/payment', { body: { scenario: 'reject', delayMs: 5 } });
  assert.equal(set.status, 200);
  assert.equal(set.json.scenario, 'reject');
  assert.equal((await h.admin('GET', '/admin/modes/payment')).json.delayMs, 5);
  await h.admin('PUT', '/admin/modes/payment', { body: { scenario: 'hold' } });
  const now = (await h.admin('GET', '/admin/modes/payment')).json;
  assert.equal(now.scenario, 'hold');
  assert.equal(now.delayMs, 1000, 'delayMs вернулся к умолчанию: PUT заменяет, а не дополняет');
});

test('неверный режим отвергается и прежний режим сохраняется', async () => {
  await h.admin('PUT', '/admin/modes/sms', { body: { behavior: 'drop' } });
  const bad = await h.admin('PUT', '/admin/modes/sms', { body: { behavior: 'explode', lateMs: -5, лишнее: 1 } });
  assert.equal(bad.status, 400);
  assert.equal(bad.json.error.code, 'invalid_mode');
  assert.equal(bad.json.error.details.length, 3);
  assert.equal((await h.admin('GET', '/admin/modes/sms')).json.behavior, 'drop');
  assert.equal((await h.admin('PUT', '/admin/modes/bank', { body: {} })).status, 404);
  assert.equal((await h.admin('GET', '/admin/modes/bank')).status, 404);
});

test('все режимы одним запросом и сводка состояния', async () => {
  await newPayment(h);
  await h.stubs.app.idle();
  const all = (await h.admin('GET', '/admin/modes')).json;
  assert.deepEqual(Object.keys(all), ['payment', 'email', 'sms', 'vkid']);
  const state = (await h.admin('GET', '/admin/state')).json;
  assert.equal(state.bootTag, 't');
  assert.equal(state.counts.payments, 1);
});

test('сброс возвращает режимы, очищает данные и ждёт завершения отложенных действий прошлого теста', async () => {
  await h.setMode('payment', { delayMs: 60 });
  await newPayment(h);
  assert.equal(h.hooks.length, 0, 'исход ещё не наступил');
  const r = await h.admin('POST', '/admin/reset');
  assert.equal(r.status, 200);
  assert.equal(h.hooks.length, 1, 'сброс дождался отложенного исхода, он не утечёт в следующий тест');
  h.hooks.length = 0;
  await new Promise((resolve) => setTimeout(resolve, 100));
  assert.equal(h.hooks.length, 0);
  assert.equal(h.state.payments.size, 0);
  assert.equal(h.state.modes.payment.delayMs, 1000);
});

test('счётчики «первые N» заново заводятся при установке режима и сбросе', async () => {
  await h.setMode('payment', { create: { failFirst: 1 } });
  assert.equal((await newPayment(h, { orderId: 'a' })).status, 503);
  await h.setMode('payment', { scenario: 'hold', create: { failFirst: 1 } });
  assert.equal((await newPayment(h, { orderId: 'b' })).status, 503, 'новый режим — новый счётчик');
  await h.reset();
  assert.equal((await newPayment(h, { orderId: 'c' })).status, 201, 'после сброса отказов нет');
});

test('журнал: фильтры по системе и виду, очистка не сбрасывает номера', async () => {
  await h.setMode('payment', { delayMs: 0 });
  await newPayment(h);
  await h.stubs.app.idle();
  const all = (await h.admin('GET', '/admin/journal')).json;
  assert.ok(all.items.some((e) => e.kind === 'request'));
  assert.ok(all.items.some((e) => e.kind === 'webhook'));
  const hooksOnly = (await h.admin('GET', '/admin/journal?kind=webhook')).json.items;
  assert.ok(hooksOnly.length && hooksOnly.every((e) => e.kind === 'webhook'));
  assert.equal(hooksOnly[0].body.type, 'payment.paid');
  assert.equal(hooksOnly[0].outcome, 200);
  const cleared = (await h.admin('DELETE', '/admin/journal')).json;
  assert.equal(cleared.items.length, 0);
  assert.equal(cleared.last, all.last);
  await newPayment(h, { orderId: 'next' });
  assert.ok((await h.admin('GET', '/admin/journal')).json.items[0].n > all.last);
});

test('журнал ограничен по размеру: старые записи вытесняются', async () => {
  const before = h.state.journalLimit;
  h.state.journalLimit = 5;
  for (let i = 0; i < 12; i++) h.state.record({ kind: 'request', system: 'payment', note: String(i) });
  assert.equal(h.state.journal.length, 5);
  assert.equal(h.state.journal.at(-1).note, '11');
  h.state.journalLimit = before;
});

test('секреты не попадают в журнал: поля token, secret, code маскируются', async () => {
  await h.api()('POST', '/vkid/token', { raw: 'grant_type=authorization_code&code=abc&client_secret=hunter2&client_id=x', headers: { 'Content-Type': 'application/x-www-form-urlencoded' } });
  const entry = h.state.journal.find((e) => e.path === '/vkid/token');
  assert.equal(entry.body.client_secret, '***');
  assert.equal(entry.body.code, '***');
  assert.equal(JSON.stringify(h.state.journal).includes('hunter2'), false);
});
