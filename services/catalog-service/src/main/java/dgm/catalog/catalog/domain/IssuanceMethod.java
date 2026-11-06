package dgm.catalog.catalog.domain;

import java.util.Arrays;
import java.util.Optional;

/** Способ выдачи: пул ключей (R1) или выдача по запросу к системе продавца (R2, Р-184). Задаётся при создании товара и не меняется. */
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

    public static IssuanceMethod fromCode(String code) {
        return find(code).orElseThrow(() -> new IllegalArgumentException("Неизвестный способ выдачи: " + code));
    }

    public static Optional<IssuanceMethod> find(String code) {
        return Arrays.stream(values()).filter(m -> m.code.equals(code)).findFirst();
    }
}
