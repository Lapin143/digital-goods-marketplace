// «Система продавца» (Ф5, ADR-028, Р-187, Р-191, Р-193): то, что у настоящего продавца стоит за адресом, записанным в кабинете.
//
//   POST /seller/:slug/request   подписанный запрос платформы «пришлите ключ» (SEQ-07): подпись и метка времени проверяются, запрос
//                                запоминается, ответ 202 «принято»; ключи заглушка присылает отдельным вызовом шлюзу платформы
//                                (`respondSellerDelivery`, заголовок `X-Api-Key`) по режиму `seller.request`
//   POST /seller/:slug/webhook   подписанный webhook о заказе: подпись, метка времени, дубли по `eventId`; режим `seller.webhook`
//                                делает первые N попыток неудачными (повторы отправителя)
//
// Кто такой продавец, заглушке сообщает сценарий командой `PUT /admin/sellers/:slug` (секрет подписи, который платформа показала продавцу
// при подключении, и API-ключ, выпущенный в кабинете). Секрет и ключ хранятся только в памяти, ни в журнал, ни в ответы команд управления
// не попадают. Значения ключей, которые заглушка «выдумала» для ответа, команды управления показывают: сценарию нужно сверить их с письмом покупателя.
import { verify, SIGNATURE_HEADER, TIMESTAMP_HEADER } from './signing.mjs';
import { HttpError, sendJson, sleep } from './util.mjs';

export const SLUG = /^[a-z0-9][a-z0-9-]{0,62}$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const REQUEST_FIELDS = ['requestId', 'orderNumber', 'productId', 'quantity', 'respondBy'];
const WEBHOOK_FIELDS = ['eventId', 'type', 'occurredAt', 'orderNumber', 'productId', 'quantity', 'amount'];
const FAILING = ['http500', 'refuse', 'reject'];
const REJECTED_LIMIT = 200;

const isObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
const isTimestamp = (v) => typeof v === 'string' && /^\d{4}-\d{2}-\d{2}T/.test(v) && !Number.isNaN(Date.parse(v));

/** Что не так в теле запроса платформы. Пустой список: тело годится. Лишние поля не ошибка, но запоминаются (проверка «персональных данных нет»). */
export function requestProblems(body) {
  if (!isObject(body)) return ['тело: ожидается JSON-объект'];
  const errors = [];
  if (typeof body.requestId !== 'string' || !UUID.test(body.requestId)) errors.push('requestId: ожидается UUID');
  // Короткий номер заказа это целое число (OpenAPI, схема заказа: orderNumber 1042), так его и шлёт delivery-service
  if (!Number.isInteger(body.orderNumber) || body.orderNumber < 1) errors.push('orderNumber: целое число от 1');
  if (typeof body.productId !== 'string' || !UUID.test(body.productId)) errors.push('productId: ожидается UUID');
  if (!Number.isInteger(body.quantity) || body.quantity < 1 || body.quantity > 10) errors.push('quantity: целое от 1 до 10');
  if (!isTimestamp(body.respondBy)) errors.push('respondBy: ожидается время RFC 3339');
  return errors;
}

export function webhookProblems(body) {
  if (!isObject(body)) return ['тело: ожидается JSON-объект'];
  const errors = [];
  if (typeof body.eventId !== 'string' || !body.eventId || body.eventId.length > 128) errors.push('eventId: строка от 1 до 128 символов');
  if (typeof body.type !== 'string' || !body.type || body.type.length > 64) errors.push('type: строка от 1 до 64 символов');
  if (!isTimestamp(body.occurredAt)) errors.push('occurredAt: ожидается время RFC 3339');
  return errors;
}

const extraFields = (body, known) => Object.keys(body).filter((k) => !known.includes(k));

/** Подключение «системы продавца»: секрет (и предыдущий, если нужно принять и старую подпись) и API-ключ. Записанное ранее остаётся. */
export function configureSeller(state, slug, { secret, previousSecret, apiKey }) {
  const secrets = [secret, ...(previousSecret ? [previousSecret] : [])];
  const seller = state.sellers.get(slug) ?? { slug, requests: new Map(), webhooks: new Map(), attempts: [], rejected: [] };
  seller.secrets = secrets;
  seller.apiKey = apiKey ?? null;
  state.sellers.set(slug, seller);
  return seller;
}

/** Описание подключения для команд управления: без секрета и ключа. */
export const publicSeller = (seller) => ({
  slug: seller.slug,
  hasApiKey: Boolean(seller.apiKey),
  secrets: seller.secrets.length,
  requests: seller.requests.size,
  webhooks: seller.webhooks.size,
  rejected: seller.rejected.length,
});

