// E-mail-провайдер: приём писем по REST и SMTP, статусы доставки вебхуком, просмотр писем.
import { bearerToken, escapeHtml, HttpError, secureEquals, sendHtml, sendJson, sleep } from './util.mjs';

const EMAIL = /^[^\s@<>]+@[^\s@<>]+\.[^\s@<>]+$/;

export function senderFor(app, req) {
  const token = bearerToken(req);
  const { deliveryEmailKey, platformEmailKey } = app.config.secrets;
  if (token && secureEquals(token, deliveryEmailKey)) return 'delivery';
  if (token && secureEquals(token, platformEmailKey)) return 'platform';
  throw new HttpError(401, 'unauthorized', 'Нужен заголовок Authorization: Bearer <ключ e-mail-провайдера>');
}

/** Решает, откажет ли режим на этом письме. Счётчик «первые N» и доля неудач расходуются только на подходящих письмах. */
export function shouldFail(app, sender) {
  const { state } = app;
  const m = state.modes.email;
  if (m.behavior === 'accept') return false;
  if (m.sender !== 'any' && m.sender !== sender) return false;
  if (state.counters.emailFail === 0) return false;
  if (m.failRate < 1 && state.rng() >= m.failRate) return false;
  state.takeFailure('emailFail');
  return true;
}

function validate(body) {
  const errors = [];
  if (!body || typeof body !== 'object' || Array.isArray(body)) throw new HttpError(400, 'invalid_request', 'Ожидается JSON-объект');
  const to = Array.isArray(body.to) ? body.to : [body.to];
  if (!to.length || !to.every((a) => typeof a === 'string' && EMAIL.test(a))) errors.push('to: адрес или список адресов');
  if (typeof body.subject !== 'string' || !body.subject) errors.push('subject: непустая строка');
  if (body.text === undefined && body.html === undefined) errors.push('нужно text или html');
  for (const f of ['text', 'html']) if (body[f] !== undefined && typeof body[f] !== 'string') errors.push(`${f}: строка`);
  if (body.messageId !== undefined && (typeof body.messageId !== 'string' || !body.messageId || body.messageId.length > 128)) {
    errors.push('messageId: строка до 128 символов');
  }
  if (errors.length) throw new HttpError(400, 'invalid_request', 'Запрос не принят', errors);
  return to;
}

/** Хранит письмо и ставит в очередь итоговый статус. Общий путь для REST и SMTP. */
export function storeMessage(app, { sender, via, messageId, to, subject, text, html, from }) {
  const { state, clock } = app;
  const mode = state.modes.email;
  const first = state.emails.find((m) => m.messageId === messageId);
  if (first && mode.dedupe) {
    first.receivedCount += 1;
    return { message: first, duplicate: true };
  }
  const message = {
    seq: state.emails.length + 1,
    messageId,
    sender,
    via,
    from,
    to,
    subject,
    text,
    html,
    receivedAt: clock.now().toISOString(),
    receivedCount: 1,
    status: 'accepted',
    duplicateOf: first?.seq,
    plan: structuredClone(mode),
  };
  state.emails.push(message);
  if (message.plan.status !== 'none') {
    app.schedule(message.plan.statusDelayMs, () => emitEmailStatus(app, message, message.plan.status, message.plan.failReason, message.plan), 'email status');
  }
  return { message, duplicate: false };
}

const WEBHOOK_TARGET = {
  delivery: (app) => ({ url: app.config.webhookUrls.deliveryEmail, secret: app.config.secrets.deliveryWebhookSecret }),
  platform: (app) => ({ url: app.config.webhookUrls.platformEmail, secret: app.config.secrets.platformWebhookSecret }),
};

/** Меняет статус письма у провайдера и присылает вебхук (если письмо пришло по REST). */
export async function emitEmailStatus(app, message, status, reason, plan, extra = {}) {
  message.status = status;
  if (status === 'failed') message.reason = reason;
  const target = WEBHOOK_TARGET[message.sender];
  if (!target) {
    app.state.record({ kind: 'webhook', system: 'email', messageId: message.messageId, outcome: 'skipped', note: 'письма по SMTP статуса вебхуком не получают' });
    return [];
  }
  const event = { messageId: message.messageId, status, occurredAt: app.clock.now().toISOString() };
  if (status === 'failed') event.reason = reason ?? 'other';
  return app.webhooks.deliver({
    system: 'email',
    ...target(app),
    event,
    options: { ...plan.webhook, ...(extra.webhook ?? {}) },
    timestamp: extra.timestamp,
    meta: { messageId: message.messageId, sender: message.sender },
  });
}

