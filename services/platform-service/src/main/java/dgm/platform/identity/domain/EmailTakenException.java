package dgm.platform.identity.domain;

/** Адрес уже стоит у другой учётной записи: сработал уникальный индекс {@code uq_user_account_email} (INV-34). Значение адреса в тексте не повторяется. */
public final class EmailTakenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EmailTakenException(Throwable cause) {
        super("Адрес уже привязан к другой учётной записи", cause);
    }
}
