package dgm.platform.identity.otp.service;

import dgm.kit.id.Uuids;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.time.Timestamps;
import dgm.platform.auditadmin.api.PlatformParameters;
import dgm.platform.identity.otp.crypto.OtpCrypto;
import dgm.platform.identity.otp.domain.OtpLimits;
import dgm.platform.identity.otp.domain.OtpPurpose;
import dgm.platform.identity.otp.domain.PhoneNumber;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Одноразовые коды (компонент {@code otp-service}, ADR-010): выпуск, ограничения частоты и неделимая проверка с погашением. Код живёт
 * только в Redis как HMAC; в базе номер и признаки. Правила кода: 6 цифр, срок 5 минут, 5 попыток ({@link OtpProperties}); лимиты запросов и
 * потолок SMS берутся из параметров платформы ({@link PlatformParameters}). Если Redis недоступен, любой выпуск и проверка отвечают 503 и
 * SMS не уходит: безопаснее отказать, чем отправлять сообщения без счётчиков.
 *
 * <p>Ни код, ни номер, ни адрес IP в журнал, метрики и тексты ошибок не попадают.
 */
@Component
public final class OtpService {

    private static final Logger LOG = LoggerFactory.getLogger(OtpService.class);
    private static final String PREFIX = "platform:otp:";
    private static final int SMS_RETRY_AFTER_SECONDS = 30;

    private final OtpStore store;
    private final OtpCrypto crypto;
    private final CodeSource codes;
    private final SmsSender sms;
    private final PlatformParameters parameters;
    private final OtpProperties properties;
    private final Clock clock;
    private final MeterRegistry meters;