async function failResponse(app, res, mode) {
  if (mode.behavior === 'perm_error') {
    return sendJson(res, mode.permStatus, { error: { code: 'address_rejected', message: 'Адрес получателя отклонён' } });
  }
  if (mode.tempKind === 'timeout') {
    await sleep(mode.timeoutMs);
    res.socket?.destroy();
    return;
  }
  if (mode.tempKind === 'http_429') {
    return sendJson(res, 429, { error: { code: 'rate_limited', message: 'Слишком много писем' } }, { 'Retry-After': '1' });
  }
  return sendJson(res, 503, { error: { code: 'unavailable', message: 'Провайдер временно недоступен' } });
}

export function registerEmail(router, app) {
  const { state } = app;

  router.add('POST', '/email/v1/messages', async ({ req, res, body }) => {
    const sender = senderFor(app, req);
    const to = validate(body);
    if (shouldFail(app, sender)) return failResponse(app, res, state.modes.email);
    const messageId = body.messageId ?? state.id('msg');
    const { message, duplicate } = storeMessage(app, { sender, via: 'api', messageId, to, subject: body.subject, text: body.text, html: body.html });
    sendJson(res, 202, { messageId: message.messageId, status: 'accepted' }, duplicate ? { 'Idempotent-Replayed': 'true' } : {});
  });

  router.add('GET', '/email/v1/messages/:id', async ({ req, res, params }) => {
    const sender = senderFor(app, req);
    const message = state.emails.find((m) => m.messageId === params.id && m.sender === sender);
    if (!message) throw new HttpError(404, 'message_not_found', 'Письмо не найдено');
    sendJson(res, 200, { messageId: message.messageId, status: message.status, ...(message.reason ? { reason: message.reason } : {}) });
  });

  // Просмотр писем: Keycloak, оповещения и письма платформы в одном месте (для демонстрации и ручной проверки)
  router.add('GET', '/mail', async ({ res }) => sendHtml(res, 200, mailList(state)));
  router.add('GET', '/mail/:seq', async ({ res, params }) => {
    const m = state.emails.find((x) => String(x.seq) === params.seq);
    if (!m) throw new HttpError(404, 'message_not_found', 'Письмо не найдено');
    sendHtml(res, 200, mailPage(m));
  });
}

const PAGE_STYLE = '<style>body{font:15px system-ui;max-width:60rem;margin:2rem auto;padding:0 1rem}table{border-collapse:collapse;width:100%}td,th{border-bottom:1px solid #ddd;padding:.3rem .5rem;text-align:left}pre{white-space:pre-wrap;background:#f6f6f6;padding:1rem}</style>';

function mailList(state) {
  const rows = [...state.emails].reverse().map((m) =>
    `<tr><td>${escapeHtml(m.receivedAt)}</td><td>${escapeHtml(m.sender)}</td><td>${escapeHtml(m.to.join(', '))}</td><td><a href="/mail/${m.seq}">${escapeHtml(m.subject)}</a></td><td>${escapeHtml(m.status)}</td></tr>`).join('');
  const sms = [...state.sms].reverse().map((m) =>
    `<tr><td>${escapeHtml(m.receivedAt)}</td><td>${escapeHtml(m.to)}</td><td>${escapeHtml(m.text)}</td><td>${escapeHtml(m.delivery)}</td></tr>`).join('');
  return `<!doctype html><html lang="ru"><head><meta charset="utf-8"><title>Письма и SMS (заглушка)</title>${PAGE_STYLE}</head><body>
<h1>Письма</h1><table><tr><th>Время</th><th>От системы</th><th>Кому</th><th>Тема</th><th>Статус</th></tr>${rows || '<tr><td colspan="5">Писем нет</td></tr>'}</table>
<h1>SMS</h1><table><tr><th>Время</th><th>Телефон</th><th>Текст</th><th>Доставка</th></tr>${sms || '<tr><td colspan="4">SMS нет</td></tr>'}</table>
</body></html>`;
}

function mailPage(m) {
  return `<!doctype html><html lang="ru"><head><meta charset="utf-8"><title>${escapeHtml(m.subject)}</title>${PAGE_STYLE}</head><body>
<p><a href="/mail">← все письма</a></p><h1>${escapeHtml(m.subject)}</h1>
<p>Кому: ${escapeHtml(m.to.join(', '))}<br>Получено: ${escapeHtml(m.receivedAt)}, канал: ${escapeHtml(m.via)}, статус: ${escapeHtml(m.status)}</p>
<h2>Текст</h2><pre>${escapeHtml(m.text ?? '(нет текстовой части)')}</pre>
${m.html ? `<h2>HTML (как есть)</h2><pre>${escapeHtml(m.html)}</pre>` : ''}
</body></html>`;
}

