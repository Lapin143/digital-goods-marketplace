package dgm.kit.problem;

import dgm.kit.trace.TraceContext;
import dgm.kit.trace.TraceContexts;
import dgm.kit.trace.TraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/** Пишет ответ-проблему в ответ сервлета: для фильтров, которые отвечают сами, до контроллеров. */
public final class ProblemWriter {

    public static final String MEDIA_TYPE = "application/problem+json";

    private ProblemWriter() {
    }

    public static Problem problem(HttpServletRequest request, ProblemType type, String detail, Map<String, Object> extensions) {
        return new Problem(type, detail, request.getRequestURI(), correlationId(request), extensions);
    }

    public static void write(HttpServletRequest request, HttpServletResponse response, ProblemType type, String detail)
            throws IOException {
        write(request, response, problem(request, type, detail, Map.of()), Map.of());
    }

    public static void write(HttpServletRequest request, HttpServletResponse response, Problem problem, Map<String, String> headers)
            throws IOException {
        response.setStatus(problem.type().status());
        response.setContentType(MEDIA_TYPE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        if (problem.type().status() == 401 && !headers.containsKey("WWW-Authenticate")) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        headers.forEach(response::setHeader);
        response.getWriter().write(problem.toJson());
        response.getWriter().flush();
    }

    /** Идентификатор корреляции запроса: из фильтра, из заголовка или, если запрос пришёл мимо фильтра, новый. */
    public static String correlationId(HttpServletRequest request) {
        Object attr = request.getAttribute(TraceFilter.ATTRIBUTE);
        if (attr instanceof TraceContext context) {
            return context.correlationId();
        }
        return TraceContexts.current().map(TraceContext::correlationId).orElseGet(() -> {
            String header = request.getHeader(TraceFilter.CORRELATION_ID);
            return header != null && header.length() == 36 ? header : UUID.randomUUID().toString();
        });
    }
}
