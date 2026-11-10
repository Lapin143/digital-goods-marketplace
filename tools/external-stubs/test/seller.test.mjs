import test, { after, afterEach, before } from 'node:test';
import assert from 'node:assert/strict';
import { startHarness } from './harness.mjs';
import { sign, verify } from '../src/signing.mjs';
import { keysFor, requestProblems, webhookProblems } from '../src/seller.mjs';

// Заглушка «система продавца» (Ф5, ADR-028): приём подписанного запроса, ответ вызовом шлюза с ключом API, приём webhook.
// «Шлюз платформы» в тесте это получатель из harness: он записывает вызов ответа и отвечает так, как задано в `gateway`.

const SECRET = 'a3c1e5b7d9f0123456789abcdef0123456789abcdef0123456789abcdef01234';
const OLD_SECRET = '9f2c4e6a8b0d1f3a5c7e9b1d3f5a7c9e0b2d4f6a8c0e2a4c6e8b0d2f4a6c8e0a';
const API_KEY = `dgm_${'Zk3v'.repeat(10)}`;
const SLUG = 'shop-1';
const REQUEST_ID = '0199e0a0-0000-7000-8000-000000000a01';
const PRODUCT_ID = '0199e0a0-0000-7000-8000-000000000202';
const ANSWER_PATH = `/gateway/api/v1/seller-api/delivery-requests/${REQUEST_ID}/answer`;

let gateway = () => ({ status: 200, json: { requestId: REQUEST_ID, status: 'answered', stored: 2 } });
let h;
before(async () => {
  h = await startHarness({ respond: (hook) => gateway(hook) });
});
after(() => h.stop());
afterEach(async () => {
  gateway = () => ({ status: 200, json: { requestId: REQUEST_ID, status: 'answered', stored: 2 } });
  await h.reset();
});

const requestBody = (extra = {}) => ({ requestId: REQUEST_ID, orderNumber: 1042, productId: PRODUCT_ID, quantity: 2, respondBy: new Date(Date.now() + 600_000).toISOString(), ...extra });
const webhookBody = (extra = {}) => ({ eventId: 'evt-1', type: 'order.paid', occurredAt: new Date().toISOString(), orderNumber: 1042, productId: PRODUCT_ID, quantity: 2, amount: { amount: 299800, currency: 'RUB' }, ...extra });

const connect = (fields = { secret: SECRET, apiKey: API_KEY }) => h.admin('PUT', `/admin/sellers/${SLUG}`, { body: fields });
const signed = (obj, { secret = SECRET, timestamp = new Date().toISOString(), body, headers = {} } = {}) => {
  const raw = body ?? JSON.stringify(obj);
  return { raw, headers: { 'Content-Type': 'application/json', 'X-Webhook-Timestamp': timestamp, 'X-Webhook-Signature': sign(secret, timestamp, raw), ...headers } };
};
const post = (path, obj, opts) => {
  const { raw, headers } = signed(obj, opts);
  return h.api()('POST', path, { raw, headers });
};
const sendRequest = (extra, opts) => post(`/seller/${SLUG}/request`, requestBody(extra), opts);
const sendWebhook = (extra, opts) => post(`/seller/${SLUG}/webhook`, webhookBody(extra), opts);
const requests = async () => (await h.admin('GET', `/admin/sellers/${SLUG}/requests`)).json.items;
const answerCalls = () => h.hooksOf(ANSWER_PATH);
const mode = (request = {}, webhook = {}) => h.setMode('seller', { request: { answerDelayMs: 0, ...request }, webhook });

