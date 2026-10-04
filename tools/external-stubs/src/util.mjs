// Общие мелочи: ошибки HTTP, чтение тела, сравнение за постоянное время, стабильная сериализация.
import { createHash, timingSafeEqual } from 'node:crypto';

export class HttpError extends Error {
  constructor(status, code, message, details) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details;
  }
}

export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** Читает тело запроса целиком. Лишнее вычитывает и отбрасывает, чтобы ответ 413 дошёл до клиента. */
export function readBody(req, limit = 1048576) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    let tooBig = false;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > limit) tooBig = true;
      else chunks.push(chunk);
    });
    req.on('end', () => {
      if (tooBig) reject(new HttpError(413, 'payload_too_large', `Тело запроса больше ${limit} байт`));
      else resolve(Buffer.concat(chunks));
    });
    req.on('error', reject);
  });
}

/** JSON, форма или текст по Content-Type. Пустое тело даёт undefined. */
export function parseBody(contentType, buffer) {
  if (!buffer || buffer.length === 0) return undefined;
  const text = buffer.toString('utf8');
  const type = String(contentType || '').split(';')[0].trim().toLowerCase();
  if (type === 'application/json' || type.endsWith('+json')) {
    try {
      return JSON.parse(text);
    } catch {
      throw new HttpError(400, 'invalid_json', 'Тело запроса не является JSON');
    }
  }
  if (type === 'application/x-www-form-urlencoded') return Object.fromEntries(new URLSearchParams(text));
  return text;
}

export function sendJson(res, status, body, headers = {}) {
  const payload = body === undefined ? '' : JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(payload),
    'Cache-Control': 'no-store',
    ...headers,
  });
  res.end(payload);
}

export function sendHtml(res, status, html, headers = {}) {
  res.writeHead(status, {
    'Content-Type': 'text/html; charset=utf-8',
    'Content-Length': Buffer.byteLength(html),
    'Cache-Control': 'no-store',
    ...headers,
  });
  res.end(html);
}

export function sendEmpty(res, status, headers = {}) {
  res.writeHead(status, { 'Content-Length': 0, ...headers });
  res.end();
}

export function bearerToken(req) {
  const m = /^Bearer\s+(\S+)$/i.exec(req.headers.authorization || '');
  return m ? m[1] : null;
}

/** Сравнение строк за постоянное время (через хеши одинаковой длины). */
export function secureEquals(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  return timingSafeEqual(createHash('sha256').update(a).digest(), createHash('sha256').update(b).digest());
}

/** JSON с отсортированными ключами: одинаковые по смыслу тела дают одинаковую строку. */
export function stableStringify(value) {
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.keys(value)
      .sort()
      .map((k) => `${JSON.stringify(k)}:${stableStringify(value[k])}`)
      .join(',')}}`;
  }
  return JSON.stringify(value);
}

export const fingerprint = (value) => createHash('sha256').update(stableStringify(value)).digest('hex');

export function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}

/** Детерминированный генератор псевдослучайных чисел (mulberry32): «доля неудач» повторяется от запуска к запуску. */
export function mulberry32(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export const isoNow = (clock) => clock.now().toISOString();
