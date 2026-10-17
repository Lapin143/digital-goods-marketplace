package dgm.inventory.inventory.crypto;

import dgm.kit.secret.SecretValue;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA-256 значения ключа для поиска дублей (ADR-009, INV-03): {@code HMAC(секрет, идентификатор_товара ‖ 0x00 ‖ значение)}, значение
 * перед подписью нормализуется: убираются пробелы и перевод строки по краям, регистр значим. Идентификатор товара в подписи делает
 * одинаковые значения разных товаров несопоставимыми.
 */
public final class KeyHasher {

    private static final int MIN_SECRET_BYTES = 16;

    private final byte[] secret;

    public KeyHasher(byte[] secret) {
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("Секрет HMAC короче 16 байт");
        }
        this.secret = secret.clone();
    }

    /** Секрет {@code inventory_hmac}: Base64 от случайных байт (формат {@code key32}) или любой текст не короче 16 байт. */
    public static KeyHasher fromSecret(SecretValue value) {
        byte[] text = value.revealBytes();
        try {
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(text);
            } catch (IllegalArgumentException e) {
                bytes = text.clone();
            }
            try {
                return new KeyHasher(bytes);
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        } finally {
            Arrays.fill(text, (byte) 0);
        }
    }

    /** Значение без пробелов по краям: именно оно шифруется и подписывается. */
    public static SecretValue normalize(String raw) {
        return SecretValue.of(raw.strip());
    }

    public byte[] hmac(UUID productId, SecretValue normalized) {
        byte[] plain = normalized.revealBytes();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(productId.toString().getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) 0);
            mac.update(plain);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new CryptoException("Не удалось вычислить HMAC", e);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    /** Запись HMAC для множеств и сообщений: 64 шестнадцатеричных знака. */
    public static String hex(byte[] hmac) {
        return HexFormat.of().formatHex(hmac);
    }
}
