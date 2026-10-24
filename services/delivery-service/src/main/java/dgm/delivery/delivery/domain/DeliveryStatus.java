package dgm.delivery.delivery.domain;

/** Статусы выдачи (SM-06, столбец {@code delivery.delivery.status}). */
public final class DeliveryStatus {

    public static final String QUEUED = "queued";
    public static final String SENT = "sent";
    public static final String DELIVERED = "delivered";
    public static final String FAILED = "failed";

    private DeliveryStatus() {
    }
}
