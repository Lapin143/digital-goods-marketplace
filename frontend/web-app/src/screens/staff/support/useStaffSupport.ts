import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { useApi } from '../../../api/ApiContext';
import { STAFF_SUPPORT_TICKETS, staffTicketPath } from '../../../api/support';
import type { StaffSupportTicket, StaffSupportTicketPage } from './types';

export const SUPPORT_QUEUE_PAGE_SIZE = 20;
/** Очередь обновляется сама без перезагрузки страницы (US-7.2, шаг 19.1). */
export const QUEUE_REFRESH_MS = 15_000;
/** Карточка обновляется реже, а пока выдача «в очереди», чаще: результат повторной отправки оператор ждёт. */
export const CARD_REFRESH_MS = 15_000;
export const CARD_REFRESH_DELIVERY_MS = 5_000;

export const SUPPORT_QUEUE_KEY = ['support-queue'] as const;
export const SUPPORT_CARD_KEY = ['support-card'] as const;

export type QueueFilter = 'queue' | 'mine';

export interface QueueSearch {
  filter: QueueFilter;
  /** Короткий номер заказа: ищет обращения заказа в любом статусе, фильтр статуса тогда не применяется. */
  orderNumber: number | undefined;
  /** Идентификатор текущего оператора (`sub` токена): по нему отбираются «мои». */
  operatorId: string;
}

function queueQuery(search: QueueSearch): Record<string, string | number> {
  if (search.orderNumber !== undefined) {
    return { orderNumber: search.orderNumber };
  }
  return search.filter === 'mine' ? { status: 'in_progress', operatorId: search.operatorId } : { status: 'created' };
}

/** Очередь страницами по курсору, старые выше (порядок задаёт сервер); обновляется по таймеру. */
export function useSupportQueue(search: QueueSearch) {
  const api = useApi();
  return useInfiniteQuery({
    queryKey: [...SUPPORT_QUEUE_KEY, search.filter, search.orderNumber ?? null, search.operatorId],
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam, signal }) => {
      const response = await api.get<StaffSupportTicketPage>(STAFF_SUPPORT_TICKETS, {
        auth: 'staff',
        query: { ...queueQuery(search), limit: SUPPORT_QUEUE_PAGE_SIZE, cursor: pageParam },
        signal,
      });
      return response.data;
    },
    getNextPageParam: (last) => last.page.nextCursor ?? undefined,
    refetchInterval: QUEUE_REFRESH_MS,
  });
}

/** Карточка обращения для оператора: заказ, выдача, состояние смены e-mail. Ключей и кодов в ответе нет. */
export function useStaffTicket(ticketId: string) {
  const api = useApi();
  return useQuery({
    queryKey: [...SUPPORT_CARD_KEY, ticketId],
    queryFn: async ({ signal }) => (await api.get<StaffSupportTicket>(staffTicketPath(ticketId), { auth: 'staff', signal })).data,
    refetchInterval: (query) => {
      const ticket = query.state.data;
      if (!ticket || ticket.status === 'resolved') {
        return false;
      }
      return ticket.deliveryStatus === 'queued' ? CARD_REFRESH_DELIVERY_MS : CARD_REFRESH_MS;
    },
  });
}
