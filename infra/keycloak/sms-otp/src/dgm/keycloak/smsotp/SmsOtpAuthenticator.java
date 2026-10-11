package dgm.keycloak.smsotp;

import dgm.keycloak.smsotp.core.SmsLogin;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.Authenticator;
import org.keycloak.events.Details;
import org.keycloak.events.Errors;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;

/**
 * Вход по коду из SMS (ADR-010, FT-1.7): две страницы, «номер» и «код». Самих кодов расширение не знает: номер и введённый код уходят
 * на {@code platform-service} ({@link SmsLogin}), платформа выпускает, проверяет, считает лимиты и отвечает одинаково для любого номера.
 * После верного кода Keycloak находит пользователя по идентификатору из ответа платформы и ставит заметку сессии
 * {@value #AUTH_LEVEL_NOTE}={@value #SMS_LEVEL}: по такой сессии клиенты с полным доступом SSO не принимают ({@link DgmCookieAuthenticator}).
 *
 * <p>Состояние диалога живёт в сессии аутентификации (заметка с номером), а не в странице: подменить номер между страницами нельзя.
 */
final class SmsOtpAuthenticator implements Authenticator {

    static final String AUTH_LEVEL_NOTE = "auth_level";
    static final String SMS_LEVEL = "sms";
    private static final String PHONE_NOTE = "dgm.sms.phone";
    private static final Logger LOG = Logger.getLogger(SmsOtpAuthenticator.class);

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        context.getAuthenticationSession().removeAuthNote(PHONE_NOTE);
        context.challenge(render(context, new SmsLogin.Step.AskPhone(null)));
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        AuthenticationSessionModel auth = context.getAuthenticationSession();
        MultivaluedMap<String, String> form = context.getHttpRequest().getDecodedFormParameters();
        String phone = auth.getAuthNote(PHONE_NOTE);
        SmsLogin logic = new SmsLogin(PlatformConnection.get());
        String ip = context.getConnection().getRemoteAddr();

        SmsLogin.Step step;
        if (form.containsKey("change") || phone == null) {
            step = form.containsKey("change") ? new SmsLogin.Step.AskPhone(null) : logic.submitPhone(form.getFirst("phone"), ip);
        } else if (form.containsKey("resend")) {
            step = logic.resend(phone, ip);
        } else {
            step = logic.submitCode(phone, form.getFirst("code"));
        }
        proceed(context, step);
    }

    private void proceed(AuthenticationFlowContext context, SmsLogin.Step step) {
        AuthenticationSessionModel auth = context.getAuthenticationSession();
        switch (step) {
            case SmsLogin.Step.Done done -> finish(context, done.userId());
            case SmsLogin.Step.AskPhone ask -> {
                auth.removeAuthNote(PHONE_NOTE);
                context.challenge(render(context, ask));
            }
            case SmsLogin.Step.AskCode ask -> {
                auth.setAuthNote(PHONE_NOTE, ask.phone());
                context.challenge(render(context, ask));
            }
        }
    }

    private void finish(AuthenticationFlowContext context, String userId) {
        RealmModel realm = context.getRealm();
        UserModel user = context.getSession().users().getUserById(realm, userId);
        if (user == null || !user.isEnabled()) {
            // Платформа приняла код, а в Keycloak пользователя нет или он отключён: для покупателя это обычный неверный код
            LOG.warnf("sms-otp: платформа вернула пользователя %s, которого нет в Keycloak или который отключён", userId);
            context.getEvent().error(Errors.USER_NOT_FOUND);
            proceed(context, new SmsLogin.Step.AskPhone(SmsLogin.Message.CODE_INVALID));
            return;
        }
        AuthenticationSessionModel auth = context.getAuthenticationSession();
        auth.removeAuthNote(PHONE_NOTE);
        auth.setUserSessionNote(AUTH_LEVEL_NOTE, SMS_LEVEL);
        context.setUser(user);
        context.getEvent().detail(Details.AUTH_METHOD, "sms");
        context.success();
    }

    private Response render(AuthenticationFlowContext context, SmsLogin.Step step) {
        LoginFormsProvider form = context.form();
        SmsLogin.Message message = switch (step) {
            case SmsLogin.Step.AskPhone ask -> ask.message();
            case SmsLogin.Step.AskCode ask -> ask.message();
            case SmsLogin.Step.Done done -> null;
        };
        if (message != null) {
            form.setError(message.key(), message.seconds() > 0 ? new Object[] {message.minutes()} : new Object[0]);
        }
        if (step instanceof SmsLogin.Step.AskCode ask) {
            form.setAttribute("phoneMasked", mask(ask.phone()));
            return form.createForm("sms-code.ftl");
        }
        return form.createForm("sms-phone.ftl");
    }

    /** {@code +79001234567} превращается в {@code +7900***4567}: на странице кода номер виден не целиком. */
    static String mask(String phone) {
        return phone.length() < 9 ? "***" : phone.substring(0, 5) + "***" + phone.substring(phone.length() - 4);
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
        // обязательных действий нет
    }

    @Override
    public void close() {
        // ресурсов нет
    }
}
