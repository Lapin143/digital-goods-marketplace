package dgm.order.orders.controller;

import dgm.kit.json.Json;
import dgm.kit.security.AuthenticatedUser;
import dgm.kit.security.CurrentUser;
import dgm.kit.time.Timestamps;
import dgm.kit.web.PageCursor;
import dgm.kit.web.QueryParams;
import dgm.order.orders.domain.DeliveryAddress;
import dgm.order.orders.domain.Order;
import dgm.order.orders.domain.OrderFilter;
import dgm.order.orders.domain.OrderPage;
import dgm.order.orders.service.OrderHistoryService;
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

/** {@code listOrders}: история заказов текущего покупателя, новые выше. Нужна область {@code orders.read}, владелец берётся из токена (OP-58, U1). */
@RestController
public class OrderController {

    private static final MediaType JSON = new MediaType("application", "json", StandardCharsets.UTF_8);
    private static final Set<String> STATUSES = Set.of("created", "awaiting_payment", "paid", "issued", "cancelled", "refunded");

    private final OrderHistoryService service;

    public OrderController(OrderHistoryService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/orders")
    public ResponseEntity<String> list(HttpServletRequest request) {
        AuthenticatedUser user = CurrentUser.of(request);
        UUID buyer = CurrentUser.id(user);
        QueryParams params = QueryParams.of(request, "status", "createdFrom", "createdTo");
        Optional<List<String>> statuses = params.list("status", STATUSES);
        Optional<Instant> from = params.instant("createdFrom");
        Optional<Instant> to = params.instant("createdTo");
        if (from.isPresent() && to.isPresent() && !from.get().isBefore(to.get())) {
            params.error("createdFrom", "out_of_range", "Начало периода должно быть раньше его конца.");
        }
        int limit = params.limit();
        Optional<OrderFilter.After> after = cursor(params);
        params.check();

        OrderPage page = service.list(new OrderFilter(buyer, statuses, from, to, limit, after));
        return ResponseEntity.ok().contentType(JSON).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Json.write(toJson(page, user.smsSession())));
    }

    private static Optional<OrderFilter.After> cursor(QueryParams params) {
        Optional<Map<String, String>> values = params.cursorValues("t", "i");
        if (values.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new OrderFilter.After(Instant.parse(values.get().get("t")), UUID.fromString(values.get().get("i"))));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            params.error("cursor", "invalid_format", "Курсор не разобран: возьмите его из page.nextCursor предыдущего ответа.");
            return Optional.empty();
        }
    }

    /** Ответ по схеме OrderPage; в сессии по SMS адрес доставки маскируется. */
    static Map<String, Object> toJson(OrderPage page, boolean maskAddress) {
        List<Object> items = new ArrayList<>();
        for (Order order : page.items()) {
            items.add(toJson(order, maskAddress));
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("limit", page.limit());
        info.put("nextCursor", page.next().map(n -> PageCursor.encode(Map.of("t", n.createdAt().toString(), "i", n.id().toString()))).orElse(null));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("page", info);
        return body;
    }

    private static Map<String, Object> toJson(Order o, boolean maskAddress) {
        Map<String, Object> delivery = new LinkedHashMap<>();
        delivery.put("channel", o.deliveryChannel());
        delivery.put("address", maskAddress ? DeliveryAddress.mask(o.deliveryAddress()) : o.deliveryAddress());
        delivery.put("masked", maskAddress);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("orderId", o.id().toString());
        row.put("productId", o.productId().toString());
        row.put("productTitle", o.productTitle());
        row.put("quantity", o.quantity());
        row.put("unitPrice", money(o.unitPrice(), o.currency()));
        row.put("total", money(o.amount(), o.currency()));
        row.put("status", o.status());
        row.put("cancelReason", o.cancelReason().orElse(null));
        row.put("delivery", delivery);
        row.put("reserveExpiresAt", o.reserveUntil().map(Timestamps::format).orElse(null));
        row.put("paymentSessionExpiresAt", o.sessionUntil().map(Timestamps::format).orElse(null));
        row.put("createdAt", Timestamps.format(o.createdAt()));
        row.put("paidAt", o.paidAt().map(Timestamps::format).orElse(null));
        row.put("issuedAt", o.issuedAt().map(Timestamps::format).orElse(null));
        return row;
    }

    private static Map<String, Object> money(long amount, String currency) {
        Map<String, Object> money = new LinkedHashMap<>();
        money.put("amount", amount);
        money.put("currency", currency);
        return money;
    }
}
