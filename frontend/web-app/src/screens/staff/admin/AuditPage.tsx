import { useState } from 'react';
import type { FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { ApiError, problemMessage } from '../../../api/problem';
import { hasText, t } from '../../../i18n';
import { Button } from '../../../ui/Button';
import { StatePanel } from '../../../ui/StatePanel';
import { Table } from '../../../ui/Table';
import type { Column } from '../../../ui/Table';
import { TextField } from '../../../ui/TextField';
import { EMPTY_FORM, changeValue, formatPrecise, readForm, shortId } from './audit';
import type { AuditForm, FormErrors } from './audit';
import type { AuditRecord } from './types';
import { NO_FILTERS, useAuditRecords } from './useAdmin';
import type { AuditFilters } from './useAdmin';
import styles from './Admin.module.css';

/** Роль исполнителя словами; незнакомая роль (перечисление может вырасти) показывается как есть. */
export function roleLabel(role: string): string {
  const key = `audit.role.${role}`;
  return hasText(key) ? t(key) : role;
}

function Changes({ record }: { record: AuditRecord }) {
  if (record.changes.length === 0) {
    return <span className={styles.muted}>{t('audit.change.none')}</span>;
  }
  return (
    <ul className={styles.changes}>
      {record.changes.map((change, index) => (
        <li key={`${change.field}-${index}`}>
          {t('audit.change.line', { field: change.field, before: changeValue(change, 'before'), after: changeValue(change, 'after') })}
          {change.personal ? ` ${t('audit.change.personal')}` : ''}
        </li>
      ))}
    </ul>
  );
}

const COLUMNS: Array<Column<AuditRecord>> = [
  { key: 'time', header: t('audit.col.time'), render: (record) => formatPrecise(record.occurredAt) },
  {
    key: 'actor',
    header: t('audit.col.actor'),
    render: (record) => (
      <span className={styles.code} title={record.actorId}>
        {shortId(record.actorId)}
      </span>
    ),
  },
  { key: 'role', header: t('audit.col.role'), render: (record) => roleLabel(record.actorRole) },
  { key: 'action', header: t('audit.col.action'), render: (record) => <span className={styles.code}>{record.action}</span> },
  {
    key: 'object',
    header: t('audit.col.object'),
    render: (record) => (
      <>
        {record.objectType}
        <span className={styles.key}>{record.objectId}</span>
      </>
    ),
  },
  { key: 'changes', header: t('audit.col.changes'), render: (record) => <Changes record={record} /> },
];

/**
 * Журнал аудита (US-8.6, US-8.7, OP-86): записи с исполнителем, ролью, действием, объектом и изменениями, новые выше, страницы по курсору,
 * фильтры по исполнителю, объекту, действию и периоду. Журнал только читается: ни изменить, ни удалить запись здесь нельзя. Персональные поля
 * приходят хешем и показываются сокращённо. Каждое чтение журнала записывается в него самого (Р-166), поэтому экран об этом говорит, а если
 * записать факт просмотра не удалось (503), честно пишет, что журнал недоступен, и записей не показывает.
 */
export function AuditPage() {
  const [form, setForm] = useState<AuditForm>(EMPTY_FORM);
  const [errors, setErrors] = useState<FormErrors>({});
  const [filters, setFilters] = useState<AuditFilters>(NO_FILTERS);
  const query = useAuditRecords(filters);
  const records = query.data?.pages.flatMap((page) => page.items) ?? [];
  const unavailable = query.error instanceof ApiError && query.error.status === 503;

  function set(field: keyof AuditForm, value: string) {
    setForm((current) => ({ ...current, [field]: value }));
  }

  function apply(event: FormEvent) {
    event.preventDefault();
    const result = readForm(form);
    if ('errors' in result) {
      setErrors(result.errors);
      return;
    }
    setErrors({});
    if (JSON.stringify(result.filters) === JSON.stringify(filters)) {
      void query.refetch();
    } else {
      setFilters(result.filters);
    }
  }

  function reset() {
    setForm(EMPTY_FORM);
    setErrors({});
    if (JSON.stringify(filters) === JSON.stringify(NO_FILTERS)) {
      void query.refetch();
    } else {
      setFilters(NO_FILTERS);
    }
  }

  return (
    <section>
      <p>
        <Link to="/staff">{t('moderation.back')}</Link>
      </p>
      <h1>{t('audit.title')}</h1>
      <p className={styles.lead}>{t('audit.lead')}</p>
      <form noValidate onSubmit={apply}>
        <div className={styles.filters}>
          <TextField label={t('audit.filter.actorId')} autoComplete="off" spellCheck={false} value={form.actorId} error={errors.actorId} onChange={(event) => set('actorId', event.target.value)} />
          <TextField label={t('audit.filter.objectType')} autoComplete="off" value={form.objectType} onChange={(event) => set('objectType', event.target.value)} />
          <TextField label={t('audit.filter.objectId')} autoComplete="off" value={form.objectId} onChange={(event) => set('objectId', event.target.value)} />
          <TextField label={t('audit.filter.action')} autoComplete="off" value={form.action} onChange={(event) => set('action', event.target.value)} />
          <TextField label={t('audit.filter.from')} type="datetime-local" value={form.from} error={errors.from} onChange={(event) => set('from', event.target.value)} />
          <TextField label={t('audit.filter.to')} type="datetime-local" value={form.to} error={errors.to} onChange={(event) => set('to', event.target.value)} />
        </div>
        <div className={styles.actions}>
          <Button type="submit" busy={query.isFetching && !query.isFetchingNextPage}>
            {t('audit.filter.apply')}
          </Button>
          <Button variant="secondary" onClick={reset}>
            {t('audit.filter.reset')}
          </Button>
        </div>
      </form>
      <div aria-live="polite" aria-busy={query.isFetching}>
        {query.isPending ? <StatePanel kind="loading" /> : null}
        {query.isError && !query.data ? (
          <StatePanel kind="error" message={unavailable ? t('audit.unavailable') : problemMessage(query.error)} onRetry={() => void query.refetch()} />
        ) : null}
        {query.data && records.length === 0 ? <StatePanel kind="empty" message={t('audit.empty')} /> : null}
        {records.length > 0 ? (
          <>
            <Table caption={t('audit.caption')} columns={COLUMNS} rows={records} rowKey={(record) => record.recordId} />
            {query.isFetchNextPageError ? <StatePanel kind="error" message={problemMessage(query.error)} onRetry={() => void query.fetchNextPage()} /> : null}
            {query.hasNextPage && !query.isFetchNextPageError ? (
              <Button variant="secondary" busy={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()}>
                {t('catalog.more')}
              </Button>
            ) : null}
          </>
        ) : null}
      </div>
    </section>
  );
}
