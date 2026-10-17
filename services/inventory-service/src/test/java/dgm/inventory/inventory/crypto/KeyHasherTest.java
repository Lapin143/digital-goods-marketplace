package dgm.inventory.inventory.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dgm.kit.secret.SecretValue;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** HMAC значения ключа (ADR-009, INV-03): состав подписи, нормализация, раздельность товаров. */
@Tag("TC-410")
class KeyHasherTest {

    private static final UUID PRODUCT = UUID.fromString("0199e0a0-0000-7000-8000-000000000201");
    private static final byte[] SECRET = "k".repeat(32).getBytes(StandardCharsets.US_ASCII);

    private final KeyHasher hasher = new KeyHasher(SECRET);

    private static SecretValue value(String text) {
        return KeyHasher.normalize(text);
    }

    @Test
    void theSignatureCoversTheProductIdZeroByteAndTheValue() throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
        mac.update(PRODUCT.toString().getBytes(StandardCharsets.US_ASCII));
        mac.update((byte) 0);
        mac.update("ABC-123".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(mac.doFinal(), hasher.hmac(PRODUCT, value("ABC-123")));
    }

    @Test
    void spacesAndNewlinesAtTheEdgesAreIgnoredButTheCaseMatters() {
        byte[] plain = hasher.hmac(PRODUCT, value("ABC-123"));
        assertArrayEquals(plain, hasher.hmac(PRODUCT, value("  ABC-123\r\n")));
        assertArrayEquals(plain, hasher.hmac(PRODUCT, value("\tABC-123 ")));
        assertFalse(Arrays.equals(plain, hasher.hmac(PRODUCT, value("abc-123"))));
        assertFalse(Arrays.equals(plain, hasher.hmac(PRODUCT, value("ABC- 123"))), "пробелы внутри значения значимы");
    }

    @Test
    void theSameValueInAnotherProductGivesAnotherHmac() {
        assertFalse(Arrays.equals(hasher.hmac(PRODUCT, value("ABC-123")), hasher.hmac(UUID.randomUUID(), value("ABC-123"))));
    }

    @Test
    void anotherSecretGivesAnotherHmac() {
        KeyHasher other = new KeyHasher("fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.US_ASCII));
        assertFalse(Arrays.equals(hasher.hmac(PRODUCT, value("ABC-123")), other.hmac(PRODUCT, value("ABC-123"))));
    }

    @Test
    void theResultIs32BytesAndHexIs64Characters() {
        byte[] hmac = hasher.hmac(PRODUCT, value("ABC-123"));
        assertEquals(32, hmac.length);
        assertEquals(64, KeyHasher.hex(hmac).length());
    }

    @Test
    void theSecretIsBase64OfRandomBytesOrAnyTextOfAtLeast16Bytes() {
        byte[] raw = new byte[32];
        Arrays.fill(raw, (byte) 7);
        KeyHasher fromBase64 = KeyHasher.fromSecret(SecretValue.of(Base64.getEncoder().encodeToString(raw)));
        assertArrayEquals(new KeyHasher(raw).hmac(PRODUCT, value("A")), fromBase64.hmac(PRODUCT, value("A")));
        KeyHasher fromText = KeyHasher.fromSecret(SecretValue.of("секрет не в base64, но длинный"));
        assertEquals(32, fromText.hmac(PRODUCT, value("A")).length);
        assertThrows(IllegalArgumentException.class, () -> new KeyHasher(new byte[15]));
    }
}
