package dgm.kit.problem;

import java.util.LinkedHashMap;
import java.util.Map;

/** Ошибка, которую сервис превращает в ответ-проблему: тип из реестра, объяснение случая, расширения и заголовки ответа. */
public final class ProblemException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProblemType type;
    private final transient Map<String, Object> extensions;
    private final transient Map<String, String> headers;

    public ProblemException(ProblemType type, String detail) {
        this(type, detail, Map.of(), Map.of());
    }

    public ProblemException(ProblemType type, String detail, Map<String, Object> extensions, Map<String, String> headers) {
        super(detail);
        this.type = type;
        this.extensions = new LinkedHashMap<>(extensions);
        this.headers = new LinkedHashMap<>(headers);
    }

    /**
     * Зависимость не ответила вовремя или недоступна: 503 {@code dependency-unavailable} с обязательным {@code Retry-After}
     * (conventions.md, 9.1). Так же отвечают при недоступном Redis там, где без него действие небезопасно, например при отправке SMS.
     */
    public static ProblemException dependencyUnavailable(String detail, int retryAfterSeconds) {
        return new ProblemException(ProblemType.DEPENDENCY_UNAVAILABLE, detail, Map.of(), Map.of("Retry-After", Integer.toString(retryAfterSeconds)));
    }

    public ProblemType type() {
        return type;
    }

    public Map<String, Object> extensions() {
        return Map.copyOf(extensions);
    }

    public Map<String, String> headers() {
        return Map.copyOf(headers);
    }
}
