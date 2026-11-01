import type { components as PlatformComponents } from '../../../api/generated/platform';

// Типы берутся из OpenAPI (npm run gen:api): расхождение интерфейса и контракта ломает проверку типов
export type PlatformParameter = PlatformComponents['schemas']['PlatformParameter'];
export type PlatformParameterList = PlatformComponents['schemas']['PlatformParameterList'];
export type AuditRecord = PlatformComponents['schemas']['AuditRecord'];
export type AuditRecordPage = PlatformComponents['schemas']['AuditRecordPage'];
