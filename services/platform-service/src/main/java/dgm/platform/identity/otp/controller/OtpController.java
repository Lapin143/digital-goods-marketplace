package dgm.platform.identity.otp.controller;

import dgm.kit.json.Json;
import dgm.platform.identity.otp.domain.OtpPurpose;
import dgm.platform.identity.otp.domain.PhoneNumber;
import dgm.platform.identity.otp.service.SmsLoginService;
import dgm.platform.identity.otp.service.VkEmailCodeService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Внутренние операции кодов для Keycloak: {@code issueOtpCode} и {@code verifyOtpCode} (OP-07). Назначение {@code sms_login} это вход по коду из
 * SMS (номер), {@code vk_email} это код из письма при входе через VK ID без адреса (Р-194, адрес). Вызывает только расширение Keycloak
 * {@code sms-otp} (фильтр каркаса проверяет вызывающего по сертификату mTLS), ключ идемпотентности ведёт охранник каркаса.
 */
@RestController
public class OtpController {

    private static final int MAX_BODY = 1024;
    private static final String SMS = OtpPurpose.SMS_LOGIN.code();
    private static final String VK_EMAIL = OtpPurpose.VK_EMAIL.code();
    private static final Set<String> PURPOSES = Set.of(SMS, VK_EMAIL);

    private final SmsLoginService service;
    private final VkEmailCodeService vkEmail;

    public OtpController(SmsLoginService service, VkEmailCodeService vkEmail) {
        this.service = service;
        this.vkEmail = vkEmail;
    }

    @PostMapping("/internal/v1/otp-codes")
    public ResponseEntity<String> issue(HttpServletRequest request) throws IOException {
        OtpRequests.Body body = OtpRequests.body(request, MAX_BODY, "purpose", "phone", "email", "clientIp");
        String purpose = body.oneOf("purpose", PURPOSES).orElse("");
        if (VK_EMAIL.equals(purpose)) {
            Optional<String> email = body.email("email");
            body.absent("phone", VK_EMAIL);
            body.absent("clientIp", VK_EMAIL);
            body.check();
            vkEmail.requestCode(email.orElseThrow());
        } else {
            Optional<PhoneNumber> phone = body.phone("phone");
            Optional<String> ip = body.optionalIp("clientIp");
            body.absent("email", SMS);
            body.check();
            service.requestCode(phone.orElseThrow(), ip);
        }
        return OtpRequests.noStore(ResponseEntity.status(HttpStatus.ACCEPTED)).body(Json.write(Map.of("accepted", true)));
    }

    @PostMapping("/internal/v1/otp-verifications")
    public ResponseEntity<String> verify(HttpServletRequest request) throws IOException {
        OtpRequests.Body body = OtpRequests.body(request, MAX_BODY, "purpose", "phone", "email", "code");
        String purpose = body.oneOf("purpose", PURPOSES).orElse("");
        Map<String, Object> json = new LinkedHashMap<>();
        if (VK_EMAIL.equals(purpose)) {
            Optional<String> email = body.email("email");
            Optional<String> code = body.code("code");
            body.absent("phone", VK_EMAIL);
            body.check();
            vkEmail.verifyCode(email.orElseThrow(), code.orElseThrow());
            json.put("verified", true);
        } else {
            Optional<PhoneNumber> phone = body.phone("phone");
            Optional<String> code = body.code("code");
            body.absent("email", SMS);
            body.check();
            UUID userId = service.verify(phone.orElseThrow(), code.orElseThrow());
            json.put("userId", userId.toString());
        }
        return OtpRequests.noStore(ResponseEntity.ok()).body(Json.write(json));
    }
}