test('подпись и контракт: проверка тела запроса и webhook', () => {
  assert.deepEqual(requestProblems(requestBody()), []);
  assert.equal(requestProblems(requestBody({ requestId: 'не-uuid' })).length, 1);
  assert.equal(requestProblems(requestBody({ quantity: 0 })).length, 1);
  assert.equal(requestProblems(requestBody({ orderNumber: '1042' })).length, 1, 'номер заказа в запросе это число, строка нарушает контракт');
  assert.equal(requestProblems(requestBody({ orderNumber: 0 })).length, 1);
  assert.equal(requestProblems(requestBody({ quantity: 11 })).length, 1);
  assert.equal(requestProblems(requestBody({ respondBy: 'завтра' })).length, 1);
  assert.equal(requestProblems(null).length, 1);
  assert.deepEqual(webhookProblems(webhookBody()), []);
  assert.equal(webhookProblems(webhookBody({ eventId: '' })).length, 1);
  assert.equal(webhookProblems(webhookBody({ occurredAt: 'вчера' })).length, 1);
});

test('значения ключей: по одному на единицу заказа, одинаковы при повторе, сдвиг режима меняет число', () => {
  const app = h.stubs.app;
  const entry = { quantity: 2 };
  const keys = keysFor(app, entry);
  assert.equal(keys.length, 2);
  assert.equal(new Set(keys).size, 2);
  assert.deepEqual(keysFor(app, entry), keys);
  assert.equal(keysFor(app, entry, 1).length, 3);
  assert.equal(keysFor(app, entry, -1).length, 1);
  assert.equal(keysFor(app, entry, -5).length, 0);
  assert.equal(keysFor(app, entry, 0, 7).length, 7);
});

test('режим по умолчанию: запрос принят (202), запомнен, ключи уходят шлюзу с API-ключом и без подписи', async () => {
  await connect();
  await mode();
  const r = await sendRequest();
  assert.equal(r.status, 202);
  assert.deepEqual(r.json, { requestId: REQUEST_ID, status: 'accepted' });
  await h.idle();

  const [entry] = await requests();
  assert.equal(entry.requestId, REQUEST_ID);
  assert.equal(entry.attempts, 1);
  assert.equal(entry.status, 'accepted');
  assert.deepEqual(entry.extraFields, []);
  assert.equal(entry.answers.length, 1);
  assert.equal(entry.answers[0].kind, 'keys');
  assert.equal(entry.answers[0].status, 200);
  assert.equal(entry.answers[0].stored, 2);
  assert.equal(entry.answers[0].result, 'answered');

  const [call] = answerCalls();
  assert.ok(call, 'вызов respondSellerDelivery дошёл до шлюза');
  assert.equal(call.headers['x-api-key'], API_KEY);
  assert.equal(call.headers['x-webhook-signature'], undefined, 'ответ продавца идёт по ключу API, а не по подписи');
  assert.equal(call.headers.authorization, undefined);
  const body = JSON.parse(call.body);
  assert.deepEqual(Object.keys(body), ['keys']);
  assert.equal(body.keys.length, 2);
  assert.deepEqual(body.keys, entry.answers[0].keys, 'ключи, которые видит сценарий, это ровно то, что ушло шлюзу');
});

test('подпись: чужая подпись, устаревшая метка, нет заголовков: 401, запрос не запоминается, причина в списке отклонённых', async () => {
  await connect();
  await mode();
  assert.equal((await sendRequest({}, { secret: OLD_SECRET })).status, 401);
  assert.equal((await sendRequest({}, { timestamp: new Date(Date.now() - 600_000).toISOString() })).status, 401);
  assert.equal((await h.api()('POST', `/seller/${SLUG}/request`, { body: requestBody() })).status, 401);
  // тело изменили после подписи
  const { headers } = signed(requestBody());
  assert.equal((await h.api()('POST', `/seller/${SLUG}/request`, { raw: JSON.stringify(requestBody({ quantity: 3 })), headers })).status, 401);
  await h.idle();
  assert.equal((await requests()).length, 0);
  assert.equal(answerCalls().length, 0);
  const rejected = (await h.admin('GET', `/admin/sellers/${SLUG}/rejected`)).json.items;
  assert.deepEqual(rejected.map((x) => x.reason), ['bad_signature', 'stale', 'missing', 'bad_signature']);
});

