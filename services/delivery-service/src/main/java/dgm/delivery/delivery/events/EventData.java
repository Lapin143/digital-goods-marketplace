package dgm.delivery.delivery.events;

import dgm.kit.events.EventEnvelope;
import dgm.kit.events.FatalEventException;
import dgm.kit.time.Timestamps;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Разбор данных входящих событий по схемам AsyncAPI. Любое нарушение схемы это неисправимая ошибка ({@link FatalEventException}): повтор не
 * поможет, событие уходит в DLQ сразу. Значения полей в текст ошибки не попадают (адрес в {@code order.paid} персональные данные).
 */
final class EventData {

    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private EventData() {
    }

    /** Идентификатор в каноническом виде (строчные буквы), как требует контракт. */
    static UUID uuid(EventEnvelope event, String field) {
        if (event.data().get(field) instanceof String value && ID.matcher(value).matches()) {
            return UUID.fromString(value);
        }
        throw new FatalEventException("В событии " + event.id() + " поле " + field + " не идентификатор");
    }

    static Instant instant(EventEnvelope event, String field) {
        if (event.data().get(field) instanceof String value) {
            return Timestamps.parse(value).orElseThrow(() -> new FatalEventException("В событии " + event.id() + " поле " + field + " не момент времени"));
        }
        throw new FatalEventException("В событии " + event.id() + " нет поля " + field);
    }

    /** Строка длиной от {@code min} до {@code max} знаков. */
    static String text(EventEnvelope event, String field, int min, int max) {
        if (event.data().get(field) instanceof String value && value.length() >= min && value.length() <= max) {
            return value;
        }
        throw new FatalEventException("В событии " + event.id() + " поле " + field + " не строка допустимой длины");
    }

    /** Целое число из диапазона. */
    static int integer(EventEnvelope event, String field, int min, int max) {
        if (event.data().get(field) instanceof Number number && number.doubleValue() == number.intValue() && number.intValue() >= min
                && number.intValue() <= max) {
            return number.intValue();
        }
        throw new FatalEventException("В событии " + event.id() + " поле " + field + " не число из допустимого диапазона");
    }
}
