package dgm.payment.payments.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Обработанные уведомления шлюза ({@code payments.payment_event}). Первичный ключ «внешний идентификатор платежа, тип события» это
 * дедупликация (INV-14, ADR-006): повтор не вставляется, и действие по нему второй раз не выполняется.
 */
public interface PaymentEventRepository {

    /** Исходы обработки, допустимые таблицей. */
    String APPLIED = "applied";
    String AMOUNT_MISMATCH = "amount_mismatch";
    String ANOMALY = "anomaly";

    /** Пара «платёж, тип события» уже обработана. */
    boolean exists(String gatewayPaymentId, String eventType);

    /**
     * Записывает обработанное уведомление.
     *
     * @return {@code true}, если запись сделана этим вызовом; {@code false}, если такая пара уже есть (повтор)
     */
    boolean insertIfAbsent(String gatewayPaymentId, String eventType, String gatewayEventId, UUID paymentId, String outcome, Instant occurredAt,
                           Instant receivedAt);
}
