package dgm.platform.support.testing;

import dgm.kit.time.ManualClock;
import dgm.platform.identity.otp.testing.FakeParameters;
import dgm.platform.support.service.QueueMetrics;
import dgm.platform.support.service.QueueService;
import dgm.platform.support.service.SupportService;
import dgm.platform.support.service.TicketService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.UUID;

/** Модуль поддержки в памяти для тестов обращений покупателя: модель чтения, транзакция с откатом, Outbox, лимит, параметры, часы и метрики. */
public final class SupportWorld {

    public static final UUID BUYER = UUID.fromString("3f8e1d52-7b6a-4c90-8d21-5a9b0e7c4f13");
    public static final UUID STRANGER = UUID.fromString("7a1c9e04-2d3b-4f58-9b6e-8c0d1a2b3c4d");
    public static final UUID OPERATOR = UUID.fromString("9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d");
    public static final UUID OPERATOR_2 = UUID.fromString("5d4c3b2a-1f0e-4d9c-8b7a-6e5f4d3c2b1a");
    public static final Instant T0 = Instant.parse("2026-10-25T12:00:00Z");

    public final ManualClock clock = new ManualClock(T0);
    public final InMemorySupport repository = new InMemorySupport();
    public final RecordingOutbox outbox = new RecordingOutbox();
    public final InMemoryRateLimiter limiter = new InMemoryRateLimiter();
    public final FakeParameters parameters = new FakeParameters();
    public final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    public final SupportService events = new SupportService(repository, outbox, clock);
    public final TicketService tickets = new TicketService(repository, new FakeSupportTransaction(repository), outbox, limiter, parameters, clock, metrics);
    public final QueueService queue = new QueueService(repository, new FakeSupportTransaction(repository), outbox, clock, metrics);
    public final QueueMetrics queueMetrics = new QueueMetrics(repository, clock, metrics);

    private long nextNumber = 1000;

    /** Выданный заказ покупателя: события {@code order.paid} и {@code order.issued} применяет служба поддержки, как потребитель. */
    public UUID issuedOrder(UUID buyer, Instant issuedAt) {
        UUID orderId = UUID.randomUUID();
        long number = nextNumber++;
        events.onOrderPaid(orderId, number, buyer);
        events.onOrderIssued(orderId, number, buyer, issuedAt);
        return orderId;
    }

    /** Оплаченный, но ещё не выданный заказ. */
    public UUID paidOrder(UUID buyer) {
        UUID orderId = UUID.randomUUID();
        events.onOrderPaid(orderId, nextNumber++, buyer);
        return orderId;
    }

    /** Обращение системы по заказу: событие {@code delivery.overdue} создаёт его в статусе «создано». */
    public UUID overdueTicketOf(UUID orderId) {
        events.onDeliveryOverdue(orderId, clock.instant());
        return repository.ticketsOf(orderId).get(0).id;
    }

    public double count(String result) {
        var counter = metrics.find(TicketService.METRIC_NAME).tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }
}
