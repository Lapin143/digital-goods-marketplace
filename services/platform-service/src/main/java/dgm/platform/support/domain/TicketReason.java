package dgm.platform.support.domain;

/**
 * Причины обращения системы (SM-07, {@code ck_ticket_reason}): по ним оператор видит, что случилось с выдачей. Причина покупателя
 * {@code key_not_received} относится к срезу 3.
 */
public final class TicketReason {

    public static final String NOT_DELIVERED = "not_delivered";
    public static final String ATTEMPTS_EXHAUSTED = "attempts_exhausted";
    public static final String DELIVERY_OVERDUE = "delivery_overdue";

    private TicketReason() {
    }

    /**
     * Причина обращения по причине неудачи выдачи из {@code delivery.failed}: исчерпание попыток называется так же, остальное
     * (адрес отклонён провайдером) это «не доставлено».
     */
    public static String forFailure(String deliveryFailureReason) {
        return ATTEMPTS_EXHAUSTED.equals(deliveryFailureReason) ? ATTEMPTS_EXHAUSTED : NOT_DELIVERED;
    }
}
