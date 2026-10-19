package dgm.order.orders.service;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.order.orders.domain.Order;
import dgm.order.orders.domain.OrderSession;
import dgm.order.orders.repository.OrderRepository;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Карточка заказа и его платёжная сессия ({@code getOrder}, {@code getOrderPaymentSession}). Владельца задаёт вызывающий из токена: чужой заказ и
 * несуществующий неотличимы, оба дают 404 (условие U1).
 */
public final class OrderViewService {

    private final OrderRepository orders;
    private final Clock clock;

    public OrderViewService(OrderRepository orders, Clock clock) {
        this.orders = orders;
        this.clock = clock;
    }

    /** @throws ProblemException 404, если заказа нет или он чужой */
    public Order get(UUID orderId, UUID buyerId) {
        return orders.findOwned(orderId, buyerId).orElseThrow(OrderViewService::notFound);
    }

    /**
     * Платёжная сессия, к которой покупатель возвращается после закрытой страницы оплаты (US-5.3).
     *
     * @throws ProblemException 404, если заказа нет или он чужой; 409 {@code state-conflict} со статусом и причиной отмены, если заказ уже не ждёт
     *                          оплаты или сессия кончилась
     */
    public OrderSession paymentSession(UUID orderId, UUID buyerId) {
        OrderSession session = orders.findSession(orderId, buyerId).orElseThrow(OrderViewService::notFound);
        if (!session.usableAt(clock.instant())) {
            Map<String, Object> extensions = new LinkedHashMap<>();
            extensions.put("currentStatus", session.status());
            session.cancelReason().ifPresent(reason -> extensions.put("cancelReason", reason));
            throw new ProblemException(ProblemType.STATE_CONFLICT, "Оплата этого заказа недоступна: заказ не ждёт оплаты или срок оплаты истёк.",
                    extensions, Map.of());
        }
        return session;
    }

    private static ProblemException notFound() {
        return new ProblemException(ProblemType.NOT_FOUND, "Заказ не найден.");
    }
}
