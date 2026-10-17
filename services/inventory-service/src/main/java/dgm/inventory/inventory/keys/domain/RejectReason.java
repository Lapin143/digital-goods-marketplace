package dgm.inventory.inventory.keys.domain;

/** Почему строка загрузки отклонена (схема {@code RejectedLine.reason}). Значение ключа в отчёт не попадает. */
public enum RejectReason {
    EMPTY("empty"),
    TOO_LONG("too_long"),
    DUPLICATE_IN_UPLOAD("duplicate_in_upload"),
    DUPLICATE_IN_POOL("duplicate_in_pool");

    private final String code;

    RejectReason(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
