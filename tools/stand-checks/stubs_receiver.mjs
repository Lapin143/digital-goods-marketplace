// Получатель вебхуков для проверки контейнера заглушек: играет api-gateway (публичный вход платформы).
// Принимает HTTPS на 8443 с сертификатом api-gateway, проверяет подпись вебхука секретом источника, свежесть метки времени и клиентский
// сертификат заглушки, печатает по строке JSON на каждый запрос. Запуск и разбор журнала: tools/stand-checks/stubs_checks.sh.
// Подпись проверяется отдельной реализацией (не кодом заглушки), чтобы проверка не подтверждала ошибку саму себя.
import fs from 'node:fs';
import https from 'node:https';
import { createHmac, timingSafeEqual } from 'node:crypto';

const read = (name) => fs.readFileSync(`/run/secrets/${name}`);
const secretOf = {
  '/api/v1/webhooks/payment-gateway': read('payment_webhook_secret').toString().trim(),
  '/api/v1/webhooks/email-provider-keys': read('delivery_webhook_secret').toString().trim(),
  '/api/v1/webhooks/email-provider': read('platform_webhook_secret').toString().trim(),
  '/api/v1/webhooks/sms-provider': read('platform_webhook_secret').toString().trim(),
};

const server = https.createServer(
  { key: read('tls_api-gateway.key'), cert: read('tls_api-gateway.crt'), ca: read('tls_ca.crt'), requestCert: true, rejectUnauthorized: false },
  (req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      const body = Buffer.concat(chunks).toString('utf8');
      const ts = req.headers['x-webhook-timestamp'] ?? '';
      const given = Buffer.from(req.headers['x-webhook-signature'] ?? '', 'utf8');
      const secret = secretOf[req.url];
      let sigOk = false;
      if (secret) {
        const want = Buffer.from(createHmac('sha256', secret).update(`${ts}.${body}`).digest('hex'), 'utf8');
        sigOk = want.length === given.length && timingSafeEqual(want, given);
      }
      let parsed = {};
      try {
        parsed = JSON.parse(body);
      } catch {}
      const cert = req.socket.getPeerCertificate();
      console.log(
        JSON.stringify({
          path: req.url,
          type: parsed.type ?? parsed.status,
          sigOk,
          fresh: Math.abs(Date.now() - Date.parse(ts)) < 300_000,
          clientCn: cert?.subject?.CN ?? null,
          clientVerified: req.socket.authorized === true,
        }),
      );
      res.statusCode = secret ? 200 : 404;
      res.end();
    });
  },
);
server.listen(8443, () => console.log('receiver-ready'));
