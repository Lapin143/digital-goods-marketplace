// Подпись вебхуков по ADR-015: HMAC-SHA-256 от строки «метка времени, точка, тело», hex.
import { createHmac } from 'node:crypto';
import { secureEquals } from './util.mjs';

export const SIGNATURE_HEADER = 'x-webhook-signature';
export const TIMESTAMP_HEADER = 'x-webhook-timestamp';
export const TOLERANCE_MS = 5 * 60 * 1000;

export function sign(secret, timestamp, body) {
  return createHmac('sha256', secret).update(`${timestamp}.${body}`).digest('hex');
}

/** Проверка со стороны получателя: так её делают сервисы платформы. Нужна контрактному тесту заглушки. */
export function verify({ secrets, timestamp, body, signature, now = Date.now(), toleranceMs = TOLERANCE_MS }) {
  if (!timestamp || !signature) return { ok: false, reason: 'missing' };
  const sent = Date.parse(timestamp);
  if (Number.isNaN(sent)) return { ok: false, reason: 'bad_timestamp' };
  const match = secrets.some((s) => secureEquals(sign(s, timestamp, body), signature));
  if (!match) return { ok: false, reason: 'bad_signature' };
  if (Math.abs(now - sent) > toleranceMs) return { ok: false, reason: 'stale' };
  return { ok: true };
}
