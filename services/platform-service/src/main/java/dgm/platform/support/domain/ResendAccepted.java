package dgm.platform.support.domain;

import java.util.UUID;

/**
 * Итог повторной отправки (схема {@code ResendAccepted}): заказ получил актуальный адрес, повторная выдача создаётся асинхронно по
 * {@code order.address-updated}. Адреса и ключей в итоге нет.
 *
 * @param ticketId обращение
 * @param orderId  заказ
 */
public record ResendAccepted(UUID ticketId, UUID orderId) {

    /** Значение поля {@code status} ответа. */
    public static final String STATUS = "accepted";
}
