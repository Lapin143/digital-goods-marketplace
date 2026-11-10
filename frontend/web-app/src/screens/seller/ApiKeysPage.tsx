import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { useApi } from '../../api/ApiContext';
import { fieldMessages, problemMessage } from '../../api/problem';
import { isOutcomeUnknown, useRequestKeys } from '../../api/requestKey';
import { t } from '../../i18n';
import { Badge } from '../../ui/Badge';
import { Button } from '../../ui/Button';
import { Notice } from '../../ui/Notice';
import { StatePanel } from '../../ui/StatePanel';
import { Table } from '../../ui/Table';
import type { Column } from '../../ui/Table';
import { TextField } from '../../ui/TextField';
import { formatMoment } from '../../ui/format';
import { API_KEYS_MAX, API_KEY_NAME_MAX, activeKeyCount, keyMark, validateKeyName } from './apiKeys';
import { ConfirmPanel } from './ConfirmPanel';
import { OneTimeSecret } from './OneTimeSecret';
import { useReturnFocus } from './focus';
import type { ApiKey, ApiKeyIssued, ApiKeyPage } from './types';
import styles from './SellerProducts.module.css';

const PAGE_SIZE = 50;
const LIST_KEY = ['seller-api-keys'] as const;

type Message = { kind: 'error' | 'success' | 'info'; text: string } | undefined;

/** Только что выпущенный ключ: единственное место, где живёт его значение. После закрытия панели запись стирается. */
interface Issued {
  name: string;
  value: string;
}

/**
 * Ключи API системы продавца (US-11.1). Значение ключа видно один раз, в панели после выпуска: его нет ни в списке,
 * ни в кэше запросов (выпуск идёт прямым вызовом клиента, а не через кэш мутаций), ни в хранилище браузера.
 * Если ответ на выпуск потерян, повтор с тем же `Idempotency-Key` вернёт ключ без значения (Р-198): экран просит отозвать его
 * и выпустить новый.
 */
