package dgm.kit.client;

import dgm.kit.problem.ProblemException;

/**
 * Сосед или внешняя система недоступны: сеть, тайм-аут, ответ 5xx или 429 после всех попыток, исчерпанный общий срок или
 * открытый предохранитель. Отказ по смыслу (ответ 4xx) сообщается другим исключением, {@link DependencyRejectedException}.
 *
 * <p>Текст ошибки не содержит адресов, заголовков и тел ответов: в журнал идёт имя соседа и код причины.
 */
public final class DependencyUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Коды причин (значения метки {@code outcome} метрики и поля {@link #reason()}). */
    public static final String BREAKER_OPEN = "breaker_open";
    public static final String TIMEOUT = "timeout";
    public static final String IO_ERROR = "io_error";
    public static final String SERVER_ERROR = "server_error";
    public static final String DEADLINE = "deadline";
    public static final String INTERRUPTED = "interrupted";

    private final String peer;
    private final String reason;
    private final int lastStatus;

    /**
     * @param peer       имя соседа
     * @param reason     код причины, одна из констант класса
     * @param lastStatus последний полученный код ответа, 0 если ответа не было
     */
    public DependencyUnavailableException(String peer, String reason, int lastStatus, Throwable cause) {
        super("Сосед " + peer + " недоступен: " + reason + (lastStatus > 0 ? " (" + lastStatus + ")" : ""), cause);
        this.peer = peer;
        this.reason = reason;
        this.lastStatus = lastStatus;
    }

    public String peer() {
        return peer;
    }

    public String reason() {
        return reason;
    }

    public int lastStatus() {
        return lastStatus;
    }

    /** Ответ 503 для пользователя с заголовком {@code Retry-After}, если вызывающему нечего сказать точнее. */
    public ProblemException toProblem(int retryAfterSeconds) {
        return ProblemException.dependencyUnavailable("Сервис временно недоступен, повторите запрос позже.", retryAfterSeconds);
    }
}
