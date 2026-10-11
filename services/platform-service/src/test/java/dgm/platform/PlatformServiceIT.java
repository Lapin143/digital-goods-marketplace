package dgm.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.boot.ModuleDatabases;
import dgm.kit.boot.testing.StandHttp;
import dgm.kit.boot.testing.StandHttp.Response;
import dgm.kit.crypto.WebhookSignature;
import dgm.kit.json.Json;
import dgm.kit.redis.KitRedis;
import dgm.kit.secret.SecretFiles;
import dgm.kit.testing.Stand;
import dgm.kit.testing.TestTokens;
import dgm.kit.time.Timestamps;
import dgm.kit.tls.TlsMaterial;
import dgm.platform.identity.service.IdentityReconciler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
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
 * Kafka, Redis (пользователь ACL {@code platform}), заглушка SMS-провайдера {@code external-stubs} и правила маршрутов, а Keycloak заменён
 * заглушкой Admin REST в памяти ({@link KeycloakStub}). Проверяется шаг 5 Ф4: создание записи при первом обращении (в том числе при
 * одновременных запросах), роли, деактивация, анонимизация, порядок «база и Outbox, потом Keycloak», сверка (создаёт записи мимо создания
 * при обращении и догоняет Keycloak после сбоя), и шаг 6: подтверждение телефона по коду из SMS (ST-05, ST-11), внутренние операции входа по
 * SMS от имени {@code keycloak}, статус доставки вебхуком, передача {@code phone_verified} и её повтор сверкой. Ответы складываются в
 * build/contract-samples и проверяются {@code check_contract.py}.
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
        registry.add("dgm.redis.host", () -> env("DGM_REDIS_HOST", "localhost"));
        registry.add("dgm.redis.port", () -> env("DGM_REDIS_PORT", "16379"));
        registry.add("dgm.sms.base-url", () -> "https://localhost:" + env("DGM_STUBS_PORT", "18443"));
        // Сверку в тестах запускает сам тест: расписание уходит за пределы прогона
        registry.add("dgm.identity.reconcile-interval", () -> "PT1H");
        registry.add("dgm.identity.reconcile-initial-delay", () -> "PT1H");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
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
    @Autowired
    private KitRedis redis;

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

    // ---- подтверждение телефона и вход по SMS (шаг 6 Ф4) ----

    private static final HttpClient STUBS = HttpClient.newBuilder()
            .sslContext(TlsMaterial.forService(Stand.secrets(), "api-gateway").trustOnlyContext()).connectTimeout(Duration.ofSeconds(5)).build();

    /** Ответ администрирования заглушки внешних систем (порт отладки, достаточно доверять центру сертификации). */
    private static HttpResponse<String> stubs(String method, String path, String json) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://localhost:" + env("DGM_STUBS_ADMIN_PORT", "18444") + path))
                .timeout(Duration.ofSeconds(5));
        if (json == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        }
        try {
            return STUBS.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Запрос к заглушке прерван", e);
        }
    }

    /** SMS, принятые заглушкой для номера, по порядку отправки (в том числе не доставленные «на телефон»). */
    private static List<Map<?, ?>> sms(String phone) throws IOException {
        HttpResponse<String> response = stubs("GET", "/admin/sms?all=1&to=" + URLEncoder.encode(phone, StandardCharsets.UTF_8), null);
        assertEquals(200, response.statusCode(), response.body());
        List<Map<?, ?>> items = new ArrayList<>();
        for (Object item : (List<?>) Json.parseObject(response.body()).get("items")) {
            items.add((Map<?, ?>) item);
        }
        return items;
    }

    /** Код из последнего SMS на номер: то, что увидел бы человек на телефоне. */
    private static String smsCode(String phone) throws IOException {
        List<Map<?, ?>> items = sms(phone);
        assertFalse(items.isEmpty(), "заглушка не получила SMS на " + phone);
        return String.valueOf(items.get(items.size() - 1).get("code"));
    }

    /** Новый номер: на стенде остаются ключи Redis прошлых прогонов, поэтому номера не повторяются. */
    private static String newPhone() {
        return "+79" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
    }

    /** Новый адрес клиента из блока документации (198.18.0.0/15): счётчики по адресу тоже остаются между прогонами. */
    private static String newIp() {
        return "198.18." + ThreadLocalRandom.current().nextInt(256) + "." + (1 + ThreadLocalRandom.current().nextInt(254));
    }

    private static String maskedPhone(String phone) {
        return phone.substring(0, 5) + "***" + phone.substring(phone.length() - 4);
    }

    private static String wrongCodeFor(String code) {
        return "000000".equals(code) ? "000001" : "000000";
    }

    private static Response asBuyer(String subject, String path, String body, String ip) throws IOException {
        return gateway.post(path, body, "Authorization", "Bearer " + token(subject, "buyer", "account.manage", "pwd"),
                "Idempotency-Key", UUID.randomUUID().toString(), "X-Forwarded-For", ip);
    }

    private static String phoneBody(String phone) {
        return "{\"phone\":\"" + phone + "\"}";
    }

    private static String codeBody(String code) {
        return "{\"code\":\"" + code + "\"}";
    }

    private static String verifyPath(Response created) {
        return "/api/v1/phone-confirmations/" + created.json().get("confirmationId") + "/verify";
    }

    /** Покупатель с подтверждённым телефоном: полный путь через SMS-заглушку. */
    private KeycloakStub.User confirmedBuyer(String phone) throws IOException {
        KeycloakStub.User buyer = provisioned("buyer");
        String ip = newIp();
        Response created = asBuyer(buyer.id, "/api/v1/phone-confirmations", phoneBody(phone), ip);
        assertEquals(201, created.status(), created.body());
        assertEquals(200, asBuyer(buyer.id, verifyPath(created), codeBody(smsCode(phone)), ip).status());
        return buyer;
    }

    private static Response asKeycloak(StandHttp client, String path, String body, String key) throws IOException {
        return key == null ? client.post(path, body) : client.post(path, body, "Idempotency-Key", key);
    }

    @Test
    @Order(11)
    void phoneIsConfirmedByTheCodeFromTheSmsAndPassedToKeycloak() throws Exception {
        KeycloakStub.User buyer = provisioned("buyer");
        String phone = newPhone();
        String ip = newIp();

        Response created = asBuyer(buyer.id, "/api/v1/phone-confirmations", phoneBody(phone), ip);

        assertEquals(201, created.status(), created.body());
        assertEquals(maskedPhone(phone), created.json().get("phoneMasked"));
        assertEquals(5, ((Number) created.json().get("attemptsLeft")).intValue());
        assertNotNull(created.json().get("expiresAt"));
        assertEquals("/api/v1/phone-confirmations/" + created.json().get("confirmationId"), created.header("Location"));
        assertEquals(1, sms(phone).size());
        String code = smsCode(phone);
        assertTrue(code.matches("[0-9]{6}"), "код из шести цифр");
        // В Redis лежит хеш кода, а не код (NFT-3.7)
        String stored = redis.get("platform:otp:code:phone_confirm:" + buyer.id).orElseThrow();
        assertFalse(stored.contains(code), "код в Redis не хранится открытым");
        assertEquals(0, count("select count(*) from identity.user_account where phone = ?", phone), "до проверки номер в базе не записан");

        Response wrong = asBuyer(buyer.id, verifyPath(created), codeBody(wrongCodeFor(code)), ip);
        assertEquals(422, wrong.status());
        assertEquals("otp-invalid", wrong.json().get("code"));
        assertEquals(4, ((Number) wrong.json().get("attemptsLeft")).intValue());

        Response verified = asBuyer(buyer.id, verifyPath(created), codeBody(code), ip);

        assertEquals(200, verified.status(), verified.body());
        assertEquals(Boolean.TRUE, verified.json().get("phoneConfirmed"));
        assertEquals(maskedPhone(phone), verified.json().get("phoneMasked"));
        Map<String, Object> account = jdbc().queryForMap("select phone, phone_confirmed, phone_synced_to_idp from identity.user_account where id = ?",
                UUID.fromString(buyer.id));
        assertEquals(phone, account.get("phone"));
        assertEquals(Boolean.TRUE, account.get("phone_confirmed"));
        assertEquals(Boolean.TRUE, account.get("phone_synced_to_idp"));
        assertEquals(List.of("true"), buyer.attributes.get("phone_verified"));
        assertEquals(List.of("user.registered", "user.phone-confirmed", "audit.recorded"), events(buyer.id));
        for (String payload : jdbc().queryForList("select payload::text from public.outbox where aggregate_id = ?", String.class, buyer.id)) {
            assertFalse(payload.contains(phone), "в событиях нет номера открытым текстом");
            assertFalse(payload.contains(ip), "в событиях нет адреса клиента открытым текстом");
        }
        String audit = jdbc().queryForObject("select payload::text from public.outbox where aggregate_id = ? and event_type = 'audit.recorded'",
                String.class, buyer.id);
        assertNotNull(audit);
        assertTrue(audit.contains("user.phone_confirmed"), audit);

        // Погашенный код не воскресить: повтор того же кода отвечает «срок истёк»
        Response again = asBuyer(buyer.id, verifyPath(created), codeBody(code), ip);
        assertEquals(422, again.status());
        assertEquals("otp-expired", again.json().get("code"));
        assertEquals(Optional.empty(), redis.get("platform:otp:code:phone_confirm:" + buyer.id));
    }

    @Test
    @Order(12)
    void confirmedNumberCannotBeTakenByAnotherAccountAndSmsSessionsAreRefused() throws Exception {
        String phone = newPhone();
        confirmedBuyer(phone);
        KeycloakStub.User other = provisioned("buyer");

        Response taken = asBuyer(other.id, "/api/v1/phone-confirmations", phoneBody(phone), newIp());

        assertEquals(409, taken.status());
        assertEquals("phone-not-allowed", taken.json().get("code"));
        assertEquals(1, sms(phone).size(), "на занятый номер SMS не уходит");

        Response smsSession = gateway.post("/api/v1/phone-confirmations", phoneBody(newPhone()), "Authorization",
                "Bearer " + token(other.id, "buyer", "account.manage", "sms"), "Idempotency-Key", UUID.randomUUID().toString());
        assertEquals(403, smsSession.status());
        assertEquals("full-login-required", smsSession.json().get("code"));

        Response invalid = asBuyer(other.id, "/api/v1/phone-confirmations", phoneBody("89001234567"), newIp());
        assertEquals(422, invalid.status());
        assertEquals("validation-failed", invalid.json().get("code"));
    }

    @Test
    @Order(13)
    void fourthRequestInTenMinutesIsRefusedWithRetryAfterForTheAccountAndForTheNumber() throws Exception {
        KeycloakStub.User first = provisioned("buyer");
        KeycloakStub.User second = provisioned("buyer");
        String phone = newPhone();

        for (int i = 0; i < 3; i++) {
            assertEquals(201, asBuyer(first.id, "/api/v1/phone-confirmations", phoneBody(phone), newIp()).status(), "запрос " + (i + 1));
        }
        Response limited = asBuyer(first.id, "/api/v1/phone-confirmations", phoneBody(phone), newIp());

        assertEquals(429, limited.status());
        assertEquals("otp-request-limited", limited.json().get("code"));
        assertTrue(Long.parseLong(limited.header("Retry-After")) > 0, "Retry-After в секундах");
        assertNotNull(limited.json().get("retryAfterAt"));
        assertEquals(3, sms(phone).size(), "четвёртое SMS не отправлено");
        // Окно учётной записи действует и для другого номера, окно номера и для другой учётной записи
        assertEquals(429, asBuyer(first.id, "/api/v1/phone-confirmations", phoneBody(newPhone()), newIp()).status());
        assertEquals(429, asBuyer(second.id, "/api/v1/phone-confirmations", phoneBody(phone), newIp()).status());
        assertEquals(201, asBuyer(second.id, "/api/v1/phone-confirmations", phoneBody(newPhone()), newIp()).status());
    }

    @Test
    @Order(14)
    void eleventhRequestFromOneAddressInAnHourIsRefused() throws Exception {
        String ip = newIp();
        List<KeycloakStub.User> buyers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            buyers.add(provisioned("buyer"));
        }

        for (int i = 0; i < 10; i++) {
            assertEquals(201, asBuyer(buyers.get(i / 3).id, "/api/v1/phone-confirmations", phoneBody(newPhone()), ip).status(), "запрос " + (i + 1));
        }
        Response limited = asBuyer(buyers.get(3).id, "/api/v1/phone-confirmations", phoneBody(newPhone()), ip);

        assertEquals(429, limited.status());
        assertEquals("otp-request-limited", limited.json().get("code"));
        assertTrue(Long.parseLong(limited.header("Retry-After")) > 0);
        assertEquals(201, asBuyer(buyers.get(3).id, "/api/v1/phone-confirmations", phoneBody(newPhone()), newIp()).status(),
                "другой адрес не затронут");
    }

    @Test
    @Order(15)
    void fifthWrongCodeClosesTheRequestAndTheSixthCannotRedeemIt() throws Exception {
        KeycloakStub.User buyer = provisioned("buyer");
        String phone = newPhone();
        String ip = newIp();
        Response created = asBuyer(buyer.id, "/api/v1/phone-confirmations", phoneBody(phone), ip);
        assertEquals(201, created.status());
        String code = smsCode(phone);
        String wrong = wrongCodeFor(code);

        for (int left = 4; left >= 1; left--) {
            Response response = asBuyer(buyer.id, verifyPath(created), codeBody(wrong), ip);
            assertEquals(422, response.status());
            assertEquals("otp-invalid", response.json().get("code"));
            assertEquals(left, ((Number) response.json().get("attemptsLeft")).intValue());
        }
        Response fifth = asBuyer(buyer.id, verifyPath(created), codeBody(wrong), ip);
        assertEquals(429, fifth.status());
        assertEquals("otp-locked", fifth.json().get("code"));
        assertTrue(Long.parseLong(fifth.header("Retry-After")) > 0);

        Response sixth = asBuyer(buyer.id, verifyPath(created), codeBody(code), ip);
        assertEquals(422, sixth.status());
        assertEquals("otp-expired", sixth.json().get("code"));
        assertEquals(0, count("select count(*) from identity.user_account where id = ? and phone_confirmed", UUID.fromString(buyer.id)));
    }

    @Test
    @Order(16)
    void foreignConfirmationIdAndMalformedRequestsAreRefused() throws Exception {
        KeycloakStub.User buyer = provisioned("buyer");
        String phone = newPhone();
        String ip = newIp();
        Response created = asBuyer(buyer.id, "/api/v1/phone-confirmations", phoneBody(phone), ip);
        assertEquals(201, created.status());

        Response foreign = asBuyer(buyer.id, "/api/v1/phone-confirmations/" + UUID.randomUUID() + "/verify", codeBody(smsCode(phone)), ip);
        assertEquals(404, foreign.status());
        assertEquals("not-found", foreign.json().get("code"));
        Response format = asBuyer(buyer.id, verifyPath(created), codeBody("12"), ip);
        assertEquals(422, format.status());
        assertEquals("validation-failed", format.json().get("code"));
        Response noKey = gateway.post("/api/v1/phone-confirmations", phoneBody(newPhone()), "Authorization",
                "Bearer " + token(buyer.id, "buyer", "account.manage", "pwd"));
        assertEquals(400, noKey.status());
        assertEquals("idempotency-key-required", noKey.json().get("code"));
        // Верный код всё ещё действует: отказы по чужому идентификатору и формату попыток не тратят
        assertEquals(200, asBuyer(buyer.id, verifyPath(created), codeBody(smsCode(phone)), ip).status());
    }

    @Test
    @Order(17)
    void providerFailureGivesRetryableUnavailableAndLeavesNoCode() throws Exception {
        KeycloakStub.User buyer = provisioned("buyer");
        String phone = newPhone();
        assertEquals(200, stubs("PUT", "/admin/modes/sms", "{\"behavior\":\"error\"}").statusCode());
        try {
            Response failed = asBuyer(buyer.id, "/api/v1/phone-confirmations", phoneBody(phone), newIp());

            assertEquals(503, failed.status());
            assertEquals("dependency-unavailable", failed.json().get("code"));
            assertEquals("30", failed.header("Retry-After"));
            assertEquals(Optional.empty(), redis.get("platform:otp:code:phone_confirm:" + buyer.id), "запись кода убрана вместе с отказом");
            assertTrue(sms(phone).isEmpty());
        } finally {
            assertEquals(200, stubs("PUT", "/admin/modes/sms", "{\"behavior\":\"deliver\"}").statusCode());
        }
        assertEquals(201, asBuyer(buyer.id, "/api/v1/phone-confirmations", phoneBody(phone), newIp()).status());
    }

    @Test
    @Order(18)
    void keycloakOutageDuringConfirmationIsCaughtUpByTheReconciler() throws Exception {
        KeycloakStub.User buyer = provisioned("buyer");
        String phone = newPhone();
        String ip = newIp();
        Response created = asBuyer(buyer.id, "/api/v1/phone-confirmations", phoneBody(phone), ip);
        assertEquals(201, created.status());
        String code = smsCode(phone);

        KEYCLOAK.down(true);
        try {
            assertEquals(200, asBuyer(buyer.id, verifyPath(created), codeBody(code), ip).status(), "подтверждение не зависит от Keycloak");
        } finally {
            KEYCLOAK.down(false);
        }
        Map<String, Object> account = jdbc().queryForMap("select phone_confirmed, phone_synced_to_idp from identity.user_account where id = ?",
                UUID.fromString(buyer.id));
        assertEquals(Boolean.TRUE, account.get("phone_confirmed"));
        assertEquals(Boolean.FALSE, account.get("phone_synced_to_idp"));
        assertNull(buyer.attributes.get("phone_verified"));

        IdentityReconciler.Summary summary = reconciler.runPass();

        assertTrue(summary.phonesSynced() >= 1, summary.toString());
        assertEquals(List.of("true"), buyer.attributes.get("phone_verified"));
        assertEquals(Boolean.TRUE, jdbc().queryForObject("select phone_synced_to_idp from identity.user_account where id = ?", Boolean.class,
                UUID.fromString(buyer.id)));
        assertEquals(0, reconciler.runPass().phonesSynced());
    }

    @Test
    @Order(19)
    void keycloakCanRequestAndRedeemALoginCodeOnlyOnceAndAnUnknownNumberLooksTheSame() throws Exception {
        String phone = newPhone();
        KeycloakStub.User buyer = confirmedBuyer(phone);
        String unknown = newPhone();
        String ip = newIp();
        StandHttp keycloak = StandHttp.asService("keycloak", PORT);
        String issueBody = "{\"purpose\":\"sms_login\",\"phone\":\"%s\",\"clientIp\":\"" + ip + "\"}";

        Response bound = asKeycloak(keycloak, "/internal/v1/otp-codes", issueBody.formatted(phone), UUID.randomUUID().toString());
        Response decoy = asKeycloak(keycloak, "/internal/v1/otp-codes", issueBody.formatted(unknown), UUID.randomUUID().toString());

        assertEquals(202, bound.status(), bound.body());
        assertEquals(202, decoy.status(), decoy.body());
        assertEquals(bound.body(), decoy.body());
        assertEquals(Boolean.TRUE, bound.json().get("accepted"));
        assertEquals(2, sms(phone).size(), "SMS подтверждения и SMS входа");
        assertTrue(sms(unknown).isEmpty(), "на неизвестный номер SMS не уходит");
        String code = smsCode(phone);
        String verifyBody = "{\"purpose\":\"sms_login\",\"phone\":\"%s\",\"code\":\"%s\"}";

        Response wrongKnown = asKeycloak(keycloak, "/internal/v1/otp-verifications", verifyBody.formatted(phone, wrongCodeFor(code)),
                UUID.randomUUID().toString());
        Response wrongUnknown = asKeycloak(keycloak, "/internal/v1/otp-verifications", verifyBody.formatted(unknown, "000000"),
                UUID.randomUUID().toString());
        assertEquals(422, wrongKnown.status());
        assertEquals(422, wrongUnknown.status());
        assertEquals(wrongKnown.json().get("code"), wrongUnknown.json().get("code"));
        assertEquals(wrongKnown.json().get("title"), wrongUnknown.json().get("title"));
        assertEquals(wrongKnown.json().get("detail"), wrongUnknown.json().get("detail"));

        Response accepted = asKeycloak(keycloak, "/internal/v1/otp-verifications", verifyBody.formatted(phone, code), UUID.randomUUID().toString());
        assertEquals(200, accepted.status(), accepted.body());
        assertEquals(buyer.id, accepted.json().get("userId"));
        assertEquals("no-store", accepted.header("Cache-Control"));
        assertTrue(events(buyer.id).contains("user.sms-login"), events(buyer.id).toString());

        Response reuse = asKeycloak(keycloak, "/internal/v1/otp-verifications", verifyBody.formatted(phone, code), UUID.randomUUID().toString());
        assertEquals(422, reuse.status());
        assertEquals("otp-invalid", reuse.json().get("code"));
        assertEquals(1, events(buyer.id).stream().filter("user.sms-login"::equals).count());
    }

    @Test
    @Order(20)
    void internalOperationsAreClosedToOthersAndNeedAnIdempotencyKey() throws Exception {
        String body = "{\"purpose\":\"sms_login\",\"phone\":\"" + newPhone() + "\"}";

        Response notKeycloak = asKeycloak(gateway, "/internal/v1/otp-codes", body, UUID.randomUUID().toString());
        assertEquals(403, notKeycloak.status());
        assertEquals("forbidden", notKeycloak.json().get("code"));

        StandHttp keycloak = StandHttp.asService("keycloak", PORT);
        Response noKey = asKeycloak(keycloak, "/internal/v1/otp-codes", body, null);
        assertEquals(400, noKey.status());
        assertEquals("idempotency-key-required", noKey.json().get("code"));
        Response invalid = asKeycloak(keycloak, "/internal/v1/otp-codes", "{\"purpose\":\"sms_login\",\"phone\":\"89001234567\"}",
                UUID.randomUUID().toString());
        assertEquals(422, invalid.status());
        assertEquals("validation-failed", invalid.json().get("code"));
    }

    @Test
    @Order(21)
    void deliveryStatusWebhookIsAcceptedOnceSignedAndCountedOnce() throws Exception {
        KeycloakStub.User buyer = provisioned("buyer");
        String phone = newPhone();
        assertEquals(201, asBuyer(buyer.id, "/api/v1/phone-confirmations", phoneBody(phone), newIp()).status());
        String messageId = String.valueOf(sms(phone).get(0).get("messageId"));
        String body = "{\"messageId\":\"" + messageId + "\",\"status\":\"delivered\",\"occurredAt\":\"" + Timestamps.format(Instant.now()) + "\"}";
        byte[] secret = SecretFiles.read("platform_webhook_secret").revealBytes();
        String timestamp = Timestamps.format(Instant.now());
        String signature = WebhookSignature.sign(secret, timestamp, body.getBytes(StandardCharsets.UTF_8));
        StandHttp provider = StandHttp.asService("external-stubs", PORT);

        Response first = provider.post("/api/v1/webhooks/sms-provider", body, WebhookSignature.TIMESTAMP_HEADER, timestamp,
                WebhookSignature.SIGNATURE_HEADER, signature);
        Response repeat = provider.post("/api/v1/webhooks/sms-provider", body, WebhookSignature.TIMESTAMP_HEADER, timestamp,
                WebhookSignature.SIGNATURE_HEADER, signature);

        assertEquals(200, first.status(), first.body());
        assertEquals(200, repeat.status());
        assertTrue(redis.get("platform:sms:status:" + messageId + ":delivered").isPresent(), "статус учтён один раз и помечен на сутки");

        Response forged = provider.post("/api/v1/webhooks/sms-provider", body, WebhookSignature.TIMESTAMP_HEADER, timestamp,
                WebhookSignature.SIGNATURE_HEADER, "0".repeat(64));
        assertEquals(401, forged.status());
        assertEquals("signature-invalid", forged.json().get("code"));
        String oldTimestamp = Timestamps.format(Instant.now().minus(Duration.ofMinutes(30)));
        Response stale = provider.post("/api/v1/webhooks/sms-provider", body, WebhookSignature.TIMESTAMP_HEADER, oldTimestamp,
                WebhookSignature.SIGNATURE_HEADER, WebhookSignature.sign(secret, oldTimestamp, body.getBytes(StandardCharsets.UTF_8)));
        assertEquals(401, stale.status());
        assertEquals("webhook-expired", stale.json().get("code"));

        String bad = "{\"messageId\":\"" + messageId + "\",\"status\":\"read\",\"occurredAt\":\"" + Timestamps.format(Instant.now()) + "\"}";
        Response invalid = provider.post("/api/v1/webhooks/sms-provider", bad, WebhookSignature.TIMESTAMP_HEADER, timestamp,
                WebhookSignature.SIGNATURE_HEADER, WebhookSignature.sign(secret, timestamp, bad.getBytes(StandardCharsets.UTF_8)));
        assertEquals(400, invalid.status());
        assertEquals("bad-request", invalid.json().get("code"));
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
