package dgm.catalog.catalog.seller.service;

import dgm.catalog.catalog.domain.Product;
import dgm.catalog.catalog.domain.ProductForm;
import dgm.catalog.catalog.domain.ProductPage;
import dgm.catalog.catalog.domain.ProductSettings;
import dgm.catalog.catalog.domain.ProductStatus;
import dgm.catalog.catalog.seller.repository.SellerProductRepository;
import dgm.catalog.catalog.service.CatalogTransaction;
import dgm.catalog.catalog.service.ProductEvents;
import dgm.catalog.sellers.api.ApprovedSellers;
import dgm.kit.id.Uuids;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Товары в кабинете продавца (OP-20, OP-21, OP-25, OP-26): создать, показать, править, отправить на модерацию, архивировать,
 * восстановить. Правила статусов и сроков лежат в {@link Product}, здесь порядок проверок и запись.
 *
 * <p>Условие U3: продавец одобрен (интерфейс модуля {@code sellers}) и товар его (идентификатор профиля из токена). Одобрение
 * проверяется до транзакции, отдельным чтением: транзакция каталога не держит соединение соседнего модуля. Чужой товар не отличается
 * от несуществующего: 404 (ST-02). Порядок отказов при изменении: профиль не одобрен (403), нет товара (404), статус (409), версия (412).
 * Каждое изменение пишет событие в Outbox той же транзакцией: {@code product.created} при создании, {@code product.updated} при остальных.
 */
@Component
public final class SellerProductService {

    private final SellerProductRepository repository;
    private final CatalogTransaction transaction;
    private final ApprovedSellers sellers;
    private final Clock clock;
    private final ProductSettings settings;

    public SellerProductService(SellerProductRepository repository, CatalogTransaction transaction, ApprovedSellers sellers, Clock clock,
                                ProductSettings settings) {
        this.repository = repository;
        this.transaction = transaction;
        this.sellers = sellers;
        this.clock = clock;
        this.settings = settings;
    }

    /** SM-04/T1: товар в черновике. Повтор запроса с тем же ключом идемпотентности товар не дублирует (охранник каркаса). */
    public Product create(UUID sellerId, ProductForm form) {
        requireApproved(sellerId);
        Product product = Product.create(Uuids.v7(clock), sellerId, form, clock.instant());
        return transaction.execute(() -> {
            repository.insert(product);
            transaction.publish(ProductEvents.TOPIC, ProductEvents.AGGREGATE, product.id().toString(), ProductEvents.CREATED,
                    ProductEvents.SCHEMA_VERSION, ProductEvents.created(product));
            return product;
        });
    }

    public ProductPage list(UUID sellerId, List<ProductStatus> statuses, int limit, Optional<ProductPage.After> after) {
        requireApproved(sellerId);
        List<Product> rows = repository.list(sellerId, statuses, after, limit + 1);
        boolean more = rows.size() > limit;
        List<Product> items = more ? rows.subList(0, limit) : rows;
        Optional<ProductPage.After> next = more ? Optional.of(new ProductPage.After(items.get(limit - 1).createdAt(), items.get(limit - 1).id()))
                : Optional.empty();
        return new ProductPage(List.copyOf(items), limit, next);
    }

    public Product get(UUID sellerId, UUID productId) {
        requireApproved(sellerId);
        return repository.find(sellerId, productId).orElseThrow(SellerProductService::notFound);
    }

    /**
     * Правка полей. {@code expectedVersion} это версия из {@code If-Match}. Правка без отличий ничего не пишет и события не даёт.
     * Название, описание или тип опубликованного товара возвращают его на модерацию (T6), остальное нет (T7).
     */
    public Product replace(UUID sellerId, UUID productId, long expectedVersion, ProductForm form) {
        requireApproved(sellerId);
        return transaction.execute(() -> {
            Product current = lockOwned(sellerId, productId);
            current.requireEditable();
            if (current.version() != expectedVersion) {
                throw versionConflict();
            }
            Product.Edited edited = current.replace(form, clock.instant(), settings.reviewPeriod());
            if (edited.changed()) {
                save(edited.product(), current.version(), edited.changedFields());
            }
            return edited.product();
        });
    }

    /** SM-04/T2, T5, T11: товар встаёт в очередь модератора, срок проверки идёт от этой подачи. */
    public Product submit(UUID sellerId, UUID productId) {
        requireApproved(sellerId);
        return transaction.execute(() -> {
            Product current = lockOwned(sellerId, productId);
            Product submitted = current.submit(clock.instant(), settings.reviewPeriod());
            save(submitted, current.version(), List.of("status"));
            return submitted;
        });
    }

    /** SM-04/T12: товар снят с продажи в архив. */
    public Product archive(UUID sellerId, UUID productId) {
        requireApproved(sellerId);
        return transaction.execute(() -> {
            Product current = lockOwned(sellerId, productId);
            Product archived = current.archive(clock.instant());
            save(archived, current.version(), List.of("status"));
            return archived;
        });
    }

    /** SM-04/T13: товар возвращён из архива в черновик. */
    public Product restore(UUID sellerId, UUID productId) {
        requireApproved(sellerId);
        return transaction.execute(() -> {
            Product current = lockOwned(sellerId, productId);
            Product restored = current.restore(clock.instant());
            save(restored, current.version(), List.of("status"));
            return restored;
        });
    }

    private Product lockOwned(UUID sellerId, UUID productId) {
        return repository.lock(sellerId, productId).orElseThrow(SellerProductService::notFound);
    }

    private void save(Product product, int expectedVersion, List<String> changedFields) {
        if (!repository.update(product, expectedVersion)) {
            throw versionConflict();
        }
        transaction.publish(ProductEvents.TOPIC, ProductEvents.AGGREGATE, product.id().toString(), ProductEvents.UPDATED,
                ProductEvents.SCHEMA_VERSION, ProductEvents.updated(product, changedFields));
    }

    private void requireApproved(UUID sellerId) {
        if (!sellers.isApproved(sellerId)) {
            throw new ProblemException(ProblemType.FORBIDDEN, "Профиль продавца не одобрен: товары доступны после одобрения заявки.");
        }
    }

    static ProblemException notFound() {
        return new ProblemException(ProblemType.NOT_FOUND, "Товар не найден.");
    }

    private static ProblemException versionConflict() {
        return new ProblemException(ProblemType.VERSION_CONFLICT, "Товар изменился, перечитайте его и повторите с новым ETag.");
    }
}
