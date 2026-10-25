import type { components as OrderComponents } from '../../api/generated/order';

// Типы берутся из OpenAPI (npm run gen:api): расхождение интерфейса и контракта ломает проверку типов
export type Order = OrderComponents['schemas']['Order'];
export type OrderWithPaymentSession = OrderComponents['schemas']['OrderWithPaymentSession'];
export type OrderPage = OrderComponents['schemas']['OrderPage'];
export type CreateOrderRequest = OrderComponents['schemas']['CreateOrderRequest'];
export type OrderStatus = Order['status'];
export type PaymentSession = OrderComponents['schemas']['PaymentSession'];
