package dgm.payment.payments.gateway;

/**
 * Ответ шлюза на запрос статуса платежа в модели платформы.
 *
 * @param id       внешний идентификатор платежа
 * @param status   состояние
 * @param amount   сумма в копейках по данным шлюза
 * @param currency валюта по данным шлюза
 */
public record GatewayPaymentState(String id, GatewayStatus status, long amount, String currency) {

    /** Сумма и валюта равны ожидаемым. */
    public boolean sameMoney(long expectedAmount, String expectedCurrency) {
        return amount == expectedAmount && currency.equals(expectedCurrency);
    }
}
