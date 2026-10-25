package dgm.platform.auditadmin.controller;

import dgm.kit.json.Json;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.security.AuthenticatedUser;
import dgm.kit.security.CurrentUser;
import dgm.kit.time.Timestamps;
import dgm.kit.web.ClientAddress;
import dgm.kit.web.PageCursor;
import dgm.kit.web.QueryParams;
import dgm.platform.auditadmin.domain.AuditFilter;
import dgm.platform.auditadmin.domain.AuditPage;
import dgm.platform.auditadmin.domain.AuditRecordView;
import dgm.platform.auditadmin.domain.Viewer;
import dgm.platform.auditadmin.service.AuditViewService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code listAuditRecords} (OP-86): чтение журнала аудита. Права по матрице проверяет фильтр каркаса (роль admin, второй фактор, область
 * {@code staff.audit}; сеанс по SMS не допускается). Ни одной операции записи в журнал в API нет (INV-42).
 */
@RestController
public class AuditController {

    private static final MediaType JSON = new MediaType("application", "json", StandardCharsets.UTF_8);
    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final String ADMIN = "admin";

    private final AuditViewService service;

    public AuditController(AuditViewService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/staff/audit-records")
    public ResponseEntity<String> list(HttpServletRequest request) {
        AuthenticatedUser user = CurrentUser.of(request);
        QueryParams params = QueryParams.of(request, "actorId", "objectType", "objectId", "action", "occurredFrom", "occurredTo");
        Optional<UUID> actorId = params.matching("actorId", ID, 36).map(UUID::fromString);
        Optional<String> objectType = params.text("objectType", 1, 64);
        Optional<String> objectId = params.text("objectId", 1, 128);
        Optional<String> action = params.text("action", 1, 128);
        Optional<Instant> from = params.instant("occurredFrom");
        Optional<Instant> to = params.instant("occurredTo");
        if (from.isPresent() && to.isPresent() && !from.get().isBefore(to.get())) {
            params.error("occurredTo", "out_of_range", "Конец диапазона должен быть позже его начала.");
        }
        int limit = params.limit();
        Optional<AuditFilter.After> after = cursor(params);
        params.check();

        Viewer viewer = new Viewer(CurrentUser.id(user), role(user), ClientAddress.of(request));
        AuditPage page = service.list(viewer, new AuditFilter(actorId, objectType, objectId, action, from, to, limit, after));
        return ResponseEntity.ok().contentType(JSON).header(HttpHeaders.CACHE_CONTROL, "no-store").body(Json.write(toJson(page)));
    }

    /** Журнал читает только администратор; фильтр каркаса другого не пропускает, здесь страховка на случай ошибки в матрице. */
    private static String role(AuthenticatedUser user) {
        if (!user.roles().contains(ADMIN)) {
            throw new ProblemException(ProblemType.FORBIDDEN, "Журнал аудита доступен администратору.");
        }
        return ADMIN;
    }

    private static Optional<AuditFilter.After> cursor(QueryParams params) {
        Optional<Map<String, String>> values = params.cursorValues("t", "i");
        if (values.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new AuditFilter.After(Instant.parse(values.get().get("t")), UUID.fromString(values.get().get("i"))));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            params.error("cursor", "invalid_format", "Курсор не разобран: возьмите его из page.nextCursor предыдущего ответа.");
            return Optional.empty();
        }
    }

    /** Ответ по схеме AuditRecordPage. */
    static Map<String, Object> toJson(AuditPage page) {
        List<Object> items = new ArrayList<>();
        for (AuditRecordView record : page.items()) {
            items.add(toJson(record));
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("limit", page.limit());
        info.put("nextCursor", page.next().map(n -> PageCursor.encode(Map.of("t", n.occurredAt().toString(), "i", n.id().toString()))).orElse(null));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("page", info);
        return body;
    }

    /** Ответ по схеме AuditRecord. */
    static Map<String, Object> toJson(AuditRecordView r) {
        List<Object> changes = new ArrayList<>();
        for (AuditRecordView.Change c : r.changes()) {
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("field", c.field());
            change.put("personal", c.personal());
            change.put("before", c.before());
            change.put("after", c.after());
            changes.add(change);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("recordId", r.recordId().toString());
        row.put("occurredAt", Timestamps.format(r.occurredAt()));
        row.put("actorId", r.actorId().toString());
        row.put("actorRole", r.actorRole());
        row.put("action", r.action());
        row.put("objectType", r.objectType());
        row.put("objectId", r.objectId());
        row.put("changes", changes);
        row.put("actorIp", r.actorIp());
        row.put("actorIpHashed", r.actorIpHashed());
        row.put("correlationId", r.correlationId() == null ? null : r.correlationId().toString());
        return row;
    }
}
