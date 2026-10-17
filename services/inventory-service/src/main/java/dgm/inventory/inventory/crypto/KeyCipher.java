package dgm.inventory.inventory.crypto;

import dgm.kit.secret.SecretValue;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Двухуровневое шифрование значений ключей (ADR-009): мастер-ключ (KEK) шифрует ключи данных (DEK), DEK шифрует значения.
 * Алгоритм AES-256-GCM, nonce 96 бит, случайный на каждое шифрование. Дополнительные аутентифицируемые данные (AAD) значения это
 * идентификатор ключа и идентификатор товара: шифртекст, перенесённый в другую строку или в другой товар, не расшифруется.
 *
 * <p>Открытое значение живёт только в {@link SecretValue}; массивы байт, созданные на время операции, стираются сразу после неё.
 * Сообщения исключений не содержат значений.
 */
public final class KeyCipher {

    /** Длина nonce в байтах (96 бит, рекомендация NIST для GCM). */
    public static final int NONCE_BYTES = 12;
    /** Длина DEK и KEK в байтах (256 бит). */
    public static final int KEY_BYTES = 32;
    /** Длина обёрнутого DEK: nonce, 32 байта шифртекста и тег 16 байт (ограничение {@code ck_data_key_wrapped_key}). */
    public static final int WRAPPED_BYTES = NONCE_BYTES + KEY_BYTES + 16;

    private static final int TAG_BITS = 128;
    private static final byte[] WRAP_AAD = "dgm.inventory.data_key".getBytes(StandardCharsets.US_ASCII);

    private final byte[] kek;
    private final SecureRandom random;

    public KeyCipher(byte[] kek, SecureRandom random) {
        if (kek.length != KEY_BYTES) {
            throw new IllegalArgumentException("Мастер-ключ должен быть 256 бит");
        }
        this.kek = kek.clone();
        this.random = random;
    }

    /** Мастер-ключ из секрета {@code inventory_kek}: 32 байта в Base64 (формат {@code key32} генератора секретов). */
    public static KeyCipher fromSecret(SecretValue secret, SecureRandom random) {
        byte[] text = secret.revealBytes();
        try {
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(text);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("Секрет inventory_kek не является Base64");
            }
            try {
                if (decoded.length != KEY_BYTES) {
                    throw new IllegalStateException("Секрет inventory_kek должен содержать 32 байта (256 бит)");
                }
                return new KeyCipher(decoded, random);
            } finally {
                Arrays.fill(decoded, (byte) 0);
            }
        } finally {
            Arrays.fill(text, (byte) 0);
        }
    }

    /** Новый случайный ключ данных. */
    public byte[] newDek() {
        byte[] dek = new byte[KEY_BYTES];
        random.nextBytes(dek);
        return dek;
    }

    /** DEK, зашифрованный мастер-ключом: nonce, шифртекст и тег подряд ({@link #WRAPPED_BYTES} байт). */
    public byte[] wrap(byte[] dek) {
        byte[] nonce = nonce();
        byte[] sealed = run(Cipher.ENCRYPT_MODE, kek, nonce, WRAP_AAD, dek);
        return ByteBuffer.allocate(NONCE_BYTES + sealed.length).put(nonce).put(sealed).array();
    }

    /** @throws CryptoException если мастер-ключ другой или запись изменена */
    public byte[] unwrap(byte[] wrapped) {
        if (wrapped.length != WRAPPED_BYTES) {
            throw new CryptoException("Обёрнутый ключ данных имеет неверную длину");
        }
        return run(Cipher.DECRYPT_MODE, kek, Arrays.copyOfRange(wrapped, 0, NONCE_BYTES), WRAP_AAD,
                Arrays.copyOfRange(wrapped, NONCE_BYTES, wrapped.length));
    }

    /** Шифрует значение ключом данных. */
    public Sealed seal(byte[] dek, UUID keyId, UUID productId, SecretValue value) {
        byte[] nonce = nonce();
        byte[] plain = value.revealBytes();
        try {
            return new Sealed(nonce, run(Cipher.ENCRYPT_MODE, dek, nonce, aad(keyId, productId), plain));
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    /**
     * Расшифровывает значение.
     *
     * @throws CryptoException если ключ данных другой, запись изменена или значение перенесено в другую строку или товар
     */
    public SecretValue open(byte[] dek, UUID keyId, UUID productId, byte[] nonce, byte[] ciphertext) {
        if (nonce.length != NONCE_BYTES) {
            throw new CryptoException("Nonce имеет неверную длину");
        }
        byte[] plain = run(Cipher.DECRYPT_MODE, dek, nonce, aad(keyId, productId), ciphertext);
        try {
            return SecretValue.ofBytes(plain);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    static byte[] aad(UUID keyId, UUID productId) {
        return ByteBuffer.allocate(32).putLong(keyId.getMostSignificantBits()).putLong(keyId.getLeastSignificantBits())
                .putLong(productId.getMostSignificantBits()).putLong(productId.getLeastSignificantBits()).array();
    }

    private byte[] nonce() {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        return nonce;
    }

    private static byte[] run(int mode, byte[] key, byte[] nonce, byte[] aad, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            // Текст исходной ошибки не нужен: он ничего не добавляет к «не расшифровалось», а класс сохраняется для разбора
            throw new CryptoException(mode == Cipher.ENCRYPT_MODE ? "Не удалось зашифровать" : "Не удалось расшифровать: ключ другой или данные изменены",
                    e);
        }
    }

    /**
     * Результат шифрования значения.
     *
     * @param nonce      96 бит
     * @param ciphertext шифртекст вместе с тегом целостности
     */
    public record Sealed(byte[] nonce, byte[] ciphertext) {
    }
}
