package dgm.inventory.inventory.reservation.handler;

import dgm.inventory.inventory.registry.JdbcProductCopyRepository;
import dgm.inventory.inventory.reservation.domain.Reservation;
import dgm.inventory.inventory.reservation.service.ReservationService;
import dgm.kit.events.EventEnvelope;
import dgm.kit.events.EventHandler;
import dgm.kit.events.FatalEventException;
import dgm.kit.events.HandleResult;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Обработчик {@code order.cancelled} (компонент {@code reservation-service} в контракте событий, SM-08/T4 и T6): снимает активный
 * резерв заказа и возвращает ключи в свободные. Причина снятия {@code payment_declined}, если заказ отменён по отказу шлюза, иначе
 * {@code order_cancelled}. Резерв уже снят (срок истёк) или использован: событие игнорируется, поэтому повтор и порядок событий
 * безопасны. Работает внутри транзакции потребителя под ролью модуля {@code inventory}.
 */
@Component
public final class ReservationReleaseHandler implements EventHandler {

    /** Имя потребителя в {@code processed_event}: так обработчик назван в контракте событий (x-consumer.handler). */
    static final String NAME = "reservation-service";

    private final ReservationService service;

    public ReservationReleaseHandler(ReservationService service) {
        this.service = service;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String module() {
        return JdbcProductCopyRepository.MODULE;
    }

    @Override
    public String topic() {
        return "order.events";
    }

    @Override
    public Set<String> types() {
        return Set.of("order.cancelled");
    }

    @Override
    public int maxSchemaVersion() {
        return 1;
    }

    @Override
    public HandleResult handle(EventEnvelope event) {
        UUID orderId = orderId(event);
        String reason = "payment_declined".equals(event.data().get("cancelReason")) ? Reservation.PAYMENT_DECLINED : Reservation.ORDER_CANCELLED;
        return service.release(orderId, reason).isPresent() ? HandleResult.done() : HandleResult.ignored("no-active-reservation");
    }

    private static UUID orderId(EventEnvelope event) {
        Object value = event.data().get("orderId");
        if (value instanceof String text) {
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException e) {
                throw new FatalEventException("orderId события " + event.id() + " не UUID", e);
            }
        }
        throw new FatalEventException("В событии " + event.id() + " нет orderId");
    }
}
