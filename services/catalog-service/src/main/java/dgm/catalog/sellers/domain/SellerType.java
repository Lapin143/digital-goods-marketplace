package dgm.catalog.sellers.domain;

import java.util.Arrays;
import java.util.Optional;

/** Тип продавца (conventions.md, раздел 7.3). */
public enum SellerType {
    SOLE_PROPRIETOR("sole_proprietor"),
    SELF_EMPLOYED("self_employed"),
    LEGAL_ENTITY("legal_entity");

    private final String code;

    SellerType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<SellerType> fromCode(String code) {
        return Arrays.stream(values()).filter(t -> t.code.equals(code)).findFirst();
    }

    /** Допустимые коды для сообщения об ошибке. */
    public static String codes() {
        return String.join(", ", Arrays.stream(values()).map(SellerType::code).toList());
    }
}
