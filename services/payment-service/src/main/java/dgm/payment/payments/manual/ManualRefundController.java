package dgm.payment.payments.manual;

import dgm.kit.json.Json;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.security.CurrentUser;
import dgm.kit.time.Timestamps;
import dgm.kit.web.ClientAddress;
import dgm.kit.web.PageCursor;
import dgm.kit.web.QueryParams;
import dgm.payment.payments.domain.ManualRefund;
import dgm.payment.payments.repository.RefundRepository.QueueCursor;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Очередь ручных возвратов: {@code listManualRefunds} и {@code completeManualRefund} (OP-57, US-5.8). Роль администратора, область
 * {@code staff.admin} и второй фактор проверяет фильтр каркаса по правилам маршрутов (сессия по SMS отклоняется), ключ идемпотентности
 * ведёт охранник каркаса: повтор отметки с тем же ключом отдаёт сохранённый ответ.
 */
@RestController
public class ManualRefundController {

    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final String BASE = "/api/v1/staff/manual-refunds";

    private final ManualRefundService service;

    public ManualRefundController(ManualRefundService service) {
        this.service = service;
    }

    @GetMapping(BASE)
    public ResponseEntity<String> queue(HttpServletRequest request) {
        QueryParams params = QueryParams.of(request);
        int limit = params.limit();
        Optional<QueueCursor> after = cursor(params);
        params.check();
        return json(queueView(service.queue(limit, after)));
    }

    @PostMapping(BASE + "/{paymentId}/complete")
    public ResponseEntity<String> complete(@PathVariable("paymentId") String paymentId, HttpServletRequest request) throws IOException {
        UUID id = paymentId(paymentId);
        String externalRefundId = ManualRefundBodies.externalRefundId(request);
        ManualRefundService.Admin admin = new ManualRefundService.Admin(CurrentUser.id(CurrentUser.of(request)), ClientAddress.of(request));
        ManualRefundService.Completed done = service.complete(admin, id, externalRefundId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("paymentId", done.paymentId().toString());
        body.put("orderId", done.orderId().toString());
        body.put("paymentStatus", "refunded");
        body.put("refundedAt", Timestamps.format(done.refundedAt()));
        return json(body);
    }

    /** Тело ответа по схеме {@code ManualRefundPage}. */
    static Map<String, Object> queueView(ManualRefundService.Page page) {
        List<Object> items = new ArrayList<>();
        for (ManualRefund refund : page.items()) {
            Map<String, Object> money = new LinkedHashMap<>();
            money.put("amount", refund.amount());
            money.put("currency", refund.currency());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("paymentId", refund.paymentId().toString());
            row.put("orderId", refund.orderId().toString());
            row.put("amount", money);
            row.put("refundReason", refund.refundReason());
            row.put("attempts", refund.attempts());
            row.put("escalatedAt", Timestamps.format(refund.escalatedAt()));
            items.add(row);
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("limit", page.limit());
        info.put("nextCursor", page.next().map(n -> PageCursor.encode(Map.of("p", n.escalatedAt().toString(), "i", n.paymentId().toString()))).orElse(null));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("page", info);
        return body;
    }

    private static ResponseEntity<String> json(Map<String, Object> body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CACHE_CONTROL, "no-store").body(Json.write(body));
    }

    private static UUID paymentId(String value) {
        if (!ID.matcher(value).matches()) {
            throw new ProblemException(ProblemType.BAD_REQUEST, "Идентификатор платежа должен быть UUID в нижнем регистре.");
        }
        return UUID.fromString(value);
    }

    private static Optional<QueueCursor> cursor(QueryParams params) {
        Optional<Map<String, String>> values = params.cursorValues("p", "i");
        if (values.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new QueueCursor(Instant.parse(values.get().get("p")), UUID.fromString(values.get().get("i"))));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            params.error("cursor", "invalid_format", "Курсор не разобран: возьмите его из page.nextCursor предыдущего ответа.");
            return Optional.empty();
        }
    }
}
