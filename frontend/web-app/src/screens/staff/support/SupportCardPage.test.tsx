import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { RequireAccess } from '../../../app/Guards';
import { OPERATOR_ROLES } from '../../../auth/claims';
import { t } from '../../../i18n';
import { renderScreen, respond } from '../../../test/harness';
import type { Handler } from '../../../test/harness';
import { MASKED_EMAIL, emailChangeFixture, operatorManager, ownTicket, staffTicket } from '../../../test/staffSupport';
import { formatMoment } from '../../../ui/format';
import { SupportCardPage } from './SupportCardPage';

const BASE = '/api/v1/staff/support-tickets/t-1';

interface State {
  ticket: Record<string, unknown>;
}

function server(state: State, overrides: Record<string, Handler> = {}): Handler {
  return (url, init) => {
    const route = `${init.method ?? 'GET'} ${url.pathname}`;
    const custom = overrides[route];
    if (custom) {
      return custom(url, init);
    }
    return route === `GET ${BASE}` ? respond(200, state.ticket) : respond(404, { code: 'not-found' });
  };
}

function renderCard(state: State, overrides: Record<string, Handler> = {}) {
  return renderScreen(
    <RequireAccess kind="staff" roles={OPERATOR_ROLES} secondFactor>
      <Routes>
        <Route path="/staff/support/:ticketId" element={<SupportCardPage />} />
      </Routes>
    </RequireAccess>,
    { route: '/staff/support/t-1', handler: server(state, overrides), managers: { staff: operatorManager() }, tokens: { staff: 'staff-token' } },
  );
}

const TITLE = { level: 1 as const, name: t('supportCard.title', { number: 1042 }) };

/** Выбирает действие и подтверждает его в панели подтверждения. */
async function perform(user: ReturnType<typeof userEvent.setup>, action: string) {
  await user.click(await screen.findByRole('button', { name: action }));
  const group = await screen.findByRole('group', { name: action });
  await user.click(within(group).getByRole('button', { name: t('supportCard.confirm') }));
}

function posts(calls: Array<{ init: RequestInit; url: URL; headers: Record<string, string> }>) {
  return calls.filter((call) => call.init.method === 'POST');
}

describe('карточка обращения оператора (TC-704)', () => {
  it('показывает заказ, статусы, источник и причину; значений ключа и идентификатора покупателя нет', async () => {
    const { calls } = renderCard({ ticket: staffTicket() });
    expect(await screen.findByRole('heading', TITLE)).toBeInTheDocument();
    expect(screen.getByText(t('supportCard.source.buyer'))).toBeInTheDocument();
    expect(screen.getByText(t('ticket.reason.key_not_received'))).toBeInTheDocument();
    expect(screen.getByText(t('order.status.issued'))).toBeInTheDocument();
    expect(screen.getByText(t('supportCard.delivery.sent'))).toBeInTheDocument();
    expect(screen.getByText(formatMoment('2026-10-17T10:00:00.000Z'))).toBeInTheDocument();
    expect(screen.getByText(formatMoment('2026-10-17T12:00:00.000Z'))).toBeInTheDocument();
    expect(screen.getByText(t('supportCard.operator.none'))).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('buyer-secret-id');
    expect(calls[0]?.url.pathname).toBe(BASE);
    expect(calls[0]?.headers.Authorization).toBe('Bearer staff-token');
  });

  it('заказ не выдан: первичной выдачи нет, статус выдачи «нет данных»', async () => {
    renderCard({ ticket: staffTicket({ orderStatus: 'paid', issuedAt: null, deliveryStatus: null }) });
    expect(await screen.findByRole('heading', TITLE)).toBeInTheDocument();
    expect(screen.getByText(t('supportCard.delivery.none'))).toBeInTheDocument();
    expect(screen.getAllByText(t('supportCard.none'), { selector: 'dd' })).toHaveLength(2);
  });

  it('обращения нет: ошибка загрузки без карточки', async () => {
    renderCard({ ticket: staffTicket() }, { [`GET ${BASE}`]: () => respond(404, { code: 'not-found' }) });
    expect(await screen.findByText(t('problem.not-found'))).toBeInTheDocument();
    expect(screen.queryByRole('heading', TITLE)).not.toBeInTheDocument();
  });

  it('решённое обращение: действий нет, время решения показано', async () => {
    renderCard({ ticket: ownTicket({ status: 'resolved', resolvedAt: '2026-10-17T13:00:00.000Z', deliveryStatus: 'delivered' }) });
    expect(await screen.findByRole('heading', TITLE)).toBeInTheDocument();
    expect(screen.getByText(t('supportCard.resolved'))).toBeInTheDocument();
    expect(screen.getByText(formatMoment('2026-10-17T13:00:00.000Z'))).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('supportCard.resend') })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('supportCard.release') })).not.toBeInTheDocument();
  });

  it('обращение ведёт другой оператор: действий нет', async () => {
    renderCard({ ticket: ownTicket({ operatorId: 'op-2' }) });
    expect(await screen.findByRole('heading', TITLE)).toBeInTheDocument();
    expect(screen.getByText(t('supportCard.otherOperator'))).toBeInTheDocument();
    expect(screen.getByText(t('supportCard.operator.other'))).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('supportCard.resend') })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('supportCard.take') })).not.toBeInTheDocument();
  });
});

