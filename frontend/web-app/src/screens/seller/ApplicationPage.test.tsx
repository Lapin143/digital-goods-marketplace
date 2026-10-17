import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { t } from '../../i18n';
import { renderScreen, respond } from '../../test/harness';
import type { Handler } from '../../test/harness';
import { formatMoment } from '../../ui/format';
import { ApplicationPage } from './ApplicationPage';

const FULL_FORM = {
  sellerType: 'sole_proprietor',
  name: 'ИП Иванов',
  payoutDetails: { recipientName: 'Иванов Иван', bankName: 'Банк', bik: '044525225', accountNumber: '40702810400000000001' },
  assortmentDescription: 'Ключи для игр',
};

const DOCUMENT = { documentId: 'd-1', fileName: 'passport.pdf', contentType: 'application/pdf', sizeBytes: 2048, uploadedAt: '2026-10-17T09:00:00Z' };

function application(overrides: Record<string, unknown> = {}) {
  return {
    sellerId: 's-1',
    status: 'draft',
    documents: [],
    decisionReason: null,
    rejectedAt: null,
    nextApplicationAt: null,
    submittedAt: null,
    reviewDeadlineAt: null,
    createdAt: '2026-10-17T09:00:00Z',
    updatedAt: '2026-10-17T09:00:00Z',
    ...overrides,
  };
}

/** Сервер заявки с состоянием: `current` отдаётся на GET, остальное подменяется по месту. */
function server(state: { current: ReturnType<typeof application> | undefined; etag?: string }, overrides: Record<string, Handler> = {}): Handler {
  return (url, init) => {
    const route = `${init.method ?? 'GET'} ${url.pathname}`;
    const custom = overrides[route];
    if (custom) {
      return custom(url, init);
    }
    if (route === 'GET /api/v1/seller-applications/current') {
      return state.current ? respond(200, state.current, { ETag: state.etag ?? '"v1"' }) : respond(404, { code: 'not-found' });
    }
    return respond(404, { code: 'not-found' });
  };
}

describe('заявка продавца: анкета', () => {
  it('пустая анкета на проверку не уходит: ошибки у полей, запросов кроме загрузки заявки нет', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(<ApplicationPage />, { handler: server({ current: undefined }) });
    await user.click(await screen.findByRole('button', { name: t('application.submit') }));

    expect(await screen.findByText(t('application.incomplete'))).toBeInTheDocument();
    expect(screen.getAllByText(t('field.required')).length).toBeGreaterThanOrEqual(6);
    expect(screen.getByText(t('application.error.documents'))).toBeInTheDocument();
    expect(calls).toHaveLength(1);
    expect(calls[0]?.init.method ?? 'GET').toBe('GET');
  });

  it('неверный БИК и счёт: ошибка у поля, черновик не сохраняется', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(<ApplicationPage />, { handler: server({ current: undefined }) });
    await user.type(await screen.findByLabelText(t('application.field.bik')), '1234');
    await user.type(screen.getByLabelText(t('application.field.accountNumber')), '55');
    await user.click(screen.getByRole('button', { name: t('application.save') }));

    expect(await screen.findByText(t('application.error.bik'))).toBeInTheDocument();
    expect(screen.getByText(t('application.error.account'))).toBeInTheDocument();
    expect(calls).toHaveLength(1);
  });

  it('первое сохранение создаёт заявку с Idempotency-Key, второе правит её с If-Match', async () => {
    const user = userEvent.setup();
    const state: { current: ReturnType<typeof application> | undefined; etag?: string } = { current: undefined };
    const handler = server(state, {
      'POST /api/v1/seller-applications': () => {
        state.current = application({ name: 'ИП Иванов', sellerType: 'sole_proprietor' });
        return respond(201, state.current, { ETag: '"v1"' });
      },
      'PUT /api/v1/seller-applications/current': () => respond(200, application({ name: 'ИП Петров', sellerType: 'sole_proprietor' }), { ETag: '"v2"' }),
    });
    const { calls } = renderScreen(<ApplicationPage />, { handler });

    await user.selectOptions(await screen.findByLabelText(t('application.field.sellerType')), 'sole_proprietor');
    await user.type(screen.getByLabelText(t('application.field.name')), '  ИП Иванов  ');
    await user.click(screen.getByRole('button', { name: t('application.save') }));
    expect(await screen.findByText(t('application.saved'))).toBeInTheDocument();

    const create = calls[1];
    expect(create?.init.method).toBe('POST');
    expect(create?.url.pathname).toBe('/api/v1/seller-applications');
    expect(create?.headers['Idempotency-Key']).toBe('key-1');
    expect(JSON.parse(String(create?.init.body))).toEqual({ sellerType: 'sole_proprietor', name: 'ИП Иванов' });

    const name = screen.getByLabelText(t('application.field.name'));
    await user.clear(name);
    await user.type(name, 'ИП Петров');
    await user.click(screen.getByRole('button', { name: t('application.save') }));
    await waitFor(() => expect(calls.some((call) => call.init.method === 'PUT')).toBe(true));

    const update = calls.find((call) => call.init.method === 'PUT');
    expect(update?.headers['If-Match']).toBe('"v1"');
    expect(JSON.parse(String(update?.init.body)).name).toBe('ИП Петров');
  });

  it('ошибка поля от сервера показывается под нужным полем', async () => {
    const user = userEvent.setup();
    const handler = server(
      { current: application() },
      {
        'PUT /api/v1/seller-applications/current': () =>
          respond(422, { code: 'validation-failed', errors: [{ pointer: '/payoutDetails/bik', code: 'invalid_format' }] }),
      },
    );
    renderScreen(<ApplicationPage />, { handler });
    await user.type(await screen.findByLabelText(t('application.field.name')), 'ИП');
    await user.click(screen.getByRole('button', { name: t('application.save') }));

    const field = screen.getByLabelText(t('application.field.bik'));
    await waitFor(() => expect(field).toHaveAccessibleDescription(expect.stringContaining(t('field.invalid_format'))));
  });
});

