package dgm.delivery.delivery.service;

import dgm.delivery.delivery.repository.DeliveryRepository;
import dgm.kit.id.Uuids;
import java.time.Clock;
import java.util.UUID;

/**
 * Повторная выдача по смене адреса (SM-06/T1, Р-170): по {@code order.address-updated} создаёт выдачу {@code repeat} в очереди на новый адрес.
 * Дальше она идёт обычным путём диспетчера: те же ключи заказа, шесть попыток, письмо без значений ключей в записях (ADR-011). Контроль
 * доставки не открывается: он один на заказ и относится к первичной выдаче. Класс своей транзакции не открывает: запись идёт в транзакции
 * потребителя вместе с отметкой {@code processed_event}.
 */
public final class DeliveryRepeat {

    /** Что произошло с событием о смене адреса. */
    public enum Outcome {
        /** Повторная выдача создана. */
        CREATED,
        /** Выдача заказа уже стоит в очереди: вторая не создаётся (ADR-011, Р-170), событие пропущено с причиной {@code already-queued}. */
        ALREADY_QUEUED,
        /** Первичной выдачи заказа нет: повторять нечего. */
        NO_PRIMARY
    }

    private final DeliveryRepository deliveries;
    private final Clock clock;

    public DeliveryRepeat(DeliveryRepository deliveries, Clock clock) {
        this.deliveries = deliveries;
        this.clock = clock;
    }

    public Outcome accept(UUID orderId, String address) {
        if (deliveries.primaryOf(orderId).isEmpty()) {
            return Outcome.NO_PRIMARY;
        }
        return deliveries.insertRepeat(Uuids.v7(clock), orderId, address, clock.instant()) ? Outcome.CREATED : Outcome.ALREADY_QUEUED;
    }
}
