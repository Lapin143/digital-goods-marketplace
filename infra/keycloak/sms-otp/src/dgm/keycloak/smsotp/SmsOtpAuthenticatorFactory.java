package dgm.keycloak.smsotp;

import java.util.List;
import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

/**
 * Фабрика аутентификатора «вход по коду из SMS» (ADR-010, вариант 4: Keycloak только связывает, коды живут в platform-service).
 * Идентификатор sms-otp указан в потоке sms-login файла realm (infra/keycloak/realm/dgm-realm.json).
 */
public final class SmsOtpAuthenticatorFactory implements AuthenticatorFactory {

    /** Идентификатор провайдера, на него ссылается поток входа в realm. */
    public static final String ID = "sms-otp";

    private static final SmsOtpAuthenticator AUTHENTICATOR = new SmsOtpAuthenticator();
    private static final Requirement[] REQUIREMENTS = {Requirement.REQUIRED, Requirement.DISABLED};

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayType() {
        return "DGM: вход по коду из SMS";
    }

    @Override
    public String getReferenceCategory() {
        return "sms-otp";
    }

    @Override
    public String getHelpText() {
        return "Принимает номер и код из SMS, проверяет их вызовом platform-service (ADR-010). До фазы 4 только заглушка.";
    }

    @Override
    public boolean isConfigurable() {
        return false;
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    @Override
    public Requirement[] getRequirementChoices() {
        return REQUIREMENTS;
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return List.of();
    }

    @Override
    public Authenticator create(KeycloakSession session) {
        return AUTHENTICATOR;
    }

    @Override
    public void init(Config.Scope config) {
        // настроек нет: адрес platform-service появится вместе с логикой в фазе 4
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // действий после запуска нет
    }

    @Override
    public void close() {
        // ресурсов нет
    }
}
