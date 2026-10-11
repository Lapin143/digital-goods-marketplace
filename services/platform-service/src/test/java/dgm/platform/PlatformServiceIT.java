package dgm.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.boot.ModuleDatabases;
import dgm.kit.boot.testing.StandHttp;
import dgm.kit.boot.testing.StandHttp.Response;
import dgm.kit.testing.TestTokens;
import dgm.platform.identity.service.IdentityReconciler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Платформенный сервис целиком на стенде (набор dev-kit): настоящие PostgreSQL (роль {@code app_identity}, миграции под ролью-мигратором),
 * Kafka и правила маршрутов, а Keycloak заменён заглушкой Admin REST в памяти ({@link KeycloakStub}). Проверяется шаг 5 Ф4: создание записи при
 * первом обращении (в том числе при одновременных запросах), роли, деактивация, анонимизация, порядок «база и Outbox, потом Keycloak»,
 * сверка (создаёт записи мимо создания при обращении и догоняет Keycloak после сбоя). Ответы складываются в build/contract-samples и
 * проверяются {@code check_contract.py}.
 */
@Tag("integration")
@SpringBootTest(classes = PlatformServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(PlatformServiceIT.TestBeans.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlatformServiceIT {

    private static final int PORT = StandHttp.freePort();
    private static final int MANAGEMENT = StandHttp.freePort();
    private static final TestTokens TOKENS = TestTokens.create(Clock.systemUTC());
    private static final KeycloakStub KEYCLOAK = startStub();

    private static StandHttp gateway;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        JwtDecoder jwtDecoder() {
            return StandHttp.decoderFor(TOKENS);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        StandHttp.register(registry, PORT, MANAGEMENT);
        registry.add("dgm.keycloak.base-url", KEYCLOAK::baseUrl);
        // Сверку в тестах запускает сам тест: расписание уходит за пределы прогона
        registry.add("dgm.identity.reconcile-interval", () -> "PT1H");
        registry.add("dgm.identity.reconcile-initial-delay", () -> "PT1H");
    }

    private static KeycloakStub startStub() {
        try {
            return new KeycloakStub();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @BeforeAll
    static void clients() {
        gateway = StandHttp.asService("api-gateway", PORT);
    }

    @AfterAll
    static void stopStub() {
        KEYCLOAK.close();
    }

    @Autowired
    private ModuleDatabases databases;
    @Autowired
    private IdentityReconciler reconciler;

    // ---- помощники ----

    private JdbcTemplate jdbc() {
        return databases.jdbc("identity");
    }

    private static String token(String subject, String role, String scope, String... amr) {
        return TOKENS.token().subject(subject).scopes(scope).roles(role).amr(amr).build();
    }

    private static String adminToken(String subject) {
        return token(subject, "admin", "staff.admin", "pwd", "otp");
    }

    private static Response get(String adminSubject, String path) throws IOException {
        return gateway.get(path, StandHttp.bearer(adminToken(adminSubject)));
    }

    private static Response post(String adminSubject, String path, String body, String key) throws IOException {
        return gateway.post(path, body, "Authorization", "Bearer " + adminToken(adminSubject), "Idempotency-Key", key);
    }

    private static Response post(String adminSubject, String path, String body) throws IOException {
        return post(adminSubject, path, body, UUID.randomUUID().toString());
    }

    private KeycloakStub.User newAdmin() {
        KeycloakStub.User admin = KEYCLOAK.add("admin", "Мария", "Админова");
        admin.otp = true;
        return admin;
    }

    /** Учётная запись пользователя из заглушки, созданная сверкой мимо создания при обращении. */
    private KeycloakStub.User provisioned(String role) {
        KeycloakStub.User user = KEYCLOAK.add(role, null, null);
        reconciler.runPass();
        assertEquals(1, count("select count(*) from identity.user_account where id = ?", UUID.fromString(user.id)));
        return user;
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc().queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private Map<String, Object> row(String id) {
        return jdbc().queryForMap("select email, name, role, has_2fa, status, anonymized, audit_salt is null as no_salt, deactivated_at is not null as has_deactivated "
                + "from identity.user_account where id = ?", UUID.fromString(id));
    }

    private List<String> events(String aggregateId) {
        return jdbc().queryForList("select event_type from public.outbox where aggregate_id = ? order by id", String.class, aggregateId);
    }

    private static String roleBody(String role) {
        return "{\"role\":\"" + role + "\"}";
    }

    private static String users(String id) {
        return "/api/v1/staff/users/" + id;
    }

    // ---- создание записи при первом обращении ----

    @Test
    @Order(1)
    void parallelFirstRequestsCreateOneAccountAndOneEvent() throws Exception {
        KeycloakStub.User admin = newAdmin();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                results.add(pool.submit(() -> get(admin.id, "/api/v1/staff/users?limit=5").status()));
            }
            for (Future<Integer> result : results) {
                assertEquals(200, result.get());
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, count("select count(*) from identity.user_account where id = ?", UUID.fromString(admin.id)));
        assertEquals(List.of("user.registered"), events(admin.id));
        Map<String, Object> row = row(admin.id);
        assertEquals("Мария Админова", row.get("name"));
        assertEquals("admin", row.get("role"));
        assertEquals(Boolean.TRUE, row.get("has_2fa"));
        assertEquals(admin.email, row.get("email"));
    }

    @Test
    @Order(2)
    void requestRefusedByTheRouteRulesCreatesNoAccount() throws IOException {
        KeycloakStub.User buyer = KEYCLOAK.add("buyer", "Иван", "Петров");

        Response response = gateway.get("/api/v1/staff/users", StandHttp.bearer(token(buyer.id, "buyer", "orders.read", "pwd")));

        assertEquals(403, response.status());
        assertEquals(0, count("select count(*) from identity.user_account where id = ?", UUID.fromString(buyer.id)));
    }

    @Test
    @Order(3)
    void userUnknownToKeycloakIsUnauthenticatedAndAnOutageGivesRetryableUnavailable() throws IOException {
        Response unknown = get(UUID.randomUUID().toString(), "/api/v1/staff/users");
        assertEquals(401, unknown.status());
        assertEquals("unauthenticated", unknown.json().get("code"));

        KeycloakStub.User admin = newAdmin();
        KEYCLOAK.down(true);
        try {
            Response outage = get(admin.id, "/api/v1/staff/users");
            assertEquals(503, outage.status());
            assertEquals("dependency-unavailable", outage.json().get("code"));
            assertEquals("5", outage.header("Retry-After"));
        } finally {
            KEYCLOAK.down(false);
        }
        assertEquals(0, count("select count(*) from identity.user_account where id = ?", UUID.fromString(admin.id)));
        assertEquals(200, get(admin.id, "/api/v1/staff/users").status());
    }

    // ---- сверка ----

    @Test
    @Order(4)
    void reconcilerCreatesAccountsThatBypassedFirstRequestCreation() {
        KeycloakStub.User moderator = KEYCLOAK.add("moderator", null, null);
        moderator.otp = true;
        KeycloakStub.User buyer = KEYCLOAK.add("buyer", "Иван", "Петров");
        buyer.federated = true;

        IdentityReconciler.Summary first = reconciler.runPass();

        assertTrue(first.created() >= 2, first.toString());
        Map<String, Object> staff = row(moderator.id);
        assertEquals("moderator", staff.get("role"));
        assertEquals(Boolean.TRUE, staff.get("has_2fa"));
        assertEquals("Иван Петров", row(buyer.id).get("name"));
        assertEquals("buyer", row(buyer.id).get("role"));
        assertEquals(List.of("user.registered"), events(moderator.id));
        assertEquals(List.of("user.registered"), events(buyer.id));
        String payload = jdbc().queryForObject("select payload::text from public.outbox where aggregate_id = ?", String.class, buyer.id);
        assertNotNull(payload);
        assertTrue(payload.contains("vk_id"), payload);
        assertFalse(payload.contains(buyer.email), "в событии нет адреса");

        IdentityReconciler.Summary second = reconciler.runPass();
        assertEquals(0, second.created());
        assertEquals(0, second.failed());
        assertEquals(List.of("user.registered"), events(moderator.id));
    }

    // ---- listStaffUsers ----

    @Test
    @Order(5)
    void listFiltersAndPaginatesNewestFirst() throws IOException {
        KeycloakStub.User admin = newAdmin();
        List<KeycloakStub.User> moderators = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            KeycloakStub.User user = provisioned("buyer");
            assertEquals(200, post(admin.id, users(user.id) + "/change-role", roleBody("moderator")).status());
            moderators.add(user);
        }

        Response first = get(admin.id, "/api/v1/staff/users?role=moderator&status=active&limit=2");
        assertEquals(200, first.status());
        List<?> items = (List<?>) first.json().get("items");
        assertEquals(2, items.size());
        String cursor = (String) ((Map<?, ?>) first.json().get("page")).get("nextCursor");
        assertNotNull(cursor);
        assertEquals(moderators.get(2).id, ((Map<?, ?>) items.get(0)).get("userId"));

        Response second = get(admin.id, "/api/v1/staff/users?role=moderator&status=active&limit=2&cursor=" + cursor);
        assertEquals(200, second.status());
        assertFalse(((List<?>) second.json().get("items")).isEmpty());

        Response exact = get(admin.id, "/api/v1/staff/users?email=" + moderators.get(0).email.toUpperCase());
        List<?> found = (List<?>) exact.json().get("items");
        assertEquals(1, found.size());
        assertEquals(moderators.get(0).id, ((Map<?, ?>) found.get(0)).get("userId"));
        assertFalse(((Map<?, ?>) found.get(0)).containsKey("phone"));

        Response invalid = get(admin.id, "/api/v1/staff/users?role=root&limit=0");
        assertEquals(422, invalid.status());
        assertEquals("validation-failed", invalid.json().get("code"));
    }

    // ---- changeUserRole ----

    @Test
    @Order(6)
    void roleChangeIsWrittenToTheDatabaseAndOutboxFirstThenToKeycloak() throws IOException {
        KeycloakStub.User admin = newAdmin();
        KeycloakStub.User target = provisioned("buyer");
        String key = UUID.randomUUID().toString();

        Response response = post(admin.id, users(target.id) + "/change-role", roleBody("moderator"), key);

        assertEquals(200, response.status());
        assertEquals("moderator", response.json().get("role"));
        assertEquals(target.id, response.json().get("userId"));
        assertEquals("moderator", row(target.id).get("role"));
        assertEquals(List.of("user.registered", "user.role-assigned", "audit.recorded"), events(target.id));
        assertEquals(Set.of("moderator"), Set.copyOf(target.roles));
        assertTrue(target.requiredActions.contains("CONFIGURE_TOTP"));

        Response replay = post(admin.id, users(target.id) + "/change-role", roleBody("moderator"), key);
        assertEquals(200, replay.status());
        assertEquals("true", replay.header("Idempotency-Replayed"));
        assertEquals(3, events(target.id).size());

        String audit = jdbc().queryForObject("select payload::text from public.outbox where aggregate_id = ? and event_type = 'audit.recorded'",
                String.class, target.id);
        assertNotNull(audit);
        assertTrue(audit.contains("user.role_assigned"), audit);
        assertTrue(audit.contains(admin.id), audit);
        assertFalse(audit.contains(target.email), "в аудите нет адреса");
    }

    @Test
    @Order(7)
    void sellerRoleUnknownRoleAndUnknownUserAreRefusedByTheContract() throws IOException {
        KeycloakStub.User admin = newAdmin();
        KeycloakStub.User target = provisioned("buyer");

        Response seller = post(admin.id, users(target.id) + "/change-role", roleBody("seller"));
        assertEquals(409, seller.status());
        assertEquals("role-managed-by-system", seller.json().get("code"));

        Response invalid = post(admin.id, users(target.id) + "/change-role", roleBody("root"));
        assertEquals(422, invalid.status());
        assertEquals("validation-failed", invalid.json().get("code"));

        Response unknown = post(admin.id, users(UUID.randomUUID().toString()) + "/change-role", roleBody("moderator"));
        assertEquals(404, unknown.status());
        assertEquals("not-found", unknown.json().get("code"));

        assertEquals("buyer", row(target.id).get("role"));
        assertEquals(List.of("user.registered"), events(target.id));
    }

    // ---- deactivateUser и anonymizeUser ----

    @Test
    @Order(8)
    void deactivationDisablesKeycloakAndRepeatsAreIdempotent() throws IOException {
        KeycloakStub.User admin = newAdmin();
        KeycloakStub.User target = provisioned("buyer");

        Response response = post(admin.id, users(target.id) + "/deactivate", null);

        assertEquals(200, response.status());
        assertEquals("deactivated", response.json().get("status"));
        assertNotNull(response.json().get("deactivatedAt"));
        assertEquals(Boolean.TRUE, row(target.id).get("has_deactivated"));
        assertFalse(target.enabled);
        assertTrue(target.loggedOut);
        assertEquals(List.of("user.registered", "user.deactivated", "audit.recorded"), events(target.id));

        Response again = post(admin.id, users(target.id) + "/deactivate", null);
        assertEquals(200, again.status());
        assertEquals(3, events(target.id).size());

        Response noKey = gateway.post(users(target.id) + "/deactivate", null, "Authorization", "Bearer " + adminToken(admin.id));
        assertEquals(400, noKey.status());
        assertEquals("idempotency-key-required", noKey.json().get("code"));
    }

    @Test
    @Order(9)
    void anonymizationNeedsDeactivationThenRemovesPersonalDataEverywhere() throws IOException {
        KeycloakStub.User admin = newAdmin();
        KeycloakStub.User target = provisioned("buyer");
        String email = target.email;

        Response active = post(admin.id, users(target.id) + "/anonymize", null);
        assertEquals(409, active.status());
        assertEquals("state-conflict", active.json().get("code"));

        assertEquals(200, post(admin.id, users(target.id) + "/deactivate", null).status());
        Response response = post(admin.id, users(target.id) + "/anonymize", null);

        assertEquals(200, response.status());
        assertEquals(Boolean.TRUE, response.json().get("anonymized"));
        assertNull(response.json().get("name"));
        assertEquals("anonymized-" + target.id + "@invalid", response.json().get("email"));
        Map<String, Object> row = row(target.id);
        assertEquals("Удалён", row.get("name"));
        assertEquals(Boolean.TRUE, row.get("no_salt"));
        assertEquals(Boolean.TRUE, row.get("anonymized"));
        assertNull(KEYCLOAK.get(target.id));
        assertEquals(List.of("user.registered", "user.deactivated", "audit.recorded", "user.anonymized", "audit.recorded"), events(target.id));
        for (String payload : jdbc().queryForList("select payload::text from public.outbox where aggregate_id = ?", String.class, target.id)) {
            assertFalse(payload.contains(email), "в событиях нет адреса");
        }
    }

    // ---- сбои Keycloak и возврат к согласию ----

    @Test
    @Order(10)
    void reconcilerCatchesKeycloakUpAfterAnOutageDuringTheChange() throws IOException {
        KeycloakStub.User admin = newAdmin();
        KeycloakStub.User roleTarget = provisioned("buyer");
        KeycloakStub.User deactivateTarget = provisioned("buyer");

        KEYCLOAK.refuseRoleMappings(true);
        try {
            Response role = post(admin.id, users(roleTarget.id) + "/change-role", roleBody("support-operator"));
            assertEquals(200, role.status());
        } finally {
            KEYCLOAK.refuseRoleMappings(false);
        }
        KEYCLOAK.down(true);
        try {
            assertEquals(200, post(admin.id, users(deactivateTarget.id) + "/deactivate", null).status());
        } finally {
            KEYCLOAK.down(false);
        }
        assertEquals("support-operator", row(roleTarget.id).get("role"));
        assertEquals(Set.of("default-roles-dgm"), Set.copyOf(roleTarget.roles));
        assertTrue(deactivateTarget.enabled);

        IdentityReconciler.Summary summary = reconciler.runPass();

        assertTrue(summary.rolesFixed() >= 1, summary.toString());
        assertTrue(summary.disabled() >= 1, summary.toString());
        assertEquals(Set.of("support-operator"), Set.copyOf(roleTarget.roles));
        assertFalse(deactivateTarget.enabled);
        assertEquals(0, reconciler.runPass().rolesFixed());
    }

    // ---- последний администратор: в конце, потому что тест деактивирует остальных ----

    @Test
    @Order(99)
    void lastActiveAdministratorCannotBeDeactivatedOrDemoted() throws IOException {
        KeycloakStub.User last = newAdmin();
        assertEquals(200, get(last.id, "/api/v1/staff/users").status());
        List<String> others = new ArrayList<>();
        String cursor = null;
        do {
            Response page = get(last.id, "/api/v1/staff/users?role=admin&status=active&limit=100" + (cursor == null ? "" : "&cursor=" + cursor));
            assertEquals(200, page.status());
            for (Object item : (List<?>) page.json().get("items")) {
                String id = String.valueOf(((Map<?, ?>) item).get("userId"));
                if (!id.equals(last.id)) {
                    others.add(id);
                }
            }
            cursor = (String) ((Map<?, ?>) page.json().get("page")).get("nextCursor");
        } while (cursor != null);
        for (String id : others) {
            assertEquals(200, post(last.id, users(id) + "/deactivate", null).status(), id);
        }

        Response deactivate = post(last.id, users(last.id) + "/deactivate", null);
        assertEquals(409, deactivate.status());
        assertEquals("last-admin-required", deactivate.json().get("code"));
        Response demote = post(last.id, users(last.id) + "/change-role", roleBody("buyer"));
        assertEquals(409, demote.status());
        assertEquals("last-admin-required", demote.json().get("code"));
        assertEquals("active", row(last.id).get("status"));
        assertEquals("admin", row(last.id).get("role"));
    }
}
