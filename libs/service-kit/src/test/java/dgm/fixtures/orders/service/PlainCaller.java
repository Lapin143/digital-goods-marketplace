package dgm.fixtures.orders.service;

import dgm.fixtures.orders.client.PaymentClient;

/** Допустимый код: клиент соседа вызывается вне транзакции (оркестратор без аннотации). */
public class PlainCaller {

    private final PaymentClient payments;

    public PlainCaller(PaymentClient payments) {
        this.payments = payments;
    }

    public String charge() {
        return payments.charge();
    }
}
