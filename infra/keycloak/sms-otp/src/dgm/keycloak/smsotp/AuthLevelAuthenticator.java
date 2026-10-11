package dgm.keycloak.smsotp;

import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

/**
 * Отмечает сессию как открытую полным способом (пароль, второй фактор, внешний провайдер): заметка пользовательской сессии
 * {@code auth_level=full}. Нужна потому, что Keycloak использует идентификатор уже существующей сессии пользователя повторно: если
 * в том же браузере раньше был вход по SMS, заметка {@code auth_level=sms} осталась бы после пароля, и {@link DgmCookieAuthenticator}
 * отказывал бы в cookie-входе уже полноценной сессии. Стоит в конце потоков, после всех проверок.
 */
final class AuthLevelAuthenticator implements Authenticator {

    static final String FULL_LEVEL = "full";

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        context.getAuthenticationSession().setUserSessionNote(SmsOtpAuthenticator.AUTH_LEVEL_NOTE, FULL_LEVEL);
        context.success();
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        authenticate(context);
    }

    @Override
    public boolean requiresUser() {
        return true;
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
