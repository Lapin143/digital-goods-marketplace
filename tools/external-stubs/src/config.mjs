// Настройка из переменных окружения. Секреты читаются из файлов (`*_FILE`), в окружении их значений нет.
import { readFileSync } from 'node:fs';

const SECRETS = {
  paymentGatewayKey: 'PAYMENT_GATEWAY_KEY_FILE',
  paymentWebhookSecret: 'PAYMENT_WEBHOOK_SECRET_FILE',
  deliveryEmailKey: 'DELIVERY_EMAIL_KEY_FILE',
  deliveryWebhookSecret: 'DELIVERY_WEBHOOK_SECRET_FILE',
  platformEmailKey: 'PLATFORM_EMAIL_KEY_FILE',
  platformSmsKey: 'PLATFORM_SMS_KEY_FILE',
  platformWebhookSecret: 'PLATFORM_WEBHOOK_SECRET_FILE',
};

export function loadConfig(env = process.env, read = (path) => readFileSync(path)) {
  const port = (name, def) => {
    const raw = env[name];
    if (raw === undefined || raw === '') return def;
    const n = Number(raw);
    if (!Number.isInteger(n) || n < 0 || n > 65535) throw new Error(`${name}: ожидается порт 0–65535, получено «${raw}»`);
    return n;
  };
  const file = (name) => (env[name] ? read(env[name]) : undefined);

  const secrets = {};
  const missing = [];
  for (const [key, variable] of Object.entries(SECRETS)) {
    const path = env[variable];
    if (!path) {
      missing.push(variable);
      continue;
    }
    secrets[key] = read(path).toString('utf8').replace(/[\r\n]+$/, '');
    if (!secrets[key]) throw new Error(`${variable}: файл секрета пуст`);
  }
  if (missing.length) throw new Error(`Не заданы переменные с путями к секретам: ${missing.join(', ')}`);

  const cert = file('STUBS_TLS_CERT_FILE');
  const key = file('STUBS_TLS_KEY_FILE');
  const ca = file('STUBS_TLS_CA_FILE');
  if (Boolean(cert) !== Boolean(key)) throw new Error('STUBS_TLS_CERT_FILE и STUBS_TLS_KEY_FILE задаются вместе');

  return {
    bind: env.STUBS_BIND || '0.0.0.0',
    apiPort: port('STUBS_API_PORT', 8443),
    adminPort: port('STUBS_ADMIN_PORT', 8444),
    smtpPort: port('STUBS_SMTP_PORT', 1025),
    publicUrl: (env.STUBS_PUBLIC_URL || 'https://external-stubs:8443').replace(/\/+$/, ''),
    tls: cert ? { cert, key, ca } : null,
    secrets,
    webhookUrls: {
      payment: env.STUBS_PAYMENT_WEBHOOK_URL || '',
      deliveryEmail: env.STUBS_DELIVERY_EMAIL_WEBHOOK_URL || '',
      platformEmail: env.STUBS_PLATFORM_EMAIL_WEBHOOK_URL || '',
      platformSms: env.STUBS_PLATFORM_SMS_WEBHOOK_URL || '',
    },
    quiet: env.STUBS_QUIET === '1',
  };
}
