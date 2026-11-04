package dgm.catalog.sellers.apikey.repository;

import dgm.catalog.sellers.apikey.domain.ApiKey;
import dgm.catalog.sellers.apikey.domain.ApiKeyPage;
import dgm.catalog.sellers.apikey.domain.ApiKeyStatus;
import dgm.catalog.sellers.apikey.domain.KeyCheck;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * API-ключи продавцов в схеме {@code seller_onboarding}. Значения ключа здесь нет и быть не может (INV-41): таблица хранит только
 * SHA-256, а в объекты {@link ApiKey} хеш не попадает. Изменения идут в транзакции вызывающего сервиса (модуль {@code seller_onboarding}).
 */
public interface ApiKeyRepository {

    /** Сколько у продавца действующих ключей. Для решения «пятый или шестой» годится только под блокировкой профиля продавца. */
    int countActive(UUID sellerId);

    /**
     * Новая строка ключа в статусе «действует».
     *
     * @param keyHash SHA-256 значения ключа, ровно 32 байта
     */
    void insert(ApiKey key, byte[] keyHash);

    /** Ключ продавца без блокировки. Чужой и несуществующий ключ неразличимы: пусто. */
    Optional<ApiKey> find(UUID sellerId, UUID apiKeyId);

    /** Ключ продавца с блокировкой строки до конца транзакции: два отзыва одного ключа идут по очереди. */
    Optional<ApiKey> lock(UUID sellerId, UUID apiKeyId);

    /**
     * Записывает отзыв, если ключ ещё действует.
     *
     * @return {@code false}, если ключ уже отозван (отзыв необратим, повторная запись ничего не меняет)
     */
    boolean markRevoked(ApiKey revoked);

    /**
     * Ключи продавца, новые выше (индекс {@code ix_api_key_seller_id}).
     *
     * @param status фильтр по статусу; пусто: все
     * @param after  строго после этой пары в порядке «новые выше»; пусто: с начала
     * @param limit  сколько строк вернуть
     */
    List<ApiKey> list(UUID sellerId, Optional<ApiKeyStatus> status, Optional<ApiKeyPage.After> after, int limit);

    /** Ключ по SHA-256 значения (уникальный индекс {@code uq_api_key_key_hash}) вместе со статусом профиля владельца. */
    Optional<KeyCheck> findByHash(byte[] keyHash);
}
