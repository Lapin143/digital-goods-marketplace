package dgm.kit.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Журнал обращений: одна строка INFO на каждый ответ прикладного порта, логгер {@code dgm.access}. В строке метод, путь без строки
 * запроса (в ней бывают токены и персональные данные), статус и время в миллисекундах; сквозной идентификатор печатается полем MDC
 * {@code correlationId}, потому что фильтр стоит после {@link TraceFilter}. По этой строке в Loki видно, через какие контейнеры прошёл
 * запрос шлюза (шаг 17 Ф3, дымовой тест).
 */
public final class AccessLogFilter extends OncePerRequestFilter {

    public static final String LOGGER = "dgm.access";

    private static final int MAX_PATH = 200;
    private static final Logger LOG = LoggerFactory.getLogger(LOGGER);

    private final Clock clock;

    public AccessLogFilter(Clock clock) {
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = clock.millis();
        try {
            chain.doFilter(request, response);
        } finally {
            if (LOG.isInfoEnabled()) {
                String path = request.getRequestURI();
                LOG.info("{} {} {} {} мс", request.getMethod(), path.length() > MAX_PATH ? path.substring(0, MAX_PATH) : path,
                        response.getStatus(), clock.millis() - started);
            }
        }
    }
}
