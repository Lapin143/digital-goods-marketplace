package dgm.platform.support.testing;

import dgm.kit.time.ManualClock;
import dgm.platform.identity.otp.testing.FakeParameters;
import dgm.platform.support.service.SupportService;
import dgm.platform.support.service.TicketService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.UUID;

/** Модуль поддержки в памяти для тестов обращений покупателя: модель чтения, транзакция с откатом, Outbox, лимит, параметры, часы и метрики. */
public final class SupportWorld {

    public static final UUID BUYER = UUID.fromString("3f8e1d52-7b6a-4c90-8d21-5a9b0e7c4f13");
    public static final UUID STRANGER = UUID.fromString("7a1c9e04-2d3b-4f58-9b6e-8c0d1a2b3c4d");
    public static final Instant T0 = Instant.parse("2026-10-25T12:00:00Z");

    public final ManualClock clock = new ManualClock(T0);
    public final InMemorySupport repository = new InMemorySupport();
    public final RecordingOutbox outbox = new RecordingOutbox();
    public final InMemoryRateLimiter limiter = new InMemoryRateLimiter();
    public final FakeParameters parameters = new FakeParameters();
    public final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    public final SupportService events = new SupportService(repository, outbox, clock);
    public final TicketService tickets = new TicketService(repository, new FakeSupportTransaction(repository), outbox, limiter, parameters, clock, metrics);

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

    public double count(String result) {
        var counter = metrics.find(TicketService.METRIC_NAME).tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }
}
