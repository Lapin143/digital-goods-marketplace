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
import dgm.platform.support.domain.StaffTicket;
import dgm.platform.support.testing.RecordingOutbox;
import dgm.platform.support.testing.SupportWorld;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Взятие обращения в работу (SM-07/T2, Р-163): переход «создано» в «в работе» с оператором, один победитель из двух операторов, запись
 * аудита только у настоящего взятия. Гонку двух параллельных запросов на настоящей базе проверяет {@code PlatformServiceIT}.
 */
@Tag("TC-705")
class TicketTakeTest {

    private static final Operator FIRST = new Operator(OPERATOR, "203.0.113.7");
    private static final Operator SECOND = new Operator(OPERATOR_2, "203.0.113.8");

    private SupportWorld world;
    private UUID order;
    private UUID ticket;

    @BeforeEach
    void setUp() {
        world = new SupportWorld();
        order = world.issuedOrder(BUYER, T0.minus(Duration.ofHours(1)));
        ticket = world.tickets.create(BUYER, order).id();
        world.clock.advance(Duration.ofMinutes(3));
    }

    @Test
    void takingMovesTheTicketIntoWorkWithTheOperator() {
        StaffTicket taken = world.queue.take(FIRST, ticket);

        assertEquals("in_progress", taken.status());
        assertEquals(Optional.of(OPERATOR), taken.operatorId());
        assertEquals(Optional.of(T0.plus(Duration.ofMinutes(3))), taken.takenAt());
        assertEquals(T0, taken.createdAt(), "время создания не меняется");
        assertEquals(1, world.metrics.get(QueueService.METRIC_NAME).tag("action", "take").tag("result", "ok").counter().count());
    }

    @Test
    void takingWritesOneAuditRecordWithTheActorTheTicketAndTheOrder() {
        world.queue.take(FIRST, ticket);

        List<RecordingOutbox.Event> records = world.outbox.events("audit.recorded");
        assertEquals(1, records.size());
        RecordingOutbox.Event record = records.get(0);
        assertEquals("audit.events", record.topic());
        assertEquals("AuditRecord", record.aggregateType());
        assertEquals(ticket.toString(), record.aggregateId());
        assertEquals(1, record.schemaVersion());
        Map<String, Object> data = record.data();
        assertEquals("support.ticket_taken", data.get("action"));
        assertEquals("support-operator", data.get("actorRole"));
        assertEquals(OPERATOR.toString(), data.get("actorId"));
        assertEquals("203.0.113.7", data.get("actorIp"));
        assertEquals(false, data.get("actorIpHashed"));
        assertEquals("ticket", data.get("objectType"));
        assertEquals(ticket.toString(), data.get("objectId"));
        assertEquals("2026-10-25T12:03:00.000Z", data.get("occurredAt"));
        assertEquals(List.of(
                Map.of("field", "status", "personal", false, "before", "created", "after", "in_progress"),
                change("operatorId", null, OPERATOR.toString()),
                change("orderId", null, order.toString())), data.get("changes"));
    }

    private static Map<String, Object> change(String field, Object before, Object after) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("field", field);
        change.put("personal", false);
        change.put("before", before);
        change.put("after", after);
        return change;
    }

    @Test
    void theSecondOperatorGetsAConflictAndNothingChanges() {
        world.queue.take(FIRST, ticket);

        ProblemException refused = assertThrows(ProblemException.class, () -> world.queue.take(SECOND, ticket));

        assertEquals(ProblemType.STATE_CONFLICT, refused.type());
        StaffTicket now = world.queue.get(ticket);
        assertEquals(Optional.of(OPERATOR), now.operatorId(), "обращение осталось у первого");
        assertEquals(1, world.outbox.events("audit.recorded").size(), "запись аудита только у настоящего взятия");
        assertEquals(1, world.metrics.get(QueueService.METRIC_NAME).tag("action", "take").tag("result", "conflict").counter().count());
    }

    @Test
    void theSameOperatorCannotTakeItTwice() {
        world.queue.take(FIRST, ticket);

        assertEquals(ProblemType.STATE_CONFLICT, assertThrows(ProblemException.class, () -> world.queue.take(FIRST, ticket)).type());
    }

    @Test
    void aSolvedTicketIsNotTaken() {
        world.events.onDeliveryDelivered(order, T0.plusSeconds(60));

        ProblemException refused = assertThrows(ProblemException.class, () -> world.queue.take(FIRST, ticket));

        assertEquals(ProblemType.STATE_CONFLICT, refused.type());
        assertTrue(world.outbox.events("audit.recorded").isEmpty());
    }

    @Test
    void anUnknownTicketIsNotFound() {
        ProblemException refused = assertThrows(ProblemException.class, () -> world.queue.take(FIRST, UUID.randomUUID()));

        assertEquals(ProblemType.NOT_FOUND, refused.type());
        assertEquals(1, world.metrics.get(QueueService.METRIC_NAME).tag("action", "take").tag("result", "not-found").counter().count());
    }

    @Test
    void cardOfAnUnknownTicketIsNotFound() {
        assertEquals(ProblemType.NOT_FOUND, assertThrows(ProblemException.class, () -> world.queue.get(UUID.randomUUID())).type());
    }
}
