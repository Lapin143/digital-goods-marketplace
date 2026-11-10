import { useInfiniteQuery, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import type { FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { useApi } from '../../api/ApiContext';
import { ApiError, fieldMessages, problemMessage } from '../../api/problem';
import { isOutcomeUnknown, useRequestKeys } from '../../api/requestKey';
import { t } from '../../i18n';
import { Badge } from '../../ui/Badge';
import { Button } from '../../ui/Button';
import { Notice } from '../../ui/Notice';
import { SelectField } from '../../ui/SelectField';
import { StatePanel } from '../../ui/StatePanel';
import { Table } from '../../ui/Table';
import type { Column } from '../../ui/Table';
import { TextField } from '../../ui/TextField';
import { formatMoment } from '../../ui/format';
import { ConfirmPanel } from './ConfirmPanel';
import { OneTimeSecret } from './OneTimeSecret';
import { useReturnFocus } from './focus';
import {
  DELIVERY_FILTERS,
  ENDPOINT_MAX,
  INTEGRATION_STATUSES,
  deliveryStatusLabel,
  deliveryStatusTone,
  deliveryTypeLabel,
  integrationStatusLabel,
  integrationStatusTone,
  isEndpointRejected,
  validateEndpoint,
} from './integration';
import type { SecretRotation, SellerIntegration, SellerIntegrationSaved, WebhookDelivery, WebhookDeliveryPage } from './types';
import styles from './SellerProducts.module.css';

const INTEGRATION_KEY = ['seller-integration'] as const;
const DELIVERIES_PAGE = 20;

type Message = { kind: 'error' | 'success' | 'info'; text: string } | undefined;

interface Loaded {
  integration: SellerIntegration;
  etag: string | undefined;
}

/** Секрет подписи, показанный один раз: после сохранения первого подключения или после смены. */
interface ShownSecret {
  heading: string;
  value: string;
  /** Кнопка, после которой показан секрет: на неё возвращается фокус при закрытии панели. */
  from: 'save' | 'rotate';
}

/**
 * Адрес системы продавца и секрет подписи (US-11.3). Правка уходит с `If-Match`; при 412 введённые значения остаются в полях,
 * а данные подключения перечитываются, чтобы человек проверил их и сохранил ещё раз. Секрет приходит только в ответе на первое
 * подключение и на смену, живёт в состоянии панели и стирается её закрытием; в кэш запросов попадает одна запись подключения без секрета.
 */
function Connection({ loaded }: { loaded: Loaded }) {
  const api = useApi();
  const queryClient = useQueryClient();
  const requestKeys = useRequestKeys();
  const saveButton = useReturnFocus();
  const rotateButton = useReturnFocus();
  const { integration } = loaded;

  const [endpoint, setEndpoint] = useState(integration.endpointUrl ?? '');
  const [status, setStatus] = useState<string>(integration.endpointUrl === null ? 'active' : integration.status);
  const [fieldError, setFieldError] = useState<string | undefined>(undefined);
  const [message, setMessage] = useState<Message>(undefined);
  const [busy, setBusy] = useState<'save' | 'rotate' | undefined>(undefined);
  const [secret, setSecret] = useState<ShownSecret | undefined>(undefined);
  const [confirmRotate, setConfirmRotate] = useState(false);

  async function save() {
    const invalid = validateEndpoint(endpoint);
    setFieldError(invalid ? t(invalid) : undefined);
    if (invalid) {
      setMessage({ kind: 'error', text: t('problem.validation-failed') });
      return;
    }
    setBusy('save');
    setMessage(undefined);
    try {
      const response = await api.put<SellerIntegrationSaved>(
        '/api/v1/seller/integration',
        { endpointUrl: endpoint.trim(), status },
        { auth: 'staff', ifMatch: loaded.etag ?? `"${integration.version}"` },
      );
      const saved = response.data;
      queryClient.setQueryData<Loaded>(INTEGRATION_KEY, { integration: saved.integration, etag: response.etag });
      setEndpoint(saved.integration.endpointUrl ?? endpoint.trim());
      if (saved.secret !== undefined) {
        setSecret({ heading: t('integration.secret.first'), value: saved.secret, from: 'save' });
      }
      setMessage({ kind: 'success', text: t(saved.secret !== undefined ? 'integration.saved.first' : 'integration.saved') });
    } catch (error) {
      if (isEndpointRejected(error)) {
        setFieldError(t('problem.endpoint-not-allowed'));
      } else if (error instanceof ApiError && error.status === 412) {
        // Введённое остаётся в полях, перечитывается только версия записи
        setMessage({ kind: 'error', text: t('integration.conflict') });
        void queryClient.invalidateQueries({ queryKey: INTEGRATION_KEY });
      } else {
        setFieldError(fieldMessages(error).endpointUrl);
        setMessage({ kind: 'error', text: problemMessage(error) });
      }
    } finally {
      setBusy(undefined);
    }
  }

  async function rotate() {
    setBusy('rotate');
    setMessage(undefined);
    try {
      const response = await api.post<SecretRotation>('/api/v1/seller/integration/secret-rotation', undefined, {
        auth: 'staff',
        idempotencyKey: requestKeys.keyFor(`rotate:${integration.secretVersion}`),
      });
      requestKeys.settle();
      setConfirmRotate(false);
      if (response.data.secret === undefined) {
        setMessage({ kind: 'error', text: t('integration.rotate.lost') });
        rotateButton.restore();
      } else {
        setSecret({ heading: t('integration.secret.rotated', { version: response.data.secretVersion }), value: response.data.secret, from: 'rotate' });
      }
      void queryClient.invalidateQueries({ queryKey: INTEGRATION_KEY });
    } catch (error) {
      requestKeys.settle(error);
      setMessage({ kind: 'error', text: problemMessage(error) + (isOutcomeUnknown(error) ? ` ${t('integration.rotate.retryHint')}` : '') });
      setConfirmRotate(false);
      rotateButton.restore();
    } finally {
      setBusy(undefined);
    }
  }

  const connected = integration.secretVersion > 0;
  const secretOpen = secret !== undefined;

  return (
    <div>
      {secret ? (
        <OneTimeSecret
          heading={secret.heading}
          warning={t('integration.secret.warning')}
          value={secret.value}
          onClose={() => {
            setSecret(undefined);
            (secret.from === 'rotate' ? rotateButton : saveButton).restore();
          }}
        />
      ) : null}
      {message ? <Notice kind={message.kind}>{message.text}</Notice> : null}

      <dl className={styles.stock}>
        <dt>{t('integration.state.status')}</dt>
        <dd>
          <Badge tone={integrationStatusTone(integration.status)}>{connected ? integrationStatusLabel(integration.status) : t('integration.state.none')}</Badge>
        </dd>
        <dt>{t('integration.state.secretVersion')}</dt>
        <dd>{connected ? integration.secretVersion : t('integration.state.noSecret')}</dd>
        {integration.updatedAt ? (
          <>
            <dt>{t('integration.state.updatedAt')}</dt>
            <dd>{formatMoment(integration.updatedAt)}</dd>
          </>
        ) : null}
      </dl>

      <form
        className={styles.form}
        noValidate
        onSubmit={(event: FormEvent) => {
          event.preventDefault();
          void save();
        }}
      >
        <TextField
          label={t('integration.endpoint')}
          hint={t('integration.endpoint.hint')}
          type="url"
          inputMode="url"
          autoComplete="off"
          maxLength={ENDPOINT_MAX}
          value={endpoint}
          error={fieldError}
          onChange={(event) => setEndpoint(event.target.value)}
        />
        <SelectField
          label={t('integration.status')}
          options={INTEGRATION_STATUSES.map((value) => ({ value, label: integrationStatusLabel(value) }))}
          value={status}
          onChange={(event) => setStatus(event.target.value)}
        />
        <div className={styles.actions}>
          <Button type="submit" buttonRef={saveButton.ref} busy={busy === 'save'} disabled={busy !== undefined || secretOpen}>
            {t('integration.save')}
          </Button>
          {connected ? (
            <Button variant="secondary" buttonRef={rotateButton.ref} disabled={busy !== undefined || secretOpen || confirmRotate} onClick={() => setConfirmRotate(true)}>
              {t('integration.rotate')}
            </Button>
          ) : null}
        </div>
      </form>

      {confirmRotate ? (
        <ConfirmPanel
          heading={t('integration.rotate.heading')}
          confirmLabel={t('integration.rotate.confirm')}
          cancelLabel={t('integration.rotate.cancel')}
          danger
          busy={busy === 'rotate'}
          onConfirm={() => void rotate()}
          onCancel={() => {
            setConfirmRotate(false);
            rotateButton.restore();
          }}
        >
          <p>{t('integration.rotate.text')}</p>
        </ConfirmPanel>
      ) : null}
    </div>
  );
}

/** Уведомления о заказах, которые платформа отправляла в систему продавца (US-11.4): по умолчанию те, что не удалось доставить. */
function Deliveries() {
  const api = useApi();
  const [filter, setFilter] = useState<string>('failed');
  const list = useInfiniteQuery({
    queryKey: ['seller-webhook-deliveries', filter],
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam, signal }) => {
      const response = await api.get<WebhookDeliveryPage>('/api/v1/seller/webhook-deliveries', {
        auth: 'staff',
        query: { status: filter === '' ? undefined : filter, limit: DELIVERIES_PAGE, cursor: pageParam },
        signal,
      });
      return response.data;
    },
    getNextPageParam: (last) => last.page.nextCursor ?? undefined,
  });
  const items: WebhookDelivery[] = list.data?.pages.flatMap((page) => page.items) ?? [];

  const columns: Array<Column<WebhookDelivery>> = [
    { key: 'order', header: t('integration.delivery.col.order'), render: (row) => row.orderNumber },
    { key: 'type', header: t('integration.delivery.col.type'), render: (row) => deliveryTypeLabel(row.type) },
    {
      key: 'status',
      header: t('integration.delivery.col.status'),
      render: (row) => <Badge tone={deliveryStatusTone(row.status)}>{deliveryStatusLabel(row.status)}</Badge>,
    },
    { key: 'attempts', header: t('integration.delivery.col.attempts'), render: (row) => row.attempts },
    { key: 'code', header: t('integration.delivery.col.code'), render: (row) => row.lastResponseCode ?? t('integration.delivery.noResponse') },
    { key: 'last', header: t('integration.delivery.col.last'), render: (row) => formatMoment(row.lastAttemptAt) },
    { key: 'next', header: t('integration.delivery.col.next'), render: (row) => (row.status === 'queued' ? formatMoment(row.nextAttemptAt) : '–') },
  ];

  return (
    <section>
      <h2>{t('integration.deliveries.title')}</h2>
      <p>{t('integration.deliveries.lead')}</p>
      <div className={styles.toolbar}>
        <div className={styles.filter}>
          <SelectField
            label={t('integration.deliveries.filter')}
            placeholder={t('integration.deliveries.all')}
            options={DELIVERY_FILTERS.map((value) => ({ value, label: deliveryStatusLabel(value) }))}
            value={filter}
            onChange={(event) => setFilter(event.target.value)}
          />
        </div>
      </div>
      <div aria-live="polite" aria-busy={list.isFetching}>
        {list.isPending ? <StatePanel kind="loading" /> : null}
        {list.isError && !list.data ? <StatePanel kind="error" message={problemMessage(list.error)} onRetry={() => void list.refetch()} /> : null}
        {list.data && items.length === 0 ? (
          <StatePanel kind="empty" message={filter === 'failed' ? t('integration.deliveries.emptyFailed') : t('integration.deliveries.empty')} />
        ) : null}
        {items.length > 0 ? (
          <>
            <Table caption={t('integration.deliveries.caption')} columns={columns} rows={items} rowKey={(row) => row.deliveryId} />
            {list.isFetchNextPageError ? <StatePanel kind="error" message={problemMessage(list.error)} onRetry={() => void list.fetchNextPage()} /> : null}
            {list.hasNextPage && !list.isFetchNextPageError ? (
              <Button variant="secondary" busy={list.isFetchingNextPage} onClick={() => void list.fetchNextPage()}>
                {t('catalog.more')}
              </Button>
            ) : null}
          </>
        ) : null}
      </div>
    </section>
  );
}

/** Подключение системы продавца (`/seller/integration`): адрес, статус, секрет подписи и недоставленные уведомления. */
export function IntegrationPage() {
  const api = useApi();
  const query = useQuery<Loaded>({
    queryKey: INTEGRATION_KEY,
    queryFn: async ({ signal }) => {
      const response = await api.get<SellerIntegration>('/api/v1/seller/integration', { auth: 'staff', signal });
      return { integration: response.data, etag: response.etag };
    },
  });
  // Форма (Connection) остаётся на месте, пока есть данные, поэтому перечитывание после конфликта не стирает введённое

  return (
    <section>
      <p>
        <Link to="/seller">{t('seller.cabinet.back')}</Link>
      </p>
      <h1>{t('integration.title')}</h1>
      <p>{t('integration.lead')}</p>
      {query.isPending ? <StatePanel kind="loading" /> : null}
      {query.isError && !query.data ? <StatePanel kind="error" message={problemMessage(query.error)} onRetry={() => void query.refetch()} /> : null}
      {query.data ? <Connection loaded={query.data} /> : null}
      <Deliveries />
    </section>
  );
}
