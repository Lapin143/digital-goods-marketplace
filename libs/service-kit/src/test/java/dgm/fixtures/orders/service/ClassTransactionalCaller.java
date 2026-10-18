package dgm.fixtures.orders.service;

import dgm.fixtures.orders.client.PaymentClient;
import org.springframework.transaction.annotation.Transactional;

/** Нарушение: транзакция объявлена на классе, метод без собственной аннотации вызывает клиент соседа. */
@Transactional
public class ClassTransactionalCaller {

    private final PaymentClient payments;

    public ClassTransactionalCaller(PaymentClient payments) {
        this.payments = payments;
    }

    public String charge() {
        return payments.charge();
    }
}
