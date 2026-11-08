package dgm.platform.identity.otp.controller;

import dgm.kit.json.Json;
import dgm.platform.identity.otp.service.OtpService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Внутренняя операция {@code consumeEmailQuota} (OP-103, F4-1, Р-194): счётчик писем на адрес. Расширение Keycloak {@code sms-otp} спрашивает его
 * до отправки письма на шагах регистрации, сброса пароля и запроса e-mail в потоке VK ID: больше 3 писем на адрес за 10 минут и 10 за сутки не уходит.
 * Вызывает только Keycloak (фильтр каркаса проверяет вызывающего по сертификату mTLS), ключ идемпотентности ведёт охранник каркаса: повтор того
 * же вызова места не занимает. Адрес в журнал и метрики не попадает.
 */
@RestController
public class EmailQuotaController {

    private static final int MAX_BODY = 1024;
    private static final Set<String> STEPS = Set.of("register", "reset", "vk_email");

    private final OtpService otp;

    public EmailQuotaController(OtpService otp) {
        this.otp = otp;
    }

    @PostMapping("/internal/v1/email-quota/consume")
    public ResponseEntity<String> consume(HttpServletRequest request) throws IOException {
        OtpRequests.Body body = OtpRequests.body(request, MAX_BODY, "purpose", "email");
        Optional<String> step = body.oneOf("purpose", STEPS);
        Optional<String> email = body.email("email");
        body.check();

        otp.consumeEmailQuota(step.orElseThrow(), email.orElseThrow());
        return OtpRequests.noStore(ResponseEntity.ok()).body(Json.write(Map.of("allowed", true)));
    }
}
