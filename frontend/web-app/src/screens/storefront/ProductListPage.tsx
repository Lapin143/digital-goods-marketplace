import { useEffect, useMemo, useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { problemMessage } from '../../api/problem';
import { plural, t } from '../../i18n';
import { Button } from '../../ui/Button';
import { StatePanel } from '../../ui/StatePanel';
import { TextField } from '../../ui/TextField';
import { EMPTY_FILTERS, MAX_PLATFORM_LENGTH, MAX_QUERY_LENGTH, PRODUCT_TYPES, filtersFromParams, filtersToParams, hasFilters, toApiQuery, validateFilters } from './filters';
import type { FilterErrors, Filters } from './filters';
import { productTypeLabel } from './labels';
import { formatMoney } from './money';
import type { StorefrontItem } from './types';
import { useProductList } from './useStorefront';
import styles from './ProductList.module.css';

function ProductCard({ item }: { item: StorefrontItem }) {
  return (
    <li>
      <article className={styles.card}>
        <h2>
          <Link to={`/products/${encodeURIComponent(item.productId)}`}>{item.title}</Link>
        </h2>
        <p className={styles.meta}>
          {`${productTypeLabel(item.productType)}, ${item.platform}`}
        </p>
        <p className={styles.price}>{formatMoney(item.price)}</p>
        <p className={item.inStock ? styles.stock : `${styles.stock} ${styles.out}`}>{item.inStock ? t('catalog.inStock') : t('catalog.outOfStock')}</p>
      </article>
    </li>
  );
}

/**
 * Каталог (шаг 17 Ф4): поиск и фильтры в адресе страницы, список страницами по курсору («Показать ещё»), состояния загрузки, пусто и ошибки.
 * Запрос уходит по кнопке «Показать», а не на каждую букву: поиск по триграммному индексу и лимиты шлюза не нужно перегружать (NFT-1.1).
 */
export function ProductListPage() {
  const [params, setParams] = useSearchParams();
  const applied = useMemo(() => filtersFromParams(params), [params]);
  const [draft, setDraft] = useState<Filters>(applied);
  const [submitted, setSubmitted] = useState(false);

  // Кнопка «назад» и ссылка с фильтрами меняют адрес: поля показывают то, что применено
  useEffect(() => {
    setDraft(applied);
    setSubmitted(false);
  }, [applied]);

  const errors: FilterErrors = submitted ? validateFilters(draft) : {};
  const query = useMemo(() => toApiQuery(applied), [applied]);
  const list = useProductList(query);
  const items = list.data?.pages.flatMap((page) => page.items) ?? [];

  function onSubmit(event: FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    if (Object.keys(validateFilters(draft)).length > 0) {
      return;
    }
    setParams(filtersToParams(draft));
  }

  function toggleType(type: string) {
    setDraft((current) => ({ ...current, types: current.types.includes(type) ? current.types.filter((item) => item !== type) : [...current.types, type] }));
  }

  function reset() {
    setDraft(EMPTY_FILTERS);
    setSubmitted(false);
    setParams(new URLSearchParams());
  }

  return (
    <section>
      <h1>{t('catalog.title')}</h1>
      <div className={styles.layout}>
        <form className={styles.filters} role="search" aria-label={t('catalog.filters.label')} onSubmit={onSubmit} noValidate>
          <TextField
            label={t('catalog.filter.q')}
            hint={t('catalog.filter.q.hint')}
            type="search"
            maxLength={MAX_QUERY_LENGTH}
            value={draft.q}
            onChange={(event) => setDraft({ ...draft, q: event.target.value })}
          />
          <fieldset className={styles.fieldset}>
            <legend className={styles.legend}>{t('catalog.filter.types')}</legend>
            {PRODUCT_TYPES.map((type) => (
              <label key={type} className={styles.check}>
                <input type="checkbox" checked={draft.types.includes(type)} onChange={() => toggleType(type)} />
                {productTypeLabel(type)}
              </label>
            ))}
          </fieldset>
          <TextField
            label={t('catalog.filter.platform')}
            hint={t('catalog.filter.platform.hint')}
            maxLength={MAX_PLATFORM_LENGTH}
            value={draft.platform}
            onChange={(event) => setDraft({ ...draft, platform: event.target.value })}
          />
          <TextField
            label={t('catalog.filter.region')}
            hint={t('catalog.filter.region.hint')}
            maxLength={2}
            autoCapitalize="characters"
            error={errors.region ? t(errors.region) : undefined}
            value={draft.region}
            onChange={(event) => setDraft({ ...draft, region: event.target.value })}
          />
          <div className={styles.prices}>
            <TextField
              label={t('catalog.filter.priceFrom')}
              inputMode="decimal"
              error={errors.priceFrom ? t(errors.priceFrom) : undefined}
              value={draft.priceFrom}
              onChange={(event) => setDraft({ ...draft, priceFrom: event.target.value })}
            />
            <TextField
              label={t('catalog.filter.priceTo')}
              inputMode="decimal"
              error={errors.priceTo ? t(errors.priceTo) : undefined}
              value={draft.priceTo}
              onChange={(event) => setDraft({ ...draft, priceTo: event.target.value })}
            />
          </div>
          <div className={styles.actions}>
            <Button type="submit">{t('catalog.filter.apply')}</Button>
            <Button variant="secondary" onClick={reset}>
              {t('catalog.filter.reset')}
            </Button>
          </div>
        </form>

        <div aria-label={t('catalog.results')} role="region" aria-live="polite" aria-busy={list.isFetching}>
          {list.isPending ? <StatePanel kind="loading" /> : null}
          {list.isError && !list.data ? <StatePanel kind="error" message={problemMessage(list.error)} onRetry={() => void list.refetch()} /> : null}
          {list.data && items.length === 0 ? <StatePanel kind="empty" message={hasFilters(applied) ? t('catalog.empty.filtered') : t('catalog.empty.all')} /> : null}
          {items.length > 0 ? (
            <>
              <p className={styles.count}>{plural(items.length, { one: 'catalog.count.one', few: 'catalog.count.few', many: 'catalog.count.many' })}</p>
              <ul className={styles.grid}>
                {items.map((item) => (
                  <ProductCard key={item.productId} item={item} />
                ))}
              </ul>
              {list.isFetchNextPageError ? <StatePanel kind="error" message={problemMessage(list.error)} onRetry={() => void list.fetchNextPage()} /> : null}
              {list.hasNextPage && !list.isFetchNextPageError ? (
                <Button variant="secondary" busy={list.isFetchingNextPage} onClick={() => void list.fetchNextPage()}>
                  {t('catalog.more')}
                </Button>
              ) : null}
            </>
          ) : null}
        </div>
      </div>
    </section>
  );
}
