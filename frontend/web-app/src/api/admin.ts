/** Параметры платформы и журнал аудита администратора (роль `admin`, второй фактор обязателен, области `staff.admin` и `staff.audit`). */
export const PARAMETERS = '/api/v1/staff/parameters';

export function parameterPath(key: string): string {
  return `${PARAMETERS}/${encodeURIComponent(key)}`;
}

export const AUDIT_RECORDS = '/api/v1/staff/audit-records';
