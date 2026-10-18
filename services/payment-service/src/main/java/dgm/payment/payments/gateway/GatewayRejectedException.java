package dgm.payment.payments.gateway;

/**
 * Шлюз ответил отказом по смыслу (код 4xx): запрос не будет принят и при повторе. Для платформы это ошибка настройки или контракта,
 * а не сбой сети.
 */
public final class GatewayRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;

    public GatewayRejectedException(int status, String message) {
        super(message);
        this.status = status;
    }

    /** Код ответа шлюза. */
    public int status() {
        return status;
    }
}
