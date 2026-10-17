package dgm.catalog.sellers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dgm.catalog.sellers.domain.SellerSettings;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Параметры сроков читаются из настроек под именами параметров платформы; вне границ сервис не стартует. */
class SellersConfigurationTest {

    private final SellersConfiguration configuration = new SellersConfiguration();

    @Test
    void withoutPropertiesTheDocumentedDefaultsApply() {
        SellerSettings settings = configuration.sellerSettings(new MockEnvironment());
        assertEquals(SellerSettings.defaults(), settings);
    }

    @Test
    void propertiesAreReadInSeconds() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("seller.reapplication-pause-seconds", "7200")
                .withProperty("moderation.review-deadline-seconds", "10")
                .withProperty("seller.document-link-ttl-seconds", "120");
        SellerSettings settings = configuration.sellerSettings(environment);
        assertEquals(Duration.ofHours(2), settings.reapplicationPause());
        assertEquals(Duration.ofSeconds(10), settings.reviewPeriod());
        assertEquals(Duration.ofMinutes(2), settings.documentLinkTtl());
    }

    @Test
    void oneOverriddenValueLeavesTheOthersAtDefaults() {
        SellerSettings settings = configuration.sellerSettings(new MockEnvironment().withProperty("moderation.review-deadline-seconds", "10"));
        assertEquals(SellerSettings.defaults().reapplicationPause(), settings.reapplicationPause());
        assertEquals(Duration.ofSeconds(10), settings.reviewPeriod());
    }

    @Test
    void valueOutsideTheBoundsStopsTheStartup() {
        assertThrows(IllegalArgumentException.class,
                () -> configuration.sellerSettings(new MockEnvironment().withProperty("seller.reapplication-pause-seconds", "60")));
        assertThrows(IllegalArgumentException.class,
                () -> configuration.sellerSettings(new MockEnvironment().withProperty("seller.document-link-ttl-seconds", "3600")));
    }

    @Test
    void notANumberIsRefused() {
        assertThrows(RuntimeException.class,
                () -> configuration.sellerSettings(new MockEnvironment().withProperty("moderation.review-deadline-seconds", "три дня")));
    }
}
