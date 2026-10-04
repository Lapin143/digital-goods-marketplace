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
