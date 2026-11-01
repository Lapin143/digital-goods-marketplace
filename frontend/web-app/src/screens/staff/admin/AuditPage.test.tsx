import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { RequireAccess } from '../../../app/Guards';
import { ADMIN_ROLES } from '../../../auth/claims';
import { t } from '../../../i18n';
import { OTHER_ADMIN_ID, adminManager, auditRecord } from '../../../test/admin';
import { renderScreen, respond } from '../../../test/harness';
import type { Handler, Rendered } from '../../../test/harness';
import { AuditPage, roleLabel } from './AuditPage';
import { formatPrecise } from './audit';

const AUDIT = '/api/v1/staff/audit-records';
const BUYER_ID = '11111111-2222-4333-8444-555555555555';
const HASH = '9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08';
const ACTOR = '0199E0A0-0000-7000-8000-0000000000A1';

function pageOf(items: Array<Record<string, unknown>>, nextCursor: string | null = null) {
  return { items, page: { limit: 20, nextCursor } };
}

const PARAMETER_CHANGED = auditRecord();
const PHONE_CONFIRMED = auditRecord({
  recordId: '0199e0a0-0000-7000-8000-000000000b02',
  occurredAt: '2026-10-17T11:59:30.000Z',
  actorId: BUYER_ID,
  actorRole: 'buyer',
  action: 'user.phone_confirmed',
  objectType: 'user',
  objectId: BUYER_ID,
  changes: [
    { field: 'phone', personal: true, before: null, after: HASH },
    { field: 'phone_confirmed', personal: false, before: 'false', after: 'true' },
  ],
  actorIp: HASH,
  actorIpHashed: true,
});

function renderAudit(handler: Handler) {
  return renderScreen(
    <RequireAccess kind="staff" roles={ADMIN_ROLES} secondFactor>
      <AuditPage />
    </RequireAccess>,
    { route: '/staff/admin/audit', handler, managers: { staff: adminManager() }, tokens: { staff: 'staff-token' } },
  );
}

function reads(calls: Rendered['calls']) {
  return calls.filter((call) => call.url.pathname === AUDIT);
}

const tableOf = () => screen.findByRole('region', { name: t('audit.caption') });

describe('журнал аудита: записи (TC-810)', () => {
  it('показывает время с секундами, исполнителя, роль, действие, объект и изменения; запрос с токеном сотрудника и без условий', async () => {
    const { calls } = renderAudit(() => respond(200, pageOf([PARAMETER_CHANGED, PHONE_CONFIRMED])));
    expect(await screen.findByRole('heading', { level: 1, name: t('audit.title') })).toBeInTheDocument();
    const table = within(await tableOf());

    expect(table.getByText(formatPrecise('2026-10-17T12:00:00.123Z'))).toBeInTheDocument();
    expect(table.getByText('c3d4e5f6')).toHaveAttribute('title', OTHER_ADMIN_ID);
    expect(table.getByText(t('audit.role.admin'))).toBeInTheDocument();
    expect(table.getByText('parameter.changed')).toBeInTheDocument();
    expect(table.getByText('reservation.ttl-seconds')).toBeInTheDocument();
    expect(table.getByText('value: 900 → 1200')).toBeInTheDocument();

    expect(table.getByText(t('audit.role.buyer'))).toBeInTheDocument();
    expect(table.getByText('user.phone_confirmed')).toBeInTheDocument();
    expect(table.getByText('phone_confirmed: false → true')).toBeInTheDocument();

    expect(calls).toHaveLength(1);
    expect(calls[0]?.url.pathname).toBe(AUDIT);
    expect(calls[0]?.url.search).toBe('?limit=20');
    expect(calls[0]?.headers.Authorization).toBe('Bearer staff-token');
    expect(screen.getByText(t('audit.lead'))).toBeInTheDocument();
  });

  it('персональное значение показано хешем сокращённо с пометкой, полного хеша на экране нет', async () => {
    renderAudit(() => respond(200, pageOf([PHONE_CONFIRMED])));
    const table = within(await tableOf());
    expect(table.getByText(`phone: нет → 9f86d081… ${t('audit.change.personal')}`)).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent(HASH);
  });

  it('записи без изменений показывают «нет», незнакомая роль выводится как есть', async () => {
    renderAudit(() => respond(200, pageOf([auditRecord({ actorRole: 'robot', action: 'audit.viewed', changes: [] })])));
    const table = within(await tableOf());
    expect(table.getByText(t('audit.change.none'))).toBeInTheDocument();
    expect(table.getByText('robot')).toBeInTheDocument();
  });

  it('роли словами: все из перечисления, незнакомая как есть', () => {
    expect(roleLabel('admin')).toBe(t('audit.role.admin'));
    expect(roleLabel('system')).toBe(t('audit.role.system'));
    expect(roleLabel('support-operator')).toBe(t('audit.role.support-operator'));
    expect(roleLabel('moderator')).toBe(t('audit.role.moderator'));
    expect(roleLabel('new-role')).toBe('new-role');
  });

  it('записей нет: сообщение, таблицы нет', async () => {
    renderAudit(() => respond(200, pageOf([])));
    expect(await screen.findByText(t('audit.empty'))).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: t('audit.caption') })).not.toBeInTheDocument();
  });

  it('журнал только читается (TC-815): в таблице нет действий, на сервер уходят только запросы чтения', async () => {
    const { calls } = renderAudit(() => respond(200, pageOf([PARAMETER_CHANGED, PHONE_CONFIRMED])));
    const table = within(await tableOf());
    expect(table.queryAllByRole('button')).toHaveLength(0);
    expect(table.queryAllByRole('textbox')).toHaveLength(0);
    expect(table.queryAllByRole('link')).toHaveLength(0);
    expect(calls.every((call) => call.init.method === 'GET')).toBe(true);
  });
});

