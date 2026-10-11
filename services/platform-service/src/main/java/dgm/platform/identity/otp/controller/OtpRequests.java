package dgm.platform.identity.otp.controller;

import dgm.kit.json.Json;
import dgm.kit.json.JsonException;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.time.Timestamps;
import dgm.kit.web.RequestBodies;
import dgm.platform.identity.otp.domain.PhoneNumber;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Общее для контроллеров подтверждения телефона и входа по SMS: чтение закрытого JSON-объекта, проверка полей по схемам OpenAPI
 * ({@code additionalProperties: false}), адрес клиента и ответы без кеширования. Значения полей в тексты ошибок не попадают: в них бывают
 * номер и код.
 */
final class OtpRequests {

    static final MediaType JSON = new MediaType("application", "json", StandardCharsets.UTF_8);
    static final Pattern CODE = Pattern.compile("^[0-9]{6}$");
    private static final Pattern PHONE = Pattern.compile("^\\+7[0-9]{10}$");
    private static final Pattern IP = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");
    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final int IP_FIELD_MAX = 45;

    private OtpRequests() {
    }

    /**
     * Читает тело как объект JSON с перечнем допустимых полей.
     *
     * @throws ProblemException 415, если тип содержимого не JSON; 400, если тело не разбирается как объект; 413, если оно больше предела
     */
    static Body body(HttpServletRequest request, int maxBytes, String... allowed) throws IOException {
        String type = request.getContentType();
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw new ProblemException(ProblemType.UNSUPPORTED_MEDIA_TYPE, "Тип содержимого запроса не поддерживается, нужен application/json.");
        }
        Map<String, Object> values;
        try {
            values = Json.parseObject(new String(RequestBodies.read(request, maxBytes), StandardCharsets.UTF_8));
        } catch (JsonException e) {
            throw new ProblemException(ProblemType.BAD_REQUEST, "Тело запроса не разбирается как объект JSON.");
        }
        return new Body(values, Set.of(allowed));
    }

    /** Адрес клиента: шлюз ставит {@code X-Forwarded-For} по самому соединению (ADR-021, пункт 9). Непохожее на адрес значение не принимается. */
    static Optional<String> clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = forwarded == null || forwarded.isBlank() ? request.getRemoteAddr() : forwarded.split(",")[0].strip();
        return ip != null && IP.matcher(ip).matches() ? Optional.of(ip) : Optional.empty();
    }

    /** Идентификатор в пути не по форме UUID не может принадлежать объекту: 404, как для любого отсутствующего. */
    static UUID idOrNotFound(String text, String what) {
        if (!ID.matcher(text).matches()) {
            throw new ProblemException(ProblemType.NOT_FOUND, what + " не найден.");
        }
        return UUID.fromString(text);
    }

    static ResponseEntity.BodyBuilder noStore(ResponseEntity.BodyBuilder builder) {
        return builder.contentType(JSON).header(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    /** Тело запроса и накопленные ошибки проверки: все ошибки уходят одним ответом 422. */
    static final class Body {

        private final Map<String, Object> values;
        private final List<Map<String, Object>> errors = new ArrayList<>();

        private Body(Map<String, Object> values, Set<String> allowed) {
            this.values = values;
            for (String name : values.keySet()) {
                if (!allowed.contains(name)) {
                    error("/" + name, "unknown_value", "Лишнее поле: допустимы только " + String.join(", ", new TreeSet<>(allowed)) + ".");
                }
            }
        }

        /** Номер {@code +7} и десять цифр; пусто и ошибка, если поля нет или оно не подходит. */
        Optional<PhoneNumber> phone(String name) {
            return text(name, true, PHONE, Integer.MAX_VALUE, "Номер в формате +7 и десяти цифр.").flatMap(PhoneNumber::parse);
        }

        /** Код из шести цифр. */
        Optional<String> code(String name) {
            return text(name, true, CODE, Integer.MAX_VALUE, "Код из шести цифр.");
        }

        /** Поле с единственным допустимым значением. */
        void purpose(String name, String expected) {
            oneOf(name, Set.of(expected));
        }

        /** Строка из перечня. */
        Optional<String> oneOf(String name, Set<String> allowed) {
            return text(name, true, null, 64, null).filter(value -> {
                if (!allowed.contains(value)) {
                    error("/" + name, "unknown_value", "Допустимы только значения: " + String.join(", ", new TreeSet<>(allowed)) + ".");
                    return false;
                }
                return true;
            });
        }

        /** Строка длиной от {@code min} до {@code max} символов. */
        Optional<String> string(String name, int min, int max) {
            return text(name, true, null, max, null).filter(value -> {
                if (value.length() < min) {
                    error("/" + name, "invalid_format", "Значение короче " + min + " символов.");
                    return false;
                }
                return true;
            });
        }

        /** Момент времени в формате RFC 3339. */
        Optional<Instant> timestamp(String name) {
            return text(name, true, null, 40, null).flatMap(value -> {
                Optional<Instant> parsed = Timestamps.parse(value);
                if (parsed.isEmpty()) {
                    error("/" + name, "invalid_format", "Время в формате RFC 3339.");
                }
                return parsed;
            });
        }

        /** Необязательный адрес клиента (не длиннее 45 символов). */
        Optional<String> optionalIp(String name) {
            return text(name, false, IP, IP_FIELD_MAX, "Адрес IPv4 или IPv6.");
        }

        Optional<String> text(String name, boolean required, Pattern pattern, int maxLength, String formatDetail) {
            Object value = values.get(name);
            if (value == null) {
                if (required) {
                    error("/" + name, "required", "Поле обязательно.");
                }
                return Optional.empty();
            }
            if (!(value instanceof String text)) {
                error("/" + name, "invalid_format", "Значение должно быть строкой.");
                return Optional.empty();
            }
            if (text.length() > maxLength) {
                error("/" + name, "too_long", "Значение длиннее " + maxLength + " символов.");
                return Optional.empty();
            }
            if (pattern != null && !pattern.matcher(text).matches()) {
                error("/" + name, "invalid_format", formatDetail);
                return Optional.empty();
            }
            return Optional.of(text);
        }

        /** @throws ProblemException 422 {@code validation-failed} со списком ошибок, если хотя бы одно поле не прошло проверку */
        void check() {
            check(ProblemType.VALIDATION_FAILED);
        }

        /**
         * То же с заданным типом отказа: контракт вебхука отвечает на неверные поля кодом 400, а не 422.
         *
         * @throws ProblemException отказ типа {@code type} со списком ошибок, если хотя бы одно поле не прошло проверку
         */
        void check(ProblemType type) {
            if (!errors.isEmpty()) {
                throw new ProblemException(type, "Данные не прошли проверку, подробности в поле errors.",
                        Map.of("errors", List.copyOf(errors)), Map.of());
            }
        }

        private void error(String pointer, String code, String detail) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("pointer", pointer);
            error.put("code", code);
            error.put("detail", detail);
            errors.add(error);
        }
    }
}
