package dgm.platform.identity.otp.service;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.platform.identity.domain.Roles;
import dgm.platform.identity.domain.UserAccount;
import dgm.platform.identity.otp.domain.PhoneNumber;
import dgm.platform.identity.repository.UserAccountRepository;
import dgm.platform.identity.service.IdentityEvents;
import dgm.platform.identity.service.IdentityTransaction;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Вход по коду из SMS (OP-07, US-1.7, ADR-010): две внутренние операции, которые вызывает расширение Keycloak {@code sms-otp}.
 *
 * <p>Запрос кода нейтрален: на любой номер, привязан он или нет, приходит один и тот же ответ за одно и то же время (U12, ST-06). Для
 * привязанного номера покупателя выпускается код и уходит SMS, для остальных кладётся код-приманка, а ветка без отправки ждёт до границы
 * {@code minResponse} (800 мс), общей для обеих веток. Вход по SMS открыт только покупателям: у продавцов и сотрудников номер
 * не считается каналом входа, они входят паролем и вторым фактором.
 *
 * <p>Проверка кода возвращает учётную запись и пишет событие {@code user.sms-login} (письмо «Выполнен вход по коду из SMS», F9-1).
 * Состояние записи проверяется ещё раз при погашении: за 5 минут жизни кода её могли деактивировать.
 */
@Component
public final class SmsLoginService {

    private final UserAccountRepository accounts;
    private final IdentityTransaction tx;
    private final OtpService otp;
    private final ResponsePacer pacer;
    private final OtpProperties properties;
    private final Clock clock;

    public SmsLoginService(UserAccountRepository accounts, IdentityTransaction tx, OtpService otp, ResponsePacer pacer,
                           OtpProperties properties, Clock clock) {
        this.accounts = accounts;
        this.tx = tx;
        this.otp = otp;
        this.pacer = pacer;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Принимает запрос кода входа.
     *
     * @throws ProblemException 429 {@code otp-request-limited}; 503, если Redis недоступен
     */
    public void requestCode(PhoneNumber phone, Optional<String> ip) {
        Instant started = clock.instant();
        Optional<UUID> account = accounts.findByPhone(phone.value()).filter(SmsLoginService::eligible).map(UserAccount::id);
        otp.issueLogin(phone, ip, account);
        pacer.awaitUntil(started.plus(properties.minResponse()));
    }

    /**
     * Проверяет код входа.
     *
     * @return учётная запись, которая входит
     * @throws ProblemException 422 {@code otp-invalid} / {@code otp-expired}; 429 {@code otp-locked}
     */
    public UUID verify(PhoneNumber phone, String code) {
        UUID userId = otp.verifyLogin(phone, code);
        Optional<UserAccount> account = accounts.find(userId).filter(SmsLoginService::eligible);
        if (account.isEmpty()) {
            throw new ProblemException(ProblemType.OTP_INVALID, "Код неверен.");
        }
        tx.execute(() -> {
            tx.publish(IdentityEvents.USER_TOPIC, IdentityEvents.USER_AGGREGATE, userId.toString(), IdentityEvents.SMS_LOGIN, 1,
                    IdentityEvents.smsLogin(userId));
            return Boolean.TRUE;
        });
        return userId;
    }

    /** Вход по SMS разрешён покупателю с подтверждённым номером и активной, не анонимизированной записью. */
    static boolean eligible(UserAccount account) {
        return account.phoneConfirmed() && account.active() && !account.anonymized() && Roles.BUYER.equals(account.role());
    }
}
