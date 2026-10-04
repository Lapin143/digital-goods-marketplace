package dgm.kit.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Фильтр сквозного идентификатора (компонент {@code trace-filter}, c4-components.md, раздел 2).
 *
 * <p>Принимает {@code traceparent} и {@code X-Correlation-Id}, создаёт недостающее, кладёт контекст в MDC журнала и в атрибут запроса
 * и возвращает оба заголовка в каждом ответе. Фильтр стоит первым в цепочке, чтобы ответы об ошибке фильтров токена и вызывающего
 * тоже несли идентификатор.
 */
public final class TraceFilter extends OncePerRequestFilter {

    public static final String ATTRIBUTE = "dgm.trace";
    public static final String TRACEPARENT = "traceparent";
    public static final String CORRELATION_ID = "X-Correlation-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        TraceContext context = TraceContext.incoming(request.getHeader(TRACEPARENT), request.getHeader(CORRELATION_ID));
        request.setAttribute(ATTRIBUTE, context);
        response.setHeader(TRACEPARENT, context.traceparent());
        response.setHeader(CORRELATION_ID, context.correlationId());
        TraceContexts.Scope scope = TraceContexts.open(context);
        try {
            chain.doFilter(request, response);
        } finally {
            scope.close();
        }
    }
}
