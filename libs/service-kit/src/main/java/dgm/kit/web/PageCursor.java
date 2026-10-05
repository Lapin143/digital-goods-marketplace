package dgm.kit.web;

import dgm.kit.json.Json;
import dgm.kit.json.JsonException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Курсор страницы: последнее значение ключа сортировки, закодированное в непрозрачную строку (conventions.md, 10.4). Курсор не содержит
 * персональных данных и не является защитой: права проверяются на каждой странице.
 */
public final class PageCursor {

    private PageCursor() {
    }

    /** Кодирует пары «имя: значение» (порядок сохраняется) в строку из символов base64url. */
    public static String encode(Map<String, String> values) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Json.write(new LinkedHashMap<>(values)).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Разбирает курсор.
     *
     * @throws IllegalArgumentException текст не является курсором: не base64url, не объект JSON или значения не строки
     */
    public static Map<String, String> decode(String cursor) {
        try {
            String json = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            Map<String, String> result = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : Json.parseObject(json).entrySet()) {
                if (!(e.getValue() instanceof String s)) {
                    throw new IllegalArgumentException("Значение курсора не строка");
                }
                result.put(e.getKey(), s);
            }
            return result;
        } catch (JsonException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Курсор не разобран", e);
        }
    }
}
