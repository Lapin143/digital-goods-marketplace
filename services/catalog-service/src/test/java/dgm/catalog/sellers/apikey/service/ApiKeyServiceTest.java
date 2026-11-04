package dgm.catalog.sellers.apikey.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.catalog.sellers.FakeSellers;
import dgm.catalog.sellers.FakeTransaction;
import dgm.catalog.sellers.FakeTransaction.Published;
import dgm.catalog.sellers.apikey.FakeApiKeys;
import dgm.catalog.sellers.apikey.domain.ApiKey;
import dgm.catalog.sellers.apikey.domain.ApiKeyForm;
import dgm.catalog.sellers.apikey.domain.ApiKeyPage;
import dgm.catalog.sellers.apikey.domain.ApiKeyStatus;
import dgm.catalog.sellers.apikey.domain.ApiKeyValues;
import dgm.catalog.sellers.apikey.domain.IssuedKey;
import dgm.catalog.sellers.apikey.domain.KeyCheck;
import dgm.catalog.sellers.domain.SellerProfile;
import dgm.catalog.sellers.domain.SellerStatus;
import dgm.kit.json.Json;
import dgm.kit.outbox.AuditRecords;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.time.ManualClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Выпуск, список, отзыв и проверка API-ключей без базы и с управляемыми часами: значение существует только в результате выпуска, не больше
 * пяти действующих ключей, ключ выпускает только одобренный продавец, каждое изменение пишет событие и запись аудита без значения и хеша,
 * повторный отзыв ничего не пишет, чужой ключ неотличим от несуществующего.
 */
@Tag("TC-950")
@Tag("TC-951")
@Tag("TC-952")
@Tag("TC-953")
class ApiKeyServiceTest {

    private static final Instant T0 = Instant.parse("2026-11-04T10:00:00.123456Z");
    private static final UUID ACTOR = FakeSellers.USER;
    private static final Set<String> EVENT_FIELDS = Set.of("apiKeyId", "sellerId");
    private static final Set<String> AUDIT_FIELDS = Set.of("occurredAt", "actorId", "actorRole", "action", "objectType", "objectId", "changes",
            "actorIp", "actorIpHashed", "correlationId");

