package dgm.platform.identity.otp.controller;

import dgm.kit.json.Json;
import dgm.platform.identity.otp.domain.OtpPurpose;
import dgm.platform.identity.otp.domain.PhoneNumber;
import dgm.platform.identity.otp.service.SmsLoginService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Внутренние операции входа по коду из SMS: {@code issueOtpCode} и {@code verifyOtpCode} (OP-07). Вызывает только расширение Keycloak
 * {@code sms-otp} (фильтр каркаса проверяет вызывающего по сертификату mTLS), ключ идемпотентности ведёт охранник каркаса.
 */
@RestController
public class OtpController {

    private static final int MAX_BODY = 1024;

    private final SmsLoginService service;

    public OtpController(SmsLoginService service) {
        this.service = service;
    }

    @PostMapping("/internal/v1/otp-codes")
    public ResponseEntity<String> issue(HttpServletRequest request) throws IOException {
        OtpRequests.Body body = OtpRequests.body(request, MAX_BODY, "purpose", "phone", "clientIp");
        body.purpose("purpose", OtpPurpose.SMS_LOGIN.code());
        Optional<PhoneNumber> phone = body.phone("phone");
        Optional<String> ip = body.optionalIp("clientIp");
        body.check();

        service.requestCode(phone.orElseThrow(), ip);
        return OtpRequests.noStore(ResponseEntity.status(HttpStatus.ACCEPTED)).body(Json.write(Map.of("accepted", true)));
    }

    @PostMapping("/internal/v1/otp-verifications")
    public ResponseEntity<String> verify(HttpServletRequest request) throws IOException {
        OtpRequests.Body body = OtpRequests.body(request, MAX_BODY, "purpose", "phone", "code");
        body.purpose("purpose", OtpPurpose.SMS_LOGIN.code());
        Optional<PhoneNumber> phone = body.phone("phone");
        Optional<String> code = body.code("code");
        body.check();

        UUID userId = service.verify(phone.orElseThrow(), code.orElseThrow());
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("userId", userId.toString());
        return OtpRequests.noStore(ResponseEntity.ok()).body(Json.write(json));
    }
}
