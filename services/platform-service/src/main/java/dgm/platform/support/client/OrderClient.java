package dgm.platform.support.client;

import java.util.UUID;

/**
 * Вызов {@code order-service} из поддержки (контейнерная связь платформы с заказами, SEQ-04): единственная операция, обновление снимка адреса
 * доставки выданного заказа ({@code updateDeliveryAddress}, OP-72). Вызов идёт вне транзакции базы (правило 9).
 */
public interface OrderClient {

    /**
     * Записывает в заказ текущий адрес покупателя; заказ публикует {@code order.address-updated}, и выдача создаёт повторную выдачу. Повтор с
     * тем же {@code idempotencyKey} даёт тот же ответ и нового события не публикует.
     *
     * @param email          адрес в нормальном виде, значение живёт только в памяти на время вызова (INV-44)
     * @param idempotencyKey UUID версии 4, выведенный из обращения и состояния выдачи ({@code IdempotencyKeys.derived})
     * @throws OrderRefusedException                           сосед ответил отказом по смыслу (4xx)
     * @throws dgm.kit.client.DependencyUnavailableException сосед недоступен: сеть, тайм-аут, 5xx, открытый предохранитель
     */
    void updateDeliveryAddress(UUID orderId, UUID ticketId, String email, UUID idempotencyKey);
}
