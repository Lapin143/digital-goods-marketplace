import test from 'node:test';
import assert from 'node:assert/strict';
import { SCHEMAS, SYSTEMS, defaults, validateMode } from '../src/modes.mjs';
import { HttpError } from '../src/util.mjs';

test('у каждой системы есть режим по умолчанию, и он проходит собственную проверку', () => {
  assert.deepEqual(SYSTEMS, ['payment', 'email', 'sms', 'vkid']);
  for (const s of SYSTEMS) assert.deepEqual(validateMode(s, defaults(SCHEMAS[s])), defaults(SCHEMAS[s]));
});

test('пустое тело даёт режим по умолчанию (замена, а не слияние)', () => {
  assert.equal(validateMode('payment', undefined).scenario, 'success');
  assert.equal(validateMode('payment', { scenario: 'reject' }).delayMs, 1000);
});

test('вложенные объекты дополняются значениями по умолчанию', () => {
  const m = validateMode('payment', { webhook: { repeat: 3 } });
  assert.equal(m.webhook.repeat, 3);
  assert.equal(m.webhook.send, true);
  assert.equal(m.webhook.retries, 3);
});

function errorsOf(system, input) {
  try {
    validateMode(system, input);
  } catch (e) {
    assert.ok(e instanceof HttpError);
    assert.equal(e.status, 400);
    return e.details;
  }
  assert.fail('ожидалась ошибка');
}

test('неизвестные поля, неверные типы и выход за границы отвергаются со списком причин', () => {
  const details = errorsOf('payment', { scenario: 'boom', delayMs: -1, webhook: { repeat: 'много', extra: 1 }, лишнее: true });
  assert.ok(details.some((d) => d.startsWith('payment.scenario')));
  assert.ok(details.some((d) => d.startsWith('payment.delayMs')));
  assert.ok(details.some((d) => d.startsWith('payment.webhook.repeat')));
  assert.ok(details.some((d) => d.includes('payment.webhook.extra: неизвестное поле')));
  assert.ok(details.some((d) => d.includes('payment.лишнее')));
});

test('целочисленное поле с перечнем значений принимает только перечисленные', () => {
  assert.equal(validateMode('email', { permStatus: 400 }).permStatus, 400);
  assert.ok(errorsOf('email', { permStatus: 404 })[0].includes('допустимо 400, 422'));
});

test('неизвестная система даёт 404', () => {
  assert.throws(() => validateMode('bank', {}), (e) => e.status === 404);
});

test('доля неудач ограничена отрезком от 0 до 1', () => {
  assert.equal(validateMode('email', { failRate: 0.25 }).failRate, 0.25);
  assert.ok(errorsOf('email', { failRate: 1.5 }).length);
});
