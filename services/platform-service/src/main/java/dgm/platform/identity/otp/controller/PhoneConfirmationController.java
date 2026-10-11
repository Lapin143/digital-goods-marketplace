package dgm.platform.identity.otp.controller;

import dgm.kit.json.Json;
import dgm.kit.security.AuthenticatedUser;
import dgm.kit.security.CurrentUser;
import dgm.kit.time.Timestamps;
import dgm.platform.identity.domain.Roles;
import dgm.platform.identity.otp.domain.PhoneNumber;
import dgm.platform.identity.otp.service.PhoneConfirmationService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Подтверждение телефона покупателя: {@code createPhoneConfirmation} и {@code verifyPhoneConfirmation} (OP-06). Права по матрице
 * (покупатель с полной сессией, область {@code account.manage}) проверяет фильтр каркаса, ключ идемпотентности ведёт охранник каркаса.
 */
@RestController
public class PhoneConfirmationController {

    private static final int MAX_BODY = 1024;

    private final PhoneConfirmationService service;

    public PhoneConfirmationController(PhoneConfirmationService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/phone-confirmations")
    public ResponseEntity<String> create(HttpServletRequest request) throws IOException {
        AuthenticatedUser user = CurrentUser.of(request);
        OtpRequests.Body body = OtpRequests.body(request, MAX_BODY, "phone");
        Optional<PhoneNumber> phone = body.phone("phone");
        body.check();

        PhoneConfirmationService.Started started = service.start(CurrentUser.id(user), phone.orElseThrow(), OtpRequests.clientIp(request));
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("confirmationId", started.confirmationId().toString());
        json.put("phoneMasked", started.phone().masked());
        json.put("expiresAt", Timestamps.format(started.expiresAt()));
        json.put("attemptsLeft", started.attempts());
        json.put("resendAvailableAt", started.resendAt().map(Timestamps::format).orElse(null));
        return OtpRequests.noStore(ResponseEntity.status(HttpStatus.CREATED))
                .header(HttpHeaders.LOCATION, URI.create("/api/v1/phone-confirmations/" + started.confirmationId()).toString())
                .body(Json.write(json));
    }

    @PostMapping("/api/v1/phone-confirmations/{confirmationId}/verify")
    public ResponseEntity<String> verify(@PathVariable("confirmationId") String confirmationId, HttpServletRequest request)
            throws IOException {
        AuthenticatedUser user = CurrentUser.of(request);
        UUID id = OtpRequests.idOrNotFound(confirmationId, "Запрос подтверждения");
        OtpRequests.Body body = OtpRequests.body(request, MAX_BODY, "code");
        Optional<String> code = body.code("code");
        body.check();

        PhoneNumber phone = service.verify(CurrentUser.id(user), Roles.best(user.roles()), id, code.orElseThrow(),
                OtpRequests.clientIp(request));
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("phoneConfirmed", true);
        json.put("phoneMasked", phone.masked());
        return OtpRequests.noStore(ResponseEntity.ok()).body(Json.write(json));
    }
}
