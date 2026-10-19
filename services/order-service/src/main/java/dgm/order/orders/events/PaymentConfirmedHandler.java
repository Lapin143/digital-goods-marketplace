package dgm.order.orders.events;

import dgm.kit.events.EventEnvelope;
import dgm.kit.events.ExternalStepsHandler;
import dgm.kit.events.RetryableEventException;
import dgm.order.orders.service.PaymentDecision;
import dgm.order.orders.service.SagaOrchestrator;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code payment.confirmed} (компонент {@code saga-orchestrator}, ADR-004 шаг 5, ADR-013, c4-components-order-service 3.1): платёж
 * подтверждён, нужно закрепить ключи и перевести заказ в «оплачен». Это единственный обработчик, которому нужен сосед до изменения данных,
 * поэтому он работает в режиме внешних шагов ({@link ExternalStepsHandler}): статус читается и {@code confirmReservation} вызывается вне
 * транзакции базы (правило 9), а переход T4 или T7, запись {@code order.paid} в Outbox и отметка {@code processed_event} это одна короткая
 * транзакция каркаса.
 *
 * <p>Сервис остатков недоступен или заказ ещё «создан» (подтверждение обогнало T2): временная ошибка, повторы 1, 5, 25 секунд, затем
 * {@code payment.events.dlq}. Платёж при этом остаётся подтверждённым (ADR-013, «Последствия»).
 */
@Component
public final class PaymentConfirmedHandler implements ExternalStepsHandler {

    /** Имя потребителя в {@code processed_event} и в контракте событий (x-consumer.handler). */
    static final String NAME = "saga-orchestrator";
    static final String TOPIC = "payment.events";
    static final String CONFIRMED = "payment.confirmed";

    private final SagaOrchestrator saga;
    private final Clock clock;

    public PaymentConfirmedHandler(SagaOrchestrator saga, Clock clock) {
        this.saga = saga;
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String topic() {
        return TOPIC;
    }

    @Override
    public Set<String> types() {
        return Set.of(CONFIRMED);
    }

    @Override
    public int maxSchemaVersion() {
        return 1;
    }

    @Override
    public TransactionalStep prepare(EventEnvelope event) {
        UUID orderId = EventData.uuid(event, "orderId");
        PaymentDecision decision = saga.decidePayment(orderId);
        if (decision instanceof PaymentDecision.Wait wait) {
            throw new RetryableEventException("Платёж по заказу " + orderId + " подтверждён, обработка отложена: " + wait.reason());
        }
        return () -> EventData.result(saga.applyPayment(orderId, decision, clock.instant()));
    }
}
