package dgm.delivery.delivery.domain;

/**
 * Исход одной отправки запроса системе продавца (ADR-028, раздел 2): принят, временный сбой или отказ. Тел ответа продавца и адреса здесь нет.
 */
public sealed interface SellerCallResult {

    /** Продавец ответил кодом 2xx: «принято». */
    record Accepted() implements SellerCallResult {
    }

    /** Код 5xx, тайм-аут, обрыв соединения, слишком большой или неразборчивый ответ: запрос повторяется внутри окна. */
    record Retryable(String code) implements SellerCallResult {
    }

    /** Код 4xx или 3xx (перенаправления платформа не выполняет): отказ продавца, повтор не поможет. */
    record Rejected(int status) implements SellerCallResult {
    }

    /** Адрес не прошёл проверку защиты от SSRF прямо перед вызовом: соединение не открывалось, повтор не поможет. */
    record EndpointRejected() implements SellerCallResult {
    }
}
