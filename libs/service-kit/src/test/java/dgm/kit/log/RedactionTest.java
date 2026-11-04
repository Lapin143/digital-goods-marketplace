package dgm.kit.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class RedactionTest {

    @Test
    void masksEmailAndPhone() {
        String masked = Redaction.mask("Не доставлено на ivan.petrov+shop@example.com, телефон +7 (916) 123-45-67 и 89161234567");
        assertFalse(masked.contains("ivan"));
        assertFalse(masked.contains("example.com"));
        assertFalse(masked.contains("123-45-67"));
        assertFalse(masked.contains("89161234567"));
        assertTrue(masked.contains("***@***"));
    }

    @Test
    @Tag("TC-953")
    void masksAnApiKeyValueButKeepsTheFingerprint() {
        String key = "dgm_kJ3x9Zt0Qw5LmN8RpV2cYbH7sGdFaE4uTi6oXe1A";
        String masked = Redaction.mask("Запрос с ключом " + key + " отклонён, отпечаток kJ3x9Zt0, ещё " + key);
        assertFalse(masked.contains("Qw5LmN8"));
        assertFalse(masked.contains("Xe1A"));
        assertTrue(masked.contains("kJ3x9Zt0"));
        assertEquals("Запрос с ключом dgm_*** отклонён, отпечаток kJ3x9Zt0, ещё dgm_***", masked);
    }

    @Test
    @Tag("TC-953")
    void doesNotTouchShorterStringsWithThePrefix() {
        assertEquals("метрика dgm_client_requests_total растёт", Redaction.mask("метрика dgm_client_requests_total растёт"));
    }

    @Test
    void keepsOrdinaryText() {
        assertEquals("order 42 failed in step 3", Redaction.mask("order 42 failed in step 3"));
    }

    @Test
    void truncates() {
        assertEquals(5, Redaction.mask("abcdefghij", 5).length());
    }

    @Test
    void nullBecomesEmpty() {
        assertEquals("", Redaction.mask(null));
    }
}
