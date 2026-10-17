import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { t } from '../../i18n';
import { renderScreen, respond } from '../../test/harness';
import type { Handler } from '../../test/harness';
import { PhonePage } from './PhonePage';

const CONFIRMATION = {
  confirmationId: 'c-1',
  phoneMasked: '+7900***4567',
  expiresAt: '2026-10-17T12:05:00.000Z',
  attemptsLeft: 5,
  resendAvailableAt: null,
};

afterEach(() => {
  vi.useRealTimers();
});

/** Сервер подтверждения: ответы создания и проверки кода можно подменить, остальное 404. */
function server(overrides: { create?: Handler; verify?: Handler } = {}): Handler {
  const create: Handler = overrides.create ?? (() => respond(201, CONFIRMATION));
  const verify: Handler = overrides.verify ?? (() => respond(200, { phoneConfirmed: true, phoneMasked: '+7900***4567' }));
  return (url, init) => {
    if (url.pathname === '/api/v1/phone-confirmations') {
      return create(url, init);
    }
    if (url.pathname === '/api/v1/phone-confirmations/c-1/verify') {
      return verify(url, init);
    }
    return respond(404, { code: 'not-found' });
  };
}

async function requestCode(user: ReturnType<typeof userEvent.setup>, phone = '8 900 123-45-67') {
  await user.type(screen.getByLabelText(t('phone.number')), phone);
  await user.click(screen.getByRole('button', { name: t('phone.send') }));
}

