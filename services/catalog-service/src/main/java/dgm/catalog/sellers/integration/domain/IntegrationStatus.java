package dgm.catalog.sellers.integration.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * Статус подключения системы продавца: {@code active}, платформа вызывает адрес, или {@code disabled}, адрес сохранён, вызовов нет
 * (ограничение {@code ck_seller_integration_status} в DDL).
 */
public enum IntegrationStatus {
    ACTIVE("active"),
    DISABLED("disabled");

    private final String code;

    IntegrationStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<IntegrationStatus> fromCode(String code) {
        return Arrays.stream(values()).filter(status -> status.code.equals(code)).findFirst();
    }
}
