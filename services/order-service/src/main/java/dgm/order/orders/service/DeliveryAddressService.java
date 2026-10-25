package dgm.order.orders.service;

import dgm.order.orders.domain.AddressUpdate;
import java.time.Clock;
import java.util.UUID;

/**
 * Смена адреса доставки по решению оператора поддержки ({@code updateDeliveryAddress}, OP-72, US-7.3). Время берётся у часов сервиса, а сама
 * замена снимка и событие {@code order.address-updated} выполняются одной транзакцией домена ({@link OrderTransitions#updateDeliveryAddress}).
 */
public final class DeliveryAddressService {

    private final OrderTransitions transitions;
    private final Clock clock;

    public DeliveryAddressService(OrderTransitions transitions, Clock clock) {
        this.transitions = transitions;
        this.clock = clock;
    }

    /**
     * @throws dgm.kit.problem.ProblemException 404, если заказа нет; 409 {@code state-conflict}, если заказ не «выдан»
     */
    public AddressUpdate update(UUID orderId, UUID ticketId, String address) {
        return transitions.updateDeliveryAddress(orderId, ticketId, address, clock.instant());
    }
}
