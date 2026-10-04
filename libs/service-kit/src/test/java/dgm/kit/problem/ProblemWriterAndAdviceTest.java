package dgm.kit.problem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.json.Json;
import dgm.kit.trace.TraceContext;
import dgm.kit.trace.TraceFilter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotSupportedException;

class ProblemWriterAndAdviceTest {

    private static final String CORRELATION = "6b3c0a1e-1111-4111-8111-111111111111";

    private final ProblemAdvice advice = new ProblemAdvice();

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders");
        request.setAttribute(TraceFilter.ATTRIBUTE, new TraceContext("0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331", "01", CORRELATION));
        return request;
    }

    @Test
    void writerProducesProblemJsonWithCorrelationId() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ProblemWriter.write(request(), response, ProblemType.FORBIDDEN, "Нет прав.");
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/problem+json"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
        Map<String, Object> body = Json.parseObject(response.getContentAsString());
        assertEquals("forbidden", body.get("code"));
        assertEquals(CORRELATION, body.get("correlationId"));
        assertEquals("/api/v1/orders", body.get("instance"));
        assertNull(response.getHeader("WWW-Authenticate"));
    }

    @Test
    void unauthenticatedAnswerCarriesBearerChallenge() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ProblemWriter.write(request(), response, ProblemType.UNAUTHENTICATED, "Нужен токен.");
        assertEquals(401, response.getStatus());
        assertEquals("Bearer", response.getHeader("WWW-Authenticate"));
    }

    @Test
    void correlationIdIsCreatedWhenRequestBypassedTheFilter() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        assertNotNull(ProblemWriter.correlationId(request));
        assertEquals(36, ProblemWriter.correlationId(request).length());
    }

    @Test
    void adviceMapsProblemException() {
        ProblemException e = new ProblemException(ProblemType.INSUFFICIENT_STOCK, "Осталось 2.", Map.of("available", 2), Map.of("Retry-After", "5"));
        ResponseEntity<String> r = advice.handle(e, request());
        assertEquals(409, r.getStatusCode().value());
        assertEquals("5", r.getHeaders().getFirst("Retry-After"));
        Map<String, Object> body = Json.parseObject(r.getBody());
        assertEquals("insufficient-stock", body.get("code"));
        assertEquals(2, body.get("available"));
        assertEquals("application/problem+json", r.getHeaders().getContentType().getType() + "/" + r.getHeaders().getContentType().getSubtype());
    }

    @Test
    void unexpectedErrorHidesDetails() {
        ResponseEntity<String> r = advice.handle(new IllegalStateException("select * from secret_table: password=hunter2"), request());
        assertEquals(500, r.getStatusCode().value());
        assertFalse(r.getBody().contains("hunter2"));
        assertFalse(r.getBody().contains("secret_table"));
        assertEquals("internal-error", Json.parseObject(r.getBody()).get("code"));
    }

    @Test
    void unreadableBodyIsBadRequest() {
        HttpMessageNotReadableException e = new HttpMessageNotReadableException("JSON parse error: Unexpected character", new MockHttpInputMessage(new byte[0]));
        ResponseEntity<String> r = advice.handle(e, request());
        assertEquals(400, r.getStatusCode().value());
        assertFalse(r.getBody().contains("Unexpected character"));
    }

    @Test
    void unsupportedMediaType() {
        ResponseEntity<String> r = advice.handle(new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, List.of()), request());
        assertEquals(415, r.getStatusCode().value());
    }

    @Test
    void frameworkNotFoundBecomesNotFoundProblem() {
        ResponseEntity<String> r = advice.handle(new ErrorResponseException(HttpStatus.NOT_FOUND), request());
        assertEquals(404, r.getStatusCode().value());
        assertEquals("not-found", Json.parseObject(r.getBody()).get("code"));
    }

    @Test
    void fieldCodesFollowConventions() {
        assertEquals("required", ProblemAdvice.fieldCode("NotBlank"));
        assertEquals("out_of_range", ProblemAdvice.fieldCode("Max"));
        assertEquals("too_long", ProblemAdvice.fieldCode("Size"));
        assertEquals("invalid_format", ProblemAdvice.fieldCode("Pattern"));
        assertEquals("invalid_format", ProblemAdvice.fieldCode(null));
    }
}
