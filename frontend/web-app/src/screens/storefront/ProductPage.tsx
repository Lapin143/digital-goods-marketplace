import { Link, useParams } from 'react-router-dom';
import { ApiError, problemMessage } from '../../api/problem';
import { t } from '../../i18n';
import { Button } from '../../ui/Button';
import { StatePanel } from '../../ui/StatePanel';
import { productTypeLabel } from './labels';
import { formatMoney } from './money';
import { useProduct } from './useStorefront';
import styles from './ProductPage.module.css';

/**
 * Карточка товара. Наличие берётся из `maxOrderQuantity`: ноль означает «нет в наличии» (INV-09, INV-10).
 * Описание от продавца показывается текстом, а не разметкой: React экранирует всё, что внутри.
 */
export function ProductPage() {
  const { productId = '' } = useParams();
  const product = useProduct(productId);

  if (product.isPending) {
    return <StatePanel kind="loading" />;
  }
  if (product.isError) {
    if (product.error instanceof ApiError && product.error.status === 404) {
      return (
        <section>
          <h1>{t('product.notFound.title')}</h1>
          <p>{t('product.notFound.text')}</p>
          <Link to="/products">{t('product.back')}</Link>
        </section>
      );
    }
    return <StatePanel kind="error" message={problemMessage(product.error)} onRetry={() => void product.refetch()} />;
  }

  const item = product.data;
  const available = item.inStock && item.maxOrderQuantity > 0;
  return (
    <article>
      <Link className={styles.back} to="/products">
        {t('product.back')}
      </Link>
      <h1>{item.title}</h1>
      <p className={styles.price}>{formatMoney(item.price)}</p>
      <dl className={styles.facts}>
        <dt>{t('product.type')}</dt>
        <dd>{productTypeLabel(item.productType)}</dd>
        <dt>{t('product.platform')}</dt>
        <dd>{item.platform}</dd>
        <dt>{t('product.regions')}</dt>
        <dd>{item.activationRegions.length === 0 ? t('product.regions.none') : item.activationRegions.join(', ')}</dd>
        <dt>{t('product.stock')}</dt>
        <dd className={available ? styles.stock : `${styles.stock} ${styles.out}`}>
          {available ? t('product.orderUpTo', { count: item.maxOrderQuantity }) : t('catalog.outOfStock')}
        </dd>
      </dl>
      <p>
        <Button variant="secondary" busy={product.isFetching} onClick={() => void product.refetch()}>
          {t('product.refresh')}
        </Button>
      </p>
      <h2>{t('product.description')}</h2>
      <p className={styles.description}>{item.description}</p>
    </article>
  );
}
