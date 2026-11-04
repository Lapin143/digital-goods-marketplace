package dgm.catalog.sellers.apikey.domain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Значение API-ключа и производные (ADR-027, п. 1): вид {@code dgm_} + 40 знаков base64url, SHA-256 в 32 байта, отпечаток из первых
 * восьми знаков, разбор хеша из запроса шлюза.
 */
@Tag("TC-950")
@Tag("TC-953")
class ApiKeyValuesTest {

    /** Тридцать байт 0x00, 0x01, … : значение известно заранее. */
    private static ApiKeyValues.RandomBytes sequence() {
        return length -> {
            byte[] bytes = new byte[length];
            for (int i = 0; i < length; i++) {
                bytes[i] = (byte) i;
            }
            return bytes;
        };
    }

    @Test
    void aGeneratedValueIsThePrefixAndFortyBase64UrlCharactersOfThirtyRandomBytes() {
        String value = ApiKeyValues.generate(sequence());
        assertEquals("dgm_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwd", value);
        assertEquals(44, value.length());
        assertTrue(ApiKeyValues.isWellFormed(value));
    }

    @Test
    void theValueUsesTheUrlSafeAlphabetWithoutPaddingForEveryByte() {
        // 0xFB 0xFF даёт в обычном base64 «+» и «/»: в значении их быть не должно
        String value = ApiKeyValues.generate(length -> {
            byte[] bytes = new byte[length];
            java.util.Arrays.fill(bytes, (byte) 0xFB);
            bytes[1] = (byte) 0xFF;
            return bytes;
        });
        assertTrue(ApiKeyValues.isWellFormed(value), value);
        assertFalse(value.contains("+") || value.contains("/") || value.contains("="), value);
    }

    @Test
    void theSecureRandomSourceGivesDifferentValuesEveryTime() {
        SecureRandom secure = new SecureRandom();
        ApiKeyValues.RandomBytes random = length -> {
            byte[] bytes = new byte[length];
            secure.nextBytes(bytes);
            return bytes;
        };
        assertNotEquals(ApiKeyValues.generate(random), ApiKeyValues.generate(random));
    }

    @Test
    void aSourceThatReturnsTheWrongNumberOfBytesIsAConfigurationErrorNotAWeakKey() {
        assertThrows(IllegalStateException.class, () -> ApiKeyValues.generate(length -> new byte[length - 1]));
        assertThrows(IllegalStateException.class, () -> ApiKeyValues.generate(length -> new byte[length + 1]));
        assertThrows(IllegalStateException.class, () -> ApiKeyValues.generate(length -> null));
    }

    @Test
    void wellFormedMeansExactlyThePrefixAndFortyCharactersOfTheAlphabet() {
        String value = ApiKeyValues.generate(sequence());
        assertFalse(ApiKeyValues.isWellFormed(null));
        assertFalse(ApiKeyValues.isWellFormed(""));
        assertFalse(ApiKeyValues.isWellFormed(value.substring(1)));
        assertFalse(ApiKeyValues.isWellFormed(value + "A"));
        assertFalse(ApiKeyValues.isWellFormed("DGM_" + value.substring(4)));
        assertFalse(ApiKeyValues.isWellFormed(value.substring(0, 43) + "+"));
        assertFalse(ApiKeyValues.isWellFormed(value + "\n"));
    }

    @Test
    void theHashIsSha256OfTheValueInThirtyTwoBytes() throws Exception {
        String value = ApiKeyValues.generate(sequence());
        byte[] expected = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        assertEquals(ApiKeyValues.HASH_BYTES, ApiKeyValues.hash(value).length);
        assertArrayEquals(expected, ApiKeyValues.hash(value));
    }

    @Test
    void theFingerprintIsTheFirstEightCharactersAfterThePrefixAndRevealsNothingMoreOfTheValue() {
        String value = ApiKeyValues.generate(sequence());
        assertEquals("AAECAwQF", ApiKeyValues.fingerprint(value));
        assertEquals(ApiKeyValues.FINGERPRINT_LENGTH, ApiKeyValues.fingerprint(value).length());
        assertThrows(IllegalArgumentException.class, () -> ApiKeyValues.fingerprint("dgm_короткий"));
    }

    @Test
    void aHashFromTheGatewayIsSixtyFourLowercaseHexCharactersAndDecodesToTheSameBytes() {
        String value = ApiKeyValues.generate(sequence());
        String hex = HexFormat.of().formatHex(ApiKeyValues.hash(value));
        assertTrue(ApiKeyValues.isHashHex(hex));
        assertArrayEquals(ApiKeyValues.hash(value), ApiKeyValues.decodeHash(hex));
        assertFalse(ApiKeyValues.isHashHex(null));
        assertFalse(ApiKeyValues.isHashHex(hex.toUpperCase(java.util.Locale.ROOT)));
        assertFalse(ApiKeyValues.isHashHex(hex.substring(1)));
        assertFalse(ApiKeyValues.isHashHex(hex + "0"));
        assertFalse(ApiKeyValues.isHashHex(hex.substring(0, 63) + "g"));
        assertThrows(IllegalArgumentException.class, () -> ApiKeyValues.decodeHash(hex.substring(1)));
    }
}
