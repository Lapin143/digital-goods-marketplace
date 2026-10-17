import { renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { describe, expect, it } from 'vitest';
import { ApiProvider } from './ApiContext';
import { createApiClient } from './client';
import { isOutcomeUnknown, useRequestKeys } from './requestKey';
import { ApiError, parseProblem } from './problem';
import { AuthProvider } from '../auth/AuthContext';
import { fakeManager } from '../test/fakeAuth';

function setup() {
  let counter = 0;
  const client = createApiClient({
    token: () => undefined,
    newKey: () => {
      counter += 1;
      return `key-${counter}`;
    },
  });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <AuthProvider managerFactory={() => fakeManager(null)}>
      <ApiProvider client={client}>{children}</ApiProvider>
    </AuthProvider>
  );
  return renderHook(() => useRequestKeys(), { wrapper }).result.current;
}

describe('ключи идемпотентности', () => {
  it('те же данные и неизвестный исход: ключ прежний', () => {
    const keys = setup();
    const first = keys.keyFor('create:+79001234567');
    keys.settle(new ApiError({ status: 0, code: 'network-error' }));
    expect(keys.keyFor('create:+79001234567')).toBe(first);
  });

  it('другие данные: новый ключ', () => {
    const keys = setup();
    const first = keys.keyFor('verify:1:111111');
    expect(keys.keyFor('verify:1:222222')).not.toBe(first);
  });

  it('окончательный ответ и успех сбрасывают ключ', () => {
    const keys = setup();
    const first = keys.keyFor('a');
    keys.settle(new ApiError(parseProblem(422, { code: 'otp-invalid' })));
    const second = keys.keyFor('a');
    expect(second).not.toBe(first);
    keys.settle();
    expect(keys.keyFor('a')).not.toBe(second);
  });

  it('неизвестный исход: сеть, 5xx, «запрос выполняется»', () => {
    expect(isOutcomeUnknown(new ApiError({ status: 0, code: 'network-error' }))).toBe(true);
    expect(isOutcomeUnknown(new ApiError(parseProblem(503, { code: 'dependency-unavailable' })))).toBe(true);
    expect(isOutcomeUnknown(new ApiError(parseProblem(409, { code: 'request-in-progress' })))).toBe(true);
    expect(isOutcomeUnknown(new ApiError(parseProblem(409, { code: 'state-conflict' })))).toBe(false);
    expect(isOutcomeUnknown(new Error('x'))).toBe(false);
  });
});
