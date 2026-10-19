package dgm.delivery.delivery.client;

import dgm.delivery.delivery.domain.ReleasedKeys;
import java.util.UUID;

/** Клиент ключей (компонент {@code key-client}): значения ключей заказа у {@code inventory-service} в момент отправки, без кэша. */
public interface KeyClient {

    /**
     * Запрашивает значения ключей заказа по mTLS. Вызывающий закрывает результат ({@link ReleasedKeys#close()}), когда письмо отправлено.
     *
     * @throws KeyRequestException ключи не получены: сервис недоступен, ключи заказа ещё не выданы, ответ не по формату
     */
    ReleasedKeys keysOf(UUID orderId);
}
