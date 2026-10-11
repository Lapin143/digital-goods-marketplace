package dgm.keycloak.smsotp;

import dgm.keycloak.smsotp.core.TimePad;
import java.time.Duration;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.authenticators.resetcred.ResetCredentialEmail;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

/**
 * Шаг «отправить письмо для сброса пароля» с выровненным временем ответа (ST-06). Страница Keycloak отвечает на любой адрес одинаково
 * («вы должны получить письмо»), но для существующего адреса отправляет письмо по SMTP, а для неизвестного нет: ответ быстрее на время
 * отправки, и по времени адреса различимы. Здесь ответ задерживается до {@link #MINIMUM}; саму работу выполняет стандартный шаг.
 */
final class ResetEmailAuthenticator implements Authenticator {

    /** Минимальная длительность шага: заведомо больше отправки письма по SMTP. */
    static final Duration MINIMUM = Duration.ofMillis(800);

    private final ResetCredentialEmail standard = new ResetCredentialEmail();

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        long started = System.nanoTime();
        try {
            standard.authenticate(context);
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
