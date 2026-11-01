import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { RequireAccess } from '../../../app/Guards';
import { ADMIN_ROLES } from '../../../auth/claims';
import { t } from '../../../i18n';
import { ADMIN_ID, COMMISSION, MAX_UNPAID, OTHER_ADMIN_ID, RESERVE, SESSION, SUPPORT_WINDOW, adminManager, parameter } from '../../../test/admin';
import { renderScreen, respond } from '../../../test/harness';
import type { Handler, Rendered } from '../../../test/harness';
import { formatMoment } from '../../../ui/format';
import { ParametersPage, updaterLabel } from './ParametersPage';

const LIST = '/api/v1/staff/parameters';
const RESERVE_PATH = `${LIST}/reservation.ttl-seconds`;
const SESSION_PATH = `${LIST}/payment-session.ttl-seconds`;
const COMMISSION_PATH = `${LIST}/commission.default-rate-bp`;
const WINDOW_PATH = `${LIST}/support.window-seconds`;

interface State {
  items: Array<Record<string, unknown>>;
}

function server(state: State, overrides: Record<string, Handler> = {}): Handler {
  return (url, init) => {
    const route = `${init.method ?? 'GET'} ${url.pathname}`;
    const custom = overrides[route];
    if (custom) {
      return custom(url, init);
    }
    return route === `GET ${LIST}` ? respond(200, { items: state.items }) : respond(404, { code: 'not-found' });
  };
}

function renderPage(state: State, overrides: Record<string, Handler> = {}) {
  return renderScreen(
    <RequireAccess kind="staff" roles={ADMIN_ROLES} secondFactor>
      <ParametersPage />
    </RequireAccess>,
    { route: '/staff/admin/parameters', handler: server(state, overrides), managers: { staff: adminManager() }, tokens: { staff: 'staff-token' } },
  );
}

const allParameters = (): State => ({ items: [COMMISSION, RESERVE, SESSION, MAX_UNPAID, SUPPORT_WINDOW] });

function rowOf(key: string): HTMLElement {
  const row = screen.getByText(key).closest('tr');
  if (!row) {
    throw new Error(`Строки параметра ${key} нет`);
  }
  return row;
}

function puts(calls: Rendered['calls']) {
  return calls.filter((call) => call.init.method === 'PUT');
}

function gets(calls: Rendered['calls']) {
  return calls.filter((call) => call.init.method === 'GET');
}

const EDIT_SECONDS = t('admin.params.edit.label', { unit: t('admin.unitName.seconds') });

/** Открывает правку параметра и возвращает поле ввода. */
async function openEdit(user: ReturnType<typeof userEvent.setup>, key: string, label = EDIT_SECONDS) {
  await user.click(await screen.findByRole('button', { name: t('admin.params.edit.aria', { name: key }) }));
  return screen.findByRole('textbox', { name: label });
}

async function enter(user: ReturnType<typeof userEvent.setup>, field: HTMLElement, value: string) {
  await user.clear(field);
  if (value !== '') {
    await user.type(field, value);
  }
}

const saveButton = () => screen.getByRole('button', { name: t('admin.params.save') });

