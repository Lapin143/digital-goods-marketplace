package dgm.catalog.sellers.integration.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.catalog.sellers.FakeSellers;
import dgm.catalog.sellers.FakeTransaction;
import dgm.catalog.sellers.FakeTransaction.Published;
import dgm.catalog.sellers.domain.SellerProfile;
import dgm.catalog.sellers.domain.SellerStatus;
import dgm.catalog.sellers.integration.FakeIntegrations;
import dgm.catalog.sellers.integration.crypto.IntegrationSecrets;
import dgm.catalog.sellers.integration.domain.IntegrationForm;
import dgm.catalog.sellers.integration.domain.IntegrationStatus;
import dgm.catalog.sellers.integration.domain.RotatedSecret;
import dgm.catalog.sellers.integration.domain.SavedIntegration;
import dgm.catalog.sellers.integration.domain.SellerIntegration;
import dgm.kit.client.EndpointGuard;
import dgm.kit.crypto.SellerSecrets;
import dgm.kit.crypto.WebhookSignature;
import dgm.kit.json.Json;
import dgm.kit.outbox.AuditRecords;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.secret.SecretValue;
import dgm.kit.time.ManualClock;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Подключение системы продавца без базы, с настоящим {@link EndpointGuard} на подставном DNS и с управляемыми часами: адрес проходит защиту от
 * SSRF до транзакции (TC-954), первое сохранение показывает секрет один раз, повтор и изменение его не возвращают, смена секрета делает
 * прежний недействительным сразу (TC-959), в аудит идут версии, статусы и хеши частей адреса, но не секрет и не адрес (TC-960).
 */
@Tag("TC-954")
@Tag("TC-959")
@Tag("TC-960")
class IntegrationServiceTest {

    private static final Instant T0 = Instant.parse("2026-11-04T10:00:00.123456Z");
    private static final UUID ACTOR = FakeSellers.USER;
    private static final String URL = "https://shop.seller.example/dgm?token=s3cr3t-in-path";
    private static final SecretValue MASTER = SecretValue.of("master-for-catalog-tests-only-0123456789");
    private static final Set<String> AUDIT_FIELDS = Set.of("occurredAt", "actorId", "actorRole", "action", "objectType", "objectId", "changes",
            "actorIp", "actorIpHashed", "correlationId");

    private ManualClock clock;
    private FakeSellers sellers;
    private FakeIntegrations integrations;
    private FakeTransaction transaction;
    private SellerProfile seller;
    private IntegrationService service;
    private final SellerSecrets derivation = new SellerSecrets(MASTER);

    @BeforeEach
    void setUp() {
        clock = new ManualClock(T0);
        sellers = new FakeSellers();
        seller = FakeSellers.profile(ACTOR, SellerStatus.APPROVED, FakeSellers.completeForm(), T0);
        sellers.put(seller);
        integrations = new FakeIntegrations(sellers, clock);
        transaction = new FakeTransaction();
        service = service(Set.of());
    }

    /** Подставной DNS: публичные имена разрешаются во внешний адрес, «внутренние» в частный, «смешанное» в оба. */
    private static InetAddress[] resolve(String host) throws UnknownHostException {
        return switch (host) {
            case "shop.seller.example" -> new InetAddress[] {InetAddress.getByAddress(host, new byte[] {93, (byte) 184, (byte) 216, 34})};
            case "other.seller.example" -> new InetAddress[] {InetAddress.getByAddress(host, new byte[] {93, (byte) 184, (byte) 216, 35})};
            case "internal.seller.example" -> new InetAddress[] {InetAddress.getByAddress(host, new byte[] {10, 1, 2, 3})};
            case "mixed.seller.example" -> new InetAddress[] {InetAddress.getByAddress(host, new byte[] {93, (byte) 184, (byte) 216, 34}),
                    InetAddress.getByAddress(host, new byte[] {10, 1, 2, 3})};
            case "loop.seller.example", "localhost" -> new InetAddress[] {InetAddress.getByAddress(host, new byte[] {127, 0, 0, 1})};
            case "metadata.seller.example" -> new InetAddress[] {InetAddress.getByAddress(host, new byte[] {(byte) 169, (byte) 254, (byte) 169, (byte) 254})};
            default -> {
                if (host.matches("[0-9.]+") || host.contains(":")) {
                    yield new InetAddress[] {InetAddress.getByName(host)};
                }
                throw new UnknownHostException(host);
            }
        };
    }

