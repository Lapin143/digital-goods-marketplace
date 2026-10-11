package dgm.keycloak.smsotp;

import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.authenticators.browser.CookieAuthenticator;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.services.managers.AuthenticationManager;

/**
 * Вход по SSO-cookie для клиентов с полным доступом. Стандартный шаг «Cookie» принимает любую живую сессию Keycloak, в том числе
 * открытую кодом из SMS: покупатель, вошедший по SMS (ограниченная сессия, roles-permissions.md, раздел 5), получил бы без пароля токен
 * dgm-web с полными областями. Здесь такая сессия (заметка {@code auth_level=sms}) cookie-входом не принимается: шаг сообщает
 * «не подошёл», поток идёт дальше к форме пароля, а после пароля шаг {@link AuthLevelAuthenticator} повышает уровень сессии.
 *
 * <p>Всё остальное делает стандартный шаг, чтобы не копировать его правила (повторная аутентификация, max_age, prompt=login).
 */
final class DgmCookieAuthenticator implements Authenticator {

    private final CookieAuthenticator standard = new CookieAuthenticator();

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        AuthenticationManager.AuthResult existing =
                AuthenticationManager.authenticateIdentityCookie(context.getSession(), context.getRealm(), true);
        if (existing != null && SmsOtpAuthenticator.SMS_LEVEL.equals(existing.session().getNote(SmsOtpAuthenticator.AUTH_LEVEL_NOTE))) {
            context.attempted();
            return;
        }
        standard.authenticate(context);
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
