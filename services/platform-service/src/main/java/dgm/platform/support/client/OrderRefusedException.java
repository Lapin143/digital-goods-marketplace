package dgm.platform.support.client;

/**
 * {@code order-service} отказал по смыслу: заказ не найден (404), не «выдан» (409 {@code state-conflict}), запрос с этим ключом ещё выполняется
 * (409 {@code request-in-progress}) либо запрос не принят (остальные 4xx: ошибка самой платформы). Адрес в сообщение не попадает.
 */
public final class OrderRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String problemCode;

    /**
     * @param status      код ответа
     * @param problemCode код проблемы из тела RFC 9457 или пустая строка
     */
    public OrderRefusedException(int status, String problemCode) {
        super("order-service отказал в обновлении адреса: " + status + (problemCode.isEmpty() ? "" : " " + problemCode));
        this.status = status;
        this.problemCode = problemCode;
    }

    public int status() {
        return status;
    }

    public String problemCode() {
        return problemCode;
    }
}
