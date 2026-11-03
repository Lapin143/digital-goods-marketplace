package dgm.kit.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.secret.SecretValue;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Производный секрет продавца (ADR-028): значения ниже посчитаны независимо (Python hmac), не этим кодом. */
class SellerSecretsTest {

    private static final SecretValue MASTER = SecretValue.of("master-for-test-vectors-only");
    private static final UUID SELLER_A = UUID.fromString("5b7f0e1a-3c4d-4e5f-8a6b-7c8d9e0f1a2b");
    private static final UUID SELLER_B = UUID.fromString("9d8c7b6a-5f4e-4d3c-b2a1-0f9e8d7c6b5a");
    private static final String A_V1 = "472be46cea9be46b54ed35049fe32e8bc331fe4560ed380ad16b13739f7324e6";
    private static final String A_V2 = "4893651dbeea4d5742f1d9e5e1451cd8e749ede1fc7b7e66769f91b63c547498";
    private static final String B_V1 = "4ae337e88a5d8f9e6ae114008d824d045e53968f69c5a09e195c3c6c8a9d1321";
    private static final String TIMESTAMP = "2026-11-03T10:00:00.000Z";
    private static final byte[] BODY = "{\"requestId\":\"r1\"}".getBytes(StandardCharsets.UTF_8);
    /** HMAC-SHA-256(A_V1 как строка, метка + "." + тело), посчитано независимо. */
    private static final String SIGNATURE = "c15ec9505c737d40db3e47ba743c543c2a6408f3578766f46703ebf5835fb357";

    private final SellerSecrets secrets = new SellerSecrets(MASTER);

    @Test
    void secretsMatchIndependentlyComputedValues() {
        assertEquals(A_V1, secrets.secret(SELLER_A, 1).reveal());
        assertEquals(A_V2, secrets.secret(SELLER_A, 2).reveal());
        assertEquals(B_V1, secrets.secret(SELLER_B, 1).reveal());
    }

    @Test
    void secretIsSixtyFourLowercaseHexCharacters() {
        String value = secrets.secret(SELLER_A, 1).reveal();
        assertEquals(64, value.length());
        assertTrue(value.matches("[0-9a-f]{64}"), value);
    }

    @Test
    void sameInputsGiveSameSecretAndAnyChangeGivesAnother() {
        assertEquals(secrets.secret(SELLER_A, 1), secrets.secret(SELLER_A, 1));
        assertNotEquals(secrets.secret(SELLER_A, 1), secrets.secret(SELLER_A, 2));
        assertNotEquals(secrets.secret(SELLER_A, 1), secrets.secret(SELLER_B, 1));
        assertNotEquals(secrets.secret(SELLER_A, 1), new SellerSecrets(SecretValue.of("another-master-secret-value")).secret(SELLER_A, 1));
    }

    @Test
    void signatureUsesTheDerivedSecretAsKeyString() {
        assertEquals(SIGNATURE, secrets.sign(SELLER_A, 1, TIMESTAMP, BODY));
        // Тот, кто получил секрет в кабинете, проверяет подпись общим правилом каркаса
        assertEquals(WebhookSignature.Result.VALID,
                WebhookSignature.verify(List.of(secrets.secret(SELLER_A, 1)), TIMESTAMP, SIGNATURE, BODY, Instant.parse("2026-11-03T10:00:10Z")));
        assertEquals(WebhookSignature.Result.SIGNATURE_INVALID,
                WebhookSignature.verify(List.of(secrets.secret(SELLER_A, 2)), TIMESTAMP, SIGNATURE, BODY, Instant.parse("2026-11-03T10:00:10Z")));
    }

    @Test
    void versionStartsAtOne() {
        assertThrows(IllegalArgumentException.class, () -> secrets.secret(SELLER_A, 0));
        assertThrows(IllegalArgumentException.class, () -> secrets.secret(SELLER_A, -1));
    }

    @Test
    void shortMasterIsRejectedAndNeverPrinted() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new SellerSecrets(SecretValue.of("short-secret")));
        assertTrue(!e.getMessage().contains("short-secret"), e.getMessage());
        assertEquals("SecretValue[****]", secrets.secret(SELLER_A, 1).toString());
    }
}
