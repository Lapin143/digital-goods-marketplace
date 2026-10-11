package dgm.kit.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dgm.kit.secret.SecretValue;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Подпись вебхука по ADR-015: HMAC-SHA-256 от «метка.тело», hex, окно 5 минут, несколько секретов. */
class WebhookSignatureTest {

    private static final SecretValue SECRET = SecretValue.of("test-secret-key");
    private static final String TIMESTAMP = "2026-10-11T10:00:00.000Z";
    private static final byte[] BODY = "{\"messageId\":\"sms_1\",\"status\":\"delivered\"}".getBytes(StandardCharsets.UTF_8);
    /** Значение посчитано независимо (Python hmac) для ключа test-secret-key и строки «метка.тело». */
    private static final String KNOWN = "8ac3e6a4436ff6b0364f1c6f4bc15544f0ff204d533f82cccc036b032dbe694b";
    private static final Instant NOW = Instant.parse("2026-10-11T10:00:30Z");

    private static WebhookSignature.Result verify(List<SecretValue> secrets, String timestamp, String signature, byte[] body, Instant now) {
        return WebhookSignature.verify(secrets, timestamp, signature, body, now);
    }

    @Test
    void signatureMatchesIndependentlyComputedValue() {
        assertEquals(KNOWN, WebhookSignature.sign("test-secret-key".getBytes(StandardCharsets.UTF_8), TIMESTAMP, BODY));
    }

    @Test
    void validSignatureInsideWindowPasses() {
        assertEquals(WebhookSignature.Result.VALID, verify(List.of(SECRET), TIMESTAMP, KNOWN, BODY, NOW));
    }

    @Test
    void hexCaseAndSurroundingSpacesAreTolerated() {
        assertEquals(WebhookSignature.Result.VALID, verify(List.of(SECRET), TIMESTAMP, "  " + KNOWN.toUpperCase(java.util.Locale.ROOT) + " ", BODY, NOW));
    }

    @Test
    void wrongSecretTamperedBodyOrTimestampFail() {
        assertEquals(WebhookSignature.Result.SIGNATURE_INVALID, verify(List.of(SecretValue.of("other")), TIMESTAMP, KNOWN, BODY, NOW));
        byte[] tampered = "{\"messageId\":\"sms_1\",\"status\":\"failed\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals(WebhookSignature.Result.SIGNATURE_INVALID, verify(List.of(SECRET), TIMESTAMP, KNOWN, tampered, NOW));
        assertEquals(WebhookSignature.Result.SIGNATURE_INVALID, verify(List.of(SECRET), "2026-10-11T10:00:01.000Z", KNOWN, BODY, NOW));
    }

    @Test
    void missingHeadersFail() {
        assertEquals(WebhookSignature.Result.SIGNATURE_INVALID, verify(List.of(SECRET), null, KNOWN, BODY, NOW));
        assertEquals(WebhookSignature.Result.SIGNATURE_INVALID, verify(List.of(SECRET), TIMESTAMP, null, BODY, NOW));
        assertEquals(WebhookSignature.Result.SIGNATURE_INVALID, verify(List.of(SECRET), " ", KNOWN, BODY, NOW));
        assertEquals(WebhookSignature.Result.SIGNATURE_INVALID, verify(List.of(SECRET), TIMESTAMP, "", BODY, NOW));
    }

    @Test
    void noSecretsMeansNothingIsAccepted() {
        assertEquals(WebhookSignature.Result.SIGNATURE_INVALID, verify(List.of(), TIMESTAMP, KNOWN, BODY, NOW));
    }

    @Test
    void windowIsFiveMinutesInBothDirections() {
        Instant sent = Instant.parse(TIMESTAMP);
        assertEquals(WebhookSignature.Result.VALID, verify(List.of(SECRET), TIMESTAMP, KNOWN, BODY, sent.plus(Duration.ofMinutes(5))));
        assertEquals(WebhookSignature.Result.EXPIRED, verify(List.of(SECRET), TIMESTAMP, KNOWN, BODY, sent.plus(Duration.ofMinutes(5)).plusMillis(1)));
        assertEquals(WebhookSignature.Result.VALID, verify(List.of(SECRET), TIMESTAMP, KNOWN, BODY, sent.minus(Duration.ofMinutes(5))));
        assertEquals(WebhookSignature.Result.EXPIRED, verify(List.of(SECRET), TIMESTAMP, KNOWN, BODY, sent.minus(Duration.ofMinutes(5)).minusMillis(1)));
    }

    @Test
    void signatureIsCheckedBeforeFreshness() {
        // Устаревшее, но неподписанное уведомление получает отказ по подписи, а не подсказку про время
        Instant late = Instant.parse(TIMESTAMP).plus(Duration.ofHours(1));
        assertEquals(WebhookSignature.Result.SIGNATURE_INVALID, verify(List.of(SECRET), TIMESTAMP, "00".repeat(32), BODY, late));
        assertEquals(WebhookSignature.Result.EXPIRED, verify(List.of(SECRET), TIMESTAMP, KNOWN, BODY, late));
    }

    @Test
    void anyOfTheCurrentSecretsIsEnough() {
        SecretValue previous = SecretValue.of("previous-key");
        String signedWithPrevious = WebhookSignature.sign(previous.revealBytes(), TIMESTAMP, BODY);
        assertEquals(WebhookSignature.Result.VALID, verify(List.of(SECRET, previous), TIMESTAMP, signedWithPrevious, BODY, NOW));
        assertEquals(WebhookSignature.Result.VALID, verify(List.of(previous, SECRET), TIMESTAMP, KNOWN, BODY, NOW));
        assertEquals(WebhookSignature.Result.SIGNATURE_INVALID, verify(List.of(SECRET), TIMESTAMP, signedWithPrevious, BODY, NOW));
    }

    @Test
    void signedGarbageTimestampIsNotTrusted() {
        String garbage = "yesterday";
        String signature = WebhookSignature.sign(SECRET.revealBytes(), garbage, BODY);
        assertEquals(WebhookSignature.Result.SIGNATURE_INVALID, verify(List.of(SECRET), garbage, signature, BODY, NOW));
    }
}
