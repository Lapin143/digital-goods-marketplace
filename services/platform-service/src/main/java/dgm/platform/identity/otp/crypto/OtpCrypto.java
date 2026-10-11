package dgm.platform.identity.otp.crypto;

import dgm.kit.secret.SecretValue;
import dgm.platform.identity.otp.domain.OtpPurpose;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA-256 с секретом {@code otp_pepper} (ADR-010): хеш кода для хранения в Redis и обезличенные идентификаторы номера и IP в ключах
 * Redis. Ни сам код, ни номер, ни адрес в Redis открытыми не лежат; без секрета хеш шестизначного кода не перебрать, как бы ни был
 * устроен дамп хранилища.
 *
 * <p>Секрет читается здесь (пакет {@code crypto}): правило ArchUnit разрешает {@link SecretValue#revealBytes()} только в таких модулях.
 */
public final class OtpCrypto {

    private static final String ALGORITHM = "HmacSHA256";
    private static final HexFormat HEX = HexFormat.of();

    private final SecretKeySpec key;

    private OtpCrypto(byte[] secret) {
        if (secret.length < 16) {
            throw new IllegalArgumentException("Секрет otp_pepper короче 16 байт");
        }
        this.key = new SecretKeySpec(secret, ALGORITHM);
    }

    public static OtpCrypto fromSecret(SecretValue secret) {
        return new OtpCrypto(secret.revealBytes());
    }

    /** Для тестов: ключ задаётся байтами. */
    public static OtpCrypto ofBytes(byte[] secret) {
        return new OtpCrypto(secret.clone());
    }

    /** HMAC кода: назначение и цель входят в хеш, поэтому код нельзя применить к другому назначению или номеру. */
    public String codeHash(OtpPurpose purpose, String target, String code) {
        return hmac("code", purpose.code(), target, code);
    }

    /** Обезличенное значение для ключей Redis: {@code label} разделяет пространства (номер, IP, приманка). */
    public String target(String label, String value) {
        return hmac("target", label, value);
    }

    private String hmac(String... parts) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) {
                    mac.update((byte) 0);
                }
                mac.update(parts[i].getBytes(StandardCharsets.UTF_8));
            }
            return HEX.formatHex(mac.doFinal());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA-256 недоступен в этой JVM", e);
        }
    }
}
