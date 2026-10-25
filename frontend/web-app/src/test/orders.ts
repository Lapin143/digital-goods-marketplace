/** Заказ для тестов экранов: поля по схеме Order (docs/06-api/openapi/order-service.yaml), моменты задаются смещением от «сейчас» в минутах. */
export interface OrderFixtureOptions {
  status?: string;
  cancelReason?: string | null;
  reserveInMinutes?: number | null;
  paidMinutesAgo?: number | null;
  issued?: boolean;
  orderId?: string;
  orderNumber?: number;
  [extra: string]: unknown;
}

const MINUTE = 60_000;

export function orderFixture(options: OrderFixtureOptions = {}): Record<string, unknown> {
  const { status = 'awaiting_payment', cancelReason = null, reserveInMinutes = 12, paidMinutesAgo = null, issued = false, orderId = 'o-1', orderNumber = 1042, ...extra } = options;
  const now = Date.now();
  const at = (offset: number) => new Date(now + offset).toISOString();
  return {
    orderId,
    orderNumber,
    productId: 'p-1',
    productTitle: 'Лунная тропа: ключ Steam',
    quantity: 2,
    unitPrice: { amount: 149900, currency: 'RUB' },
    total: { amount: 299800, currency: 'RUB' },
    status,
    cancelReason,
    delivery: { channel: 'email', address: 'buyer@mail.example', masked: false },
    reserveExpiresAt: reserveInMinutes === null ? null : at(reserveInMinutes * MINUTE),
    paymentSessionExpiresAt: reserveInMinutes === null ? null : at((reserveInMinutes - 3) * MINUTE),
    createdAt: at(-20 * MINUTE),
    paidAt: paidMinutesAgo === null ? null : at(-paidMinutesAgo * MINUTE),
    issuedAt: issued ? at(-MINUTE) : null,
    ...extra,
  };
}
