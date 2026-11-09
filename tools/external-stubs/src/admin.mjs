// Административный порт: переключение режимов, сброс, журнал, просмотр состояния, ручные события.
// Порт отдельный и в контуре сервера снаружи недоступен (ADR-015); токена нет, защита сетевая.
import { applyOutcome, completeRefund, sendPaymentEvent } from './payment.mjs';
import { emitEmailStatus } from './email.mjs';
import { emitSmsStatus } from './sms.mjs';
import { HttpError, sendJson } from './util.mjs';
import { configureSeller, findSeller, keysFor, publicRequest, publicSeller, publicWebhook, sendAnswer, SLUG } from './seller.mjs';
import { SYSTEMS } from './modes.mjs';

const WEBHOOK_KEYS = { send: 'bool', repeat: 'int', parallel: 'bool', badSignature: 'bool', timestampOffsetSeconds: 'int', retries: 'int', retryDelayMs: 'int' };
const PAYMENT_KEYS = { ...WEBHOOK_KEYS, amountDelta: 'int', currency: 'string', unknownPayment: 'bool', timestamp: 'string', failureReason: 'string', refundId: 'string', type: 'string' };
const STATUS_KEYS = { ...WEBHOOK_KEYS, timestamp: 'string', status: 'string', reason: 'string' };

/** Берёт из тела только известные поля с проверкой типа: переопределения режима для одной команды. */
function overrides(body, allowed) {
  const out = {};
  const errors = [];
  for (const [key, value] of Object.entries(body ?? {})) {
    const type = allowed[key];
    if (!type) errors.push(`${key}: неизвестное поле`);
    else if (type === 'int' ? !Number.isInteger(value) : typeof value !== (type === 'bool' ? 'boolean' : type)) errors.push(`${key}: ожидается ${type === 'int' ? 'целое число' : type === 'bool' ? 'true или false' : 'строка'}`);
    else out[key] = value;
  }
  if (errors.length) throw new HttpError(400, 'invalid_request', 'Команда не принята', errors);
  return out;
}

const splitWebhook = (o) => {
  const webhook = {};
  for (const k of Object.keys(WEBHOOK_KEYS)) if (k in o) webhook[k] = o[k];
  return webhook;
};

const outcomeResult = (payment, deliveries) => ({
  paymentId: payment.paymentId,
  status: payment.status,
  deliveries: (deliveries ?? []).map(({ copy, attempts, status, error }) => ({ copy, attempts, status, error })),
});

