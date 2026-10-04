// Режимы заглушек: описание полей, значения по умолчанию и проверка того, что прислал тест.
// PUT режима заменяет его целиком: пропущенное поле получает значение по умолчанию. Так состояние режима
// определяется одним запросом, а не историей запросов (тесты не зависят друг от друга).
import { HttpError } from './util.mjs';

const int = (def, min = 0, max = 3_600_000, values) => ({ t: 'int', def, min, max, values });
const num = (def, min, max) => ({ t: 'num', def, min, max });
const bool = (def) => ({ t: 'bool', def });
const str = (def, max = 256) => ({ t: 'str', def, max });
const oneOf = (def, values) => ({ t: 'enum', def, values });
const obj = (fields) => ({ t: 'obj', fields });

/** Как заглушка отправляет вебхуки. Общий для платежей, писем и SMS. */
const webhook = (extra = {}) =>
  obj({
    send: bool(true), // false: статус у заглушки меняется, а вебхук теряется (проверка сверки и опроса)
    repeat: int(1, 1, 20), // сколько раз прислать одно и то же уведомление
    parallel: bool(false), // повторы одновременно, а не по очереди
    badSignature: bool(false),
    timestampOffsetSeconds: int(0, -86_400, 86_400), // сдвиг метки времени: -600 даёт устаревшее уведомление
    retries: int(3, 0, 10), // повторы при 5xx, 429 и сетевой ошибке, как делает настоящий шлюз
    retryDelayMs: int(500, 0, 60_000),
    ...extra,
  });

export const SCHEMAS = {
  payment: obj({
    scenario: oneOf('success', ['success', 'reject', 'hold', 'expire']),
    failureReason: oneOf('declined', ['declined', 'insufficient_funds', 'authentication_failed', 'other']),
    delayMs: int(1000, 0, 3_600_000), // через сколько после создания платежа наступает исход сценария
    sessionTtlSeconds: int(720, 1, 86_400), // срок платёжной сессии (ADR-015: 12 минут)
    create: obj({
      failFirst: int(0, -1, 100_000), // первые N вызовов создания платежа отказывают; -1 все вызовы
      failure: oneOf('http_503', ['http_503', 'http_429', 'timeout']),
      timeoutMs: int(5000, 0, 120_000), // сколько держать соединение открытым при failure=timeout
      loseResponseFirst: int(0, 0, 100_000), // платёж создан, ответ не дошёл (соединение разрывается)
    }),
    webhook: webhook({
      amountDelta: int(0, -1_000_000_000, 1_000_000_000), // расхождение суммы в уведомлении
      unknownPayment: bool(false), // уведомление о платеже, которого платформа не знает
    }),
    refund: obj({
      mode: oneOf('instant', ['instant', 'pending', 'reject']), // reject: возврат не принимается никогда
      failFirst: int(0, -1, 100_000), // первые N запросов возврата получают 503
      delayMs: int(1000, 0, 3_600_000), // через сколько приходит refund.completed при mode=instant
    }),
  }),
  email: obj({
    sender: oneOf('any', ['any', 'delivery', 'platform', 'smtp']), // чьи письма затрагивает режим
    behavior: oneOf('accept', ['accept', 'temp_error', 'perm_error']),
    failFirst: int(-1, -1, 100_000), // сколько вызовов отказывают при behavior≠accept; -1 все
    failRate: num(1, 0, 1), // доля неудач среди подходящих вызовов (детерминированный генератор)
    tempKind: oneOf('http_503', ['http_503', 'http_429', 'timeout']),
    timeoutMs: int(5000, 0, 120_000),
    permStatus: int(422, 400, 422, [400, 422]),
    dedupe: bool(true), // повтор с тем же messageId не создаёт второе письмо; false создаёт (редкий дубль, ADR-011)
    status: oneOf('delivered', ['delivered', 'failed', 'none']), // итоговый статус; none: статуса нет вовсе
    statusDelayMs: int(500, 0, 3_600_000),
    failReason: oneOf('address_rejected', ['address_rejected', 'other']),
    webhook: webhook(),
  }),
  sms: obj({
    behavior: oneOf('deliver', ['deliver', 'drop', 'late', 'reject', 'error']),
    lateMs: int(5000, 0, 3_600_000), // при behavior=late: через сколько SMS «доходит» до телефона
    statusDelayMs: int(500, 0, 3_600_000),
    webhook: webhook(),
  }),
  vkid: obj({
    behavior: oneOf('success', ['success', 'deny', 'error']),
    delayMs: int(0, 0, 60_000),
    profile: obj({
      sub: str('vk_100500', 64),
      given_name: str('Иван'),
      family_name: str('Петров'),
      name: str('Иван Петров'),
      email: str('ivan.petrov@example.test'),
      email_verified: bool(true),
      phone_number: str('+79990000001', 32),
    }),
  }),
};

