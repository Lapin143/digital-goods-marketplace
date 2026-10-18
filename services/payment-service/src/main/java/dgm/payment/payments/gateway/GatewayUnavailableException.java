package dgm.payment.payments.gateway;

/**
 * Шлюз не ответил за отведённый срок, ответил ошибкой сервера или предохранитель открыт. Результат неизвестен: платёж у шлюза мог
 * быть создан, поэтому повтор идёт с тем же ключом идемпотентности. Текст не содержит адресов и тел ответов.
 */
public final class GatewayUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public GatewayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
