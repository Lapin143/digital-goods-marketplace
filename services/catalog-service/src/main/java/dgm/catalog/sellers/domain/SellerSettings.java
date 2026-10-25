package dgm.catalog.sellers.domain;

import dgm.kit.config.ConfigRegistry;
import dgm.kit.config.ConfigValue;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Параметры сроков модуля. Имена ключей те же, что у параметров платформы {@code platform_parameter} (Р-10). Значения из
 * {@code application.yml} работают, пока слушатель {@code platform.config} ничего не принёс; {@link #live} подключает их к реестру
 * параметров, и каждое обращение читает текущее значение (правило 19 руководства среза 3). Срок, назначенный заявке при подаче, после
 * смены параметра не пересчитывается.
 */
public final class SellerSettings {

    public static final String PAUSE_KEY = "seller.reapplication-pause-seconds";
    public static final String REVIEW_KEY = "moderation.review-deadline-seconds";
    public static final String LINK_TTL_KEY = "seller.document-link-ttl-seconds";

    private static final long PAUSE_MIN = 3600;
    private static final long PAUSE_MAX = 604_800;
    private static final long REVIEW_MIN = 1;
    private static final long REVIEW_MAX = 1_209_600;
    private static final long LINK_MIN = 60;
    private static final long LINK_MAX = 900;

    private final Supplier<Duration> reapplicationPause;
    private final Supplier<Duration> reviewPeriod;
    private final Supplier<Duration> documentLinkTtl;

    /**
     * Неизменяемые настройки (значения из {@code application.yml} и тесты).
     *
     * @param reapplicationPause пауза после отказа перед новой заявкой, {@code seller.reapplication-pause-seconds}, 3600…604800 секунд
     * @param reviewPeriod       срок рассмотрения от каждой подачи, {@code moderation.review-deadline-seconds}, от 1 секунды (профиль
     *                           {@code fast-time} ставит 10 секунд; в платформе минимум 3600)
     * @param documentLinkTtl    срок ссылки на документ, {@code seller.document-link-ttl-seconds}, 60…900 секунд
     */
    public SellerSettings(Duration reapplicationPause, Duration reviewPeriod, Duration documentLinkTtl) {
        require(PAUSE_KEY, reapplicationPause, PAUSE_MIN, PAUSE_MAX);
        require(REVIEW_KEY, reviewPeriod, REVIEW_MIN, REVIEW_MAX);
        require(LINK_TTL_KEY, documentLinkTtl, LINK_MIN, LINK_MAX);
        this.reapplicationPause = () -> reapplicationPause;
        this.reviewPeriod = () -> reviewPeriod;
        this.documentLinkTtl = () -> documentLinkTtl;
    }

    private SellerSettings(ConfigValue reapplicationPause, ConfigValue reviewPeriod, ConfigValue documentLinkTtl) {
        this.reapplicationPause = reapplicationPause::seconds;
        this.reviewPeriod = reviewPeriod::seconds;
        this.documentLinkTtl = documentLinkTtl::seconds;
    }

    public static SellerSettings defaults() {
        return new SellerSettings(Duration.ofSeconds(86_400), Duration.ofSeconds(259_200), Duration.ofSeconds(300));
    }

    /**
     * Живые настройки. Срок рассмотрения общий с модулем товаров: ключ получается через {@code obtain}, порядок создания компонентов не
     * важен.
     */
    public static SellerSettings live(ConfigRegistry registry, SellerSettings initial) {
        ConfigValue pause = registry.register(PAUSE_KEY, initial.reapplicationPause().toSeconds(), PAUSE_MIN, PAUSE_MAX);
        ConfigValue review = registry.obtain(REVIEW_KEY, initial.reviewPeriod().toSeconds(), REVIEW_MIN, REVIEW_MAX);
        ConfigValue link = registry.register(LINK_TTL_KEY, initial.documentLinkTtl().toSeconds(), LINK_MIN, LINK_MAX);
        return new SellerSettings(pause, review, link);
    }

    /** Пауза после отказа перед новой заявкой. */
    public Duration reapplicationPause() {
        return reapplicationPause.get();
    }

    /** Срок рассмотрения от каждой подачи. */
    public Duration reviewPeriod() {
        return reviewPeriod.get();
    }

    /** Срок подписанной ссылки на документ. */
    public Duration documentLinkTtl() {
        return documentLinkTtl.get();
    }

    private static void require(String key, Duration value, long minSeconds, long maxSeconds) {
        if (value == null || value.toSeconds() < minSeconds || value.toSeconds() > maxSeconds) {
            throw new IllegalArgumentException("Параметр " + key + " должен быть от " + minSeconds + " до " + maxSeconds + " секунд, а задано: " + value);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SellerSettings that && reapplicationPause().equals(that.reapplicationPause())
                && reviewPeriod().equals(that.reviewPeriod()) && documentLinkTtl().equals(that.documentLinkTtl());
    }

    @Override
    public int hashCode() {
        return Objects.hash(reapplicationPause(), reviewPeriod(), documentLinkTtl());
    }

    @Override
    public String toString() {
        return "SellerSettings[reapplicationPause=" + reapplicationPause() + ", reviewPeriod=" + reviewPeriod() + ", documentLinkTtl="
                + documentLinkTtl() + "]";
    }
}
