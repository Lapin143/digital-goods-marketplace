package dgm.keycloak.smsotp;

import org.keycloak.models.AuthenticationExecutionModel.Requirement;

/** Фабрика шага {@code dgm-register}: регистрация с одинаковым ответом для свободного и занятого e-mail (см. {@link NeutralRegistrationAuthenticator}). */
public final class NeutralRegistrationAuthenticatorFactory extends DgmAuthenticatorFactory {

    public static final String ID = "dgm-register";

    public NeutralRegistrationAuthenticatorFactory() {
        super(ID, "DGM: регистрация с нейтральным ответом",
                "Форма регистрации, которая не сообщает, занят ли e-mail: страница «проверьте почту» одна, владельцу занятого адреса уходит письмо.",
                new NeutralRegistrationAuthenticator(), Requirement.REQUIRED, Requirement.DISABLED);
    }
}
