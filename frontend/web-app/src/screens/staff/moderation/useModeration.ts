import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { useApi } from '../../../api/ApiContext';
import { serverClockOffset } from '../../../api/clock';
import type { QueuePage } from './types';

export const QUEUE_PAGE_SIZE = 20;

/** Очередь страницами по курсору: старые заявки выше, порядок задаёт сервер. */
export function useQueue<T>(kind: 'applications' | 'products', path: string) {
  const api = useApi();
  return useInfiniteQuery({
    queryKey: ['moderation-queue', kind],
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam, signal }) => {
      const response = await api.get<QueuePage<T>>(path, { auth: 'staff', query: { limit: QUEUE_PAGE_SIZE, cursor: pageParam }, signal });
      return response.data;
    },
    getNextPageParam: (last) => last.page.nextCursor ?? undefined,
  });
}

/** Карточка и расхождение часов сервера и браузера: срок ссылок на документы считается по часам сервера. */
export interface CardData<T> {
  card: T;
  clockOffset: number;
}

/**
 * Карточка для решения. Каждое открытие карточки заявки сервер пишет в журнал аудита (ссылки на документы), поэтому она не
 * обновляется сама при возврате в окно (это выключено для всего приложения), только по кнопке «Обновить карточку» и после решения.
 */
export function useCard<T>(kind: 'application' | 'product', id: string, path: string) {
  const api = useApi();
  return useQuery<CardData<T>>({
    queryKey: ['moderation-card', kind, id],
    queryFn: async ({ signal }) => {
      const response = await api.get<T>(path, { auth: 'staff', signal });
      return { card: response.data, clockOffset: serverClockOffset(response.headers, Date.now()) };
    },
  });
}
