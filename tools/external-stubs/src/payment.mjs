// Платёжный шлюз. Интерфейс REST и вебхуки по ADR-015; поведением управляют режимы (modes.mjs) и административные команды.
import { randomUUID } from 'node:crypto';
import { bearerToken, escapeHtml, fingerprint, HttpError, secureEquals, sendHtml, sendJson, sendEmpty, sleep } from './util.mjs';

const CURRENCY = /^[A-Z]{3}$/;

export function requirePaymentAuth(app, req) {
  const token = bearerToken(req);
  if (!token || !secureEquals(token, app.config.secrets.paymentGatewayKey)) {
    throw new HttpError(401, 'unauthorized', 'Нужен заголовок Authorization: Bearer <ключ шлюза>');
  }
}

function validateCreate(body) {
  const errors = [];
  if (!body || typeof body !== 'object' || Array.isArray(body)) throw new HttpError(400, 'invalid_request', 'Ожидается JSON-объект');
  const { amount, orderId, returnUrl, description } = body;
  if (!amount || !Number.isInteger(amount.amount) || amount.amount <= 0) errors.push('amount.amount: целое число копеек больше нуля');
  if (!amount || typeof amount.currency !== 'string' || !CURRENCY.test(amount.currency)) errors.push('amount.currency: код из трёх заглавных букв');
  if (typeof orderId !== 'string' || !orderId || orderId.length > 128) errors.push('orderId: непустая строка до 128 символов');
  if (returnUrl !== undefined && (typeof returnUrl !== 'string' || !/^https?:\/\//.test(returnUrl))) errors.push('returnUrl: адрес http(s)');
  if (description !== undefined && typeof description !== 'string') errors.push('description: строка');
  if (errors.length) throw new HttpError(400, 'invalid_request', 'Запрос не принят', errors);
}

const view = (p) => ({
  paymentId: p.paymentId,
  orderId: p.orderId,
  amount: p.amount,
  status: p.status,
  payUrl: p.payUrl,
  createdAt: p.createdAt,
  expiresAt: p.expiresAt,
  ...(p.refund ? { refund: p.refund } : {}),
});

/** Меняет статус платежа у шлюза и фиксирует это в истории. */
function setStatus(app, payment, status) {
  payment.status = status;
  payment.history.push({ at: app.clock.now().toISOString(), status });
}

function moneyOf(payment, options) {
  return { amount: Math.max(0, payment.amount.amount + (options.amountDelta ?? 0)), currency: options.currency ?? payment.amount.currency };
}

/**
 * Отправляет уведомление о платеже получателю (payment-service). Режим webhook берётся из `plan`:
 * у сценария это снимок режима на момент создания платежа, у административной команды текущий режим плюс переопределения.
 */
export async function sendPaymentEvent(app, payment, type, plan, extra = {}) {
  const options = plan.webhook;
  const unknown = options.unknownPayment || extra.unknownPayment;
  const data = unknown
    ? { paymentId: app.state.id('pay_unknown'), orderId: randomUUID(), amount: moneyOf(payment, options) }
    : { paymentId: payment.paymentId, orderId: payment.orderId, amount: moneyOf(payment, { ...options, ...extra }) };
  if (type === 'payment.declined') data.failureReason = extra.failureReason ?? plan.failureReason ?? 'declined';
  if (type === 'refund.completed') data.refundId = extra.refundId ?? payment.refund?.refundId;
  const event = { eventId: app.state.id('evt'), type, occurredAt: app.clock.now().toISOString(), data };
  payment.webhooksSent = (payment.webhooksSent ?? 0) + 1;
  return app.webhooks.deliver({
    system: 'payment',
    url: app.config.webhookUrls.payment,
    secret: app.config.secrets.paymentWebhookSecret,
    event,
    options: { ...options, ...(extra.webhook ?? {}) },
    timestamp: extra.timestamp,
    meta: { paymentId: payment.paymentId, orderId: payment.orderId },
  });
}

/** Исход платежа: статус у шлюза и уведомление. Вызывается сценарием, страницей оплаты и административными командами. */
export async function applyOutcome(app, payment, outcome, plan, extra = {}) {
  if (outcome === 'paid') {
    setStatus(app, payment, 'paid');
    return sendPaymentEvent(app, payment, 'payment.paid', plan, extra);
  }
  if (outcome === 'declined') {
    setStatus(app, payment, 'declined');
    return sendPaymentEvent(app, payment, 'payment.declined', plan, extra);
  }
  if (outcome === 'expired') {
    setStatus(app, payment, 'expired');
    return sendPaymentEvent(app, payment, 'payment.session-expired', plan, extra);
  }
  throw new Error(`неизвестный исход ${outcome}`);
}

function scenarioOutcome(plan) {
  return { success: 'paid', reject: 'declined', expire: 'expired' }[plan.scenario] ?? null;
}

export async function completeRefund(app, payment, plan, extra = {}) {
  if (!payment.refund) {
    payment.refund = { refundId: app.state.id('ref'), status: 'pending', amount: payment.amount, manual: true };
  }
  payment.refund.status = 'completed';
  setStatus(app, payment, 'refunded');
  return sendPaymentEvent(app, payment, 'refund.completed', plan, { refundId: payment.refund.refundId, ...extra });
}

async function failCreate(app, res, mode) {
  const { failure, timeoutMs } = mode.create;
  if (failure === 'timeout') {
    // Соединение держится открытым дольше, чем ждёт клиент, затем закрывается без ответа
    await sleep(timeoutMs);
    res.socket?.destroy();
    return;
  }
  if (failure === 'http_429') {
    sendJson(res, 429, { error: { code: 'rate_limited', message: 'Слишком много запросов' } }, { 'Retry-After': '1' });
    return;
  }
  sendJson(res, 503, { error: { code: 'unavailable', message: 'Шлюз временно недоступен' } });
}

export function registerPayment(router, app) {
  const { state } = app;

  router.add('POST', '/payment/v1/payments', async ({ req, res, body }) => {
    requirePaymentAuth(app, req);
    const mode = state.modes.payment;
    if (state.takeFailure('createFail')) return failCreate(app, res, mode);

    const key = req.headers['idempotency-key'];
    if (!key) throw new HttpError(400, 'idempotency_key_required', 'Нужен заголовок Idempotency-Key');
    validateCreate(body);

    const storeKey = `create:${key}`;
    const fp = fingerprint(body);
    const known = state.idempotency.get(storeKey);
    if (known) {
      if (known.fingerprint !== fp) throw new HttpError(409, 'idempotency_conflict', 'Ключ уже использован с другими параметрами');
      return sendJson(res, known.status, view(state.payments.get(known.paymentId)), { 'Idempotent-Replayed': 'true' });
    }

    const now = app.clock.now();
    const paymentId = state.id('pay');
    const payment = {
      paymentId,
      orderId: body.orderId,
      amount: { amount: body.amount.amount, currency: body.amount.currency },
      status: 'pending',
      createdAt: now.toISOString(),
      expiresAt: new Date(now.getTime() + mode.sessionTtlSeconds * 1000).toISOString(),
      returnUrl: body.returnUrl,
      payUrl: `${app.config.publicUrl}/payment/pay/${paymentId}`,
      idempotencyKey: key,
      history: [{ at: now.toISOString(), status: 'pending' }],
      plan: structuredClone(mode), // режим на момент создания: сценарий этого платежа не меняется, если режим переключат позже
    };
    state.payments.set(paymentId, payment);
    state.idempotency.set(storeKey, { fingerprint: fp, status: 201, paymentId });

    const outcome = scenarioOutcome(payment.plan);
    if (outcome) app.schedule(payment.plan.delayMs, () => applyOutcome(app, payment, outcome, payment.plan), `payment ${outcome}`);

    if (state.takeFailure('createLose')) {
      // Платёж создан и сценарий запущен, но клиент ответа не получит
      res.socket?.destroy();
      return;
    }
    sendJson(res, 201, view(payment), { Location: `/payment/v1/payments/${paymentId}` });
  });

  router.add('GET', '/payment/v1/payments/:id', async ({ req, res, params }) => {
    requirePaymentAuth(app, req);
    const payment = state.payments.get(params.id);
    if (!payment) throw new HttpError(404, 'payment_not_found', 'Платёж не найден');
    sendJson(res, 200, view(payment));
  });

  router.add('POST', '/payment/v1/payments/:id/refunds', async ({ req, res, params, body }) => {
    requirePaymentAuth(app, req);
    const mode = state.modes.payment.refund;
    const key = req.headers['idempotency-key'];
    if (!key) throw new HttpError(400, 'idempotency_key_required', 'Нужен заголовок Idempotency-Key');
    const payment = state.payments.get(params.id);
    if (!payment) throw new HttpError(404, 'payment_not_found', 'Платёж не найден');

    const storeKey = `refund:${key}`;
    const fp = fingerprint({ id: params.id, body });
    const known = state.idempotency.get(storeKey);
    if (known) {
      if (known.fingerprint !== fp) throw new HttpError(409, 'idempotency_conflict', 'Ключ уже использован с другими параметрами');
      return sendJson(res, 200, { ...payment.refund, paymentId: payment.paymentId }, { 'Idempotent-Replayed': 'true' });
    }

    if (mode.mode === 'reject') throw new HttpError(422, 'refund_rejected', 'Шлюз не принимает возврат');
    if (state.takeFailure('refundFail')) throw new HttpError(503, 'unavailable', 'Шлюз временно недоступен');
    if (payment.status !== 'paid') throw new HttpError(409, 'payment_not_refundable', `Платёж в статусе «${payment.status}»`);
    const a = body?.amount;
    if (!a || a.amount !== payment.amount.amount || a.currency !== payment.amount.currency) {
      throw new HttpError(422, 'refund_amount_invalid', 'Возврат возможен только на полную сумму платежа');
    }

    payment.refund = { refundId: state.id('ref'), status: mode.mode === 'instant' ? 'completed' : 'pending', amount: payment.amount };
    state.idempotency.set(storeKey, { fingerprint: fp, status: 201 });
    if (mode.mode === 'instant') {
      setStatus(app, payment, 'refunded');
      const plan = structuredClone(state.modes.payment);
      app.schedule(mode.delayMs, () => sendPaymentEvent(app, payment, 'refund.completed', plan, { refundId: payment.refund.refundId }), 'refund webhook');
    } else {
      setStatus(app, payment, 'refund_pending');
    }
    sendJson(res, 201, { ...payment.refund, paymentId: payment.paymentId });
  });

  // Страница оплаты: покупатель выбирает исход вручную (интерактивная демонстрация, сценарий hold).
  router.add('GET', '/payment/pay/:id', async ({ res, params }) => {
    const p = state.payments.get(params.id);
    if (!p) throw new HttpError(404, 'payment_not_found', 'Платёж не найден');
    sendHtml(res, 200, payPage(p));
  });

  router.add('POST', '/payment/pay/:id', async ({ res, params, body }) => {
    const p = state.payments.get(params.id);
    if (!p) throw new HttpError(404, 'payment_not_found', 'Платёж не найден');
    const action = body?.action;
    if (!['pay', 'decline'].includes(action)) throw new HttpError(400, 'invalid_request', 'action: pay или decline');
    if (['pending', 'expired'].includes(p.status)) {
      const plan = { ...structuredClone(state.modes.payment) };
      await applyOutcome(app, p, action === 'pay' ? 'paid' : 'declined', plan);
    }
    if (p.returnUrl) {
      const target = new URL(p.returnUrl);
      target.searchParams.set('paymentId', p.paymentId);
      return sendEmpty(res, 303, { Location: target.toString() });
    }
    sendHtml(res, 200, payPage(p));
  });
}

function payPage(p) {
  const rub = (p.amount.amount / 100).toFixed(2);
  const open = ['pending', 'expired'].includes(p.status);
  return `<!doctype html><html lang="ru"><head><meta charset="utf-8"><title>Страница оплаты (заглушка)</title>
<style>body{font:16px system-ui;max-width:32rem;margin:3rem auto;padding:0 1rem}button{font:inherit;padding:.5rem 1rem;margin-right:.5rem}.note{color:#666}</style></head><body>
<h1>Платёжный шлюз (заглушка)</h1>
<p class="note">Это учебный стенд. Карта не нужна, деньги не списываются.</p>
<p>Платёж <b>${escapeHtml(p.paymentId)}</b>, заказ ${escapeHtml(p.orderId)}</p>
<p>Сумма: <b>${rub} ${escapeHtml(p.amount.currency)}</b>. Статус: <b>${escapeHtml(p.status)}</b></p>
${open ? `<form method="post"><button name="action" value="pay">Оплатить</button><button name="action" value="decline">Отклонить</button></form>` : ''}
</body></html>`;
}