describe('журнал аудита: сбой записи просмотра (TC-810)', () => {
  it('503: честное сообщение, записей нет, повтор делает администратор сам', async () => {
    const user = userEvent.setup();
    let unavailable = true;
    const { calls } = renderAudit(() =>
      unavailable ? respond(503, { code: 'dependency-unavailable' }, { 'Retry-After': '5' }) : respond(200, pageOf([PARAMETER_CHANGED])),
    );
    expect(await screen.findByText(t('audit.unavailable'))).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: t('audit.caption') })).not.toBeInTheDocument();
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 50));
    });
    expect(reads(calls)).toHaveLength(1);

    unavailable = false;
    await user.click(screen.getByRole('button', { name: t('state.retry') }));
    expect(await tableOf()).toBeInTheDocument();
    expect(reads(calls)).toHaveLength(2);
    expect(screen.queryByText(t('audit.unavailable'))).not.toBeInTheDocument();
  });

  it('другая ошибка читается своим текстом, не сообщением о недоступности', async () => {
    renderAudit(() => respond(403, { code: 'forbidden' }));
    expect(await screen.findByText(t('problem.forbidden'))).toBeInTheDocument();
    expect(screen.queryByText(t('audit.unavailable'))).not.toBeInTheDocument();
  });

  it('смена окна, возврат связи и время не перечитывают журнал: каждое чтение это новая запись в нём', async () => {
    const { calls } = renderAudit(() => respond(200, pageOf([PARAMETER_CHANGED])));
    await tableOf();
    window.dispatchEvent(new Event('focus'));
    document.dispatchEvent(new Event('visibilitychange', { bubbles: true }));
    window.dispatchEvent(new Event('online'));
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 50));
    });
    expect(reads(calls)).toHaveLength(1);
  });
});

describe('журнал аудита: условия выборки (TC-810)', () => {
  it('условия уходят в запрос: идентификатор в нижнем регистре, период в RFC 3339', async () => {
    const user = userEvent.setup();
    const { calls } = renderAudit(() => respond(200, pageOf([PARAMETER_CHANGED])));
    await tableOf();

    await user.type(screen.getByLabelText(t('audit.filter.actorId')), ACTOR);
    await user.type(screen.getByLabelText(t('audit.filter.objectType')), 'parameter');
    await user.type(screen.getByLabelText(t('audit.filter.objectId')), 'reservation.ttl-seconds');
    await user.type(screen.getByLabelText(t('audit.filter.action')), 'parameter.changed');
    fireEvent.change(screen.getByLabelText(t('audit.filter.from')), { target: { value: '2026-10-01T10:00' } });
    fireEvent.change(screen.getByLabelText(t('audit.filter.to')), { target: { value: '2026-10-02T10:00' } });
    await user.click(screen.getByRole('button', { name: t('audit.filter.apply') }));

    await tableOf();
    expect(reads(calls)).toHaveLength(2);
    const params = reads(calls)[1]?.url.searchParams;
    expect(params?.get('actorId')).toBe(ACTOR.toLowerCase());
    expect(params?.get('objectType')).toBe('parameter');
    expect(params?.get('objectId')).toBe('reservation.ttl-seconds');
    expect(params?.get('action')).toBe('parameter.changed');
    expect(params?.get('occurredFrom')).toBe(new Date('2026-10-01T10:00').toISOString());
    expect(params?.get('occurredTo')).toBe(new Date('2026-10-02T10:00').toISOString());
    expect(params?.get('limit')).toBe('20');
    expect(params?.has('cursor')).toBe(false);
  });

  it('исполнитель не UUID: ошибка у поля, запроса нет', async () => {
    const user = userEvent.setup();
    const { calls } = renderAudit(() => respond(200, pageOf([PARAMETER_CHANGED])));
    await tableOf();
    await user.type(screen.getByLabelText(t('audit.filter.actorId')), 'admin');
    await user.click(screen.getByRole('button', { name: t('audit.filter.apply') }));
    expect(await screen.findByText(t('audit.filter.actorId.error'))).toBeInTheDocument();
    expect(reads(calls)).toHaveLength(1);
  });

  it('конец периода не позже начала: ошибка у конца, запроса нет', async () => {
    const user = userEvent.setup();
    const { calls } = renderAudit(() => respond(200, pageOf([PARAMETER_CHANGED])));
    await tableOf();
    fireEvent.change(screen.getByLabelText(t('audit.filter.from')), { target: { value: '2026-10-02T10:00' } });
    fireEvent.change(screen.getByLabelText(t('audit.filter.to')), { target: { value: '2026-10-01T10:00' } });
    await user.click(screen.getByRole('button', { name: t('audit.filter.apply') }));
    expect(await screen.findByText(t('audit.filter.period.error'))).toBeInTheDocument();
    expect(reads(calls)).toHaveLength(1);
  });

  it('те же условия повторно: журнал читается заново (новая запись о просмотре), а не берётся из кэша', async () => {
    const user = userEvent.setup();
    const { calls } = renderAudit(() => respond(200, pageOf([PARAMETER_CHANGED])));
    await tableOf();
    await user.click(screen.getByRole('button', { name: t('audit.filter.apply') }));
    await waitFor(() => expect(reads(calls)).toHaveLength(2));
    await tableOf();
  });

  it('«Сбросить» очищает поля и читает журнал без условий', async () => {
    const user = userEvent.setup();
    const { calls } = renderAudit(() => respond(200, pageOf([PARAMETER_CHANGED])));
    await tableOf();
    const objectType = screen.getByLabelText(t('audit.filter.objectType'));
    await user.type(objectType, 'parameter');
    await user.click(screen.getByRole('button', { name: t('audit.filter.apply') }));
    await tableOf();
    expect(reads(calls)[1]?.url.searchParams.get('objectType')).toBe('parameter');

    await user.click(screen.getByRole('button', { name: t('audit.filter.reset') }));
    await tableOf();
    expect(objectType).toHaveValue('');
    expect(reads(calls)).toHaveLength(3);
    expect(reads(calls)[2]?.url.search).toBe('?limit=20');
  });
});

