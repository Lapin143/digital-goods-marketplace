package dgm.catalog.sellers.domain;

import java.util.Arrays;
import java.util.Optional;

/** Статус профиля продавца (SM-05). Код совпадает со значением в базе и в контракте. */
public enum SellerStatus {
    DRAFT("draft"),
    ON_REVIEW("on_review"),
    RETURNED("returned"),
    APPROVED("approved"),
    REJECTED("rejected"),
    BLOCKED("blocked");

    private final String code;

    SellerStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static SellerStatus fromCode(String code) {
        return Arrays.stream(values()).filter(s -> s.code.equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Неизвестный статус профиля продавца: " + code));
    }

    /** Заявитель может править анкету и документы: черновик, доработка и отказ (в отказе правка это переход T6). */
    public boolean applicantMayEdit() {
        return this == DRAFT || this == RETURNED || this == REJECTED;
    }

    /** Переход в {@code target} допустим по SM-05 (T2 … T7, R1). */
    public boolean canMoveTo(SellerStatus target) {
        return SellerTransition.between(this, target).isPresent();
    }

    public Optional<SellerTransition> transitionTo(SellerStatus target) {
        return SellerTransition.between(this, target);
    }
}
