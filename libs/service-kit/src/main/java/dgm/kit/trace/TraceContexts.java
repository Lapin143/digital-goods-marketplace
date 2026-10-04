package dgm.kit.trace;

import java.util.Optional;
import org.slf4j.MDC;

/** Контекст запроса в текущем потоке и в MDC журнала: поля traceId, spanId, correlationId входят в каждую строку журнала. */
public final class TraceContexts {

    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_SPAN_ID = "spanId";
    public static final String MDC_CORRELATION_ID = "correlationId";

    private static final ThreadLocal<TraceContext> CURRENT = new ThreadLocal<>();

    private TraceContexts() {
    }

    public static Optional<TraceContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** Текущий контекст или новый, если запроса нет (задача по расписанию). Новый нигде не сохраняется. */
    public static TraceContext currentOrFresh() {
        TraceContext c = CURRENT.get();
        return c != null ? c : TraceContext.fresh();
    }

    /** Делает контекст текущим. Возвращённую область обязательно закрывают в {@code finally}. */
    public static Scope open(TraceContext context) {
        TraceContext previous = CURRENT.get();
        CURRENT.set(context);
        MDC.put(MDC_TRACE_ID, context.traceId());
        MDC.put(MDC_SPAN_ID, context.spanId());
        MDC.put(MDC_CORRELATION_ID, context.correlationId());
        return new Scope(previous);
    }

    /** Область действия контекста: закрытие возвращает прежний контекст. */
    public static final class Scope {

        private final TraceContext previous;

        private Scope(TraceContext previous) {
            this.previous = previous;
        }

        public void close() {
            if (previous == null) {
                CURRENT.remove();
                MDC.remove(MDC_TRACE_ID);
                MDC.remove(MDC_SPAN_ID);
                MDC.remove(MDC_CORRELATION_ID);
            } else {
                CURRENT.set(previous);
                MDC.put(MDC_TRACE_ID, previous.traceId());
                MDC.put(MDC_SPAN_ID, previous.spanId());
                MDC.put(MDC_CORRELATION_ID, previous.correlationId());
            }
        }
    }
}
