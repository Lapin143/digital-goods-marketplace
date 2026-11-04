package dgm.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Настройки шлюза: группа лимита с точкой в имени и сроки памяти о ключах. */
class GatewayPropertiesTest {

    @Test
    @Tag("TC-957")
    void aGroupWithADotInTheTableIsFoundBySettingWrittenWithADash() {
        GatewayProperties properties = new GatewayProperties();
        GatewayProperties.Limit keyed = new GatewayProperties.Limit();
        keyed.setKey("api-key");
        properties.getLimits().put("seller-api", keyed);
        GatewayProperties.Limit plain = new GatewayProperties.Limit();
        properties.getLimits().put("seller", plain);

        assertSame(keyed, properties.limit("seller.api"), "в таблице маршрутов группа называется seller.api, в настройках seller-api");
        assertSame(keyed, properties.limit("seller-api"));
        assertSame(plain, properties.limit("seller"), "группа без точки читается как есть");
        assertNull(properties.limit("seller.upload"));
        assertEquals("seller-api", GatewayProperties.configKey("seller.api"));
        assertEquals("seller-upload", GatewayProperties.configKey("seller-upload"));
    }

    @Test
    @Tag("TC-956")
    void theDefaultsOfTheKeyMemoryAreThirtyAndFiveSeconds() {
        GatewayProperties properties = new GatewayProperties();
        assertEquals(30, properties.getApiKeyCacheSeconds());
        assertEquals(5, properties.getApiKeyNegativeCacheSeconds());
        assertEquals(java.time.Duration.ofSeconds(2), properties.getApiKeyCheckTimeout());
    }
}
