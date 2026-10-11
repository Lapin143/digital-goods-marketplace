package dgm.keycloak.smsotp;

import dgm.keycloak.smsotp.core.RegistrationInput;
import dgm.keycloak.smsotp.core.TimePad;
import jakarta.ws.rs.core.MultivaluedMap;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationProcessor;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.actiontoken.verifyemail.VerifyEmailActionToken;
import org.keycloak.common.util.Time;
import org.keycloak.credential.hash.PasswordHashProvider;
import org.keycloak.email.EmailException;
import org.keycloak.email.EmailTemplateProvider;
import org.keycloak.events.Details;
import org.keycloak.events.Errors;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.PasswordPolicy;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserCredentialModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.FormMessage;
import org.keycloak.policy.PasswordPolicyManagerProvider;
import org.keycloak.policy.PolicyError;
import org.keycloak.services.Urls;
import org.keycloak.sessions.AuthenticationSessionCompoundId;
import org.keycloak.sessions.AuthenticationSessionModel;

/**
 * Регистрация покупателя с нейтральным ответом (решение Р-8, US-01, ST-06). Стандартная форма Keycloak на занятый e-mail отвечает
 * «адрес уже существует»: по такому ответу можно перебирать адреса и узнавать, кто зарегистрирован. Здесь ответ на любой допустимый
 * адрес один: страница «проверьте почту».
 *
 * <ul>
 *   <li>Адрес свободен: создаётся учётная запись (роль по умолчанию buyer, адрес не подтверждён), владельцу уходит стандартное письмо
 *       с ссылкой подтверждения.</li>
 *   <li>Адрес занят: ничего не создаётся, владельцу уходит письмо «кто-то пытался зарегистрироваться на ваш адрес» с подсказкой, как войти
 *       или сменить пароль. В журнале событий остаётся REGISTER_ERROR, причина email_in_use: тем, кто смотрит журнал, различие видно.</li>
 * </ul>
 *
 * <p>Страница не различает ветки ни текстом, ни адресом форм. По времени ответ выравнивается до {@link #MINIMUM}; в ветке «занят» пароль
 * хешируется так же, как в ветке «свободен», чтобы объём работы совпадал. Форматы полей и политика паролей проверяются до проверки
 * адреса и одинаково для всех. Число писем на один адрес здесь не считается (находка F4-1: частоту по IP ограничивает шлюз, счётчик по
 * адресу вынесен в срез 3).
 */
final class NeutralRegistrationAuthenticator implements Authenticator {

    /** Минимальная длительность обработки отправленной формы (хеш Argon2 и письмо укладываются с большим запасом). */
    static final Duration MINIMUM = Duration.ofMillis(1200);

    private static final Logger LOG = Logger.getLogger(NeutralRegistrationAuthenticator.class);

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        context.challenge(context.form().setAttribute("email", "").createForm("register-neutral.ftl"));
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        long started = System.nanoTime();
        MultivaluedMap<String, String> form = context.getHttpRequest().getDecodedFormParameters();
        String password = form.getFirst("password");
        RegistrationInput.Result input = RegistrationInput.check(form.getFirst("email"), password, form.getFirst("password-confirm"));
        List<FormMessage> errors = new ArrayList<>();
        input.problems().forEach(p -> errors.add(new FormMessage(p.field(), p.messageKey())));
        if (input.valid()) {
            PolicyError policy = context.getSession().getProvider(PasswordPolicyManagerProvider.class).validate(input.email(), password);
            if (policy != null) {
                errors.add(new FormMessage("password", policy.getMessage(), policy.getParameters()));
            }
        }
        if (!errors.isEmpty()) {
            LoginFormsProvider page = context.form().setAttribute("email", input.email()).setErrors(errors);
            context.challenge(page.createForm("register-neutral.ftl"));
            return;
        }

        try {
            UserModel existing = context.getSession().users().getUserByEmail(context.getRealm(), input.email());
            if (existing == null) {
                existing = context.getSession().users().getUserByUsername(context.getRealm(), input.email());
            }
            if (existing == null) {
                register(context, input.email(), password);
            } else {
                notifyOwner(context, existing, password);
            }
        } catch (RuntimeException | EmailException e) {
            // Покупателю ответ тот же: о сбое он не должен узнавать больше, чем о занятом адресе. Подробности только в журнале сервера
            LOG.errorf(e, "register-neutral: обработка регистрации не завершена (%s)", e.getClass().getSimpleName());
        }
        TimePad.padTo(started, MINIMUM);
        context.challenge(context.form().createForm("register-sent.ftl"));
    }

    private void register(AuthenticationFlowContext context, String email, String password) throws EmailException {
        KeycloakSession session = context.getSession();
        RealmModel realm = context.getRealm();
        UserModel user = session.users().addUser(realm, email);
        user.setEnabled(true);
        user.setEmail(email);
        user.setEmailVerified(false);
        user.credentialManager().updateCredential(UserCredentialModel.password(password, false));
        context.getEvent().user(user).detail(Details.REGISTER_METHOD, "form").detail(Details.EMAIL, email).detail(Details.USERNAME, email).success();
        sendVerification(context, user);
    }

    private void sendVerification(AuthenticationFlowContext context, UserModel user) throws EmailException {
        KeycloakSession session = context.getSession();
        RealmModel realm = context.getRealm();
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        int lifespan = realm.getActionTokenGeneratedByUserLifespan(VerifyEmailActionToken.TOKEN_TYPE);
        String sessionId = AuthenticationSessionCompoundId.fromAuthSession(authSession).getEncodedId();
        String clientId = authSession.getClient().getClientId();
        VerifyEmailActionToken token = new VerifyEmailActionToken(user.getId(), (int) Time.currentTimeSeconds() + lifespan, sessionId, user.getEmail(), clientId);
        String link = Urls.actionTokenBuilder(session.getContext().getUri().getBaseUri(),
                        token.serialize(session, realm, session.getContext().getUri()), clientId, authSession.getTabId(),
                        AuthenticationProcessor.getClientData(session, authSession))
                .build(realm.getName()).toString();
        session.getProvider(EmailTemplateProvider.class).setAuthenticationSession(authSession).setRealm(realm).setUser(user)
                .sendVerifyEmail(link, TimeUnit.SECONDS.toMinutes(lifespan));
    }

    private void notifyOwner(AuthenticationFlowContext context, UserModel owner, String password) throws EmailException {
        KeycloakSession session = context.getSession();
        RealmModel realm = context.getRealm();
        // Тот же объём работы, что при создании учётной записи: хеш пароля считается и здесь (результат не сохраняется)
        PasswordPolicy policy = realm.getPasswordPolicy();
        session.getProvider(PasswordHashProvider.class, policy.getHashAlgorithm()).encodedCredential(password, policy.getHashIterations());
        context.getEvent().detail(Details.EMAIL, owner.getEmail()).error(Errors.EMAIL_IN_USE);
        String origin = System.getenv().getOrDefault("DGM_PUBLIC_ORIGIN", "https://localhost:8443");
        session.getProvider(EmailTemplateProvider.class).setRealm(realm).setUser(owner)
                .send("registerDuplicateSubject", "register-duplicate.ftl", new HashMap<>(Map.of("loginLink", origin + "/")));
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
        // обязательных действий нет: подтверждение адреса идёт по ссылке из письма
    }

    @Override
    public void close() {
        // ресурсов нет
    }
}
