package dgm.kit.time;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/** Момент времени в контрактах: RFC 3339 в UTC с миллисекундами и суффиксом Z (conventions.md, раздел 5), например 2026-10-03T12:00:00.123Z. */
public final class Timestamps {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private Timestamps() {
    }

    /** Текст для ответа; доли миллисекунды отбрасываются. */
    public static String format(Instant instant) {
        return FORMAT.format(instant);
    }

    /** Разбор параметра запроса: RFC 3339 с любым смещением. Неверный текст даёт пустой результат. */
    public static Optional<Instant> parse(String text) {
        try {
            return Optional.of(OffsetDateTime.parse(text).toInstant());
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
