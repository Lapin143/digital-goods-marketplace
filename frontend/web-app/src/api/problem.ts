import { hasText, t } from '../i18n';

/** Ошибка одного поля из `errors` проблемы (components.yaml, FieldError). */
export interface FieldProblem {
  pointer?: string;
  parameter?: string;
  code: string;
  detail?: string;
}

/** Проблема по RFC 9457 (`application/problem+json`), как её отдают сервисы и шлюз. */
export interface ApiProblem {
  status: number;
  code: string;
  title?: string;
  detail?: string;
  correlationId?: string;
  errors?: FieldProblem[];
  currentStatus?: string;
  available?: number;
  retryAfterAt?: string;
  attemptsLeft?: number;
  limit?: number;
}

/** Ответ API с кодом не из диапазона 2xx или сбой сети (status 0). */
export class ApiError extends Error {
  readonly problem: ApiProblem;
  /** Через сколько миллисекунд можно повторить, если сервер сказал (Retry-After или retryAfterAt). */
  readonly retryAfterMs: number | undefined;

  constructor(problem: ApiProblem, retryAfterMs?: number) {
    super(`${problem.status} ${problem.code}`);
    this.name = 'ApiError';
    this.problem = problem;
    this.retryAfterMs = retryAfterMs;
  }

  get status(): number {
    return this.problem.status;
  }

  get code(): string {
    return this.problem.code;
  }
}

function asRecord(value: unknown): Record<string, unknown> {
  return typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : {};
}

function text(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

function whole(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

function fieldErrors(value: unknown): FieldProblem[] | undefined {
  if (!Array.isArray(value)) {
    return undefined;
  }
  const items: FieldProblem[] = [];
  for (const raw of value) {
    const item = asRecord(raw);
    const code = text(item.code);
    if (code === undefined) {
      continue;
    }
    items.push({ code, pointer: text(item.pointer), parameter: text(item.parameter), detail: text(item.detail) });
  }
  return items;
}

/** Код проблемы: поле `code`, иначе последний сегмент `type` (conventions.md, раздел 9). */
function codeOf(body: Record<string, unknown>, status: number): string {
  const code = text(body.code);
  if (code) {
    return code;
  }
  const type = text(body.type);
  if (type) {
    const last = type.slice(type.lastIndexOf('/') + 1);
    if (last) {
      return last;
    }
  }
  return status >= 500 ? 'internal-error' : 'bad-request';
}

/** Разбирает тело ответа об ошибке. Тело не JSON (страница прокси, пустой ответ) даёт проблему с кодом по статусу. */
export function parseProblem(status: number, body: unknown): ApiProblem {
  const record = asRecord(body);
  return {
    status,
    code: codeOf(record, status),
    title: text(record.title),
    detail: text(record.detail),
    correlationId: text(record.correlationId),
    errors: fieldErrors(record.errors),
    currentStatus: text(record.currentStatus),
    available: whole(record.available),
    retryAfterAt: text(record.retryAfterAt),
    attemptsLeft: whole(record.attemptsLeft),
    limit: whole(record.limit),
  };
}

/** Миллисекунды до повтора: заголовок Retry-After (секунды) важнее поля retryAfterAt (момент времени). */
export function retryDelay(problem: ApiProblem, retryAfterHeader: string | null, now: number): number | undefined {
  if (retryAfterHeader !== null && /^\d+$/.test(retryAfterHeader.trim())) {
    return Number(retryAfterHeader.trim()) * 1000;
  }
  if (problem.retryAfterAt) {
    const at = Date.parse(problem.retryAfterAt);
    if (!Number.isNaN(at)) {
      return Math.max(0, at - now);
    }
  }
  return undefined;
}

/**
 * Текст ошибки для пользователя. Берётся из словаря по коду, а не из `detail`: detail написан для разработчика
 * (conventions.md, раздел 9) и может содержать внутренние подробности. Для 429 и 503 добавляется время ожидания.
 */
export function problemMessage(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return t('problem.unknown');
  }
  const { problem } = error;
  if (problem.status === 0) {
    return t('problem.network');
  }
  const key = `problem.${problem.code}`;
  let message = hasText(key) ? t(key, { limit: problem.limit ?? '', attempts: problem.attemptsLeft ?? '' }) : t('problem.unknown');
  if (error.retryAfterMs !== undefined && (problem.status === 429 || problem.status === 503)) {
    const seconds = Math.max(1, Math.ceil(error.retryAfterMs / 1000));
    message = `${message} ${t('problem.retryAfter', { seconds })}`;
  }
  return message;
}

/** Ошибки полей по указателю JSON (`/title` → «title»), чтобы форма показала их под нужными полями. */
export function fieldMessages(error: unknown): Record<string, string> {
  const result: Record<string, string> = {};
  if (!(error instanceof ApiError) || !error.problem.errors) {
    return result;
  }
  for (const item of error.problem.errors) {
    const name = item.pointer ? item.pointer.replace(/^\//, '').replace(/\//g, '.') : item.parameter;
    if (name && !(name in result)) {
      result[name] = problemFieldText(item.code);
    }
  }
  return result;
}

function problemFieldText(code: string): string {
  const key = `field.${code}`;
  return hasText(key) ? t(key) : t('problem.validation-failed');
}
