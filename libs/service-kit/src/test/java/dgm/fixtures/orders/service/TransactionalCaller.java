package dgm.fixtures.orders.service;

import dgm.fixtures.orders.client.PaymentClient;
import org.springframework.transaction.annotation.Transactional;

/** Нарушение: метод с транзакцией вызывает клиент соседа. */
public class TransactionalCaller {

    private final PaymentClient payments;

    public TransactionalCaller(PaymentClient payments) {
        this.payments = payments;
    }

    @Transactional
    public String charge() {
        return payments.charge();
    }
}
