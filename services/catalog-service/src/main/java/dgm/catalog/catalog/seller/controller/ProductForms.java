package dgm.catalog.catalog.seller.controller;

import dgm.catalog.catalog.domain.FieldViolation;
import dgm.catalog.catalog.domain.IssuanceMethod;
import dgm.catalog.catalog.domain.ProductForm;
import dgm.catalog.catalog.domain.ProductType;
import dgm.kit.json.Json;
import dgm.kit.json.JsonException;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.web.RequestBodies;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Чтение тела {@code ProductInput}. Лишнее поле, поле не того типа и нарушение правила это 422 со списком {@code errors}; не JSON это
 * 400, не тот {@code Content-Type} 415. Все поля обязательны: {@code PUT} заменяет товар целиком, пропущенное поле не сохраняется
 * прежним. Пробелы по краям текстовых полей убираются.
 */
final class ProductForms {

    /** Описание до 5000 знаков и список регионов до 250 кодов; в 4 байтах на знак это меньше 32 КиБ с запасом. */
    static final int MAX_BODY = 64 * 1024;
    private static final Set<String> FIELDS = Set.of("title", "description", "productType", "platform", "activationRegions", "price",
            "issuanceMethod");
    private static final Set<String> PRICE_FIELDS = Set.of("amount", "currency");

    private ProductForms() {
    }

    static ProductForm read(HttpServletRequest request) throws IOException {
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
        return parse(body);
    }

    static ProductForm parse(Map<String, Object> body) {
        List<FieldViolation> errors = new ArrayList<>();
        for (String name : body.keySet()) {
            if (!FIELDS.contains(name)) {
                errors.add(new FieldViolation("/" + name, "unknown_value", "Лишнее поле."));
            }
        }
        String title = text(body.get("title"), "/title", errors);
        String description = text(body.get("description"), "/description", errors);
        String platform = text(body.get("platform"), "/platform", errors);

        ProductType productType = null;
        Object rawType = body.get("productType");
        if (rawType instanceof String code) {
            productType = ProductType.find(code).orElse(null);
            if (productType == null) {
                errors.add(new FieldViolation("/productType", "unknown_value", "Допустимы: " + String.join(", ", ProductType.codes()) + "."));
            }
        } else if (rawType != null) {
            errors.add(new FieldViolation("/productType", "invalid_format", "Нужна строка."));
        }

        IssuanceMethod issuance = null;
        Object rawIssuance = body.get("issuanceMethod");
        if (rawIssuance instanceof String code) {
            issuance = IssuanceMethod.find(code).orElse(null);
            if (issuance == null) {
                errors.add(new FieldViolation("/issuanceMethod", "unknown_value", "Допустимы: key_pool, seller_api."));
            }
        } else if (rawIssuance != null) {
            errors.add(new FieldViolation("/issuanceMethod", "invalid_format", "Нужна строка."));
        }

        List<String> regions = regions(body.get("activationRegions"), errors);
        Long price = price(body.get("price"), errors);

        ProductForm form = new ProductForm(title, description, productType, platform, regions, price, issuance);
        // Поля, которые уже названы ошибкой разбора (чужой тип значения), второй раз нарушением правила не называются
        List<String> reported = errors.stream().map(FieldViolation::pointer).toList();
        for (FieldViolation violation : form.violations()) {
            boolean covered = reported.stream().anyMatch(p -> violation.pointer().equals(p) || violation.pointer().startsWith(p + "/"));
            if (!covered) {
                errors.add(violation);
            }
        }
        if (!errors.isEmpty()) {
            throw validation(errors);
        }
        return form;
    }

    static ProblemException validation(List<FieldViolation> violations) {
        List<Map<String, Object>> errors = new ArrayList<>();
        for (FieldViolation v : violations) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("pointer", v.pointer());
            error.put("code", v.code());
            error.put("detail", v.detail());
            errors.add(error);
        }
        return new ProblemException(ProblemType.VALIDATION_FAILED, "Данные не прошли проверку, подробности в поле errors.",
                Map.of("errors", List.copyOf(errors)), Map.of());
    }

    private static String text(Object value, String pointer, List<FieldViolation> errors) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text.strip();
        }
        errors.add(new FieldViolation(pointer, "invalid_format", "Нужна строка."));
        return null;
    }

    private static List<String> regions(Object value, List<FieldViolation> errors) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> list)) {
            errors.add(new FieldViolation("/activationRegions", "invalid_format", "Нужен массив кодов стран."));
            return null;
        }
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) instanceof String code) {
                codes.add(code);
            } else {
                errors.add(new FieldViolation("/activationRegions/" + i, "invalid_format", "Нужна строка."));
                codes.add("");
            }
        }
        return codes;
    }

    private static Long price(Object value, List<FieldViolation> errors) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> money)) {
            errors.add(new FieldViolation("/price", "invalid_format", "Нужен объект {amount, currency}."));
            return null;
        }
        for (Object key : money.keySet()) {
            if (!PRICE_FIELDS.contains(String.valueOf(key))) {
                errors.add(new FieldViolation("/price/" + key, "unknown_value", "Лишнее поле."));
            }
        }
        Object currency = money.get("currency");
        if (currency == null) {
            errors.add(new FieldViolation("/price/currency", "required", "Поле обязательно."));
        } else if (!ProductForm.CURRENCY.equals(currency)) {
            errors.add(new FieldViolation("/price/currency", "unknown_value", "В этом релизе только " + ProductForm.CURRENCY + "."));
        }
        Object amount = money.get("amount");
        if (amount instanceof Integer || amount instanceof Long) {
            return ((Number) amount).longValue();
        }
        if (amount instanceof BigInteger) {
            errors.add(new FieldViolation("/price/amount", "out_of_range", "Цена в копейках: от 1 до " + ProductForm.MAX_PRICE + "."));
        } else if (amount != null) {
            errors.add(new FieldViolation("/price/amount", "invalid_format", "Нужно целое число копеек."));
        }
        return null;
    }
}
