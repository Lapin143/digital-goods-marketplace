package dgm.platform.auditadmin.service;

import static dgm.platform.auditadmin.testing.AuditViews.BASE;
import static dgm.platform.auditadmin.testing.AuditViews.id;
import static dgm.platform.auditadmin.testing.AuditViews.record;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.outbox.AuditRecords;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.time.ManualClock;
import dgm.platform.auditadmin.domain.AuditFilter;
import dgm.platform.auditadmin.domain.AuditPage;
import dgm.platform.auditadmin.domain.AuditRecordView;
import dgm.platform.auditadmin.domain.Viewer;
import dgm.platform.auditadmin.testing.InMemoryAuditReader;
import dgm.platform.auditadmin.testing.RecordingViewOutbox;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Чтение журнала это действие (Р-166): запись о просмотре до отдачи данных, отказ 503 без неё, листание без новых записей. */
@Tag("TC-810")
class AuditViewServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-25T15:00:00Z");
    private static final Viewer ADMIN = new Viewer(id(1), "admin", "203.0.113.50");

    private InMemoryAuditReader reader;
    private RecordingViewOutbox outbox;
    private SimpleMeterRegistry metrics;
    private AuditViewService service;

    @BeforeEach
    void setUp() {
        reader = new InMemoryAuditReader();
        outbox = new RecordingViewOutbox();
        metrics = new SimpleMeterRegistry();
        service = new AuditViewService(reader, outbox, new ManualClock(NOW), metrics);
        for (int n = 1; n <= 5; n++) {
            reader.add(record(n));
        }
    }

    private static AuditFilter filter(int limit, Optional<AuditFilter.After> after) {
        return new AuditFilter(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), limit, after);
    }

    private double count(String result) {
        return metrics.counter(AuditViewService.METRIC, "result", result).count();
    }

    @Test
    void firstPageRecordsTheViewBeforeAnyDataIsReturned() {
        AuditPage page = service.list(ADMIN, filter(20, Optional.empty()));

        assertEquals(5, page.items().size());
        assertEquals(1, outbox.events().size());
        RecordingViewOutbox.Event event = outbox.events().get(0);
        assertEquals(AuditRecords.TOPIC, event.topic());
        assertEquals(AuditRecords.AGGREGATE, event.aggregateType());
        assertEquals(ADMIN.id().toString(), event.aggregateId());
        assertEquals(AuditRecords.EVENT, event.type());
        assertEquals(AuditRecords.SCHEMA_VERSION, event.schemaVersion());
        assertEquals(1.0, count("recorded"));
    }

    @Test
    void theRecordNamesWhoAndWhenButNeitherTheSearchNorTheResult() {
        service.list(ADMIN, new AuditFilter(Optional.of(id(77)), Optional.of("seller"), Optional.of("secret-object"), Optional.of("seller.approved"),
                Optional.empty(), Optional.empty(), 20, Optional.empty()));

        Map<String, Object> data = outbox.events().get(0).data();
        assertEquals("audit.viewed", data.get("action"));
        assertEquals("audit-log", data.get("objectType"));
        assertEquals("journal", data.get("objectId"));
        assertEquals(ADMIN.id().toString(), data.get("actorId"));
        assertEquals("admin", data.get("actorRole"));
        assertEquals("203.0.113.50", data.get("actorIp"));
        assertEquals(false, data.get("actorIpHashed"));
        assertEquals("2026-10-25T15:00:00.000Z", data.get("occurredAt"));
        assertEquals(List.of(), data.get("changes"));
        String text = data.toString();
        assertFalse(text.contains("secret-object") || text.contains(id(77).toString()), "параметры поиска в запись не попадают");
    }

    @Test
    void failedRecordMeansNoDataAndFiveHundredAndThree() {
        outbox.failWith(new IllegalStateException("Outbox недоступен"));

        ProblemException e = assertThrows(ProblemException.class, () -> service.list(ADMIN, filter(20, Optional.empty())));

        assertEquals(ProblemType.DEPENDENCY_UNAVAILABLE, e.type());
        assertTrue(e.headers().containsKey("Retry-After"));
        assertTrue(reader.requests().isEmpty(), "журнал не читается, пока запись о просмотре не зафиксирована");
        assertEquals(1.0, count("failed"));
        assertEquals(0.0, count("recorded"));
    }

    @Test
    void nextPagesByCursorDoNotRecordAgain() {
        AuditPage first = service.list(ADMIN, filter(2, Optional.empty()));
        assertEquals(List.of(id(5), id(4)), first.items().stream().map(AuditRecordView::recordId).toList());
        assertTrue(first.next().isPresent());

        AuditPage second = service.list(ADMIN, filter(2, first.next()));
        assertEquals(List.of(id(3), id(2)), second.items().stream().map(AuditRecordView::recordId).toList());
        AuditPage third = service.list(ADMIN, filter(2, second.next()));
        assertEquals(List.of(id(1)), third.items().stream().map(AuditRecordView::recordId).toList());
        assertTrue(third.next().isEmpty(), "на последней странице курсора нет");

        assertEquals(1, outbox.events().size(), "одна запись на весь просмотр");
        assertEquals(2.0, count("continued"));
    }

    @Test
    void cursorPointsAtTheLastShownRecord() {
        AuditPage first = service.list(ADMIN, filter(2, Optional.empty()));

        AuditFilter.After next = first.next().orElseThrow();
        assertEquals(id(4), next.id());
        assertEquals(BASE.plusSeconds(4), next.occurredAt());
    }

    @Test
    void pageIsExactlyFullWithoutAFollowingRecordHasNoCursor() {
        AuditPage page = service.list(ADMIN, filter(5, Optional.empty()));
        assertEquals(5, page.items().size());
        assertTrue(page.next().isEmpty());
    }

    @Test
    void emptyJournalStillRecordsTheView() {
        InMemoryAuditReader empty = new InMemoryAuditReader();
        AuditPage page = new AuditViewService(empty, outbox, new ManualClock(NOW), metrics).list(ADMIN, filter(20, Optional.empty()));

        assertTrue(page.items().isEmpty());
        assertEquals(1, outbox.events().size());
    }

    @Test
    void unreadableJournalIsFiveHundredAndThreeWithoutTheCauseInTheAnswer() {
        reader.failWith(new IllegalStateException("password=secret"));

        ProblemException e = assertThrows(ProblemException.class, () -> service.list(ADMIN, filter(20, Optional.empty())));

        assertEquals(ProblemType.DEPENDENCY_UNAVAILABLE, e.type());
        assertFalse(String.valueOf(e.getMessage()).contains("secret"));
        assertEquals(1, outbox.events().size(), "просмотр записан: журнал открывали, даже если прочитать не вышло");
    }

    @Test
    void viewerWithoutAddressIsRecordedWithAnEmptyOne() {
        service.list(new Viewer(id(1), "admin", ""), filter(20, Optional.empty()));
        assertEquals("", outbox.events().get(0).data().get("actorIp"));
    }
}
