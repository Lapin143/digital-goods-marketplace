import { useMemo, useRef } from 'react';
import { useApi } from './ApiContext';
import { ApiError } from './problem';

/**
 * Исход запроса неизвестен: сбой сети, ответ 5xx или «предыдущий запрос ещё выполняется». Повтор с тем же `Idempotency-Key`
 * безопасен и вернёт тот же результат. Любой другой ответ окончателен, и следующая попытка получает новый ключ.
 */
export function isOutcomeUnknown(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 0 || error.status >= 500 || error.code === 'request-in-progress');
}

export interface RequestKeys {
  /** Ключ для действия с этими данными: пока данные те же и исход неизвестен, ключ прежний. */
  keyFor: (fingerprint: string) => string;
  /** Запрос завершён: без ошибки или с окончательным ответом ключ забывается. */
  settle: (error?: unknown) => void;
}

export function useRequestKeys(): RequestKeys {
  const api = useApi();
  const last = useRef<{ fingerprint: string; key: string } | undefined>(undefined);
  return useMemo<RequestKeys>(
    () => ({
      keyFor: (fingerprint) => {
        if (last.current?.fingerprint !== fingerprint) {
          last.current = { fingerprint, key: api.newKey() };
        }
        return last.current.key;
      },
      settle: (error) => {
        if (error === undefined || !isOutcomeUnknown(error)) {
          last.current = undefined;
        }
      },
    }),
    [api],
  );
}