    private IntegrationService service(Set<String> allow) {
        return new IntegrationService(integrations, sellers, transaction, new EndpointGuard(IntegrationServiceTest::resolve, allow),
                new IntegrationSecrets(derivation), clock);
    }

    private SavedIntegration connect(String url) {
        return service.set(seller.id(), ACTOR, 0, new IntegrationForm(url, null));
    }

    private static ProblemException problem(Executable action) {
        return assertThrows(ProblemException.class, action);
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> changes(Published audit) {
        return (List<Map<String, Object>>) audit.data().get("changes");
    }

    private Published lastAudit() {
        List<Published> audits = transaction.published(AuditRecords.EVENT);
        return audits.get(audits.size() - 1);
    }

    // ---- TC-954: адрес и защита от SSRF ----

    @Test
    void theFirstSaveConnectsTheSystemAndShowsTheSecretOnce() {
        SavedIntegration saved = connect(URL);

        SellerIntegration integration = saved.integration();
        assertEquals(seller.id(), integration.sellerId());
        assertEquals(URL, integration.endpointUrl());
        assertEquals(IntegrationStatus.ACTIVE, integration.status());
        assertEquals(1, integration.secretVersion());
        assertEquals(1, integration.version());
        assertEquals(integration, integrations.stored(seller.id()));
        assertEquals(derivation.secret(seller.id(), 1).reveal(), saved.secret());
        assertTrue(saved.secret().matches("[0-9a-f]{64}"), saved.secret());
    }

    @Test
    void addressesThePlatformMustNeverCallAreRefusedWithTheSameTextAndNothingIsWritten() {
        String tooLong = "https://shop.seller.example/" + "a".repeat(EndpointGuard.MAX_LENGTH);
        List<String> refused = List.of(
                "http://shop.seller.example/dgm",                    // не https
                "ftp://shop.seller.example/dgm",
                "https://localhost/dgm",                            // имя localhost
                "https://loop.seller.example/dgm",                  // имя резолвится в loopback
                "https://127.0.0.1/dgm",
                "https://10.0.0.5/dgm",                             // частные диапазоны
                "https://192.168.1.10/dgm",
                "https://172.16.0.1/dgm",
                "https://internal.seller.example/dgm",              // имя резолвится в частный адрес
                "https://mixed.seller.example/dgm",                 // хотя бы один адрес внутренний
                "https://169.254.169.254/latest/meta-data",         // метаданные облака
                "https://metadata.seller.example/latest/meta-data",
                "https://[::1]/dgm",
                "https://[fe80::1]/dgm",
                "https://2130706433/dgm",                           // числовая запись 127.0.0.1
                "https://user:secret@shop.seller.example/dgm",      // учётные данные в адресе
                "https://shop.seller.example/dgm#fragment",         // фрагмент
                "https://shop.seller.example:8080/dgm",             // посторонний порт
                "https://shop.seller.example:22/dgm",
                "https://unknown.seller.example/dgm",               // имя не разрешилось
                "https://shop.seller.example/a b",                  // пробел в адресе
                "HTTPS://shop.seller.example/dgm",                  // схема не в нижнем регистре: так записано в ограничении таблицы
                tooLong);
        for (String url : refused) {
            ProblemException e = problem(() -> connect(url));
            assertEquals(ProblemType.ENDPOINT_NOT_ALLOWED, e.type(), url);
            // Один общий текст: по ответу нельзя понять, какой признак сработал, и текст не повторяет адрес
            assertEquals(IntegrationService.NOT_ALLOWED, e.getMessage(), url);
        }
        assertEquals(0, integrations.count());
        assertEquals(0, transaction.published().size());
    }

    @Test
    void anAddressIsCheckedBeforeTheTransactionSoTheNameLookupNeverHoldsTheDatabase() {
        problem(() -> connect("https://internal.seller.example/dgm"));
        // Единственная транзакция это проверка профиля до разрешения имени: ни блокировки профиля, ни записи
        assertEquals(1, transaction.executions());
        assertFalse(sellers.wasLockedById(seller.id()));
    }

    @Test
    void ordinaryAddressesAreAcceptedOnBothAllowedPortsAtTheLengthLimit() {
        assertEquals("https://shop.seller.example:8443/dgm", connect("https://shop.seller.example:8443/dgm").integration().endpointUrl());
        service.set(seller.id(), ACTOR, 1, new IntegrationForm("https://shop.seller.example:443/dgm", null));
        String atLimit = "https://shop.seller.example/" + "a".repeat(EndpointGuard.MAX_LENGTH - "https://shop.seller.example/".length());
        assertEquals(EndpointGuard.MAX_LENGTH, atLimit.length());
        assertEquals(atLimit, service.set(seller.id(), ACTOR, 2, new IntegrationForm(atLimit, null)).integration().endpointUrl());
        problem(() -> service.set(seller.id(), ACTOR, 3, new IntegrationForm(atLimit + "a", null)));
    }

    @Test
    void theStandAllowListFreesAnInternalNameFromTheRangeCheckOnlyNotFromTheOtherRules() {
        service = service(Set.of("internal.seller.example", "loop.seller.example:8443"));
        assertEquals("https://internal.seller.example/dgm", connect("https://internal.seller.example/dgm").integration().endpointUrl());
        // Имя из списка освобождено только от проверки диапазонов: схема, порт, учётные данные и разрешение имени действуют
        assertEquals(ProblemType.ENDPOINT_NOT_ALLOWED, problem(() -> service.set(seller.id(), ACTOR, 1,
                new IntegrationForm("http://internal.seller.example/dgm", null))).type());
        assertEquals(ProblemType.ENDPOINT_NOT_ALLOWED, problem(() -> service.set(seller.id(), ACTOR, 1,
                new IntegrationForm("https://internal.seller.example:8080/dgm", null))).type());
        assertEquals(ProblemType.ENDPOINT_NOT_ALLOWED, problem(() -> service.set(seller.id(), ACTOR, 1,
                new IntegrationForm("https://user:x@internal.seller.example/dgm", null))).type());
        // Имя не из списка остаётся закрытым, как и то же имя на другом порту
        assertEquals(ProblemType.ENDPOINT_NOT_ALLOWED, problem(() -> service.set(seller.id(), ACTOR, 1,
                new IntegrationForm("https://mixed.seller.example/dgm", null))).type());
        assertEquals(ProblemType.ENDPOINT_NOT_ALLOWED, problem(() -> service.set(seller.id(), ACTOR, 1,
                new IntegrationForm("https://loop.seller.example/dgm", null))).type());
        assertEquals("https://loop.seller.example:8443/dgm", service.set(seller.id(), ACTOR, 1,
                new IntegrationForm("https://loop.seller.example:8443/dgm", null)).integration().endpointUrl());
    }

    @Test
    void aMissingOrTooLongAddressIsAValidationFailureNotAnAddressRefusal() {
        ProblemException missing = problem(() -> connect(null));
        assertEquals(ProblemType.VALIDATION_FAILED, missing.type());
        ProblemException empty = problem(() -> connect(""));
        assertEquals(ProblemType.VALIDATION_FAILED, empty.type());
        ProblemException huge = problem(() -> connect("https://shop.seller.example/" + "a".repeat(IntegrationForm.MAX_URL)));
        assertEquals(ProblemType.VALIDATION_FAILED, huge.type());
        assertEquals(0, integrations.count());
    }

    @Test
    void aSellerWhoseProfileIsNotApprovedGetsForbiddenAndNoNameLookupHappens() {
        for (SellerStatus status : List.of(SellerStatus.DRAFT, SellerStatus.ON_REVIEW, SellerStatus.RETURNED, SellerStatus.REJECTED, SellerStatus.BLOCKED)) {
            UUID user = UUID.randomUUID();
            SellerProfile other = FakeSellers.profile(user, status, FakeSellers.completeForm(), T0);
            sellers.put(other);
            assertEquals(ProblemType.FORBIDDEN, problem(() -> service.get(other.id())).type(), status.name());
            assertEquals(ProblemType.FORBIDDEN, problem(() -> service.set(other.id(), user, 0, new IntegrationForm(URL, null))).type(), status.name());
            assertEquals(ProblemType.FORBIDDEN, problem(() -> service.rotate(other.id(), user)).type(), status.name());
        }
        assertEquals(ProblemType.FORBIDDEN, problem(() -> service.get(UUID.randomUUID())).type());
        assertEquals(0, integrations.count());
    }

    // ---- версия записи ----

    @Test
    void theFirstConnectionNeedsVersionZeroAndAnyOtherVersionIsAConflict() {
        for (long version : new long[] {1, 2, 99}) {
            assertEquals(ProblemType.VERSION_CONFLICT, problem(() -> service.set(seller.id(), ACTOR, version, new IntegrationForm(URL, null))).type());
        }
        assertEquals(0, integrations.count());
        assertEquals(1, connect(URL).integration().version());
    }

    @Test
    void aStaleVersionIsAConflictAndChangesNothingAndNeverRevealsTheSecretAgain() {
        connect(URL);
        // Потерянный ответ первого PUT: повтор с «0» не создаёт второго подключения и не показывает секрет
        ProblemException repeat = problem(() -> connect(URL));
        assertEquals(ProblemType.VERSION_CONFLICT, repeat.type());
        assertFalse(repeat.getMessage().contains(derivation.secret(seller.id(), 1).reveal()));
        service.set(seller.id(), ACTOR, 1, new IntegrationForm("https://other.seller.example/dgm", null));
        ProblemException stale = problem(() -> service.set(seller.id(), ACTOR, 1, new IntegrationForm("https://shop.seller.example/new", null)));
        assertEquals(ProblemType.VERSION_CONFLICT, stale.type());
        assertEquals("https://other.seller.example/dgm", integrations.stored(seller.id()).endpointUrl());
        assertEquals(2, integrations.stored(seller.id()).version());
    }

    @Test
    void theProfileRowIsLockedSoTwoRacesForTheSameConnectionTakeTurns() {
        connect(URL);
        assertTrue(sellers.wasLockedById(seller.id()));
    }

    // ---- TC-959: секрет ----

    @Test
    void laterSavesNeverReturnTheSecretAndKeepItsVersion() {
        SavedIntegration first = connect(URL);
        SavedIntegration second = service.set(seller.id(), ACTOR, 1, new IntegrationForm("https://other.seller.example/dgm", null));
        assertNotNull(first.secret());
        assertNull(second.secret());
        assertEquals(2, second.integration().version());
        assertEquals(1, second.integration().secretVersion());
        SavedIntegration third = service.set(seller.id(), ACTOR, 2, new IntegrationForm("https://other.seller.example/dgm", IntegrationStatus.DISABLED));
        assertNull(third.secret());
        assertEquals(IntegrationStatus.DISABLED, third.integration().status());
        assertEquals(3, third.integration().version());
    }

    @Test
    void savingTheSameStateAgainChangesNothingAndWritesNothing() {
        connect(URL);
        int audits = transaction.published(AuditRecords.EVENT).size();
        SavedIntegration repeat = service.set(seller.id(), ACTOR, 1, new IntegrationForm(URL, IntegrationStatus.ACTIVE));
        assertNull(repeat.secret());
        assertEquals(1, repeat.integration().version());
        assertEquals(0, integrations.updates());
        assertEquals(audits, transaction.published(AuditRecords.EVENT).size());
    }

    @Test
    void aSystemCanBeConnectedDisabledAndStillGetsItsSecret() {
        SavedIntegration saved = service.set(seller.id(), ACTOR, 0, new IntegrationForm(URL, IntegrationStatus.DISABLED));
        assertEquals(IntegrationStatus.DISABLED, saved.integration().status());
        assertEquals(derivation.secret(seller.id(), 1).reveal(), saved.secret());
    }

    @Test
    void rotationRaisesTheSecretVersionAndTheOldSecretStopsWorkingAtOnce() {
        SavedIntegration first = connect(URL);
        clock.advance(Duration.ofMinutes(5));

        RotatedSecret rotated = service.rotate(seller.id(), ACTOR);

        assertEquals(2, rotated.secretVersion());
        assertNotEquals(first.secret(), rotated.secret());
        assertEquals(derivation.secret(seller.id(), 2).reveal(), rotated.secret());
        SellerIntegration stored = integrations.stored(seller.id());
        assertEquals(2, stored.secretVersion());
        assertEquals(2, stored.version());
        assertEquals(URL, stored.endpointUrl());
        assertEquals(IntegrationStatus.ACTIVE, stored.status());

        // Отправитель платформы подписывает по версии из подключения, то есть по новой; прежний секрет такую подпись не подтверждает
        byte[] body = "{\"requestId\":\"r1\"}".getBytes(StandardCharsets.UTF_8);
        String timestamp = "2026-11-04T10:00:00.000Z";
        String signature = derivation.sign(seller.id(), stored.secretVersion(), timestamp, body);
        Instant now = Instant.parse("2026-11-04T10:00:10Z");
        assertEquals(WebhookSignature.Result.VALID, WebhookSignature.verify(List.of(SecretValue.of(rotated.secret())), timestamp, signature, body, now));
        assertEquals(WebhookSignature.Result.SIGNATURE_INVALID,
                WebhookSignature.verify(List.of(SecretValue.of(first.secret())), timestamp, signature, body, now));
        // и наоборот: подпись по старой версии новым секретом не подтверждается
        String old = derivation.sign(seller.id(), 1, timestamp, body);
        assertEquals(WebhookSignature.Result.SIGNATURE_INVALID,
                WebhookSignature.verify(List.of(SecretValue.of(rotated.secret())), timestamp, old, body, now));
    }

    @Test
    void aSaveAfterRotationKeepsTheNewVersionAndShowsNoSecret() {
        connect(URL);
        service.rotate(seller.id(), ACTOR);
        SavedIntegration saved = service.set(seller.id(), ACTOR, 2, new IntegrationForm("https://other.seller.example/dgm", null));
        assertNull(saved.secret());
        assertEquals(2, saved.integration().secretVersion());
        assertEquals(3, saved.integration().version());
        assertEquals(3, service.rotate(seller.id(), ACTOR).secretVersion());
    }

    @Test
    void rotationWithoutAConnectionIsAStateConflictAndWritesNothing() {
        ProblemException e = problem(() -> service.rotate(seller.id(), ACTOR));
        assertEquals(ProblemType.STATE_CONFLICT, e.type());
        assertEquals(0, integrations.count());
        assertEquals(0, transaction.published().size());
    }

    @Test
    void aDisabledConnectionCanStillRotateItsSecret() {
        service.set(seller.id(), ACTOR, 0, new IntegrationForm(URL, IntegrationStatus.DISABLED));
        assertEquals(2, service.rotate(seller.id(), ACTOR).secretVersion());
        assertEquals(IntegrationStatus.DISABLED, integrations.stored(seller.id()).status());
    }

    @Test
    void theSecretsOfTwoSellersAndOfTwoVersionsAllDiffer() {
        SellerProfile second = FakeSellers.profile(FakeSellers.OTHER_USER, SellerStatus.APPROVED, FakeSellers.completeForm(), T0);
        sellers.put(second);
        String first = connect(URL).secret();
        String other = service.set(second.id(), FakeSellers.OTHER_USER, 0, new IntegrationForm(URL, null)).secret();
        assertNotEquals(first, other);
        assertNotEquals(first, service.rotate(seller.id(), ACTOR).secret());
    }

    // ---- адрес для вызывающих сервисов ----

    @Test
    void theInternalAddressIsGivenOnlyForAnActiveConnectionOfAnApprovedSeller() {
        assertEquals(Optional.empty(), service.activeEndpoint(seller.id()));
        connect(URL);
        assertEquals(URL, service.activeEndpoint(seller.id()).orElseThrow().endpointUrl());

        service.set(seller.id(), ACTOR, 1, new IntegrationForm(URL, IntegrationStatus.DISABLED));
        assertEquals(Optional.empty(), service.activeEndpoint(seller.id()));

        service.set(seller.id(), ACTOR, 2, new IntegrationForm(URL, IntegrationStatus.ACTIVE));
        assertTrue(service.activeEndpoint(seller.id()).isPresent());
        sellers.put(FakeSellers.profile(ACTOR, SellerStatus.BLOCKED, FakeSellers.completeForm(), T0));
        assertEquals(Optional.empty(), service.activeEndpoint(seller.id()));
        assertEquals(Optional.empty(), service.activeEndpoint(UUID.randomUUID()));
    }

    @Test
    void theCabinetReadReturnsTheConnectionOrNothingAndNeverTheSecret() {
        assertEquals(Optional.empty(), service.get(seller.id()));
        connect(URL);
        SellerIntegration read = service.get(seller.id()).orElseThrow();
        assertEquals(URL, read.endpointUrl());
        assertFalse(read.toString().contains(derivation.secret(seller.id(), 1).reveal()));
    }

    @Test
    void thePrintedFormOfTheResultsNeverShowsTheSecretSoALogLineCannotLeakIt() {
        SavedIntegration saved = connect(URL);
        assertNotNull(saved.secret());
        assertFalse(saved.toString().contains(saved.secret()));
        RotatedSecret rotated = service.rotate(seller.id(), ACTOR);
        assertFalse(rotated.toString().contains(rotated.secret()));
        assertFalse(rotated.toString().contains(saved.secret()));
    }

    // ---- TC-960: аудит ----

    @Test
    void theFirstConnectionIsAuditedWithHashesOfTheHostAndThePathAndNothingElseOfTheAddress() throws Exception {
        SavedIntegration saved = connect(URL);

        List<Published> audits = transaction.published(AuditRecords.EVENT);
        assertEquals(1, audits.size());
        Published audit = audits.get(0);
        assertEquals(AuditRecords.TOPIC, audit.topic());
        assertEquals(seller.id().toString(), audit.aggregateId());
        assertEquals(AUDIT_FIELDS, audit.data().keySet());
        assertEquals("seller.integration_set", audit.data().get("action"));
        assertEquals("seller", audit.data().get("actorRole"));
        assertEquals(ACTOR.toString(), audit.data().get("actorId"));
        assertEquals("", audit.data().get("actorIp"));
        assertEquals("seller-integration", audit.data().get("objectType"));
        assertEquals(seller.id().toString(), audit.data().get("objectId"));
        assertEquals("2026-11-04T10:00:00.123Z", audit.data().get("occurredAt"));

        List<Map<String, Object>> changes = changes(audit);
        assertEquals(List.of("endpointHost", "endpointPath", "status", "secretVersion"), changes.stream().map(c -> (String) c.get("field")).toList());
        assertEquals(sha256("shop.seller.example"), changes.get(0).get("after"));
        assertEquals(sha256("/dgm?token=s3cr3t-in-path"), changes.get(1).get("after"));
        assertEquals("active", changes.get(2).get("after"));
        assertEquals(1, changes.get(3).get("after"));
        for (Map<String, Object> change : changes) {
            assertNull(change.get("before"), String.valueOf(change.get("field")));
            assertEquals(false, change.get("personal"));
        }

        // Ни секрета, ни мастер-секрета, ни адреса, ни его частей, ни токена из пути в записи нет
        String record = Json.write(audit.data());
        for (String forbidden : List.of(saved.secret(), MASTER.reveal(), URL, "shop.seller.example", "s3cr3t-in-path", "/dgm")) {
            assertFalse(record.contains(forbidden), forbidden);
        }
    }

    @Test
    void aChangeOfOnlyThePathOrOnlyTheHostOrOnlyTheStatusAuditsOnlyThatPart() throws Exception {
        connect("https://shop.seller.example/dgm");

        service.set(seller.id(), ACTOR, 1, new IntegrationForm("https://shop.seller.example/v2", null));
        assertEquals(List.of("endpointPath"), changes(lastAudit()).stream().map(c -> (String) c.get("field")).toList());
        assertEquals(sha256("/dgm"), changes(lastAudit()).get(0).get("before"));
        assertEquals(sha256("/v2"), changes(lastAudit()).get(0).get("after"));

        service.set(seller.id(), ACTOR, 2, new IntegrationForm("https://other.seller.example/v2", null));
        assertEquals(List.of("endpointHost"), changes(lastAudit()).stream().map(c -> (String) c.get("field")).toList());
        assertEquals(sha256("shop.seller.example"), changes(lastAudit()).get(0).get("before"));
        assertEquals(sha256("other.seller.example"), changes(lastAudit()).get(0).get("after"));

        service.set(seller.id(), ACTOR, 3, new IntegrationForm("https://other.seller.example/v2", IntegrationStatus.DISABLED));
        assertEquals(List.of("status"), changes(lastAudit()).stream().map(c -> (String) c.get("field")).toList());
        assertEquals("active", changes(lastAudit()).get(0).get("before"));
        assertEquals("disabled", changes(lastAudit()).get(0).get("after"));
        assertEquals(4, transaction.published(AuditRecords.EVENT).size());
    }

    @Test
    void rotationIsAuditedAsAVersionChangeWithoutAnySecret() {
        SavedIntegration first = connect(URL);
        RotatedSecret rotated = service.rotate(seller.id(), ACTOR);

        Published audit = lastAudit();
        assertEquals("seller.integration_secret_rotated", audit.data().get("action"));
        assertEquals("seller", audit.data().get("actorRole"));
        assertEquals(seller.id().toString(), audit.data().get("objectId"));
        List<Map<String, Object>> changes = changes(audit);
        assertEquals(1, changes.size());
        assertEquals("secretVersion", changes.get(0).get("field"));
        assertEquals(1, changes.get(0).get("before"));
        assertEquals(2, changes.get(0).get("after"));
        String everything = Json.write(transaction.published().stream().map(Published::data).toList());
        for (String forbidden : List.of(first.secret(), rotated.secret(), MASTER.reveal(), URL, "shop.seller.example")) {
            assertFalse(everything.contains(forbidden), forbidden);
        }
    }

    @Test
    void anAuditFailureAbortsTheActionAndNoSecretIsHandedOut() {
        transaction.failPublishWith(new IllegalStateException("Outbox недоступен"));
        assertThrows(IllegalStateException.class, () -> connect(URL));
    }

    @Test
    void thereAreNoDomainEventsOnlyAuditRecords() {
        connect(URL);
        service.rotate(seller.id(), ACTOR);
        assertTrue(transaction.published().stream().allMatch(p -> p.eventType().equals(AuditRecords.EVENT)));
    }
}
