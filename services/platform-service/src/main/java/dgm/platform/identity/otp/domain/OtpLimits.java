package dgm.platform.identity.otp.domain;

import java.time.Duration;

/**
 * Пределы запросов кода (NFT-3.5, ADR-010). Значения приходят из параметров платформы (FT-11.1); по умолчанию те же, что в начальном засеве.
 *
 * @param perTenMinutes запросов за 10 минут на учётную запись и на номер
 * @param perDay        запросов за сутки на учётную запись и на номер
 * @param perIpHour     запросов за час с одного IP
 * @param smsDailyCap   общий потолок запросов кода за сутки (бюджет SMS)
 * @param lockSeconds   на сколько закрывается номер после трёх погашенных из-за попыток кодов за час
 */
public record OtpLimits(int perTenMinutes, int perDay, int perIpHour, int smsDailyCap, int lockSeconds) {

    public static final String KEY_PER_TEN_MINUTES = "otp.requests-per-10-minutes";
    public static final String KEY_PER_DAY = "otp.requests-per-day";
    public static final String KEY_PER_IP_HOUR = "otp.requests-per-hour-per-ip";
    public static final String KEY_SMS_DAILY_CAP = "sms.daily-cap";
    public static final String KEY_LOCK_SECONDS = "otp.phone-lock-seconds";

    public static final OtpLimits DEFAULTS = new OtpLimits(3, 10, 10, 1000, 3600);

    public static final Duration TEN_MINUTES = Duration.ofMinutes(10);
    public static final Duration DAY = Duration.ofHours(24);
    public static final Duration HOUR = Duration.ofHours(1);

    public Duration lock() {
        return Duration.ofSeconds(lockSeconds);
    }
}