test('подпись заглушки проверяется так же, как у получателей платформы (общая функция verify)', async () => {
  await connect();
  await mode({ behavior: 'silent' });
  const ts = new Date().toISOString();
  const raw = JSON.stringify(requestBody());
  const signature = sign(SECRET, ts, raw);
  assert.equal(verify({ secrets: [SECRET], timestamp: ts, body: raw, signature }).ok, true);
  const r = await h.api()('POST', `/seller/${SLUG}/request`, { raw, headers: { 'Content-Type': 'application/json', 'X-Webhook-Timestamp': ts, 'X-Webhook-Signature': signature } });
  assert.equal(r.status, 202);
});

test('неизвестный продавец: 404 с подсказкой, какую команду выполнить', async () => {
  const r = await post('/seller/nobody/request', requestBody());
  assert.equal(r.status, 404);
  assert.equal(r.json.error.code, 'unknown_seller');
  assert.equal((await post('/seller/nobody/webhook', webhookBody())).status, 404);
});

test('тело с ошибками: 400 со списком причин; лишние поля принимаются и запоминаются (проверка «персональных данных нет»)', async () => {
  await connect();
  await mode({ behavior: 'silent' });
  const bad = await sendRequest({ quantity: 0, orderNumber: '' });
  assert.equal(bad.status, 400);
  assert.equal(bad.json.error.details.length, 2);
  assert.equal((await requests()).length, 0);

  const extra = await sendRequest({ buyerEmail: 'canary@example.test' });
  assert.equal(extra.status, 202);
  const [entry] = await requests();
  assert.deepEqual(entry.extraFields, ['buyerEmail']);
});

test('silent: подтверждение приёма есть, ответа продавца нет', async () => {
  await connect();
  await mode({ behavior: 'silent' });
  assert.equal((await sendRequest()).status, 202);
  await h.idle();
  const [entry] = await requests();
  assert.equal(entry.status, 'accepted');
  assert.equal(entry.answers.length, 0);
  assert.equal(answerCalls().length, 0);
});

test('error: ответ с отказом и заданной причиной', async () => {
  await connect();
  gateway = () => ({ status: 200, json: { requestId: REQUEST_ID, status: 'failed', stored: null } });
  await mode({ behavior: 'error', errorCode: 'out_of_stock' });
  await sendRequest();
  await h.idle();
  const body = JSON.parse(answerCalls()[0].body);
  assert.deepEqual(Object.keys(body), ['error']);
  assert.equal(body.error.code, 'out_of_stock');
  const [entry] = await requests();
  assert.equal(entry.answers[0].kind, 'error');
  assert.equal(entry.answers[0].errorCode, 'out_of_stock');
  assert.equal(entry.answers[0].keys, undefined);
});

test('duplicate: тот же ответ дважды с одними и теми же ключами; повтор платформа видит как «повтор» (заголовок)', async () => {
  await connect();
  let n = 0;
  gateway = () => ({ status: 200, headers: n++ ? { 'Idempotency-Replayed': 'true' } : {}, json: { requestId: REQUEST_ID, status: 'answered', stored: 2 } });
  await mode({ behavior: 'duplicate' });
  await sendRequest();
  await h.idle();
  const calls = answerCalls();
  assert.equal(calls.length, 2);
  assert.equal(calls[0].body, calls[1].body, 'тело второго ответа совпадает с первым');
  const [entry] = await requests();
  assert.deepEqual(entry.answers.map((a) => a.replayed), [false, true]);
});

test('keysDelta: на один ключ больше или меньше заказанного (платформа ответит 422)', async () => {
  await connect();
  gateway = () => ({ status: 422, json: { type: 'https://dgm.example/problems/validation-failed', title: 'Ошибка проверки', status: 422 } });
  await mode({ keysDelta: -1 });
  await sendRequest();
  await h.idle();
  assert.equal(JSON.parse(answerCalls()[0].body).keys.length, 1);
  const [entry] = await requests();
  assert.equal(entry.answers[0].status, 422);
  assert.equal(entry.answers[0].problem, 'validation-failed');
});

