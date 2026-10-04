package dgm.kit.json;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Тонкая обёртка над Jackson: весь код каркаса читает и пишет JSON только здесь, поэтому смена версии библиотеки меняет один файл.
 * Объекты читаются в {@code Map}, массивы в {@code List}, числа в {@code Integer}, {@code Long} или {@code Double}.
 */
public final class Json {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Json() {
    }

    /** Разбирает документ, корнем которого должен быть объект. */
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                copy.put(String.valueOf(e.getKey()), e.getValue());
            }
            return copy;
        }
        throw new JsonException("Корень документа должен быть объектом");
    }

    public static Object parse(String text) {
        if (text == null || text.isBlank()) {
            throw new JsonException("Пустой документ");
        }
        try {
            return MAPPER.readValue(text, Object.class);
        } catch (RuntimeException e) {
            throw new JsonException("Документ не является корректным JSON", e);
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (RuntimeException e) {
            throw new JsonException("Значение не записывается в JSON: " + value.getClass().getName(), e);
        }
    }
}
