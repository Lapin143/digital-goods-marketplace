package dgm.platform.support.controller;

import dgm.kit.json.Json;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.security.CurrentUser;
import dgm.kit.time.Timestamps;
import dgm.kit.web.ClientAddress;
import dgm.kit.web.PageCursor;
import dgm.kit.web.QueryParams;
import dgm.platform.support.domain.Operator;
import dgm.platform.support.domain.QueueFilter;
import dgm.platform.support.domain.ResendAccepted;
import dgm.platform.support.domain.StaffTicket;
import dgm.platform.support.domain.StaffTicketPage;
import dgm.platform.support.domain.SupportTicket;
import dgm.platform.support.service.QueueService;
import dgm.platform.support.service.ResendService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
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
 * Очередь оператора поддержки: {@code listSupportTicketQueue}, {@code getStaffSupportTicket}, {@code takeSupportTicket},
 * {@code releaseSupportTicket} (OP-71, US-7.2) и {@code resendKeyForTicket} (OP-72, US-7.3). Роль {@code support-operator} и область {@code staff.support} проверяет фильтр каркаса по
 * матрице прав; покупатель и сессия по SMS получают 403 там же (U4). Ключ идемпотентности у взятия и возврата ведёт охранник каркаса в
 * транзакции модуля {@code support} ({@code x-module}).
 *
 * <p>Фильтры очереди. {@code status} (через запятую из {@code created}, {@code in_progress}, {@code resolved}) по умолчанию {@code created}, если не
 * задан номер заказа; с {@code orderNumber} и без {@code status} показываются обращения заказа с любым статусом (контракт, US-7.2 сценарий 6).
 * {@code operatorId} сужает выборку до обращений этого оператора: свои обращения оператор находит запросом {@code status=in_progress&operatorId=…}.
 */
@RestController
public class StaffSupportController {

    private static final MediaType JSON = new MediaType("application", "json", StandardCharsets.UTF_8);
    private static final String BASE = "/api/v1/staff/support-tickets";
    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final QueueService service;
    private final ResendService resend;

    public StaffSupportController(QueueService service, ResendService resend) {
        this.service = service;
        this.resend = resend;
    }

    @GetMapping(BASE)
    public ResponseEntity<String> list(HttpServletRequest request) {
        QueryParams params = QueryParams.of(request, "status", "operatorId", "orderNumber");
        Optional<List<String>> statuses = params.list("status", SupportTicket.STATUSES);
        Optional<UUID> operatorId = params.matching("operatorId", ID, 36).map(UUID::fromString);
        OptionalLong orderNumber = params.integer("orderNumber", 1, Long.MAX_VALUE);
        int limit = params.limit();
        Optional<QueueFilter.After> after = cursor(params);
        params.check();
        List<String> effective = statuses.orElse(orderNumber.isPresent() ? List.of() : List.of(SupportTicket.CREATED));
        StaffTicketPage page = service.list(new QueueFilter(effective, operatorId, orderNumber, limit, after));
        return ok(Json.write(toJson(page)));
    }

    @GetMapping(BASE + "/{ticketId}")
    public ResponseEntity<String> get(@PathVariable("ticketId") String ticketId) {
        return ok(Json.write(toJson(service.get(ticketId(ticketId)))));
    }

    @PostMapping(BASE + "/{ticketId}/take")
    public ResponseEntity<String> take(@PathVariable("ticketId") String ticketId, HttpServletRequest request) {
        UUID id = ticketId(ticketId);
        return ok(Json.write(toJson(service.take(operator(request), id))));
    }

    @PostMapping(BASE + "/{ticketId}/release")
    public ResponseEntity<String> release(@PathVariable("ticketId") String ticketId, HttpServletRequest request) {
        UUID id = ticketId(ticketId);
        return ok(Json.write(toJson(service.release(operator(request), id))));
    }

    /**
     * {@code resendKeyForTicket} (OP-72, US-7.3): ответ 202 без ключа и адреса, статус выдачи оператор видит в карточке обращения ({@code Location}).
     * Ключ идемпотентности ведёт охранник каркаса без общей транзакции, потому что действие вызывает {@code order-service} ({@code x-idempotency-external-steps}).
     */
    @PostMapping(BASE + "/{ticketId}/resend")
    public ResponseEntity<String> resend(@PathVariable("ticketId") String ticketId, HttpServletRequest request) {
        UUID id = ticketId(ticketId);
        ResendAccepted accepted = resend.resend(operator(request), id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticketId", accepted.ticketId().toString());
        body.put("orderId", accepted.orderId().toString());
        body.put("status", ResendAccepted.STATUS);
        return ResponseEntity.accepted().contentType(JSON).header(HttpHeaders.CACHE_CONTROL, "no-store").header(HttpHeaders.LOCATION, BASE + "/" + accepted.ticketId())
                .body(Json.write(body));
    }

    private static Operator operator(HttpServletRequest request) {
        return new Operator(CurrentUser.id(CurrentUser.of(request)), ClientAddress.of(request));
    }

    /** Идентификатор неправильной формы даёт тот же 404, что и неизвестный. */
    private static UUID ticketId(String value) {
        if (!ID.matcher(value).matches()) {
            throw new ProblemException(ProblemType.NOT_FOUND, "Обращение не найдено.");
        }
        return UUID.fromString(value);
    }

    private static ResponseEntity<String> ok(String body) {
        return ResponseEntity.ok().contentType(JSON).header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    private static Optional<QueueFilter.After> cursor(QueryParams params) {
        Optional<Map<String, String>> values = params.cursorValues("t", "i");
        if (values.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new QueueFilter.After(Instant.parse(values.get().get("t")), UUID.fromString(values.get().get("i"))));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            params.error("cursor", "invalid_format", "Курсор не разобран: возьмите его из page.nextCursor предыдущего ответа.");
            return Optional.empty();
        }
    }

    /** Ответ по схеме StaffSupportTicketPage. */
    static Map<String, Object> toJson(StaffTicketPage page) {
        List<Object> items = new ArrayList<>();
        for (StaffTicket ticket : page.items()) {
            items.add(toJson(ticket));
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("limit", page.limit());
        info.put("nextCursor", page.next().map(n -> PageCursor.encode(Map.of("t", n.createdAt().toString(), "i", n.id().toString()))).orElse(null));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("page", info);
        return body;
    }

    /**
     * Ответ по схеме StaffSupportTicket. Состояние смены e-mail ({@code emailChange}) заполнено, когда оператор начал смену по обращению
     * (шаг 14, Р-169), иначе {@code null}. Ни значений ключей, ни кодов, ни полного адреса в ответе нет, у нового адреса только маска (INV-23).
     */
    static Map<String, Object> toJson(StaffTicket t) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ticketId", t.id().toString());
        row.put("orderId", t.orderId().toString());
        row.put("orderNumber", t.orderNumber());
        row.put("buyerId", t.buyerId().toString());
        row.put("source", t.source());
        row.put("reason", t.reason());
        row.put("status", t.status());
        row.put("operatorId", t.operatorId().map(UUID::toString).orElse(null));
        row.put("orderStatus", t.orderStatus());
        row.put("issuedAt", t.issuedAt().map(Timestamps::format).orElse(null));
        row.put("deliveryStatus", t.deliveryStatus().orElse(null));
        row.put("createdAt", Timestamps.format(t.createdAt()));
        row.put("takenAt", t.takenAt().map(Timestamps::format).orElse(null));
        row.put("resolvedAt", t.resolvedAt().map(Timestamps::format).orElse(null));
        row.put("emailChange", t.emailChange().map(EmailChangeController::toJson).orElse(null));
        return row;
    }
}
