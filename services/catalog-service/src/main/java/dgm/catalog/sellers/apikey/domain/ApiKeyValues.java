package dgm.catalog.sellers.apikey.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Значение API-ключа и всё, что из него получают (ADR-027, п. 1): префикс {@code dgm_} и 40 знаков base64url от 30 случайных байт
 * (240 бит), хеш SHA-256 для хранения и поиска, отпечаток из первых восьми знаков. Хеш без соли и без медленной функции допустим:
 * значение случайное и длинное, перебор невозможен, а поиск идёт по самому хешу.
 */
public final class ApiKeyValues {

    public static final String PREFIX = "dgm_";
    public static final int RANDOM_BYTES = 30;
    public static final int FINGERPRINT_LENGTH = 8;
    public static final int HASH_BYTES = 32;

    private static final Pattern WELL_FORMED = Pattern.compile("^dgm_[A-Za-z0-9_-]{40}$");
    private static final Pattern HASH_HEX = Pattern.compile("^[0-9a-f]{64}$");

    /** Источник случайных байт: в сервисе {@code SecureRandom}, в тестах подставной. */
    @FunctionalInterface
    public interface RandomBytes {
        byte[] next(int length);
    }

    private ApiKeyValues() {
    }

    /** Новое значение. Источник обязан вернуть ровно {@value #RANDOM_BYTES} байт, иначе это ошибка настройки и ключ не выпускается. */
    public static String generate(RandomBytes random) {
        byte[] bytes = random.next(RANDOM_BYTES);
        if (bytes == null || bytes.length != RANDOM_BYTES) {
            throw new IllegalStateException("Источник случайных байт вернул не " + RANDOM_BYTES + " байт");
        }
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static boolean isWellFormed(String value) {
        return value != null && WELL_FORMED.matcher(value).matches();
    }

    /** SHA-256 значения: 32 байта, они и хранятся в {@code api_key.key_hash}. */
    public static byte[] hash(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    /** Первые восемь знаков после префикса: продавец узнаёт ключ в списке, они же в журналах. */
    public static String fingerprint(String value) {
        if (!isWellFormed(value)) {
            throw new IllegalArgumentException("Значение ключа не того вида");
        }
        return value.substring(PREFIX.length(), PREFIX.length() + FINGERPRINT_LENGTH);
    }

    /** Хеш из запроса шлюза: ровно 64 шестнадцатеричных знака в нижнем регистре. */
    public static boolean isHashHex(String value) {
        return value != null && HASH_HEX.matcher(value).matches();
    }

    public static byte[] decodeHash(String hex) {
        if (!isHashHex(hex)) {
            throw new IllegalArgumentException("Хеш ключа это 64 шестнадцатеричных знака в нижнем регистре");
        }
        return HexFormat.of().parseHex(hex);
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 есть в каждой JVM", e);
        }
    }
}
