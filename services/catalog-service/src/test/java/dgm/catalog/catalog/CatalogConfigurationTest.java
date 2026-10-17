package dgm.catalog.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dgm.catalog.catalog.domain.ProductSettings;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Срок проверки товара читается из настроек под именем параметра платформы; вне границ сервис не стартует. */
class CatalogConfigurationTest {

    private final CatalogConfiguration configuration = new CatalogConfiguration();

    @Test
    void withoutThePropertyThreeDaysApply() {
        assertEquals(Duration.ofDays(3), configuration.productSettings(new MockEnvironment()).reviewPeriod());
        assertEquals(ProductSettings.defaults(), configuration.productSettings(new MockEnvironment()));
    }

    @Test
    void thePropertyIsReadInSeconds() {
        ProductSettings settings = configuration.productSettings(new MockEnvironment().withProperty("moderation.review-deadline-seconds", "10"));
        assertEquals(Duration.ofSeconds(10), settings.reviewPeriod());
    }

    @Test
    void valuesOutsideTheBoundsStopTheStartup() {
        assertThrows(IllegalArgumentException.class,
                () -> configuration.productSettings(new MockEnvironment().withProperty("moderation.review-deadline-seconds", "0")));
        assertThrows(IllegalArgumentException.class,
                () -> configuration.productSettings(new MockEnvironment().withProperty("moderation.review-deadline-seconds", "1209601")));
    }

    @Test
    void notANumberIsRefused() {
        assertThrows(RuntimeException.class,
                () -> configuration.productSettings(new MockEnvironment().withProperty("moderation.review-deadline-seconds", "три дня")));
    }
}