describe('параметры платформы: таблица (TC-806)', () => {
  it('показывает значения в читаемой форме и в единицах параметра, умолчание, границы, версию и кто менял', async () => {
    const { calls } = renderPage(allParameters());
    expect(await screen.findByRole('heading', { level: 1, name: t('admin.params.title') })).toBeInTheDocument();
    expect(await screen.findByRole('region', { name: t('admin.params.caption') })).toBeInTheDocument();

    const reserve = within(rowOf('reservation.ttl-seconds'));
    expect(reserve.getByText('Время резерва ключей')).toBeInTheDocument();
    expect(reserve.getAllByText('15 мин (900 с)')).toHaveLength(2);
    expect(reserve.getByText('5 мин – 1 ч')).toBeInTheDocument();
    expect(reserve.getByText('3')).toBeInTheDocument();
    expect(reserve.getByText(formatMoment('2026-10-17T09:00:00.000Z'))).toBeInTheDocument();
    expect(reserve.getByText(t('admin.params.updatedBy.initial'))).toBeInTheDocument();

    const commission = within(rowOf('commission.default-rate-bp'));
    expect(commission.getAllByText('2 % (200 б. п.)')).toHaveLength(2);
    expect(commission.getByText('0,01 % – 30 %')).toBeInTheDocument();

    const unpaid = within(rowOf('orders.max-unpaid'));
    expect(unpaid.getByText('1 – 20')).toBeInTheDocument();
    expect(unpaid.getByText(t('admin.params.updatedBy.admin', { id: 'c3d4e5f6' }))).toBeInTheDocument();

    const supportRow = within(rowOf('support.window-seconds'));
    expect(supportRow.getByText('2 сут (172800 с)')).toBeInTheDocument();
    expect(supportRow.getByText('3 сут (259200 с)')).toBeInTheDocument();
    expect(supportRow.getByText(t('admin.params.updatedBy.me'))).toBeInTheDocument();

    expect(calls[0]?.url.pathname).toBe(LIST);
    expect(calls[0]?.headers.Authorization).toBe('Bearer staff-token');
  });

  it('подпись «кто менял»: начальное значение, вы, другой администратор сокращённо', () => {
    expect(updaterLabel(null, ADMIN_ID)).toBe(t('admin.params.updatedBy.initial'));
    expect(updaterLabel(ADMIN_ID, ADMIN_ID)).toBe(t('admin.params.updatedBy.me'));
    expect(updaterLabel(OTHER_ADMIN_ID, ADMIN_ID)).toBe(t('admin.params.updatedBy.admin', { id: 'c3d4e5f6' }));
  });

  it('параметров нет: сообщение, таблицы нет', async () => {
    renderPage({ items: [] });
    expect(await screen.findByText(t('admin.params.empty'))).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: t('admin.params.caption') })).not.toBeInTheDocument();
  });

  it('сбой загрузки: сообщение и повтор', async () => {
    const user = userEvent.setup();
    let failing = true;
    const state = allParameters();
    renderPage(state, { [`GET ${LIST}`]: () => (failing ? respond(500, { code: 'internal-error' }) : respond(200, { items: state.items })) });
    expect(await screen.findByText(t('problem.internal-error'))).toBeInTheDocument();
    failing = false;
    await user.click(screen.getByRole('button', { name: t('state.retry') }));
    expect(await screen.findByRole('region', { name: t('admin.params.caption') })).toBeInTheDocument();
  });
});