describe('карточка: взять в работу и вернуть в очередь (TC-705, TC-706)', () => {
  it('«Взять в работу» с подтверждением: ключ идемпотентности, сообщение, дальше доступны действия оператора', async () => {
    const user = userEvent.setup();
    const state: State = { ticket: staffTicket() };
    const { calls } = renderCard(state, {
      [`POST ${BASE}/take`]: () => {
        state.ticket = ownTicket();
        return respond(200, state.ticket);
      },
    });
    expect(await screen.findByRole('heading', TITLE)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('supportCard.resend') })).not.toBeInTheDocument();
    await perform(user, t('supportCard.take'));
    expect(await screen.findByText(t('supportCard.take.done'))).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: t('supportCard.resend') })).toBeEnabled();
    expect(screen.getByRole('button', { name: t('supportCard.release') })).toBeInTheDocument();
    expect(screen.getByText(t('supportCard.operator.me'))).toBeInTheDocument();
    expect(posts(calls)).toHaveLength(1);
    expect(posts(calls)[0]?.headers['Idempotency-Key']).toBe('key-1');
  });

  it('«Отмена» закрывает подтверждение и ничего не отправляет', async () => {
    const user = userEvent.setup();
    const { calls } = renderCard({ ticket: staffTicket() });
    await user.click(await screen.findByRole('button', { name: t('supportCard.take') }));
    await user.click(screen.getByRole('button', { name: t('supportCard.cancel') }));
    expect(screen.getByRole('button', { name: t('supportCard.take') })).toBeInTheDocument();
    expect(posts(calls)).toHaveLength(0);
  });

  it('другой оператор успел раньше (409): «Обращение уже взято», карточка обновлена, действий нет', async () => {
    const user = userEvent.setup();
    const state: State = { ticket: staffTicket() };
    renderCard(state, {
      [`POST ${BASE}/take`]: () => {
        state.ticket = ownTicket({ operatorId: 'op-2' });
        return respond(409, { code: 'state-conflict' });
      },
    });
    await screen.findByRole('heading', TITLE);
    await perform(user, t('supportCard.take'));
    expect(await screen.findByText(t('supportQueue.alreadyTaken'))).toBeInTheDocument();
    expect(await screen.findByText(t('supportCard.otherOperator'))).toBeInTheDocument();
  });

  it('«Вернуть в очередь»: обращение снова в очереди, можно взять', async () => {
    const user = userEvent.setup();
    const state: State = { ticket: ownTicket() };
    const { calls } = renderCard(state, {
      [`POST ${BASE}/release`]: () => {
        state.ticket = staffTicket();
        return respond(200, state.ticket);
      },
    });
    await screen.findByRole('heading', TITLE);
    await perform(user, t('supportCard.release'));
    expect(await screen.findByText(t('supportCard.release.done'))).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: t('supportCard.take') })).toBeInTheDocument();
    expect(posts(calls)[0]?.url.pathname).toBe(`${BASE}/release`);
  });

  it('вернуть нельзя, обращение уже не у оператора (409): сообщение и обновлённая карточка', async () => {
    const user = userEvent.setup();
    const state: State = { ticket: ownTicket() };
    renderCard(state, {
      [`POST ${BASE}/release`]: () => {
        state.ticket = ownTicket({ operatorId: 'op-2' });
        return respond(409, { code: 'ticket-not-in-progress' });
      },
    });
    await screen.findByRole('heading', TITLE);
    await perform(user, t('supportCard.release'));
    expect(await screen.findByText(t('problem.ticket-not-in-progress'))).toBeInTheDocument();
    expect(await screen.findByText(t('supportCard.otherOperator'))).toBeInTheDocument();
  });
});

