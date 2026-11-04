package dgm.catalog.sellers.integration.repository;

import dgm.catalog.sellers.integration.domain.IntegrationStatus;
import dgm.catalog.sellers.integration.domain.SellerIntegration;
import java.util.Optional;
import java.util.UUID;

/**
 * Подключения систем продавцов в схеме {@code seller_onboarding}. Секрета подписи здесь нет и быть не может (Р-187): таблица хранит адрес,
 * статус и версию секрета. Изменения идут в транзакции вызывающего сервиса (модуль {@code seller_onboarding}) под блокировкой строки
 * профиля продавца, поэтому два запроса одного продавца идут по очереди.
 */
public interface IntegrationRepository {

    /** Подключение продавца; пусто, пока система не подключена. */
    Optional<SellerIntegration> find(UUID sellerId);

    /** Первая запись подключения. */
    void insert(SellerIntegration integration);

    /**
     * Меняет адрес и статус, версию секрета оставляет. Версию записи и время изменения ставит триггер базы
     * ({@code trg_seller_integration_touch}), поэтому возвращается строка после изменения.
     */
    SellerIntegration update(UUID sellerId, String endpointUrl, IntegrationStatus status);

    /** Увеличивает версию секрета на единицу; возвращает строку после изменения. */
    SellerIntegration rotateSecret(UUID sellerId);
}
