package dgm.order.orders.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.order.orders.controller.CreateOrderBodies.CreateRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Разбор тела {@code CreateOrderRequest}: два поля, количество от 1 до 10 (INV-10), лишние поля (additionalProperties: false), типы тела. */
@Tag("TC-515")
class CreateOrderBodiesTest {

    private static final String PRODUCT = "0199e0a0-0000-7000-8000-000000000201";

    private static Map<String, Object> body(Object quantity) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productId", PRODUCT);
        body.put("quantity", quantity);
        return body;
    }

    private static List<String> pointers(Map<String, Object> body) {
        ProblemException e = assertThrows(ProblemException.class, () -> CreateOrderBodies.parse(body));
        assertEquals(ProblemType.VALIDATION_FAILED, e.type());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) e.extensions().get("errors");
        return errors.stream().map(error -> (String) error.get("pointer")).toList();
    }

    private static List<String> codes(Map<String, Object> body) {
        ProblemException e = assertThrows(ProblemException.class, () -> CreateOrderBodies.parse(body));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) e.extensions().get("errors");
        return errors.stream().map(error -> (String) error.get("code")).toList();
    }

    private static MockHttpServletRequest request(String contentType, String content) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/orders");
        if (contentType != null) {
            request.setContentType(contentType);
        }
        request.setContent(content.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    @Test
    void aValidRequestIsParsed() {
        assertEquals(new CreateRequest(UUID.fromString(PRODUCT), 2), CreateOrderBodies.parse(body(2)));
    }

    @Test
    void theQuantityIsAnIntegerFromOneToTen() {
        for (Object bad : new Object[] {0, 11, -1, 1.5, "2", true}) {
            assertEquals(List.of("/quantity"), pointers(body(bad)), String.valueOf(bad));
        }
        assertEquals(10, CreateOrderBodies.parse(body(10)).quantity());
        assertEquals(1, CreateOrderBodies.parse(body(1)).quantity());
        assertEquals(List.of("out_of_range"), codes(body(11)));
        assertEquals(List.of("invalid_format"), codes(body("2")));
    }

    @Test
    void missingFieldsAreReportedWithTheRequiredCode() {
        assertEquals(List.of("/productId", "/quantity"), pointers(new LinkedHashMap<>()));
        assertEquals(List.of("required", "required"), codes(new LinkedHashMap<>()));
        Map<String, Object> noQuantity = new LinkedHashMap<>();
        noQuantity.put("productId", PRODUCT);
        assertEquals(List.of("/quantity"), pointers(noQuantity));
        Map<String, Object> nullQuantity = body(null);
        assertEquals(List.of("/quantity"), pointers(nullQuantity));
    }

    @Test
    void anExtraFieldIsRefusedSoThePriceCannotBeSentFromOutside() {
        Map<String, Object> body = body(1);
        body.put("price", 1);
        body.put("sellerId", PRODUCT);
        assertEquals(List.of("/price", "/sellerId"), pointers(body));
        assertEquals(List.of("unknown_value", "unknown_value"), codes(body));
    }

    @Test
    void theProductIdMustBeALowercaseUuidString() {
        Map<String, Object> upper = body(1);
        upper.put("productId", PRODUCT.toUpperCase());
        assertEquals(List.of("/productId"), pointers(upper));
        Map<String, Object> number = body(1);
        number.put("productId", 5);
        assertEquals(List.of("/productId"), pointers(number));
        Map<String, Object> text = body(1);
        text.put("productId", "not-a-uuid");
        assertEquals(List.of("/productId"), pointers(text));
    }

    @Test
    void everyErrorIsReportedAtOnce() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("extra", 1);
        body.put("productId", "x");
        body.put("quantity", 99);
        assertEquals(List.of("/extra", "/productId", "/quantity"), pointers(body));
    }

    @Test
    void aJsonRequestIsReadFromTheBody() throws IOException {
        CreateRequest request = CreateOrderBodies.read(request("application/json", "{\"productId\":\"" + PRODUCT + "\",\"quantity\":3}"));
        assertEquals(new CreateRequest(UUID.fromString(PRODUCT), 3), request);
        assertEquals(new CreateRequest(UUID.fromString(PRODUCT), 3),
                CreateOrderBodies.read(request("application/json; charset=UTF-8", "{\"productId\":\"" + PRODUCT + "\",\"quantity\":3}")));
    }

    @Test
    void anotherContentTypeIs415() {
        for (String type : new String[] {"text/plain", "application/x-www-form-urlencoded", null}) {
            ProblemException e = assertThrows(ProblemException.class, () -> CreateOrderBodies.read(request(type, "{}")));
            assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, e.type(), String.valueOf(type));
        }
    }

    @Test
    void aBodyThatIsNotAJsonObjectIs400() {
        for (String content : new String[] {"", "not json", "[1, 2]", "\"text\"", "{\"productId\":"}) {
            ProblemException e = assertThrows(ProblemException.class, () -> CreateOrderBodies.read(request("application/json", content)));
            assertEquals(ProblemType.BAD_REQUEST, e.type(), content);
        }
    }

    @Test
    void aBodyLargerThanFourKibIs413() {
        String large = "{\"productId\":\"" + PRODUCT + "\",\"quantity\":1,\"pad\":\"" + "x".repeat(5000) + "\"}";
        ProblemException e = assertThrows(ProblemException.class, () -> CreateOrderBodies.read(request("application/json", large)));
        assertEquals(ProblemType.PAYLOAD_TOO_LARGE, e.type());
    }
}
