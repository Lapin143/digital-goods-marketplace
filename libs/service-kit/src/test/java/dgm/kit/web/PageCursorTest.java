package dgm.kit.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PageCursorTest {

    private static String b64(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void roundTripKeepsValuesAndOrder() {
        Map<String, String> in = new LinkedHashMap<>();
        in.put("t", "2026-10-03T12:00:00.000Z");
        in.put("i", "0199e0a0-0000-4000-8000-000000000201");
        Map<String, String> out = PageCursor.decode(PageCursor.encode(in));
        assertEquals(in, out);
        assertEquals(List.of("t", "i"), List.copyOf(out.keySet()));
    }

    @Test
    void encodedCursorIsUrlSafe() {
        String cursor = PageCursor.encode(Map.of("t", "значение с пробелами и / + ?"));
        assertTrue(cursor.matches("^[A-Za-z0-9_-]+$"), cursor);
    }

    @Test
    void notBase64IsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PageCursor.decode("***"));
    }

    @Test
    void notJsonIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PageCursor.decode(b64("не json")));
    }

    @Test
    void jsonArrayIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PageCursor.decode(b64("[1,2]")));
    }

    @Test
    void nonStringValueIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PageCursor.decode(b64("{\"t\":5}")));
    }
}
