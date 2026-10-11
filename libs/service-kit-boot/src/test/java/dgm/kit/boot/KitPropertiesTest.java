package dgm.kit.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Привязка настроек {@code dgm.*}: значения по умолчанию и настройки модулей базы. */
class KitPropertiesTest {

    private static KitProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bind("dgm", KitProperties.class).get();
    }

    @Test
    void minimalServiceGetsDefaults() {
        KitProperties p = bind(Map.of("dgm.service", "demo-service"));
        assertEquals("demo-service", p.service());
        assertEquals("dgm/routes.json", p.routes());
        assertEquals("dgm-api", p.security().jwt().audience());
        assertEquals("postgres", p.db().host());
        assertEquals(5432, p.db().port());
        assertTrue(p.db().migrate());
        assertEquals(List.of("classpath:db/migration"), p.db().locations());
        assertTrue(p.db().modules().isEmpty());
        assertNull(p.db().database());
        assertNull(p.kafka().bootstrap());
        assertTrue(p.outbox().enabled());
        assertTrue(p.consumer().enabled());
        assertTrue(p.probe().enabled());
        assertEquals(8081, p.probe().port());
    }

    @Test
    void bodyLimitIdempotencyRedisAndKeycloakDefaults() {
        KitProperties p = bind(Map.of("dgm.service", "x"));
        assertEquals(2L * 1024 * 1024, p.web().maxBody().toBytes());
        assertEquals(64L * 1024, p.web().webhookMaxBody().toBytes());
        assertEquals(Duration.ofSeconds(2), p.idempotency().waitForOther());
        assertEquals(Duration.ofSeconds(60), p.idempotency().abandonedAfter());
        assertNull(p.idempotency().module());
        assertNull(p.redis().host());
        assertEquals(6379, p.redis().port());
        assertEquals(Duration.ofMillis(500), p.redis().commandTimeout());
        assertEquals(Duration.ofSeconds(2), p.redis().connectTimeout());
        assertNull(p.keycloak().baseUrl());
        assertEquals("dgm", p.keycloak().realm());
        assertEquals("platform-service", p.keycloak().clientId());
        assertEquals("keycloak_client_platform", p.keycloak().clientSecret());
    }

    @Test
    void idempotencyRedisAndKeycloakAreConfigurable() {
        KitProperties p = bind(Map.of(
                "dgm.service", "platform-service",
                "dgm.web.max-body", "1MB",
                "dgm.web.webhook-max-body", "16KB",
                "dgm.idempotency.module", "support",
                "dgm.idempotency.wait-for-other", "500ms",
                "dgm.redis.host", "redis",
                "dgm.redis.user", "platform",
                "dgm.keycloak.base-url", "https://keycloak:8443/auth"));
        assertEquals(1024L * 1024, p.web().maxBody().toBytes());
        assertEquals(16L * 1024, p.web().webhookMaxBody().toBytes());
        assertEquals("support", p.idempotency().module());
        assertEquals(Duration.ofMillis(500), p.idempotency().waitForOther());
        assertEquals("redis", p.redis().host());
        assertEquals("platform", p.redis().user());
        assertEquals("https://keycloak:8443/auth", p.keycloak().baseUrl());
    }

    @Test
    void modulesKeepRolesAndPoolSizes() {
        KitProperties p = bind(Map.of(
                "dgm.service", "platform-service",
                "dgm.db.database", "platform_db",
                "dgm.db.migrator-role", "migrator_platform",
                "dgm.db.modules.identity.role", "app_identity",
                "dgm.db.modules.identity.pool-size", "1",
                "dgm.db.modules.audit-admin.role", "app_audit_admin"));
        assertEquals("platform_db", p.db().database());
        assertEquals("migrator_platform", p.db().migratorRole());
        assertEquals(2, p.db().modules().size());
        assertEquals("app_identity", p.db().modules().get("identity").role());
        assertEquals(1, p.db().modules().get("identity").poolSize());
        assertEquals("app_audit_admin", p.db().modules().get("audit-admin").role());
        assertEquals(4, p.db().modules().get("audit-admin").poolSize());
    }

    @Test
    void locationsCanBeExtendedForTestData() {
        KitProperties p = bind(Map.of("dgm.service", "x", "dgm.db.locations[0]", "classpath:db/migration", "dgm.db.locations[1]", "classpath:db/testdata"));
        assertEquals(List.of("classpath:db/migration", "classpath:db/testdata"), p.db().locations());
    }

    @Test
    void outboxAndConsumerCanBeSwitchedOff() {
        KitProperties p = bind(Map.of("dgm.service", "x", "dgm.outbox.enabled", "false", "dgm.consumer.enabled", "false", "dgm.probe.port", "0"));
        assertTrue(!p.outbox().enabled());
        assertTrue(!p.consumer().enabled());
        assertEquals(0, p.probe().port());
    }
}