export const SYSTEMS = Object.keys(SCHEMAS);

export function defaults(spec) {
  if (spec.t === 'obj') return Object.fromEntries(Object.entries(spec.fields).map(([k, v]) => [k, defaults(v)]));
  return spec.def;
}

function check(spec, value, path, errors) {
  if (value === undefined) return defaults(spec);
  switch (spec.t) {
    case 'obj': {
      if (value === null || typeof value !== 'object' || Array.isArray(value)) {
        errors.push(`${path}: ожидается объект`);
        return defaults(spec);
      }
      for (const key of Object.keys(value)) if (!(key in spec.fields)) errors.push(`${path}.${key}: неизвестное поле`);
      return Object.fromEntries(Object.entries(spec.fields).map(([k, s]) => [k, check(s, value[k], `${path}.${k}`, errors)]));
    }
    case 'int':
    case 'num': {
      const ok = typeof value === 'number' && Number.isFinite(value) && (spec.t === 'num' || Number.isInteger(value));
      if (!ok) errors.push(`${path}: ожидается ${spec.t === 'int' ? 'целое число' : 'число'}`);
      else if (spec.values && !spec.values.includes(value)) errors.push(`${path}: допустимо ${spec.values.join(', ')}`);
      else if (value < spec.min || value > spec.max) errors.push(`${path}: от ${spec.min} до ${spec.max}`);
      return ok ? value : spec.def;
    }
    case 'bool':
      if (typeof value !== 'boolean') errors.push(`${path}: ожидается true или false`);
      return typeof value === 'boolean' ? value : spec.def;
    case 'str':
      if (typeof value !== 'string' || value.length > spec.max) errors.push(`${path}: ожидается строка до ${spec.max} символов`);
      return typeof value === 'string' ? value : spec.def;
    case 'enum':
      if (!spec.values.includes(value)) errors.push(`${path}: допустимо ${spec.values.join(', ')}`);
      return spec.values.includes(value) ? value : spec.def;
    default:
      throw new Error(`неизвестный тип поля ${spec.t}`);
  }
}

/** Возвращает полный режим системы или бросает 400 со списком ошибок. */
export function validateMode(system, input) {
  const spec = SCHEMAS[system];
  if (!spec) throw new HttpError(404, 'unknown_system', `Нет системы «${system}». Есть: ${SYSTEMS.join(', ')}`);
  const errors = [];
  const value = check(spec, input === undefined ? {} : input, system, errors);
  if (errors.length) throw new HttpError(400, 'invalid_mode', 'Режим не принят', errors);
  return value;
}

/** Проверка произвольного объекта по такой же схеме коротких полей (для тел административных команд). */
export function validateFields(fields, input, path = 'body') {
  const errors = [];
  const value = check(obj(fields), input === undefined ? {} : input, path, errors);
  if (errors.length) throw new HttpError(400, 'invalid_request', 'Команда не принята', errors);
  return value;
}

export const fieldTypes = { int, num, bool, str, oneOf, obj };
