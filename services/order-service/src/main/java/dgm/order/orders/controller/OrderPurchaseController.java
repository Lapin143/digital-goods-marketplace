package dgm.order.orders.controller;

import dgm.kit.json.Json;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.security.AuthenticatedUser;
import dgm.kit.security.CurrentUser;
import dgm.kit.time.Timestamps;
import dgm.order.orders.domain.Order;
import dgm.order.orders.domain.OrderSession;
import dgm.order.orders.domain.StoredOrder;
import dgm.order.orders.service.OrderViewService;
import dgm.order.orders.service.SagaOrchestrator;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * Покупка: {@code createOrder} (OP-50, область {@code orders.create}), {@code getOrder} (OP-58, {@code orders.read}) и
 * {@code getOrderPaymentSession} (OP-52). Владелец всегда берётся из токена, чужой заказ неотличим от несуществующего (U1). Сессия по SMS
 * оформлять заказ и открывать оплату не может (правило маршрута), карточку заказа видит с маскированным адресом. Ключ идемпотентности создания
 * ведёт охранник каркаса в режиме внешних шагов (ADR-006, Р-150): тело и заголовки ответа он сохраняет сам.
 */
@RestController
public class OrderPurchaseController {

    private static final String BASE = "/api/v1/orders";
    private static final MediaType JSON = new MediaType("application", "json", StandardCharsets.UTF_8);
    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final SagaOrchestrator saga;
    private final OrderViewService views;

    public OrderPurchaseController(SagaOrchestrator saga, OrderViewService views) {
        this.saga = saga;
        this.views = views;
    }

    @PostMapping(BASE)
    public ResponseEntity<String> create(HttpServletRequest request) throws IOException {
        AuthenticatedUser user = CurrentUser.of(request);
        if (!user.phoneVerified()) {
            throw new ProblemException(ProblemType.PHONE_NOT_CONFIRMED, "Подтвердите номер телефона, чтобы оформить заказ.");
        }
        UUID buyer = CurrentUser.id(user);
        CreateOrderBodies.CreateRequest body = CreateOrderBodies.read(request);
        String email = CurrentUser.email(user);

        StoredOrder order = saga.create(buyer, email, body.productId(), body.quantity());
        return ResponseEntity.status(HttpStatus.CREATED).contentType(JSON).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.LOCATION, BASE + "/" + order.id()).body(Json.write(withSession(order)));
    }

    @GetMapping(BASE + "/{orderId}")
    public ResponseEntity<String> get(@PathVariable("orderId") String orderId, HttpServletRequest request) {
        AuthenticatedUser user = CurrentUser.of(request);
        Order order = views.get(orderId(orderId), CurrentUser.id(user));
        return ResponseEntity.ok().contentType(JSON).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Json.write(OrderController.toJson(order, user.smsSession())));
    }

    @GetMapping(BASE + "/{orderId}/payment-session")
    public ResponseEntity<String> paymentSession(@PathVariable("orderId") String orderId, HttpServletRequest request) {
        AuthenticatedUser user = CurrentUser.of(request);
        OrderSession session = views.paymentSession(orderId(orderId), CurrentUser.id(user));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", session.orderId().toString());
        body.put("url", session.url().orElseThrow());
        body.put("expiresAt", Timestamps.format(session.expiresAt().orElseThrow()));
        return ResponseEntity.ok().contentType(JSON).header(HttpHeaders.CACHE_CONTROL, "no-store").body(Json.write(body));
    }

    /** Заказ в ответе создания: схема Order и платёжная сессия (схема OrderWithPaymentSession). Адрес не маскируется: сессия по SMS сюда не пускается. */
    static Map<String, Object> withSession(StoredOrder order) {
        Map<String, Object> body = new LinkedHashMap<>(OrderController.toJson(order.view(), false));
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("url", order.paymentSessionUrl());
        session.put("expiresAt", Timestamps.format(order.sessionUntil()));
        body.put("paymentSession", session);
        return body;
    }

    private static UUID orderId(String value) {
        if (!ID.matcher(value).matches()) {
            throw new ProblemException(ProblemType.BAD_REQUEST, "Идентификатор заказа должен быть UUID в нижнем регистре.");
        }
        return UUID.fromString(value);
    }
}
