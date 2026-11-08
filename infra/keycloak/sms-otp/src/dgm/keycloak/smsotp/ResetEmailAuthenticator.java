package dgm.keycloak.smsotp;

import dgm.keycloak.smsotp.core.EmailQuotaGate;
import dgm.keycloak.smsotp.core.TimePad;
import java.time.Duration;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.authenticators.resetcred.ResetCredentialEmail;
import org.keycloak.events.EventType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.FormMessage;
import org.keycloak.services.messages.Messages;

/**
 * Шаг «отправить письмо для сброса пароля» с выровненным временем ответа (ST-06). Страница Keycloak отвечает на любой адрес одинаково
 * («вы должны получить письмо»), но для существующего адреса отправляет письмо по SMTP, а для неизвестного нет: ответ быстрее на время
 * отправки, и по времени адреса различимы. Здесь ответ задерживается до {@link #MINIMUM}; саму работу выполняет стандартный шаг.
 *
 * <p>Перед отправкой письма существующей учётной записи шаг занимает место в общем счётчике писем на адрес (F4-1, {@link EmailQuotaGate}).
 * Если места нет (лимит исчерпан или платформа недоступна), письмо не отправляется, а ответ тот же «вы должны получить письмо», что и при
 * неизвестном адресе. Неизвестный адрес в счётчик не попадает: письма ему и так нет, а различий в ответе нет. Ответ и время прежние.
 */
final class ResetEmailAuthenticator implements Authenticator {

    /** Минимальная длительность шага: заведомо больше отправки письма по SMTP. */
    static final Duration MINIMUM = Duration.ofMillis(800);

    private final ResetCredentialEmail standard = new ResetCredentialEmail();

    private static final Logger LOG = Logger.getLogger(ResetEmailAuthenticator.class);

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        long started = System.nanoTime();
        try {
            UserModel user = context.getUser();
            if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
                standard.authenticate(context);
                return;
            }
            EmailQuotaGate.Verdict verdict = new EmailQuotaGate(PlatformConnection.get()).check("reset", user.getEmail());
            if (verdict == EmailQuotaGate.Verdict.SEND) {
                standard.authenticate(context);
            } else {
                LOG.infof("dgm-reset-email: письмо не отправлено, счётчик писем на адрес: %s", verdict);
                context.getEvent().clone().event(EventType.SEND_RESET_PASSWORD).user(user).error("email_quota_exceeded");
                context.forkWithSuccessMessage(new FormMessage(Messages.EMAIL_SENT));
            }
        } finally {
            TimePad.padTo(started, MINIMUM);
        }
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        standard.action(context);
    }

    @Override
    public boolean requiresUser() {
        return false;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
        // обязательных действий нет
    }

    @Override
    public void close() {
        // ресурсов нет
    }
}
