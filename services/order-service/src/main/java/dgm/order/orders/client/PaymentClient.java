package dgm.order.orders.client;

import dgm.kit.client.DependencyUnavailableException;
import java.time.Instant;
import java.util.UUID;

/**
 * Клиент платежей (адаптер, {@code payment-client}): «открыть платёжную сессию» (SEQ-01, шаги 12 и 13). У {@code payment-service} на шаг 7 секунд
 * (ADR-015), поэтому тайм-аут вызова 8 секунд и повторов нет: повторы шлюзу делает сам сервис платежей. Ключ идемпотентности выводится из имени
 * шага и заказа ({@code derived("open-session", orderId)}).
 */
public interface PaymentClient {

    /**
     * @throws DependencyUnavailableException сервис платежей не ответил или ответил 5xx (в том числе 503 {@code payment-session-unavailable})
     */
    SessionResult open(UUID orderId, long amount, String currency, Instant sessionExpiresAt);

    /** Исход вызова, который сервис платежей назвал сам. */
    sealed interface SessionResult permits Opened, Refused {
    }

    /** Сессия открыта: адрес страницы оплаты и конец сессии. */
    record Opened(String url, Instant expiresAt) implements SessionResult {
    }

    /** Сервис платежей отказал по смыслу (4xx): платёж по заказу уже в другом статусе или запрос не принят. */
    record Refused(int status) implements SessionResult {
    }
}
