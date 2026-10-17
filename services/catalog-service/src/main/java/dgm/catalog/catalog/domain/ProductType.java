package dgm.catalog.catalog.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Тип товара (conventions.md, раздел 7.3). */
public enum ProductType {
    GAME_KEY("game_key"),
    SOFTWARE_LICENSE("software_license"),
    GIFT_CARD("gift_card"),
    SUBSCRIPTION("subscription");

    private final String code;

    ProductType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static ProductType fromCode(String code) {
        return find(code).orElseThrow(() -> new IllegalArgumentException("Неизвестный тип товара: " + code));
    }

    public static Optional<ProductType> find(String code) {
        return Arrays.stream(values()).filter(t -> t.code.equals(code)).findFirst();
    }

    public static List<String> codes() {
        return Arrays.stream(values()).map(ProductType::code).toList();
    }
}
