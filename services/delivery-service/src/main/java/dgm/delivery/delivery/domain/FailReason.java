package dgm.delivery.delivery.domain;

/** Причина статуса «ошибка» (поле {@code fail_reason}, событие {@code delivery.failed}). */
public final class FailReason {

    /** Шестая попытка не удалась. */
    public static final String ATTEMPTS_EXHAUSTED = "attempts_exhausted";

    /**
     * Провайдер отказал по адресу окончательно (400, 422), адрес обезличен либо письмо принято, но не доставлено (T6). Таблица знает две причины,
     * поэтому любая недоставка по сообщению провайдера записывается как отказ по адресу (Р-152).
     */
    public static final String ADDRESS_REJECTED = "address_rejected";

    private FailReason() {
    }
}
