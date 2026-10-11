package dgm.keycloak.smsotp;

import org.keycloak.models.AuthenticationExecutionModel.Requirement;

/** Фабрика шага {@code dgm-auth-level}: отмечает сессию как открытую полным способом входа (см. {@link AuthLevelAuthenticator}). */
public final class AuthLevelAuthenticatorFactory extends DgmAuthenticatorFactory {

    public static final String ID = "dgm-auth-level";

    public AuthLevelAuthenticatorFactory() {
        super(ID, "DGM: уровень входа «полный»",
                "Ставит в сессию заметку auth_level=full после пароля, второго фактора или входа через внешнего провайдера.",
                new AuthLevelAuthenticator(), Requirement.REQUIRED, Requirement.DISABLED);
    }
}
