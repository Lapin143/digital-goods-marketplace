package dgm.platform.notification.controller;

import dgm.platform.notification.service.ReceiveMailStatusService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code receiveMailStatus} (OP-84, компонент {@code provider-webhook-controller}): статус письма платформы от почтового провайдера
 * (письма с ключами принимает {@code delivery-service}). Подпись и свежесть метки времени проверяет фильтр каркаса {@code webhook-signature}
 * до этого метода (секрет {@code platform_webhook_secret}), тело до 64 КБ. Ответ 200 без тела получают и применённый статус, и повтор, и неизвестное
 * сообщение, и статус, который в состояние уведомления не вписывается: провайдер не должен повторять их бесконечно, а по ответу не должно быть
 * видно, есть ли у платформы такое сообщение.
 */
@RestController
public class MailWebhookController {

    private static final String PATH = "/api/v1/webhooks/email-provider";

    private final ReceiveMailStatusService statuses;

    public MailWebhookController(ReceiveMailStatusService statuses) {
        this.statuses = statuses;
    }

    @PostMapping(PATH)
    public ResponseEntity<Void> receive(HttpServletRequest request) throws IOException {
        statuses.apply(MailStatusBodies.read(request));
        return ResponseEntity.ok().build();
    }
}
