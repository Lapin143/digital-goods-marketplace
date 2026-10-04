package dgm.fixtures.orders.service;

import dgm.fixtures.payments.api.PaymentApi;
import java.time.Clock;
import java.time.Instant;

/** Допустимый код: часы внедряются, сосед вызывается через api, транзакции в сервисном слое. */
public class GoodService {

    private final Clock clock;
    private final PaymentApi payments;

    public GoodService(Clock clock, PaymentApi payments) {
        this.clock = clock;
        this.payments = payments;
    }

    @org.springframework.transaction.annotation.Transactional
    public String run(String orderId) {
        Instant now = Instant.now(clock);
        return payments.status(orderId) + now;
    }
}
