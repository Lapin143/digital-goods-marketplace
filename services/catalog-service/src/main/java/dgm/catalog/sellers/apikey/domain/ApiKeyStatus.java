package dgm.catalog.sellers.apikey.domain;

import java.util.Arrays;
import java.util.Optional;

/** Статус API-ключа: {@code active} работает, {@code revoked} отозван навсегда (конечный статус, ограничение {@code ck_api_key_status} в DDL). */
public enum ApiKeyStatus {
    ACTIVE("active"),
    REVOKED("revoked");

    private final String code;

    ApiKeyStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<ApiKeyStatus> fromCode(String code) {
        return Arrays.stream(values()).filter(status -> status.code.equals(code)).findFirst();
    }
}
