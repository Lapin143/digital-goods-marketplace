package dgm.order.orders.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Платёжная сессия заказа для операции {@code getOrderPaymentSession}: статус и причина отмены нужны, чтобы объяснить покупателю, почему
 * ссылку на оплату уже не отдают.
 *
 * @param url       адрес страницы оплаты: пусто, пока сессия не открыта
 * @param expiresAt конец сессии: пусто, пока сессия не открыта
 */
public record OrderSession(UUID orderId, String status, Optional<String> cancelReason, Optional<String> url, Optional<Instant> expiresAt) {

    /** Ссылку на оплату можно отдать: заказ ждёт оплаты, сессия открыта и ещё не кончилась. */
    public boolean usableAt(Instant now) {
        return OrderStatus.AWAITING_PAYMENT.equals(status) && url.isPresent() && expiresAt.map(now::isBefore).orElse(false);
    }
}
