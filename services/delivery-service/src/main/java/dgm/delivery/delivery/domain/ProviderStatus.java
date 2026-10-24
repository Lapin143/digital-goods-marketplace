package dgm.delivery.delivery.domain;

import java.util.Locale;

/**
 * Статус письма по данным e-mail-провайдера (схема {@code EmailStatusNotification}, ответ запроса статуса). Перечисление открытое: статус,
 * которого платформа не знает, это {@link #OTHER}, он принимается и ничего не меняет (контракт веб-входа, NFT-2.3).
 */
public enum ProviderStatus {

    /** Провайдер принял письмо в работу. Платформа это уже знает (T2), статус только записывается. */
    ACCEPTED("accepted"),

    /** Письмо доставлено в почтовый ящик (T5). */
    DELIVERED("delivered"),

    /** Письмо не доставлено: отказ или неверный адрес (T6). */
    FAILED("failed"),

    /** Статус, которого платформа не знает. */
    OTHER("other");

    private final String wire;

    ProviderStatus(String wire) {
        this.wire = wire;
    }

    /** Значение в теле запроса и в таблице {@code provider_status_event}. */
    public String wire() {
        return wire;
    }

    /** Разбирает значение провайдера; незнакомое значение это {@link #OTHER}. */
    public static ProviderStatus parse(String value) {
        if (value != null) {
            String normalized = value.toLowerCase(Locale.ROOT);
            for (ProviderStatus status : values()) {
                if (status != OTHER && status.wire.equals(normalized)) {
                    return status;
                }
            }
        }
        return OTHER;
    }
}