test('http500, reject, refuse: отказ на запросе, платформа повторяет; после failFirst заглушка принимает', async () => {
  await connect();
  await mode({ behavior: 'http500', failFirst: 2, thenBehavior: 'answer' });
  assert.equal((await sendRequest()).status, 500);
  assert.equal((await sendRequest()).status, 500);
  assert.equal((await sendRequest()).status, 202);
  assert.equal((await sendRequest()).status, 202, 'повтор после принятия не порождает второго ответа');
  await h.idle();
  const [entry] = await requests();
  assert.equal(entry.attempts, 4);
  assert.deepEqual(entry.outcomes, ['http500', 'http500', 'accepted', 'accepted']);
  assert.equal(entry.answers.length, 1);
  assert.equal(answerCalls().length, 1);

  await h.reset();
  await connect();
  await mode({ behavior: 'reject' });
  const rejected = await sendRequest();
  assert.equal(rejected.status, 422);

  await mode({ behavior: 'refuse' });
  await assert.rejects(sendRequest(), 'соединение рвётся без ответа');
  const journal = (await h.admin('GET', '/admin/journal?system=seller')).json.items;
  assert.ok(journal.some((e) => e.path === `/seller/${SLUG}/request` && e.status === 0), 'в журнале видно запрос без ответа');
});

test('нет шлюза или нет ключа API: ответ не отправляется, причина в записи об ответе', async () => {
  await connect({ secret: SECRET });
  await mode();
  await sendRequest();
  await h.idle();
  let [entry] = await requests();
  assert.equal(entry.answers[0].skipped, 'у подключения нет API-ключа');
  assert.equal(answerCalls().length, 0);

  await h.reset();
  await connect();
  const config = h.stubs.app.config;
  const saved = config.gatewayUrl;
  config.gatewayUrl = '';
  try {
    await mode();
    await sendRequest();
    await h.idle();
    [entry] = await requests();
    assert.match(entry.answers[0].skipped, /STUBS_GATEWAY_URL/);
  } finally {
    config.gatewayUrl = saved;
  }
});

test('отказ платформы на ответе: код и тип проблемы в записи (поздний ответ, чужой запрос)', async () => {
  await connect();
  gateway = () => ({ status: 409, json: { type: 'https://dgm.example/problems/request-expired', title: 'Срок ответа истёк', status: 409 } });
  await mode({ behavior: 'silent' });
  await sendRequest();
  const r = await h.admin('POST', `/admin/sellers/${SLUG}/requests/${REQUEST_ID}/answer`, { body: {} });
  assert.equal(r.status, 200);
  assert.equal(r.json.answers[0].status, 409);
  assert.equal(r.json.answers[0].problem, 'request-expired');
});

test('ручной ответ: свои ключи, отказ, лишнее поле, число ключей, повтор; ошибки команды', async () => {
  await connect();
  await mode({ behavior: 'silent' });
  await sendRequest();
  const answer = (body) => h.admin('POST', `/admin/sellers/${SLUG}/requests/${REQUEST_ID}/answer`, { body });

  assert.equal((await answer({ keys: ['K-1', 'K-2'] })).status, 200);
  assert.deepEqual(JSON.parse(answerCalls().at(-1).body), { keys: ['K-1', 'K-2'] });
  await answer({ count: 3 });
  assert.equal(JSON.parse(answerCalls().at(-1).body).keys.length, 3);
  await answer({ error: { code: 'cannot_issue', message: 'нет' } });
  assert.deepEqual(JSON.parse(answerCalls().at(-1).body), { error: { code: 'cannot_issue', message: 'нет' } });
  await answer({ extra: { note: 'лишнее' } });
  assert.equal(JSON.parse(answerCalls().at(-1).body).note, 'лишнее');
  const twice = await answer({ repeat: 2, keys: ['K-9'] });
  assert.equal(twice.json.answers.length, 2);

  assert.equal((await answer({ keys: ['a'], error: { code: 'other' } })).status, 400);
  assert.equal((await answer({ unknown: 1 })).status, 400);
  assert.equal((await answer({ count: 99 })).status, 400);
  assert.equal((await h.admin('POST', `/admin/sellers/${SLUG}/requests/0199e0a0-0000-7000-8000-00000000ffff/answer`, { body: {} })).status, 404);
});

