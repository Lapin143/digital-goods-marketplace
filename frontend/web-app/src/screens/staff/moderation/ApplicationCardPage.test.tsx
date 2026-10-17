import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { t } from '../../../i18n';
import { renderScreen, respond } from '../../../test/harness';
import type { Handler } from '../../../test/harness';
import { ApplicationCardPage } from './ApplicationCardPage';

const SCREEN = (
  <Routes>
    <Route path="/staff/applications/:sellerId" element={<ApplicationCardPage />} />
  </Routes>
);
const ROUTE = '/staff/applications/s-1';
const BASE = '/api/v1/staff/seller-applications/s-1';

function card(overrides: Record<string, unknown> = {}, expiresInMs = 5 * 60_000) {
  return {
    sellerId: 's-1',
    userId: 'u-1',
    status: 'on_review',
    sellerType: 'sole_proprietor',
    name: 'ИП Иванов',
    payoutDetails: { recipientName: 'Иванов Иван', bankName: 'Банк Тест', bik: '044525225', accountNumber: '40702810400000000001' },
    assortmentDescription: 'Ключи для игр\nи подарочные карты',
    documents: [
      {
        documentId: 'd-1',
        fileName: 'passport.pdf',
        contentType: 'application/pdf',
        sizeBytes: 2048,
        uploadedAt: '2026-10-17T09:00:00Z',
        link: { url: 'https://storage.example/d-1?sig=1', expiresAt: new Date(Date.now() + expiresInMs).toISOString() },
      },
    ],
    submittedAt: '2026-10-17T09:30:00Z',
    reviewDeadlineAt: '2026-10-20T09:30:00Z',
    ...overrides,
  };
}

function server(state: { card: ReturnType<typeof card> }, overrides: Record<string, Handler> = {}): Handler {
  return (url, init) => {
    const route = `${init.method ?? 'GET'} ${url.pathname}`;
    const custom = overrides[route];
    if (custom) {
      return custom(url, init);
    }
    if (route === `GET ${BASE}`) {
      return respond(200, state.card);
    }
    return respond(404, { code: 'not-found' });
  };
}

async function choose(user: ReturnType<typeof userEvent.setup>, name: string) {
  await user.click(await screen.findByRole('button', { name }));
}

describe('карточка заявки: анкета и документы', () => {
  it('показывает анкету, реквизиты и документ со ссылкой в новой вкладке', async () => {
    renderScreen(SCREEN, { route: ROUTE, handler: server({ card: card() }) });
    expect(await screen.findByRole('heading', { level: 1, name: 'ИП Иванов' })).toBeInTheDocument();
    expect(screen.getByText('Иванов Иван')).toBeInTheDocument();
    expect(screen.getByText('044525225')).toBeInTheDocument();
    expect(screen.getByText('40702810400000000001')).toBeInTheDocument();
    expect(screen.getByText(/Ключи для игр/)).toBeInTheDocument();

    const link = screen.getByRole('link', { name: 'passport.pdf' });
    expect(link).toHaveAttribute('href', 'https://storage.example/d-1?sig=1');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    expect(link).toHaveAttribute('referrerpolicy', 'no-referrer');
    expect(screen.getByText(/Ссылки на документы действуют ещё [45]:\d\d\./)).toBeInTheDocument();
  });

  it('ссылка просрочена: имя файла без ссылки и подсказка обновить карточку', async () => {
    renderScreen(SCREEN, { route: ROUTE, handler: server({ card: card({}, -1000) }) });
    expect(await screen.findByText(t('moderation.documents.expired'))).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'passport.pdf' })).not.toBeInTheDocument();
    expect(screen.getByText('passport.pdf')).toBeInTheDocument();
  });

  it('срок ссылки считается по часам сервера: сервер впереди на 10 минут, ссылка уже недействительна', async () => {
    const ahead = new Date(Date.now() + 10 * 60_000).toUTCString();
    renderScreen(SCREEN, {
      route: ROUTE,
      handler: (url) => (url.pathname === BASE ? respond(200, card(), { Date: ahead }) : respond(404, { code: 'not-found' })),
    });
    expect(await screen.findByText(t('moderation.documents.expired'))).toBeInTheDocument();
  });

  it('«Обновить карточку» запрашивает карточку заново и возвращает рабочие ссылки', async () => {
    const user = userEvent.setup();
    const state = { card: card({}, -1000) };
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler: server(state) });
    await screen.findByText(t('moderation.documents.expired'));
    state.card = card();
    await user.click(screen.getByRole('button', { name: t('moderation.refresh') }));
    expect(await screen.findByRole('link', { name: 'passport.pdf' })).toBeInTheDocument();
    expect(calls.filter((call) => call.url.pathname === BASE)).toHaveLength(2);
  });

  it('заявки нет: сообщение об ошибке', async () => {
    renderScreen(SCREEN, { route: ROUTE, handler: () => respond(404, { code: 'not-found' }) });
    expect(await screen.findByText(t('problem.not-found'))).toBeInTheDocument();
  });

  it('заявка уже обработана: решений нет, есть пояснение со статусом', async () => {
    renderScreen(SCREEN, { route: ROUTE, handler: server({ card: card({ status: 'approved' }) }) });
    expect(await screen.findByText(t('moderation.application.finished', { status: t('sellerStatus.approved') }))).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('moderation.application.approve') })).not.toBeInTheDocument();
  });
});

