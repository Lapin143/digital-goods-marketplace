import type { components as PlatformComponents } from '../../../api/generated/platform';

// Типы берутся из OpenAPI (npm run gen:api): расхождение интерфейса и контракта ломает проверку типов
export type StaffSupportTicket = PlatformComponents['schemas']['StaffSupportTicket'];
export type StaffSupportTicketPage = PlatformComponents['schemas']['StaffSupportTicketPage'];
export type StartEmailChangeRequest = PlatformComponents['schemas']['StartEmailChangeRequest'];
export type ResendAccepted = PlatformComponents['schemas']['ResendAccepted'];
export type EmailChangeState = PlatformComponents['schemas']['EmailChangeState'];
