package dgm.platform.support.controller;

import dgm.kit.json.Json;
import dgm.kit.json.JsonException;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.security.CurrentUser;
import dgm.kit.time.Timestamps;
import dgm.kit.web.ClientAddress;
import dgm.kit.web.RequestBodies;
import dgm.platform.support.domain.EmailChangeView;
import dgm.platform.support.domain.Operator;
import dgm.platform.support.service.EmailChangeService;
import dgm.platform.support.service.EmailChangeService.Step;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Смена e-mail по обращению (US-7.4, Р-169): у оператора {@code startEmailChange} и {@code getEmailChangeState} (OP-73, роль {@code support-operator},
 * область {@code staff.support}, 2FA), у покупателя {@code requestEmailChangeCode} и {@code verifyEmailChangeCode} (OP-74, область {@code support.write},
 * доступны и сессии по SMS: покупатель потерял доступ к e-mail). Права по матрице проверяет фильтр каркаса. Ключ идемпотентности ведёт охранник каркаса
 * без общей транзакции ({@code x-idempotency-external-steps}): операции отправляют SMS и письма и сами пишут этап короткими транзакциями; неуспешный
 * ответ ключ не занимает. Покупателем считается владелец токена: чужое обращение даёт тот же 404, что и отсутствующее. Ответы без кеширования, в них
 * нет кодов и адреса, только маска (FT-7.5, INV-23).
 */
@RestController
public class EmailChangeController {

    private static final MediaType JSON = new MediaType("application", "json", StandardCharsets.UTF_8);
    private static final String STAFF = "/api/v1/staff/support-tickets/{ticketId}/email-change";
    private static final String BUYER = "/api/v1/support-tickets/{ticketId}/email-change";
    private static final int MAX_BODY = 1024;
    private static final int EMAIL_MIN = 3;
    private static final int EMAIL_MAX = 254;
    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern CODE = Pattern.compile("^[0-9]{6}$");

    private final EmailChangeService service;

    public EmailChangeController(EmailChangeService service) {
        this.service = service;
    }

    @PostMapping(STAFF)
    public ResponseEntity<String> start(@PathVariable("ticketId") String ticketId, HttpServletRequest request) throws IOException {
        UUID id = ticketId(ticketId);
        Operator operator = new Operator(CurrentUser.id(CurrentUser.of(request)), ClientAddress.of(request));
        Body body = body(request, "newEmail");
        String newEmail = body.email("newEmail");
        body.check();
        EmailChangeView state = service.start(operator, id, newEmail);
        return ResponseEntity.status(HttpStatus.CREATED).contentType(JSON).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .location(URI.create("/api/v1/staff/support-tickets/" + id + "/email-change")).body(Json.write(toJson(state)));
    }

    @GetMapping(STAFF)
    public ResponseEntity<String> state(@PathVariable("ticketId") String ticketId, HttpServletRequest request) {
        UUID id = ticketId(ticketId);
        Operator operator = new Operator(CurrentUser.id(CurrentUser.of(request)), ClientAddress.of(request));
        return ok(service.state(operator, id));
    }

    @PostMapping(BUYER + "/codes")
    public ResponseEntity<String> requestCode(@PathVariable("ticketId") String ticketId, HttpServletRequest request) throws IOException {
        UUID id = ticketId(ticketId);
        UUID buyerId = CurrentUser.id(CurrentUser.of(request));
        Body body = body(request, "step");
        Step step = body.step("step");
        body.check();
        return ok(service.requestCode(buyerId, ip(request), id, step));
    }

    @PostMapping(BUYER + "/verifications")
    public ResponseEntity<String> verify(@PathVariable("ticketId") String ticketId, HttpServletRequest request) throws IOException {
        UUID id = ticketId(ticketId);
        UUID buyerId = CurrentUser.id(CurrentUser.of(request));
        Body body = body(request, "step", "code");
        Step step = body.step("step");
        String code = body.code("code");
        body.check();
        return ok(service.verify(buyerId, ip(request), id, step, code));
    }

