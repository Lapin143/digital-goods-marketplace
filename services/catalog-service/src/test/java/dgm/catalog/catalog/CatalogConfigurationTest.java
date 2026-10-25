package dgm.catalog.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dgm.catalog.catalog.domain.CacheSettings;
import dgm.catalog.catalog.domain.ProductSettings;
import dgm.kit.config.ConfigRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Срок проверки товара и срок кэша читаются из настроек под именами параметров платформы; вне границ сервис не стартует. */
@Tag("TC-808")
class CatalogConfigurationTest {

    private final CatalogConfiguration configuration = new CatalogConfiguration();
    private final ConfigRegistry registry = new ConfigRegistry(List.of(), new SimpleMeterRegistry());

    private ProductSettings product(MockEnvironment environment) {
        return configuration.productSettings(environment, new ConfigRegistry(List.of(), new SimpleMeterRegistry()));
    }

    @Test
    void withoutThePropertyThreeDaysApply() {
        assertEquals(Duration.ofDays(3), product(new MockEnvironment()).reviewPeriod());
        assertEquals(ProductSettings.defaults(), product(new MockEnvironment()));
    }

    @Test
    void thePropertyIsReadInSeconds() {
        ProductSettings settings = product(new MockEnvironment().withProperty("moderation.review-deadline-seconds", "10"));
        assertEquals(Duration.ofSeconds(10), settings.reviewPeriod());
    }

    @Test
    void valuesOutsideTheBoundsStopTheStartup() {
        assertThrows(IllegalArgumentException.class, () -> product(new MockEnvironment().withProperty("moderation.review-deadline-seconds", "0")));
        assertThrows(IllegalArgumentException.class, () -> product(new MockEnvironment().withProperty("moderation.review-deadline-seconds", "1209601")));
    }

    @Test
    void notANumberIsRefused() {
        assertThrows(RuntimeException.class, () -> product(new MockEnvironment().withProperty("moderation.review-deadline-seconds", "три дня")));
    }

    @Test
    void theReviewPeriodFollowsTheRegistryAndTheCardCacheTooButTheyKeepTheirOwnBounds() {
        ProductSettings settings = configuration.productSettings(new MockEnvironment(), registry);
        CacheSettings cache = configuration.cacheSettings(new MockEnvironment(), registry);
        assertEquals(Duration.ofDays(3), settings.reviewPeriod());
        assertEquals(Duration.ofSeconds(60), cache.productTtl(), "по бюджетам времени и по начальному значению платформы");

        registry.apply("moderation.review-deadline-seconds", 7200, 2);
        registry.apply("catalog.cache-ttl-seconds", 15, 2);
        assertEquals(Duration.ofHours(2), settings.reviewPeriod());
        assertEquals(Duration.ofSeconds(15), cache.productTtl());

        registry.apply("catalog.cache-ttl-seconds", 4000, 3);
        assertEquals(Duration.ofSeconds(15), cache.productTtl(), "значение вне границ не применяется");
    }

    @Test
    void theCacheTtlIsReadUnderTheKeyOfThePlatformParameter() {
        assertEquals("catalog.cache-ttl-seconds", CacheSettings.PRODUCT_TTL_KEY);
        CacheSettings cache = configuration.cacheSettings(new MockEnvironment().withProperty("catalog.cache-ttl-seconds", "20"), registry);
        assertEquals(Duration.ofSeconds(20), cache.productTtl());
        assertThrows(IllegalArgumentException.class,
                () -> configuration.cacheSettings(new MockEnvironment().withProperty("catalog.cache-ttl-seconds", "3601"), registry));
    }

    @Test
    void theReviewPeriodKeyMayBeRequestedTwiceAndBothReadersSeeTheSameValue() {
        ProductSettings first = configuration.productSettings(new MockEnvironment(), registry);
        ProductSettings second = configuration.productSettings(new MockEnvironment(), registry);
        registry.apply("moderation.review-deadline-seconds", 7200, 2);
        assertEquals(Duration.ofHours(2), first.reviewPeriod());
        assertEquals(Duration.ofHours(2), second.reviewPeriod());
    }

    @Test
    void theSecondRegistrationWithAnotherInitialValueIsRefused() {
        configuration.productSettings(new MockEnvironment(), registry);
        assertThrows(IllegalStateException.class,
                () -> configuration.productSettings(new MockEnvironment().withProperty("moderation.review-deadline-seconds", "10"), registry));
    }
}
