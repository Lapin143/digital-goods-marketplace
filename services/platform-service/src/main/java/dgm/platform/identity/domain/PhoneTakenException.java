package dgm.platform.identity.domain;

/** Номер уже подтверждён другой учётной записью: сработал уникальный индекс {@code uq_user_account_phone} (INV-34, FT-1.5). */
public final class PhoneTakenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PhoneTakenException(Throwable cause) {
        super("Номер уже привязан к другой учётной записи", cause);
    }
}