describe('правка параметра (TC-806)', () => {
  it('сохраняет значение в единицах параметра с If-Match версии, строка обновляется без перезагрузки списка', async () => {
    const user = userEvent.setup();
    const { calls } = renderPage(allParameters(), {
      [`PUT ${RESERVE_PATH}`]: () => respond(200, parameter({ value: 1200, version: 4, updatedBy: ADMIN_ID, updatedAt: '2026-11-01T10:00:00.000Z' })),
    });
    const field = await openEdit(user, 'reservation.ttl-seconds');
    expect(field).toHaveValue('900');
    expect(field).toHaveFocus();
    await enter(user, field, '1200');
    await user.click(saveButton());

    expect(await screen.findByText(t('admin.params.saved', { name: 'reservation.ttl-seconds', value: '20 мин (1200 с)', version: 4 }))).toBeInTheDocument();
    const [put] = puts(calls);
    expect(put?.url.pathname).toBe(RESERVE_PATH);
    expect(put?.headers['If-Match']).toBe('"3"');
    expect(put?.headers.Authorization).toBe('Bearer staff-token');
    expect(JSON.parse(String(put?.init.body))).toEqual({ value: 1200 });

    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    const reserve = within(rowOf('reservation.ttl-seconds'));
    expect(reserve.getByText('20 мин (1200 с)')).toBeInTheDocument();
    expect(reserve.getByText('4')).toBeInTheDocument();
    expect(reserve.getByText(t('admin.params.updatedBy.me'))).toBeInTheDocument();
    expect(gets(calls)).toHaveLength(1);
  });

  it('под полем границы и читаемая форма набранного, у счётчика только границы', async () => {
    const user = userEvent.setup();
    renderPage(allParameters());
    const field = await openEdit(user, 'reservation.ttl-seconds');
    expect(field).toHaveAccessibleDescription('От 300 до 3600 (5 мин – 1 ч). Это 15 мин.');
    await enter(user, field, '1200');
    expect(field).toHaveAccessibleDescription('От 300 до 3600 (5 мин – 1 ч). Это 20 мин.');
    await user.click(screen.getByRole('button', { name: t('admin.params.cancel') }));

    const count = await openEdit(user, 'orders.max-unpaid', t('admin.params.edit.label', { unit: t('admin.unitName.count') }));
    expect(count).toHaveAccessibleDescription('От 1 до 20.');
  });

  it('отмена закрывает правку без запроса, повторное открытие начинает с текущего значения', async () => {
    const user = userEvent.setup();
    const { calls } = renderPage(allParameters());
    const field = await openEdit(user, 'reservation.ttl-seconds');
    await enter(user, field, '1500');
    await user.click(screen.getByRole('button', { name: t('admin.params.cancel') }));
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(await openEdit(user, 'reservation.ttl-seconds')).toHaveValue('900');
    expect(puts(calls)).toHaveLength(0);
  });

  it('повторное нажатие «Сохранить» во время запроса не создаёт второго запроса', async () => {
    const user = userEvent.setup();
    let release!: () => void;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const { calls } = renderPage(allParameters(), {
      [`PUT ${RESERVE_PATH}`]: async () => {
        await gate;
        return respond(200, parameter({ value: 1200, version: 4 }));
      },
    });
    const field = await openEdit(user, 'reservation.ttl-seconds');
    await enter(user, field, '1200');
    await user.click(saveButton());
    expect(saveButton()).toBeDisabled();
    expect(field).toBeDisabled();
    await user.click(saveButton());
    expect(puts(calls)).toHaveLength(1);
    release();
    expect(await screen.findByText(t('admin.params.saved', { name: 'reservation.ttl-seconds', value: '20 мин (1200 с)', version: 4 }))).toBeInTheDocument();
    expect(puts(calls)).toHaveLength(1);
  });

  it('сервис недоступен или нет связи: честное сообщение, правка остаётся открытой со своим значением', async () => {
    const user = userEvent.setup();
    let attempt = 0;
    renderPage(allParameters(), {
      [`PUT ${RESERVE_PATH}`]: () => {
        attempt += 1;
        return attempt === 1 ? respond(503, { code: 'dependency-unavailable' }, { 'Retry-After': '5' }) : { fail: new TypeError('offline') };
      },
    });
    const field = await openEdit(user, 'reservation.ttl-seconds');
    await enter(user, field, '1200');
    await user.click(saveButton());
    expect(await screen.findByRole('alert')).toHaveTextContent(t('problem.dependency-unavailable'));
    expect(field).toHaveValue('1200');
    expect(saveButton()).toBeEnabled();
    await user.click(saveButton());
    expect(await screen.findByText(t('problem.network'))).toBeInTheDocument();
    expect(field).toHaveValue('1200');
  });
});

describe('параллельная правка: чужая версия (TC-806, TC-807)', () => {
  it('412: сообщение, список перечитан с чужим значением, повтор с новой версией проходит', async () => {
    const user = userEvent.setup();
    const state = allParameters();
    let attempt = 0;
    const { calls } = renderPage(state, {
      [`PUT ${RESERVE_PATH}`]: () => {
        attempt += 1;
        if (attempt === 1) {
          // другой администратор успел раньше: значение 1500, версия 4
          state.items = [COMMISSION, parameter({ value: 1500, version: 4, updatedBy: OTHER_ADMIN_ID }), SESSION, MAX_UNPAID, SUPPORT_WINDOW];
          return respond(412, { code: 'version-conflict' });
        }
        return respond(200, parameter({ value: 1200, version: 5, updatedBy: ADMIN_ID }));
      },
    });
    const field = await openEdit(user, 'reservation.ttl-seconds');
    await enter(user, field, '1200');
    await user.click(saveButton());

    expect(await screen.findByText(t('admin.params.conflict', { name: 'reservation.ttl-seconds' }))).toBeInTheDocument();
    expect(await within(rowOf('reservation.ttl-seconds')).findByText('25 мин (1500 с)')).toBeInTheDocument();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(gets(calls)).toHaveLength(2);
    expect(puts(calls)[0]?.headers['If-Match']).toBe('"3"');

    const again = await openEdit(user, 'reservation.ttl-seconds');
    expect(again).toHaveValue('1500');
    await enter(user, again, '1200');
    await user.click(saveButton());
    expect(await screen.findByText(t('admin.params.saved', { name: 'reservation.ttl-seconds', value: '20 мин (1200 с)', version: 5 }))).toBeInTheDocument();
    expect(puts(calls)[1]?.headers['If-Match']).toBe('"4"');
  });
});

