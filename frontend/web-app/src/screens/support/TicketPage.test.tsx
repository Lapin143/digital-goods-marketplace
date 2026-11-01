import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { t } from '../../i18n';
import { formatMoment } from '../../ui/format';
import { renderScreen, respond } from '../../test/harness';
import type { Handler } from '../../test/harness';
import { TicketPage } from './TicketPage';

const CREATED_AT = '2026-10-17T12:00:00.000Z';
const MASKED = 'n***@mail.example';

function ticketFixture(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    ticketId: 't-1',
    orderId: 'o-1',
    source: 'buyer',
    reason: 'key_not_received',
    status: 'created',
    createdAt: CREATED_AT,
    resolvedAt: null,
    resolution: null,
    emailChange: null,
    ...overrides,
  };
}

function emailChangeFixture(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    status: 'awaiting_phone_code',
    phoneCheck: 'pending',
    newEmailCheck: 'not_started',
    newEmailMasked: MASKED,
    startedAt: CREATED_AT,
    updatedAt: CREATED_AT,
    ...overrides,
  };
}

function page(handler: Handler) {
  return renderScreen(
    <Routes>
      <Route path="/account/tickets/:ticketId" element={<TicketPage />} />
    </Routes>,
    { route: '/account/tickets/t-1', handler },
  );
}

const ticketPath = (url: URL) => url.pathname === '/api/v1/support-tickets/t-1';

describe('TC-701. Карточка обращения: статус по SM-07', () => {
  it('создано: статус «Принято», причина, источник, время, ссылка на заказ, подсказка про обновление', async () => {
    page((url) => (ticketPath(url) ? respond(200, ticketFixture()) : respond(404, { code: 'not-found' })));

    expect(await screen.findByRole('heading', { level: 1, name: t('ticket.title') })).toBeInTheDocument();
    expect(screen.getByText(t('ticket.state.created'))).toBeInTheDocument();
    expect(screen.getByText(t('ticket.status.created'))).toBeInTheDocument();
    expect(screen.getByText(t('ticket.reason.key_not_received'))).toBeInTheDocument();
    expect(screen.getByText(t('ticket.source.buyer'))).toBeInTheDocument();
    expect(screen.getByText(formatMoment(CREATED_AT))).toBeInTheDocument();
    expect(screen.getByRole('link', { name: t('ticket.order.open') })).toHaveAttribute('href', '/account/orders/o-1');
    expect(screen.getByRole('link', { name: t('ticket.back') })).toHaveAttribute('href', '/account/tickets');
    expect(screen.getByText(t('ticket.autoRefresh'))).toBeInTheDocument();
    expect(screen.queryByText(t('emailChange.title'))).not.toBeInTheDocument();
  });

  it('в работе: оператор взял обращение', async () => {
    page(() => respond(200, ticketFixture({ status: 'in_progress' })));
    expect(await screen.findByText(t('ticket.state.in_progress'))).toBeInTheDocument();
    expect(screen.getByText(t('ticket.status.in_progress'))).toBeInTheDocument();
  });

  it('решено системой по факту доставки: время решения и итог, подсказки про обновление нет', async () => {
    page(() =>
      respond(200, ticketFixture({ status: 'resolved', resolvedAt: '2026-10-17T12:05:00.000Z', resolution: { resolvedBy: 'system', action: 'resend' } })),
    );
    expect(await screen.findByText(t('ticket.state.resolved'))).toBeInTheDocument();
    expect(screen.getByText(t('ticket.resolution.system.resend'))).toBeInTheDocument();
    expect(screen.getByText(formatMoment('2026-10-17T12:05:00.000Z'))).toBeInTheDocument();
    expect(screen.queryByText(t('ticket.autoRefresh'))).not.toBeInTheDocument();
  });

  it('обращение создала система: источник показан словами', async () => {
    page(() => respond(200, ticketFixture({ source: 'system', reason: 'attempts_exhausted' })));
    expect(await screen.findByText(t('ticket.source.system'))).toBeInTheDocument();
    expect(screen.getByText(t('ticket.reason.attempts_exhausted'))).toBeInTheDocument();
  });

  it('чужое и несуществующее обращение: одна страница «не найдено» без подробностей', async () => {
    page(() => respond(404, { code: 'not-found' }));
    expect(await screen.findByRole('heading', { name: t('ticket.notFound.title') })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: t('ticket.back') })).toHaveAttribute('href', '/account/tickets');
  });

  it('сбой сервера: сообщение и «Повторить», после повтора обращение показано', async () => {
    const user = userEvent.setup();
    let failing = true;
    page(() => (failing ? respond(503, { code: 'dependency-unavailable' }) : respond(200, ticketFixture())));
    expect(await screen.findByRole('alert')).toHaveTextContent(t('problem.dependency-unavailable'));
    failing = false;
    await user.click(screen.getByRole('button', { name: t('state.retry') }));
    expect(await screen.findByRole('heading', { level: 1, name: t('ticket.title') })).toBeInTheDocument();
  });

  it('значений ключей на экране нет, даже если сервер прислал их по ошибке (INV-20)', async () => {
    page(() => respond(200, ticketFixture({ key: 'ABCDE-FGHIJ-KLMNO', keys: ['ABCDE-FGHIJ-KLMNO'], delivery: { value: 'ABCDE-FGHIJ-KLMNO' } })));
    await screen.findByRole('heading', { level: 1, name: t('ticket.title') });
    expect(document.body.textContent ?? '').not.toContain('ABCDE');
  });
});