describe('карточка: повторная отправка ключа (TC-708)', () => {
  const RESEND = `POST ${BASE}/resend`;
  const accepted = () => respond(202, { ticketId: 't-1', orderId: 'o-1', status: 'accepted' });

  it('после подтверждения уходит один запрос, выдача становится «в очереди», кнопка закрывается с причиной', async () => {
    const user = userEvent.setup();
    const state: State = { ticket: ownTicket() };
    const { calls } = renderCard(state, {
      [RESEND]: () => {
        state.ticket = { ...state.ticket, deliveryStatus: 'queued' };
        return accepted();
      },
    });
    await screen.findByRole('heading', TITLE);
    await user.click(await screen.findByRole('button', { name: t('supportCard.resend') }));
    expect(screen.getByText(t('supportCard.resend.confirm'))).toBeInTheDocument();
    await user.click(within(screen.getByRole('group', { name: t('supportCard.resend') })).getByRole('button', { name: t('supportCard.confirm') }));
    expect(await screen.findByText(t('supportCard.resend.done'))).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: t('supportCard.resend') })).toBeDisabled());
    expect(screen.getByText(t('supportCard.delivery.queued'))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: t('supportCard.resend') })).toHaveAccessibleDescription(t('supportCard.resend.blocked.queued'));
    const sent = posts(calls);
    expect(sent).toHaveLength(1);
    expect(sent[0]?.url.pathname).toBe(`${BASE}/resend`);
    expect(sent[0]?.headers['Idempotency-Key']).toBe('key-1');
    expect(sent[0]?.init.body).toBeUndefined();
  });

  it('двойное нажатие «Подтвердить» даёт один запрос', async () => {
    const user = userEvent.setup();
    const { calls } = renderCard(
      { ticket: ownTicket() },
      {
        [RESEND]: async () => {
          await new Promise((resolve) => setTimeout(resolve, 20));
          return accepted();
        },
      },
    );
    await screen.findByRole('heading', TITLE);
    await user.click(await screen.findByRole('button', { name: t('supportCard.resend') }));
    await user.dblClick(within(screen.getByRole('group', { name: t('supportCard.resend') })).getByRole('button', { name: t('supportCard.confirm') }));
    expect(await screen.findByText(t('supportCard.resend.done'))).toBeInTheDocument();
    expect(posts(calls)).toHaveLength(1);
  });

  it('выдача уже «в очереди»: кнопка недоступна, причина названа, запроса нет', async () => {
    const { calls } = renderCard({ ticket: ownTicket({ deliveryStatus: 'queued' }) });
    const button = await screen.findByRole('button', { name: t('supportCard.resend') });
    expect(button).toBeDisabled();
    expect(button).toHaveAccessibleDescription(t('supportCard.resend.blocked.queued'));
    expect(posts(calls)).toHaveLength(0);
  });

  it('заказ не «выдан»: кнопка недоступна с причиной', async () => {
    renderCard({ ticket: ownTicket({ orderStatus: 'refunded' }) });
    const button = await screen.findByRole('button', { name: t('supportCard.resend') });
    expect(button).toBeDisabled();
    expect(screen.getByText(t('supportCard.resend.blocked.order'))).toBeInTheDocument();
  });

  it('выдача запущена другим способом (409 delivery-in-progress): сообщение, карточка обновлена, кнопка закрыта', async () => {
    const user = userEvent.setup();
    const state: State = { ticket: ownTicket() };
    renderCard(state, {
      [RESEND]: () => {
        state.ticket = { ...state.ticket, deliveryStatus: 'queued' };
        return respond(409, { code: 'delivery-in-progress' });
      },
    });
    await screen.findByRole('heading', TITLE);
    await perform(user, t('supportCard.resend'));
    expect(await screen.findByText(t('problem.delivery-in-progress'))).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: t('supportCard.resend') })).toBeDisabled());
  });

  it('обращение уже не у оператора (409 ticket-not-in-progress): сообщение и обновлённая карточка', async () => {
    const user = userEvent.setup();
    const state: State = { ticket: ownTicket() };
    renderCard(state, {
      [RESEND]: () => {
        state.ticket = staffTicket();
        return respond(409, { code: 'ticket-not-in-progress' });
      },
    });
    await screen.findByRole('heading', TITLE);
    await perform(user, t('supportCard.resend'));
    expect(await screen.findByText(t('problem.ticket-not-in-progress'))).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: t('supportCard.take') })).toBeInTheDocument();
  });

  it('заказ уже не «выдан» (409 state-conflict): сообщение сервера и обновлённая карточка', async () => {
    const user = userEvent.setup();
    const state: State = { ticket: ownTicket() };
    renderCard(state, {
      [RESEND]: () => {
        state.ticket = { ...state.ticket, orderStatus: 'refunded' };
        return respond(409, { code: 'state-conflict' });
      },
    });
    await screen.findByRole('heading', TITLE);
    await perform(user, t('supportCard.resend'));
    expect(await screen.findByText(t('problem.state-conflict'))).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: t('supportCard.resend') })).toBeDisabled());
  });

  it('сосед недоступен (503): ошибка в панели, повтор уходит с тем же ключом и принимается', async () => {
    const user = userEvent.setup();
    let attempts = 0;
    const { calls } = renderCard(
      { ticket: ownTicket() },
      {
        [RESEND]: () => {
          attempts += 1;
          return attempts === 1 ? respond(503, { code: 'dependency-unavailable' }, { 'Retry-After': '5' }) : accepted();
        },
      },
    );
    await screen.findByRole('heading', TITLE);
    await perform(user, t('supportCard.resend'));
    const group = await screen.findByRole('group', { name: t('supportCard.resend') });
    expect(await within(group).findByRole('alert')).toBeInTheDocument();
    await user.click(within(group).getByRole('button', { name: t('supportCard.confirm') }));
    expect(await screen.findByText(t('supportCard.resend.done'))).toBeInTheDocument();
    expect(posts(calls).map((call) => call.headers['Idempotency-Key'])).toEqual(['key-1', 'key-1']);
  });

  it('обрыв связи: повтор уходит с тем же ключом, окончательный ответ меняет ключ для следующей отправки', async () => {
    const user = userEvent.setup();
    let attempts = 0;
    const { calls } = renderCard(
      { ticket: ownTicket() },
      {
        [RESEND]: () => {
          attempts += 1;
          return attempts === 1 ? { fail: new TypeError('offline') } : accepted();
        },
      },
    );
    await screen.findByRole('heading', TITLE);
    await perform(user, t('supportCard.resend'));
    const group = await screen.findByRole('group', { name: t('supportCard.resend') });
    await within(group).findByRole('alert');
    await user.click(within(group).getByRole('button', { name: t('supportCard.confirm') }));
    expect(await screen.findByText(t('supportCard.resend.done'))).toBeInTheDocument();
    await perform(user, t('supportCard.resend'));
    await waitFor(() => expect(posts(calls)).toHaveLength(3));
    expect(posts(calls).map((call) => call.headers['Idempotency-Key'])).toEqual(['key-1', 'key-1', 'key-2']);
  });
});

