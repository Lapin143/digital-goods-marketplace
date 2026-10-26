package dgm.platform.identity.otp.service;

import dgm.platform.notification.api.DirectMail;
import dgm.platform.notification.api.DirectMailException;
import dgm.platform.notification.api.InvalidNotificationException;
import java.util.Map;

/**
 * {@link EmailCodeSender} через прямую отправку модуля {@code notification} ({@link DirectMail}): письмо с кодом уходит провайдеру сразу,
 * в очереди и в базе его нет. К модулю notification идёт только его подпакет {@code api} (правило модульности 1).
 */
public final class DirectMailCodeSender implements EmailCodeSender {

    private final DirectMail mail;

    public DirectMailCodeSender(DirectMail mail) {
        this.mail = mail;
    }

    @Override
    public void send(String to, String code) {
        try {
            mail.send(to, DirectMail.EMAIL_CHANGE_CODE, Map.of("code", code));
        } catch (DirectMailException | InvalidNotificationException e) {
            throw new EmailCodeSendException("Письмо с кодом не принято провайдером почты", e);
        }
    }
}
