package dgm.keycloak.smsotp;

import org.keycloak.models.AuthenticationExecutionModel.Requirement;

/** Фабрика шага {@code dgm-reset-email}: письмо для сброса пароля с выровненным временем ответа (см. {@link ResetEmailAuthenticator}). */
public final class ResetEmailAuthenticatorFactory extends DgmAuthenticatorFactory {

    public static final String ID = "dgm-reset-email";

    public ResetEmailAuthenticatorFactory() {
        super(ID, "DGM: письмо для сброса пароля с выровненным временем",
                "Как стандартный шаг «Отправить письмо для сброса», но время ответа не зависит от того, есть ли такой пользователь.",
                new ResetEmailAuthenticator(), Requirement.REQUIRED, Requirement.DISABLED);
    }
}
