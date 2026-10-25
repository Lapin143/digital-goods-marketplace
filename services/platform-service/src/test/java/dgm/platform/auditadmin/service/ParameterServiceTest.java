package dgm.platform.auditadmin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.time.ManualClock;
import dgm.platform.auditadmin.domain.Administrator;
import dgm.platform.auditadmin.domain.PlatformParameter;
import dgm.platform.auditadmin.testing.FakeParameterTransaction;
import dgm.platform.auditadmin.testing.FakeParameterTransaction.Published;
import dgm.platform.auditadmin.testing.InMemoryParameters;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Правка параметров: версия, границы, INV-07 в обе стороны, порядок блокировок, запись аудита и config.changed в одной транзакции. */
@Tag("TC-806")
@Tag("TC-807")
class ParameterServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-25T15:00:00Z");
    private static final UUID ADMIN_ID = UUID.fromString("c3d4e5f6-0718-4293-a4b5-c6d7e8f90a1b");
    private static final Administrator ADMIN = new Administrator(ADMIN_ID, "203.0.113.50");
    private static final String SESSION = ParameterService.SESSION;
    private static final String RESERVE = ParameterService.RESERVE;
    private static final Set<String> AUDIT_FIELDS = Set.of("occurredAt", "actorId", "actorRole", "action", "objectType", "objectId", "changes", "actorIp",
            "actorIpHashed", "correlationId");

    private InMemoryParameters parameters;
    private FakeParameterTransaction transaction;
    private SimpleMeterRegistry metrics;
    private ParameterService service;

    @BeforeEach
    void setUp() {
        ManualClock clock = new ManualClock(NOW);
        parameters = InMemoryParameters.seeded(clock);
        transaction = new FakeParameterTransaction();
        metrics = new SimpleMeterRegistry();
        service = new ParameterService(parameters, transaction, clock, metrics);
    }

    private double count(String result) {
        return metrics.counter(ParameterService.METRIC, "result", result).count();
    }

    private static ProblemException failure(Runnable action) {
        return assertThrows(ProblemException.class, action::run);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> errors(ProblemException e) {
        return (List<Map<String, Object>>) e.extensions().get("errors");
    }

    @Test
    void changeWritesTheValueTheAuditRecordAndTheConfigEventTogether() {
        PlatformParameter changed = service.set(ADMIN, RESERVE, 1, 1200);

        assertEquals(1200, changed.value());
        assertEquals(2, changed.version());
        assertEquals(ADMIN_ID, changed.updatedBy());
        assertEquals(List.of("audit.recorded", "config.changed"), transaction.published().stream().map(Published::eventType).toList());

        Published audit = transaction.published("audit.recorded").get(0);
        assertEquals("audit.events", audit.topic());
        assertEquals("AuditRecord", audit.aggregateType());
        assertEquals(RESERVE, audit.aggregateId());
        assertEquals(AUDIT_FIELDS, audit.data().keySet());
        assertEquals("parameter.changed", audit.data().get("action"));
        assertEquals("parameter", audit.data().get("objectType"));
        assertEquals(RESERVE, audit.data().get("objectId"));
        assertEquals("admin", audit.data().get("actorRole"));
        assertEquals(ADMIN_ID.toString(), audit.data().get("actorId"));
        assertEquals("203.0.113.50", audit.data().get("actorIp"));
        assertEquals(false, audit.data().get("actorIpHashed"));
        assertEquals(List.of(Map.of("field", "value", "personal", false, "before", 900L, "after", 1200L)), audit.data().get("changes"),
                "значения до и после как есть, числами (NFT-5.3)");

        Published config = transaction.published("config.changed").get(0);
        assertEquals("platform.config", config.topic());
        assertEquals("Parameter", config.aggregateType());
        assertEquals(RESERVE, config.aggregateId(), "ключ записи Kafka равен ключу параметра");
        assertEquals(1, config.schemaVersion());
        assertEquals(Map.of("key", RESERVE, "value", 1200L, "unit", "seconds", "version", 2, "changedBy", ADMIN_ID.toString()), config.data());
        assertEquals(1.0, count("changed"));
    }

    @Test
    void staleVersionIsRefusedWithFourHundredAndTwelveAndNothingChanges() {
        service.set(ADMIN, "orders.max-unpaid", 1, 5);

        ProblemException e = failure(() -> service.set(ADMIN, "orders.max-unpaid", 1, 7));

        assertEquals(ProblemType.VERSION_CONFLICT, e.type());
        assertEquals(5, parameters.stored("orders.max-unpaid").value());
        assertEquals(2, parameters.stored("orders.max-unpaid").version());
        assertEquals(2, transaction.published().size(), "только события первой правки");
        assertEquals(1.0, count("conflict"));
    }

    @Test
    void unknownKeyIsNotFound() {
        assertEquals(ProblemType.NOT_FOUND, failure(() -> service.set(ADMIN, "no.such-key", 1, 5)).type());
        assertEquals(ProblemType.NOT_FOUND, failure(() -> service.get("no.such-key")).type());
        assertTrue(transaction.published().isEmpty());
    }

    @Test
    void valuesOutsideTheBoundsAreRefusedWithTheBounds() {
        for (long bad : new long[] {0, -1, Long.MIN_VALUE, 21, Long.MAX_VALUE}) {
            ProblemException e = failure(() -> service.set(ADMIN, "orders.max-unpaid", 1, bad));
            assertEquals(ProblemType.VALIDATION_FAILED, e.type(), String.valueOf(bad));
            Map<String, Object> error = errors(e).get(0);
            assertEquals("/value", error.get("pointer"));
            assertEquals("out_of_range", error.get("code"));
            assertTrue(((String) error.get("detail")).contains("от 1 до 20"), "ответ называет границы: " + error.get("detail"));
        }
        assertEquals(3, parameters.stored("orders.max-unpaid").value());
        assertEquals(1, parameters.stored("orders.max-unpaid").version());
        assertTrue(transaction.published().isEmpty());
        assertEquals(5.0, count("invalid"));
    }

    @Test
    void boundsAreInclusive() {
        assertEquals(1, service.set(ADMIN, "orders.max-unpaid", 1, 1).value());
        assertEquals(20, service.set(ADMIN, "orders.max-unpaid", 2, 20).value());
    }

    /** INV-07, первая сторона: поднять срок сессии до срока резерва или выше нельзя. */
    @Test
    void sessionMustStayStrictlyShorterThanTheReserve() {
        for (long bad : new long[] {900, 901, 3599}) {
            ProblemException e = failure(() -> service.set(ADMIN, SESSION, 1, bad));
            assertEquals(ProblemType.VALIDATION_FAILED, e.type(), String.valueOf(bad));
            String detail = (String) errors(e).get(0).get("detail");
            assertTrue(detail.contains(RESERVE) && detail.contains("900"), "названы парный параметр и его значение: " + detail);
        }
        assertEquals(899, service.set(ADMIN, SESSION, 1, 899).value(), "на единицу меньше резерва можно");
    }

    /** INV-07, вторая сторона: опустить срок резерва до срока сессии или ниже нельзя. */
    @Test
    void reserveMustStayStrictlyLongerThanTheSession() {
        for (long bad : new long[] {720, 719, 300}) {
            ProblemException e = failure(() -> service.set(ADMIN, RESERVE, 1, bad));
            assertEquals(ProblemType.VALIDATION_FAILED, e.type(), String.valueOf(bad));
            String detail = (String) errors(e).get(0).get("detail");
            assertTrue(detail.contains(SESSION) && detail.contains("720"), "названы парный параметр и его значение: " + detail);
        }
        assertEquals(721, service.set(ADMIN, RESERVE, 1, 721).value(), "на единицу больше сессии можно");
        assertEquals(1, parameters.stored(SESSION).version(), "сессия не менялась");
    }

    @Test
    void theLimitIsTheCurrentValueOfThePartnerSoTheOrderOfEditsDoesNotMatter() {
        assertEquals(1200, service.set(ADMIN, RESERVE, 1, 1200).value());
        assertEquals(1100, service.set(ADMIN, SESSION, 1, 1100).value(), "после подъёма резерва сессию можно поднять выше прежнего резерва");
        ProblemException e = failure(() -> service.set(ADMIN, RESERVE, 2, 1100));
        assertEquals(ProblemType.VALIDATION_FAILED, e.type(), "резерв ниже новой сессии нельзя");
    }

    @Test
    void thePairIsLockedInTheSameOrderWhicheverParameterIsEdited() {
        service.set(ADMIN, RESERVE, 1, 1000);
        service.set(ADMIN, SESSION, 1, 800);
        service.set(ADMIN, "orders.max-unpaid", 1, 4);

        assertEquals(List.of(List.of(SESSION, RESERVE), List.of(SESSION, RESERVE), List.of("orders.max-unpaid")), parameters.locks(),
                "у пары блокировка идёт сначала по сессии, потом по резерву, у остальных по одной строке");
    }

    @Test
    void theSameValueChangesNothingAndWritesNothing() {
        PlatformParameter same = service.set(ADMIN, "orders.max-unpaid", 1, 3);

        assertEquals(1, same.version(), "версия прежняя");
        assertNull(same.updatedBy());
        assertTrue(transaction.published().isEmpty(), "ни записи аудита, ни события");
        assertEquals(1.0, count("unchanged"));
        assertEquals(0.0, count("changed"));
    }

    @Test
    void staleVersionIsCheckedBeforeTheValue() {
        ProblemException e = failure(() -> service.set(ADMIN, "orders.max-unpaid", 7, 0));
        assertEquals(ProblemType.VERSION_CONFLICT, e.type());
    }

    @Test
    void failureWhilePublishingLeavesNoEventAtAll() {
        transaction.failPublishWith(new IllegalStateException("Outbox недоступен"));

        assertThrows(IllegalStateException.class, () -> service.set(ADMIN, "orders.max-unpaid", 1, 5));

        assertTrue(transaction.published().isEmpty(), "запись аудита и config.changed фиксируются вместе или не фиксируются совсем");
    }

    @Test
    void tableConstraintIsTheSpareControlAndAlsoAnswersFourHundredAndTwentyTwo() {
        parameters.failUpdateWith(new org.springframework.dao.DataIntegrityViolationException("ck_platform_parameter_session_vs_reserve"));

        ProblemException e = failure(() -> service.set(ADMIN, SESSION, 1, 800));

        assertEquals(ProblemType.VALIDATION_FAILED, e.type());
        assertFalse(((String) errors(e).get(0).get("detail")).contains("ck_platform"), "имя ограничения в ответ не идёт");
        assertTrue(transaction.published().isEmpty());
    }

    @Test
    void listAndGetReturnTheStoredParameters() {
        assertEquals(3, service.list().size());
        assertSame(parameters.stored(RESERVE), service.get(RESERVE));
    }

    @Test
    void reissueSendsEveryCurrentValueWithItsVersionAndTheSystemAsTheAuthorOfTheSeed() {
        service.set(ADMIN, RESERVE, 1, 1200);
        int before = transaction.published().size();

        int reissued = service.reissue();

        assertEquals(3, reissued);
        List<Published> events = transaction.published("config.changed");
        assertEquals(1 + 3, events.size());
        Map<String, Object> reserve = events.stream().skip(1).filter(p -> p.aggregateId().equals(RESERVE)).findFirst().orElseThrow().data();
        assertEquals(Map.of("key", RESERVE, "value", 1200L, "unit", "seconds", "version", 2, "changedBy", ADMIN_ID.toString()), reserve);
        Map<String, Object> seed = events.stream().skip(1).filter(p -> p.aggregateId().equals("orders.max-unpaid")).findFirst().orElseThrow().data();
        assertEquals("00000000-0000-0000-0000-000000000000", seed.get("changedBy"), "у начального значения автор система");
        assertEquals(1, seed.get("version"));
        assertEquals(before + 3, transaction.published().size(), "только config.changed, записей аудита переиздание не пишет");
    }

    @Test
    void reissueYieldsToAnotherInstanceHoldingTheLock() {
        parameters.reissueLockTakenByAnotherInstance();
        assertEquals(0, service.reissue());
        assertTrue(transaction.published().isEmpty());
    }
}