describe('заявка продавца: отправка на проверку', () => {
  const READY = application({ ...FULL_FORM, documents: [DOCUMENT] });

  it('полная заявка уходит на проверку без лишнего сохранения, затем показывается срок', async () => {
    const user = userEvent.setup();
    const state = { current: READY as ReturnType<typeof application> | undefined };
    const deadline = '2026-10-20T09:00:00Z';
    const handler = server(state, {
      'POST /api/v1/seller-applications/current/submit': () => {
        state.current = application({ ...FULL_FORM, documents: [DOCUMENT], status: 'on_review', submittedAt: '2026-10-17T10:00:00Z', reviewDeadlineAt: deadline });
        return respond(200, state.current);
      },
    });
    const { calls } = renderScreen(<ApplicationPage />, { handler });
    await user.click(await screen.findByRole('button', { name: t('application.submit') }));

    expect(await screen.findByText(t('application.submitted'))).toBeInTheDocument();
    expect(await screen.findByText(t('application.status.on_review', { time: formatMoment(deadline) }))).toBeInTheDocument();
    expect(calls.map((call) => `${call.init.method ?? 'GET'} ${call.url.pathname}`)).toEqual([
      'GET /api/v1/seller-applications/current',
      'POST /api/v1/seller-applications/current/submit',
      'GET /api/v1/seller-applications/current',
    ]);
    expect(calls[1]?.headers['Idempotency-Key']).toBe('key-1');
    // На проверке анкета закрыта, кнопок сохранения и отправки нет
    expect(screen.queryByRole('button', { name: t('application.submit') })).not.toBeInTheDocument();
    expect(screen.getByLabelText(t('application.field.name'))).toBeDisabled();
  });

  it('изменённые поля сначала сохраняются с If-Match, потом заявка отправляется', async () => {
    const user = userEvent.setup();
    const handler = server(
      { current: READY, etag: '"v7"' },
      {
        'PUT /api/v1/seller-applications/current': () => respond(200, application({ ...FULL_FORM, name: 'ИП Новый', documents: [DOCUMENT] }), { ETag: '"v8"' }),
        'POST /api/v1/seller-applications/current/submit': () => respond(200, application({ ...FULL_FORM, documents: [DOCUMENT], status: 'on_review' })),
      },
    );
    const { calls } = renderScreen(<ApplicationPage />, { handler });
    const name = await screen.findByLabelText(t('application.field.name'));
    await user.clear(name);
    await user.type(name, 'ИП Новый');
    await user.click(screen.getByRole('button', { name: t('application.submit') }));

    await waitFor(() => expect(calls.some((call) => call.url.pathname.endsWith('/submit'))).toBe(true));
    const methods = calls.map((call) => `${call.init.method ?? 'GET'} ${call.url.pathname}`);
    expect(methods.indexOf('PUT /api/v1/seller-applications/current')).toBeLessThan(methods.indexOf('POST /api/v1/seller-applications/current/submit'));
    expect(calls.find((call) => call.init.method === 'PUT')?.headers['If-Match']).toBe('"v7"');
  });

  it('сбой сети при отправке: повтор уходит с тем же Idempotency-Key', async () => {
    const user = userEvent.setup();
    let attempt = 0;
    const handler = server(
      { current: READY },
      {
        'POST /api/v1/seller-applications/current/submit': () => {
          attempt += 1;
          return attempt === 1 ? { fail: new TypeError('network') } : respond(200, application({ ...FULL_FORM, documents: [DOCUMENT], status: 'on_review' }));
        },
      },
    );
    const { calls } = renderScreen(<ApplicationPage />, { handler });
    await user.click(await screen.findByRole('button', { name: t('application.submit') }));
    expect(await screen.findByText(t('problem.network'))).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: t('application.submit') }));
    expect(await screen.findByText(t('application.submitted'))).toBeInTheDocument();

    const keys = calls.filter((call) => call.url.pathname.endsWith('/submit')).map((call) => call.headers['Idempotency-Key']);
    expect(keys).toEqual(['key-1', 'key-1']);
  });

  it('окончательный отказ сервера: следующая попытка получает новый ключ', async () => {
    const user = userEvent.setup();
    const handler = server(
      { current: READY },
      { 'POST /api/v1/seller-applications/current/submit': () => respond(409, { code: 'state-conflict' }) },
    );
    const { calls } = renderScreen(<ApplicationPage />, { handler });
    await user.click(await screen.findByRole('button', { name: t('application.submit') }));
    expect(await screen.findByText(t('problem.state-conflict'))).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: t('application.submit') }));
    await waitFor(() => expect(calls.filter((call) => call.url.pathname.endsWith('/submit'))).toHaveLength(2));

    const keys = calls.filter((call) => call.url.pathname.endsWith('/submit')).map((call) => call.headers['Idempotency-Key']);
    expect(keys).toEqual(['key-1', 'key-2']);
  });

  it('пауза после отказа: сообщение с моментом, когда можно подать заново', async () => {
    const user = userEvent.setup();
    const retryAfterAt = '2026-10-24T09:00:00Z';
    const handler = server(
      { current: READY },
      { 'POST /api/v1/seller-applications/current/submit': () => respond(409, { code: 'application-pause-active', retryAfterAt }) },
    );
    renderScreen(<ApplicationPage />, { handler });
    await user.click(await screen.findByRole('button', { name: t('application.submit') }));
    expect(await screen.findByText(new RegExp(t('application.pauseUntil', { time: formatMoment(retryAfterAt) })))).toBeInTheDocument();
  });
});

