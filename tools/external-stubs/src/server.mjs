// Сборка заглушки: два HTTP(S)-сервера (прикладной и административный) и SMTP.
import http from 'node:http';
import https from 'node:https';
import { createApp } from './app.mjs';
import { createRouter } from './router.mjs';
import { registerAdmin } from './admin.mjs';
import { registerEmail } from './email.mjs';
import { registerPayment } from './payment.mjs';
import { registerSms } from './sms.mjs';
import { registerVkid } from './vkid.mjs';
import { createSmtpServer } from './smtp.mjs';
import { HttpError, parseBody, readBody, sendJson } from './util.mjs';

const SENSITIVE = /secret|password|token|verifier|code$/i;

function maskBody(body) {
  if (!body || typeof body !== 'object') return typeof body === 'string' ? body.slice(0, 4096) : body;
  return Object.fromEntries(Object.entries(body).map(([k, v]) => [k, SENSITIVE.test(k) && typeof v === 'string' ? '***' : v]));
}

const systemOf = (path) =>
  path.startsWith('/payment/') ? 'payment' : path.startsWith('/email/') || path.startsWith('/mail') ? 'email' : path.startsWith('/sms/') ? 'sms' : path.startsWith('/vkid/') ? 'vkid' : 'stubs';

function makeHandler(app, router, { journal }) {
  return async (req, res) => {
    const started = Date.now();
    const url = new URL(req.url, 'http://stubs');
    let body;
    // Запись в журнал по закрытию соединения: так видны и запросы, на которые заглушка намеренно не ответила
    res.once('close', () => {
      const status = res.headersSent ? res.statusCode : 0;
      if (journal && url.pathname !== '/health') {
        app.state.record({
          kind: 'request',
          system: systemOf(url.pathname),
          method: req.method,
          path: url.pathname,
          status,
          idempotencyKey: req.headers['idempotency-key'],
          body: maskBody(body),
          ...(status === 0 ? { note: 'соединение закрыто без ответа' } : {}),
        });
      }
      if (!app.config.quiet) console.log(JSON.stringify({ t: new Date().toISOString(), m: req.method, p: url.pathname, s: status, ms: Date.now() - started }));
    });
    try {
      if (url.pathname === '/health' && req.method === 'GET') return sendJson(res, 200, { status: 'UP' });
      const found = router.match(req.method, url.pathname);
      if (!found) throw new HttpError(404, 'not_found', `Нет маршрута ${req.method} ${url.pathname}`);
      if (found.allowed) {
        res.setHeader('Allow', [...new Set(found.allowed)].join(', '));
        throw new HttpError(405, 'method_not_allowed', `Для ${url.pathname} допустимо: ${[...new Set(found.allowed)].join(', ')}`);
      }
      const raw = req.method === 'GET' || req.method === 'HEAD' ? Buffer.alloc(0) : await readBody(req);
      body = parseBody(req.headers['content-type'], raw);
      await found.handler({ req, res, url, query: Object.fromEntries(url.searchParams), params: found.params, body, raw });
    } catch (e) {
      if (!(e instanceof HttpError)) app.state.record({ kind: 'internal', system: 'stubs', outcome: 'error', note: e.stack?.split('\n')[0] ?? String(e) });
      if (!res.headersSent && !res.destroyed) {
        const status = e instanceof HttpError ? e.status : 500;
        sendJson(res, status, { error: { code: e instanceof HttpError ? e.code : 'internal_error', message: e instanceof HttpError ? e.message : 'Внутренняя ошибка заглушки', ...(e.details ? { details: e.details } : {}) } });
      }
    }
  };
}

export function createStubs(config, deps = {}) {
  const app = createApp(config, deps);

  const api = createRouter();
  registerPayment(api, app);
  registerEmail(api, app);
  registerSms(api, app);
  registerVkid(api, app);

  const admin = createRouter();
  registerAdmin(admin, app);

  const tlsOptions = config.tls ? { key: config.tls.key, cert: config.tls.cert, minVersion: 'TLSv1.2' } : null;
  const make = (handler) => (tlsOptions ? https.createServer(tlsOptions, handler) : http.createServer(handler));
  const apiServer = make(makeHandler(app, api, { journal: true }));
  const adminServer = make(makeHandler(app, admin, { journal: false }));
  const smtpServer = createSmtpServer(app);
  const servers = [apiServer, adminServer, smtpServer];

  const listen = (server, port) =>
    new Promise((resolve, reject) => {
      server.once('error', reject);
      server.listen(port, config.bind, () => resolve(server.address().port));
    });

  return {
    app,
    state: app.state,
    async start() {
      const [apiPort, adminPort, smtpPort] = await Promise.all([
        listen(apiServer, config.apiPort),
        listen(adminServer, config.adminPort),
        listen(smtpServer, config.smtpPort),
      ]);
      return { apiPort, adminPort, smtpPort };
    },
    async stop() {
      await Promise.all(
        servers.map(
          (s) =>
            new Promise((resolve) => {
              s.close(() => resolve());
              s.closeAllConnections?.();
            }),
        ),
      );
    },
  };
}