describe('карточка: смена e-mail (TC-710)', () => {
  const CHANGE = `POST ${BASE}/email-change`;

  function started(): Response {
    return respond(201, emailChangeFixture());
  }

  it('новый адрес приводится к нижнему регистру и без пробелов, после ответа поле пусто и адреса на экране нет, видна только маска', async () => {
    const user = userEvent.setup();
    const state: State = { ticket: ownTicket() };
    const { calls } = renderCard(state, {
      [CHANGE]: () => {
        state.ticket = { ...state.ticket, emailChange: emailChangeFixture() };
        return started();
      },
    });
    await screen.findByRole('heading', TITLE);
    await user.click(await screen.findByRole('button', { name: t('supportCard.emailChange') }));
    await user.type(screen.getByLabelText(t('supportCard.emailChange.field')), '  New.Address@Mail.Example ');
    await user.click(within(screen.getByRole('group', { name: t('supportCard.emailChange') })).getByRole('button', { name: t('supportCard.confirm') }));
    expect(await screen.findByText(t('supportCard.emailChange.done'))).toBeInTheDocument();
    expect(await screen.findByText(MASKED_EMAIL)).toBeInTheDocument();
    expect(screen.getByText(t('supportCard.change.awaiting_phone_code'))).toBeInTheDocument();
    expect(screen.getByText(t('supportCard.check.pending'))).toBeInTheDocument();
    expect(screen.getByText(t('supportCard.check.not_started'))).toBeInTheDocument();
    expect(screen.queryByLabelText(t('supportCard.emailChange.field'))).not.toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('new.address@mail.example');
    const sent = posts(calls);
    expect(sent).toHaveLength(1);
    expect(sent[0]?.url.pathname).toBe(`${BASE}/email-change`);
    expect(JSON.parse(String(sent[0]?.init.body))).toEqual({ newEmail: 'new.address@mail.example' });
    expect(sent[0]?.headers['Idempotency-Key']).toBe('key-1');
  });

  it('не адрес: ошибка у поля, запроса нет', async () => {
    const user = userEvent.setup();
    const { calls } = renderCard({ ticket: ownTicket() });
    await user.click(await screen.findByRole('button', { name: t('supportCard.emailChange') }));
    await user.type(screen.getByLabelText(t('supportCard.emailChange.field')), 'без-собаки');
    await user.click(screen.getByRole('button', { name: t('supportCard.confirm') }));
    expect(await screen.findByText(t('supportCard.emailChange.invalid'))).toBeInTheDocument();
    expect(posts(calls)).toHaveLength(0);
  });

  it('занятый или собственный адрес (409 email-not-allowed): один нейтральный текст без причины, поле очищено', async () => {
    const user = userEvent.setup();
    renderCard({ ticket: ownTicket() }, { [CHANGE]: () => respond(409, { code: 'email-not-allowed' }) });
    await screen.findByRole('heading', TITLE);
    await user.click(await screen.findByRole('button', { name: t('supportCard.emailChange') }));
    await user.type(screen.getByLabelText(t('supportCard.emailChange.field')), 'busy@mail.example');
    await user.click(screen.getByRole('button', { name: t('supportCard.confirm') }));
    expect(await screen.findByText(t('supportCard.emailChange.notAllowed'))).toBeInTheDocument();
    expect(screen.getByLabelText(t('supportCard.emailChange.field'))).toHaveValue('');
    expect(document.body).not.toHaveTextContent('busy@mail.example');
  });

  it('предел запросов кода (429): ответ сервера словами, поле очищено', async () => {
    const user = userEvent.setup();
    renderCard({ ticket: ownTicket() }, { [CHANGE]: () => respond(429, { code: 'otp-request-limited' }, { 'Retry-After': '60' }) });
    await screen.findByRole('heading', TITLE);
    await user.click(await screen.findByRole('button', { name: t('supportCard.emailChange') }));
    await user.type(screen.getByLabelText(t('supportCard.emailChange.field')), 'new@mail.example');
    await user.click(screen.getByRole('button', { name: t('supportCard.confirm') }));
    expect(await screen.findByText((text) => text.includes(t('problem.otp-request-limited')))).toBeInTheDocument();
    expect(screen.getByLabelText(t('supportCard.emailChange.field'))).toHaveValue('');
  });

  it('обрыв связи: адрес остаётся в поле, повтор уходит с тем же ключом', async () => {
    const user = userEvent.setup();
    let attempts = 0;
    const state: State = { ticket: ownTicket() };
    const { calls } = renderCard(state, {
      [CHANGE]: () => {
        attempts += 1;
        if (attempts === 1) {
          return { fail: new TypeError('offline') };
        }
        state.ticket = { ...state.ticket, emailChange: emailChangeFixture() };
        return started();
      },
    });
    await screen.findByRole('heading', TITLE);
    await user.click(await screen.findByRole('button', { name: t('supportCard.emailChange') }));
    await user.type(screen.getByLabelText(t('supportCard.emailChange.field')), 'new@mail.example');
    await user.click(screen.getByRole('button', { name: t('supportCard.confirm') }));
    await screen.findByRole('alert');
    expect(screen.getByLabelText(t('supportCard.emailChange.field'))).toHaveValue('new@mail.example');
    await user.click(screen.getByRole('button', { name: t('supportCard.confirm') }));
    expect(await screen.findByText(t('supportCard.emailChange.done'))).toBeInTheDocument();
    expect(posts(calls).map((call) => call.headers['Idempotency-Key'])).toEqual(['key-1', 'key-1']);
  });

  it('смена завершена: заменить нельзя, кнопка недоступна с причиной', async () => {
    renderCard({ ticket: ownTicket({ emailChange: emailChangeFixture({ status: 'completed', phoneCheck: 'passed', newEmailCheck: 'passed' }) }) });
    const button = await screen.findByRole('button', { name: t('supportCard.emailChange') });
    expect(button).toBeDisabled();
    expect(screen.getByText(t('supportCard.emailChange.blocked.completed'))).toBeInTheDocument();
    expect(screen.getByText(t('supportCard.change.completed'))).toBeInTheDocument();
    expect(screen.getAllByText(t('supportCard.check.passed'))).toHaveLength(2);
  });

  it('обращение уже не у оператора (409 ticket-not-in-progress): сообщение и обновлённая карточка', async () => {
    const user = userEvent.setup();
    const state: State = { ticket: ownTicket() };
    renderCard(state, {
      [CHANGE]: () => {
        state.ticket = staffTicket();
        return respond(409, { code: 'ticket-not-in-progress' });
      },
    });
    await screen.findByRole('heading', TITLE);
    await user.click(await screen.findByRole('button', { name: t('supportCard.emailChange') }));
    await user.type(screen.getByLabelText(t('supportCard.emailChange.field')), 'new@mail.example');
    await user.click(screen.getByRole('button', { name: t('supportCard.confirm') }));
    expect(await screen.findByText(t('problem.ticket-not-in-progress'))).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: t('supportCard.take') })).toBeInTheDocument();
  });
});

describe('оператор не видит коды и полный адрес (TC-711)', () => {
  it('в карточке только результат проверок и маска, полей для кодов нет', async () => {
    renderCard({ ticket: ownTicket({ emailChange: emailChangeFixture({ status: 'failed', phoneCheck: 'passed', newEmailCheck: 'failed' }) }) });
    expect(await screen.findByRole('heading', TITLE)).toBeInTheDocument();
    expect(screen.getByText(t('supportCard.check.passed'))).toBeInTheDocument();
    expect(screen.getByText(t('supportCard.check.failed'))).toBeInTheDocument();
    expect(screen.getByText(t('supportCard.change.failed'))).toBeInTheDocument();
    expect(screen.getByText(t('supportCard.change.note'))).toBeInTheDocument();
    expect(screen.getAllByText(/@/)).toHaveLength(1);
    expect(screen.getByText(MASKED_EMAIL)).toBeInTheDocument();
    expect(document.querySelectorAll('input')).toHaveLength(0);
    expect(document.body.textContent ?? '').not.toMatch(/\d{6}/);
  });
});
