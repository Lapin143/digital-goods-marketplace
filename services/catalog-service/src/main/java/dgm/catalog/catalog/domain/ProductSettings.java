package dgm.catalog.catalog.domain;

import dgm.kit.config.ConfigRegistry;
import dgm.kit.config.ConfigValue;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Параметры сроков модуля {@code catalog}. Имя ключа то же, что у параметра платформы {@code platform_parameter} (Р-10) и у модуля
 * {@code sellers}: срок проверки один для заявок и для товаров. Значение из {@code application.yml} работает, пока слушатель
 * {@code platform.config} ничего не принёс; {@link #live} подключает его к реестру параметров, и новая подача получает срок из текущего
 * значения (правило 19 руководства среза 3): уже поданные заявки сохраняют срок, назначенный при подаче.
 */
public final class ProductSettings {

    public static final String REVIEW_KEY = "moderation.review-deadline-seconds";
    public static final long MIN_SECONDS = 1;
    public static final long MAX_SECONDS = 1_209_600;
    private static final long DEFAULT_SECONDS = 259_200;

    private final Supplier<Duration> reviewPeriod;

    /**
     * Неизменяемые настройки (значение из {@code application.yml} и тесты).
     *
     * @param reviewPeriod срок рассмотрения от каждой подачи, от 1 секунды до 14 суток (профиль {@code fast-time} ставит 10 секунд;
     *                     в платформе минимум 3600)
     */
    public ProductSettings(Duration reviewPeriod) {
        if (reviewPeriod == null || reviewPeriod.toSeconds() < MIN_SECONDS || reviewPeriod.toSeconds() > MAX_SECONDS) {
            throw new IllegalArgumentException("Параметр " + REVIEW_KEY + " должен быть от 1 до 1209600 секунд, а задано: " + reviewPeriod);
        }
        this.reviewPeriod = () -> reviewPeriod;
    }

    private ProductSettings(ConfigValue reviewPeriod) {
        this.reviewPeriod = reviewPeriod::seconds;
    }

    public static ProductSettings defaults() {
        return new ProductSettings(Duration.ofSeconds(DEFAULT_SECONDS));
    }

    /**
     * Живые настройки. Срок рассмотрения читает и модуль заявок продавцов: ключ общий, первый вызов регистрирует его, второй получает то же
     * значение (порядок создания компонентов не важен).
     */
    public static ProductSettings live(ConfigRegistry registry, ProductSettings initial) {
        return new ProductSettings(registry.obtain(REVIEW_KEY, initial.reviewPeriod().toSeconds(), MIN_SECONDS, MAX_SECONDS));
    }

    /** Срок рассмотрения от каждой подачи. */
    public Duration reviewPeriod() {
        return reviewPeriod.get();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ProductSettings that && reviewPeriod().equals(that.reviewPeriod());
    }

    @Override
    public int hashCode() {
        return reviewPeriod().hashCode();
    }

    @Override
    public String toString() {
        return "ProductSettings[reviewPeriod=" + reviewPeriod() + "]";
    }
}
