package dgm.kit.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String PARENT = "00-" + TRACE_ID + "-b7ad6b7169203331-01";
    private static final String CORRELATION = "6b3c0a1e-1111-4111-8111-111111111111";

    @Test
    void validTraceparentKeepsTraceButStartsNewSpan() {
        TraceContext c = TraceContext.incoming(PARENT, CORRELATION);
        assertEquals(TRACE_ID, c.traceId());
        assertNotEquals("b7ad6b7169203331", c.spanId());
        assertEquals(CORRELATION, c.correlationId());
        assertTrue(c.traceparent().matches("^00-[0-9a-f]{32}-[0-9a-f]{16}-01$"));
    }

    @Test
    void missingOrBrokenInputIsReplaced() {
        for (String bad : new String[] {null, "", "garbage", "00-" + "0".repeat(32) + "-b7ad6b7169203331-01", "ff-" + TRACE_ID + "-b7ad6b7169203331-01"}) {
            TraceContext c = TraceContext.incoming(bad, "not-a-uuid");
            assertTrue(c.traceId().matches("[0-9a-f]{32}"));
            assertNotEquals(TRACE_ID, c.traceId());
            assertEquals(36, c.correlationId().length());
            assertNotEquals("not-a-uuid", c.correlationId());
        }
    }

    @Test
    void childKeepsTraceAndCorrelation() {
        TraceContext c = TraceContext.incoming(PARENT, CORRELATION);
        TraceContext child = c.child();
        assertEquals(c.traceId(), child.traceId());
        assertEquals(c.correlationId(), child.correlationId());
        assertNotEquals(c.spanId(), child.spanId());
    }

    @Test
    void freshContextsDiffer() {
        assertNotEquals(TraceContext.fresh().traceId(), TraceContext.fresh().traceId());
    }

    @Test
    void filterSetsHeadersMdcAndAttributeThenClears() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders");
        request.addHeader("traceparent", PARENT);
        request.addHeader("X-Correlation-Id", CORRELATION);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuring = new AtomicReference<>();
        AtomicReference<String> traceDuring = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            private static final long serialVersionUID = 1L;

            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest req, jakarta.servlet.http.HttpServletResponse res) {
                mdcDuring.set(MDC.get(TraceContexts.MDC_CORRELATION_ID));
                traceDuring.set(TraceContexts.current().map(TraceContext::traceId).orElse(null));
            }
        });

        new TraceFilter().doFilter(request, response, chain);

        assertEquals(CORRELATION, mdcDuring.get());
        assertEquals(TRACE_ID, traceDuring.get());
        assertEquals(CORRELATION, response.getHeader("X-Correlation-Id"));
        assertTrue(response.getHeader("traceparent").startsWith("00-" + TRACE_ID + "-"));
        assertTrue(request.getAttribute(TraceFilter.ATTRIBUTE) instanceof TraceContext);
        assertNull(MDC.get(TraceContexts.MDC_CORRELATION_ID));
        assertTrue(TraceContexts.current().isEmpty());
    }

    @Test
    void scopeRestoresPreviousContext() {
        TraceContext outer = TraceContext.fresh();
        TraceContext inner = TraceContext.fresh();
        TraceContexts.Scope a = TraceContexts.open(outer);
        try {
            TraceContexts.Scope b = TraceContexts.open(inner);
            assertEquals(inner.correlationId(), MDC.get(TraceContexts.MDC_CORRELATION_ID));
            b.close();
            assertEquals(outer.correlationId(), MDC.get(TraceContexts.MDC_CORRELATION_ID));
        } finally {
            a.close();
        }
        assertNull(MDC.get(TraceContexts.MDC_CORRELATION_ID));
    }
}
