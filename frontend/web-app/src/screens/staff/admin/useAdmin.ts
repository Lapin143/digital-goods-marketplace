import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { useApi } from '../../../api/ApiContext';
import { AUDIT_RECORDS, PARAMETERS } from '../../../api/admin';
import type { AuditRecordPage, PlatformParameter, PlatformParameterList } from './types';

export const PARAMETERS_KEY = ['admin-parameters'] as const;
export const AUDIT_KEY = ['admin-audit'] as const;
export const AUDIT_PAGE_SIZE = 20;

/** Все параметры платформы: список короткий, без страниц. Версия параметра это его ETag для правки. */
export function useParameters() {
  const api = useApi();
  return useQuery<PlatformParameter[]>({
    queryKey: PARAMETERS_KEY,
    queryFn: async ({ signal }) => (await api.get<PlatformParameterList>(PARAMETERS, { auth: 'staff', signal })).data.items,
  });
}

/** Условия выборки журнала в том виде, в каком они уходят в запрос: пустая строка значит «условия нет», время в формате RFC 3339. */
export interface AuditFilters {
  actorId: string;
  objectType: string;
  objectId: string;
  action: string;
  occurredFrom: string;
  occurredTo: string;
}

export const NO_FILTERS: AuditFilters = { actorId: '', objectType: '', objectId: '', action: '', occurredFrom: '', occurredTo: '' };

/**
 * Журнал аудита страницами по курсору, новые выше. Чтение первой страницы само пишется в журнал («просмотр журнала», Р-166), поэтому данные
 * не обновляются сами ни по таймеру, ни при возврате в окно, ни при повторном открытии из кэша: новое чтение журнала это новая запись в нём.
 * Автоповтора при сбое нет, чтобы не оставить лишних записей; повторяет администратор кнопкой.
 */
export function useAuditRecords(filters: AuditFilters) {
  const api = useApi();
  return useInfiniteQuery({
    queryKey: [...AUDIT_KEY, filters],
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam, signal }) => {
      const response = await api.get<AuditRecordPage>(AUDIT_RECORDS, {
        auth: 'staff',
        query: { ...filters, limit: AUDIT_PAGE_SIZE, cursor: pageParam },
        signal,
      });
      return response.data;
    },
    getNextPageParam: (last) => last.page.nextCursor ?? undefined,
    staleTime: Infinity,
    gcTime: 0,
    refetchOnReconnect: false,
    retry: false,
  });
}
