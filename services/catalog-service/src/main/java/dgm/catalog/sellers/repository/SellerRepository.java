package dgm.catalog.sellers.repository;

import dgm.catalog.sellers.domain.ReviewQueueItem;
import dgm.catalog.sellers.domain.ReviewQueuePage;
import dgm.catalog.sellers.domain.SellerDocument;
import dgm.catalog.sellers.domain.SellerProfile;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Профили продавцов и документы заявок в схеме {@code seller_onboarding}. Изменения идут в транзакции вызывающего сервиса
 * (модуль {@code seller_onboarding}); блокировка {@link #lockByUser} вне транзакции ничего не держит.
 */
public interface SellerRepository {

    /** Профиль пользователя без блокировки. */
    Optional<SellerProfile> findByUser(UUID userId);

    /** Профиль пользователя с блокировкой строки до конца транзакции: действия одного заявителя идут по очереди. */
    Optional<SellerProfile> lockByUser(UUID userId);

    /** Профиль по идентификатору без блокировки: карточка заявки у модератора. */
    Optional<SellerProfile> findById(UUID sellerId);

    /** Профиль по идентификатору с блокировкой строки до конца транзакции: два решения по одной заявке идут по очереди. */
    Optional<SellerProfile> lockById(UUID sellerId);

    /**
     * Заявки на проверке, старые выше (индекс {@code ix_seller_profile_review_queue}).
     *
     * @param after строго после этой пары «момент подачи, идентификатор»; пусто: с начала
     * @param limit сколько строк вернуть
     */
    List<ReviewQueueItem> reviewQueue(Optional<ReviewQueuePage.After> after, int limit);

    /** Новый профиль. {@code false}, если у пользователя профиль уже есть (INV-39): гонку двух запросов решает уникальный индекс. */
    boolean insert(SellerProfile profile);

    /**
     * Записывает новое состояние профиля, если версия в базе равна {@code expectedVersion}.
     *
     * @return {@code false}, если версия в базе другая (профиль изменили между чтением и записью)
     */
    boolean update(SellerProfile profile, int expectedVersion);

    List<SellerDocument> documents(UUID sellerId);

    int countDocuments(UUID sellerId);

    void insertDocument(SellerDocument document);

    Optional<SellerDocument> findDocument(UUID sellerId, UUID documentId);

    /** @return {@code false}, если такого документа у профиля нет */
    boolean deleteDocument(UUID sellerId, UUID documentId);

    /**
     * Документ пользователя по идентификатору, без блокировки: для выдачи ссылки.
     */
    Optional<SellerDocument> findUserDocument(UUID userId, UUID documentId);

    /**
     * Помечает просроченные заявки (на проверке, срок вышел, пометки нет): один раз на заявку.
     *
     * @return идентификаторы помеченных профилей
     */
    List<UUID> markOverdue(Instant now);
}
