package dgm.platform.notification.controller;

import dgm.kit.json.Json;
import dgm.kit.security.CurrentUser;
import dgm.kit.time.Timestamps;
import dgm.kit.web.PageCursor;
import dgm.kit.web.QueryParams;
import dgm.platform.notification.domain.WebhookDelivery;
import dgm.platform.notification.domain.WebhookDeliveryFilter;
import dgm.platform.notification.domain.WebhookDeliveryPage;
import dgm.platform.notification.service.WebhookDeliveryService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code listWebhookDeliveries}: уведомления о заказах, которые платформа отправляла или отправит в систему текущего продавца, новые выше (компонент
 * {@code webhook-delivery-controller}, OP-102, US-11.4, Р-193). Нужна роль продавца с областью {@code seller.catalog} и второй фактор, по API-ключу
 * операция недоступна (маршрут {@code smsSession: denied}, правила шлюза). Продавец берётся только из токена: другой продавец в запросе не
 * задаётся, чужие уведомления не видны ни при каком фильтре (U3). Тела уведомления и подписи в ответе нет, персональных данных нет. Ответ не кэшируется.
 */
@RestController
public class WebhookDeliveryController {

    private static final MediaType JSON = new MediaType("application", "json", StandardCharsets.UTF_8);
    private static final Set<String> STATUSES = Set.of("queued", "sent", "failed", "skipped");

    private final WebhookDeliveryService service;

    public WebhookDeliveryController(WebhookDeliveryService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/seller/webhook-deliveries")
    public ResponseEntity<String> list(HttpServletRequest request) {
        UUID seller = CurrentUser.sellerId(CurrentUser.of(request));
        QueryParams params = QueryParams.of(request, "status");
        Optional<List<String>> statuses = params.list("status", STATUSES);
        int limit = params.limit();
        Optional<WebhookDeliveryFilter.After> after = cursor(params);
        params.check();

        WebhookDeliveryPage page = service.list(new WebhookDeliveryFilter(seller, statuses, limit, after));
        return ResponseEntity.ok().contentType(JSON).header(HttpHeaders.CACHE_CONTROL, "no-store").body(Json.write(toJson(page)));
    }

    private static Optional<WebhookDeliveryFilter.After> cursor(QueryParams params) {
        Optional<Map<String, String>> values = params.cursorValues("t", "i");
        if (values.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new WebhookDeliveryFilter.After(Instant.parse(values.get().get("t")), UUID.fromString(values.get().get("i"))));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            params.error("cursor", "invalid_format", "Курсор не разобран: возьмите его из page.nextCursor предыдущего ответа.");
            return Optional.empty();
        }
    }

    /** Ответ по схеме WebhookDeliveryPage. */
    static Map<String, Object> toJson(WebhookDeliveryPage page) {
        List<Object> items = new ArrayList<>();
        for (WebhookDelivery delivery : page.items()) {
            items.add(toJson(delivery));
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("limit", page.limit());
        info.put("nextCursor", page.next().map(n -> PageCursor.encode(Map.of("t", n.createdAt().toString(), "i", n.id().toString()))).orElse(null));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("page", info);
        return body;
    }

    /** Доставка по схеме WebhookDelivery: тела и подписи нет. */
    static Map<String, Object> toJson(WebhookDelivery d) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("deliveryId", d.id().toString());
        row.put("eventId", d.eventId().toString());
        row.put("type", d.eventType());
        row.put("orderNumber", d.orderNumber());
        row.put("status", d.status());
        row.put("attempts", d.attempts());
        row.put("lastResponseCode", d.lastResponseCode());
        row.put("lastAttemptAt", d.lastAttemptAt() == null ? null : Timestamps.format(d.lastAttemptAt()));
        row.put("nextAttemptAt", d.nextAttemptAt() == null ? null : Timestamps.format(d.nextAttemptAt()));
        row.put("createdAt", Timestamps.format(d.createdAt()));
        return row;
    }
}