export function ApiKeysPage() {
  const api = useApi();
  const queryClient = useQueryClient();
  const requestKeys = useRequestKeys();
  const issueButton = useReturnFocus();
  const opener = useRef<HTMLElement | null>(null);

  const [name, setName] = useState('');
  const [nameError, setNameError] = useState<string | undefined>(undefined);
  const [message, setMessage] = useState<Message>(undefined);
  const [issuing, setIssuing] = useState(false);
  const [issued, setIssued] = useState<Issued | undefined>(undefined);
  const [revoking, setRevoking] = useState<ApiKey | undefined>(undefined);
  const [revokeBusy, setRevokeBusy] = useState(false);

  const list = useInfiniteQuery({
    queryKey: LIST_KEY,
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam, signal }) => {
      const response = await api.get<ApiKeyPage>('/api/v1/seller/api-keys', { auth: 'staff', query: { limit: PAGE_SIZE, cursor: pageParam }, signal });
      return response.data;
    },
    getNextPageParam: (last) => last.page.nextCursor ?? undefined,
  });
  const items: ApiKey[] = list.data?.pages.flatMap((page) => page.items) ?? [];
  const limitReached = activeKeyCount(items) >= API_KEYS_MAX;

  async function issue() {
    const invalid = validateKeyName(name);
    setNameError(invalid ? t(invalid) : undefined);
    if (invalid) {
      return;
    }
    const trimmed = name.trim();
    setIssuing(true);
    setMessage(undefined);
    try {
      const response = await api.post<ApiKeyIssued>(
        '/api/v1/seller/api-keys',
        { name: trimmed },
        { auth: 'staff', idempotencyKey: requestKeys.keyFor(`issue:${trimmed}`) },
      );
      requestKeys.settle();
      const value = response.data.value;
      if (value === undefined) {
        setMessage({ kind: 'error', text: t('apiKeys.lost', { name: trimmed }) });
      } else {
        setIssued({ name: trimmed, value });
      }
      setName('');
    } catch (error) {
      requestKeys.settle(error);
      setNameError(fieldMessages(error).name);
      setMessage({ kind: 'error', text: problemMessage(error) + (isOutcomeUnknown(error) ? ` ${t('apiKeys.retryHint')}` : '') });
    } finally {
      setIssuing(false);
      void queryClient.invalidateQueries({ queryKey: LIST_KEY });
    }
  }

  async function revoke(key: ApiKey) {
    setRevokeBusy(true);
    setMessage(undefined);
    try {
      await api.post<ApiKey>(`/api/v1/seller/api-keys/${encodeURIComponent(key.id)}/revocation`, undefined, {
        auth: 'staff',
        idempotencyKey: requestKeys.keyFor(`revoke:${key.id}`),
      });
      requestKeys.settle();
      setMessage({ kind: 'success', text: t('apiKeys.revoked', { name: key.name }) });
      setRevoking(undefined);
      issueButton.restore();
    } catch (error) {
      requestKeys.settle(error);
      setMessage({ kind: 'error', text: problemMessage(error) });
      setRevoking(undefined);
      opener.current?.focus();
    } finally {
      setRevokeBusy(false);
      void queryClient.invalidateQueries({ queryKey: LIST_KEY });
    }
  }

  const columns: Array<Column<ApiKey>> = [
    { key: 'name', header: t('apiKeys.col.name'), render: (row) => row.name },
    { key: 'mark', header: t('apiKeys.col.mark'), render: (row) => <code>{`${keyMark(row.fingerprint)}…`}</code> },
    {
      key: 'status',
      header: t('apiKeys.col.status'),
      render: (row) => <Badge tone={row.status === 'active' ? 'good' : 'neutral'}>{t(row.status === 'active' ? 'apiKeys.status.active' : 'apiKeys.status.revoked')}</Badge>,
    },
    { key: 'created', header: t('apiKeys.col.created'), render: (row) => formatMoment(row.createdAt) },
    { key: 'revoked', header: t('apiKeys.col.revoked'), render: (row) => (row.revokedAt ? formatMoment(row.revokedAt) : '–') },
    {
      key: 'actions',
      header: t('apiKeys.col.actions'),
      render: (row) =>
        row.status === 'active' ? (
          <Button
            variant="danger"
            aria-label={t('apiKeys.revoke.label', { name: row.name })}
            onClick={(event) => {
              if (revokeBusy) {
                return;
              }
              opener.current = event.currentTarget;
              setMessage(undefined);
              setRevoking(row);
            }}
          >
            {t('apiKeys.revoke')}
          </Button>
        ) : null,
    },
  ];

  return (
    <section>
      <p>
        <Link to="/seller">{t('seller.cabinet.back')}</Link>
      </p>
      <h1>{t('apiKeys.title')}</h1>
      <p>{t('apiKeys.lead')}</p>

      {issued ? (
        <OneTimeSecret
          heading={t('apiKeys.issued.heading', { name: issued.name })}
          warning={t('apiKeys.issued.warning')}
          value={issued.value}
          onClose={() => {
            setIssued(undefined);
            issueButton.restore();
          }}
        />
      ) : null}

      {message ? <Notice kind={message.kind}>{message.text}</Notice> : null}

      {revoking ? (
        <ConfirmPanel
          heading={t('apiKeys.revoke.heading', { name: revoking.name })}
          confirmLabel={t('apiKeys.revoke.confirm')}
          cancelLabel={t('apiKeys.revoke.cancel')}
          danger
          busy={revokeBusy}
          onConfirm={() => void revoke(revoking)}
          onCancel={() => {
            setRevoking(undefined);
            opener.current?.focus();
          }}
        >
          <p>{t('apiKeys.revoke.text', { mark: keyMark(revoking.fingerprint) })}</p>
        </ConfirmPanel>
      ) : null}

      <h2>{t('apiKeys.issue.title')}</h2>
      {limitReached ? <Notice kind="info">{t('apiKeys.limitReached', { limit: API_KEYS_MAX })}</Notice> : null}
      <form
        className={styles.form}
        noValidate
        onSubmit={(event: FormEvent) => {
          event.preventDefault();
          void issue();
        }}
      >
        <TextField
          label={t('apiKeys.issue.name')}
          hint={t('apiKeys.issue.name.hint', { limit: API_KEYS_MAX })}
          maxLength={API_KEY_NAME_MAX}
          autoComplete="off"
          value={name}
          error={nameError}
          onChange={(event) => setName(event.target.value)}
        />
        <Button type="submit" buttonRef={issueButton.ref} busy={issuing} disabled={limitReached || issued !== undefined}>
          {t('apiKeys.issue')}
        </Button>
      </form>

      <h2>{t('apiKeys.list.title')}</h2>
      <div aria-live="polite" aria-busy={list.isFetching}>
        {list.isPending ? <StatePanel kind="loading" /> : null}
        {list.isError && !list.data ? <StatePanel kind="error" message={problemMessage(list.error)} onRetry={() => void list.refetch()} /> : null}
        {list.data && items.length === 0 ? <StatePanel kind="empty" message={t('apiKeys.empty')} /> : null}
        {items.length > 0 ? (
          <>
            <Table caption={t('apiKeys.list.caption')} columns={columns} rows={items} rowKey={(row) => row.id} />
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
