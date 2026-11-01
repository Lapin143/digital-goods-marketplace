import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { t } from '../../i18n';
import { renderScreen, respond } from '../../test/harness';
import type { Handler } from '../../test/harness';
import { EmailChangeForm, awaitedStep } from './EmailChangeForm';
import type { EmailChangeState } from './types';

const MASKED = 'n***@mail.example';
const VERIFY = '/api/v1/support-tickets/t-1/email-change/verifications';
const CODES = '/api/v1/support-tickets/t-1/email-change/codes';

function stateFixture(overrides: Partial<EmailChangeState> = {}): EmailChangeState {
  return {
    status: 'awaiting_phone_code',
    phoneCheck: 'pending',
    newEmailCheck: 'not_started',
    newEmailMasked: MASKED,
    startedAt: '2026-10-17T12:00:00.000Z',
    updatedAt: '2026-10-17T12:00:00.000Z',
    ...overrides,
  };
}

const AWAITING_EMAIL = stateFixture({ status: 'awaiting_email_code', phoneCheck: 'passed', newEmailCheck: 'pending' });

function form(state: EmailChangeState, handler: Handler) {
  const onChanged = vi.fn();
  const rendered = renderScreen(<EmailChangeForm ticketId="t-1" state={state} onChanged={onChanged} />, { handler });
  return { ...rendered, onChanged };
}

/** Сервер кодов: ответ на ввод кода и на повторный запрос кода задаёт тест, остальное 404. */
function server(options: { verify?: Handler; codes?: Handler } = {}): Handler {
  return (url, init) => {
    if (url.pathname === VERIFY) {
      return (options.verify ?? (() => respond(200, AWAITING_EMAIL)))(url, init);
    }
    if (url.pathname === CODES) {
      return (options.codes ?? (() => respond(200, stateFixture())))(url, init);
    }
    return respond(404, { code: 'not-found' });
  };
}

async function type(user: ReturnType<typeof userEvent.setup>, label: string, code: string) {
  await user.type(screen.getByLabelText(label), code);
}

