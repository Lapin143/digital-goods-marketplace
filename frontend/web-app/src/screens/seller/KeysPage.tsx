import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import type { ChangeEvent } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useApi } from '../../api/ApiContext';
import { problemMessage } from '../../api/problem';
import { isOutcomeUnknown } from '../../api/requestKey';
import { t } from '../../i18n';
import { Button } from '../../ui/Button';
import { Notice } from '../../ui/Notice';
import { StatePanel } from '../../ui/StatePanel';
import { Table } from '../../ui/Table';
import type { Column } from '../../ui/Table';
import { formatMoment } from '../../ui/format';
import { CSV_MAX_BYTES, KEYS_MAX, checkCsv, splitKeys } from './keys';
import { rejectReasonLabel } from './labels';
import type { KeyUploadReport, StockInfo } from './types';
import styles from './SellerProducts.module.css';

/** Отправка, которую можно повторить с тем же телом и тем же ключом идемпотентности, пока исход неизвестен. */
interface Pending {
  send: () => Promise<KeyUploadReport>;
}

function StockPanel({ productId }: { productId: string }) {
  const api = useApi();
  const query = useQuery({
    queryKey: ['seller-stock', productId],
    queryFn: async ({ signal }) => {
      const response = await api.get<StockInfo>(`/api/v1/seller/products/${encodeURIComponent(productId)}/stock`, { auth: 'staff', signal });
      return response.data;
    },
  });
  if (query.isPending) {
    return <StatePanel kind="loading" />;
  }
  if (query.isError) {
    return <StatePanel kind="error" message={problemMessage(query.error)} onRetry={() => void query.refetch()} />;
  }
  const stock = query.data;
  return (
    <div>
      <dl className={styles.stock}>
        <dt>{t('keys.stock.available')}</dt>
        <dd>{stock.available}</dd>
        <dt>{t('keys.stock.reserved')}</dt>
        <dd>{stock.reserved}</dd>
        <dt>{t('keys.stock.issued')}</dt>
        <dd>{stock.issued}</dd>
        <dt>{t('keys.stock.poolSize')}</dt>
        <dd>{t('keys.stock.ofLimit', { size: stock.poolSize, limit: stock.poolLimit })}</dd>
        {stock.updatedAt ? (
          <>
            <dt>{t('keys.stock.updatedAt')}</dt>
            <dd>{formatMoment(stock.updatedAt)}</dd>
          </>
        ) : null}
      </dl>
      <Button variant="secondary" busy={query.isFetching} onClick={() => void query.refetch()}>
        {t('keys.stock.refresh')}
      </Button>
    </div>
  );
}

function ReportView({ report }: { report: KeyUploadReport }) {
  const columns: Array<Column<KeyUploadReport['rejected'][number]>> = [
    { key: 'line', header: t('keys.report.line'), render: (row) => row.line },
    { key: 'reason', header: t('keys.report.reason'), render: (row) => rejectReasonLabel(row.reason) },
  ];
  return (
    <div className={styles.report}>
      <Notice kind={report.accepted > 0 ? 'success' : 'info'}>
        {t('keys.report.summary', { accepted: report.accepted, rejected: report.rejectedTotal, available: report.available, limit: report.poolLimit })}
      </Notice>
      {report.rejected.length > 0 ? (
        <>
          {report.rejectedTotal > report.rejected.length ? <p>{t('keys.report.firstOnly', { shown: report.rejected.length, total: report.rejectedTotal })}</p> : null}
          <Table caption={t('keys.report.caption')} columns={columns} rows={report.rejected} rowKey={(row) => `${row.line}-${row.reason}`} />
        </>
      ) : null}
    </div>
  );
}

/**
 * Загрузка ключей (US-4.2, US-4.3): списком в поле или файлом CSV. Значения ключей не хранятся в состоянии компонента:
 * поле неуправляемое, его текст читается один раз при нажатии и сразу очищается, а до ответа сервера тело держит только замыкание
 * повторной отправки. Ответ с отказом или успехом это замыкание стирает; повтор с тем же `Idempotency-Key` остаётся только
 * при неизвестном исходе (сбой сети, 5xx). В отчёте только номера строк и причины, значений там нет.
 */
