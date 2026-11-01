import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { useApi } from '../../api/ApiContext';
import { SUPPORT_TICKETS, ticketPath } from '../../api/support';
import { isOpenTicket } from './labels';
import type { SupportTicket, SupportTicketPage } from './types';

/** Список обращений: по двадцать, дальше по курсору. */
export const TICKETS_PAGE_SIZE = 20;

/** Как часто карточка обращения спрашивает сервер, пока обращение не решено (оператор берёт в работу, начинает смену e-mail, письмо доходит). */
export const TICKET_POLL_MS = 10_000;

/** Корень ключей запросов обращений: после создания обращения сбрасывается всё, что с ним связано. */
export const SUPPORT_QUERY_KEY = ['support'] as const;

/**
 * Открытое обращение по заказу (статус «создано» или «в работе», INV-21): кнопка «Не получил ключ» заменяется ссылкой на него.
 * `null` значит «открытого обращения нет». Ошибка запроса не мешает кнопке: сервер всё равно ответит 409 `ticket-already-open`.
 */
export function useOpenTicketForOrder(orderId: string) {
  const api = useApi();
  return useQuery({
    queryKey: [...SUPPORT_QUERY_KEY, 'order', orderId],
    queryFn: async ({ signal }): Promise<SupportTicket | null> => {
      const response = await api.get<SupportTicketPage>(SUPPORT_TICKETS, {
        query: { orderId, status: 'created,in_progress', limit: 1 },
        signal,
      });
      return response.data.items[0] ?? null;
    },
  });
}

/** Список обращений покупателя, новые выше, по курсору; курсор непрозрачный и возвращается серверу как есть. */
export function useTicketList() {
  const api = useApi();
  return useInfiniteQuery({
    queryKey: [...SUPPORT_QUERY_KEY, 'list'],
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam, signal }) => {
      const response = await api.get<SupportTicketPage>(SUPPORT_TICKETS, { query: { limit: TICKETS_PAGE_SIZE, cursor: pageParam }, signal });
      return response.data;
    },
    getNextPageParam: (last) => last.page.nextCursor ?? undefined,
  });
}

/** Обращение со статусом и состоянием смены e-mail. Пока обращение не решено, запрос повторяется; нет данных (первая загрузка не удалась), опроса нет. */
export function useTicket(ticketId: string) {
  const api = useApi();
  return useQuery({
    queryKey: [...SUPPORT_QUERY_KEY, 'ticket', ticketId],
    queryFn: async ({ signal }) => (await api.get<SupportTicket>(ticketPath(ticketId), { signal })).data,
    refetchInterval: (query) => {
      const ticket = query.state.data;
      return ticket && isOpenTicket(ticket.status) ? TICKET_POLL_MS : false;
    },
  });
}