test('webhook: принят, запомнен, повтор того же eventId даёт «duplicate», тело без лишних полей', async () => {
  await connect();
  const first = await sendWebhook();
  assert.equal(first.status, 200);
  assert.equal(first.json.status, 'ok');
  const again = await sendWebhook();
  assert.equal(again.status, 200);
  assert.equal(again.json.status, 'duplicate');
  await sendWebhook({ eventId: 'evt-2', type: 'order.issued' });

  const r = (await h.admin('GET', `/admin/sellers/${SLUG}/webhooks`)).json;
  assert.deepEqual(r.items.map((x) => [x.eventId, x.type, x.duplicates, x.attempts]), [['evt-1', 'order.paid', 1, 1], ['evt-2', 'order.issued', 0, 1]]);
  assert.deepEqual(r.attempts.map((x) => x.outcome), ['accepted', 'duplicate', 'accepted']);
  assert.deepEqual(r.items[0].extraFields, []);
  const filtered = (await h.admin('GET', `/admin/sellers/${SLUG}/webhooks?type=order.issued`)).json;
  assert.equal(filtered.items.length, 1);
  assert.equal(filtered.attempts.length, 1);
});

test('webhook: подпись и метка времени проверяются, ошибки тела дают 400', async () => {
  await connect();
  assert.equal((await sendWebhook({}, { secret: OLD_SECRET })).status, 401);
  assert.equal((await sendWebhook({}, { timestamp: new Date(Date.now() - 900_000).toISOString() })).status, 401);
  assert.equal((await sendWebhook({ eventId: '' })).status, 400);
  const r = (await h.admin('GET', `/admin/sellers/${SLUG}/webhooks`)).json;
  assert.equal(r.items.length, 0);
  const rejected = (await h.admin('GET', `/admin/sellers/${SLUG}/rejected`)).json.items;
  assert.deepEqual(rejected.map((x) => [x.kind, x.reason]), [['webhook', 'bad_signature'], ['webhook', 'stale'], ['webhook', 'invalid_body']]);
});

test('webhook: первые N попыток отказывают (повторы отправителя), потом успех; номер попытки запоминается', async () => {
  await connect();
  await mode({}, { failFirst: 2, failStatus: 503 });
  assert.equal((await sendWebhook()).status, 503);
  assert.equal((await sendWebhook()).status, 503);
  assert.equal((await sendWebhook()).status, 200);
  const r = (await h.admin('GET', `/admin/sellers/${SLUG}/webhooks`)).json;
  assert.deepEqual(r.attempts.map((x) => [x.attempt, x.outcome]), [[1, 'failed:503'], [2, 'failed:503'], [3, 'accepted']]);
  assert.equal(r.items[0].attempts, 3);
  assert.equal(new Set(r.attempts.map((x) => x.eventId)).size, 1, 'идентификатор события один во всех попытках');
});

