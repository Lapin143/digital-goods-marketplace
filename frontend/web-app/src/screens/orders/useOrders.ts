import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { useApi } from '../../api/ApiContext';
import { serverClockOffset } from '../../api/clock';
import { isFinalStatus, pollDelay } from './polling';
import type { Order, OrderPage } from './types';

/** Страница истории: заказы показываются по двадцать, дальше по курсору (US-5.9). */
export const ORDERS_PAGE_SIZE = 20;

export interface OrderView {
  order: Order;
  /** Расхождение часов сервера и браузера на момент ответа: от него считаются сроки резерва. */
  clockOffset: number;
}

/**
 * Карточка заказа с опросом статуса. Пока заказ не в конечном состоянии, запрос повторяется с нарастающей паузой
 * (оплата проходит вебхуком, а не возвратом со страницы шлюза, ADR-015). Нет данных (первая загрузка не удалась):
 * опрос не идёт, решает кнопка «Повторить». Фоновая вкладка не опрашивает: так устроен `refetchIntervalInBackground`.
 */
export function useOrder(orderId: string) {
  const api = useApi();
  return useQuery({
    queryKey: ['orders', 'order', orderId],
    queryFn: async ({ signal }): Promise<OrderView> => {
      const response = await api.get<Order>(`/api/v1/orders/${encodeURIComponent(orderId)}`, { signal });
      return { order: response.data, clockOffset: serverClockOffset(response.headers, Date.now()) };
    },
    refetchInterval: (query) => {
      const view = query.state.data;
      return view && !isFinalStatus(view.order.status) ? pollDelay(query.state.dataUpdateCount) : false;
    },
  });
}

/** История заказов постранично по курсору; курсор непрозрачный и возвращается серверу как есть. */
export function useOrderList() {
  const api = useApi();
  return useInfiniteQuery({
    queryKey: ['orders', 'list'],
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam, signal }) => {
      const response = await api.get<OrderPage>('/api/v1/orders', { query: { limit: ORDERS_PAGE_SIZE, cursor: pageParam }, signal });
      return response.data;
    },
    getNextPageParam: (last) => last.page.nextCursor ?? undefined,
  });
}