    private ManualClock clock;
    private FakeSellers sellers;
    private FakeApiKeys keys;
    private FakeTransaction transaction;
    private ApiKeyService service;
    private SellerProfile seller;
    private int counter;
    /** Все значения, которые источник случайных байт породил за тест: по ним ищем утечки. */
    private final List<String> values = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clock = new ManualClock(T0);
        sellers = new FakeSellers();
        keys = new FakeApiKeys(sellers);
        transaction = new FakeTransaction();
        seller = FakeSellers.profile(ACTOR, SellerStatus.APPROVED, FakeSellers.completeForm(), T0);
        sellers.put(seller);
        service = new ApiKeyService(keys, sellers, transaction, clock, length -> {
            byte[] bytes = new byte[length];
            int n = ++counter;
            for (int i = 0; i < length; i++) {
                bytes[i] = (byte) (n * 31 + i * 7);
            }
            return bytes;
        });
    }

    private IssuedKey issue(String name) {
        IssuedKey issued = service.issue(seller.id(), ACTOR, new ApiKeyForm(name));
        values.add(issued.value());
        return issued;
    }

    private static ProblemException problem(Runnable action) {
        return assertThrows(ProblemException.class, action::run);
    }

    // ---- выпуск ----

    @Test
    void issuingGivesAnActiveKeyAndTheValueInTheAgreedShape() {
        IssuedKey issued = issue("Склад");
        assertTrue(ApiKeyValues.isWellFormed(issued.value()), issued.value());
        ApiKey key = issued.key();
        assertEquals(ApiKeyStatus.ACTIVE, key.status());
        assertEquals(seller.id(), key.sellerId());
        assertEquals("Склад", key.name());
        assertEquals(ApiKeyValues.fingerprint(issued.value()), key.fingerprint());
        assertNull(key.revokedAt());
        assertEquals(key, keys.stored(key.id()));
        assertEquals(1, transaction.executions());
    }

    @Test
    void theTimeOfIssueIsCutToMillisecondsSoTheListAndTheIssueAnswerAgree() {
        ApiKey key = issue("Склад").key();
        assertEquals(Instant.parse("2026-11-04T10:00:00.123Z"), key.createdAt());
    }

    @Test
    void onlyTheHashIsStoredAndItFindsTheKeyForTheGateway() {
        IssuedKey issued = issue("Склад");
        byte[] hash = ApiKeyValues.hash(issued.value());
        assertEquals(32, hash.length);
        Optional<KeyCheck> found = service.check(hash);
        assertEquals(Optional.of(new KeyCheck(issued.key().id(), seller.id(), ApiKeyStatus.ACTIVE, SellerStatus.APPROVED)), found);
        assertFalse(keys.dump().contains(issued.value()), "значение ключа в хранилище");
        assertFalse(keys.dump().contains(issued.value().substring(ApiKeyValues.PREFIX.length())), "значение ключа без префикса в хранилище");
        assertTrue(keys.dump().contains(HexFormat.of().formatHex(hash)));
    }

    @Test
    void eachIssueGivesAnotherValueAndAnotherId() {
        IssuedKey first = issue("Один");
        IssuedKey second = issue("Два");
        assertNotEquals(first.value(), second.value());
        assertNotEquals(first.key().id(), second.key().id());
        assertNotEquals(first.key().fingerprint(), second.key().fingerprint());
    }

    @Test
    void issuingWritesTheEventAndTheAuditRecordInOneTransactionWithoutTheValueTheHashOrTheName() {
        IssuedKey issued = issue("Склад секретный");
        List<Published> published = transaction.published();
        assertEquals(2, published.size());

        Published event = published.get(0);
        assertEquals("catalog.events", event.topic());
        assertEquals("ApiKey", event.aggregateType());
        assertEquals(issued.key().id().toString(), event.aggregateId());
        assertEquals("api-key.issued", event.eventType());
        assertEquals(1, event.schemaVersion());
        assertEquals(EVENT_FIELDS, event.data().keySet());
        assertEquals(issued.key().id().toString(), event.data().get("apiKeyId"));
        assertEquals(seller.id().toString(), event.data().get("sellerId"));

        Published audit = published.get(1);
        assertEquals("audit.events", audit.topic());
        assertEquals("AuditRecord", audit.aggregateType());
        assertEquals(issued.key().id().toString(), audit.aggregateId());
        assertEquals("audit.recorded", audit.eventType());
        assertEquals(AUDIT_FIELDS, audit.data().keySet());
        assertEquals("seller", audit.data().get("actorRole"));
        assertEquals(ACTOR.toString(), audit.data().get("actorId"));
        assertEquals("seller.api_key_issued", audit.data().get("action"));
        assertEquals("api-key", audit.data().get("objectType"));
        assertEquals(issued.key().id().toString(), audit.data().get("objectId"));
        assertEquals("", audit.data().get("actorIp"));
        assertEquals(false, audit.data().get("actorIpHashed"));
        assertEquals(List.of(AuditRecords.change("status", null, "active"), AuditRecords.change("fingerprint", null, issued.key().fingerprint())),
                audit.data().get("changes"));

        String everything = Json.write(Map.of("events", published.stream().map(p -> p.data()).toList()));
        assertNoSecrets(everything, issued);
        assertFalse(everything.contains("Склад секретный"), "имя ключа в событии или аудите");
    }

    @Test
    void theFifthKeyIsIssuedAndTheSixthIsRefusedWith409AndTheLimitInTheAnswer() {
        for (int i = 1; i <= ApiKeyService.ACTIVE_LIMIT; i++) {
            issue("Ключ " + i);
        }
        int eventsBefore = transaction.published().size();
        ProblemException refused = problem(() -> issue("Шестой"));
        assertEquals(ProblemType.API_KEY_LIMIT, refused.type());
        assertEquals(409, refused.type().status());
        assertEquals(Map.of("limit", 5), refused.extensions());
        assertEquals(5, keys.count());
        assertEquals(eventsBefore, transaction.published().size(), "отказ не пишет событий");
    }

    @Test
    void revokingAKeyFreesAPlaceForTheNextOne() {
        List<IssuedKey> issued = new ArrayList<>();
        for (int i = 1; i <= ApiKeyService.ACTIVE_LIMIT; i++) {
            issued.add(issue("Ключ " + i));
        }
        service.revoke(seller.id(), ACTOR, issued.get(2).key().id());
        IssuedKey sixth = issue("Шестой");
        assertEquals(ApiKeyStatus.ACTIVE, sixth.key().status());
        assertEquals(6, keys.count(), "отозванный ключ остаётся в таблице");
        assertEquals(5, keys.countActive(seller.id()));
    }

    @Test
    void theLimitIsPerSeller() {
        for (int i = 1; i <= ApiKeyService.ACTIVE_LIMIT; i++) {
            issue("Ключ " + i);
        }
        SellerProfile other = FakeSellers.profile(FakeSellers.OTHER_USER, SellerStatus.APPROVED, FakeSellers.completeForm(), T0);
        sellers.put(other);
        IssuedKey issued = service.issue(other.id(), FakeSellers.OTHER_USER, new ApiKeyForm("Чужой"));
        values.add(issued.value());
        assertEquals(other.id(), issued.key().sellerId());
    }

    @Test
    void onlyAnApprovedSellerMayIssueAKey() {
        for (SellerStatus status : List.of(SellerStatus.DRAFT, SellerStatus.ON_REVIEW, SellerStatus.RETURNED, SellerStatus.REJECTED, SellerStatus.BLOCKED)) {
            sellers.put(FakeSellers.profile(ACTOR, status, FakeSellers.completeForm(), T0));
            ProblemException refused = problem(() -> service.issue(seller.id(), ACTOR, new ApiKeyForm("Склад")));
            assertEquals(ProblemType.FORBIDDEN, refused.type(), status.code());
        }
        assertEquals(0, keys.count());
        assertEquals(List.of(), transaction.published());
    }

    @Test
    void aSellerWithoutAProfileMayNotIssueAKey() {
        ProblemException refused = problem(() -> service.issue(UUID.randomUUID(), ACTOR, new ApiKeyForm("Склад")));
        assertEquals(ProblemType.FORBIDDEN, refused.type());
    }

    @Test
    void aBadNameIsRefusedBeforeAnythingIsWritten() {
        ProblemException refused = problem(() -> service.issue(seller.id(), ACTOR, new ApiKeyForm("")));
        assertEquals(ProblemType.VALIDATION_FAILED, refused.type());
        assertEquals(0, transaction.executions(), "до проверки формы транзакция не открывалась");
        assertEquals(0, keys.count());
    }

    @Test
    void anEventThatCannotBeWrittenFailsTheIssueAndLeavesNoEventBehind() {
        transaction.failPublishWith(new IllegalStateException("база недоступна"));
        assertThrows(IllegalStateException.class, () -> service.issue(seller.id(), ACTOR, new ApiKeyForm("Склад")));
        assertEquals(List.of(), transaction.published());
    }

    // ---- список ----

    @Test
    void theListShowsTheNewestFirstAndNeverTheValue() {
        IssuedKey first = issue("Первый");
        clock.advance(Duration.ofSeconds(5));
        IssuedKey second = issue("Второй");
        ApiKeyPage page = service.list(seller.id(), Optional.empty(), 20, Optional.empty());
        assertEquals(List.of(second.key(), first.key()), page.items());
        assertEquals(20, page.limit());
        assertEquals(Optional.empty(), page.next());
        assertNoSecrets(page.items().toString(), first, second);
    }

    @Test
    void theListPagesWithACursorThatSurvivesKeysIssuedInTheSameMillisecond() {
        List<ApiKey> issued = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            issued.add(issue("Ключ " + i).key());
        }
        // Время не двигалось: у всех пяти одно created_at, порядок держит идентификатор
        List<ApiKey> expected = issued.stream().sorted(java.util.Comparator.comparing(ApiKey::createdAt).thenComparing(ApiKey::id).reversed()).toList();
        List<ApiKey> seen = new ArrayList<>();
        Optional<ApiKeyPage.After> after = Optional.empty();
        int pages = 0;
        do {
            ApiKeyPage page = service.list(seller.id(), Optional.empty(), 2, after);
            seen.addAll(page.items());
            after = page.next();
            pages++;
        } while (after.isPresent());
        assertEquals(expected, seen);
        assertEquals(3, pages);
    }

    @Test
    void theStatusFilterSeparatesActiveFromRevokedKeys() {
        IssuedKey first = issue("Первый");
        IssuedKey second = issue("Второй");
        service.revoke(seller.id(), ACTOR, first.key().id());
        List<ApiKey> active = service.list(seller.id(), Optional.of(ApiKeyStatus.ACTIVE), 20, Optional.empty()).items();
        List<ApiKey> revoked = service.list(seller.id(), Optional.of(ApiKeyStatus.REVOKED), 20, Optional.empty()).items();
        assertEquals(List.of(second.key().id()), active.stream().map(ApiKey::id).toList());
        assertEquals(List.of(first.key().id()), revoked.stream().map(ApiKey::id).toList());
    }

    @Test
    void theListOfASellerNeverHasKeysOfAnotherSeller() {
        issue("Мой");
        SellerProfile other = FakeSellers.profile(FakeSellers.OTHER_USER, SellerStatus.APPROVED, FakeSellers.completeForm(), T0);
        sellers.put(other);
        service.issue(other.id(), FakeSellers.OTHER_USER, new ApiKeyForm("Чужой"));
        assertEquals(List.of("Мой"), service.list(seller.id(), Optional.empty(), 20, Optional.empty()).items().stream().map(ApiKey::name).toList());
        assertEquals(List.of("Чужой"), service.list(other.id(), Optional.empty(), 20, Optional.empty()).items().stream().map(ApiKey::name).toList());
    }

    @Test
    void aBlockedSellerCannotListKeys() {
        sellers.put(FakeSellers.profile(ACTOR, SellerStatus.BLOCKED, FakeSellers.completeForm(), T0));
        assertEquals(ProblemType.FORBIDDEN, problem(() -> service.list(seller.id(), Optional.empty(), 20, Optional.empty())).type());
    }

    // ---- отзыв ----

    @Test
    void revokingMakesTheKeyRevokedWritesTheEventAndTheAuditAndTheGatewayStopsTrustingTheKey() {
        IssuedKey issued = issue("Склад");
        clock.advance(Duration.ofMinutes(3));
        ApiKey revoked = service.revoke(seller.id(), ACTOR, issued.key().id());
        assertEquals(ApiKeyStatus.REVOKED, revoked.status());
        assertEquals(Instant.parse("2026-11-04T10:03:00.123Z"), revoked.revokedAt());
        assertEquals(revoked, keys.stored(issued.key().id()));

        List<Published> events = transaction.published("api-key.revoked");
        assertEquals(1, events.size());
        assertEquals("catalog.events", events.get(0).topic());
        assertEquals(Map.of("apiKeyId", issued.key().id().toString(), "sellerId", seller.id().toString()), events.get(0).data());

        List<Published> audits = transaction.published("audit.recorded");
        assertEquals(2, audits.size());
        Map<String, Object> audit = audits.get(1).data();
        assertEquals("seller.api_key_revoked", audit.get("action"));
        assertEquals(AUDIT_FIELDS, audit.keySet());
        assertEquals(List.of(AuditRecords.change("status", "active", "revoked")), audit.get("changes"));

        assertNoSecrets(Json.write(Map.of("events", transaction.published().stream().map(p -> p.data()).toList())), issued);
        KeyCheck check = service.check(ApiKeyValues.hash(issued.value())).orElseThrow();
        assertEquals(ApiKeyStatus.REVOKED, check.keyStatus());
    }

    @Test
    void aRepeatedRevocationGivesTheSameStateWithoutASecondEventOrAuditRecord() {
        IssuedKey issued = issue("Склад");
        ApiKey first = service.revoke(seller.id(), ACTOR, issued.key().id());
        int published = transaction.published().size();
        clock.advance(Duration.ofHours(1));
        ApiKey second = service.revoke(seller.id(), ACTOR, issued.key().id());
        assertEquals(first, second);
        assertEquals(first.revokedAt(), second.revokedAt(), "дата первого отзыва не сдвигается");
        assertEquals(published, transaction.published().size());
        assertEquals(1, transaction.published("api-key.revoked").size());
    }

    @Test
    void revocationLocksTheKeyRowSoTwoRevocationsGoInTurn() {
        IssuedKey issued = issue("Склад");
        service.revoke(seller.id(), ACTOR, issued.key().id());
        assertEquals(1, keys.lockCalls());
    }

    @Test
    void anotherSellersKeyAndAnUnknownKeyLookTheSame() {
        IssuedKey issued = issue("Склад");
        SellerProfile other = FakeSellers.profile(FakeSellers.OTHER_USER, SellerStatus.APPROVED, FakeSellers.completeForm(), T0);
        sellers.put(other);
        ProblemException foreign = problem(() -> service.revoke(other.id(), FakeSellers.OTHER_USER, issued.key().id()));
        ProblemException unknown = problem(() -> service.revoke(seller.id(), ACTOR, UUID.randomUUID()));
        assertEquals(ProblemType.NOT_FOUND, foreign.type());
        assertEquals(foreign.type(), unknown.type());
        assertEquals(foreign.getMessage(), unknown.getMessage());
        assertEquals(ApiKeyStatus.ACTIVE, keys.stored(issued.key().id()).status(), "чужой отзыв ключ не тронул");
        assertEquals(List.of(), transaction.published("api-key.revoked"));
    }

    @Test
    void aBlockedSellerCannotRevokeKeysThroughTheCabinet() {
        IssuedKey issued = issue("Склад");
        sellers.put(FakeSellers.profile(ACTOR, SellerStatus.BLOCKED, FakeSellers.completeForm(), T0));
        assertEquals(ProblemType.FORBIDDEN, problem(() -> service.revoke(seller.id(), ACTOR, issued.key().id())).type());
        assertEquals(ApiKeyStatus.ACTIVE, keys.stored(issued.key().id()).status());
    }

    // ---- проверка для шлюза ----

    @Test
    void anUnknownHashIsNotFound() {
        issue("Склад");
        assertEquals(Optional.empty(), service.check(ApiKeyValues.hash(ApiKeyValues.generate(length -> new byte[length]))));
    }

    @Test
    void theCheckCarriesTheStatusOfTheSellerProfileSoTheGatewayNeedsNoTokenForIt() {
        IssuedKey issued = issue("Склад");
        sellers.put(FakeSellers.profile(ACTOR, SellerStatus.BLOCKED, FakeSellers.completeForm(), T0));
        KeyCheck check = service.check(ApiKeyValues.hash(issued.value())).orElseThrow();
        assertEquals(ApiKeyStatus.ACTIVE, check.keyStatus());
        assertEquals(SellerStatus.BLOCKED, check.sellerStatus());
        assertEquals(issued.key().id(), check.apiKeyId());
        assertEquals(seller.id(), check.sellerId());
    }

    private void assertNoSecrets(String text, IssuedKey... issued) {
        for (IssuedKey one : issued) {
            assertFalse(text.contains(one.value()), "значение ключа в тексте");
            assertFalse(text.contains(one.value().substring(ApiKeyValues.PREFIX.length())), "значение без префикса в тексте");
            assertFalse(text.contains(HexFormat.of().formatHex(ApiKeyValues.hash(one.value()))), "хеш значения в тексте");
        }
        for (String value : values) {
            assertFalse(text.contains(value), "значение ключа в тексте");
        }
    }
}
