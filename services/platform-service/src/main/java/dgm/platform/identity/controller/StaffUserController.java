package dgm.platform.identity.controller;

import dgm.kit.json.Json;
import dgm.kit.json.JsonException;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.security.AuthenticatedUser;
import dgm.kit.security.CurrentUser;
import dgm.kit.time.Timestamps;
import dgm.kit.web.PageCursor;
import dgm.kit.web.QueryParams;
import dgm.kit.web.RequestBodies;
import dgm.platform.identity.domain.Actor;
import dgm.platform.identity.domain.Roles;
import dgm.platform.identity.domain.UserAccount;
import dgm.platform.identity.domain.UserFilter;
import dgm.platform.identity.domain.UserPage;
import dgm.platform.identity.service.StaffUserService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Операции администратора над учётными записями: {@code listStaffUsers} (OP-80), {@code changeUserRole} (OP-80), {@code deactivateUser}
 * (OP-81), {@code anonymizeUser} (OP-82). Права по матрице проверяет фильтр каркаса (роль admin, второй фактор, область staff.admin), ключ
 * идемпотентности ведёт охранник каркаса. Номер телефона наружу не отдаётся.
 */
@RestController
public class StaffUserController {

    private static final MediaType JSON = new MediaType("application", "json", StandardCharsets.UTF_8);
    private static final Set<String> STATUSES = Set.of(UserAccount.ACTIVE, UserAccount.DEACTIVATED);
    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final int MAX_BODY = 4096;
    private static final int IP_MAX = 128;

    private final StaffUserService service;

    public StaffUserController(StaffUserService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/staff/users")
    public ResponseEntity<String> list(HttpServletRequest request) {
        QueryParams params = QueryParams.of(request, "role", "status", "email");
        Optional<String> role = params.text("role", 1, 32);
        Optional<String> status = params.text("status", 1, 16);
        Optional<String> email = params.text("email", 3, 254).map(value -> value.strip().toLowerCase(Locale.ROOT));
        role.filter(value -> !Roles.known(value)).ifPresent(value -> params.error("role", "unknown_value", "Неизвестная роль."));
        status.filter(value -> !STATUSES.contains(value)).ifPresent(value -> params.error("status", "unknown_value", "Неизвестный статус."));
        int limit = params.limit();
        Optional<UserFilter.After> after = cursor(params);
        params.check();

        UserPage page = service.list(new UserFilter(role, status, email, limit, after));
        return ok(Json.write(toJson(page)));
    }

    @PostMapping("/api/v1/staff/users/{userId}/change-role")
    public ResponseEntity<String> changeRole(@PathVariable("userId") String userId, HttpServletRequest request) throws IOException {
        Actor actor = actor(request);
        String role = requestedRole(request);
        return ok(Json.write(toJson(service.changeRole(actor, target(userId), role))));
    }

    @PostMapping("/api/v1/staff/users/{userId}/deactivate")
    public ResponseEntity<String> deactivate(@PathVariable("userId") String userId, HttpServletRequest request) {
        Actor actor = actor(request);
        return ok(Json.write(toJson(service.deactivate(actor, target(userId)))));
    }

    @PostMapping("/api/v1/staff/users/{userId}/anonymize")
    public ResponseEntity<String> anonymize(@PathVariable("userId") String userId, HttpServletRequest request) {
        Actor actor = actor(request);
        return ok(Json.write(toJson(service.anonymize(actor, target(userId)))));
    }

    private static ResponseEntity<String> ok(String body) {
        return ResponseEntity.ok().contentType(JSON).header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    /** Идентификатор не по форме UUID не может принадлежать записи: ответ 404, как для любого отсутствующего объекта. */
    private static UUID target(String userId) {
        if (!ID.matcher(userId).matches()) {
            throw new ProblemException(ProblemType.NOT_FOUND, "Учётная запись не найдена.");
        }
        return UUID.fromString(userId);
    }

    private static Actor actor(HttpServletRequest request) {
        AuthenticatedUser user = CurrentUser.of(request);
        return new Actor(CurrentUser.id(user), Roles.best(user.roles()), clientIp(request));
    }

    /** Адрес клиента: шлюз ставит {@code X-Forwarded-For} по самому соединению (ADR-021, пункт 9), присланные клиентом заголовки он удаляет. */
    static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = forwarded == null || forwarded.isBlank() ? request.getRemoteAddr() : forwarded.split(",")[0].strip();
        return ip.length() <= IP_MAX ? ip : ip.substring(0, IP_MAX);
    }

    private static Optional<UserFilter.After> cursor(QueryParams params) {
        Optional<Map<String, String>> values = params.cursorValues("t", "i");
        if (values.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new UserFilter.After(Instant.parse(values.get().get("t")), UUID.fromString(values.get().get("i"))));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            params.error("cursor", "invalid_format", "Курсор не разобран: возьмите его из page.nextCursor предыдущего ответа.");
            return Optional.empty();
        }
    }

    /** Тело {@code ChangeRoleRequest}: ровно одно поле {@code role} из перечня ролей. */
    private static String requestedRole(HttpServletRequest request) throws IOException {
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
            if (!name.equals("role")) {
                errors.add(error("/" + name, "unknown_value", "Лишнее поле: допустимо только role."));
            }
        }
        Object role = body.get("role");
        if (role == null) {
            errors.add(error("/role", "required", "Поле обязательно."));
        } else if (!(role instanceof String text) || !Roles.known(text)) {
            errors.add(error("/role", "unknown_value", "Роль должна быть одной из: " + String.join(", ", Roles.ALL) + "."));
        }
        if (!errors.isEmpty()) {
            throw new ProblemException(ProblemType.VALIDATION_FAILED, "Данные не прошли проверку, подробности в поле errors.",
                    Map.of("errors", List.copyOf(errors)), Map.of());
        }
        return (String) role;
    }

    private static Map<String, Object> error(String pointer, String code, String detail) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("pointer", pointer);
        error.put("code", code);
        error.put("detail", detail);
        return error;
    }

    /** Ответ по схеме StaffUserPage. */
    static Map<String, Object> toJson(UserPage page) {
        List<Object> items = new ArrayList<>();
        for (UserAccount account : page.items()) {
            items.add(toJson(account));
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("limit", page.limit());
        info.put("nextCursor", page.next().map(n -> PageCursor.encode(Map.of("t", n.registeredAt().toString(), "i", n.id().toString()))).orElse(null));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("page", info);
        return body;
    }

    /** Ответ по схеме StaffUser; у анонимизированной записи имени нет. */
    static Map<String, Object> toJson(UserAccount a) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("userId", a.id().toString());
        row.put("email", a.email());
        row.put("name", a.anonymized() ? null : a.name());
        row.put("role", a.role());
        row.put("status", a.status());
        row.put("phoneConfirmed", a.phoneConfirmed());
        row.put("anonymized", a.anonymized());
        row.put("createdAt", Timestamps.format(a.registeredAt()));
        row.put("deactivatedAt", a.deactivatedAt().map(Timestamps::format).orElse(null));
        return row;
    }
}