function KeyUpload({ productId }: { productId: string }) {
  const api = useApi();
  const queryClient = useQueryClient();
  const area = useRef<HTMLTextAreaElement>(null);
  const pending = useRef<Pending | undefined>(undefined);
  const [report, setReport] = useState<KeyUploadReport | undefined>(undefined);
  const [message, setMessage] = useState<string | undefined>(undefined);
  const [canRetry, setCanRetry] = useState(false);

  const [busy, setBusy] = useState(false);

  // Не useMutation: кэш мутаций держит переменные запроса (а в них значения ключей) до сборки мусора. Здесь тело живёт только в замыкании отправки
  async function run(job: Pending) {
    pending.current = job;
    setBusy(true);
    setReport(undefined);
    setMessage(undefined);
    setCanRetry(false);
    try {
      const result = await job.send();
      pending.current = undefined;
      setReport(result);
      void queryClient.invalidateQueries({ queryKey: ['seller-stock', productId] });
    } catch (error) {
      const retry = isOutcomeUnknown(error);
      if (!retry) {
        pending.current = undefined;
      }
      setCanRetry(retry);
      setMessage(problemMessage(error) + (retry ? '' : ` ${t('keys.error.pasteAgain')}`));
    } finally {
      setBusy(false);
    }
  }

  const base = `/api/v1/seller/products/${encodeURIComponent(productId)}`;

  function sendList() {
    const lines = splitKeys(area.current?.value ?? '');
    if (lines.length === 0) {
      setMessage(t('keys.error.empty'));
      return;
    }
    if (lines.length > KEYS_MAX) {
      setMessage(t('keys.error.tooMany', { limit: KEYS_MAX }));
      return;
    }
    if (area.current) {
      area.current.value = '';
    }
    const idempotencyKey = api.newKey();
    void run({ send: async () => (await api.post<KeyUploadReport>(`${base}/key-batches`, { keys: lines }, { auth: 'staff', idempotencyKey })).data });
  }

  function sendFile(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (!file) {
      return;
    }
    const check = checkCsv(file);
    if (!check.ok) {
      setMessage(t(check.reason, { limit: CSV_MAX_BYTES / (1024 * 1024) }));
      event.target.value = '';
      return;
    }
    const idempotencyKey = api.newKey();
    event.target.value = '';
    void run({ send: async () => (await api.post<KeyUploadReport>(`${base}/key-files`, file, { auth: 'staff', contentType: 'text/csv', idempotencyKey })).data });
  }

  return (
    <div>
      <h2>{t('keys.list.title')}</h2>
      <label htmlFor="keys-list">{t('keys.list.label')}</label>
      <textarea
        id="keys-list"
        ref={area}
        className={styles.keysArea}
        autoComplete="off"
        autoCorrect="off"
        autoCapitalize="off"
        spellCheck={false}
        disabled={busy}
        aria-describedby="keys-list-hint"
      />
      <p id="keys-list-hint">{t('keys.list.hint', { limit: KEYS_MAX })}</p>
      <Button busy={busy} onClick={sendList}>
        {t('keys.list.send')}
      </Button>

      <h2>{t('keys.file.title')}</h2>
      <label htmlFor="keys-file">{t('keys.file.label')}</label>
      <input id="keys-file" className={styles.fileInput} type="file" accept=".csv,.txt,text/csv,text/plain" disabled={busy} aria-describedby="keys-file-hint" onChange={sendFile} />
      <p id="keys-file-hint">{t('keys.file.hint', { size: CSV_MAX_BYTES / (1024 * 1024), limit: KEYS_MAX })}</p>

      {busy ? <p role="status">{t('keys.sending')}</p> : null}
      {message ? <Notice kind="error">{message}</Notice> : null}
      {canRetry && pending.current ? (
        <p>
          <Button
            variant="secondary"
            onClick={() => {
              if (pending.current) {
                void run(pending.current);
              }
            }}
          >
            {t('keys.retry')}
          </Button>
        </p>
      ) : null}
      {report ? <ReportView report={report} /> : null}
    </div>
  );
}

/** Остаток и загрузка ключей товара (`/seller/products/:productId/keys`). */
export function KeysPage() {
  const { productId } = useParams();
  if (!productId) {
    return <StatePanel kind="error" message={t('product.notFound.text')} />;
  }
  return (
    <section>
      <p>
        <Link to={`/seller/products/${encodeURIComponent(productId)}`}>{t('keys.back')}</Link>
      </p>
      <h1>{t('keys.title')}</h1>
      <h2>{t('keys.stock.title')}</h2>
      <StockPanel productId={productId} />
      <KeyUpload productId={productId} />
    </section>
  );
}
