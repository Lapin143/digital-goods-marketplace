package dgm.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.boot.ModuleDatabases;
import dgm.kit.boot.Modules;
import dgm.kit.boot.testing.StandHttp;
import dgm.kit.boot.testing.StandHttp.Response;
import dgm.kit.crypto.WebhookSignature;
import dgm.kit.events.HandleResult;
import dgm.kit.json.Json;
import dgm.kit.kafka.KafkaClients;
import dgm.kit.outbox.AuditRecords;
import dgm.kit.outbox.EnvelopeBuilder;
import dgm.kit.outbox.OutboxRow;
import dgm.kit.outbox.OutgoingEvent;
import dgm.kit.redis.KitRedis;
import dgm.kit.secret.SecretFiles;
import dgm.kit.testing.Concurrent;
import dgm.kit.testing.Stand;
import dgm.kit.testing.TestTokens;
import dgm.kit.time.Timestamps;
import dgm.kit.tls.TlsMaterial;
import dgm.kit.trace.TraceContext;
import dgm.platform.auditadmin.service.AuditPartitionService;
import dgm.platform.auditadmin.service.AuditRecordService;
import dgm.platform.identity.service.IdentityReconciler;
import dgm.platform.identity.service.IdentityTransaction;
import dgm.platform.support.service.SupportService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
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
 * Kafka, Redis (пользователь ACL {@code platform}), заглушки SMS- и почтового провайдера {@code external-stubs} и правила маршрутов, а Keycloak заменён
 * заглушкой Admin REST в памяти ({@link KeycloakStub}). Проверяется шаг 5 Ф4: создание записи при первом обращении (в том числе при
 * одновременных запросах), роли, деактивация, анонимизация, порядок «база и Outbox, потом Keycloak», сверка (создаёт записи мимо создания
 * при обращении и догоняет Keycloak после сбоя), и шаг 6: подтверждение телефона по коду из SMS (ST-05, ST-11), внутренние операции входа по
 * SMS от имени {@code keycloak}, статус доставки вебхуком, передача {@code phone_verified} и её повтор сверкой, и шаг 8: обработчик
 * уведомлений (событие в Kafka, письмо в почтовой заглушке, статус в таблице без адреса, дубль события, повторы при сбое провайдера,
 * {@code notification.failed}). Ответы складываются в build/contract-samples и проверяются {@code check_contract.py}.
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
    /** Адрес сайта в ссылках писем среза 2: свой, чтобы тест видел, что ссылка собрана из настройки, а не из значения по умолчанию. */
    private static final String SITE = "https://shop.it.example";

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
        registry.add("dgm.email.base-url", () -> "https://localhost:" + env("DGM_STUBS_PORT", "18443"));
        // Лестница повторов письма сжата: настоящие 1, 5 и 25 секунд проверяет модульный тест, здесь важен порядок событий
        registry.add("dgm.notification.retry-delays", () -> "PT0.2S,PT0.2S,PT0.2S");
        registry.add("dgm.notification.site-url", () -> SITE);
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
    static void clients() throws Exception {
        skipBacklog();
        gateway = StandHttp.asService("api-gateway", PORT);
    }

    /** Темы, которые читает платформа (потребители обработчиков). */
    private static final List<String> CONSUMED_TOPICS = List.of("catalog.events", "identity.events", "order.events", "payment.events", "delivery.events",
            "audit.events");

    /**
     * Переносит группу потребителей платформы в конец своих тем до запуска контекста. Группа читает темы с начала, а на общем стенде в них лежат события,
     * которые оставили тесты соседних сервисов в том же прогоне CI (заказы, которых платформа не знает): поток потребителя один, и каждое такое
     * {@code delivery.failed} держит его 31 секунду повторов, пока тесты платформы ждут свои события. Контекст запускается позже этого метода,
     * потому что Spring создаёт его при создании экземпляра теста, после статических {@code @BeforeAll}.
     */
    private static void skipBacklog() throws Exception {
        Map<String, Object> props = new LinkedHashMap<>(Stand.kafkaSecurity("kafka-init"));
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, Stand.kafkaBootstrap());
        props.put(AdminClientConfig.CLIENT_ID_CONFIG, "platform-it-offsets");
        try (Admin admin = Admin.create(props)) {
            Set<String> existing = admin.listTopics().names().get(30, TimeUnit.SECONDS);
            Map<TopicPartition, OffsetSpec> latest = new LinkedHashMap<>();
            for (String topic : CONSUMED_TOPICS) {
                if (existing.contains(topic)) {
                    admin.describeTopics(List.of(topic)).allTopicNames().get(30, TimeUnit.SECONDS).get(topic).partitions()
                            .forEach(partition -> latest.put(new TopicPartition(topic, partition.partition()), OffsetSpec.latest()));
                }
            }
            Map<TopicPartition, OffsetAndMetadata> ends = new LinkedHashMap<>();
            admin.listOffsets(latest).all().get(30, TimeUnit.SECONDS).forEach((partition, info) -> ends.put(partition, new OffsetAndMetadata(info.offset())));
            admin.alterConsumerGroupOffsets("platform-service", ends).all().get(30, TimeUnit.SECONDS);
        }
    }

    @AfterAll
    static void stopStub() {
        KEYCLOAK.close();
        PRODUCERS.values().forEach(producer -> producer.close(Duration.ofSeconds(5)));
    }

    @Autowired
    private ModuleDatabases databases;
    @Autowired
    private IdentityReconciler reconciler;
    @Autowired
    private KitRedis redis;
    @Autowired
    private Modules modules;
    @Autowired
    private SupportService support;
    @Autowired
    private AuditRecordService auditRecords;
    @Autowired
    private AuditPartitionService auditPartitions;
    @Autowired
    private IdentityTransaction identityTransaction;

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

    @Tag("TC-111")
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

    @Tag("TC-112")
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

    @Tag("TC-113")
    @Tag("ST-13")
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

    @Tag("TC-115")
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

    @Tag("TC-116")
    @Tag("TC-117")
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

    @Tag("TC-121")
    @Tag("TC-122")
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

    @Tag("TC-124")
    @Tag("TC-127")
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

    @Tag("TC-125")
    @Tag("ST-05")
    @Tag("ST-11")
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

    @Tag("TC-125")
    @Tag("ST-11")
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

    @Tag("TC-122")
    @Tag("ST-05")
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

    @Tag("TC-128")
    @Tag("TC-129")
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

    // ---- уведомления (шаг 8 Ф4): событие в Kafka, письмо в почтовой заглушке, статус в таблице ----

    private static final Map<String, KafkaProducer<String, String>> PRODUCERS = new ConcurrentHashMap<>();

    /** Издатель событий от имени сервиса: права Kafka выданы по CN сертификата, поэтому события каталога пишет {@code catalog-service}. */
    private static KafkaProducer<String, String> producer(String service) {
        return PRODUCERS.computeIfAbsent(service, name -> KafkaClients.producer(Stand.kafkaBootstrap(), Stand.kafkaSecurity(name), name + "-it"));
    }

    /** Публикует конверт события, собранный тем же кодом, что собирает публикатор Outbox. Ключ записи равен {@code subject}. */
    private static void publish(String service, String topic, String aggregateType, String subject, String type, UUID eventId,
                                Map<String, Object> data) throws Exception {
        String headers = "{\"schemaversion\": 1, \"traceparent\": \"" + TraceContext.fresh().traceparent() + "\", \"correlationid\": \""
                + UUID.randomUUID() + "\"}";
        OutgoingEvent event = new EnvelopeBuilder(service).build(new OutboxRow(1, eventId, aggregateType, subject, type, topic, Json.write(data),
                headers, Instant.now(), 0));
        ProducerRecord<String, String> record = new ProducerRecord<>(event.topic(), event.key(), event.value());
        record.headers().add("ce_type", event.type().getBytes(StandardCharsets.UTF_8));
        record.headers().add("traceparent", event.traceparent().getBytes(StandardCharsets.UTF_8));
        producer(service).send(record).get(20, TimeUnit.SECONDS);
    }

    private static void publishSellerEvent(UUID userId, String type, Map<String, Object> extra) throws Exception {
        publishSellerEvent(userId, UUID.randomUUID(), type, extra);
    }

    /** Публикует событие профиля продавца и возвращает его идентификатор: по нему видно, что обработчик событие разобрал. */
    private static UUID publishSellerEvent(UUID userId, UUID sellerId, String type, Map<String, Object> extra) throws Exception {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sellerId", sellerId.toString());
        data.put("userId", userId.toString());
        data.put("moderatorId", UUID.randomUUID().toString());
        data.putAll(extra);
        publish("catalog-service", "catalog.events", "Seller", sellerId.toString(), type, eventId, data);
        return eventId;
    }

    /** Обработчик роли продавца разобрал событие: отметка в {@code processed_event} ставится в той же транзакции, что и действие. */
    private boolean sellerRoleHandled(UUID eventId) {
        return count("select count(*) from public.processed_event where consumer = 'seller-role-handler' and event_id = ?", eventId) == 1;
    }

    private static boolean await(Duration limit, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(200);
        }
        return condition.getAsBoolean();
    }

    /** Письма, принятые заглушкой для адреса, по порядку получения. */
    private static List<Map<?, ?>> mails(String address) {
        try {
            HttpResponse<String> response = stubs("GET", "/admin/emails?to=" + URLEncoder.encode(address, StandardCharsets.UTF_8), null);
            assertEquals(200, response.statusCode(), response.body());
            List<Map<?, ?>> items = new ArrayList<>();
            for (Object item : (List<?>) Json.parseObject(response.body()).get("items")) {
                items.add((Map<?, ?>) item);
            }
            return items;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<?, ?> mailWithSubject(String address, String subject) {
        return mails(address).stream().filter(m -> subject.equals(m.get("subject"))).findFirst()
                .orElseThrow(() -> new AssertionError("нет письма «" + subject + "» для " + address));
    }

    private JdbcTemplate notificationDb() {
        return databases.jdbc("notification");
    }

    private static final String ROW = "select id::text as id, status, attempts, in_dead_queue, last_error_code, next_attempt_at is null as no_next, "
            + "source_event_id::text as source_event_id, params::text as params, sent_at is not null as has_sent_at from notification.notification ";

    /** Единственное уведомление пользователя по шаблону: два уведомления дали бы ошибку, это и проверяется. */
    private Map<String, Object> notification(String userId, String template) {
        return notificationDb().queryForMap(ROW + "where user_id = ? and template = ?", UUID.fromString(userId), template);
    }

    private boolean notificationIs(String userId, String template, String status) {
        List<String> found = notificationDb().queryForList("select status from notification.notification where user_id = ? and template = ?",
                String.class, UUID.fromString(userId), template);
        return found.size() == 1 && status.equals(found.get(0));
    }

    private String wholeRow(Object id) {
        return notificationDb().queryForObject("select n::text from notification.notification n where id = ?::uuid", String.class, String.valueOf(id));
    }

    private static void emailMode(String json) throws IOException {
        assertEquals(200, stubs("PUT", "/admin/modes/email", json).statusCode());
    }

    private List<Map<String, Object>> failedEvents(Object notificationId) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (String payload : jdbc().queryForList("select payload::text from public.outbox where event_type = 'notification.failed' and aggregate_id = ?",
                String.class, String.valueOf(notificationId))) {
            events.add(Json.parseObject(payload));
        }
        return events;
    }

    @Tag("TC-130")
    @Tag("TC-014")
    @Test
    @Order(22)
    void smsLoginGivesTheOwnerOneLetterAndAReplayedEventGivesNone() throws Exception {
        String phone = newPhone();
        KeycloakStub.User buyer = confirmedBuyer(phone);
        StandHttp keycloak = StandHttp.asService("keycloak", PORT);
        String ip = newIp();
        assertEquals(202, asKeycloak(keycloak, "/internal/v1/otp-codes", "{\"purpose\":\"sms_login\",\"phone\":\"" + phone + "\",\"clientIp\":\"" + ip + "\"}",
                UUID.randomUUID().toString()).status());
        Response accepted = asKeycloak(keycloak, "/internal/v1/otp-verifications",
                "{\"purpose\":\"sms_login\",\"phone\":\"" + phone + "\",\"code\":\"" + smsCode(phone) + "\"}", UUID.randomUUID().toString());
        assertEquals(200, accepted.status(), accepted.body());
        UUID eventId = UUID.fromString(jdbc().queryForObject("select event_id::text from public.outbox where aggregate_id = ? and event_type = 'user.sms-login'",
                String.class, buyer.id));

        assertTrue(await(Duration.ofSeconds(90), () -> mails(buyer.email).size() == 1), "письмо о входе дошло до почтовой заглушки");
        Map<?, ?> mail = mails(buyer.email).get(0);
        assertEquals("platform", mail.get("sender"));
        assertEquals("Вход в аккаунт по коду из SMS", mail.get("subject"));
        assertTrue(String.valueOf(mail.get("text")).contains("(МСК) в ваш аккаунт выполнен вход по одноразовому коду из SMS"), String.valueOf(mail.get("text")));
        assertFalse(String.valueOf(mail.get("text")).contains(phone), "номера телефона в письме нет");

        assertTrue(await(Duration.ofSeconds(20), () -> notificationIs(buyer.id, "user-sms-login", "sent")));
        Map<String, Object> row = notification(buyer.id, "user-sms-login");
        assertEquals(String.valueOf(mail.get("messageId")), row.get("id"), "идентификатор сообщения у провайдера равен идентификатору уведомления");
        assertEquals(1, row.get("attempts"));
        assertEquals(eventId.toString(), row.get("source_event_id"));
        assertEquals(Boolean.TRUE, row.get("no_next"));
        assertEquals(Boolean.TRUE, row.get("has_sent_at"));
        assertEquals(Boolean.FALSE, row.get("in_dead_queue"));
        assertFalse(wholeRow(row.get("id")).contains(buyer.email), "адреса в таблице уведомлений нет (INV-44)");
        assertEquals(1, notificationDb().queryForObject("select count(*) from public.processed_event where consumer = 'notification-handler' and event_id = ?",
                Integer.class, eventId));

        // Тот же конверт приходит второй раз, затем настоящий второй вход: он идёт по той же партиции следом за дублем
        publish("platform-service", "identity.events", "User", buyer.id, "user.sms-login", eventId, Map.of("userId", buyer.id));
        publish("platform-service", "identity.events", "User", buyer.id, "user.sms-login", UUID.randomUUID(), Map.of("userId", buyer.id));
        assertTrue(await(Duration.ofSeconds(90), () -> mails(buyer.email).size() == 2), "второй вход дал второе письмо");
        assertEquals(2, mails(buyer.email).size(), "дубль события письма не дал");
        assertEquals(2, notificationDb().queryForObject("select count(*) from notification.notification where user_id = ? and template = 'user-sms-login'",
                Integer.class, UUID.fromString(buyer.id)));
    }

    @Test
    @Order(23)
    @Tag("TC-219")
    void decisionsOnTheSellerApplicationGiveTheApplicantThreeLettersWithTheModeratorText() throws Exception {
        KeycloakStub.User applicant = provisioned("buyer");
        UUID user = UUID.fromString(applicant.id);
        String reason = "Не хватает скана паспорта, проверьте " + UUID.randomUUID();
        String comment = "Приложите свежую выписку " + UUID.randomUUID();
        String nextAt = Timestamps.format(Instant.now().plus(Duration.ofDays(1)));

        publishSellerEvent(user, "seller.approved", Map.of("sellerType", "self_employed"));
        publishSellerEvent(user, "seller.rejected", Map.of("reason", reason, "nextApplicationAt", nextAt));
        publishSellerEvent(user, "seller.returned", Map.of("comment", comment));

        assertTrue(await(Duration.ofSeconds(90), () -> mails(applicant.email).size() == 3), "три письма дошли до заглушки");
        String approved = String.valueOf(mailWithSubject(applicant.email, "Ваша заявка продавца одобрена").get("text"));
        assertTrue(approved.contains("Тип профиля: самозанятый."), approved);
        assertTrue(approved.contains("второй фактор"), approved);
        String rejected = String.valueOf(mailWithSubject(applicant.email, "Заявка продавца отклонена").get("text"));
        assertTrue(rejected.contains(reason), rejected);
        assertTrue(rejected.contains("(МСК)"), rejected);
        String returned = String.valueOf(mailWithSubject(applicant.email, "Заявка продавца возвращена на доработку").get("text"));
        assertTrue(returned.contains(comment), returned);
        for (String text : List.of(approved, rejected, returned)) {
            assertTrue(text.startsWith("Здравствуйте, "), text);
            assertFalse(text.contains(applicant.email), "адреса получателя в тексте письма нет");
        }

        for (String template : List.of("seller-approved", "seller-rejected", "seller-returned")) {
            assertTrue(await(Duration.ofSeconds(20), () -> notificationIs(applicant.id, template, "sent")), template);
            Map<String, Object> row = notification(applicant.id, template);
            assertEquals(1, row.get("attempts"), template);
            assertFalse(wholeRow(row.get("id")).contains(applicant.email), template + ": адреса в таблице уведомлений нет (INV-44)");
        }
        assertTrue(String.valueOf(notification(applicant.id, "seller-rejected").get("params")).contains(reason), "причина лежит в параметрах шаблона");
    }

    @Test
    @Order(24)
    void temporaryProviderFailureIsRetriedAndTheLetterArrivesExactlyOnce() throws Exception {
        KeycloakStub.User applicant = provisioned("buyer");
        emailMode("{\"sender\":\"platform\",\"behavior\":\"temp_error\",\"failFirst\":2,\"tempKind\":\"http_503\"}");
        try {
            publishSellerEvent(UUID.fromString(applicant.id), "seller.returned", Map.of("comment", "Исправьте данные " + UUID.randomUUID()));

            assertTrue(await(Duration.ofSeconds(90), () -> notificationIs(applicant.id, "seller-returned", "sent")), "письмо отправлено с третьей попытки");
        } finally {
            emailMode("{\"behavior\":\"accept\"}");
        }
        Map<String, Object> row = notification(applicant.id, "seller-returned");
        assertEquals(3, row.get("attempts"));
        assertNull(row.get("last_error_code"), "после успеха код прошлой ошибки очищен");
        assertEquals(1, mails(applicant.email).size());
        assertTrue(failedEvents(row.get("id")).isEmpty(), "доставленное письмо события о недоставке не даёт");
    }

    @Test
    @Order(25)
    void exhaustedAttemptsPutTheLetterInTheDeadQueueAndPublishNotificationFailed() throws Exception {
        KeycloakStub.User applicant = provisioned("buyer");
        emailMode("{\"sender\":\"platform\",\"behavior\":\"temp_error\",\"failFirst\":-1,\"tempKind\":\"http_503\"}");
        try {
            publishSellerEvent(UUID.fromString(applicant.id), "seller.returned", Map.of("comment", "Исправьте данные"));

            assertTrue(await(Duration.ofSeconds(90), () -> notificationIs(applicant.id, "seller-returned", "failed")), "после четвёртой попытки письмо недоставлено");
        } finally {
            emailMode("{\"behavior\":\"accept\"}");
        }
        Map<String, Object> row = notification(applicant.id, "seller-returned");
        assertEquals(4, row.get("attempts"));
        assertEquals(Boolean.TRUE, row.get("in_dead_queue"));
        assertEquals("provider_unavailable", row.get("last_error_code"));
        assertEquals(Boolean.TRUE, row.get("no_next"));
        assertTrue(mails(applicant.email).isEmpty(), "провайдер не принял ни одного письма");

        List<Map<String, Object>> events = failedEvents(row.get("id"));
        assertEquals(1, events.size());
        Map<String, Object> event = events.get(0);
        assertEquals(Map.of("notificationId", row.get("id"), "channel", "email", "template", "seller-returned", "recipientUserId", applicant.id,
                "attempts", 4, "reason", "attempts_exhausted"), event);
        assertFalse(event.toString().contains(applicant.email), "адреса в событии нет");
        assertTrue(await(Duration.ofSeconds(30), () -> 1 == jdbc().queryForObject("select count(*) from public.outbox where event_type = 'notification.failed' "
                + "and aggregate_id = ? and published_at is not null and topic = 'notification.events'", Integer.class, String.valueOf(row.get("id")))),
                "публикатор отправил событие в notification.events");
    }

    @Test
    @Order(26)
    void providerRefusalFailsTheLetterAtOnceWithoutRetries() throws Exception {
        KeycloakStub.User applicant = provisioned("buyer");
        emailMode("{\"sender\":\"platform\",\"behavior\":\"perm_error\",\"failFirst\":-1,\"permStatus\":422}");
        try {
            publishSellerEvent(UUID.fromString(applicant.id), "seller.returned", Map.of("comment", "Исправьте данные"));

            assertTrue(await(Duration.ofSeconds(90), () -> notificationIs(applicant.id, "seller-returned", "failed")));
        } finally {
            emailMode("{\"behavior\":\"accept\"}");
        }
        Map<String, Object> row = notification(applicant.id, "seller-returned");
        assertEquals(1, row.get("attempts"), "отказ провайдера не повторяется");
        assertEquals(Boolean.TRUE, row.get("in_dead_queue"));
        assertEquals("address_rejected", row.get("last_error_code"));
        List<Map<String, Object>> events = failedEvents(row.get("id"));
        assertEquals(1, events.size());
        assertEquals("address_rejected", events.get(0).get("reason"));
        assertEquals(1, events.get(0).get("attempts"));
    }

    @Test
    @Order(27)
    @Tag("TC-220")
    void letterForARecipientWhoIsGoneIsDroppedWithoutAnEvent() throws Exception {
        UUID nobody = UUID.randomUUID();

        publishSellerEvent(nobody, "seller.returned", Map.of("comment", "Исправьте данные"));

        assertTrue(await(Duration.ofSeconds(90), () -> notificationIs(nobody.toString(), "seller-returned", "failed")));
        Map<String, Object> row = notification(nobody.toString(), "seller-returned");
        assertEquals("recipient_unavailable", row.get("last_error_code"));
        assertEquals(0, row.get("attempts"));
        assertEquals(Boolean.FALSE, row.get("in_dead_queue"));
        assertTrue(failedEvents(row.get("id")).isEmpty());
    }

    // ---- роль продавца по seller.approved (шаг 11) ----

    @Tag("TC-013")
    @Tag("ST-13")
    @Test
    @Order(28)
    @Tag("TC-212")
    void approvedBuyerBecomesSellerWithTheProfileOneRoleEventOneAuditAndTotpInKeycloak() throws Exception {
        KeycloakStub.User applicant = provisioned("buyer");
        UUID user = UUID.fromString(applicant.id);
        UUID sellerId = UUID.randomUUID();

        publishSellerEvent(user, sellerId, "seller.approved", Map.of("sellerType", "self_employed"));

        assertTrue(await(Duration.ofSeconds(90), () -> "seller".equals(row(applicant.id).get("role"))), "роль продавца записана в базе");
        assertEquals(sellerId.toString(), jdbc().queryForObject("select seller_id::text from identity.user_account where id = ?", String.class, user));
        assertTrue(await(Duration.ofSeconds(30), () -> applicant.roles.contains("seller") && applicant.requiredActions.contains("CONFIGURE_TOTP")),
                "после фиксации Keycloak получил роль и обязательную настройку TOTP");
        assertEquals(Set.of("seller"), Set.copyOf(applicant.roles), "роль одна: «покупатель» заменена");
        assertEquals(List.of(sellerId.toString()), applicant.attributes.get("seller_id"), "атрибут для claim seller_id в токене");

        assertEquals(List.of("user.registered", "user.role-assigned", "audit.recorded"), events(applicant.id));
        String roleEvent = jdbc().queryForObject("select payload::text from public.outbox where aggregate_id = ? and event_type = 'user.role-assigned'",
                String.class, applicant.id);
        assertTrue(roleEvent.contains("\"seller\""), roleEvent);
        assertTrue(roleEvent.contains("seller_approval"), roleEvent);
        assertFalse(roleEvent.contains("assignedBy"), "роль назначила система, а не администратор");
        String audit = jdbc().queryForObject("select payload::text from public.outbox where aggregate_id = ? and event_type = 'audit.recorded'",
                String.class, applicant.id);
        assertTrue(audit.contains("\"system\""), audit);
        assertTrue(audit.contains("user.role_assigned"), audit);
        assertTrue(audit.contains(sellerId.toString()), "в изменениях виден профиль продавца");
        assertFalse(audit.contains(applicant.email), "в аудите нет адреса");
    }

    @Tag("TC-013")
    @Test
    @Order(29)
    void redeliveryOfTheApprovalChangesNothingAndNeedsNoSecondTotpWhenTheDeviceExists() throws Exception {
        KeycloakStub.User applicant = provisioned("buyer");
        applicant.otp = true;
        reconciler.runPass();
        assertEquals(Boolean.TRUE, jdbc().queryForObject("select has_2fa from identity.user_account where id = ?", Boolean.class, UUID.fromString(applicant.id)),
                "сверка увидела устройство TOTP");
        UUID user = UUID.fromString(applicant.id);
        UUID sellerId = UUID.randomUUID();

        UUID first = publishSellerEvent(user, sellerId, "seller.approved", Map.of("sellerType", "legal_entity"));
        assertTrue(await(Duration.ofSeconds(90), () -> sellerRoleHandled(first)));
        assertTrue(await(Duration.ofSeconds(30), () -> applicant.roles.contains("seller")));
        assertEquals("seller", row(applicant.id).get("role"));
        assertFalse(applicant.requiredActions.contains("CONFIGURE_TOTP"), "устройство уже есть: настраивать второй фактор повторно не нужно");

        UUID again = publishSellerEvent(user, sellerId, "seller.approved", Map.of("sellerType", "legal_entity"));
        assertTrue(await(Duration.ofSeconds(90), () -> sellerRoleHandled(again)), "повторное решение разобрано");

        assertEquals(List.of("user.registered", "user.role-assigned", "audit.recorded"), events(applicant.id), "повтор не добавил событий");
        assertEquals(sellerId.toString(), jdbc().queryForObject("select seller_id::text from identity.user_account where id = ?", String.class, user));
        assertEquals(Set.of("seller"), Set.copyOf(applicant.roles));
    }

    @Tag("TC-013")
    @Tag("ST-13")
    @Test
    @Order(30)
    void keycloakOutageAtApprovalLeavesTheDecisionInPlaceAndTheReconcilerCatchesKeycloakUp() throws Exception {
        KeycloakStub.User applicant = provisioned("buyer");
        UUID user = UUID.fromString(applicant.id);
        UUID sellerId = UUID.randomUUID();
        int before = KEYCLOAK.requests().size();

        KEYCLOAK.down(true);
        try {
            publishSellerEvent(user, sellerId, "seller.approved", Map.of("sellerType", "self_employed"));

            assertTrue(await(Duration.ofSeconds(90), () -> "seller".equals(row(applicant.id).get("role"))), "решение записано, пока Keycloak недоступен");
            assertTrue(await(Duration.ofSeconds(30), () -> KEYCLOAK.requests().stream().skip(before).anyMatch(r -> r.contains("/users/" + applicant.id))),
                    "платформа обратилась к Keycloak и получила отказ");
        } finally {
            KEYCLOAK.down(false);
        }
        assertFalse(applicant.roles.contains("seller"), "Keycloak ещё не знает о роли");
        assertEquals(List.of("user.registered", "user.role-assigned", "audit.recorded"), events(applicant.id), "событие не ушло в повторы");

        reconciler.runPass();

        assertEquals(Set.of("seller"), Set.copyOf(applicant.roles), "сверка довела роль в Keycloak до базы");
        assertEquals(List.of(sellerId.toString()), applicant.attributes.get("seller_id"));
        assertTrue(applicant.requiredActions.contains("CONFIGURE_TOTP"), "второй фактор обязателен и после догонки");
        assertEquals(List.of("user.registered", "user.role-assigned", "audit.recorded"), events(applicant.id), "сверка событий роли не пишет повторно");
    }

    @Tag("ST-13")
    @Test
    @Order(31)
    void approvalNeverDemotesAStaffMember() throws Exception {
        KeycloakStub.User moderator = KEYCLOAK.add("moderator", null, null);
        reconciler.runPass();
        UUID staff = UUID.fromString(moderator.id);

        UUID event = publishSellerEvent(staff, UUID.randomUUID(), "seller.approved", Map.of("sellerType", "self_employed"));

        assertTrue(await(Duration.ofSeconds(90), () -> sellerRoleHandled(event)), "событие разобрано и проигнорировано");
        assertEquals("moderator", row(moderator.id).get("role"), "сотрудник заявкой продавца не понижается");
        assertNull(jdbc().queryForObject("select seller_id from identity.user_account where id = ?", UUID.class, staff));
        assertEquals(List.of("user.registered"), events(moderator.id));
        assertEquals(Set.of("moderator"), Set.copyOf(moderator.roles));
    }

    @Tag("TC-013")
    @Test
    @Order(32)
    void approvalForAnAccountThePlatformHasNotYetCreatedIsRetriedUntilTheReconcilerCreatesIt() throws Exception {
        KeycloakStub.User applicant = KEYCLOAK.add("buyer", null, null);
        UUID user = UUID.fromString(applicant.id);
        UUID sellerId = UUID.randomUUID();
        assertEquals(0, count("select count(*) from identity.user_account where id = ?", user));

        UUID event = publishSellerEvent(user, sellerId, "seller.approved", Map.of("sellerType", "self_employed"));
        Thread.sleep(500);
        reconciler.runPass();

        assertTrue(await(Duration.ofSeconds(90), () -> sellerRoleHandled(event)), "повтор нашёл созданную запись");
        assertEquals("seller", row(applicant.id).get("role"));
        assertEquals(sellerId.toString(), jdbc().queryForObject("select seller_id::text from identity.user_account where id = ?", String.class, user));
        assertEquals(List.of("user.registered", "user.role-assigned", "audit.recorded"), events(applicant.id));
        assertTrue(await(Duration.ofSeconds(30), () -> applicant.roles.contains("seller")));
    }

    // ---- заказ, выдача и поддержка (шаг 15 Ф4): события соседей в Kafka, письма и обращения системы ----

    private static final String NBSP = "\u00A0";

    private JdbcTemplate supportDb() {
        return databases.jdbc("support");
    }

    /** Короткий номер заказа события: уникален в модели чтения, поэтому каждый тест берёт свой из диапазона, который не пересекается с соседями. */
    private static long freshOrderNumber() {
        return ThreadLocalRandom.current().nextLong(5_000_000L, 9_000_000L);
    }

    private static Map<String, Object> money(long amount) {
        Map<String, Object> money = new LinkedHashMap<>();
        money.put("amount", amount);
        money.put("currency", "RUB");
        return money;
    }

    private static String now() {
        return Timestamps.format(Instant.now());
    }

    /** {@code order.paid} от имени order-service; в {@code delivery.address} лежит адрес из снимка, которого в очереди писем быть не должно (INV-44). */
    private static UUID publishPaid(UUID orderId, long number, UUID buyerId, String title, String snapshotAddress) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", orderId.toString());
        data.put("orderNumber", number);
        data.put("buyerId", buyerId.toString());
        data.put("sellerId", UUID.randomUUID().toString());
        data.put("productId", UUID.randomUUID().toString());
        data.put("productTitle", title);
        data.put("quantity", 1);
        data.put("total", money(149900));
        data.put("commission", money(2998));
        data.put("paidAt", now());
        data.put("latePayment", false);
        data.put("delivery", Map.of("channel", "email", "address", snapshotAddress));
        UUID eventId = UUID.randomUUID();
        publish("order-service", "order.events", "Order", orderId.toString(), "order.paid", eventId, data);
        return eventId;
    }

    private static UUID publishCreated(UUID orderId, long number, UUID buyerId) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", orderId.toString());
        data.put("orderNumber", number);
        data.put("buyerId", buyerId.toString());
        data.put("sellerId", UUID.randomUUID().toString());
        data.put("productId", UUID.randomUUID().toString());
        data.put("quantity", 1);
        data.put("unitPrice", money(149900));
        data.put("total", money(149900));
        data.put("commission", money(2998));
        data.put("reserveExpiresAt", Timestamps.format(Instant.now().plus(Duration.ofMinutes(15))));
        data.put("paymentSessionExpiresAt", "2026-10-03T12:12:02.000Z");
        UUID eventId = UUID.randomUUID();
        publish("order-service", "order.events", "Order", orderId.toString(), "order.created", eventId, data);
        return eventId;
    }

    private static UUID publishRefunded(UUID orderId, long number, UUID buyerId) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", orderId.toString());
        data.put("orderNumber", number);
        data.put("buyerId", buyerId.toString());
        data.put("sellerId", UUID.randomUUID().toString());
        data.put("total", money(149900));
        data.put("refundReason", "late_payment");
        UUID eventId = UUID.randomUUID();
        publish("order-service", "order.events", "Order", orderId.toString(), "order.refunded", eventId, data);
        return eventId;
    }

    /** Событие выдачи от имени delivery-service; ключ записи и {@code subject} это заказ, как у настоящего издателя. */
    private static UUID publishDelivery(UUID eventId, UUID orderId, String type, Map<String, Object> extra) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deliveryId", UUID.randomUUID().toString());
        data.put("orderId", orderId.toString());
        data.put("deliveryType", "primary");
        data.putAll(extra);
        publish("delivery-service", "delivery.events", "Delivery", orderId.toString(), type, eventId, data);
        return eventId;
    }

    private static UUID publishDelivery(UUID orderId, String type, Map<String, Object> extra) throws Exception {
        return publishDelivery(UUID.randomUUID(), orderId, type, extra);
    }

    private static UUID publishFailed(UUID orderId, String reason) throws Exception {
        return publishDelivery(orderId, "delivery.failed", Map.of("reason", reason, "attempts", 6));
    }

    private boolean viewExists(UUID orderId) {
        return supportDb().queryForObject("select count(*) from support.order_view where order_id = ?", Integer.class, orderId) == 1;
    }

    private boolean deliveryStatusIs(UUID orderId, String status) {
        return supportDb().queryForList("select delivery_status from support.order_view where order_id = ?", String.class, orderId).contains(status);
    }

    private boolean handledBySupport(UUID eventId) {
        return supportDb().queryForObject("select count(*) from public.processed_event where consumer = 'support-event-handler' and event_id = ?",
                Integer.class, eventId) == 1;
    }

    private int tickets(UUID orderId) {
        return supportDb().queryForObject("select count(*) from support.ticket where order_id = ?", Integer.class, orderId);
    }

    private List<String> ticketEvents(UUID ticketId) {
        return jdbc().queryForList("select event_type from public.outbox where aggregate_id = ? order by id", String.class, ticketId.toString());
    }

    private Map<String, Object> ticketPayload(UUID ticketId, String type) {
        return Json.parseObject(jdbc().queryForObject("select payload::text from public.outbox where aggregate_id = ? and event_type = ?",
                String.class, ticketId.toString(), type));
    }

    /** Модель чтения заказа на стенде строится по настоящему {@code order.paid}, поэтому события выдачи публикуются только после неё: события разных тем не упорядочены. */
    private void paidAndSeen(UUID orderId, long number, UUID buyerId, String address) throws Exception {
        publishPaid(orderId, number, buyerId, "Ключ " + orderId, address);
        assertTrue(await(Duration.ofSeconds(90), () -> viewExists(orderId)), "модель чтения заказа создана по order.paid");
    }

    /** Письма на адрес, в которых упомянут заказ: администраторы получают и письма чужих заказов, их тест не считает. */
    private static List<Map<?, ?>> mailsAbout(String address, UUID orderId) {
        return mails(address).stream().filter(m -> String.valueOf(m.get("text")).contains(orderId.toString())).toList();
    }

    private static String textAbout(String address, String subject, UUID orderId) {
        return mailsAbout(address, orderId).stream().filter(m -> subject.equals(m.get("subject"))).map(m -> String.valueOf(m.get("text"))).findFirst()
                .orElseThrow(() -> new AssertionError("нет письма «" + subject + "» о заказе " + orderId + " для " + address));
    }

    @Tag("TC-609")
    @Test
    @Order(33)
    void failedDeliveryOpensOneSystemTicketAndTheDeliveredEventClosesItWithoutDuplicates() throws Exception {
        KeycloakStub.User buyer = provisioned("buyer");
        UUID buyerId = UUID.fromString(buyer.id);
        UUID orderId = UUID.randomUUID();
        long number = freshOrderNumber();

        paidAndSeen(orderId, number, buyerId, "snapshot-" + UUID.randomUUID() + "@mail.example");
        Map<String, Object> view = supportDb().queryForMap("select order_number, buyer_id::text as buyer_id, order_status, delivery_status "
                + "from support.order_view where order_id = ?", orderId);
        assertEquals(number, ((Number) view.get("order_number")).longValue());
        assertEquals(buyer.id, view.get("buyer_id"));
        assertEquals("paid", view.get("order_status"));
        assertNull(view.get("delivery_status"));

        publishDelivery(orderId, "delivery.accepted", Map.of("attemptNo", 1, "acceptedAt", now()));
        assertTrue(await(Duration.ofSeconds(90), () -> deliveryStatusIs(orderId, "sent")), "принятое провайдером письмо даёт статус «отправлено»");
        assertEquals(0, tickets(orderId));

        UUID failed = publishFailed(orderId, "attempts_exhausted");
        assertTrue(await(Duration.ofSeconds(90), () -> tickets(orderId) == 1), "обращение создано по delivery.failed");
        Map<String, Object> ticket = supportDb().queryForMap("select id::text as id, buyer_id::text as buyer_id, source, reason, status, resolved_by, "
                + "operator_id is null as no_operator from support.ticket where order_id = ?", orderId);
        UUID ticketId = UUID.fromString((String) ticket.get("id"));
        assertEquals(buyer.id, ticket.get("buyer_id"));
        assertEquals("system", ticket.get("source"));
        assertEquals("attempts_exhausted", ticket.get("reason"));
        assertEquals("created", ticket.get("status"));
        assertNull(ticket.get("resolved_by"));
        assertEquals(Boolean.TRUE, ticket.get("no_operator"));
        assertTrue(deliveryStatusIs(orderId, "failed"));
        assertEquals(List.of("ticket.created"), ticketEvents(ticketId), "событие записано в Outbox вместе с обращением");
        Map<String, Object> created = ticketPayload(ticketId, "ticket.created");
        assertEquals(Set.of("ticketId", "orderId", "buyerId", "source", "reason"), created.keySet(), "в событии нет адресов, ключей и лишних полей");
        assertEquals(orderId.toString(), created.get("orderId"));
        assertEquals("system", created.get("source"));
        assertEquals(1, count("select count(*) from public.outbox where aggregate_id = ? and topic = 'support.events'", ticketId.toString()));

        // Тот же конверт второй раз, другая неудача и просроченная выдача того же заказа: обращение остаётся одно
        publishDelivery(failed, orderId, "delivery.failed", Map.of("reason", "attempts_exhausted", "attempts", 6));
        UUID other = publishFailed(orderId, "address_rejected");
        UUID overdue = publishDelivery(orderId, "delivery.overdue", Map.of("paidAt", now(), "thresholdSeconds", 1800));
        assertTrue(await(Duration.ofSeconds(90), () -> handledBySupport(other) && handledBySupport(overdue)), "события разобраны");
        assertEquals(1, tickets(orderId), "по заказу одно открытое обращение (INV-21)");
        assertEquals(List.of("ticket.created"), ticketEvents(ticketId), "второго ticket.created нет");

        publishDelivery(orderId, "delivery.delivered", Map.of("deliveredAt", now()));
        assertTrue(await(Duration.ofSeconds(90), () -> "resolved".equals(supportDb().queryForObject(
                "select status from support.ticket where id = ?", String.class, ticketId))), "доставка закрывает обращение");
        assertEquals("system", supportDb().queryForObject("select resolved_by from support.ticket where id = ?", String.class, ticketId));
        assertTrue(deliveryStatusIs(orderId, "delivered"));
        assertEquals(List.of("ticket.created", "ticket.resolved"), ticketEvents(ticketId));
        Map<String, Object> resolved = ticketPayload(ticketId, "ticket.resolved");
        assertEquals(Set.of("ticketId", "orderId", "buyerId", "resolvedBy"), resolved.keySet());
        assertEquals("system", resolved.get("resolvedBy"));
        assertEquals(1, tickets(orderId), "закрытое обращение не заменяется новым");
    }

    @Tag("TC-610")
    @Test
    @Order(34)
    void sixLettersOfTheSecondSliceReachTheBuyerByTheAccountAddressAndTheAdministratorsByTheOrderId() throws Exception {
        KeycloakStub.User buyer = provisioned("buyer");
        KeycloakStub.User firstAdmin = provisioned("admin");
        KeycloakStub.User secondAdmin = provisioned("admin");
        UUID buyerId = UUID.fromString(buyer.id);
        UUID orderId = UUID.randomUUID();
        long number = freshOrderNumber();
        String snapshot = "snapshot-" + UUID.randomUUID() + "@mail.example";

        publishCreated(orderId, number, buyerId);
        paidAndSeen(orderId, number, buyerId, snapshot);
        publishRefunded(orderId, number, buyerId);
        assertTrue(await(Duration.ofSeconds(90), () -> mails(buyer.email).size() == 3), "три письма покупателю дошли до заглушки почты");

        String created = String.valueOf(mailWithSubject(buyer.email, "Заказ создан и ждёт оплаты").get("text"));
        assertTrue(created.contains("Заказ №" + number + " на сумму 1" + NBSP + "499,00" + NBSP + "₽ создан и ждёт оплаты."), created);
        assertTrue(created.contains(SITE + "/account/orders/" + orderId + "\n"), "ссылка на страницу заказа собрана из адреса сайта: " + created);
        String paid = String.valueOf(mailWithSubject(buyer.email, "Заказ оплачен").get("text"));
        assertTrue(paid.contains("Оплата заказа №" + number + " получена: Ключ " + orderId + ", сумма 1" + NBSP + "499,00" + NBSP + "₽."), paid);
        assertFalse(paid.contains(snapshot), "адрес из снимка заказа в письме не используется");
        String refunded = String.valueOf(mailWithSubject(buyer.email, "Деньги за заказ возвращены").get("text"));
        assertTrue(refunded.contains("По заказу №" + number + " мы вернули вам 1" + NBSP + "499,00" + NBSP + "₽. Причина: оплата пришла после"), refunded);
        assertTrue(mails(snapshot).isEmpty(), "на адрес из снимка заказа писем нет: адрес письму даёт identity в момент отправки (INV-44)");
        for (String text : List.of(created, paid, refunded)) {
            assertTrue(text.startsWith("Здравствуйте, "), text);
            assertFalse(text.contains(buyer.email), "адреса получателя в тексте письма нет");
        }

        publish("payment-service", "payment.events", "Payment", orderId.toString(), "payment.refund-escalated", UUID.randomUUID(),
                Map.of("paymentId", UUID.randomUUID().toString(), "orderId", orderId.toString(), "amount", money(149900), "attempts", 5));
        publishFailed(orderId, "attempts_exhausted");
        publishDelivery(orderId, "delivery.overdue", Map.of("paidAt", "2026-10-03T12:03:41.000Z", "thresholdSeconds", 1800));
        for (KeycloakStub.User admin : List.of(firstAdmin, secondAdmin)) {
            assertTrue(await(Duration.ofSeconds(90), () -> mailsAbout(admin.email, orderId).size() == 3), "три письма администратору " + admin.email);
            Set<String> subjects = new java.util.HashSet<>();
            for (Map<?, ?> mail : mailsAbout(admin.email, orderId)) {
                subjects.add(String.valueOf(mail.get("subject")));
            }
            assertEquals(Set.of("Возврат денег не удался, нужна ручная обработка", "Ключ не доставлен покупателю", "Ключ не доставлен вовремя"), subjects);
            String escalated = textAbout(admin.email, "Возврат денег не удался, нужна ручная обработка", orderId);
            assertTrue(escalated.contains("по заказу " + orderId + " на сумму 1" + NBSP + "499,00" + NBSP + "₽ не удался после 5 попыток"), escalated);
            String failed = textAbout(admin.email, "Ключ не доставлен покупателю", orderId);
            assertTrue(failed.contains("закончилась ошибкой: письмо не принято после всех попыток отправки (попыток отправки: 6)"), failed);
            String overdue = textAbout(admin.email, "Ключ не доставлен вовремя", orderId);
            assertTrue(overdue.contains("оплачен 03.10.2026 15:03 (МСК), но ключ не доставлен покупателю за 30 мин"), overdue);
            assertFalse(failed.contains(buyer.email) || overdue.contains(buyer.email) || escalated.contains(buyer.email), "адреса покупателя в письмах администратору нет");
        }
        assertEquals(3, mails(buyer.email).size(), "оповещения администраторов покупателю не уходят");

        // Строки очереди: у каждого получателя своя, параметры без адресов, ключей и персональных данных
        for (String template : List.of("order-created", "order-paid", "order-refunded")) {
            assertTrue(await(Duration.ofSeconds(20), () -> notificationIs(buyer.id, template, "sent")), template);
        }
        for (KeycloakStub.User admin : List.of(firstAdmin, secondAdmin)) {
            for (String template : List.of("payment-refund-escalated", "delivery-failed", "delivery-overdue")) {
                assertTrue(await(Duration.ofSeconds(20), () -> notificationIs(admin.id, template, "sent")), template);
                String row = wholeRow(notification(admin.id, template).get("id"));
                assertFalse(row.contains(admin.email) || row.contains(buyer.email), template + ": адресов в таблице уведомлений нет (INV-44)");
            }
        }
        assertEquals(0, notificationDb().queryForObject("select count(*) from notification.notification n where n::text like '%' || ? || '%'",
                Integer.class, snapshot), "адрес из order.paid в очередь писем не попал");
        assertEquals(0, notificationDb().queryForObject("select count(*) from notification.notification where params::text ~* '\"(email|phone|name|address|recipient)\"'",
                Integer.class), "в параметрах шаблонов нет ключей с персональными данными");
        assertEquals(2, notificationDb().queryForObject("select count(*) from notification.notification where template = 'delivery-failed' "
                + "and params ->> 'orderId' = ? and user_id in (?, ?)", Integer.class, orderId.toString(), UUID.fromString(firstAdmin.id),
                UUID.fromString(secondAdmin.id)), "одно событие дало по строке каждому администратору");
    }

    @Tag("TC-041")
    @Tag("concurrency")
    @Test
    @Order(35)
    void parallelDeliveryFailuresOfOneOrderLeaveOneOpenTicketAndOneEvent() throws Exception {
        KeycloakStub.User buyer = provisioned("buyer");
        UUID orderId = UUID.randomUUID();
        paidAndSeen(orderId, freshOrderNumber(), UUID.fromString(buyer.id), "snapshot-" + UUID.randomUUID() + "@mail.example");

        // Участники гонки работают так же, как обработчик: каждый в своей транзакции модуля support (пул роли app_support держит два соединения)
        List<HandleResult> results = Concurrent.race(6, call ->
                modules.transactions("support").execute(status -> support.onDeliveryFailed(orderId, "attempts_exhausted", Instant.now())));

        assertEquals(1, results.stream().filter(HandleResult.done()::equals).count(), "обращение создал один участник: " + results);
        assertEquals(5, results.stream().filter(HandleResult.ignored("ticket-already-open")::equals).count(), "остальные увидели открытое обращение: " + results);
        assertEquals(1, tickets(orderId));
        assertEquals(1, count("select count(*) from public.outbox where event_type = 'ticket.created' and payload ->> 'orderId' = ?", orderId.toString()),
                "одно событие ticket.created на обращение");

        // Тот же заказ с другой стороны: десять событий delivery.failed по Kafka с разными идентификаторами
        List<UUID> events = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            events.add(publishFailed(orderId, i % 2 == 0 ? "attempts_exhausted" : "address_rejected"));
        }
        assertTrue(await(Duration.ofSeconds(90), () -> events.stream().allMatch(this::handledBySupport)), "все десять событий разобраны");
        assertEquals(1, tickets(orderId), "обращение по-прежнему одно");
        assertEquals(1, count("select count(*) from public.outbox where event_type = 'ticket.created' and payload ->> 'orderId' = ?", orderId.toString()));
        assertEquals(0, supportDb().queryForObject("select count(*) from (select order_id from support.ticket where status in ('created', 'in_progress') "
                + "group by order_id having count(*) > 1) d", Integer.class), "дублей открытых обращений в базе нет");
    }

    // ---- очередь недоставленных (шаг 16 Ф4): запись в .dlq и возврат тем же способом, что у tools/ops/dlq_replay.py ----

    /**
     * Записи темы недоставленных с ключом {@code key}: от имени kafka-init (суперпользователь стенда, у сервисов права читать {@code .dlq} нет),
     * с начала, всеми разделами, без группы потребителей. Читает, пока не наберётся {@code min} записей или не выйдет срок.
     */
    private static List<ConsumerRecord<String, String>> deadLetters(String topic, String key, int min, Duration timeout) {
        Map<String, Object> props = new LinkedHashMap<>(Stand.kafkaSecurity("kafka-init"));
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Stand.kafkaBootstrap());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, "platform-it-dlq-reader");
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream().map(info -> new TopicPartition(topic, info.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline && found.size() < min) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        found.add(record);
                    }
                }
            }
        }
        return found;
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /** Возвращает запись в исходную тему, как {@code dlq_replay.py replay}: тот же ключ и значение, заголовки без {@code dlq-*}, плюс {@code dlq-replayed-from}. */
    private static void replay(ConsumerRecord<String, String> dead, String publisher) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(header(dead, "dlq-original-topic"), dead.key(), dead.value());
        for (Header header : dead.headers()) {
            if (!header.key().startsWith("dlq-")) {
                record.headers().add(header.key(), header.value());
            }
        }
        record.headers().add("dlq-replayed-from", (dead.topic() + " " + dead.partition() + ":" + dead.offset()).getBytes(StandardCharsets.UTF_8));
        producer(publisher).send(record).get(20, TimeUnit.SECONDS);
    }

    private int deliveryFailedLetters(UUID orderId) {
        return notificationDb().queryForObject("select count(*) from notification.notification where template = 'delivery-failed' and params ->> 'orderId' = ?",
                Integer.class, orderId.toString());
    }

    @Tag("TC-611")
    @Test
    @Order(36)
    void deliveryEventBeforeTheOrderGoesToTheDlqAndTheReplayAppliesItOnceWhileTheSecondReplayChangesNothing() throws Exception {
        KeycloakStub.User buyer = provisioned("buyer");
        provisioned("admin");
        UUID orderId = UUID.randomUUID();

        // delivery.failed раньше order.paid: модели чтения заказа нет, обработчик поддержки повторяет 1, 5 и 25 секунд и сдаётся (поток потребителя один,
        // order.paid из соседней темы за это время не прочитать)
        UUID failed = publishFailed(orderId, "attempts_exhausted");
        List<ConsumerRecord<String, String>> dead = deadLetters("delivery.events.dlq", orderId.toString(), 1, Duration.ofSeconds(150));
        assertEquals(1, dead.size(), "запись попала в delivery.events.dlq");
        ConsumerRecord<String, String> record = dead.get(0);
        assertEquals("delivery.events", header(record, "dlq-original-topic"));
        assertEquals("support-event-handler", header(record, "dlq-consumer"));
        assertEquals("4", header(record, "dlq-attempts"), "первая попытка и три повтора");
        assertEquals("dgm.kit.events.RetryableEventException", header(record, "dlq-error-class"));
        assertTrue(header(record, "dlq-failed-at").endsWith("Z"));
        assertTrue(Long.parseLong(header(record, "dlq-original-offset")) >= 0);
        assertNotNull(header(record, "dlq-original-partition"));
        assertNotNull(header(record, "dlq-error-message"));
        assertEquals("delivery.failed", header(record, "ce_type"), "заголовки исходной записи сохранены");
        assertNotNull(header(record, "traceparent"));
        assertTrue(record.value().contains(failed.toString()), "значение записи это исходный конверт события");
        assertFalse(handledBySupport(failed), "обработчик поддержки событие не применил");
        assertEquals(0, tickets(orderId));
        int letters = deliveryFailedLetters(orderId);
        assertTrue(letters >= 1, "письмо администратору по тому же событию ушло с первого раза: его обработчик отказа не получил");

        // Причина снята: заказ появился. Возврат записи: поддержка применяет событие, обработчик писем получает дубль
        paidAndSeen(orderId, freshOrderNumber(), UUID.fromString(buyer.id), "snapshot-" + UUID.randomUUID() + "@mail.example");
        replay(record, "delivery-service");
        assertTrue(await(Duration.ofSeconds(90), () -> tickets(orderId) == 1 && handledBySupport(failed)), "обращение создано при возврате");
        UUID ticketId = UUID.fromString(supportDb().queryForObject("select id::text from support.ticket where order_id = ?", String.class, orderId));
        assertEquals(List.of("ticket.created"), ticketEvents(ticketId));
        assertEquals(letters, deliveryFailedLetters(orderId), "второго письма администратору нет: дубль отсеян по (обработчик, событие)");

        // Второй возврат той же записи: события одного заказа идут в один раздел по порядку, поэтому после метки дубль уже разобран
        replay(record, "delivery-service");
        UUID marker = publishDelivery(orderId, "delivery.overdue", Map.of("paidAt", now(), "thresholdSeconds", 1800));
        assertTrue(await(Duration.ofSeconds(90), () -> handledBySupport(marker)), "метка после дубля разобрана");
        assertEquals(1, tickets(orderId), "обращение по-прежнему одно");
        assertEquals(List.of("ticket.created"), ticketEvents(ticketId), "второго ticket.created нет");
        assertEquals(letters, deliveryFailedLetters(orderId), "повтор письма не создал");
        assertTrue(handledBySupport(failed), "событие отмечено разобранным один раз");
        assertEquals(1, deadLetters("delivery.events.dlq", orderId.toString(), Integer.MAX_VALUE, Duration.ofSeconds(5)).size(),
                "дубль в .dlq не вернулся: запись там одна");
    }

    // ---- журнал аудита (шаг 4 среза 3): потребитель audit-consumer, неизменяемость, секции ----

    /** Журнал под рабочей ролью приёмника {@code app_audit_writer}: только {@code INSERT} и {@code SELECT}. */
    private JdbcTemplate auditDb() {
        return databases.jdbc("audit-writer");
    }

    /** Журнал под владельцем схемы (ролью-мигратором): ему права есть, неизменяемость держат триггеры. */
    private static JdbcTemplate auditOwnerDb() {
        return new JdbcTemplate(Stand.dataSource("migrator_platform", "platform_db"));
    }

    private int auditRows(UUID eventId) {
        return auditDb().queryForObject("select count(*) from audit_admin.audit_log where event_id = ?", Integer.class, eventId);
    }

    private static Map<String, Object> staffAudit(UUID objectId, String action) {
        return AuditRecords.staffAction(Instant.now(), UUID.randomUUID(), "moderator", "203.0.113.7", action, "seller", objectId.toString(),
                List.of(AuditRecords.change("status", "on_review", "approved"), AuditRecords.change("score", 3, 5)));
    }

    private static UUID publishAudit(UUID subject, Map<String, Object> data) throws Exception {
        UUID eventId = UUID.randomUUID();
        publish("catalog-service", AuditRecords.TOPIC, AuditRecords.AGGREGATE, subject.toString(), AuditRecords.EVENT, eventId, data);
        return eventId;
    }

    /** Код SQLSTATE отказа базы: у отклонённой операции это {@code 42501} (нет права) или {@code 23001} (запретил триггер), у принятой {@code none}. */
    private static String sqlState(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql) {
                    return sql.getSQLState();
                }
            }
            throw e;
        }
        return "none";
    }

    @Tag("TC-813")
    @Test
    @Order(37)
    void staffAuditEventBecomesOneJournalRecordAndARedeliveryAndAFollowingEventAddNothingExtra() throws Exception {
        UUID object = UUID.randomUUID();
        Map<String, Object> data = staffAudit(object, "seller.approved");
        UUID eventId = publishAudit(object, data);
        assertTrue(await(Duration.ofSeconds(90), () -> auditRows(eventId) == 1), "запись появилась в журнале");

        Map<String, Object> row = auditDb().queryForMap("select id, actor_id::text as actor_id, actor_role, action, object_type, object_id, actor_ip, "
                + "actor_ip_hashed, correlation_id::text as correlation_id, occurred_at, recorded_at, "
                + "changes @> '[{\"field\":\"status\",\"personal\":false,\"before\":\"on_review\",\"after\":\"approved\"},"
                + "{\"field\":\"score\",\"personal\":false,\"before\":3,\"after\":5}]'::jsonb as changes_ok, jsonb_array_length(changes) as changes_count "
                + "from audit_admin.audit_log where event_id = ?", eventId);
        assertEquals(7, ((UUID) row.get("id")).version(), "RecordID это UUID версии 7");
        assertEquals(data.get("actorId"), row.get("actor_id"));
        assertEquals("moderator", row.get("actor_role"));
        assertEquals("seller.approved", row.get("action"));
        assertEquals("seller", row.get("object_type"));
        assertEquals(object.toString(), row.get("object_id"));
        assertEquals(data.get("correlationId"), row.get("correlation_id"));
        assertEquals(Instant.parse((String) data.get("occurredAt")), ((java.sql.Timestamp) row.get("occurred_at")).toInstant(),
                "время действия взято из события");
        assertTrue(((java.sql.Timestamp) row.get("recorded_at")).toInstant().isAfter(((java.sql.Timestamp) row.get("occurred_at")).toInstant().minusSeconds(1)));
        assertEquals(Boolean.TRUE, row.get("changes_ok"), "значения до и после записаны как есть: строка и число");
        assertEquals(2, row.get("changes_count"));
        assertEquals(1, count("select count(*) from public.processed_event where consumer = 'audit-consumer' and event_id = ?", eventId));

        // Повторная доставка того же события (тот же идентификатор), затем метка: когда метка в журнале, дубль уже разобран
        publish("catalog-service", AuditRecords.TOPIC, AuditRecords.AGGREGATE, object.toString(), AuditRecords.EVENT, eventId, data);
        UUID markerObject = UUID.randomUUID();
        UUID marker = publishAudit(markerObject, staffAudit(markerObject, "seller.rejected"));
        assertTrue(await(Duration.ofSeconds(90), () -> auditRows(marker) == 1), "метка после дубля записана");
        assertEquals(1, auditRows(eventId), "дубль записи не создал");
        assertEquals(1, count("select count(*) from public.processed_event where consumer = 'audit-consumer' and event_id = ?", eventId));

        // Два одинаковых действия подряд это два события с разными идентификаторами, и запись у каждого своя
        UUID second = publishAudit(object, data);
        assertTrue(await(Duration.ofSeconds(90), () -> auditRows(second) == 1), "второе событие с тем же содержимым записано");
        assertEquals(2, auditDb().queryForObject("select count(*) from audit_admin.audit_log where object_id = ? and action = 'seller.approved'",
                Integer.class, object.toString()));
    }

    @Tag("TC-813")
    @Test
    @Order(38)
    void openPersonalValueInAnAuditEventGoesToTheDlqAtOnceWithoutRepeatsAndLeavesNoRecord() throws Exception {
        UUID object = UUID.randomUUID();
        Map<String, Object> open = new LinkedHashMap<>();
        open.put("field", "email");
        open.put("personal", true);
        open.put("before", null);
        open.put("after", "ivan.petrov@mail.example");
        Map<String, Object> data = staffAudit(object, "user.email_changed");
        data.put("changes", List.of(open));
        UUID eventId = publishAudit(object, data);

        List<ConsumerRecord<String, String>> dead = deadLetters("audit.events.dlq", object.toString(), 1, Duration.ofSeconds(60));
        assertEquals(1, dead.size(), "запись попала в audit.events.dlq");
        ConsumerRecord<String, String> record = dead.get(0);
        assertEquals("audit.events", header(record, "dlq-original-topic"));
        assertEquals("audit-consumer", header(record, "dlq-consumer"));
        assertEquals("1", header(record, "dlq-attempts"), "неисправимая ошибка: повторов нет");
        assertEquals("dgm.kit.events.FatalEventException", header(record, "dlq-error-class"));
        assertTrue(header(record, "dlq-error-message").contains("email"), "в сообщении есть имя поля");
        assertFalse(header(record, "dlq-error-message").contains("ivan.petrov"), "а значения нет");
        assertEquals(0, auditRows(eventId));
        assertEquals(0, count("select count(*) from public.processed_event where consumer = 'audit-consumer' and event_id = ?", eventId),
                "отметка об обработке откатилась вместе с отказом: возврат записи из DLQ после исправления пройдёт");
        assertEquals(0, auditDb().queryForObject("select count(*) from audit_admin.audit_log where object_id = ?", Integer.class, object.toString()));
    }

    @Tag("TC-813")
    @Test
    @Order(39)
    void twoConsumersWithOneEventAtOnceLeaveOneRecord() {
        UUID object = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Map<String, Object> data = staffAudit(object, "seller.blocked");
        List<Boolean> applied = Concurrent.race(8, index -> auditRecords.record(eventId, data).applied());
        assertEquals(1, applied.stream().filter(Boolean::booleanValue).count(), "применило ровно одно из восьми одновременных обращений");
        assertEquals(1, auditRows(eventId));
    }

    @Tag("TC-814")
    @Test
    @Order(40)
    void staffAddressIsStoredAsIsAndAUserAddressOnlyAsAHash() throws Exception {
        String hash = "9f2c4b1ad03e47f6a8b5c1d2e3f40516273849a0b1c2d3e4f5061728394a5b6c";
        UUID staffObject = UUID.randomUUID();
        UUID staffEvent = publishAudit(staffObject, staffAudit(staffObject, "seller.approved"));

        UUID userObject = UUID.randomUUID();
        Map<String, Object> userData = staffAudit(userObject, "user.phone_confirmed");
        userData.put("actorRole", "buyer");
        userData.put("objectType", "user");
        userData.put("actorIp", hash);
        userData.put("actorIpHashed", true);
        userData.put("changes", List.of(AuditRecords.personalChange("phone", null, "a".repeat(64)), AuditRecords.change("phone_confirmed", false, true)));
        UUID userEvent = publishAudit(userObject, userData);

        UUID systemObject = UUID.randomUUID();
        Map<String, Object> systemData = staffAudit(systemObject, "user.role_assigned");
        systemData.put("actorRole", "system");
        systemData.put("actorIp", "");
        UUID systemEvent = publishAudit(systemObject, systemData);

        // Адрес пользователя открытым значением: форма не принята, запись не создана
        UUID rawObject = UUID.randomUUID();
        Map<String, Object> rawData = new LinkedHashMap<>(userData);
        rawData.put("actorIp", "198.51.100.23");
        UUID rawEvent = publishAudit(rawObject, rawData);

        assertTrue(await(Duration.ofSeconds(90), () -> auditRows(staffEvent) == 1 && auditRows(userEvent) == 1 && auditRows(systemEvent) == 1),
                "три записи в журнале");
        assertEquals(1, deadLetters("audit.events.dlq", rawObject.toString(), 1, Duration.ofSeconds(60)).size(), "открытый адрес пользователя ушёл в DLQ");
        assertEquals(0, auditRows(rawEvent));

        Map<String, Object> staff = auditDb().queryForMap("select actor_ip, actor_ip_hashed from audit_admin.audit_log where event_id = ?", staffEvent);
        assertEquals("203.0.113.7", staff.get("actor_ip"), "адрес сотрудника как есть");
        assertEquals(Boolean.FALSE, staff.get("actor_ip_hashed"));
        Map<String, Object> user = auditDb().queryForMap("select actor_ip, actor_ip_hashed, changes::text as changes from audit_admin.audit_log where event_id = ?",
                userEvent);
        assertEquals(hash, user.get("actor_ip"), "адрес пользователя хешем");
        assertEquals(Boolean.TRUE, user.get("actor_ip_hashed"));
        assertFalse(((String) user.get("changes")).contains("+7"), "телефона в записи нет");
        Map<String, Object> system = auditDb().queryForMap("select actor_ip, actor_ip_hashed from audit_admin.audit_log where event_id = ?", systemEvent);
        assertNull(system.get("actor_ip"), "у действия системы адреса нет");
        assertEquals(Boolean.FALSE, system.get("actor_ip_hashed"));
    }

    @Tag("TC-812")
    @Test
    @Order(41)
    void journalOnlyGrowsApplicationRoleCannotChangeItAndTheOwnerIsStoppedByTriggers() throws Exception {
        UUID object = UUID.randomUUID();
        UUID eventId = publishAudit(object, staffAudit(object, "seller.approved"));
        assertTrue(await(Duration.ofSeconds(90), () -> auditRows(eventId) == 1), "в журнале есть запись для проверки");
        UUID id = auditDb().queryForObject("select id from audit_admin.audit_log where event_id = ?", UUID.class, eventId);
        String partition = auditDb().queryForObject("select tableoid::regclass::text from audit_admin.audit_log where id = ?", String.class, id);
        String update = "update audit_admin.audit_log set action = 'x' where id = '" + id + "'";
        String delete = "delete from audit_admin.audit_log where id = '" + id + "'";

        // Роль приёмника: права на INSERT и SELECT, остальных нет (42501)
        JdbcTemplate writer = auditDb();
        assertEquals("42501", sqlState(() -> writer.execute(update)));
        assertEquals("42501", sqlState(() -> writer.execute(delete)));
        assertEquals("42501", sqlState(() -> writer.execute("truncate audit_admin.audit_log")));
        assertEquals("42501", sqlState(() -> writer.execute("truncate " + partition)));
        assertEquals("42501", sqlState(() -> writer.execute("create table audit_admin.evil (x int)")), "создавать таблицы в схеме роль не может");
        String openValue = "[{\"field\":\"email\",\"personal\":true,\"before\":null,\"after\":\"ivan.petrov@mail.example\"}]";
        assertEquals("23514", sqlState(() -> writer.update("insert into audit_admin.audit_log (id, event_id, occurred_at, actor_id, actor_role, action, object_type, "
                + "object_id, changes) values (?, ?, now(), ?, 'admin', 'user.email_changed', 'user', 'u', ?::jsonb)",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), openValue)), "открытое персональное значение отвергает ограничение таблицы");

        // Владелец схемы: права есть, но триггеры запрещают изменение, удаление и очистку таблицы, месячной и страховочной секции (23001)
        JdbcTemplate owner = auditOwnerDb();
        assertEquals("23001", sqlState(() -> owner.execute(update)));
        assertEquals("23001", sqlState(() -> owner.execute(delete)));
        assertEquals("23001", sqlState(() -> owner.execute("truncate audit_admin.audit_log")));
        assertEquals("23001", sqlState(() -> owner.execute("truncate " + partition)));
        assertEquals("23001", sqlState(() -> owner.execute("truncate audit_admin.audit_log_default")));
        assertEquals("23001", sqlState(() -> owner.execute("update " + partition + " set action = 'x' where id = '" + id + "'")));
        assertEquals("23001", sqlState(() -> owner.execute("update audit_admin.audit_log set occurred_at = occurred_at - interval '3 months' where id = '" + id + "'")),
                "перенос записи в другую секцию правкой времени тоже запрещён");

        assertEquals("seller.approved", auditDb().queryForObject("select action from audit_admin.audit_log where id = ?", String.class, id),
                "запись осталась как была");
    }

    @Tag("TC-812")
    @Test
    @Order(42)
    void partitionsForTheCurrentMonthAndThreeAheadExistAndParallelPassesDoNotConflict() {
        List<Integer> passes = Concurrent.race(4, index -> auditPartitions.run());
        assertTrue(passes.stream().allMatch(done -> done == 0 || done == AuditPartitionService.MONTHS), "проход либо сделан (4 месяца), либо уступил блокировку: " + passes);
        assertTrue(passes.stream().anyMatch(done -> done == AuditPartitionService.MONTHS), "хотя бы один проход получил блокировку");
        YearMonth first = YearMonth.now(ZoneOffset.UTC);
        for (int i = 0; i < AuditPartitionService.MONTHS; i++) {
            YearMonth month = first.plusMonths(i);
            String name = "audit_log_" + month.getYear() + "_" + String.format("%02d", month.getMonthValue());
            assertEquals(1, auditDb().queryForObject("select count(*) from pg_class where relname = ? and relnamespace = 'audit_admin'::regnamespace",
                    Integer.class, name), "секция " + name);
        }
        assertEquals(0L, auditDb().queryForObject("select count(*) from (select 1 from audit_admin.audit_log_default limit 1001) t", Long.class),
                "в страховочной секции строк нет: время всех записей теста внутри созданных месяцев");
    }

    @Tag("TC-812")
    @Test
    @Order(43)
    void actionRolledBackAfterTheAuditEventWasWrittenToTheOutboxLeavesNoEvent() {
        UUID object = UUID.randomUUID();
        Map<String, Object> data = staffAudit(object, "user.role_assigned");
        assertThrows(IllegalStateException.class, () -> identityTransaction.execute(() -> {
            identityTransaction.publish(AuditRecords.TOPIC, AuditRecords.AGGREGATE, object.toString(), AuditRecords.EVENT, AuditRecords.SCHEMA_VERSION, data);
            throw new IllegalStateException("сбой после записи в Outbox");
        }));
        assertEquals(List.of(), events(object.toString()), "действие откатилось, и события аудита в Outbox нет");
        identityTransaction.execute(() -> {
            identityTransaction.publish(AuditRecords.TOPIC, AuditRecords.AGGREGATE, object.toString(), AuditRecords.EVENT, AuditRecords.SCHEMA_VERSION, data);
            return null;
        });
        assertEquals(List.of("audit.recorded"), events(object.toString()), "а у зафиксированного действия событие есть");
    }

    @Tag("TC-114")
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
