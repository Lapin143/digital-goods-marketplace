package dgm.fixtures.orders.controller;

import dgm.fixtures.orders.client.PaymentClient;
import dgm.fixtures.orders.repository.OrderRepository;
import org.springframework.transaction.annotation.Transactional;

/** Нарушения: транзакция в контроллере, вызов репозитория и клиента из контроллера. */
@Transactional
public class OrderController {

    private final OrderRepository repository = new OrderRepository();
    private final PaymentClient client = new PaymentClient();

    public String get() {
        return repository.find() + client.charge();
    }
}
