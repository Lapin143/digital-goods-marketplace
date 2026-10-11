package dgm.platform.notification.domain;

/** Причина недоставки в событии {@code notification.failed} (AsyncAPI, {@code NotificationFailedData.reason}). */
public enum FailureReason {

    /** Попытки кончились: провайдер недоступен или отвечает ошибкой. */
    ATTEMPTS_EXHAUSTED("attempts_exhausted"),
    /** Провайдер отверг адрес или запрос; повтор не поможет. */
    ADDRESS_REJECTED("address_rejected");

    private final String code;

    FailureReason(String code) {
        this.code = code;
    }

    /** Значение в событии. */
    public String code() {
        return code;
    }
}
