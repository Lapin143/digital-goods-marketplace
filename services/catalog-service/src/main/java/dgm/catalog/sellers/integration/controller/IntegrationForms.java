package dgm.catalog.sellers.integration.controller;

import dgm.catalog.sellers.domain.FieldViolation;
import dgm.catalog.sellers.domain.SellerProfile;
import dgm.catalog.sellers.integration.domain.IntegrationForm;
import dgm.catalog.sellers.integration.domain.IntegrationStatus;
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
 * Чтение тела {@code SellerIntegrationInput}. Не JSON это 400, не тот {@code Content-Type} 415, лишнее поле, поле не того типа и статус
 * вне {@code active} и {@code disabled} это 422 со списком {@code errors} (схема закрыта, {@code additionalProperties: false}). Пробелы по
 * краям адреса убираются. Какой адрес допустим по существу, решает сервис.
 */
final class IntegrationForms {

    /** Адрес до 2048 знаков и статус: тело короткое, предел с запасом на экранирование. */
    static final int MAX_BODY = 8 * 1024;

    private IntegrationForms() {
    }

    static IntegrationForm read(HttpServletRequest request) throws IOException {
        Map<String, Object> body = object(request);
        List<FieldViolation> errors = new ArrayList<>();
        for (String name : body.keySet()) {
            if (!Set.of("endpointUrl", "status").contains(name)) {
                errors.add(new FieldViolation("/" + name, "unknown_value", "Лишнее поле."));
            }
        }
        String url = null;
        Object rawUrl = body.get("endpointUrl");
        if (rawUrl instanceof String text) {
            url = text.strip();
        } else if (rawUrl != null) {
            errors.add(new FieldViolation("/endpointUrl", "invalid_format", "Нужна строка."));
        }
        IntegrationStatus status = null;
        Object rawStatus = body.get("status");
        if (rawStatus instanceof String text) {
            status = IntegrationStatus.fromCode(text).orElse(null);
            if (status == null) {
                errors.add(new FieldViolation("/status", "unknown_value", "Допустимы active и disabled."));
            }
        } else if (rawStatus != null) {
            errors.add(new FieldViolation("/status", "invalid_format", "Нужна строка."));
        }
        IntegrationForm form = new IntegrationForm(url, status);
        if (rawUrl == null || rawUrl instanceof String) {
            errors.addAll(form.violations());
        }
        if (!errors.isEmpty()) {
            throw SellerProfile.validation(errors);
        }
        return form;
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
}
