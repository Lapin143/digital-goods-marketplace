package dgm.delivery.delivery.service;

import dgm.delivery.delivery.repository.DeliveryRepository;
import dgm.kit.id.Uuids;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Приём оплаты (SM-06/T1, INV-18): по {@code order.paid} создаёт первичную выдачу в очереди и открывает контроль доставки. Обе записи идут в
 * транзакции потребителя вместе с отметкой {@code processed_event}, поэтому класс своей транзакции не открывает. Повтор события второй
 * выдачи не создаёт: первичная выдача заказа одна (уникальный индекс), контроль один (первичный ключ).
 */
public final class DeliveryIntake {

    private final DeliveryRepository deliveries;
    private final DeliverySettings settings;
    private final Clock clock;

    public DeliveryIntake(DeliveryRepository deliveries, DeliverySettings settings, Clock clock) {
        this.deliveries = deliveries;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * @param paidAt когда заказ оплачен: от него отсчитывается окно контроля (оплата за пределами окна, пришедшая после простоя, просрочена сразу)
     * @return {@code true}: выдача создана; {@code false}: у заказа уже есть первичная выдача
     */
    public boolean accept(UUID orderId, UUID buyerId, String address, String productTitle, int quantity, Instant paidAt) {
        Instant now = clock.instant();
        boolean created = deliveries.insertPrimary(Uuids.v7(clock), orderId, buyerId, address, productTitle, quantity, now);
        if (created) {
            deliveries.insertWatch(orderId, paidAt.plus(settings.controlWindow()), now);
        }
        return created;
    }
}
