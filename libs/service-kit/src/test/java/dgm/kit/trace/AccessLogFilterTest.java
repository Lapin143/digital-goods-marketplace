package dgm.kit.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dgm.kit.time.ManualClock;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AccessLogFilterTest {

    private static final String CORRELATION = "6b3c0a1e-1111-4111-8111-111111111111";

    private final Logger logger = (Logger) LoggerFactory.getLogger(AccessLogFilter.LOGGER);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final ManualClock clock = new ManualClock(Instant.parse("2026-10-05T10:00:00Z"));
    private Level before;

    @BeforeEach
    void attach() {
        before = logger.getLevel();
        logger.setLevel(Level.INFO);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        logger.setLevel(before);
        appender.stop();
    }

    private MockFilterChain chain(int status, Duration takes) {
        return new MockFilterChain(new HttpServlet() {
            private static final long serialVersionUID = 1L;

            @Override
            protected void service(HttpServletRequest req, HttpServletResponse res) {
                clock.advance(takes);
                res.setStatus(status);
            }
        });
    }

    @Test
    void writesOneLineWithMethodPathStatusAndTimeWithoutQuery() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/products");
        request.setQueryString("access_token=secret&page=2");
        new AccessLogFilter(clock).doFilter(request, new MockHttpServletResponse(), chain(200, Duration.ofMillis(37)));

        assertEquals(1, appender.list.size());
        ILoggingEvent event = appender.list.get(0);
        assertEquals(Level.INFO, event.getLevel());
        assertEquals("GET /api/v1/products 200 37 мс", event.getFormattedMessage());
        assertFalse(event.getFormattedMessage().contains("secret"));
    }

    @Test
    void correlationIdFromTraceFilterIsInMdcOfTheLine() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders");
        request.addHeader("X-Correlation-Id", CORRELATION);
        MockFilterChain inner = new MockFilterChain(new HttpServlet() {
            private static final long serialVersionUID = 1L;

            @Override
            protected void service(HttpServletRequest req, HttpServletResponse res) {
                res.setStatus(403);
            }
        }, new AccessLogFilter(clock));
        new TraceFilter().doFilter(request, new MockHttpServletResponse(), inner);

        assertEquals(1, appender.list.size());
        assertEquals(CORRELATION, appender.list.get(0).getMDCPropertyMap().get(TraceContexts.MDC_CORRELATION_ID));
        assertTrue(appender.list.get(0).getFormattedMessage().contains(" 403 "));
    }

    @Test
    void failureOfTheChainIsStillLoggedAndRethrown() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/orders");
        MockFilterChain boom = new MockFilterChain(new HttpServlet() {
            private static final long serialVersionUID = 1L;

            @Override
            protected void service(HttpServletRequest req, HttpServletResponse res) {
                throw new IllegalStateException("сбой");
            }
        });
        assertThrows(IllegalStateException.class, () -> new AccessLogFilter(clock).doFilter(request, new MockHttpServletResponse(), boom));
        assertEquals(1, appender.list.size());
        assertTrue(appender.list.get(0).getFormattedMessage().startsWith("POST /api/v1/orders "));
    }

    @Test
    void longPathIsCut() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/" + "a".repeat(500));
        new AccessLogFilter(clock).doFilter(request, new MockHttpServletResponse(), chain(404, Duration.ZERO));

        String line = appender.list.get(0).getFormattedMessage();
        assertEquals("GET " + ("/api/" + "a".repeat(500)).substring(0, 200) + " 404 0 мс", line);
    }

    @Test
    void silentWhenInfoIsOff() throws ServletException, IOException {
        logger.setLevel(Level.WARN);
        new AccessLogFilter(clock).doFilter(new MockHttpServletRequest("GET", "/"), new MockHttpServletResponse(), chain(200, Duration.ZERO));
        assertTrue(appender.list.isEmpty());
    }
}
