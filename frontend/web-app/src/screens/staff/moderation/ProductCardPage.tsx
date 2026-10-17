import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { problemMessage } from '../../../api/problem';
import { t } from '../../../i18n';
import { Badge } from '../../../ui/Badge';
import { Notice } from '../../../ui/Notice';
import { StatePanel } from '../../../ui/StatePanel';
import { formatMoment } from '../../../ui/format';
import { productStatusLabel, productStatusTone } from '../../seller/labels';
import { productTypeLabel } from '../../storefront/labels';
import { formatMoney } from '../../storefront/money';
import { DecisionPanel } from './DecisionPanel';
import type { DecisionOption } from './DecisionPanel';
import type { ModerationProduct } from './types';
import { useCard } from './useModeration';
import styles from './Moderation.module.css';

function approveReject(): DecisionOption[] {
  return [
    { id: 'approve', label: t('moderation.product.approve'), variant: 'primary', confirm: t('moderation.product.approve.confirm'), done: t('moderation.product.approve.done') },
    {
      id: 'reject',
      label: t('moderation.product.reject'),
      variant: 'danger',
      text: { field: 'reason', label: t('moderation.product.reject.reason') },
      confirm: t('moderation.product.reject.confirm'),
      done: t('moderation.product.reject.done'),
    },
  ];
}

function block(): DecisionOption[] {
  return [
    {
      id: 'block',
      label: t('moderation.product.block'),
      variant: 'danger',
      text: { field: 'reason', label: t('moderation.product.block.reason') },
      confirm: t('moderation.product.block.confirm'),
      done: t('moderation.product.block.done'),
    },
  ];
}

/** Карточка товара на модерации (OP-22 – OP-24): опубликовать, отклонить с причиной, заблокировать опубликованный с причиной. */
export function ProductCardPage() {
  const { productId } = useParams();
  const id = productId ?? '';
  const queryClient = useQueryClient();
  const query = useCard<ModerationProduct>('product', id, `/api/v1/staff/products/${encodeURIComponent(id)}`);
  const [notice, setNotice] = useState<{ kind: 'success' | 'error'; text: string } | undefined>(undefined);

  async function reload() {
    await queryClient.invalidateQueries({ queryKey: ['moderation-card', 'product', id] });
    void queryClient.invalidateQueries({ queryKey: ['moderation-queue', 'products'] });
  }

  if (query.isPending) {
    return <StatePanel kind="loading" />;
  }
  if (query.isError) {
    return <StatePanel kind="error" message={problemMessage(query.error)} onRetry={() => void query.refetch()} />;
  }
  const product = query.data.card;
  const options = product.status === 'on_moderation' ? approveReject() : product.status === 'published' ? block() : [];
  return (
    <section>
      <p>
        <Link to="/staff/products">{t('moderation.products.back')}</Link>
      </p>
      <h1>{product.title}</h1>
      <div className={styles.meta}>
        <Badge tone={productStatusTone(product.status)}>{productStatusLabel(product.status)}</Badge>
        {product.submittedAt ? <span>{t('moderation.submittedAt', { time: formatMoment(product.submittedAt) })}</span> : null}
        {product.reviewDeadlineAt && product.status === 'on_moderation' ? <span>{t('moderation.deadlineAt', { time: formatMoment(product.reviewDeadlineAt) })}</span> : null}
      </div>
      {notice ? <Notice kind={notice.kind}>{notice.text}</Notice> : null}

      <dl className={styles.facts}>
        <dt>{t('seller.product.type')}</dt>
        <dd>{productTypeLabel(product.productType)}</dd>
        <dt>{t('seller.product.platform')}</dt>
        <dd>{product.platform}</dd>
        <dt>{t('seller.product.regions')}</dt>
        <dd>{product.activationRegions.length > 0 ? product.activationRegions.join(', ') : t('moderation.product.noRegions')}</dd>
        <dt>{t('moderation.col.price')}</dt>
        <dd>{formatMoney(product.price)}</dd>
        <dt>{t('moderation.product.seller')}</dt>
        <dd>{product.sellerId}</dd>
        <dt>{t('seller.product.description')}</dt>
        <dd className={styles.text}>{product.description}</dd>
        {product.rejectionReason ? (
          <>
            <dt>{t('moderation.product.rejectionReason')}</dt>
            <dd className={styles.text}>{product.rejectionReason}</dd>
          </>
        ) : null}
        {product.blockReason ? (
          <>
            <dt>{t('moderation.product.blockReason')}</dt>
            <dd className={styles.text}>{product.blockReason}</dd>
          </>
        ) : null}
      </dl>

      {options.length > 0 ? (
        <DecisionPanel
          subjectId={id}
          path={`/api/v1/staff/products/${encodeURIComponent(id)}`}
          options={options}
          statusLabel={productStatusLabel}
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
        <Notice kind="info">{t('moderation.product.finished', { status: productStatusLabel(product.status) })}</Notice>
      )}
    </section>
  );
}
