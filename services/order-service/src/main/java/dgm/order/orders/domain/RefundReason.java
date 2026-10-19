package dgm.order.orders.domain;

import java.util.Set;

/** Причины возврата денег (схемы {@code OrderRefundRequestedData}, {@code PaymentRefundedData}, {@code OrderRefundedData}). */
public final class RefundReason {

    /** Поздняя оплата без ключей (ADR-013, E7): единственная причина, по которой в R1 возврат запрашивает сам {@code order-service}. */
    public static final String LATE_PAYMENT = "late_payment";
    /** R2: выдача по API продавца не уложилась в срок. */
    public static final String SELLER_API_TIMEOUT = "seller_api_timeout";
    /** R2: решение по спору. */
    public static final String DISPUTE_DECISION = "dispute_decision";

    public static final Set<String> ALL = Set.of(LATE_PAYMENT, SELLER_API_TIMEOUT, DISPUTE_DECISION);

    private RefundReason() {
    }
}
