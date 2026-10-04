package dgm.fixtures.orders.service;

import dgm.fixtures.payments.repository.PaymentRepository;

/** Нарушение: модуль orders читает репозиторий модуля payments. */
public class PeekingService {

    public String peek() {
        return new PaymentRepository().find();
    }
}
