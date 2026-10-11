package dgm.keycloak.smsotp;

import org.keycloak.models.AuthenticationExecutionModel.Requirement;

/**
 * Фабрика аутентификатора «вход по коду из SMS» (ADR-010, вариант 4: Keycloak только связывает, коды живут в platform-service).
 * Идентификатор sms-otp указан в потоке sms-login файла realm.
 */
public final class SmsOtpAuthenticatorFactory extends DgmAuthenticatorFactory {

    /** Идентификатор провайдера, на него ссылается поток входа в realm. */
    public static final String ID = "sms-otp";

    public SmsOtpAuthenticatorFactory() {
        super(ID, "DGM: вход по коду из SMS",
                "Принимает номер и код из SMS, проверяет их вызовом platform-service по mTLS (ADR-010). Номер из ответа не раскрывается.",
                new SmsOtpAuthenticator(), Requirement.REQUIRED, Requirement.DISABLED);
    }
}
