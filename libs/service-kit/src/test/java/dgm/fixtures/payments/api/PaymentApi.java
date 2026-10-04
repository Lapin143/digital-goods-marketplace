package dgm.fixtures.payments.api;

/** Интерфейс модуля payments: открыт соседям. */
public interface PaymentApi {

    String status(String orderId);
}
