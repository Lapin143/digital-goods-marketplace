package dgm.inventory.inventory.keys.controller;

import dgm.inventory.inventory.keys.domain.KeyBatch;
import dgm.kit.json.Json;
import dgm.kit.json.JsonException;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.web.RequestBodies;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Чтение тела загрузки ключей. Значения ключей из тела никуда не пишутся: ни в журнал, ни в сообщения об ошибках, ни на диск
 * (файл CSV читается из запроса и нигде не сохраняется, ADR-009). Не тот {@code Content-Type} это 415, слишком большое тело 413, тело
 * не JSON 400, нарушение правила 422 со списком {@code errors}.
 */
public final class KeyBodies {

    /** Тело не больше 2 МБ, как на шлюзе (ADR-009). */
    static final long MAX_BODY = 2L * 1024 * 1024;

    private KeyBodies() {
    }

    /** Ключи из тела {@code KeyBatchRequest}: объект с единственным полем {@code keys}, от 1 до 10000 строк. */
    public static List<String> batch(HttpServletRequest request) throws IOException {
        requireType(request, "application/json");
        Map<String, Object> body;
        try {
            body = Json.parseObject(new String(RequestBodies.read(request, MAX_BODY), StandardCharsets.UTF_8));
        } catch (JsonException e) {
            throw new ProblemException(ProblemType.BAD_REQUEST, "Тело запроса не разбирается как объект JSON.");
        }
        List<Map<String, Object>> errors = new ArrayList<>();
        for (String name : body.keySet()) {
            if (!"keys".equals(name)) {
                errors.add(error("/" + name, "unknown_value", "Лишнее поле."));
            }
        }
        List<String> keys = new ArrayList<>();
        if (!(body.get("keys") instanceof List<?> items)) {
            errors.add(error("/keys", body.containsKey("keys") ? "invalid_format" : "required", "Нужен массив строк."));
        } else {
            if (items.isEmpty() || items.size() > KeyBatch.MAX_KEYS) {
                errors.add(error("/keys", "out_of_range", "В загрузке от 1 до " + KeyBatch.MAX_KEYS + " ключей."));
            }
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i) instanceof String text) {
                    keys.add(text);
                } else {
                    errors.add(error("/keys/" + i, "invalid_format", "Ключ это строка."));
                }
            }
        }
        if (!errors.isEmpty()) {
            throw validation(errors);
        }
        return keys;
    }

    /**
     * Строки CSV: UTF-8, по ключу в строке, перевод строки {@code \n} или {@code \r\n}; завершающий перевод строки пустой строки не
     * порождает, пустые строки в середине остаются (их отклонит разбор как {@code empty}).
     */
    public static List<String> csv(HttpServletRequest request) throws IOException {
        requireType(request, "text/csv");
        byte[] body = RequestBodies.read(request, MAX_BODY);
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException e) {
            throw validation(List.of(error("/file", "invalid_format", "Файл должен быть в кодировке UTF-8.")));
        }
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        if (text.isEmpty()) {
            throw validation(List.of(error("/file", "required", "Файл пуст.")));
        }
        List<String> lines = new ArrayList<>();
        int start = 0;
        while (start <= text.length()) {
            int end = text.indexOf('\n', start);
            if (end < 0) {
                if (start < text.length()) {
                    lines.add(stripCarriageReturn(text.substring(start)));
                }
                break;
            }
            lines.add(stripCarriageReturn(text.substring(start, end)));
            start = end + 1;
            if (lines.size() > KeyBatch.MAX_KEYS) {
                break;
            }
        }
        if (lines.size() > KeyBatch.MAX_KEYS) {
            throw new ProblemException(ProblemType.PAYLOAD_TOO_LARGE, "В файле не больше " + KeyBatch.MAX_KEYS + " строк.");
        }
        return lines;
    }

    private static String stripCarriageReturn(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    private static void requireType(HttpServletRequest request, String expected) {
        String type = request.getContentType();
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith(expected)) {
            throw new ProblemException(ProblemType.UNSUPPORTED_MEDIA_TYPE, "Тип содержимого запроса не поддерживается, нужен " + expected + ".");
        }
    }

    private static Map<String, Object> error(String pointer, String code, String detail) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("pointer", pointer);
        error.put("code", code);
        error.put("detail", detail);
        return error;
    }

    private static ProblemException validation(List<Map<String, Object>> errors) {
        return new ProblemException(ProblemType.VALIDATION_FAILED, "Данные не прошли проверку, подробности в поле errors.",
                Map.of("errors", List.copyOf(errors)), Map.of());
    }
}
