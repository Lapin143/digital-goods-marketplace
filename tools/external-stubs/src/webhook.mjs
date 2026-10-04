// Исходящие вебхуки: подпись, повторы при сбоях получателя, запись каждой попытки в журнал.
import http from 'node:http';
import https from 'node:https';
import { sign, SIGNATURE_HEADER, TIMESTAMP_HEADER } from './signing.mjs';
import { sleep } from './util.mjs';

/** Настоящий HTTP-клиент. Для https заглушка доверяет только нашему центру сертификации и предъявляет свой сертификат. */
export function httpRequest(config) {
  return (url, { method = 'POST', headers = {}, body = '', timeoutMs = 5000 } = {}) =>
    new Promise((resolve, reject) => {
      const target = new URL(url);
      const secure = target.protocol === 'https:';
      const options = { method, headers: { ...headers, 'Content-Length': Buffer.byteLength(body) }, timeout: timeoutMs };
      if (secure && config.tls) {
        options.ca = config.tls.ca;
        options.cert = config.tls.cert;
        options.key = config.tls.key;
      }
      const req = (secure ? https : http).request(target, options, (res) => {
        res.resume();
        res.on('end', () => resolve({ status: res.statusCode }));
      });
      req.on('timeout', () => req.destroy(new Error(`тайм-аут ${timeoutMs} мс`)));
      req.on('error', reject);
      req.end(body);
    });
}

export function createWebhookSender({ state, clock, request, pause = sleep }) {
  /**
   * @param {object} p
   * @param {string} p.system     payment | email | sms
   * @param {string} p.url        адрес получателя; пустой — вебхук не отправляется, это видно в журнале
   * @param {string} p.secret     секрет подписи источника
   * @param {object} p.event      тело уведомления (объект)
   * @param {object} p.options    поля режима webhook: send, repeat, parallel, badSignature, timestampOffsetSeconds, retries, retryDelayMs
   * @param {string} [p.timestamp] метка времени заголовка; по умолчанию «сейчас» плюс сдвиг из режима
   * @param {object} [p.meta]     что записать в журнал (идентификатор платежа, письма)
   */
  async function deliver({ system, url, secret, event, options, timestamp, meta = {} }) {
    const base = { kind: 'webhook', system, ...meta, type: event.type ?? event.status, eventId: event.eventId ?? event.messageId };
    if (options.send === false) {
      state.record({ ...base, outcome: 'lost', note: 'режим send=false: вебхук намеренно не отправлен' });
      return [];
    }
    if (!url) {
      state.record({ ...base, outcome: 'skipped', note: 'адрес получателя не задан' });
      return [];
    }
    const body = JSON.stringify(event);
    const ts = timestamp ?? new Date(clock.now().getTime() + options.timestampOffsetSeconds * 1000).toISOString();
    const signature = sign(options.badSignature ? `${secret}-wrong` : secret, ts, body);
    const headers = {
      'Content-Type': 'application/json',
      'User-Agent': 'dgm-external-stubs/1',
      [TIMESTAMP_HEADER]: ts,
      [SIGNATURE_HEADER]: signature,
    };

    const oneCopy = async (copy) => {
      for (let attempt = 1; ; attempt++) {
        let status = null;
        let error = null;
        try {
          ({ status } = await request(url, { method: 'POST', headers, body }));
        } catch (e) {
          error = e.message;
        }
        const retriable = error !== null || status >= 500 || status === 429;
        const last = !retriable || attempt > options.retries;
        state.record({ ...base, url, copy, attempt, timestamp: ts, status, error, body: event, outcome: error ? 'error' : status });
        if (last) return { copy, attempts: attempt, status, error };
        await pause(options.retryDelayMs);
      }
    };

    const copies = Array.from({ length: options.repeat }, (_, i) => i + 1);
    if (options.parallel) return Promise.all(copies.map(oneCopy));
    const results = [];
    for (const c of copies) results.push(await oneCopy(c));
    return results;
  }
  return { deliver };
}
