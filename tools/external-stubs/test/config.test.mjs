import test from 'node:test';
import assert from 'node:assert/strict';
import { loadConfig } from '../src/config.mjs';

const secretVars = {
  PAYMENT_GATEWAY_KEY_FILE: '/s/payment_gateway_key',
  PAYMENT_WEBHOOK_SECRET_FILE: '/s/payment_webhook_secret',
  DELIVERY_EMAIL_KEY_FILE: '/s/delivery_email_key',
  DELIVERY_WEBHOOK_SECRET_FILE: '/s/delivery_webhook_secret',
  PLATFORM_EMAIL_KEY_FILE: '/s/platform_email_key',
  PLATFORM_SMS_KEY_FILE: '/s/platform_sms_key',
  PLATFORM_WEBHOOK_SECRET_FILE: '/s/platform_webhook_secret',
};
const read = (path) => Buffer.from(`содержимое-${path}\n`);

test('секреты читаются из файлов, перевод строки в конце отбрасывается', () => {
  const c = loadConfig({ ...secretVars }, read);
  assert.equal(c.secrets.paymentGatewayKey, 'содержимое-/s/payment_gateway_key');
  assert.equal(c.apiPort, 8443);
  assert.equal(c.adminPort, 8444);
  assert.equal(c.smtpPort, 1025);
  assert.equal(c.tls, null);
});

test('без пути к любому из секретов запуск отказывает и называет переменные', () => {
  const env = { ...secretVars };
  delete env.PLATFORM_SMS_KEY_FILE;
  assert.throws(() => loadConfig(env, read), /PLATFORM_SMS_KEY_FILE/);
});

test('пустой файл секрета недопустим', () => {
  assert.throws(() => loadConfig({ ...secretVars }, () => Buffer.from('\n')), /пуст/);
});

test('порты и адреса вебхуков берутся из окружения', () => {
  const c = loadConfig(
    { ...secretVars, STUBS_API_PORT: '9443', STUBS_ADMIN_PORT: '9444', STUBS_SMTP_PORT: '2525', STUBS_PAYMENT_WEBHOOK_URL: 'https://api-gateway:8443/api/v1/webhooks/payment-gateway', STUBS_PUBLIC_URL: 'https://h:1/' },
    read,
  );
  assert.deepEqual([c.apiPort, c.adminPort, c.smtpPort], [9443, 9444, 2525]);
  assert.equal(c.webhookUrls.payment, 'https://api-gateway:8443/api/v1/webhooks/payment-gateway');
  assert.equal(c.webhookUrls.platformSms, '');
  assert.equal(c.publicUrl, 'https://h:1');
});

test('неверный порт отвергается', () => {
  assert.throws(() => loadConfig({ ...secretVars, STUBS_API_PORT: '70000' }, read), /STUBS_API_PORT/);
  assert.throws(() => loadConfig({ ...secretVars, STUBS_SMTP_PORT: 'abc' }, read), /STUBS_SMTP_PORT/);
});

test('сертификат и ключ TLS задаются только вместе', () => {
  assert.throws(() => loadConfig({ ...secretVars, STUBS_TLS_CERT_FILE: '/c' }, read), /вместе/);
  const c = loadConfig({ ...secretVars, STUBS_TLS_CERT_FILE: '/c', STUBS_TLS_KEY_FILE: '/k', STUBS_TLS_CA_FILE: '/ca' }, read);
  assert.ok(c.tls.cert && c.tls.key && c.tls.ca);
});