describe('карточка заявки: решения', () => {
  it('одобрить: сначала подтверждение (запроса ещё нет), затем POST с Idempotency-Key без тела и обновление карточки', async () => {
    const user = userEvent.setup();
    const state = { card: card() };
    const handler = server(state, {
      [`POST ${BASE}/approve`]: () => {
        state.card = card({ status: 'approved' });
        return respond(200, { sellerId: 's-1', status: 'approved' });
      },
    });
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler });
    await choose(user, t('moderation.application.approve'));
    expect(screen.getByText(t('moderation.application.approve.confirm'))).toBeInTheDocument();
    expect(calls.every((call) => call.init.method !== 'POST')).toBe(true);

    await user.click(screen.getByRole('button', { name: t('moderation.decision.confirm') }));
    expect(await screen.findByText(t('moderation.application.approve.done'))).toBeInTheDocument();
    const post = calls.find((call) => call.init.method === 'POST');
    expect(post?.url.pathname).toBe(`${BASE}/approve`);
    expect(post?.headers['Idempotency-Key']).toBe('key-1');
    expect(post?.init.body).toBeUndefined();
    // Карточка загружена заново и показывает новый статус, решений больше нет
    expect(await screen.findByText(t('moderation.application.finished', { status: t('sellerStatus.approved') }))).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('moderation.application.approve') })).not.toBeInTheDocument();
  });

  it('отмена на шаге подтверждения возвращает кнопки решений, запроса нет', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler: server({ card: card() }) });
    await choose(user, t('moderation.application.reject'));
    await user.click(screen.getByRole('button', { name: t('moderation.decision.cancel') }));
    expect(screen.getByRole('button', { name: t('moderation.application.reject') })).toBeInTheDocument();
    expect(calls.every((call) => call.init.method !== 'POST')).toBe(true);
  });

  it('отказ без причины не отправляется: ошибка у поля', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler: server({ card: card() }) });
    await choose(user, t('moderation.application.reject'));
    await user.click(screen.getByRole('button', { name: t('moderation.decision.confirm') }));
    expect(await screen.findByText(t('moderation.error.reasonRequired'))).toBeInTheDocument();
    await user.type(screen.getByLabelText(t('moderation.application.reject.reason')), '    ');
    await user.click(screen.getByRole('button', { name: t('moderation.decision.confirm') }));
    expect(screen.getByText(t('moderation.error.reasonRequired'))).toBeInTheDocument();
    expect(calls.every((call) => call.init.method !== 'POST')).toBe(true);
  });

  it('отказ с причиной уходит с полем reason, обрезанным по краям', async () => {
    const user = userEvent.setup();
    const state = { card: card() };
    const handler = server(state, {
      [`POST ${BASE}/reject`]: () => {
        state.card = card({ status: 'rejected' });
        return respond(200, { sellerId: 's-1', status: 'rejected' });
      },
    });
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler });
    await choose(user, t('moderation.application.reject'));
    await user.type(screen.getByLabelText(t('moderation.application.reject.reason')), '  Документы не читаются  ');
    await user.click(screen.getByRole('button', { name: t('moderation.decision.confirm') }));
    expect(await screen.findByText(t('moderation.application.reject.done'))).toBeInTheDocument();
    expect(JSON.parse(String(calls.find((call) => call.init.method === 'POST')?.init.body))).toEqual({ reason: 'Документы не читаются' });
  });

  it('возврат на доработку уходит с полем comment', async () => {
    const user = userEvent.setup();
    const state = { card: card() };
    const handler = server(state, {
      [`POST ${BASE}/return`]: () => {
        state.card = card({ status: 'returned' });
        return respond(200, { sellerId: 's-1', status: 'returned' });
      },
    });
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler });
    await choose(user, t('moderation.application.return'));
    await user.type(screen.getByLabelText(t('moderation.application.return.comment')), 'Нужен скан паспорта');
    await user.click(screen.getByRole('button', { name: t('moderation.decision.confirm') }));
    expect(await screen.findByText(t('moderation.application.return.done'))).toBeInTheDocument();
    expect(JSON.parse(String(calls.find((call) => call.init.method === 'POST')?.init.body))).toEqual({ comment: 'Нужен скан паспорта' });
  });

  it('двойное нажатие «Подтвердить» создаёт один запрос', async () => {
    const user = userEvent.setup();
    let release: (() => void) | undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const state = { card: card() };
    const handler = server(state, {
      [`POST ${BASE}/approve`]: async () => {
        await gate;
        state.card = card({ status: 'approved' });
        return respond(200, { sellerId: 's-1', status: 'approved' });
      },
    });
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler });
    await choose(user, t('moderation.application.approve'));
    const confirm = screen.getByRole('button', { name: t('moderation.decision.confirm') });
    // Два клика подряд до перерисовки: второй гасит флаг запроса, а не только недоступная кнопка
    fireEvent.click(confirm);
    fireEvent.click(confirm);
    release?.();
    expect(await screen.findByText(t('moderation.application.approve.done'))).toBeInTheDocument();
    expect(calls.filter((call) => call.init.method === 'POST')).toHaveLength(1);
  });

  it('409 «уже решено другим модератором»: сообщение со статусом, карточка обновлена, кнопок решений нет', async () => {
    const user = userEvent.setup();
    const state = { card: card() };
    const handler = server(state, {
      [`POST ${BASE}/approve`]: () => {
        state.card = card({ status: 'rejected' });
        return respond(409, { code: 'state-conflict', currentStatus: 'rejected' });
      },
    });
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler });
    await choose(user, t('moderation.application.approve'));
    await user.click(screen.getByRole('button', { name: t('moderation.decision.confirm') }));

    expect(await screen.findByText(t('moderation.alreadyDecided', { status: t('sellerStatus.rejected') }))).toBeInTheDocument();
    expect(await screen.findByText(t('moderation.application.finished', { status: t('sellerStatus.rejected') }))).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('moderation.application.approve') })).not.toBeInTheDocument();
    expect(calls.filter((call) => call.url.pathname === BASE).length).toBeGreaterThanOrEqual(2);
  });

  it('сбой сети: подтверждение остаётся открытым, повтор уходит с тем же ключом и тем же телом', async () => {
    const user = userEvent.setup();
    let attempt = 0;
    const state = { card: card() };
    const handler = server(state, {
      [`POST ${BASE}/reject`]: () => {
        attempt += 1;
        if (attempt === 1) {
          return { fail: new TypeError('network') };
        }
        state.card = card({ status: 'rejected' });
        return respond(200, { sellerId: 's-1', status: 'rejected' });
      },
    });
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler });
    await choose(user, t('moderation.application.reject'));
    await user.type(screen.getByLabelText(t('moderation.application.reject.reason')), 'Нет документов');
    await user.click(screen.getByRole('button', { name: t('moderation.decision.confirm') }));
    expect(await screen.findByText(t('problem.network'))).toBeInTheDocument();
    expect(screen.getByLabelText(t('moderation.application.reject.reason'))).toHaveValue('Нет документов');

    await user.click(screen.getByRole('button', { name: t('moderation.decision.confirm') }));
    expect(await screen.findByText(t('moderation.application.reject.done'))).toBeInTheDocument();
    const posts = calls.filter((call) => call.init.method === 'POST');
    expect(posts.map((call) => call.headers['Idempotency-Key'])).toEqual(['key-1', 'key-1']);
    expect(posts[1]?.init.body).toBe(posts[0]?.init.body);
  });

  it('окончательный отказ сервера: следующая попытка получает новый ключ', async () => {
    const user = userEvent.setup();
    const handler = server({ card: card() }, { [`POST ${BASE}/approve`]: () => respond(403, { code: 'forbidden' }) });
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler });
    await choose(user, t('moderation.application.approve'));
    await user.click(screen.getByRole('button', { name: t('moderation.decision.confirm') }));
    expect(await screen.findByText(t('problem.forbidden'))).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: t('moderation.decision.confirm') }));
    await waitFor(() => expect(calls.filter((call) => call.init.method === 'POST')).toHaveLength(2));
    expect(calls.filter((call) => call.init.method === 'POST').map((call) => call.headers['Idempotency-Key'])).toEqual(['key-1', 'key-2']);
  });

  it('текст причины длиннее 1000 символов не отправляется', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(SCREEN, { route: ROUTE, handler: server({ card: card() }) });
    await choose(user, t('moderation.application.reject'));
    fireEvent.change(screen.getByLabelText(t('moderation.application.reject.reason')), { target: { value: 'а'.repeat(1001) } });
    await user.click(screen.getByRole('button', { name: t('moderation.decision.confirm') }));
    expect(await screen.findByText(t('moderation.error.reasonTooLong'))).toBeInTheDocument();
    expect(calls.every((call) => call.init.method !== 'POST')).toBe(true);
  });

  it('в списке документов только файлы этой заявки', async () => {
    renderScreen(SCREEN, { route: ROUTE, handler: server({ card: card() }) });
    const list = await screen.findByRole('list', { name: t('moderation.documents.list') });
    expect(within(list).getAllByRole('listitem')).toHaveLength(1);
  });
});
