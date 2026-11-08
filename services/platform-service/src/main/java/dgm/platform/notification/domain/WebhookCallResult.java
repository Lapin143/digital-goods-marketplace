package dgm.platform.notification.domain;

/**
 * Исход одной отправки webhook (Р-193): получатель ответил 2xx или нет. Любой другой исход, включая 4xx, считается неудачей попытки и повторяется по
 * лестнице: решить, что отказ окончательный, платформа за продавца не может. Тела ответа и адреса здесь нет.
 *
 * @param accepted     получатель ответил кодом 2xx
 * @param responseCode код ответа, пусто, если ответа не было (тайм-аут, обрыв, адрес не прошёл проверку)
 * @param errorCode    короткий код неудачи без свободного текста, пусто при успехе
 */
public record WebhookCallResult(boolean accepted, Integer responseCode, String errorCode) {

    public static WebhookCallResult ok(int responseCode) {
        return new WebhookCallResult(true, responseCode, null);
    }

    public static WebhookCallResult failed(Integer responseCode, String errorCode) {
        return new WebhookCallResult(false, responseCode, errorCode);
    }
}
