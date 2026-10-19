package dgm.order.orders.domain;

import java.util.Set;

/** Причины отмены заказа (SM-01, решение 4): значения совпадают с {@code ck_orders_cancel_reason} и схемой события {@code order.cancelled}. */
public final class CancelReason {

    public static final String OUT_OF_STOCK = "out_of_stock";
    public static final String PAYMENT_DECLINED = "payment_declined";
    public static final String RESERVATION_EXPIRED = "reservation_expired";
    public static final String PAYMENT_SESSION_FAILED = "payment_session_failed";
    public static final String SYSTEM_ERROR = "system_error";
    /** R2: выдача по API продавца не уложилась в срок. В R1 переход T10 запрещён, значение здесь для полноты схемы. */
    public static final String SELLER_API_TIMEOUT = "seller_api_timeout";

    public static final Set<String> ALL = Set.of(OUT_OF_STOCK, PAYMENT_DECLINED, RESERVATION_EXPIRED, PAYMENT_SESSION_FAILED, SYSTEM_ERROR,
            SELLER_API_TIMEOUT);

    private CancelReason() {
    }
}