describe('TC-712. Форма кода из SMS: ввод, ошибки, повторный запрос', () => {
  it('поле кода для одноразового кода: цифры, шесть знаков, подсказка про SMS, адрес только с маской', () => {
    form(stateFixture(), server());
    const field = screen.getByLabelText(t('emailChange.code.phone'));
    expect(field).toHaveAttribute('autocomplete', 'one-time-code');
    expect(field).toHaveAttribute('inputmode', 'numeric');
    expect(field).toHaveAttribute('maxlength', '6');
    expect(screen.getByText(t('emailChange.step.phone'))).toBeInTheDocument();
    expect(screen.getByText(t('emailChange.lead', { address: MASKED }))).toBeInTheDocument();
    expect(document.body.textContent ?? '').not.toContain('mail.example@');
  });

  it('верный код уходит на ввод кода из SMS с ключом идемпотентности, поле очищается, карточка перечитывается', async () => {
    const user = userEvent.setup();
    const { calls, onChanged } = form(stateFixture(), server());

    await type(user, t('emailChange.code.phone'), '123456');
    await user.click(screen.getByRole('button', { name: t('emailChange.verify') }));

    await waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1));
    expect(calls[0]?.url.pathname).toBe(VERIFY);
    expect(calls[0]?.init.method).toBe('POST');
    expect(calls[0]?.init.body).toBe(JSON.stringify({ step: 'phone', code: '123456' }));
    expect(calls[0]?.headers['Idempotency-Key']).toBe('key-1');
    await waitFor(() => expect(screen.getByLabelText(t('emailChange.code.phone'))).toHaveValue(''));
  });

  it('в поле остаются только цифры, неполный код не отправляется', async () => {
    const user = userEvent.setup();
    const { calls } = form(stateFixture(), server());
    const field = screen.getByLabelText(t('emailChange.code.phone'));

    await user.type(field, '12a-34');
    expect(field).toHaveValue('1234');
    await user.click(screen.getByRole('button', { name: t('emailChange.verify') }));

    expect(await screen.findByText(t('emailChange.error.code'))).toBeInTheDocument();
    expect(calls).toHaveLength(0);
    expect(field).toHaveFocus();
  });

  it('неверный код: остаток попыток из ответа, поле пустое и в фокусе, карточка не перечитывается', async () => {
    const user = userEvent.setup();
    const { onChanged } = form(stateFixture(), server({ verify: () => respond(422, { code: 'otp-invalid', attemptsLeft: 3 }) }));

    await type(user, t('emailChange.code.phone'), '111111');
    await user.click(screen.getByRole('button', { name: t('emailChange.verify') }));

    expect(await screen.findByText(t('problem.otp-invalid', { attempts: 3 }))).toBeInTheDocument();
    const field = screen.getByLabelText(t('emailChange.code.phone'));
    expect(field).toHaveValue('');
    expect(field).toHaveFocus();
    expect(onChanged).not.toHaveBeenCalled();
  });

  it('срок кода истёк: просит запросить новый', async () => {
    const user = userEvent.setup();
    form(stateFixture(), server({ verify: () => respond(422, { code: 'otp-expired' }) }));

    await type(user, t('emailChange.code.phone'), '222222');
    await user.click(screen.getByRole('button', { name: t('emailChange.verify') }));

    expect(await screen.findByText(t('problem.otp-expired'))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: t('emailChange.resend') })).toBeEnabled();
  });

  it('пятая неудача (429 otp-locked): ввод заблокирован, время ожидания из ответа, карточка перечитывается', async () => {
    const user = userEvent.setup();
    const { onChanged } = form(stateFixture(), server({ verify: () => respond(429, { code: 'otp-locked' }, { 'Retry-After': '900' }) }));

    await type(user, t('emailChange.code.phone'), '333333');
    await user.click(screen.getByRole('button', { name: t('emailChange.verify') }));

    expect(await screen.findByText(`${t('problem.otp-locked')} ${t('problem.retryAfterMinutes', { minutes: 15 })}`)).toBeInTheDocument();
    await vi.waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1));
  });

  it('код не остаётся на странице после ответа сервера и не пишется в консоль', async () => {
    const user = userEvent.setup();
    const spies = [vi.spyOn(console, 'log'), vi.spyOn(console, 'info'), vi.spyOn(console, 'debug')];
    for (const spy of spies) {
      spy.mockImplementation(() => undefined);
    }
    try {
      form(stateFixture(), server({ verify: () => respond(422, { code: 'otp-invalid', attemptsLeft: 2 }) }));

      await type(user, t('emailChange.code.phone'), '654321');
      await user.click(screen.getByRole('button', { name: t('emailChange.verify') }));
      await screen.findByText(t('problem.otp-invalid', { attempts: 2 }));

      expect(screen.getByLabelText(t('emailChange.code.phone'))).toHaveValue('');
      expect(document.body.innerHTML).not.toContain('654321');
      expect(window.location.href).not.toContain('654321');
      for (const spy of spies) {
        expect(spy).not.toHaveBeenCalled();
      }
    } finally {
      for (const spy of spies) {
        spy.mockRestore();
      }
    }
  });

  it('обрыв связи: тот же код уходит повторно с тем же ключом, другой код получает новый ключ', async () => {
    const user = userEvent.setup();
    let attempts = 0;
    const { calls } = form(
      stateFixture(),
      server({
        verify: () => {
          attempts += 1;
          return attempts === 1 ? { fail: new TypeError('Failed to fetch') } : respond(422, { code: 'otp-invalid', attemptsLeft: 4 });
        },
      }),
    );

    await type(user, t('emailChange.code.phone'), '123456');
    await user.click(screen.getByRole('button', { name: t('emailChange.verify') }));
    expect(await screen.findByText(t('problem.network'))).toBeInTheDocument();

    await type(user, t('emailChange.code.phone'), '123456');
    await user.click(screen.getByRole('button', { name: t('emailChange.verify') }));
    await screen.findByText(t('problem.otp-invalid', { attempts: 4 }));

    await type(user, t('emailChange.code.phone'), '654321');
    await user.click(screen.getByRole('button', { name: t('emailChange.verify') }));
    await vi.waitFor(() => expect(calls).toHaveLength(3));

    expect(calls[0]?.headers['Idempotency-Key']).toBe('key-1');
    expect(calls[1]?.headers['Idempotency-Key']).toBe('key-1');
    expect(calls[2]?.headers['Idempotency-Key']).not.toBe('key-1');
  });

  it('«Отправить код ещё раз» запрашивает код для этапа SMS и сообщает, что прежний код не действует', async () => {
    const user = userEvent.setup();
    const { calls } = form(stateFixture(), server());

    await user.click(screen.getByRole('button', { name: t('emailChange.resend') }));

    expect(await screen.findByText(t('emailChange.resent.phone'))).toBeInTheDocument();
    expect(calls[0]?.url.pathname).toBe(CODES);
    expect(calls[0]?.init.body).toBe(JSON.stringify({ step: 'phone' }));
    expect(calls[0]?.headers['Idempotency-Key']).toBe('key-1');
  });

  it('лимит запросов кода (429 otp-request-limited): время ожидания, кнопка недоступна до его конца', async () => {
    const user = userEvent.setup();
    form(stateFixture(), server({ codes: () => respond(429, { code: 'otp-request-limited' }, { 'Retry-After': '45' }) }));

    await user.click(screen.getByRole('button', { name: t('emailChange.resend') }));

    expect(await screen.findByText(`${t('problem.otp-request-limited')} ${t('problem.retryAfter', { seconds: 45 })}`)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: t('emailChange.resend') })).toBeDisabled();
    expect(await screen.findByText(/Новый код можно запросить через (44|45|46) с\./)).toBeInTheDocument();
  });
});

