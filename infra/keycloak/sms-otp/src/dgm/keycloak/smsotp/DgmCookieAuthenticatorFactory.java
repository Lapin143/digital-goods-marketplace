package dgm.keycloak.smsotp;

import org.keycloak.models.AuthenticationExecutionModel.Requirement;

/** Фабрика шага {@code dgm-cookie}: вход по SSO-cookie, кроме сессии, открытой по коду из SMS (см. {@link DgmCookieAuthenticator}). */
public final class DgmCookieAuthenticatorFactory extends DgmAuthenticatorFactory {

    public static final String ID = "dgm-cookie";

    public DgmCookieAuthenticatorFactory() {
        super(ID, "DGM: SSO-cookie без сессий по SMS",
                "Как стандартный шаг «Cookie», но сессию, открытую входом по SMS, не принимает: полный доступ требует пароля.",
                new DgmCookieAuthenticator(), Requirement.ALTERNATIVE, Requirement.DISABLED);
    }
}
