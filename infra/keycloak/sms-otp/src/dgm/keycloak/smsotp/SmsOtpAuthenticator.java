package dgm.keycloak.smsotp;

import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

/**
 * Скелет аутентификатора входа по коду из SMS. В фазе 3 он собирается, подключается к потоку sms-login и честно отказывает:
 * логика кодов (отправка, проверка, лимиты, поиск учётной записи по номеру) принадлежит platform-service и появится в фазе 4.
 * Отказ 501 лучше «успеха по умолчанию»: недописанный второй фактор не должен пропускать никого.
 */
final class SmsOtpAuthenticator implements Authenticator {

    private static final Logger LOG = Logger.getLogger(SmsOtpAuthenticator.class);

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        LOG.warn("sms-otp: вход по коду из SMS ещё не реализован (фаза 4), доступ закрыт");
        Response page = context.form()
                .setError("Вход по коду из SMS пока недоступен.")
                .createErrorPage(Response.Status.NOT_IMPLEMENTED);
        context.failure(AuthenticationFlowError.INTERNAL_ERROR, page);
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        authenticate(context);
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
