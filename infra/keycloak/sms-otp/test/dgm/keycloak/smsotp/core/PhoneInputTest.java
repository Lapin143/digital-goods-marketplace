package dgm.keycloak.smsotp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class PhoneInputTest {

    @Test
    void usualRussianWritingsGiveE164() {
        for (String raw : new String[] {"+79001234567", "+7 900 123-45-67", "8 (900) 123-45-67", "79001234567", "89001234567", "9001234567",
                "  +7(900)123 45 67  "}) {
            assertEquals(Optional.of("+79001234567"), PhoneInput.normalize(raw), raw);
        }
    }

    @Test
    void foreignShortAndGarbageAreRejected() {
        for (String raw : new String[] {null, "", "   ", "+380501234567", "+1 202 555 0100", "+7900123456", "+790012345678", "900123456",
                "abc", "+7900123456a", "8-800-555-35-35-35", "+7+9001234567", "++79001234567"}) {
            assertTrue(PhoneInput.normalize(raw).isEmpty(), String.valueOf(raw));
        }
    }

    @Test
    void codeIsSixDigitsWithSpacesIgnored() {
        assertEquals(Optional.of("123456"), PhoneInput.normalizeCode("123456"));
        assertEquals(Optional.of("123456"), PhoneInput.normalizeCode(" 123 456 "));
        for (String raw : new String[] {null, "", "12345", "1234567", "12345a", "١٢٣٤٥٦", "12-34-56"}) {
            assertTrue(PhoneInput.normalizeCode(raw).isEmpty(), String.valueOf(raw));
        }
    }
}
