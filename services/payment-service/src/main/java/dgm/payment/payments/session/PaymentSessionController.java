package dgm.payment.payments.session;

import dgm.kit.json.Json;
import dgm.kit.time.Timestamps;
import dgm.payment.payments.domain.Payment;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code openPaymentSession} (OP-51): внутренний вызов {@code order-service} при создании заказа. Список вызывающих проверяет фильтр
 * каркаса по сертификату клиента (mTLS) и правилам маршрутов, ключ идемпотентности ведёт охранник каркаса. Внешний идентификатор платежа
 * у шлюза в ответ не попадает.
 */
@RestController
public class PaymentSessionController {

    private static final String PATH = "/internal/v1/payment-sessions";

    private final PaymentSessionService service;

    public PaymentSessionController(PaymentSessionService service) {
        this.service = service;
    }

    @PostMapping(PATH)
    public ResponseEntity<String> open(HttpServletRequest request) throws IOException {
        PaymentSessionBodies.OpenRequest body = PaymentSessionBodies.read(request);
        Payment payment = service.open(body.orderId(), body.amount(), body.currency(), body.sessionExpiresAt());
        return ResponseEntity.status(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Json.write(view(payment)));
    }

    /** Платёжная сессия для ответа (схема {@code PaymentSessionOpened}). */
    static Map<String, Object> view(Payment payment) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("paymentId", payment.id().toString());
        body.put("orderId", payment.orderId().toString());
        body.put("status", payment.status());
        body.put("sessionUrl", payment.sessionUrl());
        body.put("sessionExpiresAt", Timestamps.format(payment.sessionExpiresAt()));
        return body;
    }
}
