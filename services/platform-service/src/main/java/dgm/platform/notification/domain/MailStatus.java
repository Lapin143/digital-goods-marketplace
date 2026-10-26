package dgm.platform.notification.domain;

import java.util.Locale;

/**
 * Статус письма по данным почтового провайдера платформы (схема {@code MailProviderNotification}). Перечисление открытое: статус, которого
 * платформа не знает, это {@link #OTHER}, он принимается и ничего не меняет (контракт веб-входа, NFT-2.3).
 */
public enum MailStatus {

    /** Провайдер принял письмо в работу. Платформа это уже знает (статус {@code sent}), значение только записывается. */
    ACCEPTED("accepted"),

    /** Письмо доставлено в почтовый ящик (уведомление {@code sent} становится {@code delivered}). */
    DELIVERED("delivered"),

    /** Письмо не доставлено (уведомление становится {@code failed}, событие {@code notification.failed}). */
    FAILED("failed"),

    /** Статус, которого платформа не знает. */
    OTHER("other");

    private final String wire;

    MailStatus(String wire) {
        this.wire = wire;
    }

    /** Значение в теле запроса и в таблице {@code provider_status_event}. */
    public String wire() {
        return wire;
    }

    /** Разбирает значение провайдера; незнакомое значение это {@link #OTHER}. */
    public static MailStatus parse(String value) {
        if (value != null) {
            String normalized = value.toLowerCase(Locale.ROOT);
            for (MailStatus status : values()) {
                if (status != OTHER && status.wire.equals(normalized)) {
                    return status;
                }
            }
        }
        return OTHER;
    }
}