test('webhook на адрес подключения: платформа шлёт его туда же, куда запрос выдачи, и различает по X-Event-Id (шаг 16)', async () => {
  await connect();
  const headers = { 'X-Event-Id': 'evt-1', 'X-Event-Type': 'order.paid' };
  assert.equal((await post(`/seller/${SLUG}/request`, webhookBody(), { headers })).status, 200);
  const r = (await h.admin('GET', `/admin/sellers/${SLUG}/webhooks`)).json;
  assert.equal(r.items.length, 1);
  assert.equal(r.items[0].eventIdHeader, 'evt-1');
  assert.equal(r.items[0].eventTypeHeader, 'order.paid');
  assert.deepEqual(r.attempts.map((x) => [x.eventIdHeader, x.eventTypeHeader, x.outcome]), [['evt-1', 'order.paid', 'accepted']]);
  assert.equal((await requests()).length, 0, 'это не запрос выдачи: он не попал в список запросов');
  assert.equal((await sendRequest()).status, 202, 'запрос без X-Event-Id на том же адресе остаётся запросом выдачи');
  assert.equal((await requests()).length, 1);
});

test('webhook: заголовки события должны совпасть с телом, иначе 400 и запись об отказе', async () => {
  await connect();
  const wrongId = await post(`/seller/${SLUG}/request`, webhookBody(), { headers: { 'X-Event-Id': 'evt-2', 'X-Event-Type': 'order.paid' } });
  const wrongType = await post(`/seller/${SLUG}/request`, webhookBody(), { headers: { 'X-Event-Id': 'evt-1', 'X-Event-Type': 'order.issued' } });
  assert.equal(wrongId.status, 400);
  assert.equal(wrongType.status, 400);
  const r = (await h.admin('GET', `/admin/sellers/${SLUG}/webhooks`)).json;
  assert.equal(r.items.length, 0, 'ничего не принято');
  const rejected = (await h.admin('GET', `/admin/sellers/${SLUG}/rejected`)).json.items;
  assert.deepEqual(rejected.map((x) => [x.kind, x.reason]), [['webhook', 'invalid_header'], ['webhook', 'invalid_header']]);
});

test('webhook: постоянный сбой, обрыв соединения, медленный ответ', async () => {
  await connect();
  await mode({}, { failFirst: -1 });
  assert.equal((await sendWebhook()).status, 500);
  assert.equal((await sendWebhook()).status, 500);

  await mode({}, { failFirst: -1, failure: 'drop' });
  await assert.rejects(sendWebhook());
  const r = (await h.admin('GET', `/admin/sellers/${SLUG}/webhooks`)).json;
  assert.equal(r.items.length, 0);
  assert.equal(r.attempts.at(-1).outcome, 'dropped');

  await mode({}, { delayMs: 120 });
  const started = Date.now();
  assert.equal((await sendWebhook({ eventId: 'evt-slow' })).status, 200);
  assert.ok(Date.now() - started >= 100, 'ответ задержан режимом');
});

test('смена секрета: прежний секрет принимается, пока задан previousSecret, и перестаёт, когда его нет', async () => {
  await connect({ secret: SECRET, previousSecret: OLD_SECRET });
  assert.equal((await sendWebhook({ eventId: 'a' }, { secret: OLD_SECRET })).status, 200);
  assert.equal((await sendWebhook({ eventId: 'b' }, { secret: SECRET })).status, 200);
  await connect({ secret: SECRET });
  assert.equal((await sendWebhook({ eventId: 'c' }, { secret: OLD_SECRET })).status, 401);
  const r = (await h.admin('GET', `/admin/sellers/${SLUG}/webhooks`)).json;
  assert.equal(r.items.length, 2, 'записанное ранее остаётся после повторной настройки');
});

test('подключение: проверка полей, список без секретов, удаление', async () => {
  assert.equal((await connect({ secret: 'коротко' })).status, 400);
  assert.equal((await connect({})).status, 400);
  assert.equal((await connect({ secret: SECRET, unknown: 1 })).status, 400);
  assert.equal((await h.admin('PUT', '/admin/sellers/Плохой Слаг', { body: { secret: SECRET } })).status, 400);
  const ok = await connect();
  assert.equal(ok.status, 200);
  assert.deepEqual(ok.json, { slug: SLUG, hasApiKey: true, secrets: 1, requests: 0, webhooks: 0, rejected: 0 });
  const list = await h.admin('GET', '/admin/sellers');
  assert.equal(list.json.items.length, 1);
  assert.equal((await h.admin('DELETE', `/admin/sellers/${SLUG}`)).status, 200);
  assert.equal((await h.admin('DELETE', `/admin/sellers/${SLUG}`)).status, 404);
  assert.equal((await h.admin('GET', '/admin/state')).json.counts.sellers, 0);
});

