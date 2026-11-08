package dgm.keycloak.smsotp;

import org.keycloak.models.AuthenticationExecutionModel.Requirement;

/**
 * Фабрика шага «запрос e-mail при входе через VK ID без адреса» (Р-194, F4-3). Идентификатор dgm-vk-email указан в потоке
 * {@code dgm-first-broker-login} файла realm.
 */
public final class VkEmailAuthenticatorFactory extends DgmAuthenticatorFactory {

    /** Идентификатор провайдера, на него ссылается поток первого входа в realm. */
    public static final String ID = "dgm-vk-email";

    public VkEmailAuthenticatorFactory() {
        super(ID, "DGM: запрос e-mail при входе через VK ID",
                "Если VK ID не передал адрес, просит его у гостя и подтверждает кодом из письма (код выпускает platform-service по mTLS). Занятый адрес не привязывается.",
                new VkEmailAuthenticator(), Requirement.REQUIRED, Requirement.DISABLED);
    }
}
