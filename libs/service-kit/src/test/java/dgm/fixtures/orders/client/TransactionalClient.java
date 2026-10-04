package dgm.fixtures.orders.client;

import org.springframework.transaction.annotation.Transactional;

/** Нарушение: транзакция на методе клиента. */
public class TransactionalClient {

    @Transactional
    public void call() {
        // тело не важно, правило смотрит на аннотацию
    }
}
