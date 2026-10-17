package dgm.catalog.sellers.domain;

import java.time.Duration;

/**
 * Параметры сроков модуля. Имена ключей те же, что у параметров платформы {@code platform_parameter} (Р-10): до слушателя
 * {@code config.changed} (срез 3) значения читаются из {@code application.yml}.
 *
 * @param reapplicationPause пауза после отказа перед новой заявкой, {@code seller.reapplication-pause-seconds}, 3600…604800 секунд
 * @param reviewPeriod       срок рассмотрения от каждой подачи, {@code moderation.review-deadline-seconds}, от 1 секунды (профиль
 *                           {@code fast-time} ставит 10 секунд; в платформе минимум 3600)
 * @param documentLinkTtl    срок ссылки на документ, {@code seller.document-link-ttl-seconds}, 60…900 секунд
 */
public record SellerSettings(Duration reapplicationPause, Duration reviewPeriod, Duration documentLinkTtl) {

    public static final String PAUSE_KEY = "seller.reapplication-pause-seconds";
    public static final String REVIEW_KEY = "moderation.review-deadline-seconds";
    public static final String LINK_TTL_KEY = "seller.document-link-ttl-seconds";

    public SellerSettings {
        require(PAUSE_KEY, reapplicationPause, 3600, 604_800);
        require(REVIEW_KEY, reviewPeriod, 1, 1_209_600);
        require(LINK_TTL_KEY, documentLinkTtl, 60, 900);
    }

    public static SellerSettings defaults() {
        return new SellerSettings(Duration.ofSeconds(86_400), Duration.ofSeconds(259_200), Duration.ofSeconds(300));
    }

    private static void require(String key, Duration value, long minSeconds, long maxSeconds) {
        if (value == null || value.toSeconds() < minSeconds || value.toSeconds() > maxSeconds) {
            throw new IllegalArgumentException("Параметр " + key + " должен быть от " + minSeconds + " до " + maxSeconds + " секунд, а задано: " + value);
        }
    }
}
