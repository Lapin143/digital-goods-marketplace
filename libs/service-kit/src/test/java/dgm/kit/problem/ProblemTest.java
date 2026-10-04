package dgm.kit.problem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import dgm.kit.json.Json;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProblemTest {

    @Test
    void bodyHasRfc9457FieldsAndCode() {
        Problem p = new Problem(ProblemType.NOT_FOUND, "Заказ не найден.", "/api/v1/orders/1", "6b3c0a1e-1111-4111-8111-111111111111", Map.of());
        Map<String, Object> body = Json.parseObject(p.toJson());
        assertEquals("https://api.marketplace.example/problems/not-found", body.get("type"));
        assertEquals("Не найдено", body.get("title"));
        assertEquals(404, body.get("status"));
        assertEquals("Заказ не найден.", body.get("detail"));
        assertEquals("/api/v1/orders/1", body.get("instance"));
        assertEquals("not-found", body.get("code"));
        assertEquals("6b3c0a1e-1111-4111-8111-111111111111", body.get("correlationId"));
    }

    @Test
    void extensionsAreAddedButCannotOverrideStandardFields() {
        Problem p = new Problem(ProblemType.VALIDATION_FAILED, "d", "/x", "c",
                Map.of("errors", List.of(Map.of("pointer", "/email", "code", "required")), "status", 200));
        Map<String, Object> body = Json.parseObject(p.toJson());
        assertEquals(422, body.get("status"));
        assertEquals(List.of(Map.of("pointer", "/email", "code", "required")), body.get("errors"));
    }

    @Test
    void instanceIsOptional() {
        Problem p = new Problem(ProblemType.INTERNAL_ERROR, "d", null, "c", Map.of());
        assertFalse(Json.parseObject(p.toJson()).containsKey("instance"));
        assertNull(Json.parseObject(p.toJson()).get("instance"));
    }
}
