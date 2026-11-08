package dgm.delivery.delivery.domain;

import java.util.Objects;

/**
 * Подключение системы продавца из {@code getSellerEndpoint}: адрес, статус и версия секрета (сам секрет платформа считает по версии). Адрес не печатается
 * в {@link #toString()}: в нём продавец может держать токен.
 */
public record SellerEndpoint(String url, String status, int secretVersion) {

    public SellerEndpoint {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(status, "status");
        if (secretVersion < 1) {
            throw new IllegalArgumentException("Версия секрета начинается с 1");
        }
    }

    /** Подключение действует: запрос можно отправлять. */
    public boolean active() {
        return "active".equals(status);
    }

    @Override
    public String toString() {
        return "SellerEndpoint[status=" + status + ", secretVersion=" + secretVersion + "]";
    }
}
