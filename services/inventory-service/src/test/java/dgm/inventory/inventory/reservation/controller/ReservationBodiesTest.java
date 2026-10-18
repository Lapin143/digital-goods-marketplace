package dgm.inventory.inventory.reservation.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dgm.inventory.inventory.reservation.controller.ReservationBodies.ReserveRequest;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Разбор тела {@code ReserveRequest}: поля, границы количества, лишние поля (additionalProperties: false). */
class ReservationBodiesTest {

    private static final String ORDER = "0199e0a0-0000-7000-8000-000000000301";
    private static final String PRODUCT = "0199e0a0-0000-7000-8000-000000000201";

    private static Map<String, Object> body(Object quantity) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", ORDER);
        body.put("productId", PRODUCT);
        body.put("quantity", quantity);
        return body;
    }

    private static List<String> pointers(Map<String, Object> body) {
        ProblemException e = assertThrows(ProblemException.class, () -> ReservationBodies.parse(body));
        assertEquals(ProblemType.VALIDATION_FAILED, e.type());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) e.extensions().get("errors");
        return errors.stream().map(error -> (String) error.get("pointer")).toList();
    }

    @Test
    void aValidRequestIsParsed() {
        assertEquals(new ReserveRequest(UUID.fromString(ORDER), UUID.fromString(PRODUCT), 2), ReservationBodies.parse(body(2)));
    }

    @Test
    void quantityMustBeAnIntegerFromOneToTen() {
        for (Object bad : new Object[] {0, 11, -1, 1.5, "2"}) {
            assertEquals(List.of("/quantity"), pointers(body(bad)), String.valueOf(bad));
        }
        assertEquals(10, ReservationBodies.parse(body(10)).quantity());
        assertEquals(1, ReservationBodies.parse(body(1)).quantity());
    }

    @Test
    void missingAndExtraFieldsAreReported() {
        Map<String, Object> body = body(1);
        body.remove("orderId");
        body.put("price", 5);
        assertEquals(List.of("/price", "/orderId"), pointers(body));
    }

    @Test
    void identifiersMustBeLowercaseUuids() {
        Map<String, Object> body = body(1);
        body.put("productId", PRODUCT.toUpperCase());
        assertEquals(List.of("/productId"), pointers(body));
    }
}