describe('значение вне границ и INV-07 (TC-807)', () => {
  it.each([
    ['299', `${t('admin.params.error.range')} От 300 до 3600 (5 мин – 1 ч)`],
    ['3601', `${t('admin.params.error.range')} От 300 до 3600 (5 мин – 1 ч)`],
    ['15 минут', t('admin.params.error.notNumber')],
    ['1.5', t('admin.params.error.notNumber')],
    ['-900', t('admin.params.error.notNumber')],
    ['', t('admin.params.error.notNumber')],
    ['900', t('admin.params.error.same')],
  ])('значение «%s» отклоняется до отправки: %s', async (input, message) => {
    const user = userEvent.setup();
    const { calls } = renderPage(allParameters());
    const field = await openEdit(user, 'reservation.ttl-seconds');
    await enter(user, field, input);
    await user.click(saveButton());
    expect(screen.getByRole('alert')).toHaveTextContent(message);
    expect(field).toHaveAttribute('aria-invalid', 'true');
    expect(puts(calls)).toHaveLength(0);
  });

  it('границы на границе принимаются', async () => {
    const user = userEvent.setup();
    const { calls } = renderPage(allParameters(), { [`PUT ${RESERVE_PATH}`]: () => respond(200, parameter({ value: 300, version: 4 })) });
    const field = await openEdit(user, 'reservation.ttl-seconds');
    await enter(user, field, '300');
    await user.click(saveButton());
    expect(await screen.findByText(t('admin.params.saved', { name: 'reservation.ttl-seconds', value: '5 мин (300 с)', version: 4 }))).toBeInTheDocument();
    expect(JSON.parse(String(puts(calls)[0]?.init.body))).toEqual({ value: 300 });
  });

  it('422 сервера по границам: текст ошибки поля и границы параметра, правка остаётся открытой', async () => {
    const user = userEvent.setup();
    renderPage(allParameters(), {
      [`PUT ${COMMISSION_PATH}`]: () => respond(422, { code: 'validation-failed', errors: [{ pointer: '/value', code: 'out_of_range', detail: 'вне границ' }] }),
    });
    const field = await openEdit(user, 'commission.default-rate-bp', t('admin.params.edit.label', { unit: t('admin.unitName.basis_points') }));
    await enter(user, field, '250');
    expect(field).toHaveAccessibleDescription('От 1 до 3000 (0,01 % – 30 %). Это 2,5 %.');
    await user.click(saveButton());
    expect(await screen.findByRole('alert')).toHaveTextContent(`${t('field.out_of_range')} От 1 до 3000 (0,01 % – 30 %)`);
    expect(field).toHaveValue('250');
  });

  it('INV-07, срок сессии: сервер отклонил, экран перечитал список и назвал текущий срок резерва', async () => {
    const user = userEvent.setup();
    const state: State = { items: [RESERVE, SESSION] };
    const { calls } = renderPage(state, {
      [`PUT ${SESSION_PATH}`]: () => {
        // резерв успели сократить до 600 секунд: 800 уже не короче резерва
        state.items = [parameter({ value: 600, version: 4, updatedBy: OTHER_ADMIN_ID }), SESSION];
        return respond(422, { code: 'validation-failed', errors: [{ pointer: '/value', code: 'out_of_range', detail: 'INV-07' }] });
      },
    });
    const field = await openEdit(user, 'payment-session.ttl-seconds');
    await enter(user, field, '800');
    await user.click(saveButton());
    expect(await screen.findByText(t('admin.params.error.session', { partner: '10 мин (600 с)', limit: 600 }))).toBeInTheDocument();
    expect(gets(calls)).toHaveLength(2);
    expect(field).toHaveValue('800');
    expect(field).toHaveAttribute('aria-invalid', 'true');
  });

  it('INV-07, срок резерва: сообщение называет текущий срок платёжной сессии', async () => {
    const user = userEvent.setup();
    renderPage({ items: [RESERVE, SESSION] }, {
      [`PUT ${RESERVE_PATH}`]: () => respond(422, { code: 'validation-failed', errors: [{ pointer: '/value', code: 'out_of_range', detail: 'INV-07' }] }),
    });
    const field = await openEdit(user, 'reservation.ttl-seconds');
    await enter(user, field, '700');
    await user.click(saveButton());
    expect(await screen.findByText(t('admin.params.error.reserve', { partner: '12 мин (720 с)', limit: 720 }))).toBeInTheDocument();
  });

  it('после ошибки можно исправить значение и сохранить', async () => {
    const user = userEvent.setup();
    let attempt = 0;
    const { calls } = renderPage({ items: [RESERVE, SESSION] }, {
      [`PUT ${SESSION_PATH}`]: () => {
        attempt += 1;
        return attempt === 1
          ? respond(422, { code: 'validation-failed', errors: [{ pointer: '/value', code: 'out_of_range', detail: 'INV-07' }] })
          : respond(200, { ...SESSION, value: 600, version: 4 });
      },
    });
    const field = await openEdit(user, 'payment-session.ttl-seconds');
    await enter(user, field, '900');
    await user.click(saveButton());
    expect(await screen.findByText(t('admin.params.error.session', { partner: '15 мин (900 с)', limit: 900 }))).toBeInTheDocument();
    await enter(user, field, '600');
    await user.click(saveButton());
    expect(await screen.findByText(t('admin.params.saved', { name: 'payment-session.ttl-seconds', value: '10 мин (600 с)', version: 4 }))).toBeInTheDocument();
    expect(puts(calls)).toHaveLength(2);
    expect(JSON.parse(String(puts(calls)[1]?.init.body))).toEqual({ value: 600 });
  });
});

