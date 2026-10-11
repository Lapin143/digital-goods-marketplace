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
 * Общая часть фабрик шагов входа проекта: у шагов нет настроек, один неизменяемый объект шага на процесс, идентификатор и описание
 * задают наследники. Идентификаторы указаны в потоках realm (infra/keycloak/realm/dgm-realm.json, генератор gen_realm.py).
 */
abstract class DgmAuthenticatorFactory implements AuthenticatorFactory {

    private final String id;
    private final String displayType;
    private final String helpText;
    private final Authenticator authenticator;
    private final Requirement[] requirements;

    DgmAuthenticatorFactory(String id, String displayType, String helpText, Authenticator authenticator, Requirement... requirements) {
        this.id = id;
        this.displayType = displayType;
        this.helpText = helpText;
        this.authenticator = authenticator;
        this.requirements = requirements;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getDisplayType() {
        return displayType;
    }

    @Override
    public String getReferenceCategory() {
        return id;
    }

    @Override
    public String getHelpText() {
        return helpText;
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
        return requirements.clone();
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return List.of();
    }

    @Override
    public Authenticator create(KeycloakSession session) {
        return authenticator;
    }

    @Override
    public void init(Config.Scope config) {
        // настроек нет: адрес и сертификаты платформы читаются из окружения при первом входе (PlatformConnection)
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
