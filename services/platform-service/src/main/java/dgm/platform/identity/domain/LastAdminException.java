package dgm.platform.identity.domain;

/** Изменение оставило бы систему без активного администратора (INV-38); срабатывает ограничение базы. */
public final class LastAdminException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public LastAdminException(Throwable cause) {
        super("Нельзя убрать или деактивировать последнего активного администратора", cause);
    }
}
