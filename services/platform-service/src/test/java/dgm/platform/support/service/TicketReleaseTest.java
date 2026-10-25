package dgm.platform.support.service;

import static dgm.platform.support.testing.SupportWorld.BUYER;
import static dgm.platform.support.testing.SupportWorld.OPERATOR;
import static dgm.platform.support.testing.SupportWorld.OPERATOR_2;
import static dgm.platform.support.testing.SupportWorld.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.platform.support.domain.Operator;
import dgm.platform.support.domain.QueueDepth;
import dgm.platform.support.domain.QueueFilter;
import dgm.platform.support.domain.StaffTicket;
import dgm.platform.support.testing.RecordingOutbox;
import dgm.platform.support.testing.SupportWorld;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Возврат в очередь (SM-07/T3, Р-163) и метрика очереди: вернуть может только оператор, у которого обращение в работе, время создания не
 * меняется, обращение встаёт на прежнее место; метрики совпадают с числом открытых обращений (US-7.2, сценарий 5, NFT-6.1).
 */
@Tag("TC-706")
class TicketReleaseTest {

    private static final Operator FIRST = new Operator(OPERATOR, "203.0.113.7");
    private static final Operator SECOND = new Operator(OPERATOR_2, "203.0.113.8");

    private SupportWorld world;
    private UUID older;
    private UUID newer;

    @BeforeEach
    void setUp() {
        world = new SupportWorld();
        older = world.tickets.create(BUYER, world.issuedOrder(BUYER, T0.minus(Duration.ofHours(1)))).id();
        world.clock.advance(Duration.ofMinutes(10));
        newer = world.tickets.create(BUYER, world.issuedOrder(BUYER, T0.minus(Duration.ofHours(1)))).id();
        world.clock.advance(Duration.ofMinutes(5));
    }

    private List<UUID> queueIds() {
        return world.queue.list(new QueueFilter(List.of("created"), Optional.empty(), OptionalLong.empty(), 20, Optional.empty())).items().stream()
                .map(StaffTicket::id).toList();
    }

    @Test
    void releaseClearsTheOperatorAndKeepsTheCreationTime() {
        world.queue.take(FIRST, older);

        StaffTicket released = world.queue.release(FIRST, older);

        assertEquals("created", released.status());
        assertEquals(Optional.empty(), released.operatorId());
        assertEquals(Optional.empty(), released.takenAt());
        assertEquals(T0, released.createdAt(), "время создания не меняется");
        assertEquals(List.of(older, newer), queueIds(), "обращение встало на прежнее место, а не в конец очереди");
    }

    @Test
    void releaseWritesOneAuditRecord() {
        world.queue.take(FIRST, older);
        world.queue.release(FIRST, older);

        List<RecordingOutbox.Event> records = world.outbox.events("audit.recorded");
        assertEquals(2, records.size());
        Map<String, Object> data = records.get(1).data();
        assertEquals("support.ticket_released", data.get("action"));
        assertEquals("support-operator", data.get("actorRole"));
        assertEquals(OPERATOR.toString(), data.get("actorId"));
        assertEquals("ticket", data.get("objectType"));
        assertEquals(older.toString(), data.get("objectId"));
        Map<String, Object> operatorChange = new LinkedHashMap<>();
        operatorChange.put("field", "operatorId");
        operatorChange.put("personal", false);
        operatorChange.put("before", OPERATOR.toString());
        operatorChange.put("after", null);
        assertTrue(((List<?>) data.get("changes")).contains(operatorChange));
    }

    @Test
    void anotherOperatorCannotReleaseIt() {
        world.queue.take(FIRST, older);

        ProblemException refused = assertThrows(ProblemException.class, () -> world.queue.release(SECOND, older));

        assertEquals(ProblemType.STATE_CONFLICT, refused.type());
        assertEquals(Optional.of(OPERATOR), world.queue.get(older).operatorId());
        assertEquals(1, world.outbox.events("audit.recorded").size(), "записи о возврате нет");
    }

    @Test
    void aTicketInTheQueueIsNotReleased() {
        assertEquals(ProblemType.STATE_CONFLICT, assertThrows(ProblemException.class, () -> world.queue.release(FIRST, older)).type());
        assertTrue(world.outbox.events("audit.recorded").isEmpty());
    }

    @Test
    void anUnknownTicketIsNotFound() {
        assertEquals(ProblemType.NOT_FOUND, assertThrows(ProblemException.class, () -> world.queue.release(FIRST, UUID.randomUUID())).type());
    }

    @Test
    void metricsFollowTheRealNumberOfOpenTickets() {
        QueueDepth start = world.queueMetrics.refresh();

        assertEquals(2, start.created());
        assertEquals(0, start.inProgress());
        assertEquals(2, gauge("created"));
        assertEquals(0, gauge("in_progress"));
        assertEquals(900, oldestAge(), "самое старое обращение ждёт 15 минут");

        world.queue.take(FIRST, older);
        world.queueMetrics.refresh();
        assertEquals(1, gauge("created"));
        assertEquals(1, gauge("in_progress"));
        assertEquals(300, oldestAge(), "теперь самое старое в очереди то, что создано позже");

        world.queue.release(FIRST, older);
        world.queueMetrics.refresh();
        assertEquals(2, gauge("created"));
        assertEquals(0, gauge("in_progress"));
        assertEquals(900, oldestAge());
    }

    @Test
    void metricsAreRecountedFromTheTablesNotAccumulated() {
        world.queueMetrics.refresh();
        // Обращение решено мимо службы очереди (доставка подтвердилась): события о нём метрика не видит, но следующий проход исправляет значение
        world.repository.resolveByOperator(older, "resend", world.clock.instant());

        assertEquals(2, gauge("created"), "до прохода значение прежнее");
        world.queueMetrics.refresh();
        assertEquals(1, gauge("created"));
    }

    @Test
    void anEmptyQueueHasNoAge() {
        world.repository.resolveByOperator(older, "resend", world.clock.instant());
        world.repository.resolveByOperator(newer, "resend", world.clock.instant());

        world.queueMetrics.refresh();

        assertEquals(0, gauge("created"));
        assertEquals(0, oldestAge());
    }

    private double gauge(String status) {
        return world.metrics.get(QueueMetrics.TICKETS).tag("status", status).gauge().value();
    }

    private double oldestAge() {
        return world.metrics.get(QueueMetrics.OLDEST_AGE).gauge().value();
    }
}
