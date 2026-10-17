import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { problemMessage } from '../../../api/problem';
import { toLocalMoment } from '../../../api/clock';
import { t } from '../../../i18n';
import { Badge } from '../../../ui/Badge';
import { Button } from '../../../ui/Button';
import { Notice } from '../../../ui/Notice';
import { StatePanel } from '../../../ui/StatePanel';
import { formatMoment, formatSize } from '../../../ui/format';
import { useRemainingSeconds } from '../../../ui/useCountdown';
import { sellerStatusLabel, sellerStatusTone, sellerTypeLabel } from '../../seller/labels';
import { DecisionPanel } from './DecisionPanel';
import type { DecisionOption } from './DecisionPanel';
import { formatCountdown } from './decision';
import type { ApplicationCard, ReviewDocument } from './types';
import { useCard } from './useModeration';
import styles from './Moderation.module.css';

function options(): DecisionOption[] {
  return [
    { id: 'approve', label: t('moderation.application.approve'), variant: 'primary', confirm: t('moderation.application.approve.confirm'), done: t('moderation.application.approve.done') },
    {
      id: 'reject',
      label: t('moderation.application.reject'),
      variant: 'danger',
      text: { field: 'reason', label: t('moderation.application.reject.reason') },
      confirm: t('moderation.application.reject.confirm'),
      done: t('moderation.application.reject.done'),
    },
    {
      id: 'return',
      label: t('moderation.application.return'),
      variant: 'secondary',
      text: { field: 'comment', label: t('moderation.application.return.comment') },
      confirm: t('moderation.application.return.confirm'),
      done: t('moderation.application.return.done'),
    },
  ];
}

function Documents({ documents, clockOffset, onRefresh, refreshing }: { documents: ReviewDocument[]; clockOffset: number; onRefresh: () => void; refreshing: boolean }) {
  const units = { kb: t('units.kb'), mb: t('units.mb') };
  // Ссылки выдаются на 5 минут; срок считается по часам сервера, чтобы неверные часы устройства не показывали старую ссылку живой
  const expires = documents.map((document) => toLocalMoment(document.link.expiresAt, clockOffset)).filter((value): value is number => value !== undefined);
  const remaining = useRemainingSeconds(expires.length > 0 ? Math.min(...expires) : undefined);
  const expired = documents.length > 0 && remaining <= 0;
  return (
    <div>
      <h2>{t('moderation.documents')}</h2>
      {documents.length === 0 ? <p>{t('moderation.documents.none')}</p> : null}
      {documents.length > 0 ? (
        expired ? (
          <Notice kind="info">{t('moderation.documents.expired')}</Notice>
        ) : (
          <p>{t('moderation.documents.validFor', { time: formatCountdown(remaining) })}</p>
        )
      ) : null}
      <ul className={styles.documents} aria-label={t('moderation.documents.list')}>
        {documents.map((document) => (
          <li key={document.documentId} className={styles.document}>
            {expired ? (
              <span className={styles.documentName}>{document.fileName}</span>
            ) : (
              <a className={styles.documentName} href={document.link.url} target="_blank" rel="noopener noreferrer" referrerPolicy="no-referrer">
                {document.fileName}
              </a>
            )}
            <span>{formatSize(document.sizeBytes, units)}</span>
            <span>{formatMoment(document.uploadedAt)}</span>
          </li>
        ))}
      </ul>
      <Button variant="secondary" busy={refreshing} onClick={onRefresh}>
        {t('moderation.refresh')}
      </Button>
    </div>
  );
}

/** Карточка заявки продавца (OP-11, OP-12): анкета, документы по ссылке на 5 минут, решения. */
export function ApplicationCardPage() {
  const { sellerId } = useParams();
  const id = sellerId ?? '';
  const queryClient = useQueryClient();
  const query = useCard<ApplicationCard>('application', id, `/api/v1/staff/seller-applications/${encodeURIComponent(id)}`);
  const [notice, setNotice] = useState<{ kind: 'success' | 'error'; text: string } | undefined>(undefined);

  async function reload() {
    await queryClient.invalidateQueries({ queryKey: ['moderation-card', 'application', id] });
    void queryClient.invalidateQueries({ queryKey: ['moderation-queue', 'applications'] });
  }

  if (query.isPending) {
    return <StatePanel kind="loading" />;
  }
  if (query.isError) {
    return <StatePanel kind="error" message={problemMessage(query.error)} onRetry={() => void query.refetch()} />;
  }
  const { card, clockOffset } = query.data;
  const pending = card.status === 'on_review';
  return (
    <section>
      <p>
        <Link to="/staff/applications">{t('moderation.applications.back')}</Link>
      </p>
      <h1>{card.name}</h1>
      <div className={styles.meta}>
        <Badge tone={sellerStatusTone(card.status)}>{sellerStatusLabel(card.status)}</Badge>
        <span>{t('moderation.submittedAt', { time: formatMoment(card.submittedAt) })}</span>
        <span>{t('moderation.deadlineAt', { time: formatMoment(card.reviewDeadlineAt) })}</span>
      </div>
      {notice ? <Notice kind={notice.kind}>{notice.text}</Notice> : null}

      <h2>{t('moderation.application.profile')}</h2>
      <dl className={styles.facts}>
        <dt>{t('application.field.sellerType')}</dt>
        <dd>{sellerTypeLabel(card.sellerType)}</dd>
        <dt>{t('application.field.name')}</dt>
        <dd>{card.name}</dd>
        <dt>{t('application.field.recipientName')}</dt>
        <dd>{card.payoutDetails.recipientName}</dd>
        <dt>{t('application.field.bankName')}</dt>
        <dd>{card.payoutDetails.bankName}</dd>
        <dt>{t('application.field.bik')}</dt>
        <dd>{card.payoutDetails.bik}</dd>
        <dt>{t('application.field.accountNumber')}</dt>
        <dd>{card.payoutDetails.accountNumber}</dd>
        <dt>{t('application.field.assortment')}</dt>
        <dd className={styles.text}>{card.assortmentDescription}</dd>
      </dl>

      <Documents documents={card.documents} clockOffset={clockOffset} refreshing={query.isFetching} onRefresh={() => void query.refetch()} />

      {pending ? (
        <DecisionPanel
          subjectId={id}
          path={`/api/v1/staff/seller-applications/${encodeURIComponent(id)}`}
          options={options()}
          statusLabel={sellerStatusLabel}
          onDecided={(text) => {
            setNotice({ kind: 'success', text });
            void reload();
          }}
          onConflict={(text) => {
            setNotice({ kind: 'error', text });
            void reload();
          }}
        />
      ) : (
        <Notice kind="info">{t('moderation.application.finished', { status: sellerStatusLabel(card.status) })}</Notice>
      )}
    </section>
  );
}
