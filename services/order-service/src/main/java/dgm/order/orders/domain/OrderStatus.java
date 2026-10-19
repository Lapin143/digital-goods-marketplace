package dgm.order.orders.domain;

/** Статусы заказа по SM-01 (значения совпадают с ограничением {@code ck_orders_status} в базе). */
public final class OrderStatus {

    public static final String CREATED = "created";
    public static final String AWAITING_PAYMENT = "awaiting_payment";
    public static final String PAID = "paid";
    public static final String ISSUED = "issued";
    public static final String CANCELLED = "cancelled";
    public static final String REFUNDED = "refunded";

    private OrderStatus() {
    }

    /** Неоплаченный заказ в смысле лимита {@code orders.max-unpaid}: «создан» и «ожидает оплаты» (FT-5.1). */
    public static boolean isUnpaid(String status) {
        return CREATED.equals(status) || AWAITING_PAYMENT.equals(status);
    }
}
