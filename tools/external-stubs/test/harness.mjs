// Общая обвязка тестов: заглушка на свободных портах без TLS и «получатель вебхуков» на обычном HTTP.
import http from 'node:http';
import { createStubs } from '../src/server.mjs';

export const SECRETS = {
  paymentGatewayKey: 'gw-key',
  paymentWebhookSecret: 'pay-hook-secret',
  deliveryEmailKey: 'delivery-key',
  deliveryWebhookSecret: 'delivery-hook-secret',
  platformEmailKey: 'platform-mail-key',
  platformSmsKey: 'sms-key',
  platformWebhookSecret: 'platform-hook-secret',
};

export async function startHarness({ webhookStatuses = [] } = {}) {
  // Получатель вебхуков: запоминает всё, что пришло; ответы берёт из очереди statuses, затем отвечает 200
  const hooks = [];
  const statuses = [...webhookStatuses];
  const receiver = http.createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      hooks.push({ path: req.url, headers: req.headers, body: Buffer.concat(chunks).toString('utf8') });
      res.statusCode = statuses.length ? statuses.shift() : 200;
      res.end();
    });
  });
  await new Promise((r) => receiver.listen(0, '127.0.0.1', r));
  const rp = receiver.address().port;
  const base = `http://127.0.0.1:${rp}`;

  const config = {
    bind: '127.0.0.1',
    apiPort: 0,
    adminPort: 0,
    smtpPort: 0,
    publicUrl: 'http://stubs.test',
    tls: null,
    secrets: SECRETS,
    webhookUrls: {
      payment: `${base}/payment-gateway`,
      deliveryEmail: `${base}/delivery-email`,
      platformEmail: `${base}/platform-email`,
      platformSms: `${base}/platform-sms`,
    },
    quiet: true,
  };
  const stubs = createStubs(config, { bootTag: 't', pause: () => Promise.resolve() });
  const ports = await stubs.start();

  const call = (port, token) => async (method, path, { body, headers = {}, raw, redirect } = {}) => {
    const h = { ...headers };
    if (token) h.Authorization = `Bearer ${token}`;
    let payload = raw;
    if (body !== undefined) {
      h['Content-Type'] = h['Content-Type'] ?? 'application/json';
      payload = JSON.stringify(body);
    }
    const res = await fetch(`http://127.0.0.1:${port}${path}`, { method, headers: h, body: payload, redirect: redirect ?? 'follow' });
    const text = await res.text();
    let json;
    try {
      json = JSON.parse(text);
    } catch {
      json = undefined;
    }
    return { status: res.status, headers: res.headers, text, json };
  };

  const h = {
    stubs,
    state: stubs.state,
    ports,
    hooks,
    hooksOf: (path) => hooks.filter((x) => x.path === path),
    api: (token) => call(ports.apiPort, token),
    admin: call(ports.adminPort),
    gw: call(ports.apiPort, SECRETS.paymentGatewayKey),
    idle: () => stubs.app.idle(),
    async setMode(system, mode) {
      const r = await h.admin('PUT', `/admin/modes/${system}`, { body: mode });
      if (r.status !== 200) throw new Error(`режим ${system} не принят: ${r.text}`);
    },
    async reset() {
      await h.admin('POST', '/admin/reset');
      hooks.length = 0;
    },
    async stop() {
      await stubs.stop();
      await new Promise((r) => {
        receiver.close(r);
        receiver.closeAllConnections();
      });
    },
  };
  return h;
}

/** Создаёт платёж через API шлюза. */
export function newPayment(h, { orderId = 'order-1', amount = 299800, key = orderId, extra = {} } = {}) {
  return h.gw('POST', '/payment/v1/payments', {
    headers: { 'Idempotency-Key': key },
    body: { orderId, amount: { amount, currency: 'RUB' }, returnUrl: 'https://shop.test/return', ...extra },
  });
}

export const wait = (ms) => new Promise((r) => setTimeout(r, ms));