describe('возврат к умолчанию (TC-816)', () => {
  it('подтверждение называет умолчание, сохранение уходит тем же путём с версией и значением по умолчанию', async () => {
    const user = userEvent.setup();
    const { calls } = renderPage(allParameters(), {
      [`PUT ${WINDOW_PATH}`]: () => respond(200, { ...SUPPORT_WINDOW, value: 259200, version: 6, updatedBy: ADMIN_ID }),
    });
    await user.click(await screen.findByRole('button', { name: t('admin.params.reset.button', { name: 'support.window-seconds' }) }));
    const group = await screen.findByRole('group', { name: t('admin.params.reset.aria', { name: 'support.window-seconds' }) });
    expect(within(group).getByText(t('admin.params.reset.confirm', { value: '3 сут (259200 с)' }))).toBeInTheDocument();
    expect(puts(calls)).toHaveLength(0);

    await user.click(within(group).getByRole('button', { name: t('admin.params.reset.confirmButton') }));
    expect(await screen.findByText(t('admin.params.saved', { name: 'support.window-seconds', value: '3 сут (259200 с)', version: 6 }))).toBeInTheDocument();
    const [put] = puts(calls);
    expect(put?.url.pathname).toBe(WINDOW_PATH);
    expect(put?.headers['If-Match']).toBe('"5"');
    expect(JSON.parse(String(put?.init.body))).toEqual({ value: 259200 });
    expect(screen.queryByRole('group')).not.toBeInTheDocument();
  });

  it('отмена подтверждения ничего не отправляет', async () => {
    const user = userEvent.setup();
    const { calls } = renderPage(allParameters());
    await user.click(await screen.findByRole('button', { name: t('admin.params.reset.button', { name: 'support.window-seconds' }) }));
    await user.click(await screen.findByRole('button', { name: t('admin.params.cancel') }));
    expect(screen.queryByRole('group')).not.toBeInTheDocument();
    expect(puts(calls)).toHaveLength(0);
  });

  it('значение уже равно умолчанию: возвращать нечего, кнопка недоступна', async () => {
    renderPage(allParameters());
    expect(await screen.findByRole('button', { name: t('admin.params.reset.button', { name: 'reservation.ttl-seconds' }) })).toBeDisabled();
    expect(screen.getByRole('button', { name: t('admin.params.reset.button', { name: 'support.window-seconds' }) })).toBeEnabled();
  });

  it('412 при возврате: сообщение о чужой правке и свежий список', async () => {
    const user = userEvent.setup();
    const state = allParameters();
    const { calls } = renderPage(state, {
      [`PUT ${WINDOW_PATH}`]: () => {
        state.items = [COMMISSION, RESERVE, SESSION, MAX_UNPAID, { ...SUPPORT_WINDOW, value: 200000, version: 6, updatedBy: OTHER_ADMIN_ID }];
        return respond(412, { code: 'version-conflict' });
      },
    });
    await user.click(await screen.findByRole('button', { name: t('admin.params.reset.button', { name: 'support.window-seconds' }) }));
    await user.click(await screen.findByRole('button', { name: t('admin.params.reset.confirmButton') }));
    expect(await screen.findByText(t('admin.params.conflict', { name: 'support.window-seconds' }))).toBeInTheDocument();
    expect(await within(rowOf('support.window-seconds')).findByText('2 сут 7 ч 33 мин 20 с (200000 с)')).toBeInTheDocument();
    expect(gets(calls)).toHaveLength(2);
  });
});
