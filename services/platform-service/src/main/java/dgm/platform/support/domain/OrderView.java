package dgm.platform.support.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Модель чтения заказа в поддержке ({@code support.order_view}, Р-137): покупатель и номер заказа из событий {@code order.paid} и
 * {@code order.issued}, статус выдачи из событий {@code delivery.*}. Без неё обращение системы не создаётся: у обращения покупатель
 * обязателен (F3-21).
 *
 * @param orderId           заказ
 * @param orderNumber       короткий номер заказа
 * @param buyerId           покупатель
 * @param orderStatus       статус заказа по SM-01 из последнего применённого события
 * @param issuedAt          время первичной выдачи из {@code order.issued}
 * @param deliveryStatus    статус выдачи: {@code sent}, {@code delivered} или {@code failed}
 * @param deliveryUpdatedAt время последнего применённого события выдачи
 */
public record OrderView(UUID orderId, long orderNumber, UUID buyerId, String orderStatus, Optional<Instant> issuedAt,
                        Optional<String> deliveryStatus, Optional<Instant> deliveryUpdatedAt) {

    public static final String SENT = "sent";
    public static final String DELIVERED = "delivered";
    public static final String FAILED = "failed";

    /** Доставка подтверждена после момента {@code at}: событие, которое старше этой доставки, обращения создавать не должно. */
    public boolean deliveredAfter(Instant at) {
        return deliveryStatus.filter(DELIVERED::equals).isPresent() && deliveryUpdatedAt.filter(updated -> updated.isAfter(at)).isPresent();
    }

    /** Применённое событие выдачи новее {@code at}: более раннее событие статус не меняет. */
    public boolean deliveryNewerThan(Instant at) {
        return deliveryUpdatedAt.filter(updated -> updated.isAfter(at)).isPresent();
    }
}
