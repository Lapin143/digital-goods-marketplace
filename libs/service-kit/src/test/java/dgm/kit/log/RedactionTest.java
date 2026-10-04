package dgm.kit.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
