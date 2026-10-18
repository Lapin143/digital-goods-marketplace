package dgm.payment.payments.webhook;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code receivePaymentGatewayNotification} (OP-54): уведомление платёжного шлюза. Подпись и свежесть метки времени проверяет фильтр
 * каркаса {@code webhook-signature} до этого метода, тело до 64 КБ. Ответ 200 без тела получают и обработанное уведомление, и повтор,
 * и неизвестный платёж, и расхождение суммы: шлюз не должен повторять их бесконечно (ADR-015). 503 просит шлюз повторить, когда мы не
 * смогли спросить его о статусе платежа.
 */
@RestController
public class PaymentWebhookController {

    private static final String PATH = "/api/v1/webhooks/payment-gateway";

    private final PaymentWebhookService service;

    public PaymentWebhookController(PaymentWebhookService service) {
        this.service = service;
    }

    @PostMapping(PATH)
    public ResponseEntity<Void> receive(HttpServletRequest request) throws IOException {
        service.receive(GatewayNotificationBodies.read(request));
        return ResponseEntity.ok().build();
    }
}