    /** Ответ по схеме EmailChangeState: этап, результаты проверок и маска нового адреса. */
    static Map<String, Object> toJson(EmailChangeView view) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("status", view.status());
        row.put("phoneCheck", view.phoneCheck());
        row.put("newEmailCheck", view.newEmailCheck());
        row.put("newEmailMasked", view.newEmailMasked());
        row.put("startedAt", Timestamps.format(view.startedAt()));
        row.put("updatedAt", Timestamps.format(view.updatedAt()));
        return row;
    }

    private static ResponseEntity<String> ok(EmailChangeView view) {
        return ResponseEntity.ok().contentType(JSON).header(HttpHeaders.CACHE_CONTROL, "no-store").body(Json.write(toJson(view)));
    }

    /** Идентификатор неправильной формы даёт тот же 404, что и неизвестный. */
    private static UUID ticketId(String value) {
        if (!ID.matcher(value).matches()) {
            throw new ProblemException(ProblemType.NOT_FOUND, "Обращение не найдено.");
        }
        return UUID.fromString(value);
    }

    private static Optional<String> ip(HttpServletRequest request) {
        String address = ClientAddress.of(request);
        return address.isBlank() ? Optional.empty() : Optional.of(address);
    }

    /**
     * Читает закрытый JSON-объект: только перечисленные поля. Ошибки копятся и уходят одним ответом 422; значения полей в тексты ошибок не
     * попадают (в них код и адрес).
     */
    private static Body body(HttpServletRequest request, String... allowed) throws IOException {
        String type = request.getContentType();
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw new ProblemException(ProblemType.UNSUPPORTED_MEDIA_TYPE, "Тип содержимого запроса не поддерживается, нужен application/json.");
        }
        Map<String, Object> values;
        try {
            values = Json.parseObject(new String(RequestBodies.read(request, MAX_BODY), StandardCharsets.UTF_8));
        } catch (JsonException e) {
            throw new ProblemException(ProblemType.BAD_REQUEST, "Тело запроса не разбирается как объект JSON.");
        }
        return new Body(values, List.of(allowed));
    }

    private static final class Body {

        private final Map<String, Object> values;
        private final List<Map<String, Object>> errors = new ArrayList<>();

        private Body(Map<String, Object> values, List<String> allowed) {
            this.values = values;
            for (String name : values.keySet()) {
                if (!allowed.contains(name)) {
                    error("/" + name, "unknown_value", "Лишнее поле: допустимы только " + String.join(", ", allowed) + ".");
                }
            }
        }

        /** Адрес: пробелы по краям отбрасываются, регистр приводится к нижнему, как у учётных записей. */
        String email(String name) {
            String text = text(name);
            if (text == null) {
                return null;
            }
            String normalized = text.strip().toLowerCase(Locale.ROOT);
            if (normalized.length() < EMAIL_MIN || normalized.length() > EMAIL_MAX || !EMAIL.matcher(normalized).matches()) {
                error("/" + name, "invalid_format", "Нужен адрес e-mail не длиннее " + EMAIL_MAX + " символов.");
                return null;
            }
            return normalized;
        }

        Step step(String name) {
            String text = text(name);
            if (text == null) {
                return null;
            }
            Optional<Step> step = Step.byCode(text);
            if (step.isEmpty()) {
                error("/" + name, "unknown_value", "Допустимы только значения: phone, new_email.");
                return null;
            }
            return step.get();
        }

        String code(String name) {
            String text = text(name);
            if (text == null) {
                return null;
            }
            if (!CODE.matcher(text).matches()) {
                error("/" + name, "invalid_format", "Код из шести цифр.");
                return null;
            }
            return text;
        }

        private String text(String name) {
            Object value = values.get(name);
            if (value == null) {
                error("/" + name, "required", "Поле обязательно.");
                return null;
            }
            if (!(value instanceof String text)) {
                error("/" + name, "invalid_format", "Значение должно быть строкой.");
                return null;
            }
            if (text.length() > EMAIL_MAX + 64) {
                error("/" + name, "too_long", "Значение слишком длинное.");
                return null;
            }
            return text;
        }

        void check() {
            if (!errors.isEmpty()) {
                throw new ProblemException(ProblemType.VALIDATION_FAILED, "Данные не прошли проверку, подробности в поле errors.",
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
