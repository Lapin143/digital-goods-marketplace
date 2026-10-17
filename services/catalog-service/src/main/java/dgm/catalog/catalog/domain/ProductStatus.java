package dgm.catalog.catalog.domain;

import java.util.Arrays;
import java.util.Optional;

/** Статус товара (SM-04). Код совпадает со значением в базе и в контракте. */
public enum ProductStatus {
    DRAFT("draft"),
    ON_MODERATION("on_moderation"),
    PUBLISHED("published"),
    REJECTED("rejected"),
    BLOCKED("blocked"),
    ARCHIVED("archived");

    private final String code;

    ProductStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static ProductStatus fromCode(String code) {
        return find(code).orElseThrow(() -> new IllegalArgumentException("Неизвестный статус товара: " + code));
    }

    public static Optional<ProductStatus> find(String code) {
        return Arrays.stream(values()).filter(s -> s.code.equals(code)).findFirst();
    }
}
