package dgm.delivery.delivery.domain;

/** Причина статуса «ошибка» (поле {@code fail_reason}, событие {@code delivery.failed}). */
public final class FailReason {

    /** Шестая попытка не удалась. */
    public static final String ATTEMPTS_EXHAUSTED = "attempts_exhausted";

    /** Провайдер отказал по адресу окончательно (400, 422) либо адрес обезличен. */
    public static final String ADDRESS_REJECTED = "address_rejected";

    private FailReason() {
    }
}