export const publicRequest = ({ plan, keyStem, ...entry }) => ({ ...entry, plan: plan ?? null });

export function publicWebhook(entry) {
  return { ...entry };
}

export function findSeller(state, slug) {
  const seller = state.sellers.get(slug);
  if (!seller) throw new HttpError(404, 'unknown_seller', `Система продавца «${slug}» не подключена: команда PUT /admin/sellers/${slug}`);
  return seller;
}

/** Значения ключей для ответа: по одному на единицу заказа плюс сдвиг режима. Одинаковы при повторе ответа (duplicate, ручной ответ). */
export function keysFor(app, entry, delta = 0, count) {
  entry.keyStem ??= app.state.id('SELLR');
  const n = count ?? Math.max(0, entry.quantity + delta);
  return Array.from({ length: n }, (_, i) => `${entry.keyStem}-${i + 1}`);
}

function reject(app, seller, kind, reason, extra = {}) {
  seller.rejected.push({ at: app.clock.now().toISOString(), kind, reason, ...extra });
  if (seller.rejected.length > REJECTED_LIMIT) seller.rejected.splice(0, seller.rejected.length - REJECTED_LIMIT);
}

/** Проверка подписи так, как её делает продавец по документации: тело в том виде, в каком пришло. */
function checkSignature(app, seller, kind, req, raw) {
  const result = verify({
    secrets: seller.secrets,
    timestamp: req.headers[TIMESTAMP_HEADER],
    body: raw.toString('utf8'),
    signature: req.headers[SIGNATURE_HEADER],
    now: app.clock.now().getTime(),
  });
  if (!result.ok) {
    reject(app, seller, kind, result.reason);
    throw new HttpError(401, 'invalid_signature', 'Подпись или метка времени не приняты', [result.reason]);
  }
}

/** Разбор ответа платформы: тип проблемы из `type` (RFC 9457), итог и число принятых ключей. Тело целиком не хранится. */
function summarize(text) {
  try {
    const json = JSON.parse(text);
    const problem = typeof json.type === 'string' ? json.type.split('/').pop() : undefined;
    // `result` это поле status тела (answered, failed); код HTTP остаётся в `status` записи об ответе
    return { problem, result: typeof json.status === 'string' ? json.status : undefined, stored: json.stored, title: typeof json.title === 'string' ? json.title : undefined };
  } catch {
    return {};
  }
}

/**
 * Ответ продавца: вызов `respondSellerDelivery` через шлюз с ключом API. Возвращает запись об ответе; она же попадает в `entry.answers`.
 * Без адреса шлюза или без ключа ответ не отправляется, и это видно в записи (так сценарий понимает, что подключение не настроено).
 */
export async function sendAnswer(app, seller, entry, body) {
  const { state } = app;
  const answer = {
    at: app.clock.now().toISOString(),
    kind: body.error ? 'error' : 'keys',
    count: Array.isArray(body.keys) ? body.keys.length : null,
    keys: Array.isArray(body.keys) ? [...body.keys] : undefined,
    errorCode: body.error?.code,
    status: null,
  };
  if (!app.config.gatewayUrl) answer.skipped = 'STUBS_GATEWAY_URL не задан';
  else if (!seller.apiKey) answer.skipped = 'у подключения нет API-ключа';
  else {
    const url = `${app.config.gatewayUrl}/api/v1/seller-api/delivery-requests/${encodeURIComponent(entry.requestId)}/answer`;
    try {
      const r = await app.call(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json', 'X-Api-Key': seller.apiKey, 'User-Agent': 'dgm-seller-stub/1' },
        body: JSON.stringify(body),
      });
      answer.status = r.status;
      answer.replayed = r.replayed ?? false;
      answer.correlationId = r.correlationId;
      Object.assign(answer, summarize(r.text));
    } catch (e) {
      answer.error = e.message;
    }
  }
  entry.answers.push(answer);
  // В журнал: без значений ключей и без API-ключа
  state.record({
    kind: 'seller-answer',
    system: 'seller',
    slug: seller.slug,
    requestId: entry.requestId,
    answer: answer.kind,
    count: answer.count,
    status: answer.status,
    problem: answer.problem,
    outcome: answer.skipped ? 'skipped' : answer.error ? 'error' : answer.status,
    ...(answer.skipped ? { note: answer.skipped } : {}),
    ...(answer.error ? { note: answer.error } : {}),
  });
  return answer;
}

