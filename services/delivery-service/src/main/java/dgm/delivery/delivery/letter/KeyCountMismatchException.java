package dgm.delivery.delivery.letter;

/** Число значений ключей не равно количеству в заказе (INV-19): письмо неполным не отправляется. */
public final class KeyCountMismatchException extends RuntimeException {

    private final int expected;
    private final int actual;

    public KeyCountMismatchException(int expected, int actual) {
        super("Ключей в ответе " + actual + ", в заказе " + expected);
        this.expected = expected;
        this.actual = actual;
    }

    /** Сколько ключей в заказе. */
    public int expected() {
        return expected;
    }

    /** Сколько значений пришло. */
    public int actual() {
        return actual;
    }
}
