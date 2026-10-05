package dgm.gateway;

import dgm.kit.problem.ProblemType;
import dgm.kit.trace.TraceContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.channels.ClosedChannelException;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * Любая ошибка, дошедшая до конца цепочки, становится ответом-проблемой RFC 9457 того же вида, что у сервисов: клиент разбирает один
 * формат. Подробности (адреса сервисов, имена классов, трассировка) в ответ не попадают, они остаются в журнале. Обработчик стоит раньше
 * стандартного обработчика Spring Boot, который отвечает страницей или JSON другого вида.
 *
 * <ul>
 *   <li>сервис не отвечает (отказ в соединении, обрыв, ошибка TLS): 503;
 *   <li>сервис отвечает дольше срока: 504;
 *   <li>ошибка Spring с кодом (нет маршрута, неверный запрос): код сохраняется, тип подбирается по нему;
 *   <li>всё остальное: 500.
 * </ul>
 */
public final class ProblemErrorHandler implements WebExceptionHandler, Ordered {

    private static final Logger LOG = LoggerFactory.getLogger(ProblemErrorHandler.class);

    private final MeterRegistry meters;

    public ProblemErrorHandler(MeterRegistry meters) {
        this.meters = meters;
    }

    /** Раньше стандартного обработчика Spring Boot (порядок -1). */
    @Override
    public int getOrder() {
        return -2;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(error);
        }
        TraceContext trace = exchange.getAttribute(EntryFilter.TRACE);
        String correlationId = trace == null ? TraceContext.fresh().correlationId() : trace.correlationId();
        Classified c = classify(error);
        meters.counter("dgm.gateway.rejected", "reason", c.reason()).increment();
        if (c.type().status() >= 500) {
            // В журнал уходит причина без трассировки, кроме непредвиденной ошибки: её трассировка нужна для разбора
            if (c.type() == ProblemType.INTERNAL_ERROR) {
                LOG.error("Непредвиденная ошибка шлюза, запрос {} {}", exchange.getRequest().getMethod(), exchange.getRequest().getURI().getRawPath(),
                        error);
            } else {
                LOG.warn("Сервис не ответил как нужно ({}): {}", c.reason(), describe(error));
            }
        }
        return EntryFilter.writeProblem(exchange, correlationId, c.type(), c.detail(), c.type() == ProblemType.DEPENDENCY_UNAVAILABLE
                ? Map.of("Retry-After", "5") : Map.of());
    }

    /** Тип проблемы, объяснение для клиента и причина для метрики. */
    record Classified(ProblemType type, String detail, String reason) {
    }

    static Classified classify(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            String name = t.getClass().getSimpleName();
            if (t instanceof TimeoutException || name.contains("Timeout")) {
                return new Classified(ProblemType.GATEWAY_TIMEOUT, "Сервис не ответил за отведённое время.", "upstream_timeout");
            }
            if (t instanceof ResponseStatusException status && (status.getStatusCode().value() < 500
                    || status.getStatusCode().value() >= 502 && status.getStatusCode().value() <= 504)) {
                return fromStatus(status.getStatusCode().value());
            }
            if (t instanceof ConnectException || t instanceof UnknownHostException || t instanceof SSLException
                    || t instanceof ClosedChannelException || name.contains("PrematureClose") || name.contains("Connect")) {
                return new Classified(ProblemType.DEPENDENCY_UNAVAILABLE, "Сервис временно недоступен.", "upstream_unavailable");
            }
        }
        return new Classified(ProblemType.INTERNAL_ERROR, "Внутренняя ошибка шлюза.", "internal_error");
    }

    private static Classified fromStatus(int status) {
        return switch (status) {
            case 400 -> new Classified(ProblemType.BAD_REQUEST, "Некорректный запрос.", "bad_request");
            case 404 -> new Classified(ProblemType.NOT_FOUND, "Такого маршрута нет.", "not_found");
            case 413 -> new Classified(ProblemType.PAYLOAD_TOO_LARGE, "Тело запроса больше допустимого.", "payload_too_large");
            case 415 -> new Classified(ProblemType.UNSUPPORTED_MEDIA_TYPE, "Тип содержимого не поддерживается.", "unsupported_media_type");
            case 502, 503 -> new Classified(ProblemType.DEPENDENCY_UNAVAILABLE, "Сервис временно недоступен.", "upstream_unavailable");
            case 504 -> new Classified(ProblemType.GATEWAY_TIMEOUT, "Сервис не ответил за отведённое время.", "upstream_timeout");
            default -> status >= 400 && status < 500
                    ? new Classified(ProblemType.BAD_REQUEST, "Некорректный запрос.", "bad_request")
                    : new Classified(ProblemType.INTERNAL_ERROR, "Внутренняя ошибка шлюза.", "internal_error");
        };
    }

    /** Одна строка для журнала: класс и сообщение первой причины, без трассировки. */
    private static String describe(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
