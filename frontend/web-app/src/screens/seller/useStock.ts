import { useQuery } from '@tanstack/react-query';
import { useApi } from '../../api/ApiContext';
import type { StockInfo } from './types';

export function stockKey(productId: string) {
  return ['seller-stock', productId] as const;
}

export function stockPath(productId: string): string {
  return `/api/v1/seller/products/${encodeURIComponent(productId)}`;
}

/** Остаток товара (OP-39). Один запрос на экран и для пула ключей, и для остатка по API: отличаются полями ответа. */
export function useStock(productId: string) {
  const api = useApi();
  return useQuery({
    queryKey: stockKey(productId),
    enabled: productId !== '',
    queryFn: async ({ signal }) => {
      const response = await api.get<StockInfo>(`${stockPath(productId)}/stock`, { auth: 'staff', signal });
      return response.data;
    },
  });
}
