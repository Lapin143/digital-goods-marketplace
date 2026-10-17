package dgm.kit.storage.client;

import dgm.kit.secret.SecretValue;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Подпись запросов S3 по AWS Signature Version 4 (служба {@code s3}). Алгоритм стандартный, продуктовых расширений нет, поэтому
 * та же подпись принимается любым S3-совместимым хранилищем. Класс проверен на примерах из документации AWS (SigV4Test).
 *
 * <p>Два способа подписи: заголовок {@code Authorization} (запись и удаление объектов) и параметры строки запроса (ссылка на
 * скачивание, которую открывает браузер). Подпись ссылки считается без вызова хранилища.
 *
 * <p>Подпись покрывает заголовок {@code Host}: ссылка действует только для того адреса, под которым её подписали. Поэтому ссылка,
 * которую открывают через шлюз, подписывается на адрес шлюза, а шлюз передаёт хранилищу заголовок {@code Host} без изменений.
 */
public final class SigV4 {

    public static final String ALGORITHM = "AWS4-HMAC-SHA256";
    public static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";
    /** Предел срока подписанной ссылки в SigV4: 7 суток. */
    public static final long MAX_EXPIRES_SECONDS = 604_800;

    private static final String SERVICE = "s3";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final HexFormat HEX = HexFormat.of();

    private final String accessKey;
    private final SecretValue secretKey;
    private final String region;

    public SigV4(String accessKey, SecretValue secretKey, String region) {
        this.accessKey = Objects.requireNonNull(accessKey, "accessKey");
        this.secretKey = Objects.requireNonNull(secretKey, "secretKey");
        this.region = Objects.requireNonNull(region, "region");
    }

    /** Момент в виде {@code X-Amz-Date}: {@code 20130524T000000Z}. */
    public static String stamp(Instant instant) {
        return STAMP.format(instant);
    }

    /** SHA-256 в шестнадцатеричной записи, как в {@code x-amz-content-sha256}. */
    public static String sha256Hex(byte[] content) {
        return HEX.formatHex(sha256(content));
    }

    /**
     * Строка запроса подписанной ссылки на скачивание: параметры {@code X-Amz-*} и подпись. Подписывается один заголовок, {@code Host}.
     *
     * @param method         метод, для ссылки на скачивание {@code GET}
     * @param host           значение заголовка Host, под которым ссылку откроют: имя и порт, если порт не стандартный
     * @param canonicalPath  путь, каким его увидит хранилище (с бакетом, уже закодированный по правилам S3, см. {@link #encodePath})
     * @param now            момент подписи
     * @param expiresSeconds срок действия в секундах, от 1 до {@link #MAX_EXPIRES_SECONDS}
     * @return строка запроса без начального {@code ?}
     */
    public String presignedQuery(String method, String host, String canonicalPath, Instant now, long expiresSeconds) {
        if (expiresSeconds < 1 || expiresSeconds > MAX_EXPIRES_SECONDS) {
            throw new IllegalArgumentException("Срок ссылки должен быть от 1 секунды до 7 суток, а не " + expiresSeconds);
        }
        String stamp = STAMP.format(now);
        String day = DAY.format(now);
        String scope = scope(day);
        TreeMap<String, String> query = new TreeMap<>();
        query.put("X-Amz-Algorithm", ALGORITHM);
        query.put("X-Amz-Credential", accessKey + "/" + scope);
        query.put("X-Amz-Date", stamp);
        query.put("X-Amz-Expires", Long.toString(expiresSeconds));
        query.put("X-Amz-SignedHeaders", "host");
        String canonicalQuery = canonicalQuery(query);
        String canonicalRequest = method + "\n" + canonicalPath + "\n" + canonicalQuery + "\nhost:" + host + "\n\nhost\n" + UNSIGNED_PAYLOAD;
        return canonicalQuery + "&X-Amz-Signature=" + signature(day, stamp, scope, canonicalRequest);
    }

    /**
     * Значение заголовка {@code Authorization} для запроса без параметров строки запроса.
     *
     * @param headers     заголовки, которые входят в подпись (имена в любом регистре): {@code host}, {@code x-amz-date},
     *                    {@code x-amz-content-sha256} и при необходимости другие. Остальные заголовки запроса в подпись не входят
     * @param payloadHash SHA-256 тела в шестнадцатеричной записи (или {@link #UNSIGNED_PAYLOAD})
     */
    public String authorization(String method, String canonicalPath, Map<String, String> headers, String payloadHash, Instant now) {
        String stamp = STAMP.format(now);
        String day = DAY.format(now);
        String scope = scope(day);
        TreeMap<String, String> sorted = new TreeMap<>();
        headers.forEach((name, value) -> sorted.put(name.toLowerCase(Locale.ROOT), value.trim().replaceAll("\\s+", " ")));
        StringBuilder canonicalHeaders = new StringBuilder();
        sorted.forEach((name, value) -> canonicalHeaders.append(name).append(':').append(value).append('\n'));
        String signedHeaders = String.join(";", sorted.keySet());
        String canonicalRequest = method + "\n" + canonicalPath + "\n\n" + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;
        return ALGORITHM + " Credential=" + accessKey + "/" + scope + ", SignedHeaders=" + signedHeaders + ", Signature="
                + signature(day, stamp, scope, canonicalRequest);
    }

    /**
     * Путь для канонического запроса S3: каждый сегмент кодируется один раз, косые черты остаются. Допустимы буквы, цифры и
     * {@code - _ . ~}, остальное записывается как {@code %XX} (UTF-8, заглавные буквы).
     */
    public static String encodePath(String path) {
        StringBuilder out = new StringBuilder(path.length() + 16);
        for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (c == '/' || isUnreserved(c)) {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16))).append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return out.toString();
    }

    private static String encodeComponent(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (isUnreserved(c)) {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16))).append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return out.toString();
    }

    private static boolean isUnreserved(int c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '_' || c == '.' || c == '~';
    }

    private static String canonicalQuery(TreeMap<String, String> query) {
        StringBuilder out = new StringBuilder();
        query.forEach((name, value) -> {
            if (out.length() > 0) {
                out.append('&');
            }
            out.append(encodeComponent(name)).append('=').append(encodeComponent(value));
        });
        return out.toString();
    }

    private String scope(String day) {
        return day + "/" + region + "/" + SERVICE + "/aws4_request";
    }

    private String signature(String day, String stamp, String scope, String canonicalRequest) {
        String stringToSign = ALGORITHM + "\n" + stamp + "\n" + scope + "\n" + HEX.formatHex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        byte[] key = hmac(("AWS4" + secretKey.reveal()).getBytes(StandardCharsets.UTF_8), day);
        key = hmac(key, region);
        key = hmac(key, SERVICE);
        key = hmac(key, "aws4_request");
        return HEX.formatHex(hmac(key, stringToSign));
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 недоступен", e);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }
}
