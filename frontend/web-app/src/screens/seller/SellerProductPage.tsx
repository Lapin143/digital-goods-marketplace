import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useApi } from '../../api/ApiContext';
import { fieldMessages, problemMessage } from '../../api/problem';
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
import { formatMoment } from '../../ui/format';
import { productTypeLabel } from '../storefront/labels';
import { PRODUCT_TYPE_VALUES, isLocked, needsRemoderation, toProductInput, validateProduct, valuesFromProduct } from './product';
import type { ProductErrors, ProductValues } from './product';
import { productStatusLabel, productStatusTone } from './labels';
import type { SellerProduct } from './types';
import styles from './SellerProducts.module.css';

type Message = { kind: 'error' | 'success' | 'info'; text: string } | undefined;
type FieldTexts = Partial<Record<keyof ProductValues, string>>;
type Busy = 'save' | 'submit' | 'archive' | 'restore' | undefined;

interface Loaded {
  product: SellerProduct;
  etag: string | undefined;
}

/** Поле формы по первому сегменту указателя ошибки сервера: `/price/amount` это цена, `/activationRegions/0` это регионы. */
const FIELD_OF_POINTER: Record<string, keyof ProductValues | undefined> = {
  title: 'title',
  description: 'description',
  productType: 'productType',
  platform: 'platform',
  activationRegions: 'regions',
  price: 'price',
};

const ARCHIVABLE = new Set(['draft', 'on_moderation', 'published', 'rejected']);
const SUBMITTABLE = new Set(['draft', 'rejected', 'blocked']);

function texts(errors: ProductErrors): FieldTexts {
  const result: FieldTexts = {};
  for (const [field, key] of Object.entries(errors) as Array<[keyof ProductValues, TextKey]>) {
    result[field] = t(key);
  }
  return result;
}

/**
 * Форма товара. Правка уходит с `If-Match` (версия из `ETag`): если товар изменился с момента загрузки, сервер ответит 412
 * и экран предложит обновить данные. Опубликованный товар при правке названия, описания или типа уходит на повторную модерацию
 * (T6): об этом предупреждает подсказка.
 */
