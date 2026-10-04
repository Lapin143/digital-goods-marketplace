package dgm.fixtures.orders.repository;

import dgm.fixtures.orders.client.PaymentClient;

/** Нарушение: репозиторий вызывает клиент соседа. */
public class ClientCallingRepository {

    public String load() {
        return new PaymentClient().charge();
    }
}
