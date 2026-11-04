package dgm.catalog.sellers.apikey.controller;

import dgm.catalog.sellers.apikey.domain.ApiKeyForm;
import dgm.catalog.sellers.apikey.domain.ApiKeyValues;
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
import java.util.Set;

/**
 * Чтение тел запросов {@code ApiKeyRequest} и {@code ApiKeyCheckRequest}. Не JSON это 400, не тот {@code Content-Type} 415, лишнее поле,
 * поле не того типа и нарушение формата 422 со списком {@code errors} (схемы закрыты, {@code additionalProperties: false}). Пробелы по
 * краям имени убираются.
 */
final class ApiKeyForms {

    /** Тела короткие: имя до 60 знаков и хеш из 64 знаков; предел с запасом. */
    static final int MAX_BODY = 4 * 1024;

    private ApiKeyForms() {
    }

    static ApiKeyForm readRequest(HttpServletRequest request) throws IOException {
        Map<String, Object> body = object(request);
        List<FieldViolation> errors = new ArrayList<>(unknownFields(body, Set.of("name")));
        String name = null;
        Object raw = body.get("name");
        if (raw instanceof String text) {
            name = text.strip();
        } else if (raw != null) {
            errors.add(new FieldViolation("/name", "invalid_format", "Нужна строка."));
        }
        ApiKeyForm form = new ApiKeyForm(name);
        if (raw == null || raw instanceof String) {
            errors.addAll(form.violations());
        }
        if (!errors.isEmpty()) {
            throw SellerProfile.validation(errors);
        }
        return form;
    }

    /** Хеш из запроса шлюза: 64 шестнадцатеричных знака в нижнем регистре, байты возвращаются готовыми для поиска. */
    static byte[] readHash(HttpServletRequest request) throws IOException {
        Map<String, Object> body = object(request);
        List<FieldViolation> errors = new ArrayList<>(unknownFields(body, Set.of("keyHash")));
        Object raw = body.get("keyHash");
        if (raw == null) {
            errors.add(new FieldViolation("/keyHash", "required", "Укажите SHA-256 значения ключа."));
        } else if (!(raw instanceof String text) || !ApiKeyValues.isHashHex(text)) {
            errors.add(new FieldViolation("/keyHash", "invalid_format", "Нужны 64 шестнадцатеричных знака в нижнем регистре."));
        }
        if (!errors.isEmpty()) {
            throw SellerProfile.validation(errors);
        }
        return ApiKeyValues.decodeHash((String) raw);
    }

    private static Map<String, Object> object(HttpServletRequest request) throws IOException {
        String type = request.getContentType();
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw new ProblemException(ProblemType.UNSUPPORTED_MEDIA_TYPE, "Тип содержимого запроса не поддерживается, нужен application/json.");
        }
        try {
            return Json.parseObject(new String(RequestBodies.read(request, MAX_BODY), StandardCharsets.UTF_8));
        } catch (JsonException e) {
            throw new ProblemException(ProblemType.BAD_REQUEST, "Тело запроса не разбирается как объект JSON.");
        }
    }

    private static List<FieldViolation> unknownFields(Map<String, Object> body, Set<String> known) {
        List<FieldViolation> errors = new ArrayList<>();
        for (String name : body.keySet()) {
            if (!known.contains(name)) {
                errors.add(new FieldViolation("/" + name, "unknown_value", "Лишнее поле."));
            }
        }
        return errors;
    }
}
