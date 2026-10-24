package dgm.delivery.delivery.webhook;

import dgm.delivery.delivery.service.DeliveryStatuses;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code receiveKeyEmailStatus} (OP-60): статус письма с ключами от e-mail-провайдера. Подпись и свежесть метки времени проверяет фильтр каркаса
 * {@code webhook-signature} до этого метода (секрет {@code delivery_webhook_secret}), тело до 64 КБ. Ответ 200 без тела получают и применённый статус,
 * и повтор, и неизвестное письмо, и статус, который в состояние выдачи не вписывается: провайдер не должен повторять их бесконечно. Статус, который
 * обогнал запись исхода отправки, тоже принимается: его подберёт опрос статусов (ADR-011).
 */
@RestController
public class EmailStatusController {

    private static final String PATH = "/api/v1/webhooks/email-provider-keys";

    private final DeliveryStatuses statuses;

    public EmailStatusController(DeliveryStatuses statuses) {
        this.statuses = statuses;
    }

    @PostMapping(PATH)
    public ResponseEntity<Void> receive(HttpServletRequest request) throws IOException {
        statuses.apply(EmailStatusBodies.read(request));
        return ResponseEntity.ok().build();
    }
}
