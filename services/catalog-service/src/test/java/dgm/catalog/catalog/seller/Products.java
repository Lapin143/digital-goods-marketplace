package dgm.catalog.catalog.seller;

import dgm.catalog.catalog.domain.BlockReason;
import dgm.catalog.catalog.domain.IssuanceMethod;
import dgm.catalog.catalog.domain.Product;
import dgm.catalog.catalog.domain.ProductForm;
import dgm.catalog.catalog.domain.ProductStatus;
import dgm.catalog.catalog.domain.ProductType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Образцы товаров для модульных тестов: форма как в примере контракта, товар в любом статусе с согласованными полями (INV-40). */
public final class Products {

    public static final UUID SELLER = UUID.fromString("0199e0a0-0000-7000-8000-000000000101");
    public static final UUID OTHER_SELLER = UUID.fromString("0199e0a0-0000-7000-8000-000000000102");
    public static final Instant T0 = Instant.parse("2026-10-03T12:00:00Z");
    public static final Duration REVIEW = Duration.ofDays(3);

    private Products() {
    }

    public static UUID id(int n) {
        return UUID.fromString(String.format("0199e0a0-0000-7000-8000-%012d", 200 + n));
    }

    public static ProductForm form() {
        return new ProductForm("Лунная тропа: ключ Steam", "Приключенческая игра, ключ активации Steam", ProductType.GAME_KEY, "Steam",
                List.of("RU", "KZ"), 149_900L, IssuanceMethod.KEY_POOL);
    }

    public static ProductForm withTitle(ProductForm f, String title) {
        return new ProductForm(title, f.description(), f.productType(), f.platform(), f.activationRegions(), f.price(), f.issuanceMethod());
    }

    public static ProductForm withDescription(ProductForm f, String description) {
        return new ProductForm(f.title(), description, f.productType(), f.platform(), f.activationRegions(), f.price(), f.issuanceMethod());
    }

    public static ProductForm withType(ProductForm f, ProductType type) {
        return new ProductForm(f.title(), f.description(), type, f.platform(), f.activationRegions(), f.price(), f.issuanceMethod());
    }

    public static ProductForm withPlatform(ProductForm f, String platform) {
        return new ProductForm(f.title(), f.description(), f.productType(), platform, f.activationRegions(), f.price(), f.issuanceMethod());
    }

    public static ProductForm withRegions(ProductForm f, List<String> regions) {
        return new ProductForm(f.title(), f.description(), f.productType(), f.platform(), regions, f.price(), f.issuanceMethod());
    }

    public static ProductForm withPrice(ProductForm f, long price) {
        return new ProductForm(f.title(), f.description(), f.productType(), f.platform(), f.activationRegions(), price, f.issuanceMethod());
    }

    public static ProductForm withIssuance(ProductForm f, IssuanceMethod method) {
        return new ProductForm(f.title(), f.description(), f.productType(), f.platform(), f.activationRegions(), f.price(), method);
    }

    /** Товар в заданном статусе версии {@code version}; блокированный блокирован модератором с пояснением. */
    public static Product in(ProductStatus status, int version) {
        return in(id(1), SELLER, form(), status, version, T0);
    }

    public static Product in(UUID id, UUID seller, ProductForm form, ProductStatus status, int version, Instant created) {
        Instant submitted = status == ProductStatus.ON_MODERATION ? created.plusSeconds(60) : null;
        Instant deadline = submitted == null ? null : submitted.plus(REVIEW);
        Instant published = status == ProductStatus.PUBLISHED ? created.plusSeconds(120) : null;
        String rejection = status == ProductStatus.REJECTED ? "Описание не соответствует товару." : null;
        boolean blocked = status == ProductStatus.BLOCKED;
        return new Product(id, seller, form, rejection, blocked ? ProductStatus.PUBLISHED : null, blocked ? BlockReason.MODERATOR_DECISION : null,
                blocked ? "В описании чужой товарный знак." : null, submitted, deadline, null, published, status, version, created, created);
    }

    /** Заблокирован вместе с продавцом (R2): продавец ничего не меняет. */
    public static Product sellerBlocked(int version) {
        Product p = in(ProductStatus.BLOCKED, version);
        return new Product(p.id(), p.sellerId(), p.form(), null, ProductStatus.PUBLISHED, BlockReason.SELLER_BLOCKED, null, null, null, null,
                T0.plusSeconds(120), ProductStatus.BLOCKED, version, p.createdAt(), p.updatedAt());
    }
}
