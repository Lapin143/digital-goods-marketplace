package dgm.inventory.inventory.crypto;

/** Сбой шифрования или проверки целостности. Сообщение не содержит ни значений ключей, ни ключей шифрования (ADR-009). */
public final class CryptoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CryptoException(String message) {
        super(message);
    }

    public CryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
