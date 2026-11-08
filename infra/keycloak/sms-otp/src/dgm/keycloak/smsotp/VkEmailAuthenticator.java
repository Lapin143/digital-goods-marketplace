package dgm.keycloak.smsotp;

import dgm.keycloak.smsotp.core.VkEmailLogin;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.authenticators.broker.AbstractIdpAuthenticator;
import org.keycloak.authentication.authenticators.broker.util.SerializedBrokeredIdentityContext;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.events.Details;
import org.keycloak.events.Errors;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;

/**
 * Запрос e-mail при первом входе через VK ID, когда провайдер подтвердил вход, но адрес не передал (Р-194, F4-3, US-1.2, сценарий 4).
 * Стоит первым в потоке {@code dgm-first-broker-login}. Если адрес в данных входа есть, шаг сразу завершается и ничего не меняет. Если
 * адреса нет: форма адреса, затем форма кода из письма ({@link VkEmailLogin}); платформа выпускает и проверяет код, расширение кодов не
 * знает. После верного кода адрес записывается в данные входа, и стандартные шаги потока создают учётную запись покупателя: адрес
 * подтверждён, потому что провайдер помечен как доверенный по адресу, а здесь адрес подтверждён кодом.
 *
 * <p>Адрес, занятый существующей учётной записью, не привязывается (гость доказал владение ящиком, но не доказал владение записью): вход
 * заканчивается нейтральным сообщением, событие остаётся в журнале Keycloak без адреса.
 *
 * <p>Состояние диалога живёт в сессии аутентификации (заметка с адресом), а не в странице: подменить адрес между страницами нельзя.
 */
final class VkEmailAuthenticator extends AbstractIdpAuthenticator {

    private static final String ADDRESS_NOTE = "dgm.vkemail.address";
    private static final Logger LOG = Logger.getLogger(VkEmailAuthenticator.class);

    @Override
    protected void authenticateImpl(AuthenticationFlowContext context, SerializedBrokeredIdentityContext serialized, BrokeredIdentityContext brokered) {
        String email = brokered.getEmail();
        if (email != null && !email.isBlank()) {
            context.success();
            return;
        }
        context.getAuthenticationSession().removeAuthNote(ADDRESS_NOTE);
        context.challenge(render(context, new VkEmailLogin.Step.AskEmail(null)));
    }

    @Override
    protected void actionImpl(AuthenticationFlowContext context, SerializedBrokeredIdentityContext serialized, BrokeredIdentityContext brokered) {
        AuthenticationSessionModel auth = context.getAuthenticationSession();
        MultivaluedMap<String, String> form = context.getHttpRequest().getDecodedFormParameters();
        String address = auth.getAuthNote(ADDRESS_NOTE);
        VkEmailLogin logic = new VkEmailLogin(PlatformConnection.get(), email -> taken(context, email));

        VkEmailLogin.Step step;
        if (form.containsKey("change") || address == null) {
            step = form.containsKey("change") ? new VkEmailLogin.Step.AskEmail(null) : logic.submitEmail(form.getFirst("email"));
        } else if (form.containsKey("resend")) {
            step = logic.resend(address);
        } else {
            step = logic.submitCode(address, form.getFirst("code"));
        }
        proceed(context, serialized, step);
    }

    private void proceed(AuthenticationFlowContext context, SerializedBrokeredIdentityContext serialized, VkEmailLogin.Step step) {
        AuthenticationSessionModel auth = context.getAuthenticationSession();
        switch (step) {
            case VkEmailLogin.Step.Done done -> {
                auth.removeAuthNote(ADDRESS_NOTE);
                serialized.setEmail(done.email());
                serialized.saveToAuthenticationSession(auth, BROKERED_CONTEXT_NOTE);
                context.getEvent().detail(Details.IDENTITY_PROVIDER, "vkid");
                context.success();
            }
            case VkEmailLogin.Step.Denied denied -> {
                auth.removeAuthNote(ADDRESS_NOTE);
                // В событии нет адреса: журнал Keycloak, как и аудит платформы, адрес открытым не хранит (ADR-014)
                context.getEvent().detail(Details.IDENTITY_PROVIDER, "vkid").error(Errors.EMAIL_IN_USE);
                LOG.warn("vk-email: адрес, подтверждённый кодом, занят существующей учётной записью, привязки нет");
                Response page = context.form().setError("vkEmailDenied").createErrorPage(Response.Status.FORBIDDEN);
                context.failure(AuthenticationFlowError.IDENTITY_PROVIDER_ERROR, page);
            }
            case VkEmailLogin.Step.AskEmail ask -> {
                auth.removeAuthNote(ADDRESS_NOTE);
                context.challenge(render(context, ask));
            }
            case VkEmailLogin.Step.AskCode ask -> {
                auth.setAuthNote(ADDRESS_NOTE, ask.email());
                context.challenge(render(context, ask));
            }
        }
    }

    /** Занят ли адрес учётной записью: адресом входа служит e-mail (realm {@code registrationEmailAsUsername}), поэтому смотрим оба поля. */
    private static boolean taken(AuthenticationFlowContext context, String email) {
        KeycloakSession session = context.getSession();
        RealmModel realm = context.getRealm();
        UserModel byEmail = session.users().getUserByEmail(realm, email);
        return byEmail != null || session.users().getUserByUsername(realm, email) != null;
    }

    private Response render(AuthenticationFlowContext context, VkEmailLogin.Step step) {
        LoginFormsProvider form = context.form();
        VkEmailLogin.Message message = switch (step) {
            case VkEmailLogin.Step.AskEmail ask -> ask.message();
            case VkEmailLogin.Step.AskCode ask -> ask.message();
            case VkEmailLogin.Step.Done done -> null;
            case VkEmailLogin.Step.Denied denied -> null;
        };
        if (message != null) {
            form.setError(message.key(), message.seconds() > 0 ? new Object[] {message.minutes()} : new Object[0]);
        }
        if (step instanceof VkEmailLogin.Step.AskCode ask) {
            form.setAttribute("address", ask.email());
            return form.createForm("vk-email-code.ftl");
        }
        return form.createForm("vk-email.ftl");
    }

    @Override
    public boolean requiresUser() {
        return false;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }
}