describe('заявка продавца: статусы', () => {
  it('возвращена на доработку: комментарий модератора, поля открыты', async () => {
    renderScreen(<ApplicationPage />, {
      handler: server({ current: application({ ...FULL_FORM, status: 'returned', decisionReason: 'Нужен скан паспорта' }) }),
    });
    expect(await screen.findByText(t('application.status.returned', { reason: 'Нужен скан паспорта' }))).toBeInTheDocument();
    expect(screen.getByLabelText(t('application.field.name'))).toBeEnabled();
    expect(screen.getByRole('button', { name: t('application.submit') })).toBeInTheDocument();
  });

  it('отклонена: причина и дата, с которой можно подать новую заявку', async () => {
    const nextApplicationAt = '2026-10-24T09:00:00Z';
    renderScreen(<ApplicationPage />, {
      handler: server({ current: application({ ...FULL_FORM, status: 'rejected', decisionReason: 'Нет документов', rejectedAt: '2026-10-17T09:00:00Z', nextApplicationAt }) }),
    });
    expect(await screen.findByText(t('application.status.rejected', { reason: 'Нет документов' }))).toBeInTheDocument();
    expect(screen.getByText(t('application.nextAt', { time: formatMoment(nextApplicationAt) }))).toBeInTheDocument();
  });

  it('одобрена: ссылка в кабинет продавца, правки закрыты', async () => {
    renderScreen(<ApplicationPage />, { handler: server({ current: application({ ...FULL_FORM, status: 'approved', documents: [DOCUMENT] }) }) });
    expect(await screen.findByRole('link', { name: t('application.toCabinet') })).toHaveAttribute('href', '/seller');
    expect(screen.getByLabelText(t('application.field.name'))).toBeDisabled();
    expect(screen.queryByRole('button', { name: t('application.documents.delete') })).not.toBeInTheDocument();
  });

  it('ошибка загрузки заявки: сообщение и повтор', async () => {
    const user = userEvent.setup();
    let attempt = 0;
    renderScreen(<ApplicationPage />, {
      handler: () => {
        attempt += 1;
        return attempt === 1 ? respond(500, { code: 'internal-error' }) : respond(200, application(), { ETag: '"v1"' });
      },
    });
    expect(await screen.findByText(t('problem.internal-error'))).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: t('state.retry') }));
    expect(await screen.findByLabelText(t('application.field.name'))).toBeInTheDocument();
  });
});

