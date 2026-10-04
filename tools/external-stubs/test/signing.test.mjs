import test from 'node:test';
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { sign, verify } from '../src/signing.mjs';

const body = '{"eventId":"evt_1","type":"payment.paid"}';
const ts = '2026-10-03T12:00:00.123Z';

test('подпись: HMAC-SHA-256 от «метка времени.тело», hex (ADR-015)', () => {
  const expected = createHmac('sha256', 's3cret').update(`${ts}.${body}`).digest('hex');
  assert.equal(sign('s3cret', ts, body), expected);
  assert.match(sign('s3cret', ts, body), /^[0-9a-f]{64}$/);
});

test('проверка принимает верную подпись', () => {
  const sig = sign('a', ts, body);
  assert.deepEqual(verify({ secrets: ['a'], timestamp: ts, body, signature: sig, now: Date.parse(ts) + 1000 }), { ok: true });
});

test('проверка допускает два действующих секрета (смена секрета без простоя)', () => {
  const sig = sign('old', ts, body);
  assert.equal(verify({ secrets: ['new', 'old'], timestamp: ts, body, signature: sig, now: Date.parse(ts) }).ok, true);
});

test('проверка отвергает чужую подпись, изменённое тело и изменённую метку времени', () => {
  const sig = sign('a', ts, body);
  const now = Date.parse(ts);
  assert.equal(verify({ secrets: ['b'], timestamp: ts, body, signature: sig, now }).reason, 'bad_signature');
  assert.equal(verify({ secrets: ['a'], timestamp: ts, body: `${body} `, signature: sig, now }).reason, 'bad_signature');
  assert.equal(verify({ secrets: ['a'], timestamp: '2026-10-03T12:00:01.123Z', body, signature: sig, now }).reason, 'bad_signature');
});

test('проверка отвергает уведомление старше пяти минут, в обе стороны', () => {
  const sig = sign('a', ts, body);
  const t = Date.parse(ts);
  assert.equal(verify({ secrets: ['a'], timestamp: ts, body, signature: sig, now: t + 299_000 }).ok, true);
  assert.equal(verify({ secrets: ['a'], timestamp: ts, body, signature: sig, now: t + 301_000 }).reason, 'stale');
  assert.equal(verify({ secrets: ['a'], timestamp: ts, body, signature: sig, now: t - 301_000 }).reason, 'stale');
});

test('проверка отвергает отсутствие заголовков и неразборчивую метку', () => {
  assert.equal(verify({ secrets: ['a'], timestamp: undefined, body, signature: 'x' }).reason, 'missing');
  assert.equal(verify({ secrets: ['a'], timestamp: 'вчера', body, signature: 'x' }).reason, 'bad_timestamp');
});
