import { describe, expect, it } from 'vitest';
import { t } from '../i18n';
import { ApiError, fieldMessages, parseProblem, problemMessage, retryDelay, waitText } from './problem';

describe('разбор проблемы', () => {
  it('берёт код из поля code и расширения', () => {
    const problem = parseProblem(409, {
      type: 'https://api.marketplace.example/problems/state-conflict',
      title: 'Действие недоступно',
      status: 409,
      detail: 'внутреннее объяснение',
      code: 'state-conflict',
      correlationId: 'c-1',
      currentStatus: 'published',
    });
    expect(problem.code).toBe('state-conflict');
    expect(problem.currentStatus).toBe('published');
    expect(problem.correlationId).toBe('c-1');
  });

  it('при отсутствии code берёт последний сегмент type', () => {
    expect(parseProblem(404, { type: 'https://api.marketplace.example/problems/not-found' }).code).toBe('not-found');
  });

  it('тело не JSON даёт код по статусу', () => {
    expect(parseProblem(502, '<html>bad gateway</html>').code).toBe('internal-error');
    expect(parseProblem(400, undefined).code).toBe('bad-request');
  });

  it('собирает ошибки полей и отбрасывает записи без кода', () => {
    const problem = parseProblem(422, {
      code: 'validation-failed',
      errors: [{ pointer: '/title', code: 'required', detail: 'x' }, { pointer: '/price' }, 'мусор'],
    });
    expect(problem.errors).toEqual([{ pointer: '/title', code: 'required', parameter: undefined, detail: 'x' }]);
  });
});

describe('время ожидания', () => {
  it('заголовок Retry-After важнее retryAfterAt', () => {
    const problem = parseProblem(429, { code: 'rate-limited', retryAfterAt: '2026-10-17T12:00:30.000Z' });
    expect(retryDelay(problem, '12', Date.parse('2026-10-17T12:00:00.000Z'))).toBe(12_000);
  });

  it('без заголовка считает по retryAfterAt', () => {
    const problem = parseProblem(429, { code: 'rate-limited', retryAfterAt: '2026-10-17T12:00:30.000Z' });
    expect(retryDelay(problem, null, Date.parse('2026-10-17T12:00:00.000Z'))).toBe(30_000);
  });

  it('прошедший момент даёт ноль, мусор даёт неизвестность', () => {
    expect(retryDelay(parseProblem(429, { retryAfterAt: '2020-01-01T00:00:00.000Z' }), null, Date.now())).toBe(0);
    expect(retryDelay(parseProblem(429, {}), 'скоро', Date.now())).toBeUndefined();
  });
});

describe('текст для пользователя', () => {
  it('берёт текст из словаря по коду, а не detail сервера', () => {
    const error = new ApiError(parseProblem(409, { code: 'state-conflict', detail: 'секретная подробность' }));
    expect(problemMessage(error)).toBe(t('problem.state-conflict'));
    expect(problemMessage(error)).not.toContain('секретная');
  });

  it('429 показывает время ожидания', () => {
    const error = new ApiError(parseProblem(429, { code: 'rate-limited' }), 45_000);
    expect(problemMessage(error)).toBe(`${t('problem.rate-limited')} ${t('problem.retryAfter', { seconds: 45 })}`);
  });

  it('долгое ожидание показывается в минутах с округлением вверх', () => {
    const error = new ApiError(parseProblem(429, { code: 'otp-locked' }), 601_000);
    expect(problemMessage(error)).toBe(`${t('problem.otp-locked')} ${t('problem.retryAfterMinutes', { minutes: 11 })}`);
    expect(waitText(119_000)).toBe(t('problem.retryAfter', { seconds: 119 }));
    expect(waitText(120_000)).toBe(t('problem.retryAfterMinutes', { minutes: 2 }));
  });

  it('503 показывает нейтральный текст и время ожидания', () => {
    const error = new ApiError(parseProblem(503, { code: 'dependency-unavailable' }), 2_100);
    expect(problemMessage(error)).toBe(`${t('problem.dependency-unavailable')} ${t('problem.retryAfter', { seconds: 3 })}`);
  });

  it('сбой сети и неизвестный код получают свои тексты', () => {
    expect(problemMessage(new ApiError({ status: 0, code: 'network-error' }))).toBe(t('problem.network'));
    expect(problemMessage(new ApiError(parseProblem(418, { code: 'чайник' })))).toBe(t('problem.unknown'));
    expect(problemMessage(new Error('x'))).toBe(t('problem.unknown'));
  });

  it('подставляет предел и число попыток', () => {
    expect(problemMessage(new ApiError(parseProblem(409, { code: 'pool-limit-exceeded', limit: 100000 })))).toContain('100000');
    expect(problemMessage(new ApiError(parseProblem(400, { code: 'otp-invalid', attemptsLeft: 2 })))).toContain('2');
  });
});

describe('ошибки полей', () => {
  it('указатель превращается в имя поля', () => {
    const error = new ApiError(
      parseProblem(422, { code: 'validation-failed', errors: [{ pointer: '/price/amount', code: 'out_of_range' }, { parameter: 'limit', code: 'invalid_format' }] }),
    );
    const messages = fieldMessages(error);
    expect(Object.keys(messages)).toEqual(['price.amount', 'limit']);
  });

  it('для чужой ошибки пусто', () => {
    expect(fieldMessages(new Error('x'))).toEqual({});
  });
});
