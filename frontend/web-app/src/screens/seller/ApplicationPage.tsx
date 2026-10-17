import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import type { ChangeEvent, FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { useApi } from '../../api/ApiContext';
import { ApiError, fieldMessages, problemMessage } from '../../api/problem';
import { useRequestKeys } from '../../api/requestKey';
import { t } from '../../i18n';
import type { TextKey } from '../../i18n';
import { Badge } from '../../ui/Badge';
import { Button } from '../../ui/Button';
import { Notice } from '../../ui/Notice';
import { SelectField } from '../../ui/SelectField';
import { StatePanel } from '../../ui/StatePanel';
import { TextAreaField } from '../../ui/TextAreaField';
import { TextField } from '../../ui/TextField';
import { formatMoment, formatSize } from '../../ui/format';
import {
  DOCUMENTS_MAX,
  DOCUMENT_MAX_BYTES,
  SELLER_TYPES,
  checkDocument,
  fieldOfPointer,
  toForm,
  validateDraft,
  validateSubmit,
  valuesFromApplication,
} from './application';
import type { ApplicationErrors, ApplicationValues } from './application';
import { sellerStatusLabel, sellerStatusTone, sellerTypeLabel } from './labels';
import type { SellerApplication, SellerDocument, DocumentLink } from './types';
import styles from './ApplicationPage.module.css';

const QUERY_KEY = ['seller-application'] as const;
const EDITABLE = new Set(['draft', 'returned', 'rejected']);

interface Loaded {
  application: SellerApplication | undefined;
  etag: string | undefined;
}

type Busy = 'save' | 'submit' | 'upload' | undefined;
type Message = { kind: 'error' | 'success' | 'info'; text: string } | undefined;
type FieldTexts = Partial<Record<keyof ApplicationValues | 'documents', string>>;

function statusText(application: SellerApplication): string {
  const time = formatMoment(application.reviewDeadlineAt);
  const reason = application.decisionReason ?? '';
  switch (application.status) {
    case 'draft':
      return t('application.status.draft');
    case 'on_review':
      return t('application.status.on_review', { time });
    case 'returned':
      return t('application.status.returned', { reason });
    case 'rejected':
      return t('application.status.rejected', { reason });
    case 'approved':
      return t('application.status.approved');
    default:
      return t('application.status.blocked');
  }
}

function texts(errors: ApplicationErrors): FieldTexts {
  const result: FieldTexts = {};
  for (const [field, key] of Object.entries(errors) as Array<[keyof ApplicationErrors, TextKey]>) {
    result[field] = t(key);
  }
  return result;
}

/**
 * Анкета и документы продавца (шаг 18 Ф4, US-2.1). Заявку подаёт покупатель, поэтому экран работает под клиентом `dgm-web`;
 * кабинет с товарами открывается после одобрения под `dgm-staff` со вторым фактором.
 * Неполная анкета на проверку не уходит: поля и документ проверяются до запроса, сервер проверяет то же самое ещё раз.
 */
function ApplicationForm({ loaded, onChange, reload }: { loaded: Loaded; onChange: (next: Loaded) => void; reload: () => Promise<void> }) {
  const api = useApi();
  const keys = useRequestKeys();
  const application = loaded.application;
  const editable = !application || EDITABLE.has(application.status);
  const documents: SellerDocument[] = application?.documents ?? [];

  const [values, setValues] = useState<ApplicationValues>(() => valuesFromApplication(application));
  const [fieldTexts, setFieldTexts] = useState<FieldTexts>({});
  const [message, setMessage] = useState<Message>(undefined);
  const [busy, setBusy] = useState<Busy>(undefined);
  const fileInput = useRef<HTMLInputElement>(null);

  // Поля анкеты на сервере изменились (сохранение, правка в другой вкладке): поля показывают сохранённое.
  // Загрузка документа меняет заявку, но не анкету, поэтому несохранённый ввод в других полях при этом не пропадает
  const saved = JSON.stringify(valuesFromApplication(application));
  useEffect(() => {
    setValues(valuesFromApplication(application));
    // application берётся из того же обновления, что и подпись saved
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [saved]);

  function set<K extends keyof ApplicationValues>(field: K, value: ApplicationValues[K]) {
    setValues((current) => ({ ...current, [field]: value }));
  }

  function failure(error: unknown) {
    const mapped: FieldTexts = {};
    for (const [name, text] of Object.entries(fieldMessages(error))) {
      const field = fieldOfPointer(name);
      if (field) {
        mapped[field] = text;
      }
    }
    setFieldTexts(mapped);
    let text = problemMessage(error);
    if (error instanceof ApiError && error.code === 'application-pause-active' && error.problem.retryAfterAt) {
      text = `${text} ${t('application.pauseUntil', { time: formatMoment(error.problem.retryAfterAt) })}`;
    }
    setMessage({ kind: 'error', text });
  }

  async function persist(): Promise<Loaded> {
    const body = toForm(values);
    if (!loaded.application) {
      const response = await api.post<SellerApplication>('/api/v1/seller-applications', body, { auth: 'web', idempotencyKey: keys.keyFor('create') });
      keys.settle();
      return { application: response.data, etag: response.etag };
    }
    const response = await api.put<SellerApplication>('/api/v1/seller-applications/current', body, { auth: 'web', ifMatch: loaded.etag });
    return { application: response.data, etag: response.etag };
  }

  async function save() {
    const errors = validateDraft(values);
    setFieldTexts(texts(errors));
    if (Object.keys(errors).length > 0) {
      return;
    }
    setBusy('save');
    setMessage(undefined);
    try {
      onChange(await persist());
      setMessage({ kind: 'success', text: t('application.saved') });
    } catch (error) {
      keys.settle(error);
      failure(error);
    } finally {
      setBusy(undefined);
    }
  }

  async function submit() {
    const errors = validateSubmit(values, documents.length);
    setFieldTexts(texts(errors));
    if (Object.keys(errors).length > 0) {
      setMessage({ kind: 'error', text: t('application.incomplete') });
      return;
    }
    setBusy('submit');
    setMessage(undefined);
    try {
      const current = JSON.stringify(toForm(valuesFromApplication(application))) === JSON.stringify(toForm(values)) ? loaded : await persist();
      onChange(current);
      const response = await api.post<SellerApplication>('/api/v1/seller-applications/current/submit', undefined, {
        auth: 'web',
        idempotencyKey: keys.keyFor('submit'),
      });
      keys.settle();
      onChange({ application: response.data, etag: undefined });
      await reload();
      setMessage({ kind: 'success', text: t('application.submitted') });
    } catch (error) {
      keys.settle(error);
      failure(error);
    } finally {
      setBusy(undefined);
    }
  }

  async function upload(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (!file) {
      return;
    }
    const check = checkDocument(file, documents.length);
    if (!check.ok) {
      setMessage({ kind: 'error', text: t(check.reason) });
      if (fileInput.current) {
        fileInput.current.value = '';
      }
      return;
    }
    setBusy('upload');
    setMessage(undefined);
    try {
      if (!loaded.application) {
        // Документы лежат в заявке: сначала создаётся черновик из того, что уже введено
        const errors = validateDraft(values);
        setFieldTexts(texts(errors));
        if (Object.keys(errors).length > 0) {
          return;
        }
        onChange(await persist());
      }
      const body = new FormData();
      body.append('file', file);
      await api.post<SellerDocument>('/api/v1/seller-applications/current/documents', body, {
        auth: 'web',
        idempotencyKey: keys.keyFor(`upload:${file.name}:${file.size}:${file.lastModified}`),
      });
      keys.settle();
      await reload();
      setMessage({ kind: 'success', text: t('application.uploaded') });
    } catch (error) {
      keys.settle(error);
      failure(error);
    } finally {
      setBusy(undefined);
      if (fileInput.current) {
        fileInput.current.value = '';
      }
    }
  }

  async function remove(documentId: string) {
    setMessage(undefined);
    try {
      await api.del(`/api/v1/seller-applications/current/documents/${encodeURIComponent(documentId)}`, { auth: 'web' });
      await reload();
      setMessage({ kind: 'success', text: t('application.deleted') });
    } catch (error) {
      failure(error);
    }
  }

  async function open(documentId: string) {
    setMessage(undefined);
    try {
      const response = await api.get<DocumentLink>(`/api/v1/seller-applications/current/documents/${encodeURIComponent(documentId)}/link`, { auth: 'web' });
      window.open(response.data.url, '_blank', 'noopener,noreferrer');
    } catch (error) {
      failure(error);
    }
  }

  const sizeUnits = { kb: t('units.kb'), mb: t('units.mb') };

  return (
    <section>
      <h1>{t('application.title')}</h1>
      <p>{t('application.lead')}</p>
      {application ? (
        <div className={styles.status}>
          <Badge tone={sellerStatusTone(application.status)}>{sellerStatusLabel(application.status)}</Badge>
          <span>{statusText(application)}</span>
          {application.status === 'rejected' && application.nextApplicationAt ? (
            <span>{t('application.nextAt', { time: formatMoment(application.nextApplicationAt) })}</span>
          ) : null}
          {application.status === 'approved' ? <Link to="/seller">{t('application.toCabinet')}</Link> : null}
        </div>
      ) : null}
      {message ? <Notice kind={message.kind}>{message.text}</Notice> : null}

      <form
        className={styles.form}
        noValidate
        onSubmit={(event: FormEvent) => {
          event.preventDefault();
          void save();
        }}
      >
        <fieldset className={styles.section} disabled={!editable}>
          <legend>{t('application.section.seller')}</legend>
          <SelectField
            label={t('application.field.sellerType')}
            placeholder={t('application.select')}
            options={SELLER_TYPES.map((type) => ({ value: type, label: sellerTypeLabel(type) }))}
            value={values.sellerType}
            error={fieldTexts.sellerType}
            onChange={(event) => set('sellerType', event.target.value)}
          />
          <TextField label={t('application.field.name')} maxLength={200} value={values.name} error={fieldTexts.name} onChange={(event) => set('name', event.target.value)} />
        </fieldset>

        <fieldset className={styles.section} disabled={!editable}>
          <legend>{t('application.section.payout')}</legend>
          <TextField
            label={t('application.field.recipientName')}
            maxLength={200}
            value={values.recipientName}
            error={fieldTexts.recipientName}
            onChange={(event) => set('recipientName', event.target.value)}
          />
          <TextField
            label={t('application.field.bankName')}
            maxLength={200}
            value={values.bankName}
            error={fieldTexts.bankName}
            onChange={(event) => set('bankName', event.target.value)}
          />
          <TextField
            label={t('application.field.bik')}
            hint={t('application.field.bik.hint')}
            inputMode="numeric"
            maxLength={9}
            value={values.bik}
            error={fieldTexts.bik}
            onChange={(event) => set('bik', event.target.value)}
          />
          <TextField
            label={t('application.field.accountNumber')}
            hint={t('application.field.accountNumber.hint')}
            inputMode="numeric"
            maxLength={20}
            value={values.accountNumber}
            error={fieldTexts.accountNumber}
            onChange={(event) => set('accountNumber', event.target.value)}
          />
        </fieldset>

        <fieldset className={styles.section} disabled={!editable}>
          <legend>{t('application.section.assortment')}</legend>
          <TextAreaField
            label={t('application.field.assortment')}
            maxLength={2000}
            value={values.assortmentDescription}
            error={fieldTexts.assortmentDescription}
            onChange={(event) => set('assortmentDescription', event.target.value)}
          />
        </fieldset>

        {editable ? (
          <div className={styles.actions}>
            <Button type="submit" variant="secondary" busy={busy === 'save'} disabled={busy !== undefined}>
              {t('application.save')}
            </Button>
            <Button busy={busy === 'submit'} disabled={busy !== undefined} onClick={() => void submit()}>
              {t('application.submit')}
            </Button>
          </div>
        ) : null}
      </form>

      <h2>{t('application.documents')}</h2>
      {documents.length === 0 ? <p>{t('application.documents.none')}</p> : null}
      <ul className={styles.documents} aria-label={t('application.documents.caption')}>
        {documents.map((document) => (
          <li key={document.documentId} className={styles.document}>
            <span className={styles.documentName}>{document.fileName}</span>
            <span className={styles.documentMeta}>{formatSize(document.sizeBytes, sizeUnits)}</span>
            <span className={styles.documentMeta}>{formatMoment(document.uploadedAt)}</span>
            <Button variant="secondary" onClick={() => void open(document.documentId)}>
              {t('application.documents.open')}
            </Button>
            {editable ? (
              <Button variant="danger" onClick={() => void remove(document.documentId)}>
                {t('application.documents.delete')}
              </Button>
            ) : null}
          </li>
        ))}
      </ul>
      {fieldTexts.documents ? <Notice kind="error">{fieldTexts.documents}</Notice> : null}
      {editable ? (
        <div>
          <label htmlFor="document-file">{t('application.documents.upload')}</label>
          <input
            id="document-file"
            ref={fileInput}
            className={styles.fileInput}
            type="file"
            accept=".pdf,.jpg,.jpeg,.png,application/pdf,image/jpeg,image/png"
            disabled={busy !== undefined || documents.length >= DOCUMENTS_MAX}
            aria-describedby="document-file-hint"
            onChange={(event) => void upload(event)}
          />
          <p id="document-file-hint" className={styles.documentMeta}>
            {t('application.documents.hint', { size: DOCUMENT_MAX_BYTES / (1024 * 1024), count: DOCUMENTS_MAX })}
          </p>
          {busy === 'upload' ? <p role="status">{t('application.documents.uploading')}</p> : null}
        </div>
      ) : null}
    </section>
  );
}

export function ApplicationPage() {
  const api = useApi();
  const queryClient = useQueryClient();
  const query = useQuery<Loaded>({
    queryKey: QUERY_KEY,
    queryFn: async ({ signal }) => {
      try {
        const response = await api.get<SellerApplication>('/api/v1/seller-applications/current', { auth: 'web', signal });
        return { application: response.data, etag: response.etag };
      } catch (error) {
        if (error instanceof ApiError && error.status === 404) {
          return { application: undefined, etag: undefined };
        }
        throw error;
      }
    },
  });

  if (query.isPending) {
    return <StatePanel kind="loading" />;
  }
  if (query.isError) {
    return <StatePanel kind="error" message={problemMessage(query.error)} onRetry={() => void query.refetch()} />;
  }
  return (
    <ApplicationForm
      loaded={query.data}
      onChange={(next) => queryClient.setQueryData(QUERY_KEY, next)}
      reload={async () => {
        await queryClient.invalidateQueries({ queryKey: QUERY_KEY });
      }}
    />
  );
}