describe('подтверждение телефона', () => {
  it('неверный формат номера: ошибка у поля, запроса нет', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(<PhonePage />, { handler: server() });
    await requestCode(user, '123');
    expect(await screen.findByText(t('phone.error.format'))).toBeInTheDocument();
    expect(calls).toHaveLength(0);
  });

  it('запрос кода, ввод кода, успех', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(<PhonePage />, { handler: server() });
    await requestCode(user);

    expect(await screen.findByText(t('phone.sentTo', { phone: '+7900***4567' }))).toBeInTheDocument();
    expect(calls[0]?.init.method).toBe('POST');
    expect(calls[0]?.init.body).toBe(JSON.stringify({ phone: '+79001234567' }));
    expect(calls[0]?.headers['Idempotency-Key']).toBe('key-1');

    await user.type(screen.getByLabelText(t('phone.code')), '123456');
    await user.click(screen.getByRole('button', { name: t('phone.verify') }));

    expect(await screen.findByRole('heading', { name: t('phone.done.title') })).toBeInTheDocument();
    expect(screen.getByText(t('phone.done.text', { phone: '+7900***4567' }))).toBeInTheDocument();
    expect(calls[1]?.url.pathname).toBe('/api/v1/phone-confirmations/c-1/verify');
    expect(calls[1]?.init.body).toBe(JSON.stringify({ code: '123456' }));
    expect(calls[1]?.headers['Idempotency-Key']).toBe('key-2');
  });

  it('в поле кода остаются только цифры, неполный код не отправляется', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(<PhonePage />, { handler: server() });
    await requestCode(user);
    const field = await screen.findByLabelText(t('phone.code'));
    await user.type(field, '12a-34');
    expect(field).toHaveValue('1234');
    await user.click(screen.getByRole('button', { name: t('phone.verify') }));
    expect(await screen.findByText(t('phone.error.code'))).toBeInTheDocument();
    expect(calls).toHaveLength(1);
  });

  it('неверный код: сколько попыток осталось, поле очищено, ввод продолжается', async () => {
    const user = userEvent.setup();
    renderScreen(<PhonePage />, { handler: server({ verify: () => respond(422, { code: 'otp-invalid', attemptsLeft: 3 }) }) });
    await requestCode(user);
    await user.type(await screen.findByLabelText(t('phone.code')), '111111');
    await user.click(screen.getByRole('button', { name: t('phone.verify') }));
    expect(await screen.findByRole('alert')).toHaveTextContent(t('problem.otp-invalid', { attempts: 3 }));
    expect(screen.getByLabelText(t('phone.code'))).toHaveValue('');
  });

  it('код устарел: возврат к номеру с сообщением и сохранённым номером', async () => {
    const user = userEvent.setup();
    renderScreen(<PhonePage />, { handler: server({ verify: () => respond(422, { code: 'otp-expired' }) }) });
    await requestCode(user);
    await user.type(await screen.findByLabelText(t('phone.code')), '111111');
    await user.click(screen.getByRole('button', { name: t('phone.verify') }));
    expect(await screen.findByRole('alert')).toHaveTextContent(t('problem.otp-expired'));
    expect(screen.getByLabelText(t('phone.number'))).toHaveValue('+79001234567');
    expect(screen.getByRole('button', { name: t('phone.send') })).toBeEnabled();
  });

  it('ввод заблокирован после пятой неудачи: сообщение с временем и запрет нового запроса до конца ожидания', async () => {
    const user = userEvent.setup();
    renderScreen(<PhonePage />, {
      handler: server({ verify: () => respond(429, { code: 'otp-locked' }, { 'Retry-After': '600' }) }),
    });
    await requestCode(user);
    await user.type(await screen.findByLabelText(t('phone.code')), '111111');
    await user.click(screen.getByRole('button', { name: t('phone.verify') }));
    expect(await screen.findByRole('alert')).toHaveTextContent(`${t('problem.otp-locked')} ${t('problem.retryAfterMinutes', { minutes: 10 })}`);
    expect(screen.getByRole('button', { name: t('phone.send') })).toBeDisabled();
  });

  it('лимит запросов кода: сообщение со временем, кнопка заблокирована и снова доступна по истечении срока', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    const user = userEvent.setup();
    renderScreen(<PhonePage />, {
      handler: server({ create: () => respond(429, { code: 'otp-request-limited' }, { 'Retry-After': '90' }) }),
    });
    await requestCode(user);
    expect(await screen.findByRole('alert')).toHaveTextContent(`${t('problem.otp-request-limited')} ${t('problem.retryAfter', { seconds: 90 })}`);
    expect(screen.getByRole('button', { name: t('phone.send') })).toBeDisabled();
    expect(screen.getByText(t('phone.resendIn', { seconds: 90 }))).toBeInTheDocument();

    act(() => {
      vi.setSystemTime(Date.now() + 91_000);
    });
    await waitFor(() => expect(screen.getByRole('button', { name: t('phone.send') })).toBeEnabled(), { timeout: 3000 });
  });

  it('повторная отправка кода ждёт resendAvailableAt из ответа', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    const user = userEvent.setup();
    renderScreen(<PhonePage />, {
      handler: server({ create: () => respond(201, { ...CONFIRMATION, resendAvailableAt: new Date(Date.now() + 30_000).toISOString() }) }),
    });
    await requestCode(user);
    expect(await screen.findByText(t('phone.resendIn', { seconds: 30 }))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: t('phone.resend') })).toBeDisabled();
  });

  it('номер занят другой учётной записью: нейтральный отказ без подробностей', async () => {
    const user = userEvent.setup();
    renderScreen(<PhonePage />, { handler: server({ create: () => respond(409, { code: 'phone-not-allowed', detail: 'номер принадлежит user-77' }) }) });
    await requestCode(user);
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(t('problem.phone-not-allowed'));
    expect(alert).not.toHaveTextContent('user-77');
  });

  it('Redis недоступен (503): нейтральный текст, номер остался в поле', async () => {
    const user = userEvent.setup();
    renderScreen(<PhonePage />, { handler: server({ create: () => respond(503, { code: 'dependency-unavailable' }) }) });
    await requestCode(user);
    expect(await screen.findByRole('alert')).toHaveTextContent(t('problem.dependency-unavailable'));
    expect(screen.getByLabelText(t('phone.number'))).toHaveValue('8 900 123-45-67');
  });

  it('после сбоя сети повтор уходит с тем же Idempotency-Key, после окончательного отказа с новым', async () => {
    const user = userEvent.setup();
    let attempt = 0;
    const { calls } = renderScreen(<PhonePage />, {
      handler: server({
        create: () => {
          attempt += 1;
          if (attempt === 1) {
            return { fail: new TypeError('Failed to fetch') };
          }
          if (attempt === 2) {
            return respond(409, { code: 'phone-not-allowed' });
          }
          return respond(201, CONFIRMATION);
        },
      }),
    });
    await requestCode(user);
    expect(await screen.findByRole('alert')).toHaveTextContent(t('problem.network'));
    await user.click(screen.getByRole('button', { name: t('phone.send') }));
    await waitFor(() => expect(calls).toHaveLength(2));
    expect(calls[1]?.headers['Idempotency-Key']).toBe(calls[0]?.headers['Idempotency-Key']);

    await screen.findByText(t('problem.phone-not-allowed'));
    await user.click(screen.getByRole('button', { name: t('phone.send') }));
    await waitFor(() => expect(calls).toHaveLength(3));
    expect(calls[2]?.headers['Idempotency-Key']).not.toBe(calls[1]?.headers['Idempotency-Key']);
  });

  it('«Отправить код ещё раз» создаёт новый запрос, «Изменить номер» возвращает к вводу номера', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(<PhonePage />, { handler: server() });
    await requestCode(user);
    await user.click(await screen.findByRole('button', { name: t('phone.resend') }));
    await waitFor(() => expect(calls).toHaveLength(2));
    expect(calls[1]?.headers['Idempotency-Key']).not.toBe(calls[0]?.headers['Idempotency-Key']);
    await user.click(await screen.findByRole('button', { name: t('phone.change') }));
    expect(screen.getByLabelText(t('phone.number'))).toHaveValue('+79001234567');
  });
});
