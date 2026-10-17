package dgm.kit.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.json.Json;
import dgm.kit.problem.ProblemException;
import dgm.kit.route.RouteRule;
import dgm.kit.security.JwtFilter;
import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Предел размера тела (Р-29): объявленная длина, фактические байты при чтении, меньший предел вебхуков. */
class BodyLimitFilterTest {

    private static final RouteRule WEBHOOK = new RouteRule("POST", "/api/v1/webhooks/payments", "receivePaymentGatewayNotification", List.of(),
            List.of(), false, List.of(), false, null, "payment_webhook_secret");
    private static final RouteRule ORDINARY = new RouteRule("POST", "/api/v1/orders", "createOrder", List.of("orders.write"), List.of("buyer"),
            false, List.of());

    private final BodyLimitFilter filter = new BodyLimitFilter(100, 10);
    private final AtomicReference<String> read = new AtomicReference<>();

    private FilterChain readingChain() {
        return (request, response) -> read.set(new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static MockHttpServletRequest request(RouteRule route, int size, boolean declareLength) {
        byte[] body = "x".repeat(size).getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = new MockHttpServletRequest(route.method(), route.path()) {
            @Override
            public long getContentLengthLong() {
                return declareLength ? super.getContentLengthLong() : -1;
            }
        };
        request.setAttribute(JwtFilter.ROUTE_ATTRIBUTE, route);
        request.setContent(body);
        return request;
    }

    private static String code(MockHttpServletResponse response) throws Exception {
        return String.valueOf(Json.parseObject(response.getContentAsString()).get("code"));
    }

    @Test
    void bodyWithinLimitPassesAndIsReadable() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(ORDINARY, 100, true), response, readingChain());
        assertEquals(200, response.getStatus());
        assertEquals(100, read.get().length());
    }

    @Test
    void declaredLengthOverLimitIsRejectedBeforeReading() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(ORDINARY, 101, true), response, readingChain());
        assertEquals(413, response.getStatus());
        assertEquals("payload-too-large", code(response));
        assertNull(read.get());
    }

    @Test
    void bodyWithoutDeclaredLengthIsCountedWhileReading() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(ORDINARY, 101, false), response, readingChain());
        assertEquals(413, response.getStatus());
        assertEquals("payload-too-large", code(response));
        assertNull(read.get());
    }

    @Test
    void chunkedBodyWithinLimitPasses() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(ORDINARY, 100, false), response, readingChain());
        assertEquals(200, response.getStatus());
        assertEquals(100, read.get().length());
    }

    @Test
    void webhookRoutesHaveTheirOwnSmallerLimit() throws Exception {
        MockHttpServletResponse ok = new MockHttpServletResponse();
        filter.doFilter(request(WEBHOOK, 10, true), ok, readingChain());
        assertEquals(200, ok.getStatus());

        MockHttpServletResponse tooLarge = new MockHttpServletResponse();
        filter.doFilter(request(WEBHOOK, 11, true), tooLarge, readingChain());
        assertEquals(413, tooLarge.getStatus());
    }

    @Test
    void readerIsLimitedToo() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(ORDINARY, 101, false), response, (request, ignored) -> request.getReader().readLine());
        assertEquals(413, response.getStatus());
    }

    @Test
    void multipartFormGetsRoomForItsEnvelopeButNotForMoreThanThat() throws Exception {
        // Предел 100 относится к файлу: вокруг него форма добавляет границы и заголовки, и это не должно съедать допустимый размер файла
        MockHttpServletRequest within = request(ORDINARY, 100 + 200, true);
        within.setContentType("multipart/form-data; boundary=xyz");
        MockHttpServletResponse ok = new MockHttpServletResponse();
        filter.doFilter(within, ok, readingChain());
        assertEquals(200, ok.getStatus());
        assertEquals(300, read.get().length());

        MockHttpServletRequest beyond = request(ORDINARY, 100 + (int) RequestBodies.MULTIPART_ENVELOPE + 1, true);
        beyond.setContentType("multipart/form-data; boundary=xyz");
        MockHttpServletResponse tooLarge = new MockHttpServletResponse();
        filter.doFilter(beyond, tooLarge, readingChain());
        assertEquals(413, tooLarge.getStatus());
        assertEquals("payload-too-large", code(tooLarge));
        assertTrue(tooLarge.getContentAsString().contains("100 байт"), "клиенту назван предел файла, а не тела с запасом");
    }

    @Test
    void multipartEnvelopeIsCountedWhileReadingToo() throws Exception {
        MockHttpServletRequest chunked = request(ORDINARY, 100 + (int) RequestBodies.MULTIPART_ENVELOPE + 1, false);
        chunked.setContentType("Multipart/Form-Data; boundary=xyz");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(chunked, response, readingChain());
        assertEquals(413, response.getStatus());
        assertNull(read.get());
    }

    @Test
    void ordinaryBodyDoesNotGetTheEnvelope() throws Exception {
        MockHttpServletRequest json = request(ORDINARY, 101, true);
        json.setContentType("application/json");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(json, response, readingChain());
        assertEquals(413, response.getStatus());
    }

    @Test
    void requestBodiesKnowsMultipartAndItsLimit() {
        MockHttpServletRequest form = request(ORDINARY, 1, true);
        form.setContentType("multipart/form-data; boundary=b");
        assertTrue(RequestBodies.isMultipart(form));
        assertEquals(100 + RequestBodies.MULTIPART_ENVELOPE, RequestBodies.limitFor(form, 100));
        MockHttpServletRequest plain = request(ORDINARY, 1, true);
        assertEquals(100, RequestBodies.limitFor(plain, 100));
        assertEquals(100, RequestBodies.limitFor(new MockHttpServletRequest(), 100), "типа содержимого нет");
        assertTrue(RequestBodies.fileTooLarge(2L * 1024 * 1024).getMessage().contains("Файл больше допустимых 2 МБ"));
    }

    @Test
    void limitMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new BodyLimitFilter(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new BodyLimitFilter(10, -1));
    }

    // --- RequestBodies --------------------------------------------------------------------------------------------------------------

    @Test
    void requestBodiesReadsWithinLimitAndCachesIt() throws Exception {
        MockHttpServletRequest request = request(ORDINARY, 5, true);
        CachedBodyRequest cached = RequestBodies.cached(request, 10);
        assertEquals("xxxxx", new String(cached.body(), StandardCharsets.UTF_8));
        assertEquals("xxxxx", new String(cached.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertEquals("xxxxx", new String(cached.getInputStream().readAllBytes(), StandardCharsets.UTF_8), "тело можно прочитать повторно");
        assertSame(cached, RequestBodies.cached(cached, 10), "вторая обёртка не создаётся");
        assertNotNull(RequestBodies.read(cached, 1));
    }

    @Test
    void requestBodiesRefusesBodyOverLimit() {
        ProblemException declared = assertThrows(ProblemException.class, () -> RequestBodies.read(request(ORDINARY, 11, true), 10));
        assertEquals(413, declared.type().status());
        assertThrows(ProblemException.class, () -> RequestBodies.read(request(ORDINARY, 11, false), 10));
    }

    @Test
    void limitIsDescribedInHumanUnits() {
        assertTrue(RequestBodies.tooLarge(2L * 1024 * 1024).getMessage().contains("2 МБ"));
        assertTrue(RequestBodies.tooLarge(64L * 1024).getMessage().contains("64 КБ"));
        assertTrue(RequestBodies.tooLarge(100).getMessage().contains("100 байт"));
    }
}