describe('TC-713. Форма кода из письма на новый адрес', () => {
  it('после проверки телефона ждёт код из письма: подсказка с маской адреса, код уходит на этап нового адреса', async () => {
    const user = userEvent.setup();
    const { calls, onChanged } = form(AWAITING_EMAIL, server({ verify: () => respond(200, stateFixture({ status: 'completed', phoneCheck: 'passed', newEmailCheck: 'passed' })) }));

    expect(screen.getByText(t('emailChange.step.email', { address: MASKED }))).toBeInTheDocument();
    await type(user, t('emailChange.code.email'), '246810');
    await user.click(screen.getByRole('button', { name: t('emailChange.verify') }));

    await vi.waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1));
    expect(calls[0]?.init.body).toBe(JSON.stringify({ step: 'new_email', code: '246810' }));
  });

  it('письмо с кодом ещё не ушло (смена на этапе письма, проверка не начата): повторный запрос письма доступен', async () => {
    const user = userEvent.setup();
    const state = stateFixture({ status: 'awaiting_email_code', phoneCheck: 'passed', newEmailCheck: 'not_started' });
    const { calls } = form(state, server({ codes: () => respond(200, AWAITING_EMAIL) }));

    await user.click(screen.getByRole('button', { name: t('emailChange.resend') }));

    expect(await screen.findByText(t('emailChange.resent.email', { address: MASKED }))).toBeInTheDocument();
    expect(calls[0]?.init.body).toBe(JSON.stringify({ step: 'new_email' }));
  });

  it('адрес успели занять (409 email-not-allowed): нейтральное «Адрес использовать нельзя» без причины, карточка перечитывается', async () => {
    const user = userEvent.setup();
    const { onChanged } = form(AWAITING_EMAIL, server({ verify: () => respond(409, { code: 'email-not-allowed' }) }));

    await type(user, t('emailChange.code.email'), '135790');
    await user.click(screen.getByRole('button', { name: t('emailChange.verify') }));

    expect(await screen.findByText(t('emailChange.notAllowed'))).toBeInTheDocument();
    expect(screen.queryByText(t('problem.email-not-allowed'))).not.toBeInTheDocument();
    await vi.waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1));
  });

  it('порядок этапов нарушен (409 state-conflict): понятный текст и перечитывание карточки', async () => {
    const user = userEvent.setup();
    const { onChanged } = form(AWAITING_EMAIL, server({ verify: () => respond(409, { code: 'state-conflict' }) }));

    await type(user, t('emailChange.code.email'), '111222');
    await user.click(screen.getByRole('button', { name: t('emailChange.verify') }));

    expect(await screen.findByText(t('problem.state-conflict'))).toBeInTheDocument();
    await vi.waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1));
  });

  it('код из письма принят, смена ещё завершается: поле кода убрано, страница обновится сама', () => {
    form(stateFixture({ status: 'awaiting_email_code', phoneCheck: 'passed', newEmailCheck: 'passed' }), server());
    expect(screen.getByText(t('emailChange.confirming'))).toBeInTheDocument();
    expect(screen.queryByLabelText(t('emailChange.code.email'))).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('emailChange.verify') })).not.toBeInTheDocument();
  });

  it('смена завершена: новый адрес с маской, форма кодов убрана', () => {
    form(stateFixture({ status: 'completed', phoneCheck: 'passed', newEmailCheck: 'passed' }), server());
    expect(screen.getByText(t('emailChange.completed', { address: MASKED }))).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('emailChange.verify') })).not.toBeInTheDocument();
  });

  it('смена не удалась: адрес не изменён, оператор может начать заново, форма кодов убрана', () => {
    form(stateFixture({ status: 'failed', phoneCheck: 'passed', newEmailCheck: 'failed' }), server());
    expect(screen.getByText(t('emailChange.failed'))).toBeInTheDocument();
    expect(screen.queryByLabelText(t('emailChange.code.email'))).not.toBeInTheDocument();
    expect(screen.queryByLabelText(t('emailChange.code.phone'))).not.toBeInTheDocument();
  });
});

describe('TC-713. Какой код ждёт сервер', () => {
  it('сначала SMS, потом письмо, после верного кода из письма никакой', () => {
    expect(awaitedStep(stateFixture())).toBe('phone');
    expect(awaitedStep(AWAITING_EMAIL)).toBe('new_email');
    expect(awaitedStep(stateFixture({ status: 'awaiting_email_code', phoneCheck: 'passed', newEmailCheck: 'not_started' }))).toBe('new_email');
    expect(awaitedStep(stateFixture({ status: 'awaiting_email_code', phoneCheck: 'passed', newEmailCheck: 'passed' }))).toBeUndefined();
    expect(awaitedStep(stateFixture({ status: 'completed' }))).toBeUndefined();
    expect(awaitedStep(stateFixture({ status: 'failed' }))).toBeUndefined();
  });
});
