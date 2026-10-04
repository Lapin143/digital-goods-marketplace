package dgm.kit.outbox;

/** Событие не отправлено. {@link #unrecoverable()}: повтор не поможет (сообщение слишком велико, конверт неверен), строку паркуют сразу. */
public final class PublishException extends Exception {

    private static final long serialVersionUID = 1L;

    private final boolean unrecoverable;

    public PublishException(String message, Throwable cause, boolean unrecoverable) {
        super(message, cause);
        this.unrecoverable = unrecoverable;
    }

    public PublishException(String message, boolean unrecoverable) {
        super(message);
        this.unrecoverable = unrecoverable;
    }

    public boolean unrecoverable() {
        return unrecoverable;
    }
}
