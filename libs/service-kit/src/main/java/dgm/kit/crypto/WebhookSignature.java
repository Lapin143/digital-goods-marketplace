package dgm.kit.crypto;

import dgm.kit.secret.SecretValue;
import dgm.kit.time.Timestamps;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Подпись вебхука (ADR-015, схема {@code webhookSignature} в OpenAPI): HMAC-SHA-256 от строки {@code <X-Webhook-Timestamp>.<тело>},
 * hex, ключ это секрет источника. Подпись проверяется первой, затем свежесть метки времени: окно 5 минут в обе стороны (conventions.md,
 * пункт 5.6). Сравнение идёт за постоянное время. Допустимы несколько действующих секретов (смена ключа без простоя).
 */
public final class WebhookSignature {

    public static final String SIGNATURE_HEADER = "X-Webhook-Signature";
    public static final String TIMESTAMP_HEADER = "X-Webhook-Timestamp";
    /** Допуск расхождения метки времени и часов сервиса. */
    public static final Duration TOLERANCE = Duration.ofMinutes(5);

    /** Итог проверки. */
    public enum Result {
        VALID,
        /** Подписи нет, она не совпала ни с одним секретом или метка времени не разобрана: тип проблемы {@code signature-invalid}. */
        SIGNATURE_INVALID,
        /** Подпись верна, но метка времени вне окна: тип проблемы {@code webhook-expired}. */
        EXPIRED
    }

    private static final String ALGORITHM = "HmacSHA256";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private WebhookSignature() {
    }

    /** Подпись для заданной метки времени и тела (hex, нижний регистр). Нужна источнику вебхука и тестам. */
    public static String sign(byte[] secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            return hex(mac.doFinal(body));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA-256 недоступен или секрет не подходит как ключ", e);
        }
    }

    /**
     * Проверка вебхука.
     *
     * @param secrets         действующие секреты источника, подходит любой
     * @param timestampHeader значение {@code X-Webhook-Timestamp} как оно пришло: именно эта строка входит в подпись
     * @param signatureHeader значение {@code X-Webhook-Signature}
     * @param body            тело запроса как оно пришло
     * @param now             текущее время
     */
    public static Result verify(List<SecretValue> secrets, String timestampHeader, String signatureHeader, byte[] body, Instant now) {
        if (timestampHeader == null || timestampHeader.isBlank() || signatureHeader == null || signatureHeader.isBlank()) {
            return Result.SIGNATURE_INVALID;
        }
        byte[] provided = signatureHeader.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        boolean matched = false;
        for (SecretValue secret : secrets) {
            byte[] expected = sign(secret.revealBytes(), timestampHeader, body).getBytes(StandardCharsets.US_ASCII);
            // Перебираются все секреты без выхода по первому совпадению: время проверки не зависит от того, какой подошёл
            matched |= MessageDigest.isEqual(expected, provided);
        }
        if (!matched) {
            return Result.SIGNATURE_INVALID;
        }
        Instant sent = Timestamps.parse(timestampHeader.trim()).orElse(null);
        if (sent == null) {
            return Result.SIGNATURE_INVALID;
        }
        Duration age = Duration.between(sent, now).abs();
        return age.compareTo(TOLERANCE) > 0 ? Result.EXPIRED : Result.VALID;
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = HEX[(bytes[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[bytes[i] & 0xF];
        }
        return new String(out);
    }
}
