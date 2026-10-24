package dgm.platform.support.handler;

import dgm.kit.events.FatalEventException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;

/**
 * Чтение полей {@code data} события. Нарушение схемы неисправимо: повтор события не изменит его содержимого, поэтому оно сразу уходит в
 * DLQ. Сообщения об ошибках называют поле, но не значение.
 */
final class EventFields {

    private EventFields() {
    }

    static UUID uuid(Map<String, Object> data, String field) {
        try {
            return UUID.fromString(text(data, field, 36));
        } catch (IllegalArgumentException e) {
            throw new FatalEventException("Поле " + field + " должно быть UUID");
        }
    }

    static String text(Map<String, Object> data, String field, int maxLength) {
        Object value = data.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new FatalEventException("Нет обязательного поля " + field + " или оно не строка");
        }
        if (text.length() > maxLength) {
            throw new FatalEventException("Поле " + field + " длиннее " + maxLength + " символов");
        }
        return text;
    }

    /** Положительное целое: номер заказа начинается с единицы ({@code ck_order_view_order_number}). */
    static long positive(Map<String, Object> data, String field) {
        Object value = data.get(field);
        if (!(value instanceof Number number) || number.doubleValue() != number.longValue() || number.longValue() < 1) {
            throw new FatalEventException("Поле " + field + " должно быть целым числом от 1");
        }
        return number.longValue();
    }

    static Instant instant(Map<String, Object> data, String field) {
        try {
            return java.time.OffsetDateTime.parse(text(data, field, 40)).toInstant();
        } catch (DateTimeParseException e) {
            throw new FatalEventException("Поле " + field + " не разбирается как время RFC 3339");
        }
    }
}