describe('TC-712. Карточка обращения: форма кодов появляется, когда оператор начал смену e-mail', () => {
  it('смена начата: форма кода из SMS с маской нового адреса', async () => {
    page(() => respond(200, ticketFixture({ status: 'in_progress', emailChange: emailChangeFixture() })));
    expect(await screen.findByRole('heading', { level: 2, name: t('emailChange.title') })).toBeInTheDocument();
    expect(screen.getByLabelText(t('emailChange.code.phone'))).toBeInTheDocument();
    expect(screen.getByText(t('emailChange.lead', { address: MASKED }))).toBeInTheDocument();
  });

  it('код из SMS принят: карточка перечитывается и показывает ввод кода из письма', async () => {
    const user = userEvent.setup();
    let verified = false;
    const { calls } = page((url, init) => {
      if (url.pathname === '/api/v1/support-tickets/t-1/email-change/verifications') {
        verified = true;
        return respond(200, emailChangeFixture({ status: 'awaiting_email_code', phoneCheck: 'passed', newEmailCheck: 'pending' }));
      }
      if (ticketPath(url) && (init.method ?? 'GET') === 'GET') {
        return respond(
          200,
          ticketFixture({
            status: 'in_progress',
            emailChange: verified
              ? emailChangeFixture({ status: 'awaiting_email_code', phoneCheck: 'passed', newEmailCheck: 'pending' })
              : emailChangeFixture(),
          }),
        );
      }
      return respond(404, { code: 'not-found' });
    });

    await user.type(await screen.findByLabelText(t('emailChange.code.phone')), '123456');
    await user.click(screen.getByRole('button', { name: t('emailChange.verify') }));

    expect(await screen.findByLabelText(t('emailChange.code.email'))).toBeInTheDocument();
    expect(screen.getByText(t('emailChange.step.email', { address: MASKED }))).toBeInTheDocument();
    expect(calls.filter((call) => ticketPath(call.url)).length).toBeGreaterThanOrEqual(2);
  });

  it('смена завершена и обращение решено: итог «адрес изменён», формы нет', async () => {
    page(() =>
      respond(
        200,
        ticketFixture({
          status: 'resolved',
          resolvedAt: '2026-10-17T12:30:00.000Z',
          resolution: { resolvedBy: 'system', action: 'email_change' },
          emailChange: emailChangeFixture({ status: 'completed', phoneCheck: 'passed', newEmailCheck: 'passed' }),
        }),
      ),
    );
    expect(await screen.findByText(t('emailChange.completed', { address: MASKED }))).toBeInTheDocument();
    expect(screen.getByText(t('ticket.resolution.system.email_change'))).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('emailChange.verify') })).not.toBeInTheDocument();
  });
});
