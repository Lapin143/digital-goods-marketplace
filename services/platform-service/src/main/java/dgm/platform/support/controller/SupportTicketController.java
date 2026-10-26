package dgm.platform.support.controller;

import dgm.kit.json.Json;
import dgm.kit.json.JsonException;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.security.CurrentUser;
import dgm.kit.time.Timestamps;
import dgm.kit.web.PageCursor;
import dgm.kit.web.QueryParams;
import dgm.kit.web.RequestBodies;
import dgm.platform.support.domain.SupportTicket;
import dgm.platform.support.domain.TicketFilter;
import dgm.platform.support.domain.TicketPage;
import dgm.platform.support.service.TicketService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
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
 * Обращения покупателя: {@code createSupportTicket}, {@code listSupportTickets}, {@code getSupportTicket} (OP-70, US-7.1). Права по матрице
 * проверяет фильтр каркаса (роль, область {@code support.write}); эти три операции доступны и сессии по SMS (US-1.7), поэтому покупатель,
 * потерявший доступ к e-mail, создаёт обращение из той же карточки заказа. Ключ идемпотентности и повтор с тем же ответом ведёт охранник
 * каркаса в транзакции модуля {@code support} ({@code x-module}). Покупателем считается владелец токена: чужой заказ и чужое обращение
 * дают такой же 404, как несуществующие.
 */
@RestController
public class SupportTicketController {

    private static final MediaType JSON = new MediaType("application", "json", StandardCharsets.UTF_8);
    private static final String BASE = "/api/v1/support-tickets";
    private static final int MAX_BODY = 1024;
    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final TicketService service;

    public SupportTicketController(TicketService service) {
        this.service = service;
    }

    @PostMapping(BASE)
    public ResponseEntity<String> create(HttpServletRequest request) throws IOException {
        UUID buyerId = CurrentUser.id(CurrentUser.of(request));
        UUID orderId = requestedOrder(request);
        SupportTicket ticket = service.create(buyerId, orderId);
        return ResponseEntity.status(HttpStatus.CREATED).contentType(JSON).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .location(URI.create(BASE + "/" + ticket.id())).body(Json.write(toJson(ticket)));
    }

    @GetMapping(BASE)
    public ResponseEntity<String> list(HttpServletRequest request) {
        UUID buyerId = CurrentUser.id(CurrentUser.of(request));
        QueryParams params = QueryParams.of(request, "orderId", "status");
        Optional<UUID> orderId = params.matching("orderId", ID, 36).map(UUID::fromString);
        Optional<List<String>> statuses = params.list("status", SupportTicket.STATUSES);
        int limit = params.limit();
        Optional<TicketFilter.After> after = cursor(params);
        params.check();
        TicketPage page = service.list(new TicketFilter(buyerId, orderId, statuses.orElse(List.of()), limit, after));
        return ResponseEntity.ok().contentType(JSON).header(HttpHeaders.CACHE_CONTROL, "no-store").body(Json.write(toJson(page)));
    }

    @GetMapping(BASE + "/{ticketId}")
    public ResponseEntity<String> get(@PathVariable("ticketId") String ticketId, HttpServletRequest request) {
        UUID buyerId = CurrentUser.id(CurrentUser.of(request));
        if (!ID.matcher(ticketId).matches()) {
            throw new ProblemException(ProblemType.NOT_FOUND, "Обращение не найдено.");
        }
        SupportTicket ticket = service.get(buyerId, UUID.fromString(ticketId));
        return ResponseEntity.ok().contentType(JSON).header(HttpHeaders.CACHE_CONTROL, "no-store").body(Json.write(toJson(ticket)));
    }

    /**
     * Тело {@code CreateSupportTicketRequest}: ровно два поля, {@code orderId} (UUID) и {@code reason} со значением
     * {@code key_not_received}. Ошибки копятся и уходят одним ответом 422.
     */
    private static UUID requestedOrder(HttpServletRequest request) throws IOException {
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
            if (!name.equals("orderId") && !name.equals("reason")) {
                errors.add(error("/" + name, "unknown_value", "Лишнее поле: допустимы orderId и reason."));
            }
        }
        UUID orderId = null;
        Object rawOrder = body.get("orderId");
        if (rawOrder == null) {
            errors.add(error("/orderId", "required", "Поле обязательно: идентификатор заказа."));
        } else if (!(rawOrder instanceof String text) || !ID.matcher(text).matches()) {
            errors.add(error("/orderId", "invalid_format", "Нужен идентификатор заказа в виде UUID."));
        } else {
            orderId = UUID.fromString(text);
        }
        Object reason = body.get("reason");
        if (reason == null) {
            errors.add(error("/reason", "required", "Поле обязательно: " + SupportTicket.KEY_NOT_RECEIVED + "."));
        } else if (!SupportTicket.KEY_NOT_RECEIVED.equals(reason)) {
            errors.add(error("/reason", "unknown_value", "Для покупателя допустима только причина " + SupportTicket.KEY_NOT_RECEIVED + "."));
        }
        if (!errors.isEmpty()) {
            throw new ProblemException(ProblemType.VALIDATION_FAILED, "Данные не прошли проверку, подробности в поле errors.",
                    Map.of("errors", List.copyOf(errors)), Map.of());
        }
        return orderId;
    }

    private static Optional<TicketFilter.After> cursor(QueryParams params) {
        Optional<Map<String, String>> values = params.cursorValues("t", "i");
        if (values.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new TicketFilter.After(Instant.parse(values.get().get("t")), UUID.fromString(values.get().get("i"))));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            params.error("cursor", "invalid_format", "Курсор не разобран: возьмите его из page.nextCursor предыдущего ответа.");
            return Optional.empty();
        }
    }

    private static Map<String, Object> error(String pointer, String code, String detail) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("pointer", pointer);
        error.put("code", code);
        error.put("detail", detail);
        return error;
    }

    /** Ответ по схеме SupportTicketPage. */
    static Map<String, Object> toJson(TicketPage page) {
        List<Object> items = new ArrayList<>();
        for (SupportTicket ticket : page.items()) {
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
     * Ответ по схеме SupportTicket. Состояние смены e-mail ({@code emailChange}) заполнено, когда оператор начал смену по обращению (шаг 14, Р-169),
     * иначе {@code null}: по нему форма обращения знает, какой код просить у покупателя. Ни значений ключей, кодов и полного адреса в ответе нет.
     */
    static Map<String, Object> toJson(SupportTicket t) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ticketId", t.id().toString());
        row.put("orderId", t.orderId().toString());
        row.put("source", t.source());
        row.put("reason", t.reason());
        row.put("status", t.status());
        row.put("createdAt", Timestamps.format(t.createdAt()));
        row.put("resolvedAt", t.resolvedAt().map(Timestamps::format).orElse(null));
        Map<String, Object> resolution = null;
        if (t.resolvedBy().isPresent()) {
            resolution = new LinkedHashMap<>();
            resolution.put("resolvedBy", t.resolvedBy().get());
            resolution.put("action", t.action().orElse(null));
        }
        row.put("resolution", resolution);
        row.put("emailChange", t.emailChange().map(EmailChangeController::toJson).orElse(null));
        return row;
    }
}
