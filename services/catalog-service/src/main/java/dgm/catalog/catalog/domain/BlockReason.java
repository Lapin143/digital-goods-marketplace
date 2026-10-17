package dgm.catalog.catalog.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * Причина блокировки товара (SM-04, решение 1). В R1 бывает только решение модератора; блокировка продавца придёт в R2 вместе с
 * переходами T9 и T10.
 */
public enum BlockReason {
    MODERATOR_DECISION("moderator_decision"),
    SELLER_BLOCKED("seller_blocked");

    private final String code;

    BlockReason(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static BlockReason fromCode(String code) {
        return find(code).orElseThrow(() -> new IllegalArgumentException("Неизвестная причина блокировки: " + code));
    }

    public static Optional<BlockReason> find(String code) {
        return Arrays.stream(values()).filter(r -> r.code.equals(code)).findFirst();
    }
}
