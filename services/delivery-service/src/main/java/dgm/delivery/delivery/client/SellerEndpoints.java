package dgm.delivery.delivery.client;

import dgm.delivery.delivery.domain.SellerEndpoint;
import java.util.Optional;
import java.util.UUID;

/** Подключения продавцов (адрес, статус, версия секрета). Интерфейс нужен, чтобы задание отправки проверялось модульными тестами без каталога. */
public interface SellerEndpoints {

    /**
     * Подключение продавца.
     *
     * @return пусто, если подключения нет или профиль не одобрен
     * @throws dgm.kit.client.DependencyUnavailableException каталог недоступен: вызывающий считает это временным сбоем
     */
    Optional<SellerEndpoint> find(UUID sellerId);
}