describe('журнал аудита: страницы по курсору (TC-810)', () => {
  const FIRST = auditRecord({ recordId: '0199e0a0-0000-7000-8000-000000000c01', actorId: 'aaaaaaaa-0000-4000-8000-000000000001' });
  const SECOND = auditRecord({ recordId: '0199e0a0-0000-7000-8000-000000000c02', actorId: 'bbbbbbbb-0000-4000-8000-000000000002', action: 'seller.approved' });
  const THIRD = auditRecord({ recordId: '0199e0a0-0000-7000-8000-000000000c03', actorId: 'cccccccc-0000-4000-8000-000000000003', action: 'seller.rejected' });

  it('«Показать ещё» запрашивает следующую страницу по курсору и добавляет записи ниже, последняя страница убирает кнопку', async () => {
    const user = userEvent.setup();
    const { calls } = renderAudit((url) =>
      url.searchParams.get('cursor') === 'c1' ? respond(200, pageOf([THIRD])) : respond(200, pageOf([FIRST, SECOND], 'c1')),
    );
    const table = within(await tableOf());
    expect(table.getAllByRole('row')).toHaveLength(3);

    await user.click(screen.getByRole('button', { name: t('catalog.more') }));
    expect(await table.findByText('seller.rejected')).toBeInTheDocument();
    expect(table.getAllByRole('row')).toHaveLength(4);
    expect(table.getAllByRole('row').map((row) => row.textContent).join('|')).toMatch(/aaaaaaaa.*bbbbbbbb.*cccccccc/);
    expect(reads(calls)).toHaveLength(2);
    expect(reads(calls)[1]?.url.searchParams.get('cursor')).toBe('c1');
    expect(reads(calls)[1]?.url.searchParams.get('limit')).toBe('20');
    expect(screen.queryByRole('button', { name: t('catalog.more') })).not.toBeInTheDocument();
  });

  it('сбой следующей страницы: загруженные записи остаются, ошибка с повтором того же курсора', async () => {
    const user = userEvent.setup();
    let failing = true;
    const { calls } = renderAudit((url) => {
      if (url.searchParams.get('cursor') === 'c1') {
        return failing ? respond(503, { code: 'dependency-unavailable' }) : respond(200, pageOf([THIRD]));
      }
      return respond(200, pageOf([FIRST, SECOND], 'c1'));
    });
    const table = within(await tableOf());
    await user.click(screen.getByRole('button', { name: t('catalog.more') }));
    expect(await screen.findByRole('alert')).toHaveTextContent(t('problem.dependency-unavailable'));
    expect(table.getAllByRole('row')).toHaveLength(3);
    expect(screen.queryByText(t('audit.unavailable'))).not.toBeInTheDocument();

    failing = false;
    await user.click(screen.getByRole('button', { name: t('state.retry') }));
    expect(await table.findByText('seller.rejected')).toBeInTheDocument();
    expect(reads(calls).map((call) => call.url.searchParams.get('cursor'))).toEqual([null, 'c1', 'c1']);
  });
});
