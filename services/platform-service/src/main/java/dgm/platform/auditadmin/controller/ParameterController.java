package dgm.platform.auditadmin.controller;

import dgm.kit.json.Json;
import dgm.kit.json.JsonException;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.security.AuthenticatedUser;
import dgm.kit.security.CurrentUser;
import dgm.kit.time.Timestamps;
import dgm.kit.web.ClientAddress;
import dgm.kit.web.IfMatch;
import dgm.kit.web.QueryParams;
import dgm.kit.web.RequestBodies;
import dgm.platform.auditadmin.domain.Administrator;
import dgm.platform.auditadmin.domain.PlatformParameter;
import dgm.platform.auditadmin.service.ParameterService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Параметры платформы администратора: {@code listPlatformParameters} и {@code setPlatformParameter} (OP-83). Права по матрице проверяет
 * фильтр каркаса (роль admin, второй фактор, область {@code staff.admin}; сеанс по SMS не допускается). Правка идёт с {@code If-Match}:
 * версия параметра это его ETag.
 */
@RestController
public class ParameterController {

    private static final MediaType JSON = new MediaType("application", "json", StandardCharsets.UTF_8);
    private static final String BASE = "/api/v1/staff/parameters";
    private static final int MAX_BODY = 1024;
    private static final String ADMIN = "admin";

    private final ParameterService service;

    public ParameterController(ParameterService service) {
        this.service = service;
    }

    @GetMapping(BASE)
    public ResponseEntity<String> list(HttpServletRequest request) {
        QueryParams.of(request).check();
        List<Object> items = new ArrayList<>();
        for (PlatformParameter parameter : service.list()) {
            items.add(toJson(parameter));
        }
        return ResponseEntity.ok().contentType(JSON).header(HttpHeaders.CACHE_CONTROL, "no-store").body(Json.write(Map.of("items", items)));
    }

    @PutMapping(BASE + "/{key}")
    public ResponseEntity<String> set(@PathVariable("key") String key, HttpServletRequest request) throws IOException {
        Administrator admin = administrator(request);
        long expectedVersion = IfMatch.version(request);
        // Параметр читается до разбора тела: у неизвестного ключа ответ 404, а ошибка значения называет границы именно этого параметра
        PlatformParameter known = service.get(key);
        long value = requestedValue(request, known);
        PlatformParameter changed = service.set(admin, key, expectedVersion, value);
        return ResponseEntity.ok().contentType(JSON).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.ETAG, IfMatch.etag(changed.version())).body(Json.write(toJson(changed)));
    }

    private static Administrator administrator(HttpServletRequest request) {
        AuthenticatedUser user = CurrentUser.of(request);
        if (!user.roles().contains(ADMIN)) {
            throw new ProblemException(ProblemType.FORBIDDEN, "Параметры платформы меняет администратор.");
        }
        return new Administrator(CurrentUser.id(user), ClientAddress.of(request));
    }

    /** Тело {@code SetParameterRequest}: ровно одно поле {@code value}, целое число; вне границ параметра значение отклонит сервис. */
    private static long requestedValue(HttpServletRequest request, PlatformParameter parameter) throws IOException {
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
        List<Map<String, Object>> errors = new ArrayList<>();
        for (String name : body.keySet()) {
            if (!name.equals("value")) {
                errors.add(error("/" + name, "unknown_value", "Лишнее поле: допустимо только value."));
            }
        }
        String bounds = "от " + parameter.minValue() + " до " + parameter.maxValue();
        Object raw = body.get("value");
        Long value = null;
        if (raw == null) {
            errors.add(error("/value", "required", "Поле обязательно: целое число " + bounds + "."));
        } else if (raw instanceof Integer || raw instanceof Long) {
            value = ((Number) raw).longValue();
        } else if (raw instanceof BigInteger) {
            errors.add(error("/value", "out_of_range", "Допустимо " + bounds + "."));
        } else {
            errors.add(error("/value", "invalid_format", "Нужно целое число " + bounds + "."));
        }
        if (!errors.isEmpty()) {
            throw new ProblemException(ProblemType.VALIDATION_FAILED, "Данные не прошли проверку, подробности в поле errors.",
                    Map.of("errors", List.copyOf(errors)), Map.of());
        }
        return value;
    }

    private static Map<String, Object> error(String pointer, String code, String detail) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("pointer", pointer);
        error.put("code", code);
        error.put("detail", detail);
        return error;
    }

    /** Ответ по схеме PlatformParameter. */
    static Map<String, Object> toJson(PlatformParameter p) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", p.key());
        row.put("description", p.description());
        row.put("unit", p.unit());
        row.put("value", p.value());
        row.put("defaultValue", p.defaultValue());
        row.put("minValue", p.minValue());
        row.put("maxValue", p.maxValue());
        row.put("version", p.version());
        row.put("updatedAt", Timestamps.format(p.updatedAt()));
        row.put("updatedBy", p.updatedBy() == null ? null : p.updatedBy().toString());
        return row;
    }
}