function ProductForm({ loaded, onChange }: { loaded: Loaded | undefined; onChange: (next: Loaded) => void }) {
  const api = useApi();
  const keys = useRequestKeys();
  const navigate = useNavigate();
  const product = loaded?.product;
  const locked = isLocked(product?.status);

  const [values, setValues] = useState<ProductValues>(() => valuesFromProduct(product));
  const [fieldTexts, setFieldTexts] = useState<FieldTexts>({});
  const [message, setMessage] = useState<Message>(undefined);
  const [busy, setBusy] = useState<Busy>(undefined);

  const version = product?.version;
  useEffect(() => {
    setValues(valuesFromProduct(product));
    // product берётся из того же обновления, что и version
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [version]);

  function set<K extends keyof ProductValues>(field: K, value: ProductValues[K]) {
    setValues((current) => ({ ...current, [field]: value }));
  }

  function failure(error: unknown) {
    const mapped: FieldTexts = {};
    for (const [name, text] of Object.entries(fieldMessages(error))) {
      const field = FIELD_OF_POINTER[name.split('.')[0] ?? name];
      if (field) {
        mapped[field] = text;
      }
    }
    setFieldTexts(mapped);
    setMessage({ kind: 'error', text: problemMessage(error) });
  }

  async function save() {
    const errors = validateProduct(values);
    setFieldTexts(texts(errors));
    if (Object.keys(errors).length > 0) {
      setMessage({ kind: 'error', text: t('problem.validation-failed') });
      return;
    }
    setBusy('save');
    setMessage(undefined);
    const body = toProductInput(values);
    try {
      if (!loaded || !product) {
        const response = await api.post<SellerProduct>('/api/v1/seller/products', body, { auth: 'staff', idempotencyKey: keys.keyFor(JSON.stringify(body)) });
        keys.settle();
        onChange({ product: response.data, etag: response.etag });
        navigate(`/seller/products/${encodeURIComponent(response.data.productId)}`, { replace: true });
        return;
      }
      const response = await api.put<SellerProduct>(`/api/v1/seller/products/${encodeURIComponent(product.productId)}`, body, {
        auth: 'staff',
        ifMatch: loaded.etag ?? `"${product.version}"`,
      });
      onChange({ product: response.data, etag: response.etag });
      setMessage({ kind: 'success', text: response.data.status === 'on_moderation' ? t('seller.product.savedToModeration') : t('seller.product.saved') });
    } catch (error) {
      keys.settle(error);
      failure(error);
    } finally {
      setBusy(undefined);
    }
  }

  async function transition(action: 'submit' | 'archive' | 'restore') {
    if (!product) {
      return;
    }
    setBusy(action);
    setMessage(undefined);
    try {
      const response = await api.post<SellerProduct>(`/api/v1/seller/products/${encodeURIComponent(product.productId)}/${action}`, undefined, {
        auth: 'staff',
        idempotencyKey: keys.keyFor(`${action}:${product.productId}:${product.version}`),
      });
      keys.settle();
      // Версия после перехода меняется, ETag берём заново вместе со страницей
      onChange({ product: response.data, etag: undefined });
      setMessage({ kind: 'success', text: t(`seller.product.done.${action}`) });
    } catch (error) {
      keys.settle(error);
      failure(error);
    } finally {
      setBusy(undefined);
    }
  }

  const remoderation = product?.status === 'published' && needsRemoderation(product, values);

  return (
    <section>
      <p>
        <Link to="/seller/products">{t('seller.product.back')}</Link>
      </p>
      <h1>{product ? product.title : t('seller.product.newTitle')}</h1>
      {product ? (
        <div className={styles.meta}>
          <Badge tone={productStatusTone(product.status)}>{productStatusLabel(product.status)}</Badge>
          {product.submittedAt && product.status === 'on_moderation' ? <span>{t('seller.product.reviewUntil', { time: formatMoment(product.reviewDeadlineAt) })}</span> : null}
          <Link to={`/seller/products/${encodeURIComponent(product.productId)}/keys`}>{t('seller.product.keysLink')}</Link>
        </div>
      ) : null}
      {product?.status === 'rejected' && product.rejectionReason ? (
        <Notice kind="error">{t('seller.product.rejectedBecause', { reason: product.rejectionReason })}</Notice>
      ) : null}
      {product?.status === 'blocked' && product.blockReason ? <Notice kind="error">{t('seller.product.blockedBecause', { reason: product.blockReason })}</Notice> : null}
      {locked ? <Notice kind="info">{t(product?.status === 'archived' ? 'seller.product.lockedArchived' : 'seller.product.lockedModeration')}</Notice> : null}
      {message ? <Notice kind={message.kind}>{message.text}</Notice> : null}

      <form
        className={styles.form}
        noValidate
        onSubmit={(event: FormEvent) => {
          event.preventDefault();
          void save();
        }}
      >
        <fieldset className={styles.fieldset} disabled={locked}>
          <TextField label={t('seller.product.title')} maxLength={200} value={values.title} error={fieldTexts.title} onChange={(event) => set('title', event.target.value)} />
          <TextAreaField
            label={t('seller.product.description')}
            maxLength={5000}
            value={values.description}
            error={fieldTexts.description}
            onChange={(event) => set('description', event.target.value)}
          />
          <SelectField
            label={t('seller.product.type')}
            placeholder={t('seller.product.typeSelect')}
            options={PRODUCT_TYPE_VALUES.map((type) => ({ value: type, label: productTypeLabel(type) }))}
            value={values.productType}
            error={fieldTexts.productType}
            onChange={(event) => set('productType', event.target.value)}
          />
          <TextField
            label={t('seller.product.platform')}
            hint={t('seller.product.platform.hint')}
            maxLength={64}
            value={values.platform}
            error={fieldTexts.platform}
            onChange={(event) => set('platform', event.target.value)}
          />
          <TextField
            label={t('seller.product.regions')}
            hint={t('seller.product.regions.hint')}
            value={values.regions}
            error={fieldTexts.regions}
            onChange={(event) => set('regions', event.target.value)}
          />
          <TextField
            label={t('seller.product.price')}
            hint={remoderation ? t('seller.product.remoderation') : t('seller.product.price.hint')}
            inputMode="decimal"
            value={values.price}
            error={fieldTexts.price}
            onChange={(event) => set('price', event.target.value)}
          />
        </fieldset>
        {remoderation ? <Notice kind="info">{t('seller.product.remoderation')}</Notice> : null}

        <div className={styles.actions}>
          {!locked ? (
            <Button type="submit" busy={busy === 'save'} disabled={busy !== undefined}>
              {t('seller.product.save')}
            </Button>
          ) : null}
          {product && SUBMITTABLE.has(product.status) ? (
            <Button variant="secondary" busy={busy === 'submit'} disabled={busy !== undefined} onClick={() => void transition('submit')}>
              {t('seller.product.submit')}
            </Button>
          ) : null}
          {product && ARCHIVABLE.has(product.status) ? (
            <Button variant="danger" busy={busy === 'archive'} disabled={busy !== undefined} onClick={() => void transition('archive')}>
              {t('seller.product.archive')}
            </Button>
          ) : null}
          {product?.status === 'archived' ? (
            <Button variant="secondary" busy={busy === 'restore'} disabled={busy !== undefined} onClick={() => void transition('restore')}>
              {t('seller.product.restore')}
            </Button>
          ) : null}
        </div>
      </form>
    </section>
  );
}

/** Товар продавца: создание (`/seller/products/new`) и правка (`/seller/products/:productId`). */
export function SellerProductPage() {
  const { productId } = useParams();
  const api = useApi();
  const queryClient = useQueryClient();
  const key = ['seller-product', productId ?? 'new'] as const;
  const query = useQuery<Loaded>({
    queryKey: key,
    enabled: productId !== undefined,
    queryFn: async ({ signal }) => {
      const response = await api.get<SellerProduct>(`/api/v1/seller/products/${encodeURIComponent(productId ?? '')}`, { auth: 'staff', signal });
      return { product: response.data, etag: response.etag };
    },
  });

  if (productId === undefined) {
    return <ProductForm loaded={undefined} onChange={(next) => queryClient.setQueryData(['seller-product', next.product.productId], next)} />;
  }
  if (query.isPending) {
    return <StatePanel kind="loading" />;
  }
  if (query.isError) {
    return <StatePanel kind="error" message={problemMessage(query.error)} onRetry={() => void query.refetch()} />;
  }
  return (
    <ProductForm
      loaded={query.data}
      onChange={(next) => {
        queryClient.setQueryData(key, next);
        // После перехода статуса ETag меняется: следующая правка должна взять свежий
        void queryClient.invalidateQueries({ queryKey: ['seller-products'] });
        if (next.etag === undefined) {
          void queryClient.invalidateQueries({ queryKey: key });
        }
      }}
    />
  );
}
