// SMS-провайдер: приём сообщения, «телефон» (журнал доставленных SMS), статус доставки вебхуком.
import { bearerToken, HttpError, secureEquals, sendJson } from './util.mjs';

const PHONE = /^\+\d{10,15}$/;

export function requireSmsAuth(app, req) {
  const token = bearerToken(req);
  if (!token || !secureEquals(token, app.config.secrets.platformSmsKey)) {
    throw new HttpError(401, 'unauthorized', 'Нужен заголовок Authorization: Bearer <ключ SMS-провайдера>');
  }
}

/** Код из текста SMS: первая группа из 4–8 цифр. Тесту не нужно разбирать текст самому. */
export const extractCode = (text) => /(?<!\d)(\d{4,8})(?!\d)/.exec(text)?.[1] ?? null;

export async function emitSmsStatus(app, message, status, plan, extra = {}) {
  message.status = status;
  const event = { messageId: message.messageId, status, occurredAt: app.clock.now().toISOString() };
  return app.webhooks.deliver({
    system: 'sms',
    url: app.config.webhookUrls.platformSms,
    secret: app.config.secrets.platformWebhookSecret,
    event,
    options: { ...plan.webhook, ...(extra.webhook ?? {}) },
    timestamp: extra.timestamp,
    meta: { messageId: message.messageId },
  });
}

export function registerSms(router, app) {
  const { state } = app;

  router.add('POST', '/sms/v1/messages', async ({ req, res, body }) => {
    requireSmsAuth(app, req);
    const errors = [];
    if (!body || typeof body !== 'object' || Array.isArray(body)) throw new HttpError(400, 'invalid_request', 'Ожидается JSON-объект');
    if (typeof body.to !== 'string' || !PHONE.test(body.to)) errors.push('to: номер в формате E.164, например +79991234567');
    if (typeof body.text !== 'string' || !body.text || body.text.length > 1000) errors.push('text: строка от 1 до 1000 символов');
    if (errors.length) throw new HttpError(400, 'invalid_request', 'Запрос не принят', errors);

    const mode = state.modes.sms;
    if (mode.behavior === 'error') throw new HttpError(503, 'unavailable', 'SMS-провайдер временно недоступен');
    if (mode.behavior === 'reject') throw new HttpError(422, 'invalid_phone', 'Номер отклонён провайдером');

    const delivered = mode.behavior === 'deliver';
    const message = {
      messageId: state.id('sms'),
      to: body.to,
      text: body.text,
      code: extractCode(body.text),
      receivedAt: app.clock.now().toISOString(),
      delivery: delivered ? 'delivered' : mode.behavior === 'late' ? 'pending' : 'dropped',
      status: 'accepted',
      plan: structuredClone(mode),
    };
    state.sms.push(message);

    if (delivered) {
      app.schedule(mode.statusDelayMs, () => emitSmsStatus(app, message, 'delivered', message.plan), 'sms status');
    } else if (mode.behavior === 'late') {
      app.schedule(mode.lateMs, async () => {
        message.delivery = 'delivered';
        message.deliveredAt = app.clock.now().toISOString();
        await emitSmsStatus(app, message, 'delivered', message.plan);
      }, 'sms late');
    }
    sendJson(res, 202, { messageId: message.messageId, status: 'accepted' });
  });
}
