package dgm.catalog.catalog.moderation.service;

import dgm.catalog.catalog.cache.ProductCache;
import dgm.catalog.catalog.domain.ModerationQueueItem;
import dgm.catalog.catalog.domain.ModerationQueuePage;
import dgm.catalog.catalog.domain.Moderator;
import dgm.catalog.catalog.domain.Product;
import dgm.catalog.catalog.domain.ProductStatus;
import dgm.catalog.catalog.seller.repository.SellerProductRepository;
import dgm.catalog.catalog.service.CatalogTransaction;
import dgm.catalog.catalog.service.ProductEvents;
import dgm.catalog.sellers.api.SellerIntegrations;
import dgm.kit.outbox.AuditRecords;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * Товары на модерации (OP-22 – OP-24; US-3.3 – US-3.5): очередь, карточка и три решения. Правила статусов лежат в {@link Product}, здесь
 * порядок действий.
 *
 * <p>Решение, событие в Outbox и запись аудита фиксируются одной транзакцией (INV-43): без любого из трёх не остаётся и остальных.
 * Строка товара блокируется на время решения, поэтому два модератора, решающих один товар, идут по очереди, и второй получает 409 с
 * текущим статусом. Черновик для модератора не существует: 404. Кэш витрины сбрасывается после фиксации и не в потоке запроса.
 */
@Component
public final class ProductModerationService {

    private final SellerProductRepository repository;
    private final CatalogTransaction transaction;
    private final ProductCache cache;
    private final SellerIntegrations integrations;
    private final Clock clock;

    public ProductModerationService(SellerProductRepository repository, CatalogTransaction transaction, ProductCache cache,
                                    SellerIntegrations integrations, Clock clock) {
        this.repository = repository;
        this.transaction = transaction;
        this.cache = cache;
        this.integrations = integrations;
        this.clock = clock;
    }

    /** Очередь на модерации: старые выше. Читается на одно значение больше размера страницы, чтобы узнать, есть ли следующая. */
    public ModerationQueuePage queue(int limit, Optional<ModerationQueuePage.After> after) {
        List<ModerationQueueItem> rows = transaction.execute(() -> repository.moderationQueue(after, limit + 1));
        boolean more = rows.size() > limit;
        List<ModerationQueueItem> items = more ? rows.subList(0, limit) : rows;
        Optional<ModerationQueuePage.After> next = more
                ? Optional.of(new ModerationQueuePage.After(items.get(items.size() - 1).submittedAt(), items.get(items.size() - 1).productId()))
                : Optional.empty();
        return new ModerationQueuePage(List.copyOf(items), limit, next, clock.instant());
    }

    /** Товар целиком. Значений ключей здесь нет и быть не может: их хранит {@code inventory-service} (ST-01). */
    public Product card(UUID productId) {
        return transaction.execute(() -> repository.findById(productId).filter(Product::visibleToModerator).orElseThrow(ProductModerationService::notFound));
    }

    /**
     * SM-04/T3. Товар с выдачей по API (R2) не публикуется без действующего подключения системы продавца: 409 {@code integration-required},
     * товар остаётся на модерации (модератор может отклонить его с причиной). Подключение проверяется до транзакции решения, только для товара
     * на модерации: у решённого товара отказ даёт {@code state-conflict}.
     */
    public Product approve(Moderator moderator, UUID productId) {
        requireIntegration(productId);
        return decide(moderator, productId, ProductEvents.PUBLISHED, ProductEvents::published, (product, now) -> product.approve(now));
    }

    /** SM-04/T4: причина обязательна и видна продавцу. */
    public Product reject(Moderator moderator, UUID productId, String reason) {
        return decide(moderator, productId, ProductEvents.REJECTED, ProductEvents::rejected, (product, now) -> product.reject(reason, now));
    }

    /** SM-04/T8: причина обязательна, товар исчезает с витрины. */
    public Product block(Moderator moderator, UUID productId, String reason) {
        return decide(moderator, productId, ProductEvents.BLOCKED, ProductEvents::blocked, (product, now) -> product.block(reason, now));
    }

    private void requireIntegration(UUID productId) {
        Optional<Product> product = transaction.execute(() -> repository.findById(productId))
                .filter(p -> p.visibleToModerator() && p.sellerApi() && p.status() == ProductStatus.ON_MODERATION);
        if (product.isPresent() && !integrations.hasActive(product.get().sellerId())) {
            throw Product.integrationRequired();
        }
    }

    private Product decide(Moderator moderator, UUID productId, String decision, Function<Product, Map<String, Object>> eventData,
                           BiFunction<Product, Instant, Product> change) {
        return transaction.execute(() -> {
            Product before = repository.lockById(productId).filter(Product::visibleToModerator).orElseThrow(ProductModerationService::notFound);
            Instant now = clock.instant();
            Product after = change.apply(before, now);
            if (!repository.update(after, before.version())) {
                // Строка заблокирована с начала решения, поэтому версия не могла измениться; проверка защищает от ошибки в коде
                throw before.stateConflict();
            }
            transaction.publish(ProductEvents.TOPIC, ProductEvents.AGGREGATE, productId.toString(), decision, ProductEvents.SCHEMA_VERSION,
                    eventData.apply(after));
            transaction.publish(AuditRecords.TOPIC, AuditRecords.AGGREGATE, productId.toString(), AuditRecords.EVENT, AuditRecords.SCHEMA_VERSION,
                    ProductEvents.audit(now, moderator, decision, before, after));
            transaction.afterCommit(() -> cache.evictLater(productId));
            return after;
        });
    }

    private static ProblemException notFound() {
        return new ProblemException(ProblemType.NOT_FOUND, "Товар не найден.");
    }
}