    public OtpService(OtpStore store, OtpCrypto crypto, CodeSource codes, SmsSender sms, PlatformParameters parameters,
                      OtpProperties properties, Clock clock, MeterRegistry meters) {
        this.store = store;
        this.crypto = crypto;
        this.codes = codes;
        this.sms = sms;
        this.parameters = parameters;
        this.properties = properties;
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * Выпущенный код подтверждения телефона.
     *
     * @param confirmationId идентификатор запроса подтверждения
     * @param expiresAt      до какого момента код действует
     * @param attempts       попыток ввода
     * @param resendAt       когда можно запросить новый код; пусто, если можно сразу (лимит окна не исчерпан этим запросом)
     */
    public record Issued(UUID confirmationId, Instant expiresAt, int attempts, Optional<Instant> resendAt) {
    }

    /** Лимиты запросов из параметров платформы; недоступные параметры заменяются значениями начального засева. */
    public OtpLimits limits() {
        OtpLimits d = OtpLimits.DEFAULTS;
        return new OtpLimits(
                parameters.value(OtpLimits.KEY_PER_TEN_MINUTES, d.perTenMinutes()),
                parameters.value(OtpLimits.KEY_PER_DAY, d.perDay()),
                parameters.value(OtpLimits.KEY_PER_IP_HOUR, d.perIpHour()),
                parameters.value(OtpLimits.KEY_SMS_DAILY_CAP, d.smsDailyCap()),
                parameters.value(OtpLimits.KEY_LOCK_SECONDS, d.lockSeconds()));
    }

    // ---- подтверждение телефона ----

    /**
     * Допускает запрос кода подтверждения: лимиты учётной записи, номера, IP и общий потолок проверяются вместе, расходуются, только если
     * ни один не исчерпан.
     *
     * @throws ProblemException 429 {@code otp-request-limited} с {@code Retry-After}; 503, если Redis недоступен
     */
    public OtpStore.Admission admitConfirmation(UUID userId, PhoneNumber phone, Optional<String> ip) {
        OtpLimits limits = limits();
        List<OtpStore.Window> windows = new ArrayList<>();
        windows.add(OtpStore.Window.sliding(PREFIX + "rl:acct:" + userId + ":10m", OtpLimits.TEN_MINUTES, limits.perTenMinutes()));
        windows.add(OtpStore.Window.sliding(PREFIX + "rl:acct:" + userId + ":day", OtpLimits.DAY, limits.perDay()));
        addCommonWindows(windows, phone, ip, limits);
        return admit(OtpPurpose.PHONE_CONFIRM, windows);
    }

    /**
     * Выпускает код подтверждения и отправляет SMS. Прежний код этой учётной записи гасится заменой записи.
     *
     * @throws ProblemException 503, если SMS не принято провайдером (запись кода удалена, повторить можно)
     */
    public Issued issueConfirmation(UUID userId, PhoneNumber phone, OtpStore.Admission admission) {
        Instant now = clock.instant();
        String target = userId.toString();
        String code = codes.next();
        UUID confirmationId = Uuids.v7(clock);
        Instant expiresAt = now.plus(properties.ttl());
        String key = codeKey(OtpPurpose.PHONE_CONFIRM, target);
        store.put(key, new OtpStore.Code(crypto.codeHash(OtpPurpose.PHONE_CONFIRM, target, code), expiresAt, properties.attempts(),
                userId.toString(), confirmationId.toString(), phone.value()), properties.ttl());
        try {
            sms.send(phone, smsText(code));
            count(OtpPurpose.PHONE_CONFIRM, "sms_sent");
        } catch (SmsSendException e) {
            store.remove(key);
            count(OtpPurpose.PHONE_CONFIRM, "sms_failed");
            LOG.warn("SMS с кодом подтверждения не отправлено, учётная запись {}: {}", userId, e.getMessage());
            throw ProblemException.dependencyUnavailable("Не удалось отправить SMS, повторите запрос позже.", SMS_RETRY_AFTER_SECONDS);
        }
        Optional<Instant> resendAt = admission.remaining() > 0 ? Optional.empty() : Optional.of(now.plus(admission.resendAfter()));
        return new Issued(confirmationId, expiresAt, properties.attempts(), resendAt);
    }

    /**
     * Проверяет код подтверждения и гасит его. Возвращает номер, ожидавший подтверждения.
     *
     * @throws ProblemException 422 {@code otp-invalid} (с {@code attemptsLeft}) или {@code otp-expired}; 429 {@code otp-locked} после
     *                          пятой неудачи; 404, если код относится к другому подтверждению
     */
    public PhoneNumber verifyConfirmation(UUID userId, UUID confirmationId, String code) {
        String target = userId.toString();
        OtpStore.Redemption redemption = store.redeem(new OtpStore.Redeem(codeKey(OtpPurpose.PHONE_CONFIRM, target),
                crypto.codeHash(OtpPurpose.PHONE_CONFIRM, target, code), confirmationId.toString(), Optional.empty()), clock.instant());
        count(OtpPurpose.PHONE_CONFIRM, "verify_" + redemption.result().name().toLowerCase(java.util.Locale.ROOT));
        return switch (redemption.result()) {
            case VALID -> PhoneNumber.parse(redemption.phone())
                    .orElseThrow(() -> new IllegalStateException("В записи кода подтверждения нет номера"));
            case INVALID -> throw new ProblemException(ProblemType.OTP_INVALID, "Код неверен.",
                    Map.of("attemptsLeft", redemption.attemptsLeft()), Map.of());
            case EXHAUSTED, LOCKED -> throw locked(properties.exhaustedRetryAfter());
            case EXPIRED, ABSENT -> throw expired();
            case OTHER -> throw new ProblemException(ProblemType.NOT_FOUND, "Запрос подтверждения не найден.");
        };
    }

    // ---- вход по коду из SMS ----

    /**
     * Принимает запрос кода входа. Для привязанного подтверждённого номера выпускает код и отправляет SMS; для остальных кладёт код-приманку:
     * запись, которую нельзя погасить успешно, но которая ведёт себя как настоящая (попытки, срок, закрытие номера), поэтому ответы на
     * проверку не выдают, есть ли у номера учётная запись (ST-06). Запрос учитывается в лимитах номера и IP в обеих ветках.
     *
     * @param account учётная запись, которой принадлежит номер и которой разрешён вход по SMS; пусто для всех остальных случаев
     * @throws ProblemException 429 {@code otp-request-limited}, если лимит исчерпан или номер закрыт; 503, если Redis недоступен
     */
    public void issueLogin(PhoneNumber phone, Optional<String> ip, Optional<UUID> account) {
        Instant now = clock.instant();
        String phoneTarget = crypto.target("phone", phone.value());
        store.lockedFor(lockKey(phoneTarget), now).ifPresent(wait -> {
            throw limited(wait);
        });
        OtpLimits limits = limits();
        List<OtpStore.Window> windows = new ArrayList<>();
        addCommonWindows(windows, phone, ip, limits);
        admit(OtpPurpose.SMS_LOGIN, windows);

        String key = codeKey(OtpPurpose.SMS_LOGIN, phoneTarget);
        Instant expiresAt = now.plus(properties.ttl());
        Duration retention = properties.ttl().plus(properties.expiredGrace());
        if (account.isEmpty()) {
            store.put(key, new OtpStore.Code(crypto.target("decoy", UUID.randomUUID().toString()), expiresAt, properties.attempts(), "", "", ""),
                    retention);
            count(OtpPurpose.SMS_LOGIN, "decoy");
            return;
        }
        String code = codes.next();
        store.put(key, new OtpStore.Code(crypto.codeHash(OtpPurpose.SMS_LOGIN, phoneTarget, code), expiresAt, properties.attempts(),
                account.get().toString(), "", ""), retention);
        try {
            sms.send(phone, smsText(code));
            count(OtpPurpose.SMS_LOGIN, "sms_sent");
        } catch (SmsSendException e) {
            // Ответ нейтрален и при сбое провайдера: иначе отказ выдал бы, что номер привязан. Покупатель запросит код ещё раз
            count(OtpPurpose.SMS_LOGIN, "sms_failed");
            LOG.warn("SMS с кодом входа не отправлено, учётная запись {}: {}", account.get(), e.getMessage());
        }
    }

    /**
     * Проверяет код входа и гасит его. Неверный код и код для номера без учётной записи неотличимы.
     *
     * @return учётная запись, для которой выпущен код
     * @throws ProblemException 422 {@code otp-invalid} или {@code otp-expired}; 429 {@code otp-locked}, если попытки кончились или номер закрыт
     */
    public UUID verifyLogin(PhoneNumber phone, String code) {
        String phoneTarget = crypto.target("phone", phone.value());
        OtpLimits limits = limits();
        OtpStore.Tracking tracking = new OtpStore.Tracking(PREFIX + "burn:" + phoneTarget, lockKey(phoneTarget), properties.burnLimit(),
                properties.burnWindow(), limits.lock());
        OtpStore.Redemption redemption = store.redeem(new OtpStore.Redeem(codeKey(OtpPurpose.SMS_LOGIN, phoneTarget),
                crypto.codeHash(OtpPurpose.SMS_LOGIN, phoneTarget, code), "", Optional.of(tracking)), clock.instant());
        count(OtpPurpose.SMS_LOGIN, "verify_" + redemption.result().name().toLowerCase(java.util.Locale.ROOT));
        return switch (redemption.result()) {
            case VALID -> UUID.fromString(redemption.userId());
            case INVALID, ABSENT, OTHER -> throw new ProblemException(ProblemType.OTP_INVALID, "Код неверен.");
            case EXPIRED -> throw expired();
            case EXHAUSTED -> throw locked(redemption.lockedFor().isZero() ? properties.exhaustedRetryAfter() : redemption.lockedFor());
            case LOCKED -> throw locked(redemption.lockedFor());
        };
    }

    // ---- общее ----

    private void addCommonWindows(List<OtpStore.Window> windows, PhoneNumber phone, Optional<String> ip, OtpLimits limits) {
        String phoneTarget = crypto.target("phone", phone.value());
        windows.add(OtpStore.Window.sliding(PREFIX + "rl:phone:" + phoneTarget + ":10m", OtpLimits.TEN_MINUTES, limits.perTenMinutes()));
        windows.add(OtpStore.Window.sliding(PREFIX + "rl:phone:" + phoneTarget + ":day", OtpLimits.DAY, limits.perDay()));
        ip.ifPresent(address -> windows.add(OtpStore.Window.sliding(PREFIX + "rl:ip:" + crypto.target("ip", address) + ":hour",
                OtpLimits.HOUR, limits.perIpHour())));
        // Потолок считает запросы кода, а не доставленные SMS: так он не зависит от того, привязан ли номер (ответы одинаковы)
        windows.add(OtpStore.Window.counter(PREFIX + "cap", OtpLimits.DAY, limits.smsDailyCap()));
    }

    private OtpStore.Admission admit(OtpPurpose purpose, List<OtpStore.Window> windows) {
        OtpStore.Admission admission = store.admit(windows, clock.instant());
        if (!admission.allowed()) {
            count(purpose, "request_limited");
            throw limited(admission.retryAfter());
        }
        return admission;
    }

    private ProblemException limited(Duration wait) {
        return new ProblemException(ProblemType.OTP_REQUEST_LIMITED, "Слишком много запросов кода, повторите позже.",
                Map.of("retryAfterAt", Timestamps.format(clock.instant().plus(wait))), Map.of("Retry-After", Long.toString(seconds(wait))));
    }

    private ProblemException locked(Duration wait) {
        return new ProblemException(ProblemType.OTP_LOCKED, "Исчерпаны попытки ввода кода, повторите позже.",
                Map.of("retryAfterAt", Timestamps.format(clock.instant().plus(wait))), Map.of("Retry-After", Long.toString(seconds(wait))));
    }

    private static ProblemException expired() {
        return new ProblemException(ProblemType.OTP_EXPIRED, "Срок кода истёк, запросите новый.");
    }

    /** Секунды для {@code Retry-After}: округление вверх, не меньше одной. */
    static long seconds(Duration wait) {
        return Math.max(1, (wait.toMillis() + 999) / 1000);
    }

    private static String codeKey(OtpPurpose purpose, String target) {
        return PREFIX + "code:" + purpose.code() + ":" + target;
    }

    private static String lockKey(String phoneTarget) {
        return PREFIX + "lock:" + phoneTarget;
    }

    /** Текст SMS: только код и название сервиса с предупреждением, персональных данных нет (ADR-010). */
    static String smsText(String code) {
        return "Маркетплейс цифровых товаров: код " + code + ". Никому не сообщайте его.";
    }

    private void count(OtpPurpose purpose, String event) {
        meters.counter("otp_events_total", "purpose", purpose.code(), "event", event).increment();
    }
}
