package dgm.payment.payments.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Платёж по заказу (SM-02, INV-11, INV-14). Статус меняет только ответ или уведомление шлюза либо отметка администратора.
 *
 * @param id                идентификатор платежа
 * @param orderId           заказ; один заказ оплачивается одним платежом
 * @param gatewayPaymentId  внешний идентификатор у шлюза или {@code null}, пока шлюз не ответил
 * @param amount            сумма в копейках, равна сумме заказа
 * @param currency          валюта, в R1 только RUB
 * @param sessionUrl        адрес страницы оплаты у шлюза или {@code null}
 * @param sessionExpiresAt  срок платёжной сессии или {@code null}
 * @param status            {@link #CREATED}, {@link #CONFIRMED}, {@link #REJECTED} или {@link #REFUNDED}
 */
public record Payment(UUID id, UUID orderId, String gatewayPaymentId, long amount, String currency, String sessionUrl,
                      Instant sessionExpiresAt, String status) {

    public static final String CREATED = "created";
    public static final String CONFIRMED = "confirmed";
    public static final String REJECTED = "rejected";
    public static final String REFUNDED = "refunded";

    /** Шлюз ответил и сессия записана: повторный вызов по заказу может вернуть её, не обращаясь к шлюзу. */
    public boolean hasSession() {
        return gatewayPaymentId != null && sessionUrl != null && sessionExpiresAt != null;
    }

    public boolean isCreated() {
        return CREATED.equals(status);
    }
}
