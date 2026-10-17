import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { useApi } from '../../api/ApiContext';
import type { Query } from '../../api/client';
import { PAGE_SIZE } from './filters';
import type { StorefrontPage, StorefrontProduct } from './types';

/**
 * Список товаров постранично по курсору. Курсор непрозрачный: интерфейс возвращает его серверу как есть и не пытается разбирать.
 * Страницы кэшируются по набору фильтров, поэтому возврат к прежнему поиску показывает результат сразу.
 */
export function useProductList(query: Query) {
  const api = useApi();
  return useInfiniteQuery({
    queryKey: ['storefront', 'products', query],
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam, signal }) => {
      const response = await api.get<StorefrontPage>('/api/v1/products', {
        auth: 'none',
        query: { ...query, limit: PAGE_SIZE, cursor: pageParam },
        signal,
      });
      return response.data;
    },
    getNextPageParam: (last) => last.page.nextCursor ?? undefined,
  });
}

/**
 * Карточка товара. Условный запрос по `ETag` делает сам браузер: повторный запрос уходит с `If-None-Match`, на `304` он отдаёт
 * сохранённое тело, и интерфейсу видно обычный ответ 200.
 */
export function useProduct(productId: string) {
  const api = useApi();
  return useQuery({
    queryKey: ['storefront', 'product', productId],
    queryFn: async ({ signal }) => {
      const response = await api.get<StorefrontProduct>(`/api/v1/products/${encodeURIComponent(productId)}`, { auth: 'none', signal });
      return response.data;
    },
  });
}
