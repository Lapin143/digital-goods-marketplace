package dgm.catalog.sellers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dgm.catalog.catalog.domain.ProductSettings;
import dgm.catalog.sellers.domain.SellerSettings;
import dgm.kit.config.ConfigRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Параметры сроков читаются из настроек под именами параметров платформы; вне границ сервис не стартует. */
@Tag("TC-808")
class SellersConfigurationTest {

    private final SellersConfiguration configuration = new SellersConfiguration();

    private SellerSettings sellers(MockEnvironment environment) {
        return configuration.sellerSettings(environment, new ConfigRegistry(List.of(), new SimpleMeterRegistry()));
    }

    @Test
    void withoutPropertiesTheDocumentedDefaultsApply() {
        SellerSettings settings = sellers(new MockEnvironment());
        assertEquals(SellerSettings.defaults(), settings);
    }

    @Test
    void propertiesAreReadInSeconds() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("seller.reapplication-pause-seconds", "7200")
                .withProperty("moderation.review-deadline-seconds", "10")
                .withProperty("seller.document-link-ttl-seconds", "120");
        SellerSettings settings = sellers(environment);
        assertEquals(Duration.ofHours(2), settings.reapplicationPause());
        assertEquals(Duration.ofSeconds(10), settings.reviewPeriod());
        assertEquals(Duration.ofMinutes(2), settings.documentLinkTtl());
    }

    @Test
    void oneOverriddenValueLeavesTheOthersAtDefaults() {
        SellerSettings settings = sellers(new MockEnvironment().withProperty("moderation.review-deadline-seconds", "10"));
        assertEquals(SellerSettings.defaults().reapplicationPause(), settings.reapplicationPause());
        assertEquals(Duration.ofSeconds(10), settings.reviewPeriod());
    }

    @Test
    void valueOutsideTheBoundsStopsTheStartup() {
        assertThrows(IllegalArgumentException.class, () -> sellers(new MockEnvironment().withProperty("seller.reapplication-pause-seconds", "60")));
        assertThrows(IllegalArgumentException.class, () -> sellers(new MockEnvironment().withProperty("seller.document-link-ttl-seconds", "3600")));
    }

    @Test
    void notANumberIsRefused() {
        assertThrows(RuntimeException.class, () -> sellers(new MockEnvironment().withProperty("moderation.review-deadline-seconds", "три дня")));
    }

    @Test
    void allThreeValuesFollowTheRegistryAndOneKeyServesBothModules() {
        ConfigRegistry registry = new ConfigRegistry(List.of(), new SimpleMeterRegistry());
        SellerSettings sellers = configuration.sellerSettings(new MockEnvironment(), registry);
        ProductSettings products = ProductSettings.live(registry, ProductSettings.defaults());

        registry.apply("seller.reapplication-pause-seconds", 7200, 2);
        registry.apply("moderation.review-deadline-seconds", 10_800, 2);
        registry.apply("seller.document-link-ttl-seconds", 120, 2);

        assertEquals(Duration.ofHours(2), sellers.reapplicationPause());
        assertEquals(Duration.ofSeconds(10_800), sellers.reviewPeriod());
        assertEquals(Duration.ofSeconds(10_800), products.reviewPeriod(), "срок рассмотрения один для заявок и товаров");
        assertEquals(Duration.ofMinutes(2), sellers.documentLinkTtl());
    }
}
