package dgm.platform.notification.handler;

import dgm.kit.events.FatalEventException;
import java.util.Map;
import java.util.UUID;

/**
 * Чтение полей {@code data} события. Нарушение схемы неисправимо: повтор события не изменит его содержимого, поэтому оно сразу уходит в
 * DLQ. Сообщения об ошибках называют поле, но не значение: в значениях бывает текст модератора.
 */
final class EventData {

    private EventData() {
    }

    static UUID uuid(Map<String, Object> data, String field) {
        String value = text(data, field, 36);
        try {
            return UUID.fromString(value);
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

    /** Целое число без знака как строка цифр (параметры шаблонов строковые). */
    static String number(Map<String, Object> data, String field) {
        Object value = data.get(field);
        if (!(value instanceof Number number) || number.doubleValue() != number.longValue() || number.longValue() < 0) {
            throw new FatalEventException("Поле " + field + " должно быть целым числом не меньше нуля");
        }
        return Long.toString(number.longValue());
    }

    /** Поле {@code Money} схемы событий ({@code amount} в минимальных единицах и {@code currency}) как «149900 RUB». */
    static String money(Map<String, Object> data, String field) {
        if (!(data.get(field) instanceof Map<?, ?> money)) {
            throw new FatalEventException("Нет обязательного поля " + field + " или оно не сумма");
        }
        Map<String, Object> inner = new java.util.LinkedHashMap<>();
        money.forEach((k, v) -> inner.put(String.valueOf(k), v));
        return number(inner, "amount") + " " + text(inner, "currency", 3);
    }
}
