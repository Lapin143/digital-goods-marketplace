import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { problemMessage } from '../../../api/problem';
import { useAuth } from '../../../auth/AuthContext';
import { t } from '../../../i18n';
import { Notice } from '../../../ui/Notice';
import { StatePanel } from '../../../ui/StatePanel';
import { Table } from '../../../ui/Table';
import type { Column } from '../../../ui/Table';
import { formatMoment } from '../../../ui/format';
import { shortId } from './audit';
import { ParameterEditor } from './ParameterEditor';
import type { PlatformParameter } from './types';
import { describeValue, formatParameterValue } from './units';
import { PARAMETERS_KEY, useParameters } from './useAdmin';
import styles from './Admin.module.css';

/** Кто менял параметр: начальное значение, текущий администратор («вы») или идентификатор другого, сокращённо. */
export function updaterLabel(updatedBy: string | null, ownId: string): string {
  if (updatedBy === null) {
    return t('admin.params.updatedBy.initial');
  }
  return updatedBy === ownId ? t('admin.params.updatedBy.me') : t('admin.params.updatedBy.admin', { id: shortId(updatedBy) });
}

/**
 * Параметры платформы (US-8.3, OP-83): таблица значений с границами, умолчанием, версией и сведениями о последней правке; правка в строке.
 * Новые значения получают новые заказы и действия, созданные ранее сохраняют прежние сроки (так работает сервер, экран об этом напоминает).
 */
export function ParametersPage() {
  const queryClient = useQueryClient();
  const auth = useAuth();
  const query = useParameters();
  const ownId = auth.session('staff')?.claims.subject ?? '';
  const [notice, setNotice] = useState<{ kind: 'success' | 'error'; text: string } | undefined>();

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: PARAMETERS_KEY });
  }

  function saved(updated: PlatformParameter) {
    queryClient.setQueryData<PlatformParameter[]>(PARAMETERS_KEY, (items) => items?.map((item) => (item.key === updated.key ? updated : item)));
    setNotice({
      kind: 'success',
      text: t('admin.params.saved', { name: updated.key, value: describeValue(updated.unit, updated.value), version: updated.version }),
    });
  }

  function stale(message: string) {
    setNotice({ kind: 'error', text: message });
    refresh();
  }

  const parameters = query.data ?? [];
  const columns: Array<Column<PlatformParameter>> = [
    {
      key: 'name',
      header: t('admin.params.col.name'),
      render: (parameter) => (
        <>
          {parameter.description}
          <span className={styles.key}>{parameter.key}</span>
        </>
      ),
    },
    {
      key: 'value',
      header: t('admin.params.col.value'),
      render: (parameter) => <ParameterEditor parameter={parameter} parameters={parameters} onSaved={saved} onStale={stale} onRefresh={refresh} />,
    },
    { key: 'default', header: t('admin.params.col.default'), render: (parameter) => describeValue(parameter.unit, parameter.defaultValue) },
    {
      key: 'bounds',
      header: t('admin.params.col.bounds'),
      render: (parameter) =>
        t('admin.params.bounds.short', {
          min: formatParameterValue(parameter.unit, parameter.minValue),
          max: formatParameterValue(parameter.unit, parameter.maxValue),
        }),
    },
    { key: 'version', header: t('admin.params.col.version'), render: (parameter) => parameter.version },
    {
      key: 'updated',
      header: t('admin.params.col.updated'),
      render: (parameter) => (
        <>
          {formatMoment(parameter.updatedAt)}
          <span className={styles.key}>{updaterLabel(parameter.updatedBy, ownId)}</span>
        </>
      ),
    },
  ];

  return (
    <section>
      <p>
        <Link to="/staff">{t('moderation.back')}</Link>
      </p>
      <h1>{t('admin.params.title')}</h1>
      <p className={styles.lead}>{t('admin.params.lead')}</p>
      {notice ? <Notice kind={notice.kind}>{notice.text}</Notice> : null}
      {query.isPending ? <StatePanel kind="loading" /> : null}
      {query.isError && !query.data ? <StatePanel kind="error" message={problemMessage(query.error)} onRetry={() => void query.refetch()} /> : null}
      {query.data && parameters.length === 0 ? <StatePanel kind="empty" message={t('admin.params.empty')} /> : null}
      {parameters.length > 0 ? <Table caption={t('admin.params.caption')} columns={columns} rows={parameters} rowKey={(parameter) => parameter.key} /> : null}
    </section>
  );
}
