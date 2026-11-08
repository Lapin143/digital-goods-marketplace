package dgm.delivery.delivery.client;

import dgm.delivery.delivery.domain.KeyStoreResult;
import java.util.List;
import java.util.UUID;

/** Передача ключей продавца в хранилище ключей. Интерфейс нужен, чтобы приём ответа продавца проверялся модульными тестами без соседа. */
public interface SellerKeyStore {

    /**
     * Передаёт значения ключей {@code inventory-service}: они шифруются там и привязываются к заказу. Вызывать только вне транзакции (правило 9).
     * Значения в журнал, исключения и метрики не идут.
     */
    KeyStoreResult store(UUID orderId, UUID requestId, List<String> keys);
}
