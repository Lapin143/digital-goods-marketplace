package dgm.catalog.catalog.domain;

import java.util.Arrays;
import java.util.Optional;

/** Способ выдачи. В R1 доступен только пул ключей; выдача через API продавца придёт в R2 (FT-4.0). */
public enum IssuanceMethod {
    KEY_POOL("key_pool"),
    SELLER_API("seller_api");

    private final String code;

    IssuanceMethod(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** Способ можно выбрать в текущем релизе. */
    public boolean availableInRelease() {
        return this == KEY_POOL;
    }

    public static IssuanceMethod fromCode(String code) {
        return find(code).orElseThrow(() -> new IllegalArgumentException("Неизвестный способ выдачи: " + code));
    }

    public static Optional<IssuanceMethod> find(String code) {
        return Arrays.stream(values()).filter(m -> m.code.equals(code)).findFirst();
    }
}