describe('заявка продавца: документы', () => {
  function pdf(name = 'passport.pdf', size = 1024): File {
    const file = new File(['x'], name, { type: 'application/pdf' });
    Object.defineProperty(file, 'size', { value: size });
    return file;
  }

  it('подходящий файл уходит в multipart с Idempotency-Key, список обновляется', async () => {
    const user = userEvent.setup();
    const state = { current: application({ ...FULL_FORM }) as ReturnType<typeof application> | undefined };
    const handler = server(state, {
      'POST /api/v1/seller-applications/current/documents': () => {
        state.current = application({ ...FULL_FORM, documents: [DOCUMENT] });
        return respond(201, DOCUMENT);
      },
    });
    const { calls } = renderScreen(<ApplicationPage />, { handler });
    await user.upload(await screen.findByLabelText(t('application.documents.upload')), pdf());

    expect(await screen.findByText(t('application.uploaded'))).toBeInTheDocument();
    const list = screen.getByRole('list', { name: t('application.documents.caption') });
    expect(await within(list).findByText('passport.pdf')).toBeInTheDocument();
    const upload = calls.find((call) => call.url.pathname.endsWith('/documents'));
    expect(upload?.init.body).toBeInstanceOf(FormData);
    expect((upload?.init.body as FormData).get('file')).toBeInstanceOf(File);
    expect(upload?.headers['Idempotency-Key']).toBe('key-1');
    expect(upload?.headers['Content-Type']).toBeUndefined();
  });

  it('другой тип файла отвергается до запроса', async () => {
    const user = userEvent.setup({ applyAccept: false });
    const { calls } = renderScreen(<ApplicationPage />, { handler: server({ current: application() }) });
    await user.upload(await screen.findByLabelText(t('application.documents.upload')), new File(['x'], 'photo.gif', { type: 'image/gif' }));
    expect(await screen.findByText(t('application.error.fileType'))).toBeInTheDocument();
    expect(calls).toHaveLength(1);
  });

  it('файл больше 2 МБ отвергается до запроса', async () => {
    const user = userEvent.setup();
    const { calls } = renderScreen(<ApplicationPage />, { handler: server({ current: application() }) });
    await user.upload(await screen.findByLabelText(t('application.documents.upload')), pdf('big.pdf', 2 * 1024 * 1024 + 1));
    expect(await screen.findByText(t('application.error.fileSize'))).toBeInTheDocument();
    expect(calls).toHaveLength(1);
  });

  it('десять документов уже есть: выбор файла закрыт', async () => {
    const documents = Array.from({ length: 10 }, (_, index) => ({ ...DOCUMENT, documentId: `d-${index}`, fileName: `doc-${index}.pdf` }));
    renderScreen(<ApplicationPage />, { handler: server({ current: application({ ...FULL_FORM, documents }) }) });
    expect(await screen.findByLabelText(t('application.documents.upload'))).toBeDisabled();
  });

  it('заявки ещё нет: сначала создаётся черновик из введённого, потом грузится файл', async () => {
    const user = userEvent.setup();
    const state: { current: ReturnType<typeof application> | undefined } = { current: undefined };
    const handler = server(state, {
      'POST /api/v1/seller-applications': () => {
        state.current = application({ name: 'ИП Иванов' });
        return respond(201, state.current, { ETag: '"v1"' });
      },
      'POST /api/v1/seller-applications/current/documents': () => {
        state.current = application({ name: 'ИП Иванов', documents: [DOCUMENT] });
        return respond(201, DOCUMENT);
      },
    });
    const { calls } = renderScreen(<ApplicationPage />, { handler });
    await user.type(await screen.findByLabelText(t('application.field.name')), 'ИП Иванов');
    await user.upload(screen.getByLabelText(t('application.documents.upload')), pdf());

    expect(await screen.findByText(t('application.uploaded'))).toBeInTheDocument();
    const methods = calls.map((call) => `${call.init.method ?? 'GET'} ${call.url.pathname}`);
    expect(methods.indexOf('POST /api/v1/seller-applications')).toBeLessThan(methods.indexOf('POST /api/v1/seller-applications/current/documents'));
  });

  it('несохранённые поля не пропадают после загрузки документа', async () => {
    const user = userEvent.setup();
    const state = { current: application({ name: 'Старое имя' }) as ReturnType<typeof application> | undefined };
    const handler = server(state, {
      'POST /api/v1/seller-applications/current/documents': () => {
        state.current = application({ name: 'Старое имя', documents: [DOCUMENT], updatedAt: '2026-10-17T09:30:00Z' });
        return respond(201, DOCUMENT);
      },
    });
    renderScreen(<ApplicationPage />, { handler });
    const name = await screen.findByLabelText(t('application.field.name'));
    await user.clear(name);
    await user.type(name, 'Новое имя');
    await user.upload(screen.getByLabelText(t('application.documents.upload')), pdf());
    await screen.findByText(t('application.uploaded'));
    expect(screen.getByLabelText(t('application.field.name'))).toHaveValue('Новое имя');
  });

  it('удаление документа и открытие по ссылке', async () => {
    const user = userEvent.setup();
    const state = { current: application({ ...FULL_FORM, documents: [DOCUMENT] }) as ReturnType<typeof application> | undefined };
    const opened: unknown[][] = [];
    const original = window.open;
    window.open = ((...args: unknown[]) => {
      opened.push(args);
      return null;
    }) as typeof window.open;
    try {
      const handler = server(state, {
        'GET /api/v1/seller-applications/current/documents/d-1/link': () => respond(200, { url: 'https://storage.example/d-1?sig=1', expiresAt: '2026-10-17T09:05:00Z' }),
        'DELETE /api/v1/seller-applications/current/documents/d-1': () => {
          state.current = application({ ...FULL_FORM, documents: [] });
          return new Response(null, { status: 204 });
        },
      });
      renderScreen(<ApplicationPage />, { handler });
      await user.click(await screen.findByRole('button', { name: t('application.documents.open') }));
      await waitFor(() => expect(opened).toHaveLength(1));
      expect(opened[0]).toEqual(['https://storage.example/d-1?sig=1', '_blank', 'noopener,noreferrer']);

      await user.click(screen.getByRole('button', { name: t('application.documents.delete') }));
      expect(await screen.findByText(t('application.deleted'))).toBeInTheDocument();
      expect(await screen.findByText(t('application.documents.none'))).toBeInTheDocument();
    } finally {
      window.open = original;
    }
  });
});
