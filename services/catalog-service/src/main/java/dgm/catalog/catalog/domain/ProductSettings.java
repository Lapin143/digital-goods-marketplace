package dgm.catalog.catalog.domain;

import java.time.Duration;

/**
 * Параметры сроков модуля {@code catalog}. Имя ключа то же, что у параметра платформы {@code platform_parameter} (Р-10) и у модуля
 * {@code sellers}: срок проверки один для заявок и для товаров. До слушателя {@code config.changed} (срез 3) значение читается из
 * {@code application.yml}.
 *
 * @param reviewPeriod срок рассмотрения от каждой подачи, {@code moderation.review-deadline-seconds}, от 1 секунды до 14 суток (профиль
 *                     {@code fast-time} ставит 10 секунд; в платформе минимум 3600)
 */
public record ProductSettings(Duration reviewPeriod) {

    public static final String REVIEW_KEY = "moderation.review-deadline-seconds";

    public ProductSettings {
        if (reviewPeriod == null || reviewPeriod.toSeconds() < 1 || reviewPeriod.toSeconds() > 1_209_600) {
            throw new IllegalArgumentException("Параметр " + REVIEW_KEY + " должен быть от 1 до 1209600 секунд, а задано: " + reviewPeriod);
        }
    }

    public static ProductSettings defaults() {
        return new ProductSettings(Duration.ofSeconds(259_200));
    }
}
