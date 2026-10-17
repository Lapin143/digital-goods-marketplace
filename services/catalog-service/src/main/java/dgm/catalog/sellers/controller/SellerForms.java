package dgm.catalog.sellers.controller;

import dgm.catalog.sellers.domain.FieldViolation;
import dgm.catalog.sellers.domain.SellerForm;
import dgm.catalog.sellers.domain.SellerProfile;
import dgm.catalog.sellers.domain.SellerType;
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
 * Чтение тела {@code SellerApplicationForm}. Лишнее поле, поле не того типа и нарушение формата это 422 со списком {@code errors};
 * не JSON это 400, не тот {@code Content-Type} 415. Значение {@code null} равно отсутствию поля: {@code PUT} заменяет анкету целиком,
 * пропущенное поле очищается. Пробелы по краям строк убираются.
 */
final class SellerForms {

    /** Анкета занимает не больше 2000 знаков описания и несколько коротких полей; запас с избытком. */
    static final int MAX_BODY = 32 * 1024;
    private static final Set<String> FIELDS = Set.of("sellerType", "name", "payoutDetails", "assortmentDescription");
    private static final Set<String> PAYOUT_FIELDS = Set.of("recipientName", "bankName", "bik", "accountNumber");

    private SellerForms() {
    }

    static SellerForm read(HttpServletRequest request) throws IOException {
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

    static SellerForm parse(Map<String, Object> body) {
        List<FieldViolation> errors = new ArrayList<>();
        for (String name : body.keySet()) {
            if (!FIELDS.contains(name)) {
                errors.add(new FieldViolation("/" + name, "unknown_value", "Лишнее поле."));
            }
        }
        SellerType sellerType = null;
        Object rawType = body.get("sellerType");
        if (rawType != null) {
            if (rawType instanceof String text) {
                sellerType = SellerType.fromCode(text).orElse(null);
                if (sellerType == null) {
                    errors.add(new FieldViolation("/sellerType", "unknown_value", "Допустимы: " + SellerType.codes() + "."));
                }
            } else {
                errors.add(new FieldViolation("/sellerType", "invalid_format", "Нужна строка."));
            }
        }
        String name = text(body.get("name"), "/name", errors);
        String assortment = text(body.get("assortmentDescription"), "/assortmentDescription", errors);

        String recipient = null;
        String bank = null;
        String bik = null;
        String account = null;
        Object rawPayout = body.get("payoutDetails");
        if (rawPayout instanceof Map<?, ?> payout) {
            for (Object key : payout.keySet()) {
                if (!PAYOUT_FIELDS.contains(String.valueOf(key))) {
                    errors.add(new FieldViolation("/payoutDetails/" + key, "unknown_value", "Лишнее поле."));
                }
            }
            recipient = text(payout.get("recipientName"), "/payoutDetails/recipientName", errors);
            bank = text(payout.get("bankName"), "/payoutDetails/bankName", errors);
            bik = text(payout.get("bik"), "/payoutDetails/bik", errors);
            account = text(payout.get("accountNumber"), "/payoutDetails/accountNumber", errors);
        } else if (rawPayout != null) {
            errors.add(new FieldViolation("/payoutDetails", "invalid_format", "Нужен объект."));
        }

        SellerForm form = new SellerForm(sellerType, name, recipient, bank, bik, account, assortment);
        errors.addAll(form.violations());
        if (!errors.isEmpty()) {
            throw SellerProfile.validation(errors);
        }
        return form;
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
}
