package dgm.platform.identity.otp.crypto;

import dgm.kit.secret.SecretValue;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Хеши персональных полей для журнала аудита (ADR-014): {@code K_user = HMAC(секрет_аудита, соль_пользователя)},
 * {@code хеш = HMAC(K_user, имя_поля || 0x00 || значение)}. Персональные значения не покидают сервис в открытом виде, а удаление соли при
 * анонимизации делает хеши несопоставимыми. Секрет {@code platform_audit_hmac} читается в этом пакете (правило ArchUnit про секреты).
 */
public final class AuditHasher {

    private static final String ALGORITHM = "HmacSHA256";
    private static final HexFormat HEX = HexFormat.of();

    private final byte[] secret;

    private AuditHasher(byte[] secret) {
        if (secret.length < 16) {
            throw new IllegalArgumentException("Секрет аудита короче 16 байт");
        }
        this.secret = secret;
    }

    public static AuditHasher fromSecret(SecretValue secret) {
        return new AuditHasher(secret.revealBytes());
    }

    /** Для тестов: ключ задаётся байтами. */
    public static AuditHasher ofBytes(byte[] secret) {
        return new AuditHasher(secret.clone());
    }

    /**
     * @param salt  соль пользователя (16 байт)
     * @param field имя поля: одно значение в разных полях даёт разные хеши
     * @param value нормализованное значение
     */
    public String hash(byte[] salt, String field, String value) {
        byte[] userKey = mac(secret, salt);
        byte[] input = (field + '\0' + value).getBytes(StandardCharsets.UTF_8);
        return HEX.formatHex(mac(userKey, input));
    }

    private static byte[] mac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA-256 недоступен в этой JVM", e);
        }
    }
}
