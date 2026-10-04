package dgm.kit.trace;

import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Сквозной контекст запроса: W3C Trace Context и идентификатор корреляции (conventions.md, раздел 8.3).
 *
 * @param traceId       32 шестнадцатеричных символа, общий для всех отрезков запроса
 * @param spanId        16 шестнадцатеричных символов, свой у каждого отрезка (вызова, обработки события)
 * @param flags         два шестнадцатеричных символа, 01 значит «записывать трассу»
 * @param correlationId UUID из X-Correlation-Id: по нему ищут журналы и пишут его в тело проблемы и в конверт события
 */
public record TraceContext(String traceId, String spanId, String flags, String correlationId) {

    private static final Pattern TRACEPARENT = Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");
    private static final Pattern UUID_TEXT = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final HexFormat HEX = HexFormat.of();

    /** Новый контекст: у задачи по расписанию нет входящего запроса, поэтому трассу и корреляцию создаёт она сама. */
    public static TraceContext fresh() {
        return new TraceContext(randomHex(16), randomHex(8), "01", UUID.randomUUID().toString());
    }

    /**
     * Контекст входящего запроса. Верный {@code traceparent} сохраняет трассу, но отрезок получает новый; неверный или отсутствующий
     * начинает трассу заново. Значение X-Correlation-Id, не похожее на UUID, заменяется новым.
     */
    public static TraceContext incoming(String traceparent, String correlationId) {
        String corr = correlationId != null && UUID_TEXT.matcher(correlationId).matches()
                ? correlationId.toLowerCase(java.util.Locale.ROOT) : UUID.randomUUID().toString();
        if (traceparent != null) {
            var m = TRACEPARENT.matcher(traceparent);
            if (m.matches() && !isZero(m.group(1)) && !isZero(m.group(2))) {
                return new TraceContext(m.group(1), randomHex(8), m.group(3), corr);
            }
        }
        return new TraceContext(randomHex(16), randomHex(8), "01", corr);
    }

    /** Тот же запрос, новый отрезок: так выглядит исходящий вызов или событие. */
    public TraceContext child() {
        return new TraceContext(traceId, randomHex(8), flags, correlationId);
    }

    /** Значение заголовка traceparent: {@code 00-<traceId>-<spanId>-<flags>}. */
    public String traceparent() {
        return "00-" + traceId + "-" + spanId + "-" + flags;
    }

    private static boolean isZero(String hex) {
        return hex.chars().allMatch(c -> c == '0');
    }

    private static String randomHex(int bytes) {
        byte[] raw = new byte[bytes];
        do {
            ThreadLocalRandom.current().nextBytes(raw);
        } while (isZero(HEX.formatHex(raw)));
        return HEX.formatHex(raw);
    }
}
