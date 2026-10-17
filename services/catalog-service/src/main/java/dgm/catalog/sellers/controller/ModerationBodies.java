package dgm.catalog.sellers.controller;

import dgm.catalog.sellers.domain.FieldViolation;
import dgm.catalog.sellers.domain.SellerProfile;
import dgm.kit.json.Json;
import dgm.kit.json.JsonException;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.web.RequestBodies;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Чтение тела решения с причиной ({@code ReasonRequest}, {@code CommentRequest}): объект с одним обязательным текстовым полем от 1 до
 * 1000 знаков. Не JSON это 400, не тот {@code Content-Type} 415, лишнее поле, пустое или слишком длинное значение это 422 со списком
 * {@code errors}. Пробелы по краям убираются: причина из одних пробелов считается пустой.
 */
final class ModerationBodies {

    /** Причина до 1000 знаков, даже в 4 байтах на знак это меньше 8 КиБ с запасом на служебные символы JSON. */
    static final int MAX_BODY = 8 * 1024;
    static final int MAX_TEXT = 1000;

    private ModerationBodies() {
    }

    /**
     * @param field имя единственного поля тела ({@code reason} или {@code comment})
     * @return текст решения без пробелов по краям, от 1 до {@value #MAX_TEXT} знаков
     */
    static String requiredText(HttpServletRequest request, String field) throws IOException {
        String type = request.getContentType();
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw new ProblemException(ProblemType.UNSUPPORTED_MEDIA_TYPE, "Тип содержимого запроса не поддерживается, нужен application/json.");
        }
        Map<String, Object> body;
        try {
            body = Json.parseObject(new String(RequestBodies.read(request, MAX_BODY), StandardCharsets.UTF_8));
        } catch (JsonException e) {
            throw new ProblemException(ProblemType.BAD_REQUEST, "Тело запроса не разбирается как объект JSON.");
        }
        List<FieldViolation> errors = new ArrayList<>();
        for (String name : body.keySet()) {
            if (!name.equals(field)) {
                errors.add(new FieldViolation("/" + name, "unknown_value", "Лишнее поле."));
            }
        }
        String pointer = "/" + field;
        Object raw = body.get(field);
        String text = null;
        if (raw == null) {
            errors.add(new FieldViolation(pointer, "required", "Поле обязательно."));
        } else if (!(raw instanceof String value)) {
            errors.add(new FieldViolation(pointer, "invalid_format", "Нужна строка."));
        } else {
            text = value.strip();
            if (text.isEmpty()) {
                errors.add(new FieldViolation(pointer, "too_short", "Текст не может быть пустым."));
            } else if (text.codePointCount(0, text.length()) > MAX_TEXT) {
                errors.add(new FieldViolation(pointer, "too_long", "Текст не длиннее " + MAX_TEXT + " знаков."));
            }
        }
        if (!errors.isEmpty()) {
            throw SellerProfile.validation(errors);
        }
        return text;
    }
}
