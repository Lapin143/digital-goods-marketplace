package dgm.kit.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void parsesObjectsListsAndNumbers() {
        Map<String, Object> doc = Json.parseObject("{\"a\":1,\"b\":[1,2],\"c\":{\"d\":\"x\"},\"e\":true,\"f\":null}");
        assertEquals(1, doc.get("a"));
        assertEquals(List.of(1, 2), doc.get("b"));
        assertEquals(Map.of("d", "x"), doc.get("c"));
        assertEquals(true, doc.get("e"));
        assertNull(doc.get("f"));
    }

    @Test
    void rejectsNonObjectRoot() {
        assertThrows(JsonException.class, () -> Json.parseObject("[1]"));
        assertThrows(JsonException.class, () -> Json.parseObject("\"x\""));
    }

    @Test
    void rejectsBrokenAndEmptyText() {
        assertThrows(JsonException.class, () -> Json.parse("{\"a\":"));
        assertThrows(JsonException.class, () -> Json.parse(""));
        assertThrows(JsonException.class, () -> Json.parse(null));
    }

    @Test
    void writesKeysInInsertionOrderWithCyrillicAsIs() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("z", 1);
        m.put("a", "Привет");
        assertEquals("{\"z\":1,\"a\":\"Привет\"}", Json.write(m));
    }
}
