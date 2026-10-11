package dgm.platform.identity.otp.controller;

import dgm.kit.problem.ProblemType;
import dgm.platform.identity.otp.service.SmsStatusService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Приём статуса SMS от провайдера: {@code receiveSmsStatus} (OP-06). Подпись и свежесть метки времени проверяет фильтр каркаса
 * ({@code webhook-signature}), тело до 64 КБ. Ответ 200 без тела, в том числе на повтор; неверные поля дают 400 (так записано в контракте).
 */
@RestController
public class SmsStatusController {

    private static final int MAX_BODY = 64 * 1024;
    private static final Set<String> STATUSES = Set.of("delivered", "failed");

    private final SmsStatusService service;

    public SmsStatusController(SmsStatusService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/webhooks/sms-provider")
    public ResponseEntity<Void> receive(HttpServletRequest request) throws IOException {
        OtpRequests.Body body = OtpRequests.body(request, MAX_BODY, "messageId", "status", "occurredAt");
        Optional<String> messageId = body.string("messageId", 1, 128);
        Optional<String> status = body.oneOf("status", STATUSES);
        body.timestamp("occurredAt");
        body.check(ProblemType.BAD_REQUEST);

        service.record(messageId.orElseThrow(), status.orElseThrow());
        return ResponseEntity.ok().build();
    }
}
