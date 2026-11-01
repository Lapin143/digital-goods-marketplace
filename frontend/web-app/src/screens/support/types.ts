import type { components as PlatformComponents } from '../../api/generated/platform';

// Типы берутся из OpenAPI (npm run gen:api): расхождение интерфейса и контракта ломает проверку типов
export type SupportTicket = PlatformComponents['schemas']['SupportTicket'];
export type SupportTicketPage = PlatformComponents['schemas']['SupportTicketPage'];
export type EmailChangeState = PlatformComponents['schemas']['EmailChangeState'];
export type CreateSupportTicketRequest = PlatformComponents['schemas']['CreateSupportTicketRequest'];
export type RequestEmailChangeCodeRequest = PlatformComponents['schemas']['RequestEmailChangeCodeRequest'];
export type VerifyEmailChangeCodeRequest = PlatformComponents['schemas']['VerifyEmailChangeCodeRequest'];
export type EmailChangeStep = VerifyEmailChangeCodeRequest['step'];