test('секрет подписи и API-ключ не выходят наружу: ни в командах управления, ни в журнале', async () => {
  await connect();
  await mode({ behavior: 'duplicate' });
  await sendRequest({ buyerEmail: 'canary@example.test' });
  await sendWebhook();
  await h.idle();
  const [entry] = await requests();
  const texts = [
    JSON.stringify((await h.admin('GET', '/admin/sellers')).json),
    JSON.stringify((await h.admin('GET', `/admin/sellers/${SLUG}/requests`)).json),
    JSON.stringify((await h.admin('GET', `/admin/sellers/${SLUG}/webhooks`)).json),
    JSON.stringify((await h.admin('GET', `/admin/sellers/${SLUG}/rejected`)).json),
    JSON.stringify((await h.admin('GET', '/admin/journal?limit=2000')).json),
    JSON.stringify((await h.admin('GET', '/admin/state')).json),
    JSON.stringify((await h.admin('GET', '/admin/modes')).json),
  ];
  for (const text of texts) {
    assert.ok(!text.includes(SECRET), 'секрет подписи не должен встречаться');
    assert.ok(!text.includes(API_KEY), 'API-ключ не должен встречаться');
  }
  const journal = texts[4];
  for (const key of entry.answers[0].keys) assert.ok(!journal.includes(key), 'значения ключей в журнал не пишутся');
  const answers = (await h.admin('GET', '/admin/journal?kind=seller-answer')).json.items;
  assert.equal(answers.length, 2);
  assert.equal(answers[0].count, 2);
});

test('сброс очищает подключения, запросы и счётчики режима; идентификаторы ключей не повторяются', async () => {
  await connect();
  await mode({ behavior: 'silent' });
  await sendRequest();
  const first = (await requests())[0];
  await h.admin('POST', `/admin/sellers/${SLUG}/requests/${REQUEST_ID}/answer`, { body: {} });
  const keysBefore = (await requests())[0].answers[0].keys;
  await h.reset();
  assert.equal((await h.admin('GET', '/admin/sellers')).json.items.length, 0);
  assert.equal((await h.admin('GET', `/admin/sellers/${SLUG}/requests`)).status, 404);
  await connect();
  await mode({ behavior: 'silent' });
  await sendRequest();
  await h.admin('POST', `/admin/sellers/${SLUG}/requests/${REQUEST_ID}/answer`, { body: {} });
  const keysAfter = (await requests())[0].answers[0].keys;
  assert.equal(first.requestId, REQUEST_ID);
  assert.equal(keysBefore.some((k) => keysAfter.includes(k)), false, 'ключи нового теста не совпадают с ключами прошлого');
});

test('режим продавца: границы и значения по умолчанию', async () => {
  const defaults = (await h.admin('GET', '/admin/modes/seller')).json;
  assert.equal(defaults.request.behavior, 'answer');
  assert.equal(defaults.request.answerDelayMs, 3000);
  assert.equal(defaults.webhook.failFirst, 0);
  assert.equal((await h.admin('PUT', '/admin/modes/seller', { body: { request: { behavior: 'молчит' } } })).status, 400);
  assert.equal((await h.admin('PUT', '/admin/modes/seller', { body: { request: { keysDelta: 11 } } })).status, 400);
  assert.equal((await h.admin('PUT', '/admin/modes/seller', { body: { webhook: { failStatus: 200 } } })).status, 400);
  assert.equal((await h.admin('PUT', '/admin/modes/seller', { body: { webhook: { retry: 1 } } })).status, 400);
});
