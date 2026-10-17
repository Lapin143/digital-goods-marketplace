import type { ClientKind } from '../auth/clients';
import { ApiError, parseProblem, retryDelay } from './problem';

export type Query = Record<string, string | number | boolean | undefined>;

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  /** Чьим токеном подписать запрос. `none` для открытых методов (витрина). */
  auth?: ClientKind | 'none';
  query?: Query;
  /** Объект уходит как JSON, `FormData`, `Blob` и строка как есть. */
  body?: unknown;
  /** Тип содержимого для `Blob` и строки; у JSON и `FormData` задаётся сам. */
  contentType?: string;
  /** Нужен ли `Idempotency-Key`. Ключ берётся из `idempotencyKey` или создаётся новый. */
  idempotent?: boolean;
  /** Ключ, созданный при открытии диалога: повтор того же действия (двойное нажатие, повторная отправка) вернёт тот же ответ. */
  idempotencyKey?: string;
  /** `ETag` ресурса, версию которого меняем (заголовок If-Match). */
  ifMatch?: string;
  headers?: Record<string, string>;
  signal?: AbortSignal;
}

export interface ApiResponse<T> {
  status: number;
  data: T;
  etag: string | undefined;
  headers: Headers;
}

export interface ApiClientConfig {
  /** Токен доступа для менеджера входа; нет токена, значит нет входа. */
  token: (kind: ClientKind) => string | undefined;
  /** Сервер ответил 401: токен недействителен, сессию нужно завершить. */
  onUnauthorized?: (kind: ClientKind) => void;
  fetchImpl?: typeof fetch;
  now?: () => number;
  newKey?: () => string;
}

export interface ApiClient {
  request: <T>(path: string, options?: RequestOptions) => Promise<ApiResponse<T>>;
  get: <T>(path: string, options?: Omit<RequestOptions, 'method' | 'body'>) => Promise<ApiResponse<T>>;
  post: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'method' | 'body'>) => Promise<ApiResponse<T>>;
  put: <T>(path: string, body: unknown, options?: Omit<RequestOptions, 'method' | 'body'>) => Promise<ApiResponse<T>>;
  del: <T>(path: string, options?: Omit<RequestOptions, 'method' | 'body'>) => Promise<ApiResponse<T>>;
  /** Новый `Idempotency-Key`: экран создаёт его один раз на действие и передаёт в каждый повтор этого действия. */
  newKey: () => string;
}

export function buildUrl(path: string, query?: Query): string {
  if (!query) {
    return path;
  }
  const params = new URLSearchParams();
  for (const [name, value] of Object.entries(query)) {
    if (value !== undefined && value !== '') {
      params.set(name, String(value));
    }
  }
  const text = params.toString();
  return text ? `${path}?${text}` : path;
}

function isRawBody(body: unknown): body is FormData | Blob | string {
  return body instanceof FormData || body instanceof Blob || typeof body === 'string';
}

async function readBody(response: Response): Promise<unknown> {
  if (response.status === 204 || response.status === 304) {
    return undefined;
  }
  const type = response.headers.get('Content-Type') ?? '';
  if (type.includes('json')) {
    try {
      return await response.json();
    } catch {
      return undefined;
    }
  }
  const text = await response.text();
  return text === '' ? undefined : text;
}

/**
 * Тонкая обёртка над fetch (шаг 16 Ф4): добавляет токен, `Idempotency-Key` и `If-Match`, разбирает `application/problem+json`
 * в {@link ApiError}. Бизнес-правил здесь нет: их проверяют сервисы (решение 4 стратегии тестирования).
 */
export function createApiClient(config: ApiClientConfig): ApiClient {
  const doFetch = config.fetchImpl ?? ((input: RequestInfo | URL, init?: RequestInit) => fetch(input, init));
  const now = config.now ?? (() => Date.now());
  const newKey = config.newKey ?? (() => crypto.randomUUID());

  async function request<T>(path: string, options: RequestOptions = {}): Promise<ApiResponse<T>> {
    const method = options.method ?? 'GET';
    const auth = options.auth ?? 'web';
    const headers: Record<string, string> = { Accept: 'application/json, application/problem+json', ...options.headers };

    if (auth !== 'none') {
      const token = config.token(auth);
      if (token) {
        headers.Authorization = `Bearer ${token}`;
      }
    }
    if (options.idempotent || options.idempotencyKey) {
      headers['Idempotency-Key'] = options.idempotencyKey ?? newKey();
    }
    if (options.ifMatch) {
      headers['If-Match'] = options.ifMatch;
    }

    let body: BodyInit | undefined;
    if (options.body !== undefined) {
      if (isRawBody(options.body)) {
        body = options.body;
        const type = options.contentType ?? (options.body instanceof Blob && options.body.type ? options.body.type : undefined);
        if (type) {
          headers['Content-Type'] = type;
        }
      } else {
        body = JSON.stringify(options.body);
        headers['Content-Type'] = 'application/json';
      }
    }

    let response: Response;
    try {
      response = await doFetch(buildUrl(path, options.query), { method, headers, body, signal: options.signal, credentials: 'same-origin' });
    } catch (cause) {
      if (cause instanceof DOMException && cause.name === 'AbortError') {
        throw cause;
      }
      throw new ApiError({ status: 0, code: 'network-error' });
    }

    const payload = await readBody(response);
    if (!response.ok) {
      const problem = parseProblem(response.status, payload);
      if (response.status === 401 && auth !== 'none') {
        config.onUnauthorized?.(auth);
      }
      throw new ApiError(problem, retryDelay(problem, response.headers.get('Retry-After'), now()));
    }
    return { status: response.status, data: payload as T, etag: response.headers.get('ETag') ?? undefined, headers: response.headers };
  }

  return {
    request,
    get: (path, options) => request(path, { ...options, method: 'GET' }),
    post: (path, body, options) => request(path, { ...options, method: 'POST', body }),
    put: (path, body, options) => request(path, { ...options, method: 'PUT', body }),
    del: (path, options) => request(path, { ...options, method: 'DELETE' }),
    newKey,
  };
}