/** Ответ по плану, сохранённому при приёме запроса: ключи, отказ или тот же ответ дважды. */
async function respondByPlan(app, seller, entry) {
  const { plan } = entry;
  const body = plan.behavior === 'error' ? { error: { code: plan.errorCode, message: 'Отказ по режиму заглушки продавца' } } : { keys: keysFor(app, entry, plan.keysDelta) };
  const copies = plan.behavior === 'duplicate' ? 2 : 1;
  for (let copy = 0; copy < copies; copy++) {
    if (copy > 0 && plan.repeatDelayMs) await sleep(plan.repeatDelayMs);
    await sendAnswer(app, seller, entry, body);
  }
}

export function registerSeller(router, app) {
  const { state } = app;

  router.add('POST', '/seller/:slug/request', async ({ req, res, params, body, raw }) => {
    const seller = findSeller(state, params.slug);
    checkSignature(app, seller, 'request', req, raw);
    const problems = requestProblems(body);
    if (problems.length) {
      reject(app, seller, 'request', 'invalid_body', { problems });
      throw new HttpError(400, 'invalid_request', 'Запрос не принят', problems);
    }

    const mode = state.modes.seller.request;
    const now = app.clock.now().toISOString();
    let entry = seller.requests.get(body.requestId);
    if (!entry) {
      entry = {
        requestId: body.requestId,
        orderNumber: body.orderNumber,
        productId: body.productId,
        quantity: body.quantity,
        respondBy: body.respondBy,
        receivedAt: now,
        timestamp: req.headers[TIMESTAMP_HEADER],
        attempts: 0,
        outcomes: [],
        status: 'received',
        extraFields: extraFields(body, REQUEST_FIELDS),
        body,
        answers: [],
      };
      seller.requests.set(entry.requestId, entry);
    }
    entry.attempts += 1;

    let behavior = mode.behavior;
    if (FAILING.includes(behavior)) {
      if (state.takeFailure('sellerRequestFail')) {
        entry.outcomes.push(behavior);
        if (behavior === 'refuse') {
          req.socket.destroy(); // соединение рвётся без ответа: платформа видит отказ соединения
          return;
        }
        if (behavior === 'http500') throw new HttpError(500, 'seller_unavailable', 'Система продавца недоступна (режим заглушки)');
        throw new HttpError(422, 'rejected', 'Система продавца отклонила запрос (режим заглушки)');
      }
      behavior = mode.thenBehavior;
    }

    entry.outcomes.push('accepted');
    if (!entry.plan) {
      // Первое принятое подтверждение задаёт план. Повтор запроса (платформа не получила 202) второй ответ не порождает
      entry.status = 'accepted';
      entry.plan = {
        behavior,
        answerDelayMs: mode.answerDelayMs,
        repeatDelayMs: mode.repeatDelayMs,
        keysDelta: mode.keysDelta,
        errorCode: mode.errorCode,
      };
      if (behavior !== 'silent') app.schedule(mode.answerDelayMs, () => respondByPlan(app, seller, entry), 'seller answer');
    }
    sendJson(res, 202, { requestId: entry.requestId, status: 'accepted' });
  });

  router.add('POST', '/seller/:slug/webhook', async ({ req, res, params, body, raw }) => {
    const seller = findSeller(state, params.slug);
    checkSignature(app, seller, 'webhook', req, raw);
    const problems = webhookProblems(body);
    if (problems.length) {
      reject(app, seller, 'webhook', 'invalid_body', { problems });
      throw new HttpError(400, 'invalid_request', 'Уведомление не принято', problems);
    }

    const mode = state.modes.seller.webhook;
    if (mode.delayMs) await sleep(mode.delayMs);
    const at = app.clock.now().toISOString();
    const attempt = { at, eventId: body.eventId, type: body.type, timestamp: req.headers[TIMESTAMP_HEADER] };
    const number = seller.attempts.filter((a) => a.eventId === body.eventId).length + 1;
    const record = (outcome) => seller.attempts.push({ ...attempt, attempt: number, outcome });

    if (state.takeFailure('sellerWebhookFail')) {
      if (mode.failure === 'drop') {
        record('dropped');
        req.socket.destroy();
        return;
      }
      record(`failed:${mode.failStatus}`);
      throw new HttpError(mode.failStatus, 'seller_unavailable', 'Система продавца не приняла уведомление (режим заглушки)');
    }

    const known = seller.webhooks.get(body.eventId);
    if (known) {
      known.duplicates += 1;
      record('duplicate');
      return sendJson(res, 200, { eventId: body.eventId, status: 'duplicate' });
    }
    seller.webhooks.set(body.eventId, {
      eventId: body.eventId,
      type: body.type,
      receivedAt: at,
      attempts: number,
      duplicates: 0,
      extraFields: extraFields(body, WEBHOOK_FIELDS),
      body,
    });
    record('accepted');
    sendJson(res, 200, { eventId: body.eventId, status: 'ok' });
  });
}