export function registerAdmin(router, app) {
  const { state } = app;
  const findPayment = (id) => {
    const p = state.payments.get(id);
    if (!p) throw new HttpError(404, 'payment_not_found', 'Платёж не найден');
    return p;
  };
  const publicPayment = ({ plan, ...rest }) => ({ ...rest, plan });
  const findEmail = (id) => {
    const m = state.emails.find((x) => x.messageId === id);
    if (!m) throw new HttpError(404, 'message_not_found', 'Письмо не найдено');
    return m;
  };
  const findSms = (id) => {
    const m = state.sms.find((x) => x.messageId === id);
    if (!m) throw new HttpError(404, 'message_not_found', 'SMS не найдено');
    return m;
  };

  router.add('GET', '/admin/state', async ({ res }) => {
    sendJson(res, 200, {
      bootTag: state.bootTag,
      modes: state.modes,
      counters: state.counters,
      counts: { payments: state.payments.size, emails: state.emails.length, sms: state.sms.length, sellers: state.sellers.size, journal: state.journal.length },
    });
  });

  router.add('GET', '/admin/modes', async ({ res }) => sendJson(res, 200, state.modes));
  router.add('GET', '/admin/modes/:system', async ({ res, params }) => {
    if (!SYSTEMS.includes(params.system)) throw new HttpError(404, 'unknown_system', `Нет системы «${params.system}». Есть: ${SYSTEMS.join(', ')}`);
    sendJson(res, 200, state.modes[params.system]);
  });
  router.add('PUT', '/admin/modes/:system', async ({ res, params, body }) => {
    sendJson(res, 200, state.setMode(params.system, body));
  });

  router.add('POST', '/admin/reset', async ({ res }) => {
    await app.idle(); // отложенные исходы прошлого теста не должны попасть в следующий
    state.reset();
    sendJson(res, 200, { status: 'reset', modes: state.modes });
  });

  router.add('GET', '/admin/journal', async ({ res, query }) => {
    const since = Number(query.since ?? 0);
    const limit = Math.min(Number(query.limit ?? 500), 2000);
    const items = state.journal.filter((e) => e.n > since && (!query.system || e.system === query.system) && (!query.kind || e.kind === query.kind)).slice(-limit);
    sendJson(res, 200, { last: state.journalSeq, items });
  });
  router.add('DELETE', '/admin/journal', async ({ res }) => {
    state.journal.length = 0;
    sendJson(res, 200, { last: state.journalSeq, items: [] });
  });

  router.add('POST', '/admin/idle', async ({ res }) => {
    await app.idle();
    sendJson(res, 200, { idle: true });
  });

  // Платежи
  router.add('GET', '/admin/payments', async ({ res }) => sendJson(res, 200, { items: [...state.payments.values()].map(publicPayment) }));
  router.add('GET', '/admin/payments/:id', async ({ res, params }) => sendJson(res, 200, publicPayment(findPayment(params.id))));

  const outcomeCommand = (action, outcome) =>
    router.add('POST', `/admin/payments/:id/${action}`, async ({ res, params, body }) => {
      const payment = findPayment(params.id);
      if (payment.status === 'refunded' || payment.status === 'refund_pending') {
        throw new HttpError(409, 'payment_refunded', `Платёж в статусе «${payment.status}»`);
      }
      const o = overrides(body, PAYMENT_KEYS);
      const plan = structuredClone(state.modes.payment);
      const deliveries = await applyOutcome(app, payment, outcome, plan, { ...o, webhook: splitWebhook(o) });
      sendJson(res, 200, outcomeResult(payment, deliveries));
    });
  outcomeCommand('confirm', 'paid');
  outcomeCommand('decline', 'declined');
  outcomeCommand('expire', 'expired');

  router.add('POST', '/admin/payments/:id/refund-complete', async ({ res, params, body }) => {
    const payment = findPayment(params.id);
    const o = overrides(body, PAYMENT_KEYS);
    const plan = structuredClone(state.modes.payment);
    const deliveries = await completeRefund(app, payment, plan, { ...o, webhook: splitWebhook(o) });
    sendJson(res, 200, { ...outcomeResult(payment, deliveries), refund: payment.refund });
  });

  // Уведомление заданного типа без изменения статуса платежа: перестановка, неизвестный тип, дефекты подписи и метки времени
  router.add('POST', '/admin/payments/:id/webhook', async ({ res, params, body }) => {
    const payment = findPayment(params.id);
    const o = overrides(body, PAYMENT_KEYS);
    if (!o.type) throw new HttpError(400, 'invalid_request', 'type: обязательное поле, например payment.paid');
    const plan = structuredClone(state.modes.payment);
    const deliveries = await sendPaymentEvent(app, payment, o.type, plan, { ...o, webhook: splitWebhook(o) });
    sendJson(res, 200, outcomeResult(payment, deliveries));
  });

  // Системы продавцов (Ф5): подключение, принятые запросы и webhook, отклонённые вызовы, ручной ответ. Секрет и API-ключ наружу не выходят
  const slugOf = (params) => {
    if (!SLUG.test(params.slug)) throw new HttpError(400, 'invalid_request', 'slug: строчные латинские буквы, цифры и дефис, до 63 символов');
    return params.slug;
  };
  const secretField = (value, path, errors, required) => {
    if (value === undefined && !required) return undefined;
    if (typeof value !== 'string' || value.length < 8 || value.length > 256) errors.push(`${path}: строка от 8 до 256 символов`);
    return value;
  };

  router.add('GET', '/admin/sellers', async ({ res }) => sendJson(res, 200, { items: [...state.sellers.values()].map(publicSeller) }));
  router.add('PUT', '/admin/sellers/:slug', async ({ res, params, body }) => {
    const slug = slugOf(params);
    const errors = [];
    const input = body ?? {};
    for (const key of Object.keys(input)) if (!['secret', 'previousSecret', 'apiKey'].includes(key)) errors.push(`${key}: неизвестное поле`);
    const secret = secretField(input.secret, 'secret', errors, true);
    const previousSecret = secretField(input.previousSecret, 'previousSecret', errors, false);
    if (input.apiKey !== undefined && (typeof input.apiKey !== 'string' || !input.apiKey || input.apiKey.length > 128)) errors.push('apiKey: строка до 128 символов');
    if (errors.length) throw new HttpError(400, 'invalid_request', 'Команда не принята', errors);
    sendJson(res, 200, publicSeller(configureSeller(state, slug, { secret, previousSecret, apiKey: input.apiKey })));
  });
  router.add('DELETE', '/admin/sellers/:slug', async ({ res, params }) => {
    findSeller(state, params.slug);
    state.sellers.delete(params.slug);
    sendJson(res, 200, { slug: params.slug, deleted: true });
  });
  router.add('GET', '/admin/sellers/:slug/requests', async ({ res, params, query }) => {
    const seller = findSeller(state, params.slug);
    const items = [...seller.requests.values()].filter((r) => (!query.requestId || r.requestId === query.requestId) && (!query.orderNumber || String(r.orderNumber) === String(query.orderNumber)));
    sendJson(res, 200, { items: items.map(publicRequest) });
  });
  router.add('GET', '/admin/sellers/:slug/webhooks', async ({ res, params, query }) => {
    const seller = findSeller(state, params.slug);
    const match = (x) => (!query.type || x.type === query.type) && (!query.eventId || x.eventId === query.eventId);
    sendJson(res, 200, { items: [...seller.webhooks.values()].filter(match).map(publicWebhook), attempts: seller.attempts.filter(match) });
  });
  router.add('GET', '/admin/sellers/:slug/rejected', async ({ res, params }) => sendJson(res, 200, { items: findSeller(state, params.slug).rejected }));

  // Ответ продавца по команде: поздний ответ после окна, ответ вне режима, лишнее поле, чужие ключи. Без полей: те же ключи, что в режиме `answer`
  router.add('POST', '/admin/sellers/:slug/requests/:requestId/answer', async ({ res, params, body }) => {
    const seller = findSeller(state, params.slug);
    const entry = seller.requests.get(params.requestId);
    if (!entry) throw new HttpError(404, 'request_not_found', 'Запрос с таким requestId заглушка не принимала');
    const input = body ?? {};
    const errors = [];
    for (const key of Object.keys(input)) if (!['keys', 'error', 'count', 'extra', 'repeat'].includes(key)) errors.push(`${key}: неизвестное поле`);
    if (input.keys !== undefined && input.error !== undefined) errors.push('keys и error вместе не задаются');
    if (input.keys !== undefined && (!Array.isArray(input.keys) || input.keys.some((k) => typeof k !== 'string'))) errors.push('keys: массив строк');
    if (input.error !== undefined && (typeof input.error !== 'object' || input.error === null || Array.isArray(input.error))) errors.push('error: объект {code, message}');
    if (input.count !== undefined && (!Number.isInteger(input.count) || input.count < 0 || input.count > 20)) errors.push('count: целое от 0 до 20');
    if (input.extra !== undefined && (typeof input.extra !== 'object' || input.extra === null || Array.isArray(input.extra))) errors.push('extra: объект с лишними полями тела');
    if (input.repeat !== undefined && (!Number.isInteger(input.repeat) || input.repeat < 1 || input.repeat > 5)) errors.push('repeat: целое от 1 до 5');
    if (errors.length) throw new HttpError(400, 'invalid_request', 'Команда не принята', errors);
    const payload = input.error !== undefined ? { error: input.error } : { keys: input.keys ?? keysFor(app, entry, 0, input.count) };
    const answers = [];
    for (let i = 0; i < (input.repeat ?? 1); i++) answers.push(await sendAnswer(app, seller, entry, { ...payload, ...(input.extra ?? {}) }));
    sendJson(res, 200, { requestId: entry.requestId, answers });
  });

  // Письма
  router.add('GET', '/admin/emails', async ({ res, query }) => {
    const items = state.emails.filter(
      (m) => (!query.to || m.to.includes(query.to)) && (!query.sender || m.sender === query.sender) && (!query.subject || m.subject.includes(query.subject)),
    );
    sendJson(res, 200, { items: items.map(({ plan, ...m }) => m) });
  });
  router.add('GET', '/admin/emails/:id', async ({ res, params }) => {
    const { plan, ...m } = findEmail(params.id);
    sendJson(res, 200, m);
  });
  router.add('POST', '/admin/emails/:id/status', async ({ res, params, body }) => {
    const message = findEmail(params.id);
    const o = overrides(body, STATUS_KEYS);
    if (!['accepted', 'delivered', 'failed'].includes(o.status)) throw new HttpError(400, 'invalid_request', 'status: accepted, delivered или failed');
    const plan = structuredClone(state.modes.email);
    const deliveries = await emitEmailStatus(app, message, o.status, o.reason, plan, { timestamp: o.timestamp, webhook: splitWebhook(o) });
    sendJson(res, 200, { messageId: message.messageId, status: message.status, deliveries: deliveries.map(({ copy, attempts, status, error }) => ({ copy, attempts, status, error })) });
  });

  // SMS: «телефон» — доставленные сообщения номера
  router.add('GET', '/admin/sms', async ({ res, query }) => {
    const items = state.sms.filter((m) => (!query.to || m.to === query.to) && (query.all === '1' || m.delivery === 'delivered'));
    sendJson(res, 200, { items: items.map(({ plan, ...m }) => m) });
  });
  router.add('POST', '/admin/sms/:id/status', async ({ res, params, body }) => {
    const message = findSms(params.id);
    const o = overrides(body, STATUS_KEYS);
    if (!['delivered', 'failed'].includes(o.status)) throw new HttpError(400, 'invalid_request', 'status: delivered или failed');
    const plan = structuredClone(state.modes.sms);
    const deliveries = await emitSmsStatus(app, message, o.status, plan, { timestamp: o.timestamp, webhook: splitWebhook(o) });
    sendJson(res, 200, { messageId: message.messageId, status: message.status, deliveries: deliveries.map(({ copy, attempts, status, error }) => ({ copy, attempts, status, error })) });
  });
}
